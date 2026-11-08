#!/usr/bin/env python3
"""Sanity-checks a jmdict.db built by build_jmdict.py before it is shipped in an APK.

Usage (from the repo root):
    python3 mining/tools/check_jmdict.py [path/to/jmdict.db]

Exits non-zero (with a one-line reason) if the schema is wrong, the dictionary looks
truncated, or well-known words no longer resolve to their usual meaning. On success, prints
"release_tag<TAB>dict_date" so CI can record which dictionary went into the build.

Only the Python standard library is used.
"""

import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(HERE, "..", "src", "main", "assets", "jmdict.db")

MIN_HEADWORDS = 150_000
# Lookup key -> text that must appear in one of its glosses.
KNOWN = {
    "食べる": "to eat",
    "歌舞伎": "kabuki",
    "行く": "to go",
    "たべる": "to eat",
}

GLOSS_SEP = "\x1d"
FIELD_SEP = "\x1f"
SENSE_SEP = "\x1e"


def fail(msg):
    print(f"check_jmdict: FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def glosses(senses):
    for sense in senses.split(SENSE_SEP):
        _, _, gl = sense.partition(FIELD_SEP)
        yield from gl.split(GLOSS_SEP)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DB
    if not os.path.isfile(path):
        fail(f"{path} does not exist")
    try:
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        meta = dict(db.execute("SELECT key, value FROM meta"))
    except sqlite3.Error as e:
        fail(f"cannot read {path}: {e}")

    if meta.get("schema") != "1":
        fail(f"meta.schema is {meta.get('schema')!r}, expected '1' (Dictionary.java reads schema 1)")

    headwords = db.execute(
        "SELECT count(DISTINCT coalesce(headword, text)) FROM form").fetchone()[0]
    if headwords < MIN_HEADWORDS:
        fail(f"only {headwords} headwords, expected at least {MIN_HEADWORDS}")

    for key, want in KNOWN.items():
        rows = db.execute(
            "SELECT e.senses FROM form f JOIN entry e ON e.id = f.entry_id"
            " WHERE f.text = ? ORDER BY f.prio, f.entry_id LIMIT 20", (key,)).fetchall()
        if not rows:
            fail(f"no entry for {key}")
        if not any(want in g for (senses,) in rows for g in glosses(senses)):
            fail(f"{key} does not mean {want!r} any more")

    print(f"check_jmdict: OK: {headwords} headwords, release {meta.get('release_tag') or '?'}, "
          f"dated {meta.get('dict_date') or '?'}", file=sys.stderr)
    print(f"{meta.get('release_tag', '')}\t{meta.get('dict_date', '')}")


if __name__ == "__main__":
    main()
