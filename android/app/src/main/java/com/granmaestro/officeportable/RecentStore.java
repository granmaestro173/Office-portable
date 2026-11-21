package com.granmaestro.officeportable;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Recently opened files. Written by the :editor process and read by the home
 * screen in the main process, so it's a small JSON file read fresh on every
 * call (SharedPreferences caches per process and would go stale), with a file
 * lock around read-modify-write.
 */
final class RecentStore {
    static final class Entry {
        final String uri;
        final String name;
        final long time;

        Entry(String uri, String name, long time) {
            this.uri = uri;
            this.name = name;
            this.time = time;
        }
    }

    private static final int MAX = 40;
    private final File file;
    private final File lockFile;

    RecentStore(Context context) {
        Context app = context.getApplicationContext();
        File dir = app.getFilesDir();
        file = new File(dir, "recent.json");
        lockFile = new File(dir, "recent.lock");
        if (!file.exists()) migrateFromPrefs(app);
    }

    /** Versions before 1.0.5 kept the list in SharedPreferences. */
    private void migrateFromPrefs(Context app) {
        android.content.SharedPreferences prefs = app.getSharedPreferences("recent", Context.MODE_PRIVATE);
        String old = prefs.getString("items", null);
        if (old == null) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(old.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        prefs.edit().remove("items").apply();
    }

    List<Entry> list() {
        try (FileOutputStream lock = new FileOutputStream(lockFile, true);
             FileLock ignored = lock.getChannel().lock()) {
            return read();
        } catch (IOException | RuntimeException e) {
            return read();
        }
    }

    void add(String uri, String name) {
        update(items -> {
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).uri.equals(uri)) items.remove(i);
            items.add(0, new Entry(uri, name, System.currentTimeMillis()));
            while (items.size() > MAX) items.remove(items.size() - 1);
        });
    }

    void remove(String uri) {
        update(items -> {
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).uri.equals(uri)) items.remove(i);
        });
    }

    private interface Edit {
        void apply(List<Entry> items);
    }

    private void update(Edit edit) {
        try (FileOutputStream lock = new FileOutputStream(lockFile, true);
             FileLock ignored = lock.getChannel().lock()) {
            List<Entry> items = read();
            edit.apply(items);
            write(items);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private List<Entry> read() {
        List<Entry> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 1 << 20)];
            int n = 0, r;
            while (n < data.length && (r = in.read(data, n, data.length - n)) > 0) n += r;
            JSONArray arr = new JSONArray(new String(data, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.getString("uri"), o.optString("name", "Document"), o.optLong("time")));
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    private void write(List<Entry> items) throws IOException {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : items) {
                JSONObject o = new JSONObject();
                o.put("uri", e.uri);
                o.put("name", e.name);
                o.put("time", e.time);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new IOException("rename failed");
    }
}
