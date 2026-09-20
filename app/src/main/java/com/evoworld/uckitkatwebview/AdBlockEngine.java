package com.evoworld.uckitkatwebview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/*
 * In-memory ad/tracker block engine - the ONLY thing ever consult from
 * inside XposedInit shouldInterceptRequest hook (every single request,
 * hot path), so everything read there (isGloballyEnabled(), isWhitelisted(),
 * matchListNameIfBlocked()) MUST already be pure in-memory, zero disk/
 * SQLite touch on that path.
 *
 * AdBlockStore (persistent metadata) and the raw filter list .txt file
 * only ever get touch by reload()/initAsync()/updateAllNowAsync(), all
 * three call from a safe (non-hook) context - app start, a background
 * update Thread, or MainActivity/AdBlockActivity own UI-thread edit
 * handler. NEVER from inside a WebViewClient/WebChromeClient hook call
 * stack - that is exactly the class of bug SettingsStore.java own
 * header comment already document at painful length for site rule/UA,
 * this class just make sure to never repeat it by keeping the hot path
 * 100% in-memory instead.
 *
 * Singleton by static volatile field, whole new CompiledData build
 * fresh then ONE reference swap on reload() - a request mid-match never
 * see a half-updated structure this way.
 */
public final class AdBlockEngine {

    private AdBlockEngine() {}

    public static final class BlockEvent {
        public final long time;
        public final String url;
        public final String listName;
        public BlockEvent(long time, String url, String listName) {
            this.time = time;
            this.url = url;
            this.listName = listName;
        }
    }

    private static final class PatternRule {
        final Pattern pattern;
        final String listName;
        final String sourceLine; // original ABP rule text (pre-option-stripping), for diagnostics - "which exact rule matched" not just "which list"
        PatternRule(Pattern p, String n, String src) {
            pattern = p;
            listName = n;
            sourceLine = src;
        }
    }

    // see parseLine() note for why this exist - bound the total SLOW linear-scan pattern list size, this is what actually drive per-request matching cost, not domain-anchor rule count
    private static final int MAX_TOTAL_PATTERN_RULES = 4000;

    private static final class CompiledData {
        final Map<String, String> blockDomains = new HashMap<String, String>(); // domain -> which list name match it
        final Map<String, String> blockDomainsThirdPartyOnly = new HashMap<String, String>();
        final Set<String> exceptionDomains = new HashSet<String>(); // provenance not important for exception
        final List<PatternRule> blockPatterns = new ArrayList<PatternRule>();
        final List<Pattern> exceptionPatterns = new ArrayList<Pattern>();
        final Set<String> whitelistHosts = new HashSet<String>();
        boolean globalEnabled = true;
        int patternRulesSkippedForCap = 0;
    }

    private static volatile CompiledData sData = new CompiledData();

    /*
     * Session-only bypass (mirror MainActivity.mSessionTrustedSslHosts
     * same spirit/reason - never persist, "trust" that survive restart
     * quiet become permanent without ask again not want that) - host
     * user tap "Load anyway" on the chrome://error adblock interstitial
     * for, this app run only. Kept HERE (not MainActivity) since
     * matchListNameIfBlocked() below need consult it right on the hot
     * path.
     */
    private static final Set<String> sSessionBypassHosts = Collections.synchronizedSet(new HashSet<String>());

    // recent block event, capped ring-ish list for the "see what actually happen" transparency UI, in-memory only, not persist across restart on purpose (keep it lightweight, a log not a database)
    private static final int MAX_RECENT_EVENTS = 200;
    private static final List<BlockEvent> sRecentEvents = Collections.synchronizedList(new ArrayList<BlockEvent>());

    private static volatile long sTotalBlockedAllTime = 0;

    // ---- public API used from the hook hot path (all pure in-memory) ----

    public static boolean isGloballyEnabled() {
        return sData.globalEnabled;
    }

    public static boolean isWhitelisted(String host) {
        if (host == null) return false;
        return sData.whitelistHosts.contains(host) || sSessionBypassHosts.contains(host);
    }

