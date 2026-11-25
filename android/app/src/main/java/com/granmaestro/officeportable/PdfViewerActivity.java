package com.granmaestro.officeportable;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fast native PDF viewer (Android's PdfRenderer). Opens in a fraction of the
 * time and memory of the full editor; "Edit" hands the file to the editor for
 * annotating, filling forms or exporting.
 */
public class PdfViewerActivity extends Activity {
    private static final float[] ZOOMS = {1f, 1.5f, 2f, 3f, 4f};
    /** Rendering wider than this costs memory without visible gain on a phone. */
    private static final int MAX_RENDER_WIDTH = 2400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService renderThread = Executors.newSingleThreadExecutor();

    private Uri uri;
    private String name = "Document.pdf";
    private File cacheCopy;
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;          // touched only on renderThread
    private float[] aspect;                // height / width per page
    private LruCache<Integer, Bitmap> cache;
    private volatile int renderGeneration;

    private ListView list;
    private HorizontalScrollView hscroll;
    private TextView pageLabel;
    private ProgressBar busy;
    private float zoom = 1f;
    private ScaleGestureDetector scaler;
    private float gestureScale = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = getIntent().getData();
        if (uri == null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) {
            finish();
            return;
        }
        buildUi();
        int cacheBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 256L * 1024 * 1024);
        cache = new LruCache<Integer, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };
        renderThread.execute(this::openDocument);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.bar));

        View bar = getLayoutInflater().inflate(R.layout.top_bar, root, false);
        ((ImageView) bar.findViewById(R.id.type_icon)).setImageResource(R.drawable.ic_file_pdf);
        bar.findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // No save here; the second action opens the full editor.
        bar.findViewById(R.id.btn_save).setVisibility(View.GONE);
        ImageButton edit = bar.findViewById(R.id.btn_more);
        edit.setImageResource(R.drawable.ic_edit);
        edit.setContentDescription(getString(R.string.edit));
        edit.setOnClickListener(v -> openInEditor(null));
        edit.setOnLongClickListener(v -> {
            Toast.makeText(this, R.string.edit, Toast.LENGTH_SHORT).show();
            return true;
        });
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(getColor(R.color.canvas));
        hscroll = new HorizontalScrollView(this);
        hscroll.setFillViewport(true);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(8));
        list.setSelector(android.R.color.transparent);
        list.setPadding(0, dp(8), 0, dp(8));
        list.setClipToPadding(false);
        hscroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(hscroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setTextSize(13);
        pageLabel.setPadding(dp(14), dp(6), dp(14), dp(6));
        pageLabel.setBackgroundResource(R.drawable.pill_bg);
        pageLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(16);
        content.addView(pageLabel, lp);

        busy = new ProgressBar(this);
        content.addView(busy, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total == 0) return;
                pageLabel.setText((first + 1) + " / " + total);
                pageLabel.setVisibility(View.VISIBLE);
            }
        });

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                gestureScale = 1f;
                list.setPivotX(d.getFocusX() + hscroll.getScrollX());
                list.setPivotY(d.getFocusY());
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                gestureScale = Math.max(0.5f, Math.min(4f, gestureScale * d.getScaleFactor()));
                list.setScaleX(gestureScale);
                list.setScaleY(gestureScale);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                list.setScaleX(1f);
                list.setScaleY(1f);
                applyZoom(snap(zoom * gestureScale));
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (scaler != null) scaler.onTouchEvent(ev);
        if (scaler != null && scaler.isInProgress()) return true;
        return super.dispatchTouchEvent(ev);
    }

    private static float snap(float z) {
        float best = ZOOMS[0];
        for (float s : ZOOMS) if (Math.abs(s - z) < Math.abs(best - z)) best = s;
        return best;
    }

    private void applyZoom(float z) {
        if (z == zoom || aspect == null) return;
        int first = list.getFirstVisiblePosition();
        zoom = z;
        int viewWidth = hscroll.getWidth();
        ViewGroup.LayoutParams p = list.getLayoutParams();
        p.width = zoom == 1f ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(viewWidth * zoom);
        list.setLayoutParams(p);
        renderGeneration++;
        cache.evictAll();
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        list.setSelection(first);
    }

    // ---------------------------------------------------------------- loading (render thread)

    private void openDocument() {
        String n = queryName();
        try {
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new IOException("no descriptor");
                renderer = new PdfRenderer(pfd);
            } catch (IOException | RuntimeException notSeekable) {
                // Some providers hand out pipes, which PdfRenderer can't seek; use a local copy.
                closeQuietly();
                cacheCopy = new File(getCacheDir(), "view-" + System.nanoTime() + ".pdf");
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Can't read file");
                    ImageOptimizer.copy(in, cacheCopy);
                }
                pfd = ParcelFileDescriptor.open(cacheCopy, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
            }
            int count = renderer.getPageCount();
            float[] a = new float[count];
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    a[i] = page.getHeight() / (float) Math.max(1, page.getWidth());
                }
            }
            ui.post(() -> onOpened(n, a));
        } catch (IOException | RuntimeException e) {
            // Password-protected or something PdfRenderer can't read: the editor can.
            ui.post(() -> openInEditor("Opening in the editor…"));
        }
    }

    private String queryName() {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
            } catch (Exception ignored) {
            }
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.substring(last.lastIndexOf('/') + 1) : "Document.pdf";
    }

    private void onOpened(String docName, float[] pageAspect) {
        if (isFinishing()) return;
        name = docName;
        aspect = pageAspect;
        ((TextView) findViewById(R.id.title)).setText(name);
        busy.setVisibility(View.GONE);
        list.setAdapter(new PageAdapter());
        new RecentStore(this).add(uri.toString(), name);
    }

    private final class PageAdapter extends BaseAdapter {
        @Override public int getCount() { return aspect.length; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(PdfViewerActivity.this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setBackgroundColor(Color.WHITE);
            int width = parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int side = dp(8);
            int w = width - 2 * side;
            int h = Math.round(w * aspect[position]);
            AbsListView.LayoutParams lp = new AbsListView.LayoutParams(w, h);
            iv.setLayoutParams(lp);
            iv.setPadding(0, 0, 0, 0);
            iv.setTag(position);
            Bitmap cached = cache.get(position);
            iv.setImageBitmap(cached);
            if (cached == null) requestRender(position, Math.min(w, MAX_RENDER_WIDTH), iv);
            return iv;
        }
    }

    private void requestRender(int index, int width, ImageView target) {
        final int gen = renderGeneration;
        renderThread.execute(() -> {
            if (gen != renderGeneration || renderer == null) return;
            // Skip pages scrolled out of view before we got to them.
            boolean stillWanted = true;
            Object tag = target.getTag();
            if (!(tag instanceof Integer) || (Integer) tag != index) stillWanted = false;
            if (!stillWanted) return;
            Bitmap bmp;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int w = Math.max(1, width);
                int h = Math.max(1, Math.round(w * page.getHeight() / (float) page.getWidth()));
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (RuntimeException | OutOfMemoryError e) {
                return;
            }
            final Bitmap result = bmp;
            ui.post(() -> {
                if (gen != renderGeneration) return;
                cache.put(index, result);
                Object t = target.getTag();
                if (t instanceof Integer && (Integer) t == index) target.setImageBitmap(result);
            });
        });
    }

    // ---------------------------------------------------------------- editor hand-off

    private void openInEditor(String toast) {
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, EditorActivity.class);
        i.setAction(Intent.ACTION_EDIT);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        try {
            startActivity(i);
        } catch (SecurityException e) {
            // We were granted read only; pass on what we have.
            i.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(i);
        }
        finish();
    }

    private void closeQuietly() {
        try {
            if (renderer != null) renderer.close();
        } catch (RuntimeException ignored) {
        }
        renderer = null;
        try {
            if (pfd != null) pfd.close();
        } catch (IOException ignored) {
        }
        pfd = null;
    }

    @Override
    protected void onDestroy() {
        renderGeneration++;
        renderThread.execute(() -> {
            closeQuietly();
            if (cacheCopy != null) //noinspection ResultOfMethodCallIgnored
                cacheCopy.delete();
        });
        renderThread.shutdown();
        if (cache != null) cache.evictAll();
        super.onDestroy();
    }

}
