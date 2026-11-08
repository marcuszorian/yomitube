package org.schabi.newpipe.mining;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Copies the current video frame off the player's surface (video only — no subtitles or controls)
 * and saves it as a downscaled JPEG for the card's Picture field.
 */
public final class FrameGrabber {

    public interface Callback {
        /** Main thread. {@code bitmap} is the scaled frame (also written to {@code file}). */
        void onFrame(@NonNull File file, @NonNull Bitmap bitmap);

        /** Main thread. */
        void onFrameError(@NonNull String message);
    }

    /** Max width of saved screenshots; keeps the Anki collection small. */
    public static final int MAX_WIDTH = 640;
    static final int JPEG_QUALITY = 85;

    private FrameGrabber() {
    }

    /** {@code {width, height}} scaled down to fit {@code maxWidth}, aspect kept, never upscaled. */
    public static int[] scaledSize(int width, int height, int maxWidth) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        if (width <= maxWidth) return new int[]{width, height};
        int h = Math.max(1, Math.round(height * (maxWidth / (float) width)));
        return new int[]{maxWidth, h};
    }

    /**
     * Grabs the frame currently shown by {@code videoSurface} (a SurfaceView or TextureView). The
     * capture uses the surface's own buffer size; {@code videoWidth/Height} give the aspect ratio
     * to restore when the surface is stretched (pass 0 if unknown).
     */
    @MainThread
    public static void grab(@NonNull Context context, @Nullable View videoSurface, int videoWidth,
                            int videoHeight, @NonNull Callback cb) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        if (videoSurface == null || videoSurface.getWidth() <= 0 || videoSurface.getHeight() <= 0) {
            cb.onFrameError("No video frame to capture");
            return;
        }
        if (videoSurface instanceof TextureView) {
            Bitmap b = ((TextureView) videoSurface).getBitmap();
            if (b == null) {
                cb.onFrameError("No video frame to capture");
                return;
            }
            saveInBackground(app, b, videoWidth, videoHeight, main, cb);
            return;
        }
        if (!(videoSurface instanceof SurfaceView) || Build.VERSION.SDK_INT < 24) {
            cb.onFrameError("Screenshots aren't supported on this device");
            return;
        }
        SurfaceView sv = (SurfaceView) videoSurface;
        if (!sv.getHolder().getSurface().isValid()) {
            cb.onFrameError("No video frame to capture");
            return;
        }
        final Bitmap b = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
        final HandlerThread t = new HandlerThread("frame-grab");
        t.start();
        try {
            PixelCopy.request(sv, b, result -> {
                t.quitSafely();
                if (result != PixelCopy.SUCCESS) {
                    b.recycle();
                    main.post(() -> cb.onFrameError("Screenshot failed (" + result + ")"));
                    return;
                }
                saveInBackground(app, b, videoWidth, videoHeight, main, cb);
            }, new Handler(t.getLooper()));
        } catch (IllegalArgumentException e) {
            t.quitSafely();
            b.recycle();
            cb.onFrameError("Screenshot failed: " + e.getMessage());
        }
    }

    private static void saveInBackground(Context app, Bitmap raw, int videoWidth, int videoHeight,
                                         Handler main, Callback cb) {
        new Thread(() -> {
            try {
                // The surface may be letterboxed by the view, but its buffer is sized to the video
                // aspect; still, restore the true aspect if it differs (e.g. zoom/stretch modes).
                int w = raw.getWidth();
                int h = raw.getHeight();
                if (videoWidth > 0 && videoHeight > 0) {
                    h = Math.round(w * (videoHeight / (float) videoWidth));
                }
                int[] size = scaledSize(w, h, MAX_WIDTH);
                Bitmap scaled = Bitmap.createScaledBitmap(raw, size[0], size[1], true);
                if (scaled != raw) raw.recycle();
                File file = save(app, scaled);
                main.post(() -> cb.onFrame(file, scaled));
            } catch (IOException | RuntimeException e) {
                main.post(() -> cb.onFrameError("Couldn't save screenshot: " + e.getMessage()));
            }
        }, "frame-save").start();
    }

    private static File save(Context app, Bitmap bitmap) throws IOException {
        File dir = ClipFiles.getClipDir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File f = new File(dir, "mining_" + stamp + ".jpg");
        try (OutputStream out = new FileOutputStream(f)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("JPEG encoding failed");
            }
        }
        return f;
    }
}
