package org.jellyfin.playback.media3.exoplayer

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlin.time.Duration

data class ExoPlayerOptions(
	val preferFfmpeg: Boolean = false,
	val enableDebugLogging: Boolean = false,
	val enableLibass: Boolean = false,
	val enableDecoderFallback: Boolean = true,
	/**
	 * Whether a compressed audio track may be handed to the audio HAL as a bitstream
	 * ("offload"/"tunneling") instead of being decoded by the player.
	 *
	 * Off is the safe default: a HAL that announces a compressed format but reports the
	 * position of its direct path in the wrong unit makes the audio clock drift, which
	 * tears the sound away from the picture. Devices with a correct direct path can turn
	 * this on.
	 */
	val audioOffload: Boolean = false,
	val baseDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
	val minBufferDuration: Duration? = null,
	val maxBufferDuration: Duration? = null,
	val bufferForPlaybackDuration: Duration? = null,
	val bufferForPlaybackAfterRebufferDuration: Duration? = null,
	val audioGain: () -> Int = { 0 },
	/** Foreground colour of text subtitles (ARGB). */
	val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
	/** Subtitle text size in the unit of the old player (24 is the default). */
	val subtitleTextSize: Float = 24f,
	/** Distance of the subtitles from the bottom, as a fraction of the screen height. */
	val subtitleBottomPaddingFraction: Float = 0.08f,
	/** Colour of the outline of the text (ARGB, alpha 0 disables the outline). */
	val subtitleStrokeColor: Int = 0xFFFFFFFF.toInt(),
)
