<h2 align="center"><b>YomiTube</b></h2>
<h4 align="center">Sentence mining for Japanese learners, inside a streaming front-end for Android.</h4>

> **YomiTube is neither affiliated with nor endorsed by TeamNewPipe or NewPipe e.V.**
> It is an independent fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe).
> Builds of YomiTube are not official NewPipe releases and are not compatible with them.

## What it adds

While a video plays, hold on a Japanese caption (or tap the mining button) to open a panel on that line:

- the sentence is split into words; tap one to look it up in a built-in Japanese–English dictionary
- the reading is added as furigana
- trim the sentence audio and preview it
- a screenshot of the video is captured
- **Add to Anki** creates a note in [AnkiDroid](https://github.com/ankidroid/Anki-Android) with the word, reading, meaning, sentence, picture and sentence audio

Captions come from the video's own Japanese subtitles (manual or auto-generated). Audio clips are cut from what the player has already downloaded where possible.

Set the AnkiDroid note type and deck under **Settings → Sentence mining**.

## Building

```
python3 mining/tools/build_jmdict.py   # downloads JMdict, writes mining/src/main/assets/jmdict.db
./gradlew assembleDebug
```

The mining feature lives in the `:mining` module; changes to NewPipe's own code are a few small hooks marked "Mining fork". YomiTube's icons and app-facing strings are in `app/src/fork/res`; after merging upstream, regenerate the strings with `python3 -I app/src/fork/make_strings.py app/src`.

## Credits and licences

- Based on [NewPipe](https://github.com/TeamNewPipe/NewPipe) by Team NewPipe, licensed under the [GNU GPL v3](LICENSE). YomiTube is distributed under the same licence; this repository is its source.
- Dictionary data: [JMdict](https://www.edrdg.org/wiki/index.php/JMdict-EDICT_Dictionary_Project), © the Electronic Dictionary Research and Development Group, used under [CC BY-SA 4.0](https://www.edrdg.org/edrdg/licence.html), converted with [jmdict-simplified](https://github.com/scriptin/jmdict-simplified).
- Word splitting: [Kuromoji](https://github.com/atilika/kuromoji) (Apache License 2.0) with the IPADIC dictionary; full notices are in the app under Settings → Sentence mining.
- [AnkiDroid API](https://github.com/ankidroid/Anki-Android/tree/main/api) (LGPL v3).

"NewPipe" is a registered trademark of NewPipe e.V. YouTube is a trademark of Google LLC.
