package com.granmaestro.officeportable;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Editor WebViews for the :editor process.
 *
 * Starting an editor has a fixed Android-side cost before any document work:
 * loading the WebView implementation, starting its renderer process, and
 * booting the editor shell page. The home screen asks for one "standby"
 * WebView with the shell already loaded; the next editor adopts it and only has
 * to send the document. A standby is dropped after a few minutes unused or as
 * soon as Android asks for memory back.
 */
final class WebViewPool {
    private static final String TAG = "OfficePortable";
    private static final long STANDBY_TTL_MS = 3 * 60 * 1000;

    /** Documents being served, by token. Shared by every editor WebView in the process. */
    static final Map<String, AssetServer.DocumentSource> DOCUMENTS = new ConcurrentHashMap<>();

    private static AssetServer server;
    private static WebView standby;
    private static BridgeRelay standbyRelay;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable EXPIRE = WebViewPool::dropStandby;
    private static boolean trimRegistered;

    private WebViewPool() {}

    /** The process-wide asset server (assets + /__doc/ tokens + device config). */
    static synchronized AssetServer server(Context context) {
        if (server == null) {
            Context app = context.getApplicationContext();
            server = new AssetServer(app.getAssets(), id -> {
                AssetServer.DocumentSource src = id == null ? null : DOCUMENTS.get(id);
                return src != null ? src.open(id) : null;
            }, DeviceProfile.detect(app).toJson());
        }
        return server;
    }

    /** Relays the page's AndroidBridge calls to whichever editor currently owns the WebView. */
    static final class BridgeRelay {
        volatile Object target; // EditorActivity.Bridge
        /** host.js has started (may happen before an editor adopts this WebView). */
        volatile boolean hostReady;

        private EditorActivity.Bridge t() {
            return target instanceof EditorActivity.Bridge ? (EditorActivity.Bridge) target : null;
        }

        @JavascriptInterface public void onOpened() { EditorActivity.Bridge b = t(); if (b != null) b.onOpened(); }
        @JavascriptInterface public void onError(String m) { EditorActivity.Bridge b = t(); if (b != null) b.onError(m); }
        @JavascriptInterface public void onDirty(boolean d) { EditorActivity.Bridge b = t(); if (b != null) b.onDirty(d); }
        @JavascriptInterface public void exportBegin(String id) { EditorActivity.Bridge b = t(); if (b != null) b.exportBegin(id); }
        @JavascriptInterface public void exportChunk(String id, String c) { EditorActivity.Bridge b = t(); if (b != null) b.exportChunk(id, c); }
        @JavascriptInterface public void exportEnd(String id, boolean d) { EditorActivity.Bridge b = t(); if (b != null) b.exportEnd(id, d); }
        @JavascriptInterface public void exportFailed(String id, String m) { EditorActivity.Bridge b = t(); if (b != null) b.exportFailed(id, m); }
        @JavascriptInterface public void log(String m) { Log.d(TAG, "[host] " + m); }
        @JavascriptInterface public void hostReady() {
            hostReady = true;
            EditorActivity.Bridge b = t();
            if (b != null) b.hostReady();
        }
    }

    /** Creates an editor WebView with the app's settings, JS bridge and binary channel. */
    @SuppressLint("SetJavaScriptEnabled")
    static WebView create(Context context) {
        MutableContextWrapper wrapper = new MutableContextWrapper(context);
        WebView web = new WebView(wrapper);
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        BridgeRelay relay = new BridgeRelay();
        web.addJavascriptInterface(relay, "AndroidBridge");
        web.setTag(R.id.web, relay);
        BinaryChannel.install(web);

        AssetServer srv = server(context);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return srv.handle(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !AssetServer.HOST.equals(request.getUrl().getHost());
            }
        });
        return web;
    }

    static BridgeRelay relayOf(WebView web) {
        Object tag = web.getTag(R.id.web);
        return tag instanceof BridgeRelay ? (BridgeRelay) tag : null;
    }

    /** Called on the main thread when the home screen is showing. */
    static void prepareStandby(Context context) {
        if (standby != null) {
            MAIN.removeCallbacks(EXPIRE);
            MAIN.postDelayed(EXPIRE, STANDBY_TTL_MS);
            return;
        }
        Context app = context.getApplicationContext();
        registerTrim(app);
        try {
            WebView web = create(app);
            web.loadUrl(AssetServer.ORIGIN + "/__android/host.html?standby=1");
            standby = web;
            standbyRelay = relayOf(web);
            MAIN.postDelayed(EXPIRE, STANDBY_TTL_MS);
        } catch (RuntimeException e) {
            // No WebView available (being updated, disabled): the editor will report it.
            Log.w(TAG, "standby WebView failed", e);
        }
    }

    /** Hands the warm WebView to an editor, or returns null if there isn't one. */
    static WebView adopt(Activity activity) {
        WebView web = standby;
        if (web == null) return null;
        standby = null;
        standbyRelay = null;
        MAIN.removeCallbacks(EXPIRE);
        if (web.getContext() instanceof MutableContextWrapper) {
            ((MutableContextWrapper) web.getContext()).setBaseContext(activity);
        }
        return web;
    }

    static void dropStandby() {
        MAIN.removeCallbacks(EXPIRE);
        WebView web = standby;
        standby = null;
        standbyRelay = null;
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
    }

    private static void registerTrim(Context app) {
        if (trimRegistered) return;
        trimRegistered = true;
        app.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level >= TRIM_MEMORY_RUNNING_LOW) dropStandby();
            }

            @Override public void onConfigurationChanged(Configuration c) { }
            @Override public void onLowMemory() { dropStandby(); }
        });
    }

    /** Small helper so document sources can be lambdas that throw. */
    interface Opener {
        InputStream open() throws IOException;
    }

    static void register(String token, Opener opener) {
        DOCUMENTS.put(token, id -> opener.open());
    }

    static void unregister(String token) {
        DOCUMENTS.remove(token);
    }
}
