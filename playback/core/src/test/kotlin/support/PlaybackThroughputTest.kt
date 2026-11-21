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
})
