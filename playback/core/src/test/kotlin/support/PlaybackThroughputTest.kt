package org.jellyfin.playback.core.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class PlaybackThroughputTest : FunSpec({
	test("the whole segment length is available without a delay") {
		PlaybackThroughput.usableFraction(0) shouldBe 1.0
	}

	test("a delay of one second leaves two thirds of the segment") {
		PlaybackThroughput.usableFraction(PlaybackThroughput.MAX_LINK_LATENCY_MS) shouldBe (2.0 / 3.0 plusOrMinus 1.0E-9)
	}

	test("a delay above one second is limited to one second") {
		// A measurement of a server that was busy with its disk must not shrink the budget
		// of every following stream.
		PlaybackThroughput.usableFraction(1_654) shouldBe PlaybackThroughput.usableFraction(PlaybackThroughput.MAX_LINK_LATENCY_MS)
		PlaybackThroughput.usableFraction(Long.MAX_VALUE) shouldBe PlaybackThroughput.usableFraction(PlaybackThroughput.MAX_LINK_LATENCY_MS)
	}

	test("a single audio track is covered by the bitrate of the video and the headroom") {
		PlaybackThroughput.getRequiredBitrate(5_000_000, 5_200_000) shouldBe 5_768_000
	}

	test("a file with several audio tracks is covered by the bitrate of the container") {
		// The film carries six audio tracks, so the container is above the video bitrate
		// and the request has to cover the container as well.
		PlaybackThroughput.getRequiredBitrate(5_188_393, 7_369_638) shouldBe 7_369_638
	}

	test("an unknown container keeps the bitrate of the video and the headroom") {
		PlaybackThroughput.getRequiredBitrate(5_000_000, null) shouldBe 5_768_000
	}
})
