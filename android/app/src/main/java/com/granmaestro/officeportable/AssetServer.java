package com.granmaestro.officeportable;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the bundled editor (assets/www) and the Android host page
 * (assets/shell) on a private https origin, entirely offline.
 */
final class AssetServer {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;

    /** Supplies the bytes of the document being edited. */
    interface DocumentSource {
        InputStream open(String id) throws IOException;
    }

    /** Disables service workers (the APK already holds every file) and marks the app. */
    private static final String SHIM_TAG = "<script src=\"/__android/shim.js\"></script>";

    private final AssetManager assets;
    private final DocumentSource documents;
    private final String configJson;

    AssetServer(AssetManager assets, DocumentSource documents, String configJson) {
        this.assets = assets;
        this.documents = documents;
        this.configJson = configJson;
    }

    WebResourceResponse handle(Uri url) {
        if (url == null || !HOST.equals(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";

        try {
            if (path.startsWith("/__doc/")) {
                InputStream in = documents.open(path.substring("/__doc/".length()));
                if (in == null) return notFound();
                return respond("application/octet-stream", in);
            }
            if (path.equals("/__android/shim.js")) {
                return serveShim();
            }
            if (path.startsWith("/__android/")) {
                return serveAsset("shell/" + path.substring("/__android/".length()));
            }
            if (path.equals("/sw.js") || path.endsWith("/sw.js")) {
                return notFound();
            }
            if (path.equals("/") || path.equals("/editor") || path.equals("/editor/")) {
                path = "/editor.html";
            }
            String asset = "www" + path;
            if (asset.endsWith("/")) asset += "index.html";
            WebResourceResponse r = serveAsset(asset);
            if (r == null && FileTypes.ext(path).isEmpty()) r = serveAsset(asset + ".html");
            return r != null ? r : notFound();
        } catch (IOException e) {
            return error(e);
        }
    }

    private WebResourceResponse serveAsset(String asset) throws IOException {
        InputStream in;
        try {
            in = assets.open(asset, AssetManager.ACCESS_STREAMING);
        } catch (FileNotFoundException e) {
            return null;
        }
        String mime = mimeFor(asset);
        if (mime.equals("text/html")) {
            in = injectShim(in);
        }
        return respond(mime, in);
    }

    /** The shim, prefixed with this device's settings (read by the patched editor). */
    private WebResourceResponse serveShim() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("window.__OP_CONFIG = " + configJson + ";\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = assets.open("shell/shim.js")) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return respond("text/javascript", new ByteArrayInputStream(out.toByteArray()));
    }

    private static InputStream injectShim(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        String html = out.toString("UTF-8");
        String lower = html.toLowerCase(Locale.ROOT);
        int head = lower.indexOf("<head");
        if (head >= 0) {
            int end = html.indexOf('>', head);
            if (end > 0) html = html.substring(0, end + 1) + SHIM_TAG + html.substring(end + 1);
        } else {
            html = SHIM_TAG + html;
        }
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }

    private static WebResourceResponse respond(String mime, InputStream in) {
        String encoding = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")
                || mime.endsWith("xml") ? "utf-8" : null;
        WebResourceResponse r = new WebResourceResponse(mime, encoding, 200, "OK", headers(), in);
        return r;
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cross-Origin-Resource-Policy", "cross-origin");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse error(Exception e) {
        android.util.Log.w("OfficePortable", "request failed", e);
        String msg = String.valueOf(e.getMessage());
        return new WebResourceResponse("text/plain", "utf-8", 500, "Error", headers(),
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
    }

    static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".wasm.br") || p.endsWith(".wasm")) return "application/wasm";
        int dot = p.lastIndexOf('.');
        int slash = p.lastIndexOf('/');
        String ext = dot > slash ? p.substring(dot + 1) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "txt": return "text/plain";
            case "xml": return "application/xml";
            case "docx": case "xlsx": case "pptx": return FileTypes.mimeForExt(ext);
            default: return "application/octet-stream";
        }
    }
}
