package com.pr0gramm.app.ui.views.viewer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.core.animation.doOnEnd
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import com.pr0gramm.app.Duration
import com.pr0gramm.app.Logger
import com.pr0gramm.app.R
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.databinding.PlayerDelayedOverlayBinding
import com.pr0gramm.app.databinding.PlayerSubtitleContainerBinding
import com.pr0gramm.app.databinding.SubtitleBinding
import com.pr0gramm.app.io.Cache
import com.pr0gramm.app.services.ThemeHelper
import com.pr0gramm.app.services.UriHelper
import com.pr0gramm.app.ui.views.instance
import com.pr0gramm.app.ui.views.viewer.video.InputStreamCacheDataSource
import com.pr0gramm.app.util.addOnAttachListener
import com.pr0gramm.app.util.addOnDetachListener
import com.pr0gramm.app.util.di.injector
import com.pr0gramm.app.util.find
import com.pr0gramm.app.util.layoutInflater
import com.pr0gramm.app.util.priority
import com.pr0gramm.app.util.removeFromParent
import com.pr0gramm.app.util.setImageResource


@SuppressLint("ViewConstructor")
class SimpleVideoMediaView(config: Config) :
    AbstractProgressMediaView(config, R.layout.player_kind_simple_video) {
    private val logger = Logger("SimpleVideoMediaView(${config.mediaUri.id})")

    private val volumeController: VolumeController?

    private val preferences: SharedPreferences by instance()

    // the player has confirmed that he wants to start the video
    private var isConfirmed: Boolean = false

    // the current player.
    // Will be released on detach and re-created on attach.
    private var exo: ExoPlayer? = null

    private val controlsView = LayoutInflater
        .from(context)
        .inflate(R.layout.player_video_controls, this, false) as ViewGroup

    private val subtitleContainer: ViewGroup = PlayerSubtitleContainerBinding
        .inflate(layoutInflater, this, false)
        .root

    init {
        if (config.audio) {
            val muteView: ImageView = controlsView.find(R.id.mute)

            // set visible, we need it.
            muteView.isVisible = true

            // controller will handle the button clicks & stuff
            volumeController = VolumeController(muteView) { exo }

        } else {
            volumeController = null
        }

        if (config.subtitles.isNotEmpty()) {
            val subtitlesView: ImageView = controlsView.find(R.id.subtitles)
            subtitlesView.isVisible = true
            subtitlesView.setOnClickListener { toggleSubtitles(subtitlesView) }

            if (preferences.getBoolean("subtitles", false)) {
                toggleSubtitles(subtitlesView, forceOn = true)
            }
        }

        if (config.mediaUri.delay) {
            initializePlayButtonOverlay()
        }

        publishControllerView(controlsView)
        publishControllerView(subtitleContainer)

        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.M) {
            addOnAttachListener {
                if (isPlaying) {
                    logger.debug { "View is attached, re-create video player now." }
                    startVideo()
                }
            }

            addOnDetachListener {
                if (exo != null) {
                    logger.debug { "View is detached, releasing video player now." }
                    stopVideo()
                }
            }
        }

        controlsView.find<View>(R.id.pause).setOnClickListener {
            val exo = exo ?: return@setOnClickListener

            // toggle play
            exo.playWhenReady = !exo.playWhenReady

            // publish state
            videoPauseState.value = !exo.playWhenReady

            updatePauseViewIcon()
        }
    }

    private fun initializePlayButtonOverlay() {
        val overlay = PlayerDelayedOverlayBinding.inflate(layoutInflater, this, true).also { cc ->
            cc.playerConfirm.setOnClickListener {
                cc.playerConfirm.animate()
                    .alpha(0f).scaleX(0.8f).scaleY(0.8f)
                    .withEndAction { cc.playerConfirm.removeFromParent() }
                    .start()

                controlsView.animate()
                    .alpha(1f)
                    .start()

                isConfirmed = true
                playMedia()
            }
        }

        // Display the overlay in a smooth animation
        overlay.playerConfirm.alpha = 0f
        overlay.playerConfirm.scaleX = 0.8f
        overlay.playerConfirm.scaleY = 0.8f
        overlay.playerConfirm.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(300).start()

        // hide controls until we show the player button
        controlsView.alpha = 0f
    }

    private fun toggleSubtitles(toggleView: ImageView, forceOn: Boolean = false) {
        if (forceOn || !subtitleContainer.isVisible) {
            subtitleContainer.isVisible = true
            toggleView.setImageResource(R.drawable.ic_subtitles_on, ThemeHelper.accentColor)
            preferences.edit { putBoolean("subtitles", true) }
        } else {
            subtitleContainer.isVisible = false
            toggleView.setImageResource(R.drawable.ic_subtitles_off)
            preferences.edit { putBoolean("subtitles", false) }
        }
    }

    private fun updatePauseViewIcon() {
        val exo = this.exo ?: return

        val icon = if (exo.playWhenReady) R.drawable.ic_video_pause else R.drawable.ic_video_play

        val pauseView = controlsView.find<ImageView>(R.id.pause)
        if (!exo.playWhenReady) {
            pauseView.setImageResource(icon)
        } else {
            pauseView.setImageResource(icon, ThemeHelper.accentColor)
        }
    }

    override fun currentVideoProgress(): ProgressInfo? {
        val duration = exo?.contentDuration?.takeIf { it > 0 } ?: return null
        val position = exo?.currentPosition?.takeIf { it >= 0 } ?: return null
        val buffered = exo?.contentBufferedPosition?.takeIf { it >= 0 } ?: return null

        return ProgressInfo(
            position.toFloat() / duration, buffered.toFloat() / duration,
            duration = Duration.millis(duration)
        )
    }

    @OptIn(UnstableApi::class)
    private fun startVideo() {
        logger.info { "$effectiveUri, ${exo == null}, $isPlaying" }
        if (exo != null || !isPlaying) {
            return
        }

        showBusyIndicator()

        logger.info { "Starting exo for $effectiveUri" }


        val mediaSource = createMediaSource()

        exo = ExoPlayerRecycler.get(context).apply {
            setVideoTextureView(find(R.id.texture_view))

            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = !videoPauseState.value
            volume = 0f

            trackSelectionParameters = TrackSelectionParameters.DEFAULT.buildUpon()
                .setSelectUndeterminedTextLanguage(true)
                .build()

            // don't forget to remove listeners in stop()
            addListener(playerListener)

            setMediaSource(mediaSource, false)
            prepare()

            SeekController.restore(config.mediaUri.id, this)
        }

        // apply volume to the exo player if needed.
        volumeController?.applyMuteState()

        // update pause icon. The player got reset
        updatePauseViewIcon()
    }

    @OptIn(UnstableApi::class)
    private fun createMediaSource(): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(context) {
            val cache = context.injector.instance<Cache>()
            InputStreamCacheDataSource(cache)
        }

        // we could also go with a DefaultExtractorsFactory, but we already know the possible
        // formats, so we just limit it to what we expect
        val extractorsFactory = ExtractorsFactory {
            arrayOf(
                FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
                SubtitleExtractor(
                    WebvttParser(), Format.Builder()
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        val subtitleConfigs =
            config.subtitles.minByOrNull(Api.Feed.Subtitle::priority)?.let { subtitle ->
                logger.info { "Initialize subtitle from ${subtitle.path}" }
                val subtitleConfig =
                    SubtitleConfiguration.Builder(UriHelper.NoPreload.subtitle(subtitle.path))
                        .setLanguage(subtitle.language)
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()

                listOf(subtitleConfig)
            }

        val mediaItem = MediaItem.Builder()
            .setUri(effectiveUri)
            .setSubtitleConfigurations(subtitleConfigs.orEmpty())
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    private fun stopVideo() {
        this.exo?.let { exo ->
            logger.info { "Stopping exo for $effectiveUri" }

            // store position so we can restore it later
            SeekController.store(config.mediaUri.id, exo)

            // continue music if there is any, but give it some small delay, so
            // another video player could take over before starting the actual playback.
            volumeController?.abandonAudioFocusSoon()

            // reset the player now. No one else has access to it anymore.
            this.exo = null

            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.M) {
                exo.stop()
                exo.clearMediaItems()

                exo.removeListener(playerListener)
                exo.setVideoTextureView(null)

                ExoPlayerRecycler.release(exo)
            } else {
                // on android 6 we just release the player, cause we got some crashes.
                // So, maybe this helps.
                exo.release()
            }
        }
    }

    override fun playMedia() {
        if (config.mediaUri.delay && !isConfirmed) {
            return
        }

        super.playMedia()
        startVideo()
    }

    override fun stopMedia() {
        super.stopMedia()
        stopVideo()
    }

    override fun rewind() {
        exo?.seekTo(0L)
    }

    override fun userSeekable(): Boolean {
        return true
    }

    override fun userSeekTo(fraction: Float) {
        this.exo?.let { exo ->
            exo.seekTo((exo.duration * fraction.coerceAtLeast(0f)).toLong())
        }
    }

    override fun onSeekbarVisibilityChanged(show: Boolean) {
        controlsView.animate().cancel()

        if (show) {
            controlsView.animate()
                .alpha(0f)
                .translationY(controlsView.height.toFloat())
                .withEndAction { controlsView.isVisible = false }
                .setInterpolator(AccelerateInterpolator())
                .start()

        } else {
            controlsView.alpha = 0f
            controlsView.visibility = View.VISIBLE
            controlsView.animate()
                .alpha(0.5f)
                .translationY(0f)
                .setListener(null)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        val exo = this.exo
        if (exo != null) {
            val tapPosition = event.x / width

            // always seek 10 seconds or 25%, whatever is less
            val skipFraction = 10_000L.coerceAtMost(exo.duration / 4)

            if (tapPosition < 0.25) {
                userSeekTo((exo.currentPosition - skipFraction) / exo.duration.toFloat())
                animateMediaControls(find(R.id.rewind), direction = -1)
                return true

            } else if (tapPosition > 0.75) {
                userSeekTo((exo.currentPosition + skipFraction) / exo.duration.toFloat())
                animateMediaControls(find(R.id.fast_forward), direction = +1)
                return true
            }
        }

        return super.onDoubleTap(event)
    }

    private fun animateMediaControls(imageView: ImageView, direction: Int) {
        imageView.isVisible = true

        val xTrans = imageView.width * 0.25f * direction
        ObjectAnimator.ofPropertyValuesHolder(
            imageView,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.7f, 0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -xTrans, xTrans)
        ).apply {

            duration = 300
            interpolator = AccelerateDecelerateInterpolator()

            doOnEnd { imageView.isVisible = false }

            start()
        }
    }

    @OptIn(UnstableApi::class)
    private val playerListener = object : Player.Listener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            showBusyIndicator(playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePauseViewIcon()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (viewAspect < 0) {
                viewAspect =
                    videoSize.width.toFloat() / videoSize.height.toFloat() * videoSize.pixelWidthHeightRatio
            }
        }

        override fun onRenderedFirstFrame() {
            hideBusyIndicator()
            if (isPlaying) {
                updateTimeline()
                onMediaShown()
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitleContainer.removeAllViews()

            for (cue in cueGroup.cues) {
                val text = cue.text ?: continue

                val textView = SubtitleBinding
                    .inflate(layoutInflater, subtitleContainer, true)
                    .root

                textView.text = text
            }
        }
    }
}