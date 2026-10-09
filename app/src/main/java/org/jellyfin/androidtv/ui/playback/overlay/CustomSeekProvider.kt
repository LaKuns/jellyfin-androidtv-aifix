package org.jellyfin.androidtv.ui.playback.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.leanback.widget.PlaybackSeekDataProvider
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

class CustomSeekProvider(
	private val videoPlayerAdapter: VideoPlayerAdapter,
	private val imageLoader: ImageLoader,
	private val api: ApiClient,
	private val context: Context,
	private val forwardTime: Long
) : PlaybackSeekDataProvider() {
	private val imageRequests = linkedMapOf<Int, Disposable>()
	private val requestIds = mutableMapOf<Int, Long>()
	private var nextRequestId = 0L
	private var currentSeekPositions = LongArray(0)

	private var cachedPlaceholderThumbnail: Bitmap? = null
	private val maxActiveRequests = if (PerformanceProfile.isLowPerformanceDevice(context)) 2 else 4

	private companion object {
		const val MAX_SEEK_POSITIONS = 4000
		const val MAX_TILE_PIXELS = 8_000_000L
		const val MAX_THUMBNAIL_EDGE = 1024
	}

	override fun getSeekPositions(): LongArray {
		if (!videoPlayerAdapter.canSeek() || forwardTime <= 0) {
			reset()
			return LongArray(0)
		}

		val currentSeekPosition = videoPlayerAdapter.currentPosition
		val videoEndPosition = videoPlayerAdapter.duration
		if (videoEndPosition <= 0) {
			reset()
			return LongArray(0)
		}
		val step = maxOf(forwardTime, videoEndPosition / MAX_SEEK_POSITIONS.toLong() + 1)
		val firstSeekPosition = currentSeekPosition.coerceIn(0, videoEndPosition) % step
		val seekPositions = ArrayList<Long>(minOf(MAX_SEEK_POSITIONS + 2, (videoEndPosition / step).toInt() + 2))
		seekPositions.add(0L)
		var position = if (firstSeekPosition == 0L) step else firstSeekPosition
		while (position < videoEndPosition && seekPositions.size <= MAX_SEEK_POSITIONS) {
			seekPositions.add(position)
			if (position > videoEndPosition - step) break
			position += step
		}
		if (seekPositions.last() != videoEndPosition) seekPositions.add(videoEndPosition)

		val newPositions = seekPositions.toLongArray()
		if (!newPositions.contentEquals(currentSeekPositions)) cancelImageRequests()
		currentSeekPositions = newPositions
		return currentSeekPositions
	}

	override fun getThumbnail(index: Int, callback: ResultCallback) {
		if (index !in currentSeekPositions.indices) return

		imageRequests.remove(index)?.dispose()
		requestIds.remove(index)

		val item = videoPlayerAdapter.currentlyPlayingItem
		val mediaSource = videoPlayerAdapter.currentMediaSource
		val mediaSourceId = mediaSource?.id?.toUUIDOrNull()
		if (item == null || mediaSource == null || mediaSourceId == null) return

		val trickPlayResolutions = item.trickplay?.get(mediaSource.id)
		val trickPlayInfo = trickPlayResolutions?.values?.firstOrNull() ?: return
		val interval = trickPlayInfo.interval.toLong()
		val columns = trickPlayInfo.tileWidth.toLong()
		val rows = trickPlayInfo.tileHeight.toLong()
		val width = trickPlayInfo.width.toLong()
		val height = trickPlayInfo.height.toLong()
		if (interval <= 0 || columns <= 0 || rows <= 0 || width <= 0 || height <= 0 ||
			width > MAX_THUMBNAIL_EDGE || height > MAX_THUMBNAIL_EDGE) return
		if (columns > Int.MAX_VALUE.toLong() / width || rows > Int.MAX_VALUE.toLong() / height) return
		val atlasWidth = columns * width
		val atlasHeight = rows * height
		if (atlasWidth > MAX_TILE_PIXELS / atlasHeight) return

		val currentTimeMs = currentSeekPositions[index]
		val currentTile = currentTimeMs / interval
		val tileSize = columns * rows
		val tileOffset = currentTile % tileSize
		val tileIndex = currentTile / tileSize
		if (tileIndex > Int.MAX_VALUE) return

		val tileOffsetX = tileOffset % columns
		val tileOffsetY = tileOffset / columns
		val offsetX = (tileOffsetX * width).toInt()
		val offsetY = (tileOffsetY * height).toInt()

		val url = api.trickplayApi.getTrickplayTileImageUrl(
			itemId = item.id,
			width = trickPlayInfo.width,
			index = tileIndex.toInt(),
			mediaSourceId = mediaSourceId,
		)

		val placeholderThumbnail = getPlaceholderThumbnail(trickPlayInfo.width, trickPlayInfo.height)
		while (imageRequests.size >= maxActiveRequests) {
			val oldest = imageRequests.entries.first()
			imageRequests.remove(oldest.key)?.dispose()
			requestIds.remove(oldest.key)
		}
		val requestId = ++nextRequestId
		requestIds[index] = requestId

		imageRequests[index] = imageLoader.enqueue(ImageRequest.Builder(context).apply {
			data(url)
			size(Size.ORIGINAL)
			httpHeaders(NetworkHeaders.Builder().apply {
				set(
					key = "Authorization",
					value = AuthorizationHeaderBuilder.buildHeader(
						api.clientInfo.name,
						api.clientInfo.version,
						api.deviceInfo.id,
						api.deviceInfo.name,
						api.accessToken
					)
				)
			}.build())

			transformations(SubsetTransformation(offsetX, offsetY, trickPlayInfo.width, trickPlayInfo.height))

			target(
				onStart = { _ -> if (requestIds[index] == requestId) callback.onThumbnailLoaded(placeholderThumbnail, index) },
				onError = { _ -> if (requestIds[index] == requestId) callback.onThumbnailLoaded(placeholderThumbnail, index) },
				onSuccess = { image ->
					if (requestIds[index] == requestId) callback.onThumbnailLoaded(image.toBitmap(), index)
				}
			)
		}.build())
	}

	override fun reset() {
		cancelImageRequests()
		currentSeekPositions = LongArray(0)
	}

	private fun cancelImageRequests() {
		for (request in imageRequests.values) {
			if (!request.isDisposed) request.dispose()
		}
		imageRequests.clear()
		requestIds.clear()
	}

	fun getPlaceholderThumbnail(width: Int, height: Int): Bitmap {
		if (cachedPlaceholderThumbnail?.width == width && cachedPlaceholderThumbnail?.height == height) {
			return cachedPlaceholderThumbnail!!
		}

		val color = ContextCompat.getColor(context, R.color.black_transparent_light)
		val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		result.eraseColor(color)
		cachedPlaceholderThumbnail = result
		return result
	}
}
