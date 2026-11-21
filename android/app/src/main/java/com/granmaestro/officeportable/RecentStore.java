package com.granmaestro.officeportable;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Recently opened files, kept in SharedPreferences. */
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

    private static final String PREFS = "recent";
    private static final String KEY = "items";
    private static final int MAX = 40;

    private final SharedPreferences prefs;

    RecentStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.getString("uri"), o.optString("name", "Document"), o.optLong("time")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    synchronized void add(String uri, String name) {
        List<Entry> items = list();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).uri.equals(uri)) items.remove(i);
        }
        items.add(0, new Entry(uri, name, System.currentTimeMillis()));
        while (items.size() > MAX) items.remove(items.size() - 1);
        save(items);
    }

    synchronized void remove(String uri) {
        List<Entry> items = list();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).uri.equals(uri)) items.remove(i);
        }
        save(items);
    }

    private void save(List<Entry> items) {
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
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
