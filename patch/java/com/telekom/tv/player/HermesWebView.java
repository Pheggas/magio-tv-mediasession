package com.telekom.tv.player;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Injects one guarded JS observer and receives state through HermesNative.
 * The 30 s re-install is a page-reload safety net, not state polling.
 *
 * All delayed callbacks hold the WebView weakly. A destroyed activity must not
 * be kept alive by a retry loop.
 */
final class HermesWebView {
    private static final int RETRY_MS = 500;
    private static final int MAX_ATTEMPTS = 20;
    private static final int REINSTALL_MS = 30000;

    private static final Set<WebView> ATTACHED =
            Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HermesWebView() {
    }

    static void attach(WebView webView, NowPlayingStore store) {
        if (webView == null) return;
        webView.getSettings().setJavaScriptEnabled(true);

        boolean first;
        synchronized (ATTACHED) {
            first = ATTACHED.add(webView);
            if (first) {
                webView.addJavascriptInterface(new Bridge(store), "HermesNative");
            }
        }

        if (first) {
            final WeakReference<WebView> ref = new WeakReference<>(webView);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    WebView current = ref.get();
                    if (current == null) return;
                    install(current);
                    MAIN.postDelayed(this, REINSTALL_MS);
                }
            });
        } else {
            install(webView);
        }
    }

    private static void install(WebView webView) {
        install(new WeakReference<>(webView), 0);
    }

    private static void install(final WeakReference<WebView> ref, final int attempt) {
        final WebView webView = ref.get();
        if (webView == null) return;

        if (!webView.isAttachedToWindow()) {
            if (attempt < MAX_ATTEMPTS) {
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        install(ref, attempt + 1);
                    }
                }, RETRY_MS);
            }
            return;
        }

        webView.evaluateJavascript(HermesJs.INJECT, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ((value == null || !value.contains("ok")) && attempt < MAX_ATTEMPTS) {
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            install(ref, attempt + 1);
                        }
                    }, RETRY_MS);
                }
            }
        });
    }

    static final class Bridge {
        private final NowPlayingStore store;

        Bridge(NowPlayingStore store) {
            this.store = store;
        }

        @JavascriptInterface
        public void postState(String payload) {
            NowPlayingState state = NowPlayingCodec.decode(payload);
            if (state != null && (!state.isEmpty() || !store.get().hasContent())) {
                store.set(state);
            }
        }
    }
}
