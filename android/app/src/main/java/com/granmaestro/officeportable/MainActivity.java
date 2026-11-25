package com.granmaestro.officeportable;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;

    private RecentStore recents;
    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final List<RecentStore.Entry> all = new ArrayList<>();
    private final List<RecentStore.Entry> items = new ArrayList<>();
    private RecentAdapter adapter;
    private View emptyView;
    private TextView emptyTitle, emptyHint;
    private final List<TextView> chips = new ArrayList<>();
    /** "" = all, else a FileTypes.kind(). */
    private String filter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        recents = new RecentStore(this);

        ListView list = findViewById(R.id.list);
        View header = LayoutInflater.from(this).inflate(R.layout.main_header, list, false);
        list.addHeaderView(header, null, false);
        emptyView = header.findViewById(R.id.empty);
        emptyTitle = header.findViewById(R.id.empty_title);
        emptyHint = header.findViewById(R.id.empty_hint);

        setupTile(header.findViewById(R.id.new_doc), R.drawable.ic_new_word, R.drawable.tile_word, R.string.new_doc, "docx");
        setupTile(header.findViewById(R.id.new_sheet), R.drawable.ic_new_cell, R.drawable.tile_excel, R.string.new_sheet, "xlsx");
        setupTile(header.findViewById(R.id.new_slides), R.drawable.ic_new_slide, R.drawable.tile_ppt, R.string.new_slides, "pptx");
        header.findViewById(R.id.open_file).setOnClickListener(v -> pickFile());
        setupFilters(header.findViewById(R.id.filters));

        adapter = new RecentAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < items.size()) openRecent(items.get(index));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // Start the editor process and boot an editor shell while the user picks a file.
        WarmupService.warm(this);
    }

    private void refresh() {
        bg.execute(() -> {
            List<RecentStore.Entry> fresh = recents.list();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(fresh);
                applyFilter();
            });
        });
    }

    private void setupTile(View tile, int icon, int background, int label, String newType) {
        tile.setBackgroundResource(background);
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.label)).setText(label);
        tile.setContentDescription(getString(R.string.create_new) + ": " + getString(label));
        tile.setOnClickListener(v -> {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_NEW, newType);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i);
        });
    }

    private void setupFilters(LinearLayout row) {
        String[] kinds = {"", "word", "cell", "slide", "pdf"};
        int[] labels = {R.string.filter_all, R.string.filter_docs, R.string.filter_sheets,
                R.string.filter_slides, R.string.filter_pdf};
        for (int i = 0; i < kinds.length; i++) {
            TextView chip = (TextView) getLayoutInflater().inflate(R.layout.chip, row, false);
            chip.setText(labels[i]);
            final String kind = kinds[i];
            chip.setTag(kind);
            chip.setSelected(kind.equals(filter));
            chip.setOnClickListener(v -> {
                filter = kind;
                for (TextView c : chips) c.setSelected(kind.equals(c.getTag()));
                applyFilter();
            });
            chips.add(chip);
            row.addView(chip);
        }
    }

    private void applyFilter() {
        items.clear();
        for (RecentStore.Entry e : all) {
            if (filter.isEmpty() || filter.equals(FileTypes.kind(FileTypes.ext(e.name)))) items.add(e);
        }
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean filtered = !filter.isEmpty() && !all.isEmpty();
            emptyTitle.setText(R.string.no_recent);
            emptyHint.setText(filtered ? R.string.no_recent_filtered : R.string.no_recent_hint);
        }
        adapter.notifyDataSetChanged();
    }

    private void showItemMenu(View anchor, RecentStore.Entry e) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.open);
        m.getMenu().add(0, 2, 1, R.string.share);
        m.getMenu().add(0, 3, 2, R.string.remove_recent);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openRecent(e); return true;
                case 2: share(e); return true;
                case 3:
                    bg.execute(() -> {
                        recents.remove(e.uri);
                        runOnUiThread(this::refresh);
                    });
                    return true;
            }
            return false;
        });
        m.show();
    }

    private void share(RecentStore.Entry e) {
        Uri uri = Uri.parse(e.uri);
        Intent send = new Intent(Intent.ACTION_SEND);
        String mime = FileTypes.mimeForExt(FileTypes.ext(e.name));
        send.setType(mime != null ? mime : "application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, e.name));
        } catch (Exception ex) {
            Toast.makeText(this, "Couldn't share this file.", Toast.LENGTH_LONG).show();
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, FileTypes.OPENABLE_MIME_TYPES);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored2) {
            }
        }
        openUri(uri);
    }

    private void openRecent(RecentStore.Entry e) {
        open(Uri.parse(e.uri), "pdf".equals(FileTypes.ext(e.name)));
    }

    /** Picked files: find out whether it's a PDF without blocking the UI thread. */
    private void openUri(Uri uri) {
        bg.execute(() -> {
            boolean pdf;
            try {
                pdf = "application/pdf".equals(getContentResolver().getType(uri));
            } catch (Exception e) {
                pdf = false;
            }
            if (!pdf) {
                String last = uri.getLastPathSegment();
                pdf = last != null && last.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
            }
            final boolean isPdf = pdf;
            runOnUiThread(() -> open(uri, isPdf));
        });
    }

    private void open(Uri uri, boolean pdf) {
        Class<?> target = pdf ? PdfViewerActivity.class : EditorActivity.class;
        Intent i = new Intent(this, target);
        i.setAction(Intent.ACTION_EDIT);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        try {
            startActivity(i);
        } catch (SecurityException noWrite) {
            // We only hold read access to this file; pass on what we have.
            i.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            try {
                startActivity(i);
            } catch (SecurityException gone) {
                Toast.makeText(this, "This file is no longer available.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdown();
        super.onDestroy();
    }

    private final class RecentAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.recent_item, parent, false);
            RecentStore.Entry e = items.get(position);
            String ext = FileTypes.ext(e.name);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(FileTypes.icon(ext));
            ((TextView) v.findViewById(R.id.name)).setText(e.name);
            CharSequence when = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            ((TextView) v.findViewById(R.id.when)).setText(
                    ext.isEmpty() ? when : ext.toUpperCase(java.util.Locale.ROOT) + "  ·  " + when);
            ImageButton menu = v.findViewById(R.id.menu);
            menu.setFocusable(false);
            menu.setOnClickListener(x -> showItemMenu(x, e));
            return v;
        }
    }
}
