package com.telekom.tv.player;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

/**
 * Native MediaSession owned by the Magio process.
 * A host-side watcher pushes the current channel/program via broadcasts:
 *   am broadcast -a com.telekom.magiogo.HERMES_UPDATE \
 *     --es title "Duel" --es channel "JEDNOTKA HD" \
 *     --ei position 600000 --ei duration 2100000 --ez playing true
 */
public final class HermesSession {
    private static MediaSession session;
    private static BroadcastReceiver receiver;
    private static String lastTitle = "Magio TV";
    private static String lastChannel = "";
    private static long lastPosition = 0;
    private static long lastDuration = 0;
    private static boolean lastPlaying = true;

    private HermesSession() {
    }

    public static synchronized void start(Application app) {
        if (session != null) {
            return;
        }
        session = new MediaSession(app, "MagioNowPlaying");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                lastPlaying = true;
                publish();
            }

            @Override
            public void onPause() {
                lastPlaying = false;
                publish();
            }
        });
        session.setActive(true);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String title = intent.getStringExtra("title");
                String channel = intent.getStringExtra("channel");
                if (title != null) {
                    lastTitle = title;
                }
                if (channel != null) {
                    lastChannel = channel;
                }
                lastPosition = intent.getLongExtra("position", lastPosition);
                lastDuration = intent.getLongExtra("duration", lastDuration);
                lastPlaying = intent.getBooleanExtra("playing", lastPlaying);
                publish();
            }
        };
        app.registerReceiver(receiver, new IntentFilter("com.telekom.magiogo.HERMES_UPDATE"));
        publish();
    }

    private static synchronized void publish() {
        if (session == null) {
            return;
        }
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, lastTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, lastTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, lastChannel)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, lastChannel)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, lastDuration)
                .build();
        session.setMetadata(metadata);

        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(lastPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        lastPosition,
                        lastPlaying ? 1.0f : 0.0f,
                        SystemClock.elapsedRealtime())
                .build();
        session.setPlaybackState(state);
    }
}
