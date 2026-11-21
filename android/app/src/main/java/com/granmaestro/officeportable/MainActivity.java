package com.granmaestro.officeportable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;

    private RecentStore recents;
    private final List<RecentStore.Entry> items = new ArrayList<>();
    private RecentAdapter adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        recents = new RecentStore(this);

        ListView list = findViewById(R.id.list);
        View header = LayoutInflater.from(this).inflate(R.layout.main_header, list, false);
        list.addHeaderView(header, null, false);
        emptyView = header.findViewById(R.id.empty);

        setupTile(header.findViewById(R.id.new_doc), "W", 0xFF2B579A, getString(R.string.new_doc), "docx");
        setupTile(header.findViewById(R.id.new_sheet), "X", 0xFF217346, getString(R.string.new_sheet), "xlsx");
        setupTile(header.findViewById(R.id.new_slides), "P", 0xFFC43E1C, getString(R.string.new_slides), "pptx");
        header.findViewById(R.id.open_file).setOnClickListener(v -> pickFile());

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
    }

    private void refresh() {
        items.clear();
        items.addAll(recents.list());
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void setupTile(View tile, String letter, int color, String label, String newType) {
        TextView badge = tile.findViewById(R.id.badge);
        badge.setText(letter);
        badge.setBackground(rounded(color, 10));
        ((TextView) tile.findViewById(R.id.label)).setText(label);
        tile.setOnClickListener(v -> {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_NEW, newType);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i);
        });
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return d;
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
        openUri(Uri.parse(e.uri));
    }

    private void openUri(Uri uri) {
        Intent i = new Intent(this, EditorActivity.class);
        i.setAction(Intent.ACTION_EDIT);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        startActivity(i);
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
            TextView badge = v.findViewById(R.id.badge);
            badge.setText(ext.isEmpty() ? "?" : ext.toUpperCase());
            badge.setBackground(rounded(FileTypes.color(ext), 8));
            ((TextView) v.findViewById(R.id.name)).setText(e.name);
            ((TextView) v.findViewById(R.id.when)).setText(
                    DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            ImageButton remove = v.findViewById(R.id.remove);
            remove.setFocusable(false);
            remove.setOnClickListener(x -> {
                recents.remove(e.uri);
                refresh();
            });
            return v;
        }
    }
}
