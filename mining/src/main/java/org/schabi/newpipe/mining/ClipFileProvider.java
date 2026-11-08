package org.schabi.newpipe.mining;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Exposes audio clips to AnkiDroid.
 *
 * <p>AnkiDroid's media import derives the stored file's extension from
 * {@code MimeTypeMap.getExtensionFromMimeType(contentResolver.getType(uri))}. The platform maps
 * ".m4a" inconsistently across Android versions (audio/mp4, audio/x-m4a, even audio/mpeg on old
 * releases, which would turn the file into ".mp3"), so {@link #getType} returns a MIME type that
 * round-trips back to "m4a" on this device, falling back to video/mp4 (always ".mp4").
 */
public class ClipFileProvider extends FileProvider {

    private static final String[] M4A_CANDIDATES = {"audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac-mp4"};

    public static String authority(Context context) {
        return context.getPackageName() + ".miningclips";
    }

    public static Uri getUri(Context context, File file) {
        return getUriForFile(context, authority(context), file);
    }

    @Override
    public String getType(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path != null && path.toLowerCase().endsWith(".m4a")) {
            return m4aMimeType();
        }
        return super.getType(uri);
    }

    static String m4aMimeType() {
        MimeTypeMap map = MimeTypeMap.getSingleton();
        for (String mime : M4A_CANDIDATES) {
            if ("m4a".equals(map.getExtensionFromMimeType(mime))) {
                return mime;
            }
        }
        return "video/mp4";
    }
}
