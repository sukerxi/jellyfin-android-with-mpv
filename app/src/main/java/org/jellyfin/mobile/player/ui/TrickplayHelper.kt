package org.jellyfin.mobile.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.runtime.Stable
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

    /**
     * Compose 渲染用的 trickplay 状态。
     *
     * 是一个**长期存活**的对象，字段各自是独立的快照状态：拖动时只有读取 `fraction` /
     * `timeText` 的预览层会失效。之前这里持有 `mutableStateOf(TrickplayState())`，每次
     * `copy()` 都会换掉整个对象，所有读过 `state` 的组合作用域（包括整条进度行）都得重组。
     */
    val state = TrickplayState()

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
        state.reset()

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
        state.thumbnailWidthPx = width
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

        // Update chapter name and timestamp on every move.
        val chapterName = chapters?.lastOrNull { it.startPositionTicks <= position * Constants.TICKS_PER_MILLISECOND }?.name
        // 逐字段写：每个字段是独立的快照状态，值没变就不会触发失效，读它的那一层才知道
        // 要重组（例如缩略图位置只在布局阶段读 fraction，不会重建预览的内容）。
        state.visible = true
        state.fraction = fraction
        state.chapterName = chapterName
        state.timeText = formatPositionAsElapsedTime(position)

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
                url = url,
                info = resolvedTrickPlayInfo,
                offsetX = offsetX,
                offsetY = offsetY,
                tile = currentTile,
            )
        }
        pendingRequest = runnable

        // Run immediately if next dispatch time has passed, otherwise schedule for the remaining time
        handler.postAtTime(
            runnable,
            maxOf(SystemClock.uptimeMillis(), nextDispatchAt),
        )
    }

    private fun dispatchRequest(
        url: String,
        info: TrickplayInfoDto,
        offsetX: Int,
        offsetY: Int,
        tile: Int,
    ) {
        lastDispatchedTile = tile
        pendingTile = -1
        pendingRequest = null
        nextDispatchAt = SystemClock.uptimeMillis() + Constants.TRICKPLAY_TILE_REFRESH_WINDOW_MS

        // Request the tile at half resolution instead of its full size: a tile holds a
        // tileWidth x tileHeight grid of thumbnails, so the full-size request decoded
        // the entire grid (tens of megabytes for large tiles) to show a single
        // thumbnail and threw everything but the crop away. The preview displays each
        // thumbnail upscaled anyway (160dp against the source thumbnail's height), so
        // the halved decode is not visible, while allocation, decode CPU and GC
        // pressure per tile drop 4x. SubsetTransformation maps the crop rectangle
        // through the same scale factor.
        val requestWidth = info.tileWidth * info.width / 2
        val requestHeight = info.tileHeight * info.height / 2

        currentRequest?.dispose()
        currentRequest = imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .size(requestWidth, requestHeight)
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
                .transformations(
                    SubsetTransformation(
                        x = offsetX,
                        y = offsetY,
                        width = info.width,
                        height = info.height,
                        sourceWidth = info.tileWidth * info.width,
                        sourceHeight = info.tileHeight * info.height,
                    ),
                )
                .target(
                    onSuccess = { image ->
                        if (isScrubbing) {
                            state.thumbnail = image.toBitmap().asImageBitmap()
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
        // 只清「本次拖动」的内容；thumbnailWidthPx 是按媒体源算出来的，下一次拖动还要用。
        state.reset()
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
 * 与常见写法（一个 data class + `copy()`）的区别：**每个字段各自是一个快照状态**。
 * 拖动时 `fraction` / `timeText` 每次触摸事件都在变，如果它们共同构成一个不可变对象，
 * 任何一次变化都会让所有读过该对象的组合作用域失效——包括整条进度行。拆成独立字段之后，
 * 只有真正读这些字段的那一小块（缩略图预览）会重组；`copy()` 带来的每帧对象分配也没了。
 *
 * 字段由 [TrickplayHelper] 写入（`internal set`），外部只读。
 */
@Stable
class TrickplayState {
    /** 是否展示（scrubbing 中）。 */
    var visible: Boolean by mutableStateOf(false)
        internal set

    /** 当前 scrub 位置占媒体时长的比例（0f..1f）。只在布局阶段被读取，不触发重组。 */
    var fraction: Float by mutableStateOf(0f)
        internal set

    /** 缩略图显示宽度（按显示高度与瓦片宽高比推算）。随媒体源变化，不是每帧都变。 */
    var thumbnailWidthPx: Int by mutableStateOf(0)
        internal set

    /** 已加载的缩略图；null 表示尚未取到（取图逻辑见 [TrickplayHelper]）。 */
    var thumbnail: ImageBitmap? by mutableStateOf(null)
        internal set

    /** 当前章节名（可为空）。 */
    var chapterName: String? by mutableStateOf(null)
        internal set

    /** 位置时间文本。 */
    var timeText: String by mutableStateOf("")
        internal set

    /** 清掉一次拖动留下的内容；[thumbnailWidthPx] 属于媒体源，不在此列。 */
    internal fun reset() {
        visible = false
        fraction = 0f
        thumbnail = null
        chapterName = null
        timeText = ""
    }
}
