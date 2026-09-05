package com.evoworld.uckitkatwebview;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * Settings storage + domain-pattern match, share by MainActivity/
 * SettingsActivity (read+write, from app UI) and XposedInit (read-only,
 * from inside WebViewClient/WebChromeClient hook).
 *
 * HISTORY (2026-09-03): before this back by plain
 * Context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE), on the
 * (document, but wrong) assume that being same-process self-hook module
 * make that safe, no XSharedPreferences/makeWorldReadable() cross-process
 * bridge need, not like old ucbrowser.uamod project. That hold true for
 * WHICH PROCESS, but not for WHICH FILE: this project own
 * getPackageName() spoof (for UC/chromium-internal caller, elsewhere in
 * this project) also catch call made from inside this class whenever
 * they happen on call stack running "inside" Xposed
 * WebViewClient/WebChromeClient hook, exactly where userscript/UA rule
 * match happen per navigate, silently resolve getSharedPreferences() to
 * COMPLETELY different, permanently stale file than the one
 * SettingsActivity plain-UI-thread save write to. First try to fix this
 * by route through explicit createPackageContext() with our real,
 * hardcode package name did NOT work either, confirm via log: freshly
 * create Context own getPackageName() STILL come back spoof when query
 * from same call stack, prove the hook intercept getPackageName() METHOD
 * base on caller stack trace, not tie to any specific Context instance.
 * Nothing route through getPackageName(), direct or indirect (including
 * whatever SharedPreferences use internal), can be trust from inside a
 * hook callback.
 *
 * Fix properly now by not use SharedPreferences at all: KvStore below
 * locate its file via ApplicationInfo.dataDir, plain FIELD read (not
 * method call), that why it not interceptable by same hook, no method
 * invoke there for spoof to attach to.
 *
 * UPDATE (2026-09-05): site rules (loadRules()/saveRules() below) move
 * off flat JSON onto own tiny SQLite file (rules matter enough, user
 * grow big enough, JSON round-trip parse whole array every single
 * navigate not great long-term). Bookmark/history already have own
 * proper SQLite (BrowserDatabase.java), but that class construct
 * through plain SQLiteOpenHelper(Context, ...), which internal resolve
 * its db file path through the SAME Context/PackageManager machinery
 * the getPackageName() spoof note above warn about (BrowserDatabase
 * itself safe today only because MainActivity only ever touch it from
 * its own UI-thread code, NEVER from inside a hook callback - rules on
 * the other hand read from XposedInit EVERY navigate, exact call-stack
 * this whole file already fight hard to stay safe from). So rules get
 * own separate tiny .db file instead, opened the SAME safe way KvStore
 * already prove out: resolve path via ApplicationInfo.dataDir (plain
 * field) then SQLiteDatabase.openOrCreateDatabase(File, ...) (static
 * method, take explicit File, never touch Context/getPackageName()
 * resolution path at all) - not XSQLiteOpenHelper/getWritableDatabase()
 * convenience, on purpose, for the same hook-call-stack-safety reason
 * KvStore below already document. One-time migration copy whatever old
 * KEY_RULES_JSON blob already hold into the new table on first read,
 * so nobody testing this app already lose rule they already set up.
 */

public final class SettingsStore {

    public static final String PREFS_NAME = "uckitkatwebview_settings";
    public static final String KEY_GLOBAL_UA = "global_user_agent";
    public static final String KEY_RULES_JSON = "domain_rules_json"; // legacy, migration-only now, see loadRules()/RULES_DB_NAME note
    /*
     * KvStore flag, set once migrateRulesJsonToSqliteIfNeeded() finish
     * (whether it actually find old data to move or not), so every
     * loadRules() after the very first one skip the check entire - a
     * user who legitimately delete every rule through the UI would
     * otherwise look "empty, maybe not migrate yet" forever and re-try
     * the (harmless but pointless) migration check every single call.
     */
    private static final String KEY_RULES_MIGRATED_TO_SQLITE = "rules_migrated_to_sqlite";

    private static final String RULES_DB_NAME = "evo_site_rules.db";
    private static final String RULES_TABLE = "rules";

