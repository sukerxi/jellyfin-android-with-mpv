package org.jellyfin.mobile.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.mpv.MpvPlayer
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.source.TranscodeReasonFormatter
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodeReason
import org.jellyfin.sdk.model.api.VideoRange
import java.util.Locale

/**
 * 阶段 5：播放信息面板（替代 fragment_player.xml 的 playback_info_container +
 * PlayerMenus.rebuildPlaybackInfo 的动态 inflate）。
 *
 * 沿用此前重构过的“只看有效轨道/紧凑卡片”规则：分组小节 + 标签/值行 + 元数据 chip 行。
 */

private val CardBackground = Color(0xCC000000)
private val TextPrimary = Color(0xF2FFFFFF)
private val TextSubtitle = Color(0x80FFFFFF)
private val ChipBackground = Color(0x1FFFFFFF)
private val DividerColor = Color(0x14FFFFFF)
private val AccentColor = Color(0xFF00A4DC)
private val CardCornerRadius = 16.dp
private val ChipCornerRadius = 6.dp

/** 信息面板数据模型。 */
data class PlaybackInfoModel(
    val itemName: String?,
    val sections: List<PlaybackInfoSection>,
)

data class PlaybackInfoSection(
    val title: String,
    val rows: List<PlaybackInfoRow>,
    val chips: List<String> = emptyList(),
)

data class PlaybackInfoRow(
    val label: String,
    val value: String,
)

/** 导出为纯文本：反馈播放问题时可直接粘贴，不必逐行长按选取。 */
fun PlaybackInfoModel.toPlainText(title: String): String = buildString {
    appendLine(title)
    itemName?.takeUnless(String::isBlank)?.let { appendLine(it) }
    sections.forEach { section ->
        appendLine()
        appendLine(section.title)
        if (section.chips.isNotEmpty()) appendLine(section.chips.joinToString(" · "))
        section.rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
    }
}

private data class PlaybackInfoLabels(
    val sectionOverview: String,
    val sectionMedia: String,
    val sectionVideo: String,
    val sectionAudio: String,
    val sectionSubtitle: String,
    val labelPlayMethod: String,
    /** 播放方式的本地化文案：枚举名直接上屏（DIRECTPLAY / TRANSCODE）对用户没有意义。 */
    val playMethodDirectPlay: String,
    val playMethodDirectStream: String,
    val playMethodTranscode: String,
    val labelEngine: String,
    val labelTranscodeReason: String,
    val transcodeReasonUnknown: String,
    /** Localized text per known [TranscodeReason]; resolved here because it needs the resources. */
    val transcodeReasonTexts: Map<TranscodeReason, String>,
    val nativeAssRendering: String,
    val labelSpeed: String,
    val labelBitrateLimit: String,
    val labelContainer: String,
    val labelFileSize: String,
    val labelTotalBitrate: String,
    val labelBitrate: String,
    val labelAspectRatio: String,
    val labelLanguage: String,
    val labelTrack: String,
    val off: String,
    val interlaced: String,
    val external: String,
    val forced: String,
    val default: String,
    val hardwareDecoding: String,
    val softwareDecoding: String,
    val externalPlayer: String,
)

@Composable
private fun rememberPlaybackInfoLabels(): PlaybackInfoLabels = PlaybackInfoLabels(
    sectionOverview = stringResource(R.string.playback_info_section_overview),
    sectionMedia = stringResource(R.string.playback_info_section_media),
    sectionVideo = stringResource(R.string.playback_info_video),
    sectionAudio = stringResource(R.string.playback_info_audio),
    sectionSubtitle = stringResource(R.string.playback_info_subtitle),
    labelPlayMethod = stringResource(R.string.playback_info_label_play_method),
    playMethodDirectPlay = stringResource(R.string.playback_info_play_method_direct_play),
    playMethodDirectStream = stringResource(R.string.playback_info_play_method_direct_stream),
    playMethodTranscode = stringResource(R.string.playback_info_play_method_transcode),
    labelEngine = stringResource(R.string.playback_info_label_engine),
    labelTranscodeReason = stringResource(R.string.playback_info_label_transcode_reason),
    transcodeReasonUnknown = stringResource(R.string.playback_info_transcode_reason_unknown),
    transcodeReasonTexts = buildMap {
        TranscodeReason.entries.forEach { reason ->
            TranscodeReasonFormatter.labelResOrNull(reason)?.let { res -> put(reason, stringResource(res)) }
        }
    },
    nativeAssRendering = stringResource(R.string.playback_info_native_ass_rendering),
    labelSpeed = stringResource(R.string.playback_info_label_speed),
    labelBitrateLimit = stringResource(R.string.playback_info_label_bitrate_limit),
    labelContainer = stringResource(R.string.playback_info_label_container),
    labelFileSize = stringResource(R.string.playback_info_label_file_size),
    labelTotalBitrate = stringResource(R.string.playback_info_label_total_bitrate),
    labelBitrate = stringResource(R.string.playback_info_label_bitrate),
    labelAspectRatio = stringResource(R.string.playback_info_label_aspect_ratio),
    labelLanguage = stringResource(R.string.playback_info_label_language),
    labelTrack = stringResource(R.string.playback_info_label_track),
    off = stringResource(R.string.playback_info_off),
    interlaced = stringResource(R.string.playback_info_chip_interlaced),
    external = stringResource(R.string.playback_info_chip_external),
    forced = stringResource(R.string.playback_info_chip_forced),
    default = stringResource(R.string.playback_info_chip_default),
    hardwareDecoding = stringResource(R.string.menu_item_hardware_decoding),
    softwareDecoding = stringResource(R.string.menu_item_software_decoding),
    externalPlayer = stringResource(R.string.video_player_external),
)

