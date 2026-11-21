package org.jellyfin.androidtv.util.profile

import androidx.media3.common.MimeTypes
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat

/**
 * Audio formats that every device decodes, used when the audio has to be downmixed.
 */
internal val downmixSupportedAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.MP2,
	Codec.Audio.MP3,
)

/**
 * Audio formats that the device profile knows about.
 */
internal val supportedAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AAC_LATM,
	Codec.Audio.AC3,
	Codec.Audio.ALAC,
	Codec.Audio.DCA,
	Codec.Audio.DTS,
	Codec.Audio.EAC3,
	Codec.Audio.FLAC,
	Codec.Audio.MLP,
	Codec.Audio.MP2,
	Codec.Audio.MP3,
	Codec.Audio.OPUS,
	Codec.Audio.PCM_ALAW,
	Codec.Audio.PCM_MULAW,
	Codec.Audio.PCM_S16LE,
	Codec.Audio.PCM_S20LE,
	Codec.Audio.PCM_S24LE,
	Codec.Audio.TRUEHD,
	Codec.Audio.VORBIS,
)

/**
 * Audio formats that the MPEG-TS HLS profile offers for a transcoded stream.
 *
 * The first format of the list is the one the server encodes to, so AAC leads the list. DTS is
 * missing on purpose: the server cannot copy it into MPEG-TS and encodes the audio instead.
 */
internal val hlsMpegTsAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AC3,
	Codec.Audio.EAC3,
	Codec.Audio.MP3,
)

/**
 * Audio formats that the fragmented MP4 HLS profile offers for a transcoded stream.
 *
 * DTS is deliberately missing from this list. The server copies a track that is on the list
 * instead of transcoding it, and DTS copied into fragmented MP4 is not playable for the player:
 * ffmpeg writes the track with the sample entry "mp4a" and the object type of DTS (0xA9) instead
 * of the "dtsc" entry that the format defines, while the HLS playlist advertises "dtsc". The
 * track therefore does not describe itself correctly, and a device that only passes DTS through
 * loses the audio instead of playing it. MPEG-TS carries DTS natively, but the server refuses to
 * copy it into that container, so a stream that has to be transcoded gets AAC audio instead.
 * Direct play of DTS is not affected: it is still part of the direct play profile.
 */
internal val hlsFmp4AudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AC3,
	Codec.Audio.EAC3,
	Codec.Audio.MP3,
	Codec.Audio.ALAC,
	Codec.Audio.FLAC,
	Codec.Audio.OPUS,
)

/**
 * The type of stream that a decoder has to support to play the audio codec of a file.
 *
 * The values are the ones that Android's [android.media.MediaCodecList] reports for the
 * decoders of the device. A codec that is missing from this map is a format that the player
 * decodes itself, like raw PCM, so it needs no decoder of the device.
 */
internal val audioCodecMimeTypes = mapOf(
	Codec.Audio.AAC to MimeTypes.AUDIO_AAC,
	// AAC in LATM is carried by the same decoder, only the framing of the stream differs.
	Codec.Audio.AAC_LATM to MimeTypes.AUDIO_AAC,
	Codec.Audio.AC3 to MimeTypes.AUDIO_AC3,
	Codec.Audio.EAC3 to MimeTypes.AUDIO_E_AC3,
	Codec.Audio.DTS to MimeTypes.AUDIO_DTS,
	// DCA is the name the server uses for the same stream as DTS.
	Codec.Audio.DCA to MimeTypes.AUDIO_DTS,
	Codec.Audio.TRUEHD to MimeTypes.AUDIO_TRUEHD,
	Codec.Audio.MLP to MimeTypes.AUDIO_TRUEHD,
	Codec.Audio.ALAC to MimeTypes.AUDIO_ALAC,
	Codec.Audio.FLAC to MimeTypes.AUDIO_FLAC,
	Codec.Audio.MP2 to MimeTypes.AUDIO_MPEG_L2,
	Codec.Audio.MP3 to MimeTypes.AUDIO_MPEG,
	Codec.Audio.OPUS to MimeTypes.AUDIO_OPUS,
	Codec.Audio.VORBIS to MimeTypes.AUDIO_VORBIS,
	Codec.Audio.PCM_ALAW to MimeTypes.AUDIO_ALAW,
	Codec.Audio.PCM_MULAW to MimeTypes.AUDIO_MLAW,
)

/**
 * The audio formats that are not decoded by the device but handed to the television or the
 * receiver over hdmi.
 */
internal val passthroughAudioFormats = mapOf(
	Codec.Audio.AC3 to BitstreamAudioFormat.AC3,
	Codec.Audio.EAC3 to BitstreamAudioFormat.EAC3,
	Codec.Audio.DTS to BitstreamAudioFormat.DTS,
	Codec.Audio.DCA to BitstreamAudioFormat.DTS,
	Codec.Audio.TRUEHD to BitstreamAudioFormat.TRUEHD,
)

/**
 * What the player finds on the device for an audio format, for the device report.
 *
 * The device profile itself does not need this: it asks the device whether a format can be passed
 * through, and declares everything else. The report shows the information anyway, because the
 * difference between "the device decodes it", "the device passes it through" and "the player has
 * nothing for it" is what explains the audio of a failed playback.
 */
internal fun MediaCodecCapabilitiesTest.hasAudioDecoder(codec: String): Boolean? =
	audioCodecMimeTypes[codec]?.let { mime -> supportsAudioCodec(mime) }
