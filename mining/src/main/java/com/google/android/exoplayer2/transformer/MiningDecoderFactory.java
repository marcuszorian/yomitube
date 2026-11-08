package com.google.android.exoplayer2.transformer;

import android.content.Context;

/**
 * Exposes ExoPlayer 2.19's package-private {@code DefaultDecoderFactory}.
 *
 * <p>{@link DefaultAssetLoaderFactory}'s constructor that takes a custom {@code MediaSource.Factory}
 * (needed to fetch YouTube audio through NewPipe's data source) also requires a decoder factory,
 * but 2.19 doesn't make the default one public (Media3 later did). Living in the same package lets
 * us use it; if a future ExoPlayer removes it, compilation fails rather than the app at runtime.
 */
public final class MiningDecoderFactory {

    private MiningDecoderFactory() {
    }

    public static Codec.DecoderFactory create(final Context context) {
        return new DefaultDecoderFactory(context);
    }
}
