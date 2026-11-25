package org.jellyfin.playback.core.audio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioTrackSelectorTest : FunSpec({
	fun track(
		id: String,
		language: String? = null,
		isDefault: Boolean = false,
		isSupported: Boolean = true,
	) = AudioTrackCandidate(id = id, language = language, isDefault = isDefault, isSupported = isSupported)

	test("an empty file has no track to choose") {
		AudioTrackSelector.choose(emptyList()) shouldBe null
	}

	test("the language the user picked for an earlier item wins") {
		// The box was used with Mandarin on the last film, so a film whose file default is
		// Cantonese still starts in Mandarin.
		val tracks = listOf(
			track("a", language = "yue", isDefault = true),
			track("b", language = "zho"),
		)

		AudioTrackSelector.choose(tracks, preferredLanguage = "zho")?.id shouldBe "b"
	}

	test("the language the user picked wins over a supported default") {
		val tracks = listOf(
			track("a", language = "eng", isDefault = true),
			track("b", language = "zho", isSupported = false),
		)

		AudioTrackSelector.choose(tracks, preferredLanguage = "zho")?.id shouldBe "b"
	}

	test("a Chinese device starts with the Chinese track of the file") {
		val tracks = listOf(
			track("a", language = "eng", isDefault = true),
			track("b", language = "zho"),
		)

		AudioTrackSelector.choose(tracks, deviceLanguage = "zh_CN")?.id shouldBe "b"
	}

	test("a non Chinese device keeps the default of the file") {
		val tracks = listOf(
			track("a", language = "eng", isDefault = true),
			track("b", language = "zho"),
		)

		AudioTrackSelector.choose(tracks, deviceLanguage = "en_US")?.id shouldBe "a"
	}

	test("the server default wins when no language preference applies") {
		val tracks = listOf(
			track("a", language = "zho"),
			track("b", language = "zho", isDefault = true),
		)

		AudioTrackSelector.choose(tracks)?.id shouldBe "b"
	}

	test("an unsupported default is skipped for a supported track") {
		// The most common real case: the file marks a DTS track as default, the box has no
		// decoder for it and the AAC track is the one that actually plays.
		val tracks = listOf(
			track("dts", language = "zho", isDefault = true, isSupported = false),
			track("aac", language = "zho"),
		)

		AudioTrackSelector.choose(tracks)?.id shouldBe "aac"
	}

	test("the first supported track is used when the default is missing") {
		val tracks = listOf(
			track("a", isSupported = false),
			track("b"),
			track("c"),
		)

		AudioTrackSelector.choose(tracks)?.id shouldBe "b"
	}

	test("the file order is kept when nothing is supported") {
		val tracks = listOf(
			track("a", isSupported = false),
			track("b", isDefault = true, isSupported = false),
		)

		AudioTrackSelector.choose(tracks)?.id shouldBe "b"
	}

	test("a blank preference is ignored") {
		val tracks = listOf(
			track("a", language = "eng", isDefault = true),
			track("b", language = "zho"),
		)

		AudioTrackSelector.choose(tracks, preferredLanguage = "  ")?.id shouldBe "a"
	}

	test("a track without language is still playable") {
		val tracks = listOf(
			track("a", language = null, isDefault = true),
		)

		AudioTrackSelector.choose(tracks, preferredLanguage = "zho", deviceLanguage = "zh_CN")?.id shouldBe "a"
	}
})
