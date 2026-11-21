package com.granmaestro.officeportable;

import android.webkit.WebView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

/**
 * Raw-bytes channel from the page to the app ("AndroidSave" in JS).
 *
 * The fallback bridge (@JavascriptInterface) only takes strings, so saves were
 * base64-encoded in JS and decoded in Java: +33% data, and two full passes of
 * encode/decode work on the phone's CPU. WebViews from ~2023 on can post
 * ArrayBuffers directly. Messages, in order:
 *   "b|<id>"            begin export <id>
 *   ArrayBuffer         next chunk of the current export
 *   "e|<id>|<0|1>"      end (1 = edits made during export, still dirty)
 *   "f|<id>|<message>"  failed
 */
final class BinaryChannel {
    private BinaryChannel() {}

    static boolean supported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void install(WebView web) {
        if (!supported()) return;
        final String[] current = {null};
        WebViewCompat.addWebMessageListener(web, "AndroidSave", Collections.singleton(AssetServer.ORIGIN),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame) return; // only the host page streams exports
                    WebViewPool.BridgeRelay relay = WebViewPool.relayOf(view);
                    Object target = relay != null ? relay.target : null;
                    if (!(target instanceof EditorActivity.Bridge)) return;
                    EditorActivity.Bridge bridge = (EditorActivity.Bridge) target;

                    if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                        if (current[0] != null) bridge.binaryChunk(current[0], message.getArrayBuffer());
                        return;
                    }
                    String data = message.getData();
                    if (data == null || data.length() < 2) return;
                    String[] parts = data.split("\\|", 3);
                    switch (parts[0]) {
                        case "b":
                            current[0] = parts[1];
                            bridge.binaryBegin(parts[1]);
                            break;
                        case "e":
                            bridge.binaryEnd(parts[1], parts.length > 2 && "1".equals(parts[2]));
                            current[0] = null;
                            break;
                        case "f":
                            bridge.binaryFailed(parts[1], parts.length > 2 ? parts[2] : "Export failed");
                            current[0] = null;
                            break;
                        default:
                            break;
                    }
                });
    }
}
