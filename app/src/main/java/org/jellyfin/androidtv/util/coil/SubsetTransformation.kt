package org.jellyfin.androidtv.util.coil

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

class SubsetTransformation(
	private val x: Int,
	private val y: Int,
	private val width: Int,
	private val height: Int,
) : Transformation() {
	override val cacheKey: String = "$x,$y,$width,$height"

	override suspend fun transform(
		input: Bitmap,
		size: Size,
	): Bitmap {
		val safeX = x.coerceIn(0, input.width - 1)
		val safeY = y.coerceIn(0, input.height - 1)
		val safeWidth = width.coerceIn(1, input.width - safeX)
		val safeHeight = height.coerceIn(1, input.height - safeY)
		return Bitmap.createBitmap(input, safeX, safeY, safeWidth, safeHeight)
	}
}
