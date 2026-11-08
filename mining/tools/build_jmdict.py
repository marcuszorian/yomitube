#!/usr/bin/env python3
"""Build the on-device JMdict SQLite database used by org.schabi.newpipe.mining.Dictionary.

Downloads the *full* jmdict-eng JSON release of jmdict-simplified
(https://github.com/scriptin/jmdict-simplified) and converts it into a compact
SQLite file at mining/src/main/assets/jmdict.db.

The full dictionary is used on purpose: the "common" subset lacks a lot of
anime/drama vocabulary (e.g. 模擬戦).

Usage (from the repo root):
    python3 tools/build_jmdict.py                 # latest release
    python3 tools/build_jmdict.py --json FILE     # use an already-extracted jmdict-eng-*.json
    python3 tools/build_jmdict.py --tag 3.6.2+20261102210542

Only the Python standard library is used.

Schema (read by Dictionary.java -- keep in sync):
    meta(key TEXT PRIMARY KEY, value TEXT)
        schema=1, jmdict_version, dict_date
    entry(id INTEGER PRIMARY KEY, senses TEXT)
        senses: senses separated by U+001E; inside a sense, the POS list and the
        gloss list are separated by U+001F; POS tags (JMdict codes such as
        "n", "v1", "vt") are separated by ","; glosses by U+001D.
    form(text TEXT, prio INTEGER, entry_id INTEGER, headword TEXT, reading TEXT)
        WITHOUT ROWID, PRIMARY KEY (text, prio, entry_id).
        One row per lookup key (every kanji form and every kana form).
        headword/reading are what should be shown/mined when the entry is found
        via this key; NULL means "same as text" (saves ~40% of the file).
        prio: lower = better (0 = common form, 1 = form of a common entry,
        2 = uncommon, 3 = rare/irregular/search-only form).
"""

import argparse
import io
import json
import os
import sqlite3
import sys
import tarfile
import tempfile
import urllib.request

REPO = "scriptin/jmdict-simplified"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "src", "main", "assets", "jmdict.db")

SENSE_SEP = "\x1e"
FIELD_SEP = "\x1f"
GLOSS_SEP = "\x1d"

# Form tags that mark irregular/rare/search-only spellings.
BAD_FORM_TAGS = {"iK", "ik", "io", "oK", "ok", "rK", "rk", "sK", "sk"}


def find_asset(tag):
    url = f"https://api.github.com/repos/{REPO}/releases/" + (f"tags/{tag}" if tag else "latest")
    with urllib.request.urlopen(url) as r:
        rel = json.load(r)
    for a in rel["assets"]:
        n = a["name"]
        if n.startswith("jmdict-eng-") and not n.startswith("jmdict-eng-common") and n.endswith(".json.tgz"):
            return a["browser_download_url"]
    sys.exit(f"No full jmdict-eng .json.tgz asset in release {rel.get('tag_name')}")


def download_json(tag):
    url = find_asset(tag)
    print(f"Downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        data = r.read()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
        for m in tf.getmembers():
            if m.isfile() and m.name.endswith(".json"):
                return json.load(tf.extractfile(m))
    sys.exit("No .json inside the downloaded archive")


def applies(lst, text):
    return "*" in lst or text in lst


def build(d, out):
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        PRAGMA page_size=4096;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE entry(id INTEGER PRIMARY KEY, senses TEXT NOT NULL);
        CREATE TABLE form(text TEXT NOT NULL, prio INTEGER NOT NULL, entry_id INTEGER NOT NULL,
                          headword TEXT, reading TEXT,
                          PRIMARY KEY(text, prio, entry_id)) WITHOUT ROWID;
        -- Android needs this table to open the DB without NO_LOCALIZED_COLLATORS.
        CREATE TABLE android_metadata(locale TEXT);
        INSERT INTO android_metadata VALUES('en_US');
        """
    )
    db.execute("INSERT INTO meta VALUES('schema','1')")
    db.execute("INSERT INTO meta VALUES('jmdict_version',?)", (d.get("version", ""),))
    db.execute("INSERT INTO meta VALUES('dict_date',?)", (d.get("dictDate", ""),))

    entries = []
    forms = []
    for w in d["words"]:
        eid = int(w["id"])
        senses = []
        for s in w["sense"]:
            glosses = [g["text"].replace(GLOSS_SEP, " ") for g in s["gloss"] if g.get("lang", "eng") == "eng"]
            if not glosses:
                continue
            senses.append(",".join(s["partOfSpeech"]) + FIELD_SEP + GLOSS_SEP.join(glosses))
        if not senses:
            continue
        entries.append((eid, SENSE_SEP.join(senses)))

        entry_common = any(k["common"] for k in w["kanji"]) or any(k["common"] for k in w["kana"])
        # "uk" = usually written in kana: then kana lookups should mine the kana form.
        all_uk = all("uk" in s["misc"] for s in w["sense"])

        def prio(f):
            if BAD_FORM_TAGS.intersection(f["tags"]):
                return 3
            if f["common"]:
                return 0
            return 1 if entry_common else 2

        good_kanji = [k for k in w["kanji"] if not BAD_FORM_TAGS.intersection(k["tags"])] or w["kanji"]

        def kana_for(ktext):
            cands = [r for r in w["kana"] if applies(r["appliesToKanji"], ktext)]
            cands.sort(key=prio)
            return cands[0]["text"] if cands else w["kana"][0]["text"]

        for k in w["kanji"]:
            forms.append((k["text"], prio(k), eid, k["text"], kana_for(k["text"])))
        for r in w["kana"]:
            headword = r["text"]
            nokanji = "nokanji" in r["tags"] or not r["appliesToKanji"]
            if w["kanji"] and not nokanji and not all_uk:
                ks = [k for k in good_kanji if applies(r["appliesToKanji"], k["text"])]
                ks.sort(key=prio)
                if ks:
                    headword = ks[0]["text"]
            forms.append((r["text"], prio(r), eid, headword, r["text"]))

    db.executemany("INSERT INTO entry VALUES(?,?)", entries)
    forms.sort()
    db.executemany("INSERT OR IGNORE INTO form VALUES(?,?,?,?,?)",
                   [(t, p, e, None if h == t else h, None if r == t else r) for t, p, e, h, r in forms])
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, out)
    print(f"Wrote {out}: {len(entries)} entries, {len(forms)} forms, "
          f"{os.path.getsize(out) / 1e6:.1f} MB", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", help="path to an extracted jmdict-eng-*.json (skips download)")
    ap.add_argument("--tag", help="jmdict-simplified release tag (default: latest)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    a = ap.parse_args()
    if a.json:
        with open(a.json, encoding="utf-8") as f:
            d = json.load(f)
    else:
        d = download_json(a.tag)
    if d.get("commonOnly"):
        sys.exit("Refusing to build from the common-only subset; use the full jmdict-eng release")
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    build(d, os.path.abspath(a.out))


if __name__ == "__main__":
    main()
