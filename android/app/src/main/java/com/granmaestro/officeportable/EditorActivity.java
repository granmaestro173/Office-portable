package com.granmaestro.officeportable;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditorActivity extends Activity {
    static final String EXTRA_NEW = "new_type";
    private static final String TAG = "OfficePortable";

    private static final int REQ_CREATE = 10;
    private static final int REQ_FILE_CHOOSER = 11;

    private WebView web;
    private ProgressBar busy;
    private TextView titleView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Current document
    private Uri docUri;               // null for a new, never-saved document
    private String docName;           // display name, e.g. "Report.docx"
    private String origExt;           // extension of the file on disk
    private String templateAsset;     // for new documents
    private boolean writable;
    private final String docToken = UUID.randomUUID().toString();

    private boolean opened;
    private boolean dirty;
    private boolean saving;

    /** A pending export from the editor, keyed by request id. */
    private static final class Job {
        final String id;
        final String action;      // "save", "saveas", "pdf", "download"
        final String ext;         // extension of the file being produced
        boolean finishAfter;
        boolean wasDirty;
        File temp;
        String suggestedName;

        Job(String id, String action, String ext) {
            this.id = id;
            this.action = action;
            this.ext = ext;
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> streams = new HashMap<>();
    private Job awaitingCreate;
    private ValueCallback<Uri[]> fileChooserCallback;

    private RecentStore recents() {
        return new RecentStore(this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        web = findViewById(R.id.web);
        busy = findViewById(R.id.busy);
        titleView = findViewById(R.id.title);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_save).setOnClickListener(v -> save(false));
        findViewById(R.id.btn_more).setOnClickListener(this::showMenu);

        if (!resolveDocument(getIntent())) {
            finish();
            return;
        }
        titleView.setText(docName);
        busy.setVisibility(View.VISIBLE);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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

        AssetServer server = new AssetServer(getAssets(), this::openDocumentStream);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return server.handle(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (AssetServer.HOST.equals(u.getHost())) return false;
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Toast.makeText(EditorActivity.this,
                        "The editor ran out of memory and had to close.", Toast.LENGTH_LONG).show();
                if (view.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                web = null;
                finish();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "[js] " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            String ext = FileTypes.ext(name);
            if (ext.isEmpty() || ext.equals("bin")) {
                ext = FileTypes.extForMime(mimeType);
                if (ext.isEmpty()) ext = FileTypes.ext(docName);
                name = FileTypes.baseName(docName) + "." + ext;
            }
            // The editor's own Save button / Ctrl+S arrives here as a download of the
            // document's format. Treat it as the app's Save so it goes back to the file.
            String own = FileTypes.editorExt(origExt);
            String modern = FileTypes.modernExt(origExt);
            if (ext.equals(own) || ext.equals(modern) || ext.equals(origExt)) {
                ui.post(() -> save(false));
                return;
            }
            if (saving) return;
            Job job = newJob("download", ext);
            job.suggestedName = name;
            saving = true;
            busy.setVisibility(View.VISIBLE);
            js("window.__op && window.__op.exportUrl(" + JSONObject.quote(job.id) + "," + JSONObject.quote(url) + ")");
        });

        checkWebViewVersion();

        String editorName = FileTypes.baseName(docName) + "." + FileTypes.editorExt(origExt);
        String url = AssetServer.ORIGIN + "/__android/host.html"
                + "?doc=" + Uri.encode(docToken)
                + "&name=" + Uri.encode(editorName);
        web.loadUrl(url);
    }

    // ---------------------------------------------------------------- open

    private boolean resolveDocument(Intent intent) {
        String newType = intent.getStringExtra(EXTRA_NEW);
        if (newType != null) {
            templateAsset = "templates/blank." + newType;
            origExt = newType;
            docName = (newType.equals("docx") ? "Document" : newType.equals("xlsx") ? "Spreadsheet" : "Presentation")
                    + "." + newType;
            writable = false;
            return true;
        }

        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) {
            Toast.makeText(this, "No file to open", Toast.LENGTH_LONG).show();
            return false;
        }
        docUri = uri;
        docName = queryName(uri);
        origExt = FileTypes.ext(docName);
        if (FileTypes.editorExt(origExt).isEmpty()) {
            String byMime = FileTypes.extForMime(intent.getType() != null ? intent.getType() : safeType(uri));
            if (!byMime.isEmpty()) {
                origExt = byMime;
                docName = FileTypes.baseName(docName) + "." + byMime;
            }
        }
        if (FileTypes.editorExt(origExt).isEmpty()) {
            Toast.makeText(this, "This file type isn't supported: " + docName, Toast.LENGTH_LONG).show();
            return false;
        }
        writable = canWrite(uri, intent);
        return true;
    }

    private String safeType(Uri uri) {
        try {
            return getContentResolver().getType(uri);
        } catch (Exception e) {
            return null;
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name == null || name.trim().isEmpty() ? "Document" : name;
    }

    private boolean canWrite(Uri uri, Intent intent) {
        if ("file".equals(uri.getScheme())) {
            return uri.getPath() != null && new File(uri.getPath()).canWrite();
        }
        if ((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) return true;
        if (checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) return true;
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isWritePermission()) return true;
        }
        return false;
    }

    /** Called on a WebView thread when the host page fetches /__doc/<token>. */
    private InputStream openDocumentStream(String id) throws IOException {
        if (!docToken.equals(id)) return null;
        if (templateAsset != null) return getAssets().open(templateAsset);
        if ("file".equals(docUri.getScheme())) return new FileInputStream(docUri.getPath());
        InputStream in = getContentResolver().openInputStream(docUri);
        if (in == null) throw new FileNotFoundException("Can't read " + docName);
        return in;
    }

    // ---------------------------------------------------------------- menu & saving

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        String modern = FileTypes.modernExt(origExt);
        m.getMenu().add(0, 1, 0, "Save");
        m.getMenu().add(0, 2, 1, "Save as " + modern.toUpperCase() + "…");
        String kind = FileTypes.kind(origExt);
        if (kind.equals("word")) {
            m.getMenu().add(0, 3, 2, "Save as ODT…");
            m.getMenu().add(0, 4, 3, "Save as RTF…");
            m.getMenu().add(0, 5, 4, "Save as TXT…");
        } else if (kind.equals("cell")) {
            m.getMenu().add(0, 6, 2, "Save as ODS…");
            m.getMenu().add(0, 7, 3, "Save as CSV…");
        } else if (kind.equals("slide")) {
            m.getMenu().add(0, 8, 2, "Save as ODP…");
        }
        if (!kind.equals("pdf")) m.getMenu().add(0, 9, 5, "Export to PDF…");
        m.getMenu().add(0, 10, 6, "Share a copy…");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: save(false); return true;
                case 2: saveAs(modern, false); return true;
                case 3: saveAs("odt", false); return true;
                case 4: saveAs("rtf", false); return true;
                case 5: saveAs("txt", false); return true;
                case 6: saveAs("ods", false); return true;
                case 7: saveAs("csv", false); return true;
                case 8: saveAs("odp", false); return true;
                case 9: exportPdf(); return true;
                case 10: share(); return true;
                default: return false;
            }
        });
        m.show();
    }

    private Job newJob(String action, String ext) {
        Job j = new Job("j" + UUID.randomUUID().toString().replace("-", ""), action, ext);
        j.wasDirty = dirty;
        jobs.put(j.id, j);
        return j;
    }

    private boolean ready() {
        if (!opened) {
            Toast.makeText(this, "The document is still loading", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (saving) {
            Toast.makeText(this, "Already saving…", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void save(boolean finishAfter) {
        if (!ready()) return;
        if (docUri != null && writable && FileTypes.canSaveInPlace(origExt)) {
            Job j = newJob("save", origExt);
            j.finishAfter = finishAfter;
            startExport(j);
        } else {
            saveAs(FileTypes.modernExt(origExt), finishAfter);
        }
    }

    private void saveAs(String ext, boolean finishAfter) {
        if (!ready()) return;
        Job j = newJob("saveas", ext);
        j.finishAfter = finishAfter;
        startExport(j);
    }

    private void exportPdf() {
        if (!ready()) return;
        startExport(newJob("pdf", "pdf"));
    }

    private void share() {
        if (!ready()) return;
        startExport(newJob("share", FileTypes.canSaveInPlace(origExt) ? origExt : FileTypes.modernExt(origExt)));
    }

    private void startExport(Job j) {
        saving = true;
        busy.setVisibility(View.VISIBLE);
        js("window.__op && window.__op.save(" + JSONObject.quote(j.id) + "," + JSONObject.quote(j.ext.toUpperCase()) + ")");
    }

    /** The editor has handed over the exported bytes for a job. */
    private void onExported(Job j) {
        switch (j.action) {
            case "save":
                writeTo(j, docUri, () -> done(j, "Saved"), () -> {
                    // The original location refused the write; ask where to put it instead.
                    Toast.makeText(this, "Can't write to the original file. Choose where to save it.", Toast.LENGTH_LONG).show();
                    writable = false;
                    promptCreate(j, FileTypes.baseName(docName) + "." + j.ext);
                });
                break;
            case "saveas":
            case "pdf":
                promptCreate(j, FileTypes.baseName(docName) + "." + j.ext);
                break;
            case "download":
                promptCreate(j, j.suggestedName != null ? j.suggestedName : FileTypes.baseName(docName) + "." + j.ext);
                break;
            case "share":
                shareFile(j);
                break;
            default:
                cleanup(j);
        }
    }

    private void promptCreate(Job j, String name) {
        awaitingCreate = j;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(FileTypes.mimeForExt(j.ext));
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_CREATE);
        } catch (ActivityNotFoundException e) {
            awaitingCreate = null;
            fail(j, "No app available to save files");
        }
    }

    private void writeTo(Job j, Uri target, Runnable ok, Runnable failed) {
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            boolean success = false;
            String err = null;
            try (InputStream in = new FileInputStream(j.temp); OutputStream out = openOut(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                success = true;
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "write failed", e);
            }
            final boolean s = success;
            final String error = err;
            ui.post(() -> {
                if (s) ok.run();
                else if (failed != null) failed.run();
                else fail(j, "Couldn't save: " + error);
            });
        });
    }

    private OutputStream openOut(Uri target) throws IOException {
        if ("file".equals(target.getScheme())) return new FileOutputStream(target.getPath());
        OutputStream out;
        try {
            out = getContentResolver().openOutputStream(target, "wt");
        } catch (IllegalArgumentException | FileNotFoundException e) {
            out = getContentResolver().openOutputStream(target, "w");
        }
        if (out == null) throw new IOException("Can't open the file for writing");
        return out;
    }

    private void onCreateResult(Uri target) {
        Job j = awaitingCreate;
        awaitingCreate = null;
        if (j == null) return;
        if (target == null) {
            // Cancelled: nothing was written, so earlier edits are still unsaved.
            dirty = j.wasDirty || dirty;
            cleanup(j);
            return;
        }
        writeTo(j, target, () -> {
            if (j.action.equals("saveas") || j.action.equals("save")) {
                // The document now lives at the new location in the new format.
                try {
                    getContentResolver().takePersistableUriPermission(target,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                docUri = target;
                templateAsset = null;
                docName = queryName(target);
                if (FileTypes.ext(docName).isEmpty()) docName = docName + "." + j.ext;
                origExt = j.ext;
                writable = true;
                titleView.setText(docName);
                recents().add(target.toString(), docName);
                done(j, "Saved");
            } else {
                // An export (PDF or a download) doesn't save the document itself.
                if (j.wasDirty) dirty = true;
                done(j, "Exported");
            }
        }, null);
    }

    private void shareFile(Job j) {
        // Copy into a world-readable place via MediaStore-free route: our own provider isn't
        // needed because the system share sheet accepts a file we write to a picked location.
        // Simplest robust approach: let the user pick where to put the copy, then share that URI.
        awaitingCreate = null;
        Job copy = j;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(FileTypes.mimeForExt(j.ext));
        i.putExtra(Intent.EXTRA_TITLE, FileTypes.baseName(docName) + "." + j.ext);
        pendingShare = copy;
        try {
            startActivityForResult(i, REQ_SHARE_TARGET);
        } catch (ActivityNotFoundException e) {
            pendingShare = null;
            fail(j, "No app available to save files");
        }
    }

    private static final int REQ_SHARE_TARGET = 12;
    private Job pendingShare;

    private void onShareTarget(Uri target) {
        Job j = pendingShare;
        pendingShare = null;
        if (j == null) return;
        if (target == null) {
            if (j.wasDirty) dirty = true;
            cleanup(j);
            return;
        }
        writeTo(j, target, () -> {
            if (j.wasDirty) dirty = true;
            cleanup(j);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(FileTypes.mimeForExt(j.ext));
            send.putExtra(Intent.EXTRA_STREAM, target);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(send, "Share " + docName));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app to share with", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    private void done(Job j, String message) {
        Toast.makeText(this, message + ": " + docName, Toast.LENGTH_SHORT).show();
        boolean finishAfter = j.finishAfter;
        cleanup(j);
        if (finishAfter) finish();
    }

    private void fail(Job j, String message) {
        if (j != null) {
            if (j.wasDirty) dirty = true;
            cleanup(j);
        } else {
            saving = false;
            busy.setVisibility(View.GONE);
        }
        new AlertDialog.Builder(this)
                .setTitle("Couldn't save")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void cleanup(Job j) {
        jobs.remove(j.id);
        if (j.temp != null) //noinspection ResultOfMethodCallIgnored
            j.temp.delete();
        saving = false;
        busy.setVisibility(opened ? View.GONE : View.VISIBLE);
        updateTitle();
    }

    private void updateTitle() {
        titleView.setText(dirty ? "• " + docName : docName);
    }

    // ---------------------------------------------------------------- activity plumbing

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == REQ_CREATE) {
            onCreateResult(uri);
        } else if (requestCode == REQ_SHARE_TARGET) {
            onShareTarget(uri);
        } else if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new Uri[n];
                        for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (saving) {
            Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!dirty || !opened) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Save changes to " + docName + "?")
                .setPositiveButton("Save", (d, w) -> save(true))
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.destroy();
            web = null;
        }
        io.shutdown();
        super.onDestroy();
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private void checkWebViewVersion() {
        try {
            PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p == null || p.versionName == null) return;
            int major = Integer.parseInt(p.versionName.split("\\.")[0]);
            if (major < 111) {
                new AlertDialog.Builder(this)
                        .setTitle("Update Android System WebView")
                        .setMessage("The editor needs a newer Android System WebView (version " + major
                                + " is installed). Update \"Android System WebView\" or \"Google Chrome\" "
                                + "from the Play Store, then reopen the file.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- JS bridge

    private final class Bridge {
        @JavascriptInterface
        public void onOpened() {
            ui.post(() -> {
                opened = true;
                busy.setVisibility(saving ? View.VISIBLE : View.GONE);
                if (docUri != null) recents().add(docUri.toString(), docName);
            });
        }

        @JavascriptInterface
        public void onError(String message) {
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                if (isFinishing()) return;
                new AlertDialog.Builder(EditorActivity.this)
                        .setTitle("Couldn't open " + docName)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            });
        }

        @JavascriptInterface
        public void onDirty(boolean value) {
            ui.post(() -> {
                dirty = value;
                updateTitle();
            });
        }

        @JavascriptInterface
        public void exportBegin(String id) {
            synchronized (streams) {
                try {
                    Job j = jobs.get(id);
                    if (j == null) return;
                    j.temp = File.createTempFile("export", "." + j.ext, getCacheDir());
                    streams.put(id, new FileOutputStream(j.temp));
                } catch (IOException e) {
                    Log.w(TAG, "exportBegin", e);
                }
            }
        }

        @JavascriptInterface
        public void exportChunk(String id, String base64) {
            synchronized (streams) {
                OutputStream out = streams.get(id);
                if (out == null) return;
                try {
                    out.write(Base64.decode(base64, Base64.DEFAULT));
                } catch (Exception e) {
                    Log.w(TAG, "exportChunk", e);
                    try { out.close(); } catch (IOException ignored) { }
                    streams.remove(id);
                }
            }
        }

        @JavascriptInterface
        public void exportEnd(String id, boolean stillDirty) {
            OutputStream out;
            synchronized (streams) {
                out = streams.remove(id);
            }
            boolean ok = out != null;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    ok = false;
                }
            }
            final boolean success = ok;
            ui.post(() -> {
                Job j = jobs.get(id);
                if (j == null) return;
                if (!success || j.temp == null || j.temp.length() == 0) {
                    fail(j, "The editor didn't return any data.");
                    return;
                }
                dirty = stillDirty;
                updateTitle();
                onExported(j);
            });
        }

        @JavascriptInterface
        public void exportFailed(String id, String message) {
            synchronized (streams) {
                OutputStream out = streams.remove(id);
                if (out != null) try { out.close(); } catch (IOException ignored) { }
            }
            ui.post(() -> {
                Job j = jobs.get(id);
                fail(j, message == null || message.isEmpty() ? "Export failed" : message);
            });
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, "[host] " + message);
        }
    }
}
