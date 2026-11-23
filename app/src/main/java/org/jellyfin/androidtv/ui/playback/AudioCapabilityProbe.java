package org.jellyfin.androidtv.ui.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioCapabilities;

import java.util.List;

/**
 * Temporary diagnostic: prints what this device can do with compressed audio
 * (direct passthrough, offload, or only software decoding).
 */
public final class AudioCapabilityProbe {
    private static final String TAG = "AudioProbe";

    private AudioCapabilityProbe() {
    }

    private static final int[] DIRECT_ENCODINGS = new int[]{
            AudioFormat.ENCODING_AC3,
            AudioFormat.ENCODING_E_AC3,
            AudioFormat.ENCODING_DOLBY_TRUEHD,
            AudioFormat.ENCODING_DTS,
            AudioFormat.ENCODING_DTS_HD,
    };

    private static final String[] DIRECT_MIME_TYPES = new String[]{
            "audio/ac3",
            "audio/eac3",
            "audio/true-hd",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
    };

    public static void dump(Context context) {
        try {
            dumpInternal(context);
        } catch (Exception e) {
            Log.w(TAG, "dump failed", e);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private static void dumpInternal(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Log.i(TAG, "sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);

        if (audioManager == null) {
            Log.w(TAG, "no AudioManager");
            return;
        }

        AudioAttributes mediaAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();

        // 1) what the system reports for the current media route
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<AudioProfile> direct = audioManager.getDirectProfilesForAttributes(mediaAttributes);
            Log.i(TAG, "getDirectProfilesForAttributes=" + describeProfiles(direct));
        }

        // 2) every output device and the profiles it advertises
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            Log.i(TAG, "device type=" + device.getType() + " name=" + device.getProductName()
                    + " encodings=" + describeEncodings(device.getEncodings()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.i(TAG, "  profiles=" + describeProfiles(device.getAudioProfiles()));
            }
        }

        // 3) direct playback support per encoding / channel layout
        for (int encoding : DIRECT_ENCODINGS) {
            for (int channelMask : new int[]{AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND}) {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48000)
                        .setChannelMask(channelMask)
                        .build();
                boolean direct = AudioTrack.isDirectPlaybackSupported(format, mediaAttributes);
                Log.i(TAG, "isDirectPlaybackSupported enc=0x" + Integer.toHexString(encoding)
                        + " ch=0x" + Integer.toHexString(channelMask) + " -> " + direct);
            }
        }

        // 4) media3's view of the world (this is what the player decides on)
        AudioCapabilities capabilities = AudioCapabilities.getCapabilities(context);
        Log.i(TAG, "media3 AudioCapabilities maxChannelCount=" + capabilities.getMaxChannelCount());
        for (String mimeType : DIRECT_MIME_TYPES) {
            for (int channelCount : new int[]{2, 6, 8}) {
                Format format = new Format.Builder()
                        .setSampleMimeType(mimeType)
                        .setChannelCount(channelCount)
                        .setSampleRate(48000)
                        .build();
                boolean passthrough = capabilities.isPassthroughPlaybackSupported(format);
                Log.i(TAG, "media3 passthrough mime=" + mimeType + " ch=" + channelCount + " -> " + passthrough);
            }
        }
    }

    private static String describeProfiles(@Nullable List<AudioProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (AudioProfile profile : profiles) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append("{format=0x").append(Integer.toHexString(profile.getFormat()))
                    .append(" chMasks=").append(describeEncodings(profile.getChannelMasks()))
                    .append(" rates=").append(describeEncodings(profile.getSampleRates()))
                    .append(" encap=").append(profile.getEncapsulationType())
                    .append('}');
        }
        return builder.append(']').toString();
    }

    private static String describeEncodings(@Nullable int[] values) {
        if (values == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int value : values) {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append("0x").append(Integer.toHexString(value));
        }
        return builder.append(']').toString();
    }
}
