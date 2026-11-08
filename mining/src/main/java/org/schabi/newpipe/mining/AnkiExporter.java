package org.schabi.newpipe.mining;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import com.ichi2.anki.api.AddContentApi;
import com.ichi2.anki.api.NoteInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds mined cards to AnkiDroid through its {@link AddContentApi}.
 *
 * <p>Threading: every method except {@link #isInstalled}, {@link #hasPermission} and the pref
 * getters/setters talks to AnkiDroid's ContentProvider and should be called off the main thread.
 */
public final class AnkiExporter {

    /** Default-SharedPreferences key holding the selected note type id (stored as a String). */
    public static final String PREF_KEY_MODEL_ID = "ankiModelId";
    /** Default-SharedPreferences key holding the selected deck id (stored as a String). */
    public static final String PREF_KEY_DECK_ID = "ankiDeckId";

    public static final String PERMISSION = AddContentApi.READ_WRITE_PERMISSION;
    public static final String TAG = "newpipe-mining";

    public enum Status {
        OK,
        NOT_INSTALLED,
        NO_PERMISSION,
        /** AnkiDroid's API is disabled in its settings, or the provider is failing. */
        API_UNAVAILABLE,
        MODEL_NOT_CONFIGURED,
        DECK_NOT_CONFIGURED,
        /** A note type/deck id is configured but no longer exists in AnkiDroid. */
        MODEL_NOT_FOUND,
        DECK_NOT_FOUND,
        MISSING_FIELDS
    }

    public static final class StatusInfo {
        public final Status status;
        public final String message;

        StatusInfo(Status status, String message) {
            this.status = status;
            this.message = message;
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    /** A note type or deck, for pickers. */
    public static final class Item {
        public final long id;
        public final String name;

        Item(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    public static final class Result {
        public enum Kind {SUCCESS, DUPLICATE, ERROR}

        public final Kind kind;
        /** New note id on SUCCESS, id of the first existing note on DUPLICATE, otherwise -1. */
        public final long noteId;
        /** Human-readable message for all kinds. */
        public final String message;
        /** Existing notes with the same first field (DUPLICATE only, otherwise empty). */
        public final List<NoteInfo> duplicates;

        private Result(Kind kind, long noteId, String message, List<NoteInfo> duplicates) {
            this.kind = kind;
            this.noteId = noteId;
            this.message = message;
            this.duplicates = duplicates;
        }

        static Result success(long id) {
            return new Result(Kind.SUCCESS, id, "Card added to Anki", Collections.emptyList());
        }

        static Result duplicate(List<NoteInfo> dups) {
            return new Result(Kind.DUPLICATE, dups.get(0).getId(),
                    "Already in Anki (note " + dups.get(0).getId() + ")", dups);
        }

        static Result error(String message) {
            return new Result(Kind.ERROR, -1, message, Collections.emptyList());
        }
    }

    private final Context context;

    public AnkiExporter(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ---- availability --------------------------------------------------------------------

    @Nullable
    public String getAnkiPackage() {
        return AddContentApi.getAnkiDroidPackageName(context);
    }

    public boolean isInstalled() {
        return getAnkiPackage() != null;
    }

    public boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23
                || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Checks installation, permission, API access and the configured note type/deck. Blocking. */
    @WorkerThread
    @NonNull
    public StatusInfo checkStatus() {
        if (!isInstalled()) {
            return new StatusInfo(Status.NOT_INSTALLED, "AnkiDroid is not installed");
        }
        if (!hasPermission()) {
            return new StatusInfo(Status.NO_PERMISSION, "Permission to access AnkiDroid not granted");
        }
        AddContentApi api = new AddContentApi(context);
        Map<Long, String> models;
        Map<Long, String> decks;
        try {
            models = api.getModelList();
            decks = api.getDeckList();
        } catch (RuntimeException e) {
            return new StatusInfo(Status.API_UNAVAILABLE, apiUnavailableMessage(e));
        }
        if (models == null || decks == null) {
            return new StatusInfo(Status.API_UNAVAILABLE, apiUnavailableMessage(null));
        }
        long modelId = getModelId();
        if (modelId < 0) {
            return new StatusInfo(Status.MODEL_NOT_CONFIGURED, "No Anki note type selected");
        }
        if (!models.containsKey(modelId)) {
            return new StatusInfo(Status.MODEL_NOT_FOUND, "Selected note type no longer exists in AnkiDroid");
        }
        long deckId = getDeckId();
        if (deckId < 0) {
            return new StatusInfo(Status.DECK_NOT_CONFIGURED, "No Anki deck selected");
        }
        if (!decks.containsKey(deckId)) {
            return new StatusInfo(Status.DECK_NOT_FOUND, "Selected deck no longer exists in AnkiDroid");
        }
        String[] fields;
        try {
            fields = api.getFieldList(modelId);
        } catch (RuntimeException e) {
            return new StatusInfo(Status.API_UNAVAILABLE, apiUnavailableMessage(e));
        }
        if (fields == null) {
            return new StatusInfo(Status.API_UNAVAILABLE, apiUnavailableMessage(null));
        }
        List<String> missing = NoteFieldMapper.missingFields(fields, NoteFieldMapper.REQUIRED_FIELDS);
        if (!missing.isEmpty()) {
            return new StatusInfo(Status.MISSING_FIELDS, "Note type \"" + models.get(modelId)
                    + "\" is missing field(s): " + String.join(", ", missing));
        }
        return new StatusInfo(Status.OK, "Ready: \"" + models.get(modelId) + "\" → \"" + decks.get(deckId) + "\"");
    }

    private static String apiUnavailableMessage(@Nullable Exception e) {
        return "Cannot access AnkiDroid. Check that \"Enable AnkiDroid API\" is on in AnkiDroid's "
                + "advanced settings" + (e != null && e.getMessage() != null ? " (" + e.getMessage() + ")" : "");
    }

    // ---- listing ---------------------------------------------------------------------------

    /** Note types sorted by name; null if AnkiDroid is unavailable. Blocking. */
    @WorkerThread
    @Nullable
    public List<Item> getModels() {
        try {
            return toItems(new AddContentApi(context).getModelList());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decks sorted by name; null if AnkiDroid is unavailable. Blocking. */
    @WorkerThread
    @Nullable
    public List<Item> getDecks() {
        try {
            return toItems(new AddContentApi(context).getDeckList());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Suggests a note type: the first (by name) that contains all required fields, or -1.
     * Blocking.
     */
    @WorkerThread
    public long findCompatibleModelId() {
        try {
            AddContentApi api = new AddContentApi(context);
            List<Item> models = toItems(api.getModelList());
            if (models == null) {
                return -1;
            }
            for (Item model : models) {
                String[] fields = api.getFieldList(model.id);
                if (fields != null && NoteFieldMapper.missingFields(fields, NoteFieldMapper.REQUIRED_FIELDS).isEmpty()) {
                    return model.id;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    @Nullable
    private static List<Item> toItems(@Nullable Map<Long, String> map) {
        if (map == null) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<Long, String> e : map.entrySet()) {
            items.add(new Item(e.getKey(), e.getValue()));
        }
        Collections.sort(items, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return items;
    }

    // ---- prefs -----------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public long getModelId() {
        return parseId(prefs().getString(PREF_KEY_MODEL_ID, null));
    }

    public long getDeckId() {
        return parseId(prefs().getString(PREF_KEY_DECK_ID, null));
    }

    public void setModelId(long id) {
        prefs().edit().putString(PREF_KEY_MODEL_ID, Long.toString(id)).apply();
    }

    public void setDeckId(long id) {
        prefs().edit().putString(PREF_KEY_DECK_ID, Long.toString(id)).apply();
    }

    private static long parseId(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- export ----------------------------------------------------------------------------

    /**
     * Adds {@code card} to the configured deck. Never throws; blocking — call off the main thread.
     * Duplicates (same first field value in the note type) are reported, not added.
     */
    @WorkerThread
    @NonNull
    public Result add(@NonNull MinedCard card) {
        try {
            return addInternal(card);
        } catch (RuntimeException e) {
            // AnkiDroid's provider has known NPEs/IllegalStateExceptions in addNote/addMedia.
            return Result.error("AnkiDroid error: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * Checks settings and duplicates for {@code front} without adding anything, so callers can
     * fail fast before doing expensive work (clipping audio). Returns SUCCESS (noteId -1) if a card
     * with this Front could be added. Never throws; blocking — call off the main thread.
     */
    @WorkerThread
    @NonNull
    public Result precheck(@NonNull String front) {
        try {
            StatusInfo status = checkStatus();
            if (!status.isOk()) {
                return Result.error(status.message);
            }
            if (front.trim().isEmpty()) {
                return Result.error("Front is empty");
            }
            AddContentApi api = new AddContentApi(context);
            long modelId = getModelId();
            String[] modelFields = api.getFieldList(modelId);
            Map<String, String> values = new LinkedHashMap<>();
            values.put(NoteFieldMapper.FRONT, front);
            String[] fields = NoteFieldMapper.buildFields(modelFields, values, NoteFieldMapper.REQUIRED_FIELDS);
            if (!fields[0].isEmpty()) {
                List<NoteInfo> dups = api.findDuplicateNotes(modelId, fields[0]);
                if (dups != null && !dups.isEmpty()) {
                    return Result.duplicate(dups);
                }
            }
            return new Result(Result.Kind.SUCCESS, -1, "Ready", Collections.emptyList());
        } catch (NoteFieldMapper.MissingFieldsException e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error("AnkiDroid error: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    private Result addInternal(MinedCard card) {
        StatusInfo status = checkStatus();
        if (!status.isOk()) {
            return Result.error(status.message);
        }
        if (card.front == null || card.front.trim().isEmpty()) {
            return Result.error("Front is empty");
        }
        AddContentApi api = new AddContentApi(context);
        long modelId = getModelId();
        long deckId = getDeckId();

        String[] modelFields = api.getFieldList(modelId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(NoteFieldMapper.FRONT, card.front);
        values.put(NoteFieldMapper.GLOSSARY, nullToEmpty(card.glossary));
        values.put(NoteFieldMapper.SENTENCE, nullToEmpty(card.sentence));
        String[] fields;
        try {
            // Build once without audio to get the duplicate key before importing media.
            fields = NoteFieldMapper.buildFields(modelFields, values, NoteFieldMapper.REQUIRED_FIELDS);
        } catch (NoteFieldMapper.MissingFieldsException e) {
            return Result.error(e.getMessage());
        }

        // AnkiDroid matches duplicates on the note type's first field (normally Front).
        if (!fields[0].isEmpty()) {
            List<NoteInfo> dups = api.findDuplicateNotes(modelId, fields[0]);
            if (dups != null && !dups.isEmpty()) {
                return Result.duplicate(dups);
            }
        }

        if (card.sentenceAudio != null) {
            String soundTag = importMedia(api, card.sentenceAudio, "audio");
            if (soundTag == null) {
                return Result.error("AnkiDroid could not import the audio clip");
            }
            // Only SentenceAudio: the word Audio field is deliberately left empty.
            values.put(NoteFieldMapper.SENTENCE_AUDIO, soundTag);
        }
        if (card.picture != null && NoteFieldMapper.indexOf(modelFields, NoteFieldMapper.PICTURE) >= 0) {
            // Picture is optional: a failed import leaves it empty rather than losing the card.
            String imgTag = importMedia(api, card.picture, "image");
            if (imgTag != null) {
                values.put(NoteFieldMapper.PICTURE, imgTag);
            }
        }
        try {
            fields = NoteFieldMapper.buildFields(modelFields, values, NoteFieldMapper.REQUIRED_FIELDS);
        } catch (NoteFieldMapper.MissingFieldsException e) {
            return Result.error(e.getMessage());
        }

        Set<String> tags = Collections.singleton(TAG);
        Long noteId = api.addNote(modelId, deckId, fields, tags);
        if (noteId == null) {
            return Result.error("AnkiDroid rejected the note");
        }
        return Result.success(noteId);
    }

    /** Returns the {@code [sound:...]} tag, or null on failure. */
    @Nullable
    private String importMedia(AddContentApi api, Uri audio, String mimeType) {
        String ankiPackage = getAnkiPackage();
        Uri shareUri = audio;
        String baseName = audio.getLastPathSegment();
        if ("file".equals(audio.getScheme()) && audio.getPath() != null) {
            File file = new File(audio.getPath());
            baseName = file.getName();
            try {
                shareUri = ClipFileProvider.getUri(context, file);
            } catch (IllegalArgumentException e) {
                // File outside the FileProvider's configured paths.
                return null;
            }
        }
        if (baseName == null) {
            baseName = ClipFiles.newClipName();
        }
        int dot = baseName.lastIndexOf('.');
        String preferredName = dot > 0 ? baseName.substring(0, dot) : baseName;

        boolean granted = false;
        if (ankiPackage != null && "content".equals(shareUri.getScheme())) {
            try {
                context.grantUriPermission(ankiPackage, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                granted = true;
            } catch (SecurityException ignored) {
                // Not our URI; AnkiDroid may still be able to read it.
            }
        }
        try {
            return api.addMediaFromUri(shareUri, preferredName, mimeType);
        } finally {
            if (granted) {
                context.revokeUriPermission(shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
