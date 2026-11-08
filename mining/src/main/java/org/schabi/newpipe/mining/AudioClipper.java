package org.schabi.newpipe.mining;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.transformer.Composition;
import com.google.android.exoplayer2.transformer.DefaultAssetLoaderFactory;
import com.google.android.exoplayer2.transformer.MiningDecoderFactory;
import com.google.android.exoplayer2.transformer.EditedMediaItem;
import com.google.android.exoplayer2.transformer.ExportException;
import com.google.android.exoplayer2.transformer.ExportResult;
import com.google.android.exoplayer2.transformer.TransformationRequest;
import com.google.android.exoplayer2.transformer.Transformer;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extracts a time range of audio from a media URI (http(s), content://, file://) into a local
 * AAC/M4A file using ExoPlayer 2's Transformer.
 *
 * <p>HTTP goes through the {@link DataSource.Factory} given to the constructor. For YouTube that
 * must be NewPipe's {@code YoutubeHttpDataSource} factory: it sends the POST / {@code rn=}
 * requests YouTube expects, where plain range GETs can get 403s or throttling.
 *
 * <p>Threading: {@link #clip} may be called from any thread; the export always runs on the
 * main looper and the {@link Callback} is always invoked on the main thread. At most one clip
 * runs per instance; starting a new one cancels the previous (its callback is not invoked).
 *
 * <p>Seeking: Transformer's ExoPlayer-based asset loader clips the item and seeks to its start,
 * so for seekable progressive sources (YouTube's MP4 with sidx, or WebM with Cues) only byte
 * ranges near the clip are requested rather than the whole file. Audio is transmuxed when the
 * source is already AAC, otherwise (e.g. Opus) transcoded to AAC.
 */
public final class AudioClipper {

    private static final String TAG = "AudioClipper";

    public interface Callback {
        void onClipReady(@NonNull File file, @NonNull Uri fileUri);

        void onClipError(@NonNull String message, @Nullable Exception cause);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DataSource.Factory httpDataSourceFactory;

    @Nullable
    private Transformer transformer;
    @Nullable
    private File pendingFile;
    private int generation;

    /** @param httpDataSourceFactory used for http(s) sources; local URIs are handled too */
    public AudioClipper(@NonNull DataSource.Factory httpDataSourceFactory) {
        this.httpDataSourceFactory = httpDataSourceFactory;
    }

    /** Where to clip audio from, with a way to get a fresh URL when the old one has expired. */
    public interface AudioSource {
        /** The URI to clip from now, or null if there is none. */
        @Nullable
        Uri current();

        /**
         * Fetches a fresh URI (YouTube stream URLs expire after a few hours). Called on a
         * background thread; may block. Returns null if that isn't possible.
         */
        @Nullable
        Uri refresh();
    }

    /**
     * True if {@code cause} (from {@link Callback#onClipError}) looks like the stream URL was
     * rejected, which for YouTube usually means it expired. Worth fetching a fresh URL and
     * retrying once.
     */
    public static boolean isRejectedUrl(@Nullable Exception cause) {
        return cause instanceof ExportException
                && ((ExportException) cause).errorCode == ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS;
    }

    /**
     * Like {@link #clip}, but if the URL is rejected (expired), asks {@code source} for a fresh one
     * and tries once more. Callback runs on the main thread; cancelling covers the retry too.
     */
    public void clip(@NonNull Context ctx, @NonNull AudioSource source, long startMs, long endMs,
                     @NonNull Callback cb) {
        Uri uri = source.current();
        if (uri == null) {
            mainHandler.post(() -> cb.onClipError("No audio source for this video", null));
            return;
        }
        clip(ctx, uri, startMs, endMs, new Callback() {
            @Override
            public void onClipReady(@NonNull File file, @NonNull Uri fileUri) {
                cb.onClipReady(file, fileUri);
            }

            @Override
            public void onClipError(@NonNull String message, @Nullable Exception cause) {
                if (!isRejectedUrl(cause)) {
                    cb.onClipError(message, cause);
                    return;
                }
                Log.d(TAG, "Audio URL rejected (" + message + "); fetching a fresh one");
                final int myGeneration = generation;
                new Thread(() -> {
                    Uri fresh;
                    try {
                        fresh = source.refresh();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Refreshing audio URL failed", e);
                        fresh = null;
                    }
                    final Uri retryUri = fresh;
                    mainHandler.post(() -> {
                        if (myGeneration != generation) {
                            return; // cancelled meanwhile
                        }
                        if (retryUri == null) {
                            cb.onClipError(message, cause);
                        } else {
                            clip(ctx, retryUri, startMs, endMs, cb);
                        }
                    });
                }, "AudioClipper-refresh").start();
            }
        });
    }

    /** Starts extraction of [startMs, endMs) from {@code source}. See class docs for threading. */
    public void clip(@NonNull Context ctx, @NonNull Uri source, long startMs, long endMs, @NonNull Callback cb) {
        final Context context = ctx.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startOnMain(context, source, startMs, endMs, cb);
        } else {
            mainHandler.post(() -> startOnMain(context, source, startMs, endMs, cb));
        }
    }

    /** Cancels the running clip, if any; deletes its partial output. Its callback is not invoked. */
    public void cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelOnMain();
        } else {
            mainHandler.post(this::cancelOnMain);
        }
    }

    @MainThread
    private void cancelOnMain() {
        generation++;
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        if (pendingFile != null) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            pendingFile = null;
        }
    }

    @MainThread
    private void startOnMain(Context context, Uri source, long startMs, long endMs, Callback cb) {
        cancelOnMain();
        final int myGeneration = generation;

        final ClipRange range;
        try {
            range = ClipRange.of(startMs, endMs);
        } catch (IllegalArgumentException e) {
            cb.onClipError(e.getMessage(), e);
            return;
        }

        File dir = ClipFiles.getClipDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            cb.onClipError("Cannot create clip directory " + dir, null);
            return;
        }
        ClipFiles.deleteOldClips(dir);
        final File output = new File(dir, ClipFiles.newClipName());

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(range.startMs)
                        .setEndPositionMs(range.endMs)
                        .build())
                .build();
        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build();

        // Counts what was downloaded, so the logs show whether seeking avoided a full download.
        final ByteCounter counter = new ByteCounter();
        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(context, httpDataSourceFactory)
                        .setTransferListener(counter);
        DefaultAssetLoaderFactory assetLoaderFactory = new DefaultAssetLoaderFactory(
                context,
                MiningDecoderFactory.create(context),
                /* forceInterpretHdrAsSdr= */ false,
                Clock.DEFAULT,
                new ClippedProgressiveFactory(dataSourceFactory));

        final long startedAt = System.currentTimeMillis();
        Transformer.Listener listener = new Transformer.Listener() {
            @Override
            public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                if (myGeneration != generation) {
                    return;
                }
                transformer = null;
                pendingFile = null;
                Log.d(TAG, "Clip " + range.startMs + "-" + range.endMs + " ms done in "
                        + (System.currentTimeMillis() - startedAt) + " ms; " + counter);
                if (!output.isFile() || output.length() == 0) {
                    cb.onClipError("Audio clip export produced no data", null);
                    return;
                }
                cb.onClipReady(output, Uri.fromFile(output));
            }

            @Override
            public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult,
                                @NonNull ExportException exportException) {
                if (myGeneration != generation) {
                    return;
                }
                transformer = null;
                pendingFile = null;
                //noinspection ResultOfMethodCallIgnored
                output.delete();
                Log.d(TAG, "Clip failed after " + counter);
                cb.onClipError(describe(exportException), exportException);
            }
        };

        try {
            transformer = new Transformer.Builder(context)
                    .setLooper(Looper.getMainLooper())
                    // Transmux if the source is already AAC, otherwise transcode to AAC.
                    .setTransformationRequest(new TransformationRequest.Builder()
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .build())
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .addListener(listener)
                    .build();
            pendingFile = output;
            transformer.start(editedMediaItem, output.getAbsolutePath());
        } catch (RuntimeException e) {
            transformer = null;
            pendingFile = null;
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            cb.onClipError("Could not start audio export: " + e.getMessage(), e);
        }
    }

    private static String describe(ExportException e) {
        switch (e.errorCode) {
            case ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Network error while fetching audio";
            case ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "Stream URL rejected the request (it may have expired)";
            case ExportException.ERROR_CODE_IO_FILE_NOT_FOUND:
            case ExportException.ERROR_CODE_IO_NO_PERMISSION:
                return "Cannot read the media source";
            case ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED:
                return "Audio format not supported for clipping";
            default:
                return "Audio clip failed (" + e.getErrorCodeName() + ")";
        }
    }

    /**
     * Progressive source (the clipper only gets direct stream URLs or local files) that reads in
     * small steps. The default 1 MiB step downloads about a minute of 128 kbps audio past the clip.
     */
    private static final class ClippedProgressiveFactory implements MediaSource.Factory {
        private static final int LOAD_STEP_BYTES = 64 * 1024;
        private final ProgressiveMediaSource.Factory progressive;

        ClippedProgressiveFactory(DataSource.Factory dataSourceFactory) {
            progressive = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setContinueLoadingCheckIntervalBytes(LOAD_STEP_BYTES);
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider p) {
            progressive.setDrmSessionManagerProvider(p);
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy p) {
            progressive.setLoadErrorHandlingPolicy(p);
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[]{C.CONTENT_TYPE_OTHER};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            MediaSource source = progressive.createMediaSource(mediaItem);
            MediaItem.ClippingConfiguration clip = mediaItem.clippingConfiguration;
            // Same as DefaultMediaSourceFactory: clip to the requested range, starting exactly
            // at the start position rather than at a keyframe (all audio frames are keyframes).
            return new ClippingMediaSource(source,
                    C.msToUs(clip.startPositionMs),
                    clip.endPositionMs == C.TIME_END_OF_SOURCE
                            ? C.TIME_END_OF_SOURCE : C.msToUs(clip.endPositionMs),
                    !clip.startsAtKeyFrame,
                    clip.relativeToLiveWindow,
                    clip.relativeToDefaultPosition);
        }
    }

    /** Counts requests and bytes for network transfers. Called from the loader thread. */
    private static final class ByteCounter implements TransferListener {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong requestBytes = new AtomicLong();

        @Override
        public void onTransferInitializing(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                           boolean isNetwork) {
        }

        @Override
        public void onTransferStart(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                    boolean isNetwork) {
            if (!isNetwork) return;
            requests.incrementAndGet();
            Log.d(TAG, "request @" + dataSpec.position + " len "
                    + (dataSpec.length < 0 ? "open" : String.valueOf(dataSpec.length)));
        }

        @Override
        public void onBytesTransferred(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                       boolean isNetwork, int bytesTransferred) {
            if (!isNetwork) return;
            bytes.addAndGet(bytesTransferred);
            requestBytes.addAndGet(bytesTransferred);
        }

        @Override
        public void onTransferEnd(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                  boolean isNetwork) {
            if (isNetwork) {
                Log.d(TAG, "  read " + requestBytes.getAndSet(0) / 1024 + " KiB");
            }
        }

        @NonNull
        @Override
        public String toString() {
            return requests.get() + " network requests, " + bytes.get() / 1024 + " KiB";
        }
    }
}
