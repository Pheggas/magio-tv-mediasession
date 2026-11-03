package com.telekom.tv.player;

import android.app.Application;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

/**
 * Owns the one MediaSession for the Magio process.
 *
 * V1 is observation-only: actions are deliberately zero, and there is no
 * broadcast receiver. State arrives from HermesWebViewSource/HermesWebView.
 */
public final class HermesSession {
    private static final String SESSION_TAG = "MagioNowPlaying";

    private static MediaSession session;
    private static final NowPlayingStore STORE = new NowPlayingStore();
    private static HermesWebViewSource source;

    private HermesSession() {
    }

    public static synchronized void start(Application app) {
        if (session != null) return;

        session = new MediaSession(app, SESSION_TAG);
        session.setFlags(0);
        session.setActive(true);
        STORE.addListener(new NowPlayingStore.Listener() {
            @Override
            public void onState(NowPlayingState state) {
                publish(state);
            }
        });
        publish(STORE.get());

        source = new HermesWebViewSource(app, STORE);
        source.start();
    }

    private static void publish(NowPlayingState state) {
        if (session == null) return;

        boolean hasContent = state.hasContent();
        String displayTitle = !state.title.isEmpty()
                ? state.title
                : (!state.channel.isEmpty() ? state.channel : "Magio TV");

        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.channel)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.channel)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, state.channel)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .build();
        session.setMetadata(metadata);

        int playbackState = !hasContent
                ? PlaybackState.STATE_NONE
                : state.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        float speed = hasContent && state.playing ? 1.0f : 0.0f;

        PlaybackState playback = new PlaybackState.Builder()
                .setActions(0)
                .setState(playbackState, state.positionMs, speed, SystemClock.elapsedRealtime())
                .build();
        session.setPlaybackState(playback);
    }
}
