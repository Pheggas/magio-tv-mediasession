package com.telekom.tv.player;

import java.util.concurrent.atomic.AtomicInteger;

public final class NowPlayingStoreTest {
    public static void main(String[] args) {
        dedupeAndClear();
        differentTimestampsDoNotNotify();
        pauseFreezesPosition();
        System.out.println("OK");
    }

    private static void differentTimestampsDoNotNotify() {
        NowPlayingStore store = new NowPlayingStore();
        AtomicInteger calls = new AtomicInteger();
        store.addListener(state -> calls.incrementAndGet());

        store.set(new NowPlayingState("Duel", "JEDNOTKA HD", 1000, 10000,
                true, "LIVE", 1000));
        store.set(new NowPlayingState("Duel", "JEDNOTKA HD", 1000, 10000,
                true, "LIVE", 2000));
        assertEquals(1, calls.get());

        store.set(new NowPlayingState("Duel", "JEDNOTKA HD", 2000, 10000,
                true, "LIVE", 3000));
        assertEquals(2, calls.get());
    }

    private static void dedupeAndClear() {
        NowPlayingStore store = new NowPlayingStore();
        AtomicInteger calls = new AtomicInteger();
        store.addListener(state -> calls.incrementAndGet());

        NowPlayingState state = new NowPlayingState("Duel", "JEDNOTKA HD", 1000, 10000,
                true, "LIVE", 1000);
        store.set(state);
        store.set(state);
        assertEquals(1, calls.get());
        assertEquals("Duel", store.get().title);

        store.clear();
        assertEquals(2, calls.get());
        store.clear();
        assertEquals(2, calls.get());
    }

    private static void pauseFreezesPosition() {
        NowPlayingStore store = new NowPlayingStore();
        store.set(new NowPlayingState("Duel", "JEDNOTKA HD", 1000, 10000,
                true, "LIVE", 1000));
        store.pauseAt(4000);
        assertEquals(4000L, store.get().positionMs);
        assertEquals(false, store.get().playing);
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void assertEquals(boolean expected, boolean actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
