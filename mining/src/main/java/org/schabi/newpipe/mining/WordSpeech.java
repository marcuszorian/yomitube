package org.schabi.newpipe.mining;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Japanese text-to-speech for the card's word Audio field: speaks a preview, or records to a WAV
 * file that AnkiDroid imports.
 *
 * <p>Uses Google's engine when installed (Samsung's default engine often has no Japanese voice),
 * otherwise the system default. All callbacks arrive on the main thread.
 */
public final class WordSpeech {

    private static final String TAG = "WordSpeech";
    public static final String GOOGLE_TTS = "com.google.android.tts";

    public interface FileCallback {
        void onSpeechFile(@NonNull File file);

        void onSpeechError(@NonNull String message);
    }

    public enum State {INITIALIZING, READY, NO_JAPANESE, FAILED}

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private State state = State.INITIALIZING;
    @Nullable
    private Runnable onReady;
    private final Map<String, FileCallback> pending = new HashMap<>();
    private final Map<String, File> pendingFiles = new HashMap<>();
    private int counter;

    /** @param onStateKnown called once initialisation finishes (main thread), may be null */
    @MainThread
    public WordSpeech(@NonNull Context context, @Nullable Runnable onStateKnown) {
        this.context = context.getApplicationContext();
        this.onReady = onStateKnown;
        TextToSpeech.OnInitListener init = status -> main.post(() -> onInit(status));
        tts = isInstalled(this.context, GOOGLE_TTS)
                ? new TextToSpeech(this.context, init, GOOGLE_TTS)
                : new TextToSpeech(this.context, init);
    }

    /** Installed and not disabled (Samsung phones often ship Google TTS disabled). */
    static boolean isInstalled(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** Google TTS is on the device but disabled — the user can enable it in app settings. */
    public static boolean isGoogleTtsDisabled(Context c) {
        try {
            return !c.getPackageManager().getApplicationInfo(GOOGLE_TTS, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** App-info screen for Google TTS, where it can be enabled. */
    public static Intent enableGoogleTtsIntent() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", GOOGLE_TTS, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    @MainThread
    private void onInit(int status) {
        if (tts == null) return; // shut down meanwhile
        if (status != TextToSpeech.SUCCESS) {
            state = State.FAILED;
        } else {
            int lang = tts.setLanguage(Locale.JAPAN);
            state = (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED)
                    ? State.NO_JAPANESE : State.READY;
            tts.setOnUtteranceProgressListener(new Progress());
        }
        Log.i(TAG, "TTS engine=" + tts.getDefaultEngine() + " state=" + state);
        Runnable r = onReady;
        onReady = null;
        if (r != null) r.run();
    }

    public State getState() {
        return state;
    }

    /** Human-readable reason when not {@link State#READY}. */
    public String describeProblem() {
        switch (state) {
            case INITIALIZING:
                return "Text-to-speech is still starting";
            case NO_JAPANESE:
                return isGoogleTtsDisabled(context)
                        ? "Google text-to-speech is disabled (needed for a Japanese voice)"
                        : "No Japanese text-to-speech voice installed";
            case FAILED:
                return "Text-to-speech is unavailable";
            default:
                return "";
        }
    }

    /** Opens the engine's "install voice data" screen (for {@link State#NO_JAPANESE}). */
    public static Intent installVoiceIntent() {
        Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    @MainThread
    public void speak(String text) {
        if (state != State.READY || text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview-" + (counter++));
    }

    /** Records {@code text} into a WAV file next to the audio clips. */
    @MainThread
    public void synthesizeToFile(String text, @NonNull FileCallback cb) {
        if (state != State.READY) {
            cb.onSpeechError(describeProblem());
            return;
        }
        if (text == null || text.isEmpty()) {
            cb.onSpeechError("Nothing to speak");
            return;
        }
        File dir = ClipFiles.getClipDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            cb.onSpeechError("Cannot create " + dir);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(dir, "mining_word_" + stamp + ".wav");
        String id = "file-" + (counter++);
        pending.put(id, cb);
        pendingFiles.put(id, file);
        int r = tts.synthesizeToFile(text, new Bundle(), file, id);
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id);
            pendingFiles.remove(id);
            cb.onSpeechError("Text-to-speech refused the request");
        }
    }

    @MainThread
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        for (File f : pendingFiles.values()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        pending.clear();
        pendingFiles.clear();
    }

    private final class Progress extends UtteranceProgressListener {
        @Override
        public void onStart(String id) {
        }

        @Override
        public void onDone(String id) {
            main.post(() -> finish(id, null));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String id) {
            main.post(() -> finish(id, "Text-to-speech failed"));
        }

        @Override
        public void onError(String id, int errorCode) {
            main.post(() -> finish(id, "Text-to-speech failed (" + errorCode + ")"));
        }
    }

    @MainThread
    private void finish(String id, @Nullable String error) {
        FileCallback cb = pending.remove(id);
        File file = pendingFiles.remove(id);
        if (cb == null || file == null) return; // preview, or already reported
        if (error == null && file.isFile() && file.length() > 44) { // more than a WAV header
            cb.onSpeechFile(file);
        } else {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            cb.onSpeechError(error != null ? error : "Text-to-speech produced no audio");
        }
    }
}
