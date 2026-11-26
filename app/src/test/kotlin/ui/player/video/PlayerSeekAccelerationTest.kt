package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class PlayerSeekAccelerationTest : FunSpec({
	test("a short press uses the configured amount") {
		seekAcceleration(10.seconds, repeatCount = 0) shouldBe 10.seconds
		seekAcceleration(10.seconds, repeatCount = 3) shouldBe 10.seconds
	}

	test("the step triples from the fourth repeat") {
		seekAcceleration(10.seconds, repeatCount = 4) shouldBe 30.seconds
		seekAcceleration(10.seconds, repeatCount = 8) shouldBe 30.seconds
	}

	test("the step grows to six times from the ninth repeat but stops at a minute") {
		seekAcceleration(10.seconds, repeatCount = 9) shouldBe 60.seconds
		seekAcceleration(10.seconds, repeatCount = 40) shouldBe 60.seconds
	}

	test("a configured amount of 30 seconds is capped at a minute") {
		seekAcceleration(30.seconds, repeatCount = 0) shouldBe 30.seconds
		seekAcceleration(30.seconds, repeatCount = 4) shouldBe 60.seconds
		seekAcceleration(30.seconds, repeatCount = 9) shouldBe 60.seconds
	}

	test("a configured amount above a minute is never reduced") {
		seekAcceleration(120.seconds, repeatCount = 0) shouldBe 120.seconds
		seekAcceleration(120.seconds, repeatCount = 9) shouldBe 120.seconds
	}
})
