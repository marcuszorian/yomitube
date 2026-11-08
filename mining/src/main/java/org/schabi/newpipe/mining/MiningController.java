package org.schabi.newpipe.mining;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.android.exoplayer2.upstream.DataSource;

import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mining logic for one player: tracks the playing video, loads its Japanese captions, and
 * opens/closes the {@link MiningPanel}. Knows nothing about NewPipe's player classes; the
 * {@code :app} side implements {@link Host}. All public methods on the main thread.
 */
public final class MiningController {

    private static final String TAG = "MiningController";
    /** Extra slack around the estimated subtitle text for the hold gesture. */
    private static final float HIT_SLACK_DP = 24;

    /** What the controller needs from the player. All calls on the main thread. */
    public interface Host {
        /** Activity whose window the panel is shown in, or null if there is none now. */
        @Nullable
        Activity getActivity();

        PlaybackControl getPlaybackControl();

        /** Where the clip's audio comes from (the stream the player is playing). */
        @Nullable
        AudioClipper.AudioSource getAudioSource();

        /** Data source for clipping: the player's cache, falling back to YouTube. */
        DataSource.Factory getClipDataSourceFactory();

        /** Text of the caption currently on screen, or "" if none. */
        String getShownCaptionText();

        /** The view captions are drawn in, for the hold hit test; null if not available. */
        @Nullable
        View getSubtitleView();

        /** Caption text size as a fraction of the subtitle view height. */
        float getSubtitleTextFraction();

        /** Shows this caption track on the video if no Japanese track is showing. */
        void showCaptionTrack(@NonNull String languageTag);

        /** Panel opened/closed: hide the player controls and keep them hidden. */
        void setMiningActive(boolean active);

        void openSettings();
    }

    private enum CaptionState { NONE, LOADING, LOADED, NO_JAPANESE, ERROR }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Tokenizer/dictionary warm-up, which can take ~10 s on the first run after an install. */
    private final ExecutorService worker = daemonExecutor("mining-worker");
    /** Caption fetches, kept off {@link #worker} so they never queue behind warm-up. */
    private final ExecutorService captionWorker = daemonExecutor("mining-captions");

    /** Bumped whenever the stream changes, so late results for old streams are ignored. */
    private int streamGeneration;
    @Nullable
    private String streamUrl;
    @Nullable
    private SubtitleTrackInfo track;
    private CaptionState captionState = CaptionState.NONE;
    private List<Cue> cues = Collections.emptyList();
    /** Open the panel once captions arrive (the user asked before they were loaded). */
    private boolean openWhenLoaded;

    @Nullable
    private MiningPanel panel;
    private boolean destroyed;