    /*
     * requestUri: the actual resource being fetch. pageHost: current
     * top document own host (for $third-party check + whitelist check),
     * may be null (no committed page yet). Return the matching list
     * NAME if block, null if allow - null cover both "no rule match"
     * and "an exception rule explicitly allow it", caller only care
     * about the boolean either way, name just for the recent-block log/
     * "see what actually happen" UI.
     */
    public static String matchListNameIfBlocked(Uri requestUri, String pageHost) {
        CompiledData d = sData;
        if (!d.globalEnabled) return null;
        String host = requestUri.getHost();
        if (host == null) return null;
        if (pageHost != null && isWhitelisted(pageHost)) return null;

        if (domainSetContains(d.exceptionDomains, host)) return null;
        String reqStr = requestUri.toString();
        for (Pattern p : d.exceptionPatterns) {
            if (p.matcher(reqStr).find()) return null;
        }

        boolean thirdParty = pageHost != null && !sameSite(host, pageHost);

        String hit = domainMapLookup(d.blockDomains, host);
        if (hit != null) return hit;
        if (thirdParty) {
            hit = domainMapLookup(d.blockDomainsThirdPartyOnly, host);
            if (hit != null) return hit;
        }
        for (PatternRule pr : d.blockPatterns) {
            if (pr.pattern.matcher(reqStr).find()) return pr.listName + ": pattern=" + pr.sourceLine;
        }
        return null;
    }

    // crude "same registrable-ish domain" check for $third-party - good enough for a heuristic, not full public-suffix-list correct (would need shipping/updating that whole separate list, out of scope for v1)
    private static boolean sameSite(String a, String b) {
        if (a.equals(b)) return true;
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        if (pa.length < 2 || pb.length < 2) return a.equals(b);
        String sa = pa[pa.length - 2] + "." + pa[pa.length - 1];
        String sb = pb[pb.length - 2] + "." + pb[pb.length - 1];
        return sa.equals(sb);
    }

