package com.telekom.tv.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;

/**
 * Finds the Magio player WebView when StbPlayerActivity is created/resumed.
 * No smali patch is needed for the activity itself.
 */
final class HermesWebViewSource implements Application.ActivityLifecycleCallbacks {
    private static final String PLAYER_ACTIVITY = "com.telekom.tv.player.StbPlayerActivity";
    private static final long CLEAR_DELAY_MS = 10 * 60 * 1000L;

    private final Application app;
    private final NowPlayingStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clearTask = new Runnable() {
        @Override
        public void run() {
            store.clear();
        }
    };

    HermesWebViewSource(Application app, NowPlayingStore store) {
        this.app = app;
        this.store = store;
    }

    void start() {
        app.registerActivityLifecycleCallbacks(this);
    }

    private void attach(Activity activity) {
        int id = activity.getResources().getIdentifier("webContent", "id", activity.getPackageName());
        if (id == 0) return;
        View view = activity.findViewById(id);
        if (view instanceof WebView) {
            HermesWebView.attach((WebView) view, store);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (PLAYER_ACTIVITY.equals(activity.getClass().getName())) {
            attach(activity);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!PLAYER_ACTIVITY.equals(activity.getClass().getName())) return;
        main.removeCallbacks(clearTask);
        store.setForeground(true);
        attach(activity);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (PLAYER_ACTIVITY.equals(activity.getClass().getName())) {
            store.setForeground(false);
            store.pauseAt(System.currentTimeMillis());
            main.removeCallbacks(clearTask);
            main.postDelayed(clearTask, CLEAR_DELAY_MS);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (PLAYER_ACTIVITY.equals(activity.getClass().getName())) {
            main.removeCallbacks(clearTask);
            store.clear();
        }
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
