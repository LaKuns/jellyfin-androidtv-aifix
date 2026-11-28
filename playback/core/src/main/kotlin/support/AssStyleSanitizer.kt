package org.jellyfin.playback.core.support

/**
 * Removes the opaque background from ASS/SSA subtitle styles.
 *
 * Some subtitle files draw a filled box behind the text (`BorderStyle=3` with a coloured
 * `BackColour`). The player must show the text only, so the style table is rewritten before the
 * file is handed to libass:
 *
 * - `BorderStyle` is set to 1 (outline and shadow instead of an opaque box),
 * - `BackColour` is made fully transparent,
 * - `Shadow` is set to 0.
 *
 * The primary/secondary colours, font, size and outline (the glyphs themselves) are kept. The
 * function only changes the `[V4+ Styles]`/`[V4 Styles]` table and returns the input unchanged
 * when it is not an ASS/SSA document.
 */
object AssStyleSanitizer {
	private const val TRANSPARENT = "&HFF000000"
	private val stylesSectionNames = setOf("[V4+ Styles]", "[V4 Styles]")

	fun sanitize(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
		if (length <= 0 || offset < 0 || offset + length > data.size) return data
		val text = runCatching { String(data, offset, length, Charsets.UTF_8) }.getOrNull() ?: return data.copyOfRange(offset, offset + length)

		// A subtitle buffer may contain an event chunk without the style table; leave it alone.
		if (stylesSectionNames.none(text::contains)) return data.copyOfRange(offset, offset + length)

		var inStyles = false
		var formatIndices: Map<String, Int>? = null
		var changed = false
		val lines = text.split('\n')
		val result = lines.map { raw ->
			val line = raw.trimEnd('\r')
			val trimmed = line.trim()

			if (trimmed.startsWith("[")) {
				inStyles = stylesSectionNames.any { trimmed.equals(it, ignoreCase = true) }
				formatIndices = null
				return@map raw
			}

			if (!inStyles) return@map raw

			if (trimmed.startsWith("Format:", ignoreCase = true)) {
				formatIndices = trimmed.substringAfter(':')
					.split(',')
					.mapIndexed { index, field -> field.trim() to index }
					.toMap()
				return@map raw
			}

			if (!trimmed.startsWith("Style:", ignoreCase = true)) return@map raw

			val fields = trimmed.substringAfter(':').split(',').toMutableList()
			fun set(name: String, fallbackIndex: Int, value: String) {
				val index = formatIndices?.get(name) ?: fallbackIndex
				if (index in fields.indices) fields[index] = value
			}

			set("BackColour", 6, TRANSPARENT)
			set("BorderStyle", 15, "1")
			set("Shadow", 17, "0")

			changed = true
			val indent = line.takeWhile { it == ' ' || it == '\t' }
			"${indent}Style:${fields.joinToString(",")}"
		}

		if (!changed) return data.copyOfRange(offset, offset + length)
		return result.joinToString("\n").toByteArray(Charsets.UTF_8)
	}
}