/**
 * 依据当前 mediaSource 构建信息面板模型（逻辑与原 rebuildPlaybackInfo 一致）。
 */
@Composable
fun rememberPlaybackInfoModel(
    mediaSource: JellyfinMediaSource?,
    player: Player?,
    decoderType: DecoderType?,
    appPreferences: AppPreferences,
    transcodeReasons: List<TranscodeReason> = emptyList(),
): PlaybackInfoModel {
    val labels = rememberPlaybackInfoLabels()
    return remember(mediaSource, player, decoderType, labels, transcodeReasons) {
        if (mediaSource == null) {
            PlaybackInfoModel(itemName = null, sections = emptyList())
        } else {
            buildPlaybackInfoModel(mediaSource, player, decoderType, appPreferences, labels, transcodeReasons)
        }
    }
}

@Suppress("LongMethod")
private fun buildPlaybackInfoModel(
    mediaSource: JellyfinMediaSource,
    player: Player?,
    decoderType: DecoderType?,
    appPreferences: AppPreferences,
    labels: PlaybackInfoLabels,
    transcodeReasons: List<TranscodeReason>,
): PlaybackInfoModel {
    val sections = mutableListOf<PlaybackInfoSection>()
    val itemName = mediaSource.item?.name?.takeUnless(String::isBlank)

    // ---- Overview ----
    val overviewRows = mutableListOf<PlaybackInfoRow>()
    // 枚举名不能直接上屏：面板里其他行都是本地化中文，DIRECTPLAY / TRANSCODE 混在里面
    // 既看不懂，也和「转码原因」那一行的解释对不上。
    val playMethodLabel = when (mediaSource.playMethod) {
        PlayMethod.DIRECT_PLAY -> labels.playMethodDirectPlay
        PlayMethod.DIRECT_STREAM -> labels.playMethodDirectStream
        PlayMethod.TRANSCODE -> labels.playMethodTranscode
        else -> mediaSource.playMethod.toString()
    }
    overviewRows += PlaybackInfoRow(labels.labelPlayMethod, playMethodLabel)
    buildEngineInfo(player, decoderType, mediaSource, appPreferences, labels).takeUnless(String::isBlank)?.let { engine ->
        overviewRows += PlaybackInfoRow(labels.labelEngine, engine)
    }
    player?.playbackParameters?.speed
        ?.takeIf { it != 1f }
        ?.let { speed ->
            overviewRows += PlaybackInfoRow(labels.labelSpeed, "%.2fx".format(Locale.getDefault(), speed))
        }
    if (mediaSource is RemoteJellyfinMediaSource) {
        mediaSource.maxStreamingBitrate?.let { bitrate ->
            overviewRows += PlaybackInfoRow(labels.labelBitrateLimit, formatBitrate(bitrate.toDouble()))
        }
    }
    // Only a transcoding session has a reason to explain. An unknown reason is stated explicitly
    // rather than hidden: the user asked the panel why, and silence would look like it forgot.
    if (mediaSource.playMethod == PlayMethod.TRANSCODE) {
        val value = transcodeReasons
            .distinct()
            .joinToString("\n") { reason -> labels.transcodeReasonTexts[reason] ?: reason.serialName }
            .ifEmpty { labels.transcodeReasonUnknown }
        overviewRows += PlaybackInfoRow(labels.labelTranscodeReason, value)
    }
    sections += PlaybackInfoSection(title = labels.sectionOverview, rows = overviewRows)

    // ---- Container / file ----
    val sourceInfo = mediaSource.sourceInfo
    val container = sourceInfo.container?.takeUnless(String::isBlank)
    if (container != null || sourceInfo.size != null || sourceInfo.bitrate != null) {
        val mediaRows = mutableListOf<PlaybackInfoRow>()
        container?.let { mediaRows += PlaybackInfoRow(labels.labelContainer, it) }
        sourceInfo.size?.let { size ->
            mediaRows += PlaybackInfoRow(labels.labelFileSize, formatFileSize(size))
        }
        sourceInfo.bitrate?.let { bitrate ->
            mediaRows += PlaybackInfoRow(labels.labelTotalBitrate, formatBitrate(bitrate.toDouble()))
        }
        sections += PlaybackInfoSection(title = labels.sectionMedia, rows = mediaRows)
    }

    // ---- Video ----
    mediaSource.selectedVideoStream?.let { stream ->
        val chips = buildList {
            stream.codec?.takeUnless(String::isBlank)?.let { add(it.uppercase(Locale.ROOT)) }
            stream.profile?.takeUnless(String::isBlank)?.let { add(it) }
            stream.level?.let { add("L${"%.1f".format(Locale.getDefault(), it)}") }
            val width = stream.width
            val height = stream.height
            if (width != null && height != null) add("$width×$height")
            (stream.realFrameRate ?: stream.averageFrameRate)?.let { frameRate ->
                add("%.6g fps".format(Locale.getDefault(), frameRate))
            }
            stream.bitDepth?.let { add("$it-bit") }
            if (stream.isInterlaced) add(labels.interlaced)
            stream.videoRange
                .takeIf { it != VideoRange.UNKNOWN }
                ?.let { add(it.name) }
        }
        val videoRows = mutableListOf<PlaybackInfoRow>()
        stream.bitRate?.let { bitrate ->
            videoRows += PlaybackInfoRow(labels.labelBitrate, formatBitrate(bitrate.toDouble()))
        }
        stream.aspectRatio?.takeUnless(String::isBlank)?.let { aspectRatio ->
            videoRows += PlaybackInfoRow(labels.labelAspectRatio, aspectRatio)
        }
        sections += PlaybackInfoSection(title = labels.sectionVideo, rows = videoRows, chips = chips)
    }

    // ---- Audio ----
    mediaSource.selectedAudioStream?.let { stream ->
        val chips = buildList {
            stream.codec?.takeUnless(String::isBlank)?.let { add(it.uppercase(Locale.ROOT)) }
            stream.profile?.takeUnless(String::isBlank)?.let { add(it) }
            stream.channelLayout
                ?.takeUnless(String::isBlank)
                ?.let { add(it) }
                ?: stream.channels?.let { add("$it ch") }
            stream.sampleRate?.let { sampleRate ->
                add("%.1f kHz".format(Locale.getDefault(), sampleRate / 1000.0))
            }
            stream.bitDepth?.let { add("$it-bit") }
        }
        val audioRows = mutableListOf<PlaybackInfoRow>()
        stream.language?.takeUnless(String::isBlank)?.let { language ->
            audioRows += PlaybackInfoRow(labels.labelLanguage, language)
        }
        stream.bitRate?.let { bitrate ->
            audioRows += PlaybackInfoRow(labels.labelBitrate, formatBitrate(bitrate.toDouble()))
        }
        sections += PlaybackInfoSection(title = labels.sectionAudio, rows = audioRows, chips = chips)
    }

    // ---- Subtitle ----
    val subtitleStream = mediaSource.selectedSubtitleStream
    val subtitleSection = if (subtitleStream == null) {
        PlaybackInfoSection(
            title = labels.sectionSubtitle,
            rows = listOf(PlaybackInfoRow(labels.labelTrack, labels.off)),
        )
    } else {
        val chips = buildList {
            subtitleStream.codec?.takeUnless(String::isBlank)?.let { add(it.uppercase(Locale.ROOT)) }
            subtitleStream.language?.takeUnless(String::isBlank)?.let { add(it) }
            if (subtitleStream.isExternal) add(labels.external)
            if (subtitleStream.isForced) add(labels.forced)
            if (subtitleStream.isDefault) add(labels.default)
        }
        if (chips.isNotEmpty()) {
            PlaybackInfoSection(title = labels.sectionSubtitle, rows = emptyList(), chips = chips)
        } else {
            val title = subtitleStream.displayTitle
                ?.takeUnless(String::isBlank)
                ?: formatStreamFallbackTitle(subtitleStream.codec, subtitleStream.language)
                    .takeUnless(String::isBlank)
                ?: subtitleStream.language.orEmpty()
            PlaybackInfoSection(
                title = labels.sectionSubtitle,
                rows = listOf(PlaybackInfoRow(labels.labelTrack, title)),
            )
        }
    }
    sections += subtitleSection

    return PlaybackInfoModel(itemName = itemName, sections = sections)
}

