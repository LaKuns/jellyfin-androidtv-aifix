package org.jellyfin.playback.media3.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleParser
import org.jellyfin.playback.core.support.AssStyleSanitizer

/**
 * Wraps a subtitle parser factory and strips the opaque background out of ASS/SSA styles before
 * libass renders them. Other subtitle formats are passed through untouched.
 */
@OptIn(UnstableApi::class)
class AssStyleSanitizingSubtitleParserFactory(
	private val delegate: SubtitleParser.Factory,
) : SubtitleParser.Factory {
	override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

	override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

	override fun create(format: Format): SubtitleParser {
		val parser = delegate.create(format)
		return if (format.isAssSubtitle()) AssStyleSanitizingSubtitleParser(parser) else parser
	}

	private fun Format.isAssSubtitle(): Boolean {
		val mimeType = sampleMimeType?.lowercase() ?: return false
		return "ssa" in mimeType || "ass" in mimeType
	}
}

/** A [SubtitleParser] that sanitizes the ASS style table of every parsed buffer. */
@OptIn(UnstableApi::class)
private class AssStyleSanitizingSubtitleParser(
	private val delegate: SubtitleParser,
) : SubtitleParser {
	override fun getCueReplacementBehavior(): Int = delegate.getCueReplacementBehavior()

	override fun reset() = delegate.reset()

	override fun parse(
		data: ByteArray,
		outputOptions: SubtitleParser.OutputOptions,
		output: Consumer<CuesWithTiming>,
	) {
		val sanitized = AssStyleSanitizer.sanitize(data)
		delegate.parse(sanitized, outputOptions, output)
	}

	override fun parse(
		data: ByteArray,
		offset: Int,
		length: Int,
		outputOptions: SubtitleParser.OutputOptions,
		output: Consumer<CuesWithTiming>,
	) {
		val sanitized = AssStyleSanitizer.sanitize(data, offset, length)
		delegate.parse(sanitized, 0, sanitized.size, outputOptions, output)
	}

	override fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle {
		val sanitized = AssStyleSanitizer.sanitize(data, offset, length)
		return delegate.parseToLegacySubtitle(sanitized, 0, sanitized.size)
	}
}
