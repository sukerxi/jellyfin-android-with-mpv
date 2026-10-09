package org.jellyfin.mobile.player.ui

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.mpv.MpvPlayer
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.player.ui.playermenuhelper.SkipMediaSegmentButton
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

/**
 *  Provides a menu UI for audio, subtitle and video stream selection
 */
class PlayerMenus(
    private val fragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val playerControlsBinding: ExoPlayerControlViewBinding,
) : PopupMenu.OnDismissListener,
    KoinComponent {

    private val context = playerBinding.root.context
    private val qualityOptionsProvider: QualityOptionsProvider by inject()
    private val appPreferences: AppPreferences by inject()
    private val playPauseContainer: View by playerControlsBinding::playPauseContainer
    private val previousButton: View by playerControlsBinding::previousButton
    private val nextButton: View by playerControlsBinding::nextButton
    private val previousChapterButton: View by playerControlsBinding::previousChapterButton
    private val nextChapterButton: View by playerControlsBinding::nextChapterButton
    private val lockScreenButton: View by playerControlsBinding::lockScreenButton
    private val audioStreamsButton: View by playerControlsBinding::audioStreamsButton
    private val subtitlesButton: ImageButton by playerControlsBinding::subtitlesButton
    private val speedButton: View by playerControlsBinding::speedButton
    private val qualityButton: View by playerControlsBinding::qualityButton
    private val decoderButton: View by playerControlsBinding::decoderButton
    private val infoButton: View by playerControlsBinding::infoButton
    private val playbackInfo: TextView by playerBinding::playbackInfo
    private val playbackInfoContainer: View by playerBinding::playbackInfoContainer
    private val playbackInfoToggle: TextView by playerBinding::playbackInfoToggle
    private val playbackInfoMore: TextView by playerBinding::playbackInfoMore
    private val audioStreamsMenu: PopupMenu = createAudioStreamsMenu()
    private val subtitlesMenu: PopupMenu = createSubtitlesMenu()
    private val speedMenu: PopupMenu = createSpeedMenu()
    private val qualityMenu: PopupMenu = createQualityMenu()
    private val decoderMenu: PopupMenu = createDecoderMenu()
    private val chapterMarkingContainer: ConstraintLayout by playerControlsBinding::chapterMarkingContainer
    private val exoProgress: DefaultTimeBar by playerControlsBinding::exoProgress
    private val seekBarContainer: View by playerControlsBinding::seekBarContainer
    private val trickplayContainer: View by playerControlsBinding::trickplayContainer
    private val trickplayThumbnail: AppCompatImageView by playerControlsBinding::trickplayThumbnail
    private val trickplayChapterName: AppCompatTextView by playerControlsBinding::trickplayChapterName
    private val trickplayTime: AppCompatTextView by playerControlsBinding::trickplayTime
    private val skipSegmentButton: Button by playerBinding::skipSegmentButton

    private var subtitleCount = 0
    private var subtitlesEnabled = false
    private var playbackInfoExpanded = false
    private var currentMediaSource: JellyfinMediaSource? = null
    private var decoderType: DecoderType? = null

    private val trickplayHelper = TrickplayHelper(
        trickplayContainer,
        trickplayThumbnail,
        seekBarContainer,
        trickplayChapterName,
        trickplayTime,
    )

    private val playerMenuHelper: PlayerMenuHelper = PlayerMenuHelper(
        skipMediaSegmentButton = SkipMediaSegmentButton(skipSegmentButton, fragment::onSkipMediaSegment),
    )

    init {
        exoProgress.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = trickplayHelper.onScrubMove(position)
            override fun onScrubMove(timeBar: TimeBar, position: Long) = trickplayHelper.onScrubMove(position)
            override fun onScrubStop(
                timeBar: TimeBar,
                position: Long,
                cancelled: Boolean,
            ) = trickplayHelper.onScrubStop()
        })

        previousButton.setOnClickListener {
            fragment.onSkipToPrevious()
        }
        nextButton.setOnClickListener {
            fragment.onSkipToNext()
        }
        previousChapterButton.setOnClickListener {
            fragment.onPreviousChapter()
        }
        nextChapterButton.setOnClickListener {
            fragment.onNextChapter()
        }
        lockScreenButton.setOnClickListener {
            fragment.playerLockScreenHelper.lockScreen()
        }
        audioStreamsButton.setOnClickListener {
            fragment.suppressControllerAutoHide(true)
            audioStreamsMenu.show()
        }
        subtitlesButton.setOnClickListener {
            when (subtitleCount) {
                0 -> return@setOnClickListener
                1 -> {
                    fragment.toggleSubtitles { enabled ->
                        subtitlesEnabled = enabled
                        updateSubtitlesButton()
                    }
                }
                else -> {
                    fragment.suppressControllerAutoHide(true)
                    subtitlesMenu.show()
                }
            }
        }
        speedButton.setOnClickListener {
            fragment.suppressControllerAutoHide(true)
            speedMenu.show()
        }
        qualityButton.setOnClickListener {
            fragment.suppressControllerAutoHide(true)
            qualityMenu.show()
        }
        decoderButton.setOnClickListener {
            fragment.suppressControllerAutoHide(true)
            decoderMenu.show()
        }
        infoButton.setOnClickListener {
            playbackInfoContainer.isVisible = !playbackInfoContainer.isVisible
            // Refresh runtime stats each time the panel is opened
            if (playbackInfoContainer.isVisible) {
                refreshPlaybackInfo()
                currentMediaSource?.let { source -> playbackInfoMore.text = buildPlaybackInfoDetails(source) }
            }
        }
        playbackInfoContainer.setOnClickListener {
            dismissPlaybackInfo()
        }
        playbackInfoToggle.setOnClickListener {
            playbackInfoExpanded = !playbackInfoExpanded
            updatePlaybackInfoExpanded()
        }

        fragment.setPlayerMenuHelper(playerMenuHelper)
    }

    fun onQueueItemChanged(mediaSource: JellyfinMediaSource, hasNext: Boolean) {
        // previousButton is always enabled and will rewind if at the start of the queue
        nextButton.isEnabled = hasNext

        trickplayHelper.onMediaSourceChanged(mediaSource)

        val chapters = mediaSource.item?.chapters
        updateLayoutConstraints(!chapters.isNullOrEmpty())
        val runTimeTicks = mediaSource.item?.runTimeTicks
        setChapterMarkings(chapters, runTimeTicks)

        val videoStream = mediaSource.selectedVideoStream

        val audioStreams = mediaSource.audioStreams
        buildMenuItems(
            audioStreamsMenu.menu,
            AUDIO_MENU_GROUP,
            audioStreams,
            mediaSource.selectedAudioStream,
        )

        val subtitleStreams = mediaSource.subtitleStreams
        val selectedSubtitleStream = mediaSource.selectedSubtitleStream
        buildMenuItems(
            subtitlesMenu.menu,
            SUBTITLES_MENU_GROUP,
            subtitleStreams,
            selectedSubtitleStream,
            true,
        )
        subtitleCount = subtitleStreams.size
        subtitlesEnabled = selectedSubtitleStream != null

        updateSubtitlesButton()

        val height = videoStream?.height
        val width = videoStream?.width
        when (mediaSource) {
            is LocalJellyfinMediaSource -> qualityButton.isVisible = false
            is RemoteJellyfinMediaSource -> if (height != null && width != null) {
                buildQualityMenu(qualityMenu.menu, mediaSource.maxStreamingBitrate, width, height)
            }
        }

        currentMediaSource = mediaSource
        refreshPlaybackInfo()

        playbackInfoMore.text = buildPlaybackInfoDetails(mediaSource)
        updatePlaybackInfoExpanded()
    }

    /**
     * Rebuild the basic playback info (play method, engine, streams) from the current media source.
     */
    private fun refreshPlaybackInfo() {
        val mediaSource = currentMediaSource ?: return
        val playMethod = context.getString(R.string.playback_info_play_method, mediaSource.playMethod)
        val engineInfo = buildEngineInfo()
        val videoTracksInfo = buildMediaStreamsInfo(
            mediaStreams = listOfNotNull(mediaSource.selectedVideoStream),
            prefix = R.string.playback_info_video_streams,
            maxStreams = MAX_VIDEO_STREAMS_DISPLAY,
            streamSuffix = { stream ->
                stream.bitRate?.let { bitrate -> " (${formatBitrate(bitrate.toDouble())})" }.orEmpty()
            },
        )
        val audioTracksInfo = buildMediaStreamsInfo(
            mediaStreams = mediaSource.audioStreams,
            prefix = R.string.playback_info_audio_streams,
            maxStreams = MAX_AUDIO_STREAMS_DISPLAY,
            streamSuffix = { stream ->
                stream.language?.let { lang -> " ($lang)" }.orEmpty()
            },
        )

        playbackInfo.text = listOf(
            playMethod,
            engineInfo,
            videoTracksInfo,
            audioTracksInfo,
        ).joinToString("\n\n")
    }

    /**
     * Player engine (mpv / ExoPlayer) and the decoder currently in use.
     */
    private fun buildEngineInfo(): String {
        val player = fragment.currentPlayer
        val engine = when {
            player is MpvPlayer -> "mpv"
            player is ExoPlayer -> "ExoPlayer"
            else -> when (appPreferences.videoPlayerType) {
                VideoPlayerType.MPV_PLAYER -> "mpv"
                VideoPlayerType.EXO_PLAYER -> "ExoPlayer"
                VideoPlayerType.EXTERNAL_PLAYER -> context.getString(R.string.video_player_external)
                else -> appPreferences.videoPlayerType
            }
        }
        val decoder = decoderType?.let { type ->
            context.getString(
                when (type) {
                    DecoderType.HARDWARE -> R.string.menu_item_hardware_decoding
                    DecoderType.SOFTWARE -> R.string.menu_item_software_decoding
                },
            )
        }
        return listOfNotNull(engine, decoder).joinToString(" · ")
    }

    /**
     * Build the collapsible "more info" section: container details and per-stream technical info.
     */
    private fun buildPlaybackInfoDetails(mediaSource: JellyfinMediaSource): String {
        val sourceInfo = mediaSource.sourceInfo
        val lines = mutableListOf<String>()

        // Runtime stats (refreshed each time the panel is opened/expanded)
        fragment.currentPlayer?.let { player ->
            val speed = player.playbackParameters.speed
                .takeIf { it != 1f }
                ?.let { "%.2gx".format(Locale.getDefault(), it) }
            val buffered = (player.bufferedPosition - player.currentPosition)
                .takeIf { it > 1000 }
                ?.let { context.getString(R.string.playback_info_buffered, formatDuration(it)) }
            val duration = player.duration
                .takeIf { it > 0 }
                ?: sourceInfo.runTimeTicks?.let { it / 10_000 }
                ?: 0L
            val stats = listOfNotNull(
                speed,
                buffered,
                duration.takeIf { it > 0 }?.let { formatDuration(it) },
            )
            if (stats.isNotEmpty()) lines += stats.joinToString(" · ")
        }

        // Streaming bitrate cap (remote sources only)
        if (mediaSource is RemoteJellyfinMediaSource) {
            mediaSource.maxStreamingBitrate?.let { bitrate ->
                lines += context.getString(
                    R.string.playback_info_bitrate_limit,
                    formatBitrate(bitrate.toDouble()),
                )
            }
        }

        // Container
        sourceInfo.container?.takeUnless(String::isBlank)?.let { container ->
            val details = listOfNotNull(
                sourceInfo.size?.let { formatFileSize(it) },
                sourceInfo.bitrate?.let { formatBitrate(it.toDouble()) },
            ).joinToString(" · ")
            lines += if (details.isEmpty()) container else "$container · $details"
        }

        // Video streams
        mediaSource.mediaStreams
            .filter { it.type == MediaStreamType.VIDEO }
            .forEach { stream ->
                val parts = listOfNotNull(
                    stream.codec?.uppercase(Locale.ROOT),
                    stream.profile?.takeUnless(String::isBlank),
                    stream.level?.let { "L${"%.1f".format(Locale.getDefault(), it)}" },
                    stream.width?.let { w -> stream.height?.let { h -> "${w}x$h" } },
                    (stream.realFrameRate ?: stream.averageFrameRate)?.let { "%.6g fps".format(Locale.getDefault(), it) },
                    stream.bitDepth?.let { "${it}-bit" },
                    stream.isInterlaced.takeIf { it }?.let { "interlaced" },
                    stream.videoRange?.takeIf { it != VideoRange.UNKNOWN }?.name,
                    stream.aspectRatio?.takeUnless(String::isBlank),
                )
                if (parts.isNotEmpty()) lines += "V: ${parts.joinToString(" · ")}"
            }

        // Audio streams
        mediaSource.mediaStreams
            .filter { it.type == MediaStreamType.AUDIO }
            .forEach { stream ->
                val parts = listOfNotNull(
                    stream.codec?.uppercase(Locale.ROOT),
                    stream.profile?.takeUnless(String::isBlank),
                    stream.language?.takeUnless(String::isBlank),
                    stream.channels?.let { ch -> stream.channelLayout?.let { "$ch ch ($it)" } ?: "${ch} ch" },
                    stream.sampleRate?.let { "%.1f kHz".format(Locale.getDefault(), it / 1000.0) },
                    stream.bitDepth?.let { "${it}-bit" },
                    stream.bitRate?.let { formatBitrate(it.toDouble()) },
                )
                if (parts.isNotEmpty()) lines += "A: ${parts.joinToString(" · ")}"
            }

        // Subtitle streams
        mediaSource.mediaStreams
            .filter { it.type == MediaStreamType.SUBTITLE }
            .forEach { stream ->
                val parts = listOfNotNull(
                    stream.codec?.uppercase(Locale.ROOT),
                    stream.language?.takeUnless(String::isBlank),
                    stream.isExternal.takeIf { it }?.let { "ext" },
                    stream.isForced.takeIf { it }?.let { "forced" },
                    stream.isDefault.takeIf { it }?.let { "default" },
                )
                if (parts.isNotEmpty()) lines += "S: ${parts.joinToString(" · ")}"
            }

        return lines.joinToString("\n")
    }

    private fun updatePlaybackInfoExpanded() {
        playbackInfoMore.isVisible = playbackInfoExpanded
        playbackInfoToggle.text = context.getString(
            if (playbackInfoExpanded) R.string.playback_info_less else R.string.playback_info_more,
        )
        // Runtime stats are only accurate when (re)built; refresh on expand and while visible
        if (playbackInfoExpanded) {
            currentMediaSource?.let { playbackInfoMore.text = buildPlaybackInfoDetails(it) }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        val formatted = if (unitIndex == 0) value.toInt().toString() else "%.2f".format(Locale.getDefault(), value)
        return "$formatted ${units[unitIndex]}"
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.getDefault(), minutes, seconds)
        }
    }

    private fun updateLayoutConstraints(hasChapters: Boolean) {
        if (hasChapters) {
            previousButton.updateLayoutParams<ConstraintLayout.LayoutParams> { endToStart = previousChapterButton.id }
            nextButton.updateLayoutParams<ConstraintLayout.LayoutParams> { startToEnd = nextChapterButton.id }
            previousChapterButton.isVisible = true
            nextChapterButton.isVisible = true
        } else {
            previousButton.updateLayoutParams<ConstraintLayout.LayoutParams> { endToStart = playPauseContainer.id }
            nextButton.updateLayoutParams<ConstraintLayout.LayoutParams> { startToEnd = playPauseContainer.id }
            previousChapterButton.isVisible = false
            nextChapterButton.isVisible = false
        }
    }

    private fun setChapterMarkings(chapters: List<ChapterInfo>?, runTimeTicks: Long?) {
        chapterMarkingContainer.removeAllViews()

        if (chapters.isNullOrEmpty() || runTimeTicks == null || runTimeTicks <= 0) {
            playerMenuHelper.chapterMarkings.setMarkings(emptyList())
            return
        }

        val chapterMarkings = chapters.map { ch ->
            val percent = ch.startPositionTicks.toFloat() / runTimeTicks
            val bias = percent.coerceIn(0f, 1f)
            val marking = ChapterMarking(context, bias)
            chapterMarkingContainer.addView(marking.view) // Add view as side effect to avoid another loop
            marking
        }
        playerMenuHelper.chapterMarkings.setMarkings(chapterMarkings)
    }

    private fun buildMediaStreamsInfo(
        mediaStreams: List<MediaStream>,
        @StringRes prefix: Int,
        maxStreams: Int,
        streamSuffix: (MediaStream) -> String,
    ): String = mediaStreams.joinToString(
        "\n",
        "${fragment.getString(prefix)}:\n",
        limit = maxStreams,
        truncated = fragment.getString(R.string.playback_info_and_x_more, mediaStreams.size - maxStreams),
    ) { stream ->
        val title = stream.displayTitle?.takeUnless(String::isEmpty)
            ?: fragment.getString(R.string.playback_info_stream_unknown_title)
        val suffix = streamSuffix(stream)
        "- $title$suffix"
    }

    private fun createSubtitlesMenu() = PopupMenu(context, subtitlesButton).apply {
        setOnMenuItemClickListener { clickedItem ->
            // Immediately apply changes to the menu, necessary when direct playing
            // When transcoding, the updated media source will cause the menu to be rebuilt
            clickedItem.isChecked = true

            // The itemId is the MediaStream.index of the track
            val selectedSubtitleStreamIndex = clickedItem.itemId
            fragment.onSubtitleSelected(selectedSubtitleStreamIndex) {
                subtitlesEnabled = selectedSubtitleStreamIndex >= 0
                updateSubtitlesButton()
            }
            true
        }
        setOnDismissListener(this@PlayerMenus)
    }

    private fun createAudioStreamsMenu() = PopupMenu(context, audioStreamsButton).apply {
        setOnMenuItemClickListener { clickedItem: MenuItem ->
            // Immediately apply changes to the menu, necessary when direct playing
            // When transcoding, the updated media source will cause the menu to be rebuilt
            clickedItem.isChecked = true

            // The itemId is the MediaStream.index of the track
            fragment.onAudioTrackSelected(clickedItem.itemId) {}
            true
        }
        setOnDismissListener(this@PlayerMenus)
    }

    private fun createSpeedMenu() = PopupMenu(context, speedButton).apply {
        for (step in SPEED_MENU_STEP_MIN..SPEED_MENU_STEP_MAX) {
            val newSpeed = step * SPEED_MENU_STEP_SIZE
            menu.add(SPEED_MENU_GROUP, step, Menu.NONE, "${newSpeed}x").isChecked = newSpeed == 1f
        }
        menu.setGroupCheckable(SPEED_MENU_GROUP, true, true)
        setOnMenuItemClickListener { clickedItem: MenuItem ->
            fragment.onSpeedSelected(clickedItem.itemId * SPEED_MENU_STEP_SIZE).also { success ->
                if (success) clickedItem.isChecked = true
            }
        }
        setOnDismissListener(this@PlayerMenus)
    }

    private fun createQualityMenu() = PopupMenu(context, qualityButton).apply {
        setOnMenuItemClickListener { item: MenuItem ->
            val newBitrate = item.itemId.takeUnless { bitrate -> bitrate == 0 }
            fragment.onBitrateChanged(newBitrate) {
                // Ignore callback - menu will be recreated if bitrate changes
            }
            true
        }
        setOnDismissListener(this@PlayerMenus)
    }

    private fun createDecoderMenu() = PopupMenu(context, qualityButton).apply {
        menu.add(
            DECODER_MENU_GROUP,
            DecoderType.HARDWARE.ordinal,
            Menu.NONE,
            context.getString(R.string.menu_item_hardware_decoding),
        )
        menu.add(
            DECODER_MENU_GROUP,
            DecoderType.SOFTWARE.ordinal,
            Menu.NONE,
            context.getString(R.string.menu_item_software_decoding),
        )
        menu.setGroupCheckable(DECODER_MENU_GROUP, true, true)

        setOnMenuItemClickListener { clickedItem: MenuItem ->
            val type = DecoderType.values()[clickedItem.itemId]
            fragment.onDecoderSelected(type)
            clickedItem.isChecked = true
            true
        }
        setOnDismissListener(this@PlayerMenus)
    }

    fun updatedSelectedDecoder(type: DecoderType) {
        decoderType = type
        decoderMenu.menu.findItem(type.ordinal).isChecked = true
        if (playbackInfoContainer.isVisible) refreshPlaybackInfo()
    }

    private fun buildMenuItems(
        menu: Menu,
        groupId: Int,
        mediaStreams: List<MediaStream>,
        selectedStream: MediaStream?,
        showNone: Boolean = false,
    ) {
        menu.clear()
        val itemNone = when {
            showNone -> menu.add(groupId, -1, Menu.NONE, fragment.getString(R.string.menu_item_none))
            else -> null
        }
        var selectedItem: MenuItem? = itemNone
        val menuItems = mediaStreams.map { mediaStream ->
            val title = mediaStream.displayTitle ?: "${mediaStream.language} (${mediaStream.codec})"
            menu.add(groupId, mediaStream.index, Menu.NONE, title).also { item ->
                if (mediaStream === selectedStream) {
                    selectedItem = item
                }
            }
        }
        menu.setGroupCheckable(groupId, true, true)
        // Check selected item or first item if possible
        (selectedItem ?: menuItems.firstOrNull())?.isChecked = true
    }

    private fun updateSubtitlesButton() {
        subtitlesButton.isVisible = subtitleCount > 0
        val stateSet = intArrayOf(android.R.attr.state_checked * if (subtitlesEnabled) 1 else -1)
        subtitlesButton.setImageState(stateSet, true)
    }

    private fun buildQualityMenu(menu: Menu, maxStreamingBitrate: Int?, videoWidth: Int, videoHeight: Int) {
        menu.clear()
        val options = qualityOptionsProvider.getApplicableQualityOptions(videoWidth, videoHeight)
        options.forEach { option ->
            val title = when (val bitrate = option.bitrate) {
                0 -> context.getString(R.string.menu_item_auto)
                else -> "${option.maxHeight}p - ${formatBitrate(bitrate.toDouble())}"
            }
            menu.add(QUALITY_MENU_GROUP, option.bitrate, Menu.NONE, title)
        }
        menu.setGroupCheckable(QUALITY_MENU_GROUP, true, true)

        val selection = maxStreamingBitrate?.let(menu::findItem) ?: menu[menu.size - 1] // Last element is "auto"
        selection.isChecked = true
    }

    fun dismissPlaybackInfo() {
        playbackInfoContainer.isVisible = false
    }

    override fun onDismiss(menu: PopupMenu) {
        fragment.suppressControllerAutoHide(false)
        fragment.onPopupDismissed()
    }

    private fun formatBitrate(bitrate: Double): String {
        val (value, unit) = when {
            bitrate > BITRATE_MEGA_BIT -> bitrate / BITRATE_MEGA_BIT to " Mbps"
            bitrate > BITRATE_KILO_BIT -> bitrate / BITRATE_KILO_BIT to " kbps"
            else -> bitrate to " bps"
        }

        // Remove unnecessary trailing zeros
        val formatted = "%.2f".format(Locale.getDefault(), value).removeSuffix(".00")
        return formatted + unit
    }

    companion object {
        private const val SUBTITLES_MENU_GROUP = 0
        private const val AUDIO_MENU_GROUP = 1
        private const val SPEED_MENU_GROUP = 2
        private const val QUALITY_MENU_GROUP = 3
        private const val DECODER_MENU_GROUP = 4

        private const val MAX_VIDEO_STREAMS_DISPLAY = 3
        private const val MAX_AUDIO_STREAMS_DISPLAY = 5

        private const val BITRATE_MEGA_BIT = 1_000_000
        private const val BITRATE_KILO_BIT = 1_000

        private const val SPEED_MENU_STEP_SIZE = 0.25f
        private const val SPEED_MENU_STEP_MIN = 2 // → 0.5x
        private const val SPEED_MENU_STEP_MAX = 8 // → 2x
    }
}