    /*
     * Return "listName: domain=X" (not just listName) so a log/UI
     * consumer can see EXACTLY which specific domain key matched, not
     * just which list it come from - needed to tell apart "genuine ad
     * domain block" from "over-broad walk-up match" (real question that
     * came up testing: is some SHORT/GENERIC domain like a bare TLD
     * incorrectly sit in the map, matching way more than intend).
     */
    private static String domainMapLookup(Map<String, String> map, String host) {
        String h = host;
        while (h != null) {
            String v = map.get(h);
            if (v != null) return v + ": domain=" + h;
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return null;
    }

    private static boolean domainSetContains(Set<String> set, String host) {
        String h = host;
        while (h != null) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return false;
    }

    public static void addSessionBypass(String host) {
        if (host != null) sSessionBypassHosts.add(host);
    }

    public static void recordBlock(String url, String listName) {
        sTotalBlockedAllTime++;
        sRecentEvents.add(0, new BlockEvent(System.currentTimeMillis(), url, listName));
        while (sRecentEvents.size() > MAX_RECENT_EVENTS) {
            sRecentEvents.remove(sRecentEvents.size() - 1);
        }
    }

    public static List<BlockEvent> recentEvents() {
        return new ArrayList<BlockEvent>(sRecentEvents);
    }

    public static long totalBlockedAllTime() {
        return sTotalBlockedAllTime;
    }

    // ---- reload from disk (SAFE context only: app start, after background update, after whitelist/toggle edit in UI) ----

    public static void reload(Context ctx) {
        long start = System.currentTimeMillis();
        CompiledData fresh = new CompiledData();
        fresh.globalEnabled = SettingsStore.getAdBlockGlobalEnabled(ctx);
        AdBlockStore store = new AdBlockStore(ctx);
        try {
            for (String h : store.listWhitelist()) {
                fresh.whitelistHosts.add(h);
            }
            for (AdBlockStore.FilterList list : store.listFilterLists()) {
                if (!list.enabled) continue;
                File f = AdBlockStore.getFilterFile(ctx, list.id);
                if (!f.exists()) continue;
                parseFileInto(f, list.name, fresh);
            }
        } finally {
            store.close();
        }
        sData = fresh; // single volatile flip, no half-updated state ever visible to a concurrent hook call
        long elapsed = System.currentTimeMillis() - start;
        String summary = "reload(): domains=" + fresh.blockDomains.size()
            + " thirdPartyDomains=" + fresh.blockDomainsThirdPartyOnly.size()
            + " exceptionDomains=" + fresh.exceptionDomains.size()
            + " patterns=" + fresh.blockPatterns.size() + "/" + MAX_TOTAL_PATTERN_RULES
            + " exceptionPatterns=" + fresh.exceptionPatterns.size()
            + " skippedForCap=" + fresh.patternRulesSkippedForCap
            + " whitelist=" + fresh.whitelistHosts.size()
            + " globalEnabled=" + fresh.globalEnabled
            + " elapsedMs=" + elapsed;
        Log.i("EvoAdBlock", summary);
        MainActivity.writeToSdCard("[INFO] [ADBLOCK] " + summary); // same physical uckitkatwebview.log file MainActivity/XposedInit own log already write to, reuse existing public helper rather than duplicate file-write logic a third time
    }

    // ---- parsing (Adblock Plus filter syntax, v1 scope - see plan: domain-anchor + plain pattern + exception + $third-party honored, cosmetic/scriptlet/procedural/HTML filters skipped, other $options ignored since WebResourceRequest exposes no resource-type to act on anyway) ----

    private static void parseFileInto(File file, String listName, CompiledData out) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, listName, out);
            }
        } catch (Throwable t) {
            // best effort - a partial list is still better than none
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void parseLine(String rawLine, String listName, CompiledData out) {
        String line = rawLine.trim();
        if (line.length() == 0) return;
        if (line.startsWith("!") || line.startsWith("[")) return; // comment line / "[Adblock Plus ...]" header
        final String originalTrimmedLine = line; // keep before any option-stripping/@@ mutation, for PatternRule diagnostics below

        boolean isException = false;
        if (line.startsWith("@@")) {
            isException = true;
            line = line.substring(2);
        }

        // cosmetic/scriptlet/procedural/HTML filter - skip entire, v1 scope only do network-level block
        if (line.contains("##") || line.contains("#@#") || line.contains("#$#") || line.contains("#%#")) {
            return;
        }

        boolean thirdPartyOnly = false;
        int dollar = line.lastIndexOf('$');
        if (dollar >= 0 && looksLikeOptionsTail(line.substring(dollar + 1))) {
            String options = line.substring(dollar + 1);
            if (containsOption(options, "third-party")) thirdPartyOnly = true;
            line = line.substring(0, dollar);
        }

        if (line.length() == 0) return;

        if (line.startsWith("||")) {
            String domain = extractDomainAnchor(line);
            if (domain == null || domain.length() == 0) return;
            /*
             * REAL BUG FOUND (2026-09-06, confirm against real
             * easylist.txt): extractDomainAnchor() above already
             * correctly parse OUT just the domain part of a rule like
             * "||googleapis.com/qmftp/$script" or
             * "||turtlecute.org/assets/partners/" (real line straight
             * from EasyList, both genuinely only mean block that ONE
             * narrow path) - but this method used to treat ANY
             * "||domain..." line as if it were a bare, unrestricted
             * "||domain^" block regardless what follow the domain,
             * completely discard the "/qmftp/"/"/assets/partners/" path
             * restriction. Result: googleapis.com (Google Fonts CSS
             * include) and turtlecute.org (a whole real site own JS/CSS/
             * icon, not ad at all) both get wipe out entire, confirm
             * real device test. Only take the fast domain-hashset path
             * now when NOTHING (or just a lone "^") follow the domain -
             * a genuine bare block. Anything else (a path, "*", or "^"
             * plus more after it) fall through to pattern compile
             * instead, using the FULL "domain+path" text so only that
             * narrow path really get block, domain hashset stay uncapped/
             * fast for the (large majority) rule that really are bare.
             */
            String afterDomain = line.substring(2 + domain.length());
            boolean isBareDomainBlock = afterDomain.length() == 0 || afterDomain.equals("^");
            if (isBareDomainBlock) {
                if (isException) {
                    out.exceptionDomains.add(domain);
                } else if (thirdPartyOnly) {
                    out.blockDomainsThirdPartyOnly.put(domain, listName);
                } else {
                    out.blockDomains.put(domain, listName);
                }
                return;
            }
            /*
             * Path/wildcard-scoped domain rule - compile domain+path
             * together as a plain substring pattern instead (strip only
             * the leading "||" here, not the single-leading-"|"
             * "absolute start of string" anchor compilePattern() already
             * handle for OTHER rule shape, different meaning - a domain
             * name in practice only ever appear at the host position of
             * a real URL string, so plain substring match on
             * "domain.com/path..." already correctly require the path
             * immediately follow the domain, without need build full
             * proper hostname-component regex machinery for this).
             */
            Pattern scoped = compilePattern(line.substring(2));
            if (scoped == null) return;
            if (out.blockPatterns.size() + out.exceptionPatterns.size() >= MAX_TOTAL_PATTERN_RULES) {
                out.patternRulesSkippedForCap++;
                return;
            }
            if (isException) {
                out.exceptionPatterns.add(scoped);
            } else {
                out.blockPatterns.add(new PatternRule(scoped, listName, originalTrimmedLine));
            }
            return;
        }

        Pattern p = compilePattern(line);
        if (p == null) return;
        /*
         * Hard cap on the SLOW linear-scan pattern lists (block+exception
         * combined, global across every enabled list, not per-list -
         * what matter is the TOTAL sequential Pattern.matcher().find()
         * count paid per single request). Domain-anchor rule above stay
         * completely uncapped (O(few) hashset walk regardless of how
         * many, cost never grow with count).
         *
         * REAL BUG FOUND (2026-09-06): before this cap, real EasyList
         * compile down to many thousand non-domain-anchor rule, and
         * EVERY single http(s) request - ad or NOT, since a
         * non-matching request still have to scan the WHOLE list to
         * conclude "no match" - paid the full sequential cost. This is
         * what actually made browsing painfully slow/near-unusable
         * ("doesn't work" report), not a hook/navigation bug at all.
         * Bound total count instead so worst-case per-request cost stay
         * tolerable, at the honest cost of NOT enforcing every single
         * possible EasyList rule (domain-anchor rule, the large
         * majority of real entries, still all enforce regardless of
         * this cap).
         */
        if (out.blockPatterns.size() + out.exceptionPatterns.size() >= MAX_TOTAL_PATTERN_RULES) {
            out.patternRulesSkippedForCap++;
            return;
        }
        if (isException) {
            out.exceptionPatterns.add(p);
        } else {
            out.blockPatterns.add(new PatternRule(p, listName, originalTrimmedLine));
        }
    }

    // crude but effective: a real $options tail only ever contain this char set, a literal "$" inside an actual url match part is very rarely followed by ONLY these
    private static boolean looksLikeOptionsTail(String s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '=' || c == ',' || c == '~' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsOption(String options, String name) {
        for (String part : options.split(",")) {
            if (part.trim().equals(name)) return true;
        }
        return false;
    }

    private static String extractDomainAnchor(String line) {
        String rest = line.substring(2); // strip leading "||"
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '^' || c == '/' || c == '*' || c == '$') {
                end = i;
                break;
            }
        }
        String domain = rest.substring(0, end).toLowerCase();
        if (domain.length() == 0 || domain.indexOf(' ') >= 0) return null;
        return domain;
    }

    // convert Adblock Plus wildcard/anchor syntax to a compiled regex - '*' wildcard, '^' separator placeholder, leading/trailing '|' anchor
    private static Pattern compilePattern(String abpLine) {
        try {
            String s = abpLine;
            boolean startAnchor = false, endAnchor = false;
            if (s.startsWith("|")) {
                startAnchor = true;
                s = s.substring(1);
            }
            if (s.endsWith("|") && !s.endsWith("||")) {
                endAnchor = true;
                s = s.substring(0, s.length() - 1);
            }

            StringBuilder regex = new StringBuilder();
            if (startAnchor) regex.append("^");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '^') {
                    regex.append("(?:[^a-zA-Z0-9_.%-]|$)");
                } else {
                    if ("\\.[]{}()<>*+-=!?^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
            if (endAnchor) regex.append("$");
            if (regex.length() == 0) return null;
            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    // ---- background download + update ----

    public interface UpdateCallback {
        void onListUpdated(long listId, boolean success, int ruleCount, String error);
        void onAllDone();
    }

    // generous cap well above real EasyList size (~2MB) - bound worst-case behavior against a misbehaving/compromised list URL, same spirit as the favicon size cap
    private static final long MAX_LIST_BYTES = 50L * 1024 * 1024;

    /*
     * Blocking network I/O - caller MUST already be on a background
     * Thread (initAsync()/updateAllNowAsync() below both do this
     * correctly, never call this direct from UI thread).
     */
    public static void updateListBlocking(Context ctx, AdBlockStore.FilterList list, UpdateCallback cb) {
        long start = System.currentTimeMillis();
        MainActivity.writeToSdCard("[INFO] [ADBLOCK] updateListBlocking: starting \"" + list.name + "\" -> " + list.url);
        String error = null;
        int ruleCount = 0;
        File tmpFile = null;
        try {
            URL url = new URL(list.url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; UcKitKatWebview-AdBlock-Updater)");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }

            File finalFile = AdBlockStore.getFilterFile(ctx, list.id);
            tmpFile = new File(finalFile.getParentFile(), "list_" + list.id + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmpFile);
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_LIST_BYTES) {
                    in.close();
                    fos.close();
                    throw new IOException("filter list too large (> " + (MAX_LIST_BYTES / (1024 * 1024)) + "MB), aborting");
                }
                fos.write(buf, 0, n);
            }
            in.close();
            fos.close();

            // quick throwaway parse pass just to get a rule count for the UI/log, real compiled data only build by reload() below
            CompiledData probe = new CompiledData();
            parseFileInto(tmpFile, list.name, probe);
            ruleCount = probe.blockDomains.size() + probe.blockDomainsThirdPartyOnly.size()
                + probe.exceptionDomains.size() + probe.blockPatterns.size() + probe.exceptionPatterns.size();

            if (finalFile.exists()) finalFile.delete();
            tmpFile.renameTo(finalFile);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (tmpFile != null && tmpFile.exists()) tmpFile.delete();
        }

        AdBlockStore store = new AdBlockStore(ctx);
        try {
            store.updateFilterListResult(list.id, System.currentTimeMillis(), ruleCount, error);
        } finally {
            store.close();
        }
        long elapsed = System.currentTimeMillis() - start;
        if (error != null) {
            MainActivity.writeToSdCard("[ERROR at ADBLOCK.updateListBlocking] \"" + list.name + "\" failed after " + elapsed + "ms: " + error);
        } else {
            MainActivity.writeToSdCard("[INFO] [ADBLOCK] updateListBlocking: \"" + list.name + "\" done, " + ruleCount + " rules, " + elapsed + "ms");
        }
        if (cb != null) cb.onListUpdated(list.id, error == null, ruleCount, error);
    }

    // check each enabled list's own last_updated against this, on app start only - a manual "Update now" always run regardless of staleness
    private static final long STALE_AFTER_MS = 24L * 60L * 60L * 1000L; // 24h

    /*
     * Call once at app start (safe UI-thread call site - spins its OWN
     * background Thread internal, never block the caller). Only
     * actually download whichever enabled list is stale or missing its
     * file, but reload() unconditionally at the end either way, so even
     * a "nothing stale" run still correctly pick up whatever is already
     * on disk from a previous session.
     */
    public static void initAsync(final Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        MainActivity.writeToSdCard("[INFO] [ADBLOCK] initAsync: scheduled");
        new Thread(new Runnable() {
            @Override
            public void run() {
                /*
                 * Small delay on purpose - this used to fire the SQLite
                 * open/possible network download right away inside
                 * MainActivity.onCreate(), competing for CPU/IO with
                 * whatever early-startup work already there (Xposed hook
                 * confirm/etc). Some user report loading_engine_fail_
                 * xposedinit starting around when this feature land -
                 * not proven this IS the cause (ask MainActivity own
                 * check for that message, do not have visibility into
                 * it), but push the heavier ad-block work a couple
                 * second later is a safe, cheap precaution regardless -
                 * ad-block do not need to be ready in the very first
                 * moment of app launch anyway.
                 */
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                AdBlockStore store = new AdBlockStore(appCtx);
                List<AdBlockStore.FilterList> lists;
                try {
                    lists = store.listFilterLists();
                } finally {
                    store.close();
                }
                long now = System.currentTimeMillis();
                for (AdBlockStore.FilterList list : lists) {
                    if (!list.enabled) continue;
                    boolean stale = list.lastUpdated == 0 || (now - list.lastUpdated) > STALE_AFTER_MS;
                    boolean missingFile = !AdBlockStore.getFilterFile(appCtx, list.id).exists();
                    if (stale || missingFile) {
                        updateListBlocking(appCtx, list, null);
                    }
                }
                reload(appCtx);
            }
        }, "AdBlockInit").start();
    }

    /** "Update now" button - always refresh every enabled list regardless of staleness. */
    public static void updateAllNowAsync(final Context ctx, final UpdateCallback cb) {
        final Context appCtx = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdBlockStore store = new AdBlockStore(appCtx);
                List<AdBlockStore.FilterList> lists;
                try {
                    lists = store.listFilterLists();
                } finally {
                    store.close();
                }
                for (AdBlockStore.FilterList list : lists) {
                    if (!list.enabled) continue;
                    updateListBlocking(appCtx, list, cb);
                }
                reload(appCtx);
                if (cb != null) cb.onAllDone();
            }
        }, "AdBlockUpdateAll").start();
    }
}