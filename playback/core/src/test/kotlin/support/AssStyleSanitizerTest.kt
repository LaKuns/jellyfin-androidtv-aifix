package org.jellyfin.playback.core.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class AssStyleSanitizerTest : FunSpec({
	fun sanitize(text: String) = AssStyleSanitizer.sanitize(text.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

	test("an opaque subtitle box is turned into plain outlined text") {
		val input = """
			[Script Info]
			ScriptType: v4.00+

			[V4+ Styles]
			Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
			Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,3,2,4,2,10,10,10,1

			[Events]
			Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
			Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
		""".trimIndent()

		val output = sanitize(input)

		output shouldContain "Style:Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000"
		output shouldContain "Style:Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1"
		output shouldContain "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"
	}

	test("files without a style section are left unchanged") {
		val input = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
		sanitize(input) shouldBe input
	}

	test("an SSE event chunk without the style table is left unchanged") {
		val input = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"
		sanitize(input) shouldBe input
	}
})
