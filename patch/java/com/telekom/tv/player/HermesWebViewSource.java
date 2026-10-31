package com.telekom.tv.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

/**
 * Finds the Magio player WebView when StbPlayerActivity resumes.
 * No smali patch is needed for the activity itself.
 */
final class HermesWebViewSource implements Application.ActivityLifecycleCallbacks {
    private static final String PLAYER_ACTIVITY = "com.telekom.tv.player.StbPlayerActivity";

    private final Application app;
    private final NowPlayingStore store;

    HermesWebViewSource(Application app, NowPlayingStore store) {
        this.app = app;
        this.store = store;
    }

    void start() {
        app.registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!PLAYER_ACTIVITY.equals(activity.getClass().getName())) return;
        int id = activity.getResources().getIdentifier("webContent", "id", activity.getPackageName());
        if (id == 0) return;
        View view = activity.findViewById(id);
        if (view instanceof WebView) {
            HermesWebView.attach((WebView) view, store);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (PLAYER_ACTIVITY.equals(activity.getClass().getName())) {
            store.pauseAt(System.currentTimeMillis());
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