    public static final String KEY_DEFAULT_URL = "default_url";
    public static final String KEY_LAST_URL = "last_url";
    public static final String KEY_LOAD_LAST_URL = "load_last_url";
    public static final String KEY_HIDE_BOTTOM_BAR_GLOBAL = "hide_bottom_bar_global";
    public static final String KEY_PREVENT_AUTO_KEYBOARD_WAKE = "prevent_auto_keyboard_wake";
    public static final String KEY_OPEN_TABS_JSON = "open_tabs_json";
    public static final String KEY_ACTIVE_TAB_INDEX = "active_tab_index";
    public static final String KEY_HISTORY_EXPIRY_DAYS = "history_expiry_days";

    /*
     * Store as days (30/90/180/365), or -1 for "keep forever". Default is
     * "keep forever", purge is opt-in, not assume.
     */
    public static final int HISTORY_EXPIRY_FOREVER = -1;

    public static final String RUN_AT_START = "document_start";
    public static final String RUN_AT_END = "document_end";

    private SettingsStore() {}

    public static final class Rule {
        public String pattern;
        public String customUa;
        public String script;
        public String runAt;
        public boolean hideBottomBar;

        public Rule(String pattern, String customUa, String script, String runAt, boolean hideBottomBar) {
            this.pattern = pattern;
            this.customUa = customUa;
            this.script = script;
            this.runAt = runAt;
            this.hideBottomBar = hideBottomBar;
        }

        @Override
        public String toString() {
            String timingLabel = RUN_AT_START.equals(runAt) ? "document start" : "on loaded";
            String uaTag = TextUtils.isEmpty(customUa) ? "" : " [UA]";
            String scriptTag = TextUtils.isEmpty(script) ? "" : " [JS]";
            String barTag = hideBottomBar ? " [NoBar]" : "";
            return pattern + " (" + timingLabel + ")" + uaTag + scriptTag + barTag;
        }
    }

    /*
     * TEMP DIAGNOSTIC (2026-09-03): mirror XposedInit.logToSd format/
     * behavior (same file, same "[tid=X] [TAG] msg" shape, fsync right
     * away) but self-contain, need work same no matter which
     * classloader-copy of SettingsStore call it. Write to sdcard log
     * instead of logcat since this project run on VMOS, where logcat not
     * reachable (that what IS_INTERNAL_WIFI_DEBUG/this log file exist
     * for). Safe to remove once fix confirm solid.
     */
    private static void debugLog(String msg) {
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File logFile = new File(sdcard, "uckitkatwebview.log");
            FileOutputStream fos = new FileOutputStream(logFile, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write("[tid=" + android.os.Process.myTid() + "] [EVO_SETTINGS_DEBUG] " + msg + "\n");
            osw.flush();
            fos.getFD().sync();
            osw.close();
            fos.close();
        } catch (Throwable ignored) {}
    }

    /*
     * Minimal SharedPreferences-shape store (getString/getBoolean/edit()
     * .putX().commit()/.apply()) back by flat JSON file, so every other
     * method in this class below barely need change. On purpose NOT
     * cache across call/instance, always re-read from disk on open(),
     * trade little file I/O (file is tiny) for hard guarantee of
     * freshness, exactly the property that broke before.
     */
    private static final class KvStore {
        private final File file;
        private final JSONObject data;

        private KvStore(File file, JSONObject data) {
            this.file = file;
            this.data = data;
        }

        static KvStore open(Context ctx) {
            /*
             * ApplicationInfo.dataDir is plain field on ApplicationInfo
             * object, not method call, so it can not get catch by hook
             * target Context.getPackageName() specific. No evidence
             * getApplicationInfo() self is spoof anywhere in this project
             * (every observe spoof log line name getPackageName()
             * specific), so this the one path left that should be
             * reliable from any call stack.
             */
            File dir = new File(ctx.getApplicationInfo().dataDir, "evo_settings");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "settings.json");
            boolean existedBefore = f.exists();
            JSONObject data = readFile(f);

