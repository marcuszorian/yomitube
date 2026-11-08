# Mining fork: R8 rules for the :mining module, applied to the app's release/continuous builds.
# Debug builds don't shrink, so problems here only show up in CI/release APKs: test those.

# Kuromoji loads its dictionary (*.bin next to the classes) with Class.getResourceAsStream relative
# to its own package; if R8 moves or merges its classes the lookup fails ("Could not load
# dictionaries"). The library is small next to its data, so keep it whole.
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# AnkiDroid's API talks to AnkiDroid through a content provider using its contract's names.
-keep class com.ichi2.anki.api.** { *; }
-dontwarn com.ichi2.anki.api.**

# ExoPlayer Transformer: MiningDecoderFactory lives in its package to reach package-private API.
-keep class com.google.android.exoplayer2.transformer.** { *; }
-dontwarn com.google.android.exoplayer2.transformer.**