    public MiningController(@NonNull Host host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ stream

    /**
     * A new stream started playing (or the same one was re-resolved). Closes the panel if the
     * stream changed, picks the Japanese caption track and starts fetching it in the background.
     */
    @MainThread
    public void onStreamChanged(@NonNull String url, @Nullable List<SubtitlesStream> subtitles) {
        if (url.equals(streamUrl)) {
            return; // e.g. quality change: same video, keep cues and panel
        }
        Log.i(TAG, "Stream changed from " + streamUrl + " to " + url + " (panel open: " + isPanelOpen() + ")");
        closePanel(false);
        streamGeneration++;
        streamUrl = url;
        cues = Collections.emptyList();
        openWhenLoaded = false;
        track = CaptionRepository.selectTrack(CaptionRepository.tracksOf(subtitles));
        Log.i(TAG, "Stream " + url + ": Japanese track " + track);
        if (track == null) {
            captionState = CaptionState.NO_JAPANESE;
            return;
        }
        warmUp();
        loadCues(track);
    }

    private void loadCues(SubtitleTrackInfo t) {
        captionState = CaptionState.LOADING;
        final int gen = streamGeneration;
        captionWorker.execute(() -> {
            List<Cue> result = null;
            Exception error = null;
            try {
                result = CaptionRepository.fetchCues(t);
            } catch (IOException | RuntimeException e) {
                error = e;
            }
            final List<Cue> loaded = result;
            final Exception err = error;
            main.post(() -> onCuesLoaded(gen, loaded, err));
        });
    }

    @MainThread
    private void onCuesLoaded(int gen, @Nullable List<Cue> loaded, @Nullable Exception error) {
        if (destroyed || gen != streamGeneration) return;
        if (error != null || loaded == null) {
            Log.w(TAG, "Caption fetch failed", error);
            captionState = CaptionState.ERROR;
            if (openWhenLoaded) {
                toast(host.getActivity(), R.string.mining_captions_error,
                        String.valueOf(error != null ? error.getMessage() : null));
            }
            openWhenLoaded = false;
            return;
        }
        cues = Collections.unmodifiableList(loaded);
        captionState = loaded.isEmpty() ? CaptionState.NO_JAPANESE : CaptionState.LOADED;
        Log.i(TAG, "Loaded " + loaded.size() + " cues");
        if (openWhenLoaded) {
            openWhenLoaded = false;
            openPanel();
        }
    }

    private void warmUp() {
        final Activity a = host.getActivity();
        if (a == null) return;
        final android.content.Context app = a.getApplicationContext();
        worker.execute(() -> warmUpBlocking(app));
    }

    @WorkerThread
    private static void warmUpBlocking(android.content.Context app) {
        try {
            Tokenizer.warmUp();
            Dictionary.get(app).warmUp();
        } catch (RuntimeException e) {
            Log.w(TAG, "Warm-up failed", e);
        }
    }

    // ------------------------------------------------------------------ opening the panel

    /**
     * Long press on the player at ({@code x}, {@code y}) in the subtitle view's parent
     * coordinates. Returns true (and opens mining) if the press was on the caption text.
     */
    @MainThread
    public boolean onLongPress(float x, float y) {
        if (isPanelOpen() || destroyed) return false;
        View sv = host.getSubtitleView();
        String shown = host.getShownCaptionText();
        if (sv == null || shown.isEmpty()) return false;
        int h = sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom();
        float textSize = host.getSubtitleTextFraction() * h;
        // ExoPlayer's SubtitleView puts bottom-aligned cues this far up (DEFAULT_BOTTOM_PADDING_FRACTION).
        float bottom = 0.08f * 2f / 3f * h + sv.getPaddingBottom();
        float slack = HIT_SLACK_DP * sv.getResources().getDisplayMetrics().density;
        boolean hit = SubtitleHitArea.contains(x - sv.getLeft(), y - sv.getTop(),
                sv.getWidth(), sv.getHeight(), textSize, bottom, shown, slack);
        if (!hit) return false;
        openPanel();
        return true;
    }

    /** Mine button / hold on caption. Shows a message instead if there's nothing to mine. */
    @MainThread
    public void openPanel() {
        if (isPanelOpen() || destroyed) return;
        Activity a = host.getActivity();
        if (a == null) return;
        switch (captionState) {
            case NONE:
            case NO_JAPANESE:
                toast(a, R.string.mining_captions_none);
                return;
            case LOADING:
                toast(a, R.string.mining_captions_still_loading);
                openWhenLoaded = true;
                return;
            case ERROR:
                // Try again; the panel opens when it arrives.
                if (track != null) {
                    toast(a, R.string.mining_captions_loading);
                    openWhenLoaded = true;
                    loadCues(track);
                }
                return;
            case LOADED:
            default:
                break;
        }
        if (track != null) host.showCaptionTrack(track.languageCode);
        PlaybackControl player = host.getPlaybackControl();
        int index = CueTimeline.indexAt(cues, player.getPosition());
        if (index < 0) {
            toast(a, R.string.mining_no_captions);
            return;
        }
        ViewGroup container = a.findViewById(android.R.id.content);
        if (container == null) return;
        warmUp();
        host.setMiningActive(true);
        panel = new MiningPanel(a, container, player, cues, index, host.getAudioSource(),
                host.getClipDataSourceFactory(), new MiningPanel.Listener() {
                    @Override
                    public void onClosed() {
                        panel = null;
                        host.setMiningActive(false);
                    }

                    @Override
                    public void onOpenSettings() {
                        host.openSettings();
                    }
                });
        panel.open();
    }

    public boolean isPanelOpen() {
        return panel != null;
    }

    /** Closes the panel, discarding the draft. Resumes playback if {@code resume}. */
    @MainThread
    public void closePanel(boolean resume) {
        if (panel != null) {
            // close() calls Listener.onClosed, which clears `panel`.
            panel.close(resume);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Back pressed (pre-Android 13 path). Returns true if the panel consumed it. */
    public boolean handleBack() {
        if (panel == null) return false;
        closePanel(true);
        return true;
    }

    public void onConfigurationChanged() {
        if (panel != null) panel.onConfigurationChanged();
    }

    public void destroy() {
        destroyed = true;
        closePanel(false);
        worker.shutdownNow();
        captionWorker.shutdownNow();
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static void toast(@Nullable Activity a, int res, Object... args) {
        if (a == null) return;
        Toast.makeText(a, a.getString(res, args), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ audio source helper

    /**
     * An {@link AudioClipper.AudioSource} for a stream URL that can be refreshed by re-extracting
     * the video. {@code refresher} is called on a background thread.
     */
    public static AudioClipper.AudioSource audioSource(@NonNull String initialUrl,
                                                       @NonNull UrlRefresher refresher) {
        return new AudioClipper.AudioSource() {
            private volatile String url = initialUrl;

            @Override
            public Uri current() {
                return Uri.parse(url);
            }

            @Override
            public Uri refresh() {
                String fresh = refresher.freshUrl();
                if (fresh == null) return null;
                url = fresh;
                return Uri.parse(fresh);
            }
        };
    }

    /** Fetches a fresh stream URL (blocking), or null. */
    public interface UrlRefresher {
        @Nullable
        @WorkerThread
        String freshUrl();
    }
}
