package com.telekom.tv.player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NowPlayingStore {
    public interface Listener {
        void onState(NowPlayingState state);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile NowPlayingState state = NowPlayingState.empty();

    public NowPlayingState get() {
        return state;
    }

    public synchronized void set(NowPlayingState next) {
        if (next == null) return;
        NowPlayingState previous = state;
        if (next.equals(previous)) return;
        if (next.isEmpty() && previous.isEmpty()) return;
        state = next;
        for (Listener listener : listeners) {
            listener.onState(next);
        }
    }

    public void clear() {
        set(NowPlayingState.empty());
    }

    public void pauseAt(long nowEpochMs) {
        NowPlayingState current = state;
        if (current.playing) {
            set(current.pausedAt(nowEpochMs));
        }
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
