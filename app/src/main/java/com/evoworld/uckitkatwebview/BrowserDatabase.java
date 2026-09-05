package com.evoworld.uckitkatwebview;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/*
 * SQLite storage for bookmark/folder (already full work) and history
 * (schema make now so no need migration later, but not wire to UI yet,
 * that is next part). android.database.sqlite is part of Android SDK
 * self (already there since API 1), no external dependency, not like
 * androidx.core mistake before in this project.
 *
 * Keep separate from SettingsStore on purpose. SettingsStore flat-JSON-
 * file way ok for what it hold (rule, tab session state, small
 * preference value, not much data, no query need), but bookmark/history
 * need real index search (address bar suggestion) and date-range query
 * (history expiry purge, day-group for history view), and not have
 * "rewrite whole file every change" cost that real database can avoid.
 *
 * Folder is FLAT list, no nest. Bookmark folder_id is either real
 * folder id, or NULL mean "Global" (root level, no folder). Delete
 * folder cascade delete bookmark inside too, not move to Global. Just
 * simple behavior, good to know if this surprise you sometime.
 */
public class BrowserDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "uckitkatwebview_browser.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_FOLDERS = "folders";
    private static final String TABLE_BOOKMARKS = "bookmarks";
    private static final String TABLE_HISTORY = "history";

    public static final class Folder {
        public long id;
        public String name;
    }

    public static final class Bookmark {
        public long id;
        public String title;
        public String url;
        public Bitmap favicon;
        public Long folderId; // null = Global (root level)
    }

    public BrowserDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FOLDERS + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "name TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_BOOKMARKS + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "folder_id INTEGER, "
            + "title TEXT, "
            + "url TEXT NOT NULL, "
            + "favicon BLOB, "
            + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_bookmarks_url ON " + TABLE_BOOKMARKS + " (url)");
        db.execSQL("CREATE INDEX idx_bookmarks_folder ON " + TABLE_BOOKMARKS + " (folder_id)");

        /*
         * only schema for now, see note top of class. index on both url
         * (dedup/lookup) and visited_at (day-group, expiry purge) so
         * history feature not need schema change when add those query
         * later
         */
        db.execSQL("CREATE TABLE " + TABLE_HISTORY + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "title TEXT, "
            + "url TEXT NOT NULL, "
            + "visited_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_history_url ON " + TABLE_HISTORY + " (url)");
        db.execSQL("CREATE INDEX idx_history_visited_at ON " + TABLE_HISTORY + " (visited_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // no old version yet, nothing to migrate
    }

    // --- bookmarks ---

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        Cursor c = getReadableDatabase().query(TABLE_BOOKMARKS, new String[]{"_id"},
            "url = ?", new String[]{url}, null, null, null, "1");
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /*
     * return first bookmark match this URL, or null. use for star-sync
     * (check bookmark already exist or not) and remove-confirm flow
     * (need id for really delete it)
     */
    public Bookmark getBookmarkByUrl(String url) {
        if (url == null) return null;
        Cursor c = getReadableDatabase().query(TABLE_BOOKMARKS,
            new String[]{"_id", "folder_id", "title", "url", "favicon"},
            "url = ?", new String[]{url}, null, null, null, "1");
        try {
            if (!c.moveToFirst()) return null;
            return bookmarkFromCursor(c);
        } finally {
            c.close();
        }
    }

    public long addBookmark(String title, String url, Bitmap favicon, Long folderId) {
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("url", url);
        cv.put("favicon", favicon != null ? bitmapToBytes(favicon) : null);
        if (folderId != null) {
            cv.put("folder_id", folderId);
        } else {
            cv.putNull("folder_id");
        }
        cv.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_BOOKMARKS, null, cv);
    }

    public void removeBookmarkByUrl(String url) {
        getWritableDatabase().delete(TABLE_BOOKMARKS, "url = ?", new String[]{url});
    }

    public void deleteBookmark(long id) {
        getWritableDatabase().delete(TABLE_BOOKMARKS, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void updateBookmark(long id, String title, String url) {
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("url", url);
        getWritableDatabase().update(TABLE_BOOKMARKS, cv, "_id = ?", new String[]{String.valueOf(id)});
    }

    // global bookmark (folder_id IS NULL), first layer of the list
    public List<Bookmark> listGlobalBookmarks() {
        return queryBookmarks("folder_id IS NULL", null);
    }

    public List<Bookmark> listBookmarksInFolder(long folderId) {
        return queryBookmarks("folder_id = ?", new String[]{String.valueOf(folderId)});
    }

    public List<Bookmark> searchBookmarks(String query) {
        String like = "%" + query + "%";
        return queryBookmarks("title LIKE ? OR url LIKE ?", new String[]{like, like});
    }

    private List<Bookmark> queryBookmarks(String selection, String[] args) {
        List<Bookmark> out = new ArrayList<Bookmark>();
        Cursor c = getReadableDatabase().query(TABLE_BOOKMARKS,
            new String[]{"_id", "folder_id", "title", "url", "favicon"},
            selection, args, null, null, "created_at DESC");
        try {
            while (c.moveToNext()) {
                out.add(bookmarkFromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private Bookmark bookmarkFromCursor(Cursor c) {
        Bookmark b = new Bookmark();
        b.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        int folderIdx = c.getColumnIndexOrThrow("folder_id");
        b.folderId = c.isNull(folderIdx) ? null : c.getLong(folderIdx);
        b.title = c.getString(c.getColumnIndexOrThrow("title"));
        b.url = c.getString(c.getColumnIndexOrThrow("url"));
        byte[] favBytes = c.getBlob(c.getColumnIndexOrThrow("favicon"));
        b.favicon = favBytes != null ? BitmapFactory.decodeByteArray(favBytes, 0, favBytes.length) : null;
        return b;
    }

    // --- folders ---

    public long createFolder(String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        return getWritableDatabase().insert(TABLE_FOLDERS, null, cv);
    }

    public void renameFolder(long id, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        getWritableDatabase().update(TABLE_FOLDERS, cv, "_id = ?", new String[]{String.valueOf(id)});
    }

    // cascade: delete bookmark inside this folder too, not move to Global, see note top of class
    public void deleteFolder(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_BOOKMARKS, "folder_id = ?", new String[]{String.valueOf(id)});
        db.delete(TABLE_FOLDERS, "_id = ?", new String[]{String.valueOf(id)});
    }

    public List<Folder> listFolders() {
        List<Folder> out = new ArrayList<Folder>();
        Cursor c = getReadableDatabase().query(TABLE_FOLDERS, new String[]{"_id", "name"},
            null, null, null, null, "name ASC");
        try {
            while (c.moveToNext()) {
                Folder f = new Folder();
                f.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                f.name = c.getString(c.getColumnIndexOrThrow("name"));
                out.add(f);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // --- history ---

    public static final class HistoryEntry {
        public long id;
        public String title;
        public String url;
        public long visitedAt;
    }

    /*
     * one row every visit, not dedup or collapse. this match "divide by
     * day" need naturally, because visit page again same day (or
     * different day) should show own entry. title is whatever know at
     * navigate time (usually just URL self, because real title not
     * arrive yet), see updateLatestVisitTitle() below for fix once
     * title come
     */
    public long recordVisit(String url, String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("url", url);
        cv.put("visited_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_HISTORY, null, cv);
    }

    /*
     * call once real page title arrive (onReceivedTitle, async, after
     * visit already record with just URL as placeholder title). fix
     * only MOST RECENT row for this URL, not every visit to it
     */
    public void updateLatestVisitTitle(String url, String title) {
        if (TextUtils.isEmpty(title)) return;
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.query(TABLE_HISTORY, new String[]{"_id"},
            "url = ?", new String[]{url}, null, null, "visited_at DESC", "1");
        try {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                ContentValues cv = new ContentValues();
                cv.put("title", title);
                db.update(TABLE_HISTORY, cv, "_id = ?", new String[]{String.valueOf(id)});
            }
        } finally {
            c.close();
        }
    }

    /*
     * all history, newest first. day-group do in Java
     * (MainActivity.showHistoryView()), not SQL date function, so
     * local-timezone "which day" handle simple, same way like device
     * already show date other place
     */
    public List<HistoryEntry> listAllHistory() {
        return queryHistory(null, null);
    }

    public List<HistoryEntry> searchHistory(String query) {
        String like = "%" + query + "%";
        return queryHistory("title LIKE ? OR url LIKE ?", new String[]{like, like});
    }

    private List<HistoryEntry> queryHistory(String selection, String[] args) {
        List<HistoryEntry> out = new ArrayList<HistoryEntry>();
        Cursor c = getReadableDatabase().query(TABLE_HISTORY,
            new String[]{"_id", "title", "url", "visited_at"},
            selection, args, null, null, "visited_at DESC");
        try {
            while (c.moveToNext()) {
                HistoryEntry e = new HistoryEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                e.title = c.getString(c.getColumnIndexOrThrow("title"));
                e.url = c.getString(c.getColumnIndexOrThrow("url"));
                e.visitedAt = c.getLong(c.getColumnIndexOrThrow("visited_at"));
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteHistoryEntry(long id) {
        getWritableDatabase().delete(TABLE_HISTORY, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void clearAllHistory() {
        getWritableDatabase().delete(TABLE_HISTORY, null, null);
    }

    // expiryDays same like SettingsStore.HISTORY_EXPIRY_FOREVER (-1 mean keep forever, do nothing) or positive day number
    public void purgeExpiredHistory(int expiryDays) {
        if (expiryDays <= 0) return; // forever, or bad value, not touch anything
        long cutoff = System.currentTimeMillis() - (expiryDays * 24L * 60L * 60L * 1000L);
        getWritableDatabase().delete(TABLE_HISTORY, "visited_at < ?", new String[]{String.valueOf(cutoff)});
    }

    private static byte[] bitmapToBytes(Bitmap bitmap) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}