            if (!existedBefore) {
                /*
                 * One-time best-effort migration from the old, now-abandoned
                 * SharedPreferences file. Only actually finds anything if THIS
                 * particular call happens to land on an unspoofed context (e.g.
                 * the app's own UI thread) - harmless no-op otherwise, just
                 * starts fresh, same as a clean install.
                 */
                try {
                    SharedPreferences old = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    Map<String, ?> all = old.getAll();
                    for (Map.Entry<String, ?> e : all.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof String) data.put(e.getKey(), (String) v);
                        else if (v instanceof Boolean) data.put(e.getKey(), (Boolean) v);
                    }
                    if (all.size() > 0) {
                        debugLog("KvStore.open(): migrated " + all.size() + " key(s) from legacy SharedPreferences");
                    }
                } catch (Throwable ignored) {}
            }

            return new KvStore(f, data);
        }

        private static JSONObject readFile(File f) {
            try {
                if (!f.exists()) return new JSONObject();
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return new JSONObject(sb.toString());
            } catch (Throwable t) {
                return new JSONObject();
            }
        }

        String getString(String key, String def) {
            return data.optString(key, def);
        }

        boolean getBoolean(String key, boolean def) {
            return data.optBoolean(key, def);
        }

        Editor edit() {
            return new Editor();
        }

        private synchronized boolean writeToDisk() {
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
                osw.write(data.toString());
                osw.flush();
                fos.getFD().sync();
                osw.close();
                fos.close();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        final class Editor {
            Editor putString(String key, String value) {
                try { data.put(key, value); } catch (Throwable ignored) {}
                return this;
            }
            Editor putBoolean(String key, boolean value) {
                try { data.put(key, value); } catch (Throwable ignored) {}
                return this;
            }
            boolean commit() {
                return writeToDisk();
            }
            void apply() {
                writeToDisk();
            }
        }
    }

    private static KvStore prefs(Context ctx) {
        KvStore store = KvStore.open(ctx);
        /*
         * TEMP DIAGNOSTIC (2026-09-03): dataDir should now read
         * /data/data/com.evoworld.uckitkatwebview consistent, from every
         * caller, including from inside hook callback. Safe to remove
         * once confirm.
         */
        debugLog("prefs(): ctx=" + ctx.getClass().getName()
            + " dataDir=" + ctx.getApplicationInfo().dataDir);
        return store;
    }

    public static String getGlobalUa(Context ctx) {
        return prefs(ctx).getString(KEY_GLOBAL_UA, "");
    }

    public static void setGlobalUa(Context ctx, String ua) {
        prefs(ctx).edit().putString(KEY_GLOBAL_UA, ua == null ? "" : ua.trim()).commit();
    }

    // App-behavior prefs

    public static String getDefaultUrl(Context ctx) {
        return prefs(ctx).getString(KEY_DEFAULT_URL, "");
    }

    public static void setDefaultUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_DEFAULT_URL, url == null ? "" : url.trim()).commit();
    }

    /*
     * Update on every real navigate (see MainActivity.navigateTo(),
     * .onRealNavigationUrlUpdate(), .navigateHistory()) so "load last
     * access URL" always reflect wherever user really end up, including
     * in-page link click and back/forward, not just type URL. Use
     * apply() instead of commit(), this call far more often than the
     * other, user-trigger write in this file. (Both sync now that this
     * is flat file instead of real SharedPreferences, apply() async
     * behavior never load-bearing here, just keep for call-site
     * compatibility.)
     */
    public static String getLastUrl(Context ctx) {
        return prefs(ctx).getString(KEY_LAST_URL, "");
    }

    public static void setLastUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_LAST_URL, url == null ? "" : url).apply();
    }

    public static boolean getLoadLastUrl(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LOAD_LAST_URL, false);
    }

    public static void setLoadLastUrl(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_LOAD_LAST_URL, enabled).commit();
    }

    public static boolean getHideBottomBarGlobal(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HIDE_BOTTOM_BAR_GLOBAL, false);
    }

    public static void setHideBottomBarGlobal(Context ctx, boolean hide) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_BOTTOM_BAR_GLOBAL, hide).commit();
    }

    /*
     * Off by default. When on, XposedInit ImeAdapterImpl.t() hook (the
     * confirm, real Chromium keyboard-show gatekeeper, see smali note in
     * that hook) suppress soft keyboard unless it precede by genuine
     * touch on WebView within MainActivity.KEYBOARD_TOUCH_GRACE_MS,
     * mean page call element.focus() via JS no longer pop keyboard (and
     * window resize that come with it) on own.
     */
    public static boolean getPreventAutoKeyboardWake(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PREVENT_AUTO_KEYBOARD_WAKE, false);
    }

    public static void setPreventAutoKeyboardWake(Context ctx, boolean prevent) {
        prefs(ctx).edit().putBoolean(KEY_PREVENT_AUTO_KEYBOARD_WAKE, prevent).commit();
    }

    /*
     * Just the URL, in order, not full Tab state (title/favicon not
     * save, they get re-fill natural once restore tab really load).
     * Call on every tab open/close/navigate/switch (see
     * MainActivity.saveOpenTabsToDisk()) plus final safety-net save in
     * onPause(), so crash mid-session still lose at most the very last
     * change instead of whole list.
     *
     * On purpose NOT filter out empty-URL entry here (brand new tab
     * that not navigate anywhere yet), KEY_ACTIVE_TAB_INDEX below is
     * plain index into this same list, so skip entry would silently
     * misalign it with the tab it really suppose to point at.
     */
    public static List<String> loadOpenTabUrls(Context ctx) {
        List<String> out = new ArrayList<String>();
        String raw = prefs(ctx).getString(KEY_OPEN_TABS_JSON, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i, ""));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveOpenTabUrls(Context ctx, List<String> urls) {
        JSONArray arr = new JSONArray();
        for (String u : urls) {
            arr.put(u == null ? "" : u);
        }
        prefs(ctx).edit().putString(KEY_OPEN_TABS_JSON, arr.toString()).apply();
    }

    /*
     * Which index in the list above really on screen when last save, so
     * reopen app land back on the tab user really look at, not always
     * tab 0.
     */
    public static int getActiveTabIndex(Context ctx) {
        try {
            return Integer.parseInt(prefs(ctx).getString(KEY_ACTIVE_TAB_INDEX, "0"));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setActiveTabIndex(Context ctx, int index) {
        prefs(ctx).edit().putString(KEY_ACTIVE_TAB_INDEX, String.valueOf(index)).apply();
    }

    public static int getHistoryExpiryDays(Context ctx) {
        try {
            return Integer.parseInt(prefs(ctx).getString(KEY_HISTORY_EXPIRY_DAYS, String.valueOf(HISTORY_EXPIRY_FOREVER)));
        } catch (Throwable t) {
            return HISTORY_EXPIRY_FOREVER;
        }
    }

    public static void setHistoryExpiryDays(Context ctx, int days) {
        prefs(ctx).edit().putString(KEY_HISTORY_EXPIRY_DAYS, String.valueOf(days)).commit();
    }

    /*
     * Effective bottom-bar-hidden state for URL: global switch win
     * outright, otherwise any match rule with hideBottomBar=true hide it
     * for that domain. No "force-show" override for rule when global
     * switch on, simple OR semantics, match what really ask for.
     */
    public static boolean resolveHideBottomBar(Context ctx, String url) {
        if (getHideBottomBarGlobal(ctx)) return true;
        if (url == null) return false;
        for (Rule r : loadRules(ctx)) {
            if (r.hideBottomBar && matchesPattern(url, r.pattern)) {
                return true;
            }
        }
        return false;
    }

    /*
     * Same safe-path technique as KvStore above (ApplicationInfo.dataDir
     * plain field, not any Context/getPackageName()-resolving method) -
     * see header comment UPDATE note on why this can NOT just be a
     * normal SQLiteOpenHelper(Context,...) like BrowserDatabase.java
     * use, given this get call from inside XposedInit hook every single
     * navigate, the exact call-stack this whole file already fight to
     * stay safe from.
     */
    private static SQLiteDatabase openRulesDb(Context ctx) {
        File dir = new File(ctx.getApplicationInfo().dataDir, "databases");
        if (!dir.exists()) dir.mkdirs();
        File dbFile = new File(dir, RULES_DB_NAME);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS " + RULES_TABLE + " ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "pattern TEXT NOT NULL, "
            + "custom_ua TEXT, "
            + "script TEXT, "
            + "run_at TEXT NOT NULL, "
            + "hide_bottom_bar INTEGER NOT NULL DEFAULT 0)");
        return db;
    }

    /*
     * Run once (guard by KEY_RULES_MIGRATED_TO_SQLITE flag in KvStore),
     * first time loadRules() ever call after this update, copy whatever
     * old KEY_RULES_JSON blob already hold into the new rules table - so
     * anyone already set up rule/UA/userscript during earlier testing
     * not lose them just from this storage-layer change. Old JSON key
     * left alone after (not clear it), harmless dead value, simpler than
     * also need clear it correct.
     */
    private static void migrateRulesJsonToSqliteIfNeeded(Context ctx, SQLiteDatabase db) {
        if (prefs(ctx).getBoolean(KEY_RULES_MIGRATED_TO_SQLITE, false)) return;
        try {
            String rawJson = prefs(ctx).getString(KEY_RULES_JSON, "[]");
            JSONArray arr = new JSONArray(rawJson);
            int migrated = 0;
            db.beginTransaction();
            try {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    ContentValues cv = new ContentValues();
                    cv.put("pattern", obj.getString("pattern"));
                    cv.put("custom_ua", obj.optString("customUa", ""));
                    cv.put("script", obj.optString("script", ""));
                    cv.put("run_at", obj.optString("runAt", RUN_AT_END));
                    cv.put("hide_bottom_bar", obj.optBoolean("hideBottomBar", false) ? 1 : 0);
                    db.insert(RULES_TABLE, null, cv);
                    migrated++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            debugLog("migrateRulesJsonToSqliteIfNeeded(): migrated " + migrated + " rule(s) from legacy JSON");
        } catch (Throwable t) {
            debugLog("migrateRulesJsonToSqliteIfNeeded(): nothing to migrate or parse fail - " + t);
        } finally {
            prefs(ctx).edit().putBoolean(KEY_RULES_MIGRATED_TO_SQLITE, true).commit();
        }
    }

    /*
     * ORDER BY _id ASC preserve the exact same order rule always had as
     * a JSON array (insertion order) - SiteRulesActivity only ever
     * append new rule at list end or edit-in-place at existing index,
     * never reorder, so this stay behaviorally identical to before.
     */
    public static List<Rule> loadRules(Context ctx) {
        List<Rule> out = new ArrayList<Rule>();
        SQLiteDatabase db = null;
        try {
            db = openRulesDb(ctx);
            migrateRulesJsonToSqliteIfNeeded(ctx, db);
            Cursor c = db.query(RULES_TABLE,
                new String[]{"pattern", "custom_ua", "script", "run_at", "hide_bottom_bar"},
                null, null, null, null, "_id ASC");
            try {
                while (c.moveToNext()) {
                    out.add(new Rule(
                        c.getString(0),
                        c.getString(1) == null ? "" : c.getString(1),
                        c.getString(2) == null ? "" : c.getString(2),
                        c.getString(3) == null ? RUN_AT_END : c.getString(3),
                        c.getInt(4) != 0
                    ));
                }
            } finally {
                c.close();
            }
            debugLog("loadRules(): loaded " + out.size() + " rule(s) from sqlite");
        } catch (Throwable t) {
            debugLog("loadRules(): failed - " + t);
        } finally {
            if (db != null) db.close();
        }
        return out;
    }

    /*
     * Delete-all-then-reinsert-all in one transaction, same "just
     * persist whatever the current in-memory list is" semantic the old
     * JSON version already had (SiteRulesActivity always pass its
     * WHOLE own list every save, never a partial diff), just backed by
     * SQLite now instead of a rewritten JSON string. _id reassign fresh
     * in insertion order every call, which since it always IS the full
     * list in order, keep read-back order correct every time (see
     * loadRules() ORDER BY note).
     */
    public static void saveRules(Context ctx, List<Rule> rules) {
        SQLiteDatabase db = null;
        try {
            db = openRulesDb(ctx);
            db.beginTransaction();
            try {
                db.delete(RULES_TABLE, null, null);
                for (Rule r : rules) {
                    ContentValues cv = new ContentValues();
                    cv.put("pattern", r.pattern);
                    cv.put("custom_ua", r.customUa == null ? "" : r.customUa);
                    cv.put("script", r.script == null ? "" : r.script);
                    cv.put("run_at", r.runAt == null ? RUN_AT_END : r.runAt);
                    cv.put("hide_bottom_bar", r.hideBottomBar ? 1 : 0);
                    db.insert(RULES_TABLE, null, cv);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            debugLog("saveRules(): committed " + rules.size() + " rule(s) to sqlite");
        } catch (Throwable t) {
            debugLog("saveRules(): failed - " + t);
        } finally {
            if (db != null) db.close();
        }
    }

    /*
     * Resolve which UA (if any) should apply to this URL: a match rule
     * own UA take priority, then global default, else null (mean: not
     * touch it, leave engine real default UA alone). Unlike old mod, no
     * hardcode fallback desktop UA bake in, unconfigure install change
     * nothing.
     */
    public static String resolveUserAgent(Context ctx, String url) {
        if (url == null) return null;
        for (Rule r : loadRules(ctx)) {
            if (!TextUtils.isEmpty(r.customUa) && matchesPattern(url, r.pattern)) {
                return r.customUa.trim();
            }
        }
        String global = getGlobalUa(ctx);
        return TextUtils.isEmpty(global) ? null : global;
    }

    public static List<Rule> matchingScripts(Context ctx, String url, String runAt) {
        List<Rule> out = new ArrayList<Rule>();
        if (url == null) return out;
        for (Rule r : loadRules(ctx)) {
            if (runAt.equals(r.runAt) && !TextUtils.isEmpty(r.script) && matchesPattern(url, r.pattern)) {
                out.add(r);
            }
        }
        return out;
    }

    /*
     * Port basically unchange from old ucbrowser.uamod project, a small
     * match-pattern (Chrome-extension-style *://*.example.com/*) to
     * regex compiler. "*" or empty/"<all_urls>" match everything, a bare
     * "example.com" expand to "*://*.example.com/*".
     */
    public static boolean matchesPattern(String url, String pattern) {
        if (url == null) return false;
        if (pattern == null || pattern.trim().isEmpty() || pattern.equals("*") || pattern.equals("<all_urls>")) {
            return true;
        }
        try {
            String rawPattern = pattern.trim();
            if (!rawPattern.contains("://")) {
                rawPattern = rawPattern.startsWith("*.") ? ("*://" + rawPattern + "/*") : ("*://*." + rawPattern + "/*");
            }

            int schemeSep = rawPattern.indexOf("://");
            if (schemeSep == -1) return false;
            String scheme = rawPattern.substring(0, schemeSep);
            String rest = rawPattern.substring(schemeSep + 3);

            int pathSep = rest.indexOf('/');
            String host = (pathSep == -1) ? rest : rest.substring(0, pathSep);
            String path = (pathSep == -1) ? "/*" : rest.substring(pathSep);

            String schemeRegex;
            if ("*".equals(scheme)) {
                schemeRegex = "https?";
            } else if ("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme) || "ftp".equals(scheme)) {
                schemeRegex = Pattern.quote(scheme);
            } else {
                return false;
            }

            String hostRegex;
            if ("*".equals(host)) {
                hostRegex = "[^/]+";
            } else if (host.startsWith("*.")) {
                hostRegex = "([a-zA-Z0-9_\\-\\.]+\\.)?" + Pattern.quote(host.substring(2));
            } else {
                hostRegex = Pattern.quote(host);
            }

            String pathRegex = Pattern.quote(path).replace("*", "\\E.*\\Q");
            String fullRegex = "^" + schemeRegex + "://" + hostRegex + pathRegex + "$";
            return Pattern.compile(fullRegex, Pattern.CASE_INSENSITIVE).matcher(url).matches();
        } catch (Throwable t) {
            return false;
        }
    }
}