private fun buildEngineInfo(
    player: Player?,
    decoderType: DecoderType?,
    mediaSource: JellyfinMediaSource,
    appPreferences: AppPreferences,
    labels: PlaybackInfoLabels,
): String {
    val engine = when (player) {
        is MpvPlayer -> "mpv"
        is ExoPlayer -> "ExoPlayer"
        else -> when (appPreferences.videoPlayerType) {
            VideoPlayerType.MPV_PLAYER -> "mpv"
            VideoPlayerType.EXO_PLAYER -> "ExoPlayer"
            VideoPlayerType.EXTERNAL_PLAYER -> labels.externalPlayer
            else -> appPreferences.videoPlayerType
        }
    }
    val decoder = decoderType?.let { type ->
        when (type) {
            DecoderType.HARDWARE -> labels.hardwareDecoding
            DecoderType.SOFTWARE -> labels.softwareDecoding
        }
    }
    // The player instance is authoritative: the setting can already have been changed
    // (or be about to change) without the running player being recreated.
    // SSA/ASS is only rendered locally while the video is sent verbatim: a transcoded stream
    // already carries the burned-in subtitles, so the renderer is not involved at all.
    val isExoPlayer = player is ExoPlayer ||
        player == null && appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER
    val rendersAssNatively = isExoPlayer && appPreferences.resolveExoPlayerDirectPlayAss(
        isVideoTranscoding = mediaSource.playMethod == PlayMethod.TRANSCODE,
    )
    val renderers = if (rendersAssNatively) listOf(labels.nativeAssRendering) else emptyList()
    return (listOfNotNull(engine, decoder) + renderers).joinToString(" · ")
}

