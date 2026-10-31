package com.telekom.tv.player;

public final class NowPlayingCodecTest {
    public static void main(String[] args) {
        roundTrip();
        rejectsBadPayloads();
        clamps();
        System.out.println("OK");
    }

    private static void roundTrip() {
        NowPlayingState original = new NowPlayingState(
                "Správy | test", "JEDNOTKA HD", 600000, 2100000, true, "LIVE", 1700000000000L);
        String wire = NowPlayingCodec.encode(original);
        NowPlayingState parsed = NowPlayingCodec.decode(wire);
        assertNotNull(parsed);
        assertEquals("Správy | test", parsed.title);
        assertEquals("JEDNOTKA HD", parsed.channel);
        assertEquals(600000L, parsed.positionMs);
        assertEquals(2100000L, parsed.durationMs);
        assertEquals(true, parsed.playing);
        assertEquals("LIVE", parsed.source);
        assertEquals(1700000000000L, parsed.updatedAtEpochMs);
    }

    private static void rejectsBadPayloads() {
        assertNull(NowPlayingCodec.decode(null));
        assertNull(NowPlayingCodec.decode("v1|a|b"));
        assertNull(NowPlayingCodec.decode("v2|a|b|0|0|1|LIVE|1"));
        assertNull(NowPlayingCodec.decode("v1|a|b|nope|0|1|LIVE|1"));
        assertNull(NowPlayingCodec.decode("v1|a%|b|0|0|1|LIVE|1"));
    }

    private static void clamps() {
        NowPlayingState negative = new NowPlayingState("x", "y", -5, -10, false, null, 0);
        assertEquals(0L, negative.positionMs);
        assertEquals(0L, negative.durationMs);
        assertEquals("UNKNOWN", negative.source);

        NowPlayingState tooLong = new NowPlayingState("x", "y", 5000, 1000, false, "live", 1);
        assertEquals(1000L, tooLong.positionMs);
        assertEquals("LIVE", tooLong.source);
    }

    private static void assertNotNull(Object value) {
        if (value == null) throw new AssertionError("expected non-null");
    }

    private static void assertNull(Object value) {
        if (value != null) throw new AssertionError("expected null, got " + value);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void assertEquals(boolean expected, boolean actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
