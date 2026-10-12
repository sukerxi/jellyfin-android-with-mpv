package org.jellyfin.mobile.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 阶段 5：Compose 化的 trickplay 控制器。
 *
 * 取图 URL / 瓦片换算 / 节流逻辑与原实现完全一致，但输出改为 Compose 快照状态
 * [state]（由 TrickplayPreview 渲染），不再直接操作 View。
 * 水平位置由 Compose 层依据进度条测量结果与 [TrickplayState.fraction] 计算。
 */
class TrickplayHelper(
    private val context: Context,
) : KoinComponent {
    private val api: ApiClient by inject()
    private val imageLoader: ImageLoader by inject()

    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailDisplayHeight = context.resources.getDimensionPixelSize(R.dimen.trickplay_thumbnail_height)

    private var trickPlayInfo: TrickplayInfoDto? = null
    private var itemId: UUID? = null
    private var mediaSourceId: UUID? = null
    private var durationMs: Long = 0L
    private var pendingRequest: Runnable? = null
    private var pendingTile = -1
    private var lastDispatchedTile = -1
    private var isScrubbing = false
    private var nextDispatchAt = 0L
    private var chapters: List<ChapterInfo>? = null
    private var currentRequest: Disposable? = null

    /** Compose 渲染用的 trickplay 状态。 */
    var state: TrickplayState by mutableStateOf(TrickplayState())
        private set

    fun onMediaSourceChanged(source: JellyfinMediaSource?) {
        trickPlayInfo = null
        itemId = null
        mediaSourceId = null
        durationMs = 0L
        pendingTile = -1
        lastDispatchedTile = -1
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingRequest = null
        chapters = null
        currentRequest?.dispose()
        currentRequest = null
        state = TrickplayState()

        val item = source?.item
        val resolvedSourceId = source?.id
        val resolvedMediaSourceId = resolvedSourceId?.toUUIDOrNull()
        val resolvedTrickPlayInfo = item?.trickplay?.get(resolvedSourceId)?.values?.firstOrNull()
        if (item == null || resolvedMediaSourceId == null || resolvedTrickPlayInfo == null) return

        trickPlayInfo = resolvedTrickPlayInfo
        itemId = item.id
        mediaSourceId = resolvedMediaSourceId
        durationMs = source.runTime.inWholeMilliseconds
        chapters = item.chapters
        val width = (thumbnailDisplayHeight * resolvedTrickPlayInfo.width.toFloat() / resolvedTrickPlayInfo.height).roundToInt()
        state = state.copy(thumbnailWidthPx = width)
    }

    /**
     * @param position 当前 scrub 位置（毫秒）
     */
    fun onScrubMove(position: Long) {
        isScrubbing = true
        val resolvedTrickPlayInfo = trickPlayInfo ?: return
        val resolvedItemId = itemId ?: return
        val resolvedMediaSourceId = mediaSourceId ?: return
        if (durationMs <= 0) return

        // Calculate trickplay tile position and offset based on scrubberposition
        val currentTile = position.floorDiv(resolvedTrickPlayInfo.interval).toInt()
        val tileSize = resolvedTrickPlayInfo.tileWidth * resolvedTrickPlayInfo.tileHeight
        val tileOffset = currentTile % tileSize
        val tileIndex = currentTile / tileSize
        val tileOffsetX = tileOffset % resolvedTrickPlayInfo.tileWidth
        val tileOffsetY = tileOffset / resolvedTrickPlayInfo.tileWidth
        val offsetX = tileOffsetX * resolvedTrickPlayInfo.width
        val offsetY = tileOffsetY * resolvedTrickPlayInfo.height

        // Fraction of the media duration; the Compose layer maps it to the seek bar geometry
        val fraction = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

        // Update chapter name and timestamp on every move
        val chapterName = chapters?.lastOrNull { it.startPositionTicks <= position * Constants.TICKS_PER_MILLISECOND }?.name
        state = state.copy(
            visible = true,
            fraction = fraction,
            chapterName = chapterName,
            timeText = formatPositionAsElapsedTime(position),
        )

        // Same tile already pending or already displayed - position updated above, nothing else to do
        if (currentTile == pendingTile || currentTile == lastDispatchedTile) return

        // Cancel previous pending request and schedule a new one for the latest tile
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingTile = currentTile

        val url = api.trickplayApi.getTrickplayTileImageUrl(
            itemId = resolvedItemId,
            width = resolvedTrickPlayInfo.width,
            index = tileIndex,
            mediaSourceId = resolvedMediaSourceId,
        )

        val runnable = Runnable {
            dispatchRequest(
                url,
                offsetX,
                offsetY,
                resolvedTrickPlayInfo.width,
                resolvedTrickPlayInfo.height,
                currentTile,
            )
        }
        pendingRequest = runnable

        // Run immediately if next dispatch time has passed, otherwise schedule for the remaining time
        handler.postAtTime(
            runnable,
            maxOf(SystemClock.uptimeMillis(), nextDispatchAt),
        )
    }

    private fun dispatchRequest(url: String, offsetX: Int, offsetY: Int, tileWidth: Int, tileHeight: Int, tile: Int) {
        lastDispatchedTile = tile
        pendingTile = -1
        pendingRequest = null
        nextDispatchAt = SystemClock.uptimeMillis() + Constants.TRICKPLAY_TILE_REFRESH_WINDOW_MS

        currentRequest?.dispose()
        currentRequest = imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .size(Size.ORIGINAL)
                .maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .set(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = api.clientInfo.name,
                                clientVersion = api.clientInfo.version,
                                deviceId = api.deviceInfo.id,
                                deviceName = api.deviceInfo.name,
                                accessToken = api.accessToken,
                            ),
                        )
                        .build(),
                )
                .transformations(SubsetTransformation(offsetX, offsetY, tileWidth, tileHeight))
                .target(
                    onSuccess = { image ->
                        if (isScrubbing) {
                            state = state.copy(thumbnail = image.toBitmap().asImageBitmap())
                        }
                    },
                )
                .build(),
        )
    }

    fun onScrubStop() {
        isScrubbing = false
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingRequest = null
        pendingTile = -1
        lastDispatchedTile = -1
        nextDispatchAt = 0L
        currentRequest?.dispose()
        currentRequest = null
        state = TrickplayState(thumbnailWidthPx = state.thumbnailWidthPx)
    }

    private fun formatPositionAsElapsedTime(positionMs: Long): String {
        // Add half a second before truncating to match Media3's time display round-to-nearest behavior
        val roundToNearestThresholdMs = 500L
        return DateUtils.formatElapsedTime((positionMs + roundToNearestThresholdMs).milliseconds.inWholeSeconds)
    }
}

/**
 * trickplay 预览状态（由 Compose 层渲染）。
 *
 * @param visible 是否展示（scrubbing 中）
 * @param fraction 当前 scrub 位置占媒体时长的比例（0f..1f）
 * @param thumbnailWidthPx 缩略图显示宽度（按显示高度与瓦片宽高比推算）
 * @param thumbnail 已加载的缩略图；null 表示尚未取到（取图逻辑见 [TrickplayHelper]）
 * @param chapterName 当前章节名（可为空）
 * @param timeText 位置时间文本
 */
data class TrickplayState(
    val visible: Boolean = false,
    val fraction: Float = 0f,
    val thumbnailWidthPx: Int = 0,
    val thumbnail: ImageBitmap? = null,
    val chapterName: String? = null,
    val timeText: String = "",
)
