package org.schabi.newpipe.mining

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.helper.PlayerDataSource

/**
 * Phase 2 check: clip a few seconds of real YouTube audio through ExoPlayer 2's Transformer and
 * NewPipe's YouTube data source. Needs network. Look for "AudioClipper" in logcat to see the
 * requests made.
 */
@RunWith(AndroidJUnit4::class)
class AudioClipperDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipper = AudioClipper(PlayerDataSource.getMiningYoutubeHttpDataSourceFactory())

    @Test
    fun clipsThreeSecondsOfYoutubeAudio() {
        val url = audioUrl()
        val source = object : AudioClipper.AudioSource {
            override fun current() = Uri.parse(url)
            override fun refresh(): Uri? = null
        }
        checkClip(awaitClip(source))
    }

    @Test
    fun retriesOnceWithAFreshUrlWhenTheOldOneIsRejected() {
        val url = audioUrl()
        // Same URL, but with an expiry in the past, so YouTube rejects it.
        val expired = url.replace(Regex("([?&]expire=)\\d+"), "$11000000000")
        assertTrue("URL has no expire parameter", expired != url)
        val refreshes = AtomicInteger()
        val source = object : AudioClipper.AudioSource {
            override fun current() = Uri.parse(expired)
            override fun refresh(): Uri {
                refreshes.incrementAndGet()
                return Uri.parse(url)
            }
        }
        checkClip(awaitClip(source))
        assertEquals(1, refreshes.get())
    }

    private fun audioUrl(): String {
        val info = StreamInfo.getInfo(ServiceList.YouTube, VIDEO_URL)
        val audio = CaptionRepository.selectAudioStream(info.audioStreams)
        assertNotNull("no audio stream", audio)
        Log.d(TAG, "audio: ${audio!!.format} ${audio.averageBitrate} kbps ${audio.deliveryMethod}")
        return audio.content
    }

    private fun awaitClip(source: AudioClipper.AudioSource): File {
        val done = CountDownLatch(1)
        var result: File? = null
        var error: String? = null
        clipper.clip(
            context,
            source,
            START_MS,
            END_MS,
            object : AudioClipper.Callback {
                override fun onClipReady(file: File, fileUri: Uri) {
                    result = file
                    done.countDown()
                }

                override fun onClipError(message: String, cause: Exception?) {
                    error = "$message / $cause"
                    done.countDown()
                }
            }
        )
        assertTrue("timed out", done.await(60, TimeUnit.SECONDS))
        return result ?: throw AssertionError("clip failed: $error")
    }

    private fun checkClip(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            Log.d(TAG, "clip ${file.length()} bytes, $durationMs ms, $mime")
            assertEquals((END_MS - START_MS).toDouble(), durationMs.toDouble(), 150.0)
            if (mime != "audio/mp4" && mime != "video/mp4") fail("unexpected mime $mime")
            assertEquals(
                null,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            )
        } finally {
            retriever.release()
            file.delete()
        }
    }

    companion object {
        private const val TAG = "AudioClipperDeviceTest"
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        private const val START_MS = 60_000L
        private const val END_MS = 63_000L
    }
}
