package org.schabi.newpipe.player.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.View
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.SeekParameters
import com.google.android.exoplayer2.text.Cue
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.SubtitleView
import com.google.android.exoplayer2.upstream.DataSource
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.mining.AudioClipper
import org.schabi.newpipe.mining.FrameGrabber
import org.schabi.newpipe.mining.MiningController
import org.schabi.newpipe.mining.PlaybackControl
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.settings.MiningSettingsActivity
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Mining fork: connects the sentence-mining feature (module `:mining`) to NewPipe's main player.
 * Added next to [MainPlayerUi] only, so the popup and background players don't offer mining.
 */
class MiningPlayerUi(player: Player) : PlayerUi(player), MiningController.Host {

    private val controller = MiningController(this)
    private var shownCaption = ""

    /** Player state we change while the panel is open, restored on close. */
    private var savedSeekParameters: SeekParameters? = null

    private val mainUi: MainPlayerUi?
        get() = player.UIs().get(MainPlayerUi::class.java).orElse(null)

    // ------------------------------------------------------------------ PlayerUi events

    override fun onMetadataChanged(info: StreamInfo) {
        controller.onStreamChanged(info.url, info.subtitles)
    }

    override fun onCues(cues: List<Cue>) {
        shownCaption = cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
    }

    override fun destroyPlayer() {
        controller.closePanel(false)
    }

    override fun destroy() {
        controller.destroy()
    }

    // ------------------------------------------------------------------ entry points from :app

    /** Mine button. */
    fun openPanel() = controller.openPanel()

    /** Long press at ([x], [y]) in the player root's coordinates; true if mining opened. */
    fun onLongPress(x: Float, y: Float): Boolean = controller.onLongPress(x, y)

    /** Back pressed; true if the panel consumed it. */
    fun handleBack(): Boolean = controller.handleBack()

    fun onConfigurationChanged() = controller.onConfigurationChanged()

    // ------------------------------------------------------------------ MiningController.Host

    override fun getActivity(): Activity? = mainUi?.parentActivity?.orElse(null)

    override fun getPlaybackControl(): PlaybackControl = playbackControl

    override fun getAudioSource(): AudioClipper.AudioSource? {
        val tag = player.currentMetadata ?: return null
        val info = tag.maybeStreamInfo.orElse(null) ?: return null
        val stream = tag.maybeAudioTrack.map { it.selectedAudioStream }.orElse(null)
        val url = stream?.takeIf { it.isUrl }?.content ?: return null
        val serviceId = info.serviceId
        val pageUrl = info.url
        val format = stream.format
        val bitrate = stream.averageBitrate
        val trackId = stream.audioTrackId
        return MiningController.audioSource(url) {
            // Re-extract the video (bypassing NewPipe's info cache, which holds the stale URL)
            // and take the same audio stream again.
            try {
                val fresh = ExtractorHelper.getStreamInfo(serviceId, pageUrl, true).blockingGet()
                fresh.audioStreams.firstOrNull {
                    it.isUrl && it.format == format && it.averageBitrate == bitrate &&
                        it.audioTrackId == trackId
                }?.content
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh audio URL", e)
                null
            }
        }
    }

    override fun getClipDataSourceFactory(): DataSource.Factory = PlayerDataSource.getMiningCachedDataSourceFactory(
        context,
        PlayerDataSource.getMiningYoutubeHttpDataSourceFactory()
    )

    override fun getShownCaptionText(): String = shownCaption

    override fun getSubtitleView(): View? = mainUi?.binding?.subtitleView

    override fun getSubtitleTextFraction(): Float = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * PlayerHelper.getCaptionScale(context)

    override fun showCaptionTrack(languageTag: String) {
        val rendererIndex = player.captionRendererIndex
        if (rendererIndex == Player.RENDERER_UNAVAILABLE) return
        val selector: DefaultTrackSelector = player.trackSelector
        val params = selector.parameters
        val showing = !params.getRendererDisabled(rendererIndex) &&
            params.preferredTextLanguages.any { it.startsWith("ja") }
        if (showing) return
        // Same as picking the track in NewPipe's caption menu, without saving it as the default.
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredTextLanguages(languageTag, "ja")
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setRendererDisabled(rendererIndex, false)
        )
    }

    override fun setMiningActive(active: Boolean) {
        val ui = mainUi
        val exo = player.exoPlayer
        if (active) {
            ui?.hideControls(0, 0)
            // The "fast seek" setting snaps seeks to keyframes; trim previews need exact seeks.
            if (exo != null) {
                savedSeekParameters = exo.seekParameters
                exo.setSeekParameters(SeekParameters.EXACT)
            }
        } else {
            savedSeekParameters?.let { exo?.setSeekParameters(it) }
            savedSeekParameters = null
        }
    }

    override fun openSettings() {
        getActivity()?.let { it.startActivity(Intent(it, MiningSettingsActivity::class.java)) }
    }

    // ------------------------------------------------------------------ PlaybackControl

    private val playbackControl = object : PlaybackControl {
        override fun getPosition(): Long = player.exoPlayer?.currentPosition ?: 0

        override fun getDuration(): Long {
            val d = player.exoPlayer?.duration ?: return -1
            return if (d == C.TIME_UNSET) -1 else d
        }

        override fun isPlaying(): Boolean = player.exoPlayer?.playWhenReady == true

        override fun seekTo(positionMs: Long) {
            player.exoPlayer?.seekTo(positionMs)
        }

        override fun play() = player.play()

        override fun pause() = player.pause()

        override fun grabFrame(cb: FrameGrabber.Callback) {
            val size = player.exoPlayer?.videoSize
            // Display size accounts for non-square pixels.
            val w = size?.let { Math.round(it.width * it.pixelWidthHeightRatio) } ?: 0
            val h = size?.height ?: 0
            FrameGrabber.grab(context, mainUi?.binding?.surfaceView, w, h, cb)
        }
    }

    companion object {
        private const val TAG = "MiningPlayerUi"

        /** The mining UI of [player], if it is the main player. */
        @JvmStatic
        fun of(player: Player?): MiningPlayerUi? = player?.UIs()?.get(MiningPlayerUi::class.java)?.orElse(null)
    }
}
