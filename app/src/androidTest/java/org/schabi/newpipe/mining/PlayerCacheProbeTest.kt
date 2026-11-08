package org.schabi.newpipe.mining

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.resolver.AudioPlaybackResolver

/**
 * Plays ~15 s of audio through NewPipe's real resolver and cache, then clips a line that was
 * just played with the network blocked: the clip must come entirely from the player's cache.
 */
@RunWith(AndroidJUnit4::class)
class PlayerCacheProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun clipsJustPlayedAudioFromThePlayerCacheWithoutNetwork() {
        val info = StreamInfo.getInfo(ServiceList.YouTube, VIDEO_URL)
        val played = play(info, fromMs = 55_000, forMs = 15_000)
        Log.d(TAG, "played ${played.second} to ${played.first} ms")
        val audio = played.second

        val upstreamOpens = AtomicInteger()
        val clipper = AudioClipper(
            PlayerDataSource.getMiningCachedDataSourceFactory(context, NoNetwork(upstreamOpens))
        )
        val source = object : AudioClipper.AudioSource {
            override fun current() = Uri.parse(audio.content)
            override fun refresh(): Uri? = null
        }
        val done = CountDownLatch(1)
        var file: File? = null
        var error: String? = null
        clipper.clip(
            context,
            source,
            60_000,
            63_000,
            object : AudioClipper.Callback {
                override fun onClipReady(f: File, fileUri: Uri) {
                    file = f
                    done.countDown()
                }

                override fun onClipError(message: String, cause: Exception?) {
                    error = "$message / $cause"
                    done.countDown()
                }
            }
        )
        assertTrue("timed out", done.await(30, TimeUnit.SECONDS))
        Log.d(TAG, "clip=${file?.length()} error=$error upstreamOpens=${upstreamOpens.get()}")
        // The MP4 reader may speculatively read just past the header before seeking; that read
        // failing is harmless. Nothing in the played region may need the network.
        assertTrue("clip range needed the network", upstreamOpens.get() <= 1)
        assertTrue("clip failed: $error", file != null && file!!.length() > 10_000)
        file!!.delete()
    }

    /** Plays [info]'s audio like the background player does; returns position and stream. */
    private fun play(info: StreamInfo, fromMs: Long, forMs: Long): Pair<Long, AudioStream> {
        val done = CountDownLatch(1)
        var player: ExoPlayer? = null
        var stream: AudioStream? = null
        main.post {
            val dataSource = PlayerDataSource(context, null)
            val mediaSource = AudioPlaybackResolver(context, dataSource).resolve(info)!!
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                setMediaSource(mediaSource)
                prepare()
                seekTo(fromMs)
                play()
            }
            main.postDelayed({
                val tag = player!!.currentMediaItem!!.localConfiguration!!.tag as MediaItemTag
                stream = tag.maybeAudioTrack.map { it.selectedAudioStream }.orElse(null)
                done.countDown()
            }, forMs)
        }
        done.await(forMs + 15_000, TimeUnit.MILLISECONDS)
        var position = 0L
        val released = CountDownLatch(1)
        main.post {
            position = player!!.currentPosition
            player!!.release()
            released.countDown()
        }
        released.await(5, TimeUnit.SECONDS)
        return position to stream!!
    }

    /** Upstream that fails every open and counts attempts. */
    private class NoNetwork(private val opens: AtomicInteger) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                opens.incrementAndGet()
                Log.d(TAG, "network open blocked @${dataSpec.position} len ${dataSpec.length}")
                throw IOException("network blocked by test")
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("not open")
            override fun getUri(): Uri? = null
            override fun close() {}
        }
    }

    companion object {
        private const val TAG = "CacheProbe"
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}