/**
 * 播放信息面板。
 *
 * 面板只占据控件层（标题栏/控制条）之间的区域：外层 [Box][controlsBounds] 与
 * [PlayerControlsState] 同尺寸，绘制在控制层之后因而位于其上方，同时不会挡住
 * 标题栏与控制条（点击这些区域仍能操作播放器）。
 *
 * @param controlsBounds 控件层的内边距（系统栏/刘海），由 [PlayerScreen] 传入
 */
@Composable
fun PlaybackInfoPanel(
    model: PlaybackInfoModel,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    controlsBounds: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(controlsBounds)
            // Dismiss when tapping outside of the card, without covering the control bars:
            // they sit on top of this layer and keep receiving their own clicks.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            color = CardBackground,
            shape = RoundedCornerShape(CardCornerRadius),
            // 与玻璃浮层同一道白色细边：面板同样是压在视频上的半透明卡片，
            // 没有描边时边缘会随画面内容忽隐忽现。
            border = BorderStroke(1.dp, PlayerGlassBorderColor),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 56.dp, end = 12.dp, bottom = 112.dp)
                .widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.playback_info_title),
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (model.itemName != null) {
                            Text(
                                text = model.itemName,
                                color = TextSubtitle,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = onCopy) {
                        Text(
                            text = stringResource(R.string.playback_info_copy),
                            color = AccentColor,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_white_24dp),
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier.size(24.dp),
                            tint = Color.White,
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                        .height(1.dp)
                        .background(DividerColor),
                )

                // Sections
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    model.sections.forEachIndexed { index, section ->
                        if (index != 0) Spacer(modifier = Modifier.height(20.dp))
                        PlaybackInfoSectionView(section)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackInfoSectionView(section: PlaybackInfoSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Section header with a brand-colored accent bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 14.dp)
                    .background(AccentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = section.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.06.sp,
            )
        }
        if (section.chips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                section.chips.forEach { chip ->
                    Text(
                        text = chip,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.02.sp,
                        modifier = Modifier
                            .background(ChipBackground, RoundedCornerShape(ChipCornerRadius))
                            .padding(start = 10.dp, top = 5.dp, end = 10.dp, bottom = 6.dp),
                    )
                }
            }
        }
        section.rows.forEach { row ->
            Row(modifier = Modifier.padding(vertical = 6.dp)) {
                SelectionContainer(modifier = Modifier.weight(0.85f)) {
                    Text(
                        text = row.label,
                        color = Color(0x99FFFFFF),
                        fontSize = 13.sp,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                SelectionContainer(modifier = Modifier.weight(1.6f)) {
                    Text(
                        text = row.value,
                        color = Color(0xF2FFFFFF),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
