package com.telekom.tv.player;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * Wire format, version 1:
 *
 *   v1|title|channel|positionMs|durationMs|playing|source|updatedAtEpochMs
 *
 * Every string field is encodeURIComponent()/URLEncoder-encoded. Literal '|'
 * is therefore always %7C inside a field. Extra fields are ignored so a later
 * protocol version can append data without breaking V1 readers.
 */
public final class NowPlayingCodec {
    private static final String VERSION = "v1";

    private NowPlayingCodec() {
    }

    public static NowPlayingState decode(String payload) {
        if (payload == null) return null;
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 8 || !VERSION.equals(parts[0])) return null;
        try {
            String title = decodeField(parts[1]);
            String channel = decodeField(parts[2]);
            long position = Long.parseLong(parts[3]);
            long duration = Long.parseLong(parts[4]);
            boolean playing = "1".equals(parts[5]) || "true".equalsIgnoreCase(parts[5]);
            String source = decodeField(parts[6]);
            long updated = Long.parseLong(parts[7]);
            return new NowPlayingState(title, channel, position, duration, playing, source, updated);
        } catch (Exception e) {
            return null;
        }
    }

    public static String encode(NowPlayingState state) {
        return VERSION + "|"
                + encodeField(state.title) + "|"
                + encodeField(state.channel) + "|"
                + state.positionMs + "|"
                + state.durationMs + "|"
                + (state.playing ? "1" : "0") + "|"
                + encodeField(state.source) + "|"
                + state.updatedAtEpochMs;
    }

    private static String encodeField(String value) {
        try {
            // encodeURIComponent uses %20 for spaces; URLEncoder uses '+'.
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static String decodeField(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, "UTF-8");
    }
}
