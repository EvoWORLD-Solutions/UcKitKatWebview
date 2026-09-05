package com.evoworld.uckitkatwebview;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

/*
 * Hold all open tab of browser.
 *
 * Each Tab wrap one export.WebView as plain Object, same reason like
 * other place in this project, no compile-time dependency on UC engine
 * class. webView can be null - Tab restore from save session (see
 * SettingsStore.loadOpenTabUrls()/MainActivity.restoreSavedTabs()) start
 * with webView null, WebView only construct when user really select it
 * in tab switcher (see MainActivity.switchToTab() lazy part). This way
 * not waste memory on tab that open last session but never visit again.
 *
 * HISTORY (2026-09-04): before this also have separate incognito tab
 * list plus cookie tracking per host. Already remove, because
 * CookieManager confirm by smali is one singleton for whole process, no
 * per-tab isolation possible. So incognito before only can "wipe cookie
 * after", not real separate while browsing. Feel like promise more than
 * can really give, so remove for now. Real fix need second, full
 * separate UC engine instance (second DexClassLoader load whole engine),
 * this is native process level risk, not simple safe change, so not try
 * yet.
 */
public final class TabManager {

    public static final class Tab {
        public Object webView; // null until lazy load, see note top of class
        public String title = "";
        public String url = "";
        public Bitmap favicon;
        public Bitmap thumbnail; // capture before switch away, see MainActivity.captureThumbnail()
        public boolean desktopSiteEnabled = false; // per-tab, memory only, not save, see MainActivity Desktop Site part

        /*
         * Per-tab incognito flag (see MainActivity.createTabWebView()
         * isIncognito part). Memory only, never save on purpose - if
         * incognito tab survive write to SettingsStore.saveOpenTabUrls()
         * and come back on next launch, then "private" browsing quietly
         * become "private until restart app" only.
         * NOTE (2026-09-05): this only isolate THIS tab own cache/DOM
         * storage/database/form data (all really per-WebView setting),
         * not isolate cookie. Same like history note above, CookieManager
         * confirm by smali is one singleton for whole process, no per-tab
         * isolation possible, so incognito tab still use exact same
         * cookie jar like normal tab. See MainActivity incognito part for
         * why cookie leave untouched on purpose instead of half fix.
         */
        public boolean incognito = false;

        public Tab() {}

        public Tab(Object webView) {
            this.webView = webView;
        }
    }

    public final List<Tab> tabs = new ArrayList<Tab>();
    public int activeIndex = -1;

    public Tab activeTab() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    public Tab findTab(Object webView) {
        if (webView == null) return null;
        for (Tab t : tabs) {
            if (t.webView == webView) return t;
        }
        return null;
    }

    public int indexOf(Tab tab) {
        return tabs.indexOf(tab);
    }

    /*
     * Use by MainActivity tab switcher mode toggle (Standard/Incognito).
     * Return live-filter snapshot, not view, because caller
     * (buildTabSwitcherPanel() adapter) need stable position index into
     * list that match only tab currently show.
     */
    public List<Tab> tabsForMode(boolean incognito) {
        List<Tab> out = new ArrayList<Tab>();
        for (Tab t : tabs) {
            if (t.incognito == incognito) out.add(t);
        }
        return out;
    }

    public int countForMode(boolean incognito) {
        int n = 0;
        for (Tab t : tabs) {
            if (t.incognito == incognito) n++;
        }
        return n;
    }
}