package com.telekom.tv.player;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Injects one guarded JS observer and receives state through HermesNative.
 * The 30 s re-install is a page-reload safety net, not state polling.
 */
final class HermesWebView {
    private static final Set<WebView> ATTACHED =
            Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HermesWebView() {
    }

    static void attach(WebView webView, NowPlayingStore store) {
        if (webView == null) return;
        webView.getSettings().setJavaScriptEnabled(true);
        synchronized (ATTACHED) {
            if (ATTACHED.add(webView)) {
                webView.addJavascriptInterface(new Bridge(store), "HermesNative");
            }
        }
        install(webView);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                install(webView);
            }
        }, 30000);
    }

    private static void install(final WebView webView) {
        if (webView == null || !webView.isAttachedToWindow()) return;
        webView.evaluateJavascript(HermesJs.INJECT, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value == null || !value.contains("ok")) {
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            install(webView);
                        }
                    }, 1000);
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
            if (state != null) {
                store.set(state);
            }
        }
    }
}
