package org.schabi.newpipe.mining;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Where mined media (audio clips, screenshots, word audio) is written before export. */
public final class ClipFiles {

    /** Subdirectory of {@link Context#getCacheDir()}, exposed by the FileProvider (mining_clip_paths.xml). */
    public static final String CLIP_DIR = "mining_clips";
    private static final long MAX_CLIP_AGE_MS = 24L * 60 * 60 * 1000;

    private ClipFiles() {
    }

    public static File getClipDir(Context context) {
        return new File(context.getCacheDir(), CLIP_DIR);
    }

    static String newClipName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return "mining_" + stamp + ".m4a";
    }

    /** Deletes files older than a day, in case an export was abandoned. */
    static void deleteOldClips(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_CLIP_AGE_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
