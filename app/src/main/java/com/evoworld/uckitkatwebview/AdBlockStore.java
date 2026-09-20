package com.evoworld.uckitkatwebview;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/*
 * Ad/tracker filter list metadata + domain whitelist storage. Plain
 * SQLiteOpenHelper(Context,...) same style as BrowserDatabase.java -
 * safe here (unlike SettingsStore site-rule table) because this class
 * is NEVER touch from inside a WebViewClient/WebChromeClient hook call
 * stack. Actual per-request block decision only ever consult
 * AdBlockEngine own in-memory copy (see that class); THIS class only
 * get read/write from MainActivity/AdBlockActivity own UI-thread code,
 * or from AdBlockEngine own background update Thread (also not a hook
 * stack). See AdBlockEngine.java top note for the full reasoning -
 * same lesson SettingsStore.java already document for why the site-rule
 * table specifically need the special safe-path treatment and this one
 * specifically don't.
 *
 * Raw downloaded filter list TEXT itself not store here (can be
 * several MB, no query need over the raw text, would bloat db for
 * nothing) - live as plain file instead, one per list, under
 * getFilterFile(ctx, id). This class only hold small metadata row per
 * list (url/name/enable/last-update/rule-count/last-error) plus
 * whitelist domain list.
 */
public class AdBlockStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "uckitkatwebview_adblock.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_LISTS = "filter_lists";
    private static final String TABLE_WHITELIST = "whitelist";

    public static final class FilterList {
        public long id;
        public String name;
        public String url;
        public boolean enabled;
        public long lastUpdated; // 0 = never
        public int ruleCount;
        public String lastError; // null if last update ok (or never try yet)
    }

    public AdBlockStore(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_LISTS + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "name TEXT NOT NULL, "
            + "url TEXT NOT NULL, "
            + "enabled INTEGER NOT NULL DEFAULT 1, "
            + "last_updated INTEGER NOT NULL DEFAULT 0, "
            + "rule_count INTEGER NOT NULL DEFAULT 0, "
            + "last_error TEXT)");

        db.execSQL("CREATE TABLE " + TABLE_WHITELIST + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "host TEXT NOT NULL UNIQUE)");

        /*
         * Seed EasyList as the one default list, on by default,
         * last_updated=0 (never yet) so AdBlockEngine startup staleness
         * check download it right away first run.
         */
        ContentValues cv = new ContentValues();
        cv.put("name", "EasyList");
        cv.put("url", "https://easylist.to/easylist/easylist.txt");
        cv.put("enabled", 1);
        cv.put("last_updated", 0);
        cv.put("rule_count", 0);
        db.insert(TABLE_LISTS, null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // no old version yet, nothing to migrate
    }

    // filter lists

    public List<FilterList> listFilterLists() {
        List<FilterList> out = new ArrayList<FilterList>();
        Cursor c = getReadableDatabase().query(TABLE_LISTS,
            new String[]{"_id", "name", "url", "enabled", "last_updated", "rule_count", "last_error"},
            null, null, null, null, "_id ASC");
        try {
            while (c.moveToNext()) {
                out.add(filterListFromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private FilterList filterListFromCursor(Cursor c) {
        FilterList f = new FilterList();
        f.id = c.getLong(0);
        f.name = c.getString(1);
        f.url = c.getString(2);
        f.enabled = c.getInt(3) != 0;
        f.lastUpdated = c.getLong(4);
        f.ruleCount = c.getInt(5);
        f.lastError = c.getString(6);
        return f;
    }

    public long addFilterList(String name, String url) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("url", url);
        cv.put("enabled", 1);
        cv.put("last_updated", 0);
        cv.put("rule_count", 0);
        return getWritableDatabase().insert(TABLE_LISTS, null, cv);
    }

    public void removeFilterList(Context ctx, long id) {
        getWritableDatabase().delete(TABLE_LISTS, "_id = ?", new String[]{String.valueOf(id)});
        File f = getFilterFile(ctx, id);
        if (f.exists()) f.delete();
    }

    public void setFilterListEnabled(long id, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update(TABLE_LISTS, cv, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void updateFilterListResult(long id, long timestamp, int ruleCount, String error) {
        ContentValues cv = new ContentValues();
        cv.put("last_updated", timestamp);
        cv.put("rule_count", ruleCount);
        if (error != null) {
            cv.put("last_error", error);
        } else {
            cv.putNull("last_error");
        }
        getWritableDatabase().update(TABLE_LISTS, cv, "_id = ?", new String[]{String.valueOf(id)});
    }

    // whitelist

    public List<String> listWhitelist() {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().query(TABLE_WHITELIST, new String[]{"host"},
            null, null, null, null, "host ASC");
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void addWhitelistDomain(String host) {
        ContentValues cv = new ContentValues();
        cv.put("host", host);
        getWritableDatabase().insertWithOnConflict(TABLE_WHITELIST, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removeWhitelistDomain(String host) {
        getWritableDatabase().delete(TABLE_WHITELIST, "host = ?", new String[]{host});
    }

    // raw filter text file path helper

    public static File getFilterFile(Context ctx, long listId) {
        File dir = new File(ctx.getFilesDir(), "adblock");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "list_" + listId + ".txt");
    }
}