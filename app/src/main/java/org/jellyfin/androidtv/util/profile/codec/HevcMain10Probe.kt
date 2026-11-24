package org.jellyfin.androidtv.util.profile.codec

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import org.jellyfin.androidtv.util.AndroidVersion
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes whether a hardware decoder of the device decodes 10 bit HEVC (Main 10).
 *
 * The profile list that a decoder reports is not always complete. Several TV chips decode
 * Main 10 in hardware — their specification promises HDR10 and 8K HEVC, which is Main 10 by
 * definition — while the media framework of the vendor only lists the Main and the RExt
 * profile. A server that trusts that list transcodes every 10 bit file, although the device
 * is able to direct play it: the picture is re-encoded, and every start waits for the first
 * segment of the transcode.
 *
 * The probe therefore does not read the list but configures the hardware decoder with a
 * Main 10 format. A decoder that cannot decode the profile fails to configure.
 */
object HevcMain10Probe {
	private const val MIME = "video/hevc"

	private const val UNSET = -1
	private const val UNSUPPORTED = 0

	/**
	 * The levels that are tried, highest first, with the value that ffprobe reports for it
	 * (the level of ffprobe is the level of the codec multiplied by 30).
	 *
	 * Level 5.2 is deliberately not tried. A decoder that accepts the format when it is
	 * configured has not decoded a frame yet, so the answer is evidence of the profile and
	 * not of the speed: the highest level that is claimed is the one that a 4K stream needs.
	 */
	private val LEVELS = listOf(
		CodecProfileLevel.HEVCMainTierLevel51 to 153,
		CodecProfileLevel.HEVCMainTierLevel5 to 150,
		CodecProfileLevel.HEVCMainTierLevel41 to 123,
		CodecProfileLevel.HEVCMainTierLevel4 to 120,
	)

	/** How long the profile build waits for the codec service before it gives up. */
	private const val PROBE_TIMEOUT_MS = 1_000L

	/** How often the probe may time out before the answer is cached as "not supported". */
	private const val MAXIMUM_TIMED_OUT_ATTEMPTS = 2

	@Volatile
	private var cachedLevel: Int = UNSET

	private var attempts: Int = 0

	private var timedOutAttempts: Int = 0

	/**
	 * Whether a hardware decoder of the device decodes Main 10.
	 */
	fun isSupported(): Boolean = getLevel() > UNSUPPORTED

	/**
	 * The highest level at which a hardware decoder accepts Main 10, in the level of ffprobe,
	 * or zero when no hardware decoder accepts it.
	 *
	 * The answer is cached: the probe creates a decoder, which costs a few milliseconds, and
	 * the profile is built every time that a playback starts.
	 */
	@Synchronized
	fun getLevel(): Int {
		if (cachedLevel == UNSET) {
			val level = probeWithTimeout()
			attempts++

			// A decoder can be busy while another app is using the display, so a negative
			// answer is only cached after it was confirmed a second time.
			if (level > UNSUPPORTED || attempts >= 2 || timedOutAttempts >= MAXIMUM_TIMED_OUT_ATTEMPTS) {
				cachedLevel = level
			}

			return level
		}

		return cachedLevel
	}

	/**
	 * Runs [probe] on a separate thread and waits for it with a limit.
	 *
	 * Creating a decoder talks to the codec service of the device. If that service is in a bad
	 * state the call does not return at all: the probe then blocked the thread that builds the
	 * device profile, and because that thread is the main thread of the app the playback never
	 * started. A timed out probe reports "not supported" for this session instead of hanging,
	 * and after a few timeouts the probe is not tried again until the app is restarted.
	 */
	private fun probeWithTimeout(): Int {
		val names = hardwareDecoderNames()
		if (names.isEmpty()) {
			Timber.i("Main 10 probe: the device has no hardware HEVC decoder")
			return UNSUPPORTED
		}

		var texture: SurfaceTexture? = null
		var surface: Surface? = null
		val result = AtomicInteger(UNSUPPORTED)

		try {
			// The texture belongs to the thread that consumes its frames, so it is created
			// here; only the calls into the codec service may block and are put on the worker.
			texture = SurfaceTexture(0)
			texture.setDefaultBufferSize(1920, 1080)
			surface = Surface(texture)
			val probeSurface = surface

			val worker = Thread(
				{
					try {
						result.set(probe(names, probeSurface))
					} catch (throwable: Throwable) {
						Timber.w(throwable, "Main 10 probe failed")
					}
				},
				"hevc-main10-probe",
			)
			worker.isDaemon = true
			worker.start()
			worker.join(PROBE_TIMEOUT_MS)

			if (worker.isAlive) {
				timedOutAttempts++
				Timber.w(
					"Main 10 probe: the codec service did not answer within %d ms, assuming that Main 10 is not supported",
					PROBE_TIMEOUT_MS,
				)
				return UNSUPPORTED
			}
		} catch (exception: Exception) {
			Timber.w(exception, "Main 10 probe failed")
			return UNSUPPORTED
		} finally {
			surface?.release()
			texture?.release()
		}

		return result.get()
	}

	private fun probe(names: List<String>, surface: Surface): Int {
		for (name in names) {
			val level = probeDecoder(name, surface)
			if (level > UNSUPPORTED) return level
		}

		Timber.i("Main 10 probe: no hardware decoder accepts Main 10")
		return UNSUPPORTED
	}

	private fun probeDecoder(name: String, surface: Surface): Int {
		for ((codecLevel, ffprobeLevel) in LEVELS) {
			val codec = try {
				MediaCodec.createByCodecName(name)
			} catch (exception: Exception) {
				Timber.d(exception, "Main 10 probe: cannot create %s", name)
				return UNSUPPORTED
			}

			try {
				val format = MediaFormat.createVideoFormat(MIME, 1920, 1080)
				format.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
				format.setInteger(MediaFormat.KEY_LEVEL, codecLevel)
				codec.configure(format, surface, null, 0)

				Timber.i("Main 10 probe: %s configures Main 10 at level %d", name, ffprobeLevel)
				return ffprobeLevel
			} catch (exception: Exception) {
				Timber.d(exception, "Main 10 probe: %s rejects level %d", name, ffprobeLevel)
			} finally {
				try {
					codec.release()
				} catch (_: Exception) {
					// The decoder is not usable in any case.
				}
			}
		}

		return UNSUPPORTED
	}

	/**
	 * The decoders of the device that are not software decoders.
	 *
	 * A software decoder configures any profile, so it proves nothing about the hardware.
	 */
	private fun hardwareDecoderNames(): List<String> =
		MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
			.asSequence()
			.filter { !it.isEncoder }
			.filter { info -> info.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
			.filter { info -> !isSoftwareCodec(info) }
			.map { it.name }
			.toList()

	private fun isSoftwareCodec(info: android.media.MediaCodecInfo): Boolean =
		if (AndroidVersion.isAtLeastQ) {
			info.isSoftwareOnly
		} else {
			info.name.startsWith("OMX.google.") ||
				info.name.startsWith("OMX.ffmpeg.") ||
				info.name.startsWith("c2.android.") ||
				info.name.startsWith("c2.google.")
		}
}
