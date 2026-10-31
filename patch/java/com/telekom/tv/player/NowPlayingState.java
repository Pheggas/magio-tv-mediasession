package com.telekom.tv.player;

import java.util.Locale;

public final class NowPlayingState {
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    public final String title;
    public final String channel;
    public final long positionMs;
    public final long durationMs;
    public final boolean playing;
    public final String source;
    public final long updatedAtEpochMs;

    public NowPlayingState(String title, String channel, long positionMs, long durationMs,
                           boolean playing, String source, long updatedAtEpochMs) {
        this.title = title == null ? "" : title.trim();
        this.channel = channel == null ? "" : channel.trim();
        this.durationMs = Math.max(0, durationMs);
        long position = Math.max(0, positionMs);
        this.positionMs = this.durationMs > 0 ? Math.min(position, this.durationMs) : 0;
        this.playing = playing;
        this.source = source == null || source.trim().isEmpty()
                ? SOURCE_UNKNOWN
                : source.trim().toUpperCase(Locale.US);
        this.updatedAtEpochMs = Math.max(0, updatedAtEpochMs);
    }

    public static NowPlayingState empty() {
        return new NowPlayingState("", "", 0, 0, false, SOURCE_UNKNOWN, 0);
    }

    public boolean hasContent() {
        return !title.isEmpty();
    }

    public boolean isEmpty() {
        return !hasContent();
    }

    /**
     * Freeze the current position and mark the state paused.
     * Used when the player activity stops and JS can no longer report position.
     */
    public NowPlayingState pausedAt(long nowEpochMs) {
        long position = positionMs;
        if (playing) {
            position += Math.max(0, nowEpochMs - updatedAtEpochMs);
        }
        if (durationMs > 0) {
            position = Math.min(position, durationMs);
        }
        return new NowPlayingState(title, channel, position, durationMs, false, source, nowEpochMs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NowPlayingState)) return false;
        NowPlayingState that = (NowPlayingState) other;
        return positionMs == that.positionMs
                && durationMs == that.durationMs
                && playing == that.playing
                && updatedAtEpochMs == that.updatedAtEpochMs
                && title.equals(that.title)
                && channel.equals(that.channel)
                && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        int result = title.hashCode();
        result = 31 * result + channel.hashCode();
        result = 31 * result + (int) (positionMs ^ (positionMs >>> 32));
        result = 31 * result + (int) (durationMs ^ (durationMs >>> 32));
        result = 31 * result + (playing ? 1 : 0);
        result = 31 * result + source.hashCode();
        result = 31 * result + (int) (updatedAtEpochMs ^ (updatedAtEpochMs >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "NowPlayingState{" + title + " / " + channel + " "
                + positionMs + "/" + durationMs + " playing=" + playing
                + " source=" + source + "}";
    }
}
