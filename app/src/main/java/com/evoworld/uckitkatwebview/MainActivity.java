package com.evoworld.uckitkatwebview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.DownloadManager;
import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import dalvik.system.DexClassLoader;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import android.app.ProgressDialog;

public class MainActivity extends Activity {
    private static final String TAG = "UcKitKatWebview";
    /*
     * Was "https://www.google.com" before. Now point to own internal
     * chrome://newtab page (see XposedInit.buildInternalPageResponse()),
     * only fall back to this when SettingsStore.getDefaultUrl() empty
     * AND "load last url" off, see resolveInitialUrl()/openNewTab().
     */
    private static final String DEFAULT_TARGET_URL = "chrome://newtab";
    private static final boolean IS_INTERNAL_WIFI_DEBUG = true;

    private static final int GESTURE_THRESHOLD_DP = 24;
    private static final int REQUEST_CODE_SETTINGS = 1001;
    private static final int REQUEST_CODE_FILE_CHOOSER = 1002;
    private static final int REQUEST_CODE_RUNTIME_PERMISSIONS = 1003;

    /*
     * Set by onShowFileChooserRequested() when input type file upload
     * picker on screen. Use by onActivityResult() or
     * handleFileChooserResult(), only one can handle at time, should be
     * like real browser.
     */
    private ValueCallback<Uri[]> mPendingFileChooserCallback;

    /*
     * Set by handlePermissionRequest() while wait runtime camera/mic
     * permission prompt (API23+), use in onRequestPermissionsResult().
     * Hold the com.uc.webview.export.PermissionRequest object until we
     * know grant or deny.
     */
    private Object mPendingPermissionRequest;

    // engine own built-in UA string
    private volatile String mDefaultUserAgent;

    // "can zoom", not save, by default on
    private boolean mZoomEnabled = true;

    // time of last real ACTION_DOWN, use by shouldSuppressAutoKeyboard()
    private volatile long mLastGenuineTouchTimeMs = 0;
    private static final long KEYBOARD_TOUCH_GRACE_MS = 600;

    // tab manager
    private final TabManager mTabManager = new TabManager();

    /*
     * Fill once by initUcEngineOnce() and reuse by every next
     * createTabWebView() call, this is whole point of engine-init/per-tab
     * split. mEngineInitialized stop the expensive one-time bootstrap
     * (classloader, Instrumentation.newApplication(), aerieApp.onCreate())
     * from run again for second tab.
     */
    private ClassLoader mUcClassLoader;
    private Class<?> mExportWebViewClass;
    private volatile boolean mEngineInitialized = false;
    /*
     * Set only inside BOOT_CRITICAL_FAILURE catch below, different from
     * mEngineInitialized staying false which also mean init still in
     * progress. This one mean "already try and not coming up", see
     * initUcEngineOnce() guard that stop retry after this become true.
     */
    private volatile boolean mEngineInitFailed = false;

    private FrameLayout mTabSwitcherContainerRef;
    private View mTabSwitcherPanel; // overlay panel self, null until first show
    private BaseAdapter mTabSwitcherAdapter; // read mTabManager.tabsForMode() live, see buildTabSwitcherPanel()
    private Button mModeBtnStandard;
    private Button mModeBtnIncognito;
    /*
     * Which of two nav_screen mode_switcher row the grid show now, false
     * mean Standard tab, true mean Incognito tab. Reset to false every
     * time panel rebuild (app restart), on purpose, never open into
     * incognito list by default.
     */
    private boolean mTabSwitcherShowingIncognito = false;

    /*
     * Host that user already tap "Proceed anyway" on chrome://error
     * interstitial for, THIS APP RUN only (not save to disk, on purpose -
     * same reason like incognito, a "trust" that survive restart quiet
     * become permanent without ask again, not want that). Two job: (1)
     * onSslErrorReceived() consult this FIRST, skip interstitial and
     * auto-proceed() right away when host already here (this what really
     * "resume" the page after user accept warning once - see
     * onErrorPageProceedClicked() for where host get add + retry
     * navigate); (2) updateSecurityBadge() consult same set, show warning
     * icon instead of lock for any https page whose host in here, and
     * this correct even for back/forward into WebView OWN cache (see
     * that method note), since lookup key by host not depend on hook
     * firing again.
     */
    private final java.util.Set<String> mSessionTrustedSslHosts = new java.util.HashSet<String>();

    private BrowserDatabase mDb;
    private View mBookmarksPanel;
    private View mLoadingOverlay;
	private ProgressDialog mLoadingDialog;
    private boolean mBookmarksViewOpenInNewTab; // which entry point open the panel currently show

    /*
     * Link one AwContentsClientBridge to the export.WebView Java object
     * that own it. Check by smali (2026-09-03): AwContentsClientBridge
     * hold private AwContents field, so each bridge really is 1:1 with
     * one WebView native side, but no confirm back-reference from there
     * to Java export.WebView wrapper without deeper UC internal class we
     * not have. Work around instead: WebView construct always sync and
     * single-thread here (see createTabWebView()), so whatever bridge
     * XposedInit constructor hook report WHILE we inside
     * ctor.newInstance() must belong to tab currently building.
     */
    private final List<Object> mBridgesSeenDuringCurrentConstruction = new ArrayList<Object>();
    private final Map<Object, Object> mBridgeToWebView = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Object>());

    private static File sSdLogFile;
    private View mWebView;
    private FrameLayout mWebFrame;
    private EditText mUrlInput;
    private Handler mMainHandler;

    private String mCurrentUrl;

    // AOSP UI Elements
    private ImageView mFaviconView;
    private ImageView mLockView;
    private ImageView mBtnClear;
    private ImageView mBtnStopRefresh;
    private TextView mTabCountText;
    private View mFindBar;
    private View mFindBarDivider;
    private EditText mFindQueryInput;
    private TextView mFindMatchCountText;
    private ProgressBar mProgressBar;

    // Bottom Bar Elements
    private LinearLayout mBottomBar;
    private ImageView mBtnBack;
    private ImageView mBtnForward;
    private ImageView mBtnBookmark;
    private boolean mIsBookmarked = false;
    private boolean mBottomBarVisible = true;
    private int mBottomBarHeightPx;
    private boolean mBottomBarForceHidden = false;

    private boolean mIsLoading = false;

    // Gesture tracking
    private float mGestureLastRawY = -1f;
    private float mGestureAccumPx = 0f;

    /*
     * write log line to sdcard file, so can check after app crash or
     * close (logcat not always available on device we test). append
     * mode, sync after every write so line not lost if app die right
     * after. silent fail on purpose, log write must never crash app.
     */
    public static synchronized void writeToSdCard(String msg) {
        try {
            if (sSdLogFile == null) {
                File sdcard = Environment.getExternalStorageDirectory();
                sSdLogFile = new File(sdcard, "uckitkatwebview.log");
            }
            FileOutputStream fos = new FileOutputStream(sSdLogFile, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write("[tid=" + android.os.Process.myTid() + "] " + msg + "\n");
            osw.flush();
            fos.getFD().sync();
            osw.close();
            fos.close();
        } catch (Throwable ignored) {}
    }

    // normal info log, go to logcat and also sdcard file same time
    private void logLocal(String msg) {
        Log.i(TAG, msg);
        writeToSdCard("[INFO] " + msg);
    }

    // same idea, but for exception. unwrap InvocationTargetException first, real cause is more useful than reflection wrapper
    private void logException(String step, Throwable t) {
        if (t instanceof InvocationTargetException && ((InvocationTargetException) t).getTargetException() != null) {
            t = ((InvocationTargetException) t).getTargetException();
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String trace = sw.toString();
        Log.e(TAG, step + ":\n" + trace);
        writeToSdCard("[ERROR at " + step + "]:\n" + trace);
    }

    // dump every thread stack to log file, use when crash happen and need see what other thread doing that time
    private void dumpAllActiveThreads() {
        try {
            StringBuilder sb = new StringBuilder("\n=== ACTIVE THREADS DUMP ===\n");
            Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
                Thread t = entry.getKey();
                sb.append("Thread: ").append(t.getName()).append(" [State: ").append(t.getState()).append("]\n");
                for (StackTraceElement el : entry.getValue()) {
                    sb.append("    at ").append(el.toString()).append("\n");
                }
            }
            writeToSdCard(sb.toString());
        } catch (Throwable ignored) {}
    }

    // read tombstone and anr trace file with su, for native crash that java side can not catch normal way
    private void checkAndDumpTombstones() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("ls -lt /data/tombstones/\n");
            os.writeBytes("cat /data/tombstones/tombstone_0* 2>/dev/null | tail -n 120\n");
            os.writeBytes("echo ---ANR-TRACES---\n");
            os.writeBytes("tail -n 100 /data/anr/traces.txt 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            boolean hasTomb = false;
            while ((line = br.readLine()) != null) {
                if (!hasTomb) {
                    writeToSdCard("\n=== SYSTEM / KERNEL CRASH DUMP ===");
                    hasTomb = true;
                }
                writeToSdCard("[CRASH_DUMP] " + line);
            }
            p.waitFor();
        } catch (Throwable ignored) {}
    }

    // catch any crash that normal try/catch miss, write full dump before app really die
    private void installUniversalSafetyWrapper() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                writeToSdCard("\n[GLOBAL_WRAPPER CAUGHT CRASH on " + thread.getName() + "]:\n" + sw.toString());
                Log.e(TAG, "CRASH: " + sw.toString());
                dumpAllActiveThreads();
                checkAndDumpTombstones();
            }
        });
    }

    private void runSuCommandSync(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            process.waitFor();
        } catch (Throwable t) {
            logException("runSuCommandSync: " + cmd, t);
        }
    }

    private void enableAdbOverTcpSync() {
        logLocal("Configuring root network ADB on port 5555...");
        runSuCommandSync("setprop service.adb.tcp.port 5555\nstop adbd\nstart adbd");
        logLocal("ADB port 5555 active.");
    }

    private File synchronizeU4SdkStructure(ApplicationInfo ucInfo) {
        File appDir = new File(getApplicationInfo().dataDir);
        File targetU4Dir = new File(appDir, "app_u4sdk");
        File nativeLibDir = getDir("native_libs", Context.MODE_PRIVATE);
        File paksDir = new File(appDir, "paks");
        if (!paksDir.exists()) paksDir.mkdirs();

        logLocal("Synchronizing complete U4 SDK, Native Libraries, and PAK files...");

        String syncCmd =
            "mkdir -p " + nativeLibDir.getAbsolutePath() + ";\n" +
            "cp -f /data/data/com.UCMobile/lib/*.so " + nativeLibDir.getAbsolutePath() + "/ 2>/dev/null;\n" +
            "cp -f /data/app-lib/com.UCMobile-1/*.so " + nativeLibDir.getAbsolutePath() + "/ 2>/dev/null;\n" +
            "find /data/data/com.UCMobile/app_u4sdk/ -name \"*.so\" -exec cp -f {} " + nativeLibDir.getAbsolutePath() + "/ \\; 2>/dev/null;\n" +
            "find /data/data/com.UCMobile/apollo1/ -name \"*.so\" -exec cp -f {} " + nativeLibDir.getAbsolutePath() + "/ \\; 2>/dev/null;\n" +
            "chmod -R 777 " + nativeLibDir.getAbsolutePath() + ";\n" +

            "mkdir -p " + targetU4Dir.getAbsolutePath() + ";\n" +
            "cp -rf /data/data/com.UCMobile/app_u4sdk/* " + targetU4Dir.getAbsolutePath() + "/ 2>/dev/null;\n" +
            "find /data/data/com.UCMobile/ -name \"*.pak\" -exec cp -f {} " + paksDir.getAbsolutePath() + "/ \\; 2>/dev/null;\n" +
            "find /data/data/com.UCMobile/ -name \"*.dat\" -exec cp -f {} " + paksDir.getAbsolutePath() + "/ \\; 2>/dev/null;\n" +
            "chmod -R 777 " + targetU4Dir.getAbsolutePath() + ";\n" +
            "chmod -R 777 " + paksDir.getAbsolutePath() + ";\n" +

            "mkdir -p " + appDir.getAbsolutePath() + "/apollo1;\n" +
            "cp -rf /data/data/com.UCMobile/apollo1/* " + appDir.getAbsolutePath() + "/apollo1/ 2>/dev/null;\n" +
            "chmod -R 777 " + appDir.getAbsolutePath() + "/apollo1;\n";

        runSuCommandSync(syncCmd);

        File jsiFile = new File(nativeLibDir, "libjsi.so");
        File ucSoFile = new File(nativeLibDir, "libwebviewuc.so");
        if (jsiFile.exists() && ucSoFile.exists()) {
            File jsiDir = new File(getApplicationInfo().dataDir, "app_jsi");
            if (!jsiDir.exists()) jsiDir.mkdirs();
            File soPathsFile = new File(jsiDir, "sopaths");
            String content = jsiFile.getAbsolutePath() + "`" + ucSoFile.getAbsolutePath() + "`" + jsiFile.length();
            try {
                FileOutputStream fos = new FileOutputStream(soPathsFile, false);
                fos.write(content.getBytes());
                fos.flush();
                fos.getFD().sync();
                fos.close();
            } catch (Throwable t) {
                logException("write sopaths", t);
            }
        }

        return nativeLibDir;
    }

    private ClassLoader createFullUcClassLoader(ApplicationInfo ucInfo, File nativeLibDir) throws Exception {
        File optDir = getDir("uc_opt_dex", Context.MODE_PRIVATE);
        File dexDir = getDir("uc_dex_files", Context.MODE_PRIVATE);

        List<String> dexPathList = new ArrayList<String>();
        dexPathList.add(ucInfo.sourceDir);

        logLocal("Scanning APK for secondary DEX files: " + ucInfo.sourceDir);
        ZipFile zip = new ZipFile(ucInfo.sourceDir);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("classes") && name.endsWith(".dex")) {
                File target = new File(dexDir, name);
                if (!target.exists() || target.length() != entry.getSize()) {
                    InputStream in = zip.getInputStream(entry);
                    FileOutputStream out = new FileOutputStream(target);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.close();
                    in.close();
                }
                if (!name.equals("classes.dex")) {
                    dexPathList.add(target.getAbsolutePath());
                }
            }
        }
        zip.close();
        logLocal("Total DEX files loaded into MultiDex list: " + dexPathList.size());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dexPathList.size(); i++) {
            sb.append(dexPathList.get(i));
            if (i < dexPathList.size() - 1) {
                sb.append(File.pathSeparator);
            }
        }

        return new DexClassLoader(
            sb.toString(),
            optDir.getAbsolutePath(),
            nativeLibDir.getAbsolutePath(),
            getClassLoader()
        );
    }

    public void applyCorePatches(ClassLoader cl, Context ctx) {
        logLocal("applyCorePatches() hook bridge reached.");
    }

    /*
     * Set only from XposedInit beforeHookedMethod on applyCorePatches()
     * above, before it call patchCoreFramework(). So this only become true
     * if Xposed hook really fire for this process. Reach
     * handleLoadPackage() at all (see XposedInit own comment on load
     * order, it run before this Activity onCreate()) already need real
     * active Xposed/LSPosed framework on device AND this module enable
     * for this app, so this flag alone can tell "hook fired" from "hook
     * never fired". classifyEngineFailure() below split the "never fired"
     * case more, into "no framework at all" vs "framework active but
     * module not enable", using separate check (XposedBridge
     * classloadability).
     */
    private volatile boolean mXposedModuleActive = false;

    public void markXposedModuleActive() {
        mXposedModuleActive = true;
        logLocal("markXposedModuleActive: Xposed hook fired for this process - module confirmed active.");
    }

    private void copyDirRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirRecursive(child, new File(dst, child.getName()));
                }
            }
        } else {
            if (dst.exists() && dst.length() == src.length()) return;
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();
            in.close();
        }
    }

    private void invokeSetter(Object target, String methodName, boolean value) throws Exception {
        Method m;
        try {
            m = target.getClass().getMethod(methodName, boolean.class);
        } catch (NoSuchMethodException nsme) {
            m = target.getClass().getDeclaredMethod(methodName, boolean.class);
            m.setAccessible(true);
        }
        m.invoke(target, value);
    }

    /*
     * Same like invokeSetter() above but for int-arg setter (example
     * WebSettings.setCacheMode(int), confirm present by smali). Use by
     * createTabWebView() incognito part.
     */
    private void invokeIntSetter(Object target, String methodName, int value) throws Exception {
        Method m;
        try {
            m = target.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            m = target.getClass().getDeclaredMethod(methodName, int.class);
            m.setAccessible(true);
        }
        m.invoke(target, value);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void navigateTo(String rawUrl) {
        if (mWebView == null) {
            logLocal("navigateTo: no WebView loaded yet.");
            return;
        }
        String url = rawUrl.trim();
        /*
         * chrome:// own internal page (newtab, error) is already a
         * complete, final URL same tier as http(s), must skip whole
         * block below same as those - otherwise "no http(s) prefix,
         * no dot in it" case at bottom turn it into literal google
         * search for the text "chrome://newtab", real bug catch right
         * before this go to user for test.
         */
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("chrome://")) {
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = "https://www.google.com/search?q=" + Uri.encode(url);
            }
        }
        if (mUrlInput != null) {
            mUrlInput.setText(url);
        }
        resetFaviconToPlaceholder();
        applyResolvedUserAgent(url);
        mCurrentUrl = url;
        TabManager.Tab activeTab = mTabManager.activeTab();
        /*
         * Incognito: never let this navigate update "last URL" (which
         * resolveInitialUrl() can reopen app to) or go into
         * BrowserDatabase history table. Bookmark/star-sync still work
         * normal, only the passive automatic record part is skip.
         */
        boolean incognito = activeTab != null && activeTab.incognito;
        if (!incognito) {
            SettingsStore.setLastUrl(this, url);
            /*
             * chrome:// own page (newtab, error interstitial) never go
             * into BrowserDatabase history table - real browser not
             * clutter history with internal page, and chrome://error?...
             * carry ugly encoded query string that would look bad in
             * the list anyway. setLastUrl() above still run for it on
             * purpose though, "load last page" restore to newtab correct
             * if that really where you left off, different concern than
             * the visible History list.
             */
            if (!url.startsWith("chrome://")) {
                recordHistoryVisit(url);
            }
        }
        syncBookmarkStarIcon(url);
        if (activeTab != null) {
            activeTab.url = url;
        }
        saveOpenTabsToDisk();
        applyBottomBarVisibilityForUrl(url);
        updateSecurityBadge(url);
        try {
            Method loadUrl;
            try {
                loadUrl = mWebView.getClass().getMethod("loadUrl", String.class);
            } catch (NoSuchMethodException nsme) {
                loadUrl = mWebView.getClass().getDeclaredMethod("loadUrl", String.class);
                loadUrl.setAccessible(true);
            }
            loadUrl.invoke(mWebView, url);
        } catch (Throwable e) {
            logException("navigateTo.loadUrl", e);
        }
    }

    /*
     * Call from iconcombo tap listener (bindTopBarViews()) above.
     * getCertificate() confirm by smali as plain public method on
     * export.WebView, forward straight to internal engine, return stock
     * android.net.http.SslCertificate (not UC-wrap type) - so only the
     * WebView.getCertificate() call self need reflection (UC-internal
     * class), everything AFTER that is normal direct SDK class use, no
     * more reflect need.
     *
     * No TLS protocol version show here on purpose - confirm by grep
     * whole export.WebView.smali (Ssl/Cert/Security/Tls name), no method
     * anywhere expose that, neither android.webkit.WebView/SslCertificate
     * own public API ever did on any Android version, not a UC-only gap.
     */
    private void showConnectionInfoDialog() {
        if (mWebView == null) return;
        String url = mCurrentUrl;
        boolean isHttps = url != null && url.startsWith("https://");

        if (!isHttps) {
            new AlertDialog.Builder(this)
                .setTitle(getString(R.string.connection_not_secure_title))
                .setMessage(getString(R.string.connection_not_secure_message))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        android.net.http.SslCertificate cert = null;
        try {
            Method getCert = mWebView.getClass().getMethod("getCertificate");
            Object certObj = getCert.invoke(mWebView);
            if (certObj instanceof android.net.http.SslCertificate) {
                cert = (android.net.http.SslCertificate) certObj;
            }
        } catch (Throwable t) {
            logException("showConnectionInfoDialog.getCertificate", t);
        }

        if (cert == null) {
            new AlertDialog.Builder(this)
                .setTitle(getString(R.string.connection_secure_title))
                .setMessage(getString(R.string.connection_no_cert_details))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        String host = null;
        try {
            host = Uri.parse(url).getHost();
        } catch (Throwable ignored) {}
        boolean sessionWarning = host != null && mSessionTrustedSslHosts.contains(host);

        android.net.http.SslCertificate.DName issuedTo = cert.getIssuedTo();
        android.net.http.SslCertificate.DName issuedBy = cert.getIssuedBy();

        StringBuilder msg = new StringBuilder();
        if (sessionWarning) {
            msg.append(getString(R.string.connection_warning_accepted)).append("\n\n");
        }
        msg.append(getString(R.string.connection_issued_to, issuedTo != null ? issuedTo.getCName() : "?")).append("\n");
        msg.append(getString(R.string.connection_issued_by, issuedBy != null ? issuedBy.getCName() : "?")).append("\n");
        /*
         * getValidNotBeforeString()/getValidNotAfterString() compile
         * error on modern compileSdkVersion (Google strip these out of
         * the compile-time stub android.jar on newer API level, method
         * still real exist on device own SslCertificate class though,
         * javac just can not see it in the stub it build against) - so
         * reflect same as every UC-internal class elsewhere in this
         * project, not direct call.
         *
         * TEST CONFIRM (2026-09-05 log): getValidNotAfterString() throw
         * NoSuchMethodException on real device, even reflect - this
         * particular build own SslCertificate really not have that exact
         * name (getValidNotBeforeString() apparently fine, no matching
         * error report for it). reflectCertDateString() below try the
         * "String" name FIRST, fall back to plain getValidNotBefore()/
         * getValidNotAfter() (return java.util.Date, format ourself) if
         * that fail - cover both API shape without need know for sure
         * which this build really have.
         */
        String validFrom = reflectCertDateString(cert, "getValidNotBeforeString", "getValidNotBefore");
        String validTo = reflectCertDateString(cert, "getValidNotAfterString", "getValidNotAfter");
        msg.append(getString(R.string.connection_valid_range, validFrom, validTo));

        final android.net.http.SslCertificate finalCert = cert;
        new AlertDialog.Builder(this)
            .setTitle(sessionWarning ? getString(R.string.connection_warning_title) : getString(R.string.connection_secure_title))
            .setMessage(msg.toString())
            .setPositiveButton(getString(R.string.connection_view_certificate), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showFullCertificateView(finalCert);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /*
     * android.net.http.SslCertificate.inflateCertificateView(Context) is
     * plain public method (not @hide) ON THE DEVICE, same one AOSP
     * Browser own "view certificate" dialog use historical, return real
     * system-style cert detail View, drop straight into own AlertDialog -
     * the "real android dialog" ask for. Modern compileSdkVersion strip
     * it from the compile-time stub android.jar though (confirm by build
     * error), so reflect same as inflateCertificateView being just
     * another "exist at runtime, not in the stub we compile against"
     * case, not a UC-internal class this time but same fix either way.
     */
    /*
     * Try the "String" named method first (return already-format
     * String), fall back to plain-name method (return java.util.Date,
     * format ourself SimpleDateFormat) if first not exist on this build
     * own SslCertificate class - see showConnectionInfoDialog() note on
     * why both need cover. "?" if truly neither exist.
     */
    private static String reflectCertDateString(android.net.http.SslCertificate cert, String stringMethodName, String dateMethodName) {
        try {
            Method m = cert.getClass().getMethod(stringMethodName);
            Object r = m.invoke(cert);
            if (r != null) return r.toString();
        } catch (Throwable ignored) {}
        try {
            Method m = cert.getClass().getMethod(dateMethodName);
            Object r = m.invoke(cert);
            if (r instanceof java.util.Date) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format((java.util.Date) r);
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    private void showFullCertificateView(android.net.http.SslCertificate cert) {
        try {
            Method inflate = cert.getClass().getMethod("inflateCertificateView", Context.class);
            Object viewObj = inflate.invoke(cert, this);
            if (!(viewObj instanceof View)) {
                throw new IllegalStateException("inflateCertificateView did not return a View: " + viewObj);
            }
            View certView = (View) viewObj;
            new AlertDialog.Builder(this)
                .setTitle(getString(R.string.connection_view_certificate))
                .setView(certView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Throwable t) {
            logException("showFullCertificateView", t);
            Toast.makeText(this, R.string.connection_certificate_view_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSecurityBadge(String url) {
        if (mLockView == null || mFaviconView == null) return;
        // always keep favicon show
        mFaviconView.setVisibility(View.VISIBLE);
        // show lock badge for HTTPS, hide for HTTP/chrome:// etc
        if (url != null && url.startsWith("https://")) {
            mLockView.setVisibility(View.VISIBLE);
            /*
             * Warning triangle instead of lock when this host already
             * user-accept a cert warning (see mSessionTrustedSslHosts
             * note on field). Host-key lookup, not "did hook just fire",
             * so this stay correct even switch/back-forward into a
             * cache page that never re-trigger onReceivedSslError.
             * android.R.drawable.ic_dialog_alert use as placeholder
             * warning icon (always present, no new drawable resource
             * need), swap for own asset anytime.
             */
            String host = null;
            try {
                host = Uri.parse(url).getHost();
            } catch (Throwable ignored) {}
            if (host != null && mSessionTrustedSslHosts.contains(host)) {
                mLockView.setImageResource(android.R.drawable.ic_dialog_alert);
            } else {
                // confirm real name by browser_title_bar.xml (src="@drawable/ic_secure_holo_dark" baked in there)
                mLockView.setImageResource(R.drawable.ic_secure_holo_dark);
            }
        } else {
            mLockView.setVisibility(View.GONE);
        }
    }

    /*
     * Confirm by smali (r5.smali, 2026-09-05): this engine real mobile UA
     * template is "Mozilla/5.0 (Linux; U; Android %s) AppleWebKit/537.36
     * (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UWS/%s Mobile
     * Safari/537.36", this confirm engine really is Chrome/100.0.4896.58
     * under. Use exact same version number for desktop UA too (not
     * different real desktop Chrome 100 build), so mobile/desktop stay
     * consistent for same tab, in case site fingerprint check both.
     */
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.58 Safari/537.36";

    private void applyResolvedUserAgent(String url) {
        if (mWebView == null) return;
        TabManager.Tab activeTab = mTabManager.activeTab();
        if (activeTab != null && activeTab.desktopSiteEnabled) {
            setUserAgentStringOnActiveWebView(DESKTOP_USER_AGENT);
            logLocal("applyResolvedUserAgent: desktop site override active for " + url);
            return;
        }
        String ua = SettingsStore.resolveUserAgent(this, url);
        /*
         * Before code was "if (ua == null) return;" here, mean site with no
         * match rule and no global UA just quietly keep whatever UA a
         * PREVIOUS site rule set, because setUserAgentString() not reset
         * self on navigate. Now we always set something active: the
         * resolve override, or explicit back to engine real default UA
         * that capture at WebView create time.
         */
        String effectiveUa = ua != null ? ua : mDefaultUserAgent;
        if (effectiveUa == null) return; // default not capture somehow, leave engine own UA alone
        setUserAgentStringOnActiveWebView(effectiveUa);
        logLocal("applyResolvedUserAgent: " + (ua != null ? "set rule UA" : "reset to default UA") + " for " + url + " -> " + effectiveUa);
    }

    private void setUserAgentStringOnActiveWebView(String effectiveUa) {
        try {
            Method getSettings = mWebView.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(mWebView);
            if (settings == null) return;
            Method setUa;
            try {
                setUa = settings.getClass().getMethod("setUserAgentString", String.class);
            } catch (NoSuchMethodException nsme) {
                setUa = settings.getClass().getDeclaredMethod("setUserAgentString", String.class);
                setUa.setAccessible(true);
            }
            setUa.invoke(settings, effectiveUa);
        } catch (Throwable e) {
            logException("applyResolvedUserAgent", e);
        }
    }

    private String resolveInitialUrl() {
        if (SettingsStore.getLoadLastUrl(this)) {
            String last = SettingsStore.getLastUrl(this);
            if (!TextUtils.isEmpty(last)) return last;
        }
        String def = SettingsStore.getDefaultUrl(this);
        if (!TextUtils.isEmpty(def)) return def;
        return DEFAULT_TARGET_URL;
    }

    /*
     * What bindTopBarViews() prefill address bar with, before engine even
     * start (bootExportWebView() below only run once initUcEngineOnce()
     * async init callback fire). This must predict exact what
     * bootExportWebView() about to really load, or address bar visible
     * jump from this text to different one when navigate really start,
     * this is bug this thing fix.
     *
     * Reason they can disagree: SettingsStore.getLastUrl() is single
     * GLOBAL value, update every navigateTo() on ANY tab. Save-session
     * tab list (SettingsStore.loadOpenTabUrls() + getActiveTabIndex()) is
     * separate, PER-TAB state. With more than one tab open, "URL I last
     * navigate anywhere" and "URL of tab that really on screen when app
     * close" not guarantee same tab at all (example: navigate tab A, then
     * switch tab B without navigate, then close app, last url is A but
     * tab B is what get restore and show). resolveInitialUrl() only ever
     * check the first one, so restoreSavedTabs() switchToTab(activeTab),
     * which set address bar from ACTUAL restore tab own store .url, would
     * quietly override it moment later.
     *
     * Fix by predict from SAME source bootExportWebView() about to read:
     * if save tab list exist, this just plain read of it (same as
     * restoreSavedTabs() will do), not separate
     * "last url" lookup, so nothing left to disagree with. Only when no
     * save session at all (bootExportWebView()
     * openNewTab(resolveInitialUrl()) part, see own note there) this fall
     * back to resolveInitialUrl(), match what that part will really
     * navigate to.
     */
    private String peekInitialAddressBarUrl() {
        List<String> savedUrls = SettingsStore.loadOpenTabUrls(this);
        if (!savedUrls.isEmpty()) {
            int idx = SettingsStore.getActiveTabIndex(this);
            if (idx < 0 || idx >= savedUrls.size()) idx = 0;
            String url = savedUrls.get(idx);
            if (!TextUtils.isEmpty(url)) return url;
        }
        return resolveInitialUrl();
    }

    private Object invokeNoArg(String methodName) {
        if (mWebView == null) return null;
        try {
            Method m;
            try {
                m = mWebView.getClass().getMethod(methodName);
            } catch (NoSuchMethodException nsme) {
                m = mWebView.getClass().getDeclaredMethod(methodName);
                m.setAccessible(true);
            }
            return m.invoke(mWebView);
        } catch (Throwable e) {
            logException("invokeNoArg." + methodName, e);
            return null;
        }
    }

    private Method findNoArgMethod(Class<?> clz, String name) {
        try {
            return clz.getMethod(name);
        } catch (NoSuchMethodException nsme) {
            try {
                Method m = clz.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private Method findIntArgMethod(Class<?> clz, String name) {
        try {
            return clz.getMethod(name, int.class);
        } catch (NoSuchMethodException nsme) {
            try {
                Method m = clz.getDeclaredMethod(name, int.class);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private String peekHistoryUrl(int offset) {
        if (mWebView == null) return null;
        try {
            Method copyList = findNoArgMethod(mWebView.getClass(), "copyBackForwardList");
            if (copyList == null) return null;
            Object list = copyList.invoke(mWebView);
            if (list == null) return null;

            Method getCurrentIndex = findNoArgMethod(list.getClass(), "getCurrentIndex");
            Method getSize = findNoArgMethod(list.getClass(), "getSize");
            Method getItemAtIndex = findIntArgMethod(list.getClass(), "getItemAtIndex");
            if (getCurrentIndex == null || getSize == null || getItemAtIndex == null) {
                logLocal("peekHistoryUrl: copyBackForwardList() result missing expected methods on " + list.getClass().getName());
                return null;
            }

            int currentIndex = (Integer) getCurrentIndex.invoke(list);
            int size = (Integer) getSize.invoke(list);
            int targetIndex = currentIndex + offset;
            if (targetIndex < 0 || targetIndex >= size) return null;

            Object item = getItemAtIndex.invoke(list, targetIndex);
            if (item == null) return null;

            Method getUrl = findNoArgMethod(item.getClass(), "getUrl");
            if (getUrl == null) {
                logLocal("peekHistoryUrl: history item class " + item.getClass().getName() + " has no getUrl()");
                return null;
            }
            Object url = getUrl.invoke(item);
            return (url instanceof String) ? (String) url : null;
        } catch (Throwable e) {
            logException("peekHistoryUrl(offset=" + offset + ")", e);
            return null;
        }
    }

    private void navigateHistory(int offset, String navMethodName) {
        resetFaviconToPlaceholder();
        String peeked = peekHistoryUrl(offset);
        if (peeked != null && mUrlInput != null && !mUrlInput.hasFocus()) {
            logLocal("navigateHistory: pre-setting address bar from history list -> " + peeked);
            mUrlInput.setText(peeked);
        } else if (peeked == null) {
            logLocal("navigateHistory: could not peek target URL for offset=" + offset + "; bar will only update if a nav hook fires.");
        }
        if (peeked != null) {
            mCurrentUrl = peeked;
            SettingsStore.setLastUrl(this, peeked);
            applyBottomBarVisibilityForUrl(peeked);
            updateSecurityBadge(peeked);
            syncBookmarkStarIcon(peeked);
            /*
             * goBack()/goForward() not go through shouldOverrideUrlLoading (that
             * hook only fire for page/link navigate, not history navigate), so
             * this is only chance to resolve UA correct for back/forward.
             */
            applyResolvedUserAgent(peeked);
        }
        invokeNoArg(navMethodName);
    }

    /*
     * Call (via reflection) from XposedInit shouldOverrideUrlLoading hook,
     * sync, on WebView own UI-thread callback, before this page/link
     * navigate request really go out. On purpose not post to
     * mMainHandler like most other host callback in this file: this one
     * must run inline so UA is ready in time for THIS navigate, not just
     * next one. Only handle UA, address bar/current URL bookkeeping
     * already cover separate by onRealNavigationUrlUpdate.
     */
    public void onWillNavigateTo(String url) {
        applyResolvedUserAgent(url);
    }

    /*
     * Call (via reflection) from XposedInit shouldOverrideUrlLoading
     * hook, only for the special "chrome://error/proceed?..." sentinel
     * link tap inside our own interstitial page (see
     * XposedInit.buildErrorPageHtml() proceed link, and
     * onSslErrorReceived() below for the other half of this loop).
     * Not a real page, just command: mark host session-trusted then
     * retry the real failing URL on same webView instance.
     */
    public void onErrorPageProceedClicked(final Object webView, final String failingUrl, final String host) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(host)) {
                    mSessionTrustedSslHosts.add(host);
                    logLocal("onErrorPageProceedClicked: host now session-trusted -> " + host);
                }
                if (!TextUtils.isEmpty(failingUrl)) {
                    loadUrlOnSpecificWebView(webView, failingUrl);
                }
            }
        });
    }

    /*
     * Same job like navigateTo() loadUrl() call, but can target ANY tab
     * webView instance direct, not always mWebView/foreground one. Need
     * this for chrome://error "Proceed anyway" retry (could be
     * background tab own error) and for onSslErrorReceived() below.
     * Only touch address bar/mCurrentUrl/badge bookkeeping when target
     * really is the foreground tab, background tab reload stay silent
     * same as any other background navigate elsewhere in this file.
     */
    private void loadUrlOnSpecificWebView(Object webView, String url) {
        if (webView == null || TextUtils.isEmpty(url)) return;
        try {
            Method loadUrl;
            try {
                loadUrl = webView.getClass().getMethod("loadUrl", String.class);
            } catch (NoSuchMethodException nsme) {
                loadUrl = webView.getClass().getDeclaredMethod("loadUrl", String.class);
                loadUrl.setAccessible(true);
            }
            loadUrl.invoke(webView, url);
            logLocal("loadUrlOnSpecificWebView: loaded " + url + " isForegroundTab=" + (webView == mWebView));
            TabManager.Tab tab = mTabManager.findTab(webView);
            if (tab != null) tab.url = url;
            if (webView == mWebView) {
                mCurrentUrl = url;
                if (mUrlInput != null) mUrlInput.setText(url);
                applyResolvedUserAgent(url);
                updateSecurityBadge(url);
            }
        } catch (Throwable t) {
            logException("loadUrlOnSpecificWebView", t);
        }
    }

    /** call no-arg method by name via reflection, log + swallow any fail, use for handler.cancel()/proceed() */
    private void invokeNoArgOn(Object target, String methodName) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.invoke(target);
        } catch (Throwable t) {
            logException("invokeNoArgOn(" + methodName + ")", t);
        }
    }

    /*
     * Human-short key for android.net.http.SslError.getPrimaryError(),
     * feed into chrome://error?reason=... query param, XposedInit
     * buildErrorPageHtml() turn this into real user-face sentence.
     * Keep here (not in XposedInit) since android.net.http.SslError
     * constant already plain import here same as everywhere else in
     * this file, no classloader lookup need for it (confirm stock
     * class, not UC-wrap one, by WebViewClient.smali).
     */
    private String sslPrimaryErrorReasonKey(int primaryError) {
        switch (primaryError) {
            case android.net.http.SslError.SSL_UNTRUSTED: return "untrusted";
            case android.net.http.SslError.SSL_EXPIRED: return "expired";
            case android.net.http.SslError.SSL_IDMISMATCH: return "mismatch";
            case android.net.http.SslError.SSL_NOTYETVALID: return "notyetvalid";
            case android.net.http.SslError.SSL_DATE_INVALID: return "dateinvalid";
            case android.net.http.SslError.SSL_INVALID: return "invalid";
            default: return "unknown";
        }
    }

    /*
     * Call (via reflection) from XposedInit onReceivedSslError hook.
     * Default engine behavior (confirm by smali) is silent
     * handler.cancel(), nothing ever tell UI load die, progress bar
     * stuck forever - that whole bug this fix.
     *
     * Host already in mSessionTrustedSslHosts (user already tap "Proceed
     * anyway" once this app run) -> proceed() right away, no
     * interstitial again, this "resume after accept" half of the loop
     * (other half is onErrorPageProceedClicked() above, that one add
     * host to set + retry same URL, THIS retry hit same cert problem
     * again, land back here, but this time short-circuit straight to
     * proceed()). Otherwise cancel() the bad connection clean (own
     * chrome://error page load right after is a SEPARATE navigate, not
     * try keep this original one alive) and show own interstitial.
     */
    public void onSslErrorReceived(final Object webView, final Object handler, final android.net.http.SslError error) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                String failingUrl = null;
                String host = null;
                int primaryError = -1;
                try {
                    failingUrl = error.getUrl();
                    host = Uri.parse(failingUrl).getHost();
                    primaryError = error.getPrimaryError();
                } catch (Throwable t) {
                    logException("onSslErrorReceived: read SslError fields", t);
                }
                logLocal("onSslErrorReceived: host=" + host + " primaryError=" + primaryError + " url=" + failingUrl);

                if (host != null && mSessionTrustedSslHosts.contains(host)) {
                    invokeNoArgOn(handler, "proceed");
                    logLocal("onSslErrorReceived: host already session-trusted, auto-proceed().");
                    return;
                }

                invokeNoArgOn(handler, "cancel");
                if (webView == mWebView && mProgressBar != null) {
                    mProgressBar.setVisibility(View.GONE);
                    mProgressBar.setProgress(0);
                }

                String reasonKey = sslPrimaryErrorReasonKey(primaryError);
                String errorPageUrl = "chrome://error?kind=ssl"
                    + "&host=" + Uri.encode(host == null ? "" : host)
                    + "&reason=" + Uri.encode(reasonKey)
                    + "&url=" + Uri.encode(failingUrl == null ? "" : failingUrl);
                loadUrlOnSpecificWebView(webView, errorPageUrl);
            }
        });
    }

    /*
     * Call (via reflection) from XposedInit onReceivedError hook (modern
     * WebResourceError overload). Only main-frame error worth own
     * interstitial (sub-resource fail - broken image, blocked tracker,
     * etc - too noisy to error-page for). Same stuck-progress-bar bug as
     * SSL, hide it explicit here same reason.
     */
    public void onLoadErrorReceived(final Object webView, final boolean isMainFrame, final int errorCode,
            final String description, final String failingUrl) {
        if (!isMainFrame) return;
        if (failingUrl != null && failingUrl.startsWith("chrome://")) return; // do not error-page our own error page
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                logLocal("onLoadErrorReceived: code=" + errorCode + " desc=" + description + " url=" + failingUrl);
                if (webView == mWebView && mProgressBar != null) {
                    mProgressBar.setVisibility(View.GONE);
                    mProgressBar.setProgress(0);
                }
                String errorPageUrl = "chrome://error?kind=net"
                    + "&code=" + errorCode
                    + "&desc=" + Uri.encode(description == null ? "" : description)
                    + "&url=" + Uri.encode(failingUrl == null ? "" : failingUrl);
                loadUrlOnSpecificWebView(webView, errorPageUrl);
            }
        });
    }

    /** cut string down to maxLen char, add ellipsis, use for popup-confirm dialog url display */
    private static String truncateForDialog(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, Math.max(0, maxLen - 1)) + "\u2026";
    }

    /*
     * Call (via reflection) from XposedInit onPageStarted hook, fire for
     * EVERY navigate on EVERY tab (that hook already class-wide, not
     * just foreground), added on top of existing UA/userscript work it
     * already do. Only care here when tab still carry
     * pendingPopupConfirmation flag (see TabManager.Tab note) - only
     * true for the very first real navigate a window.open()/
     * target="_blank" popup tab ever get, and that first navigate is
     * the ONLY point we really know its destination URL (onCreateWindow
     * self never give URL, see onCreateWindowRequested() below). Show
     * confirm dialog right here, OK -> switchToTab() (foreground it),
     * Cancel -> closeTab() (destroy, never show).
     */
    public void onAnyPageStarted(final Object webView, final String url) {
        final TabManager.Tab tab = mTabManager.findTab(webView);
        if (tab == null || !tab.pendingPopupConfirmation) return;
        tab.pendingPopupConfirmation = false; // clear right away, only ask once per popup tab, not every redirect after
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                tab.url = url;
                String shortUrl = truncateForDialog(url, 30);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.website_openlink_title))
                    .setMessage(getString(R.string.website_openlink_content, shortUrl))
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            logLocal("onAnyPageStarted: popup confirmed -> " + url);
                            if (!tab.incognito) {
                                SettingsStore.setLastUrl(MainActivity.this, url);
                                recordHistoryVisit(url);
                            }
                            switchToTab(tab);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            logLocal("onAnyPageStarted: popup declined -> " + url);
                            closeTab(tab);
                        }
                    })
                    .show();
            }
        });
    }

    /*
     * Call (via reflection) from XposedInit onCreateWindow hook
     * (window.open() JS call, or link tap with target="_blank" - engine
     * funnel both through same callback, no way tell apart here, so
     * both get same confirm-dialog treatment, see onAnyPageStarted()
     * above). onCreateWindow self never give destination URL, only a
     * Message whose .obj is a transport object expect setWebView(...)
     * call with a real new export.WebView instance, then
     * resultMsg.sendToTarget() let engine really start navigate it -
     * THAT navigate is what onAnyPageStarted() above catch to finally
     * learn real URL and show the dialog. Always create real tab +
     * attach transport here (never decide skip synchronously, we not
     * know URL yet to decide anything), Cancel branch in
     * onAnyPageStarted() just closeTab() it after the fact instead.
     */
    public void onCreateWindowRequested(final Object webView, final boolean isDialog, final boolean isUserGesture,
            final android.os.Message resultMsg) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    TabManager.Tab sourceTab = mTabManager.findTab(webView);
                    boolean incognito = sourceTab != null && sourceTab.incognito;
                    View newWebViewAsView = createTabWebView(incognito);
                    if (newWebViewAsView == null) {
                        logLocal("onCreateWindowRequested: createTabWebView() failed, abandon popup request.");
                        return;
                    }
                    TabManager.Tab popupTab = new TabManager.Tab(newWebViewAsView);
                    popupTab.incognito = incognito;
                    popupTab.pendingPopupConfirmation = true;
                    mTabManager.tabs.add(popupTab);
                    logLocal("onCreateWindowRequested: pending popup tab create (isDialog=" + isDialog
                        + " isUserGesture=" + isUserGesture + "), wait onAnyPageStarted for real URL.");

                    Object transport = resultMsg.obj;
                    if (transport == null) {
                        logLocal("onCreateWindowRequested: resultMsg.obj null, no transport, abandon.");
                        mTabManager.tabs.remove(popupTab);
                        return;
                    }
                    Method setWebView;
                    try {
                        setWebView = transport.getClass().getMethod("setWebView", mExportWebViewClass);
                    } catch (NoSuchMethodException nsme) {
                        /*
                         * exact mExportWebViewClass param type guess not
                         * match, fallback scan every public method name
                         * "setWebView" take 1 arg, log what find either
                         * way so wrong guess show up right away (same
                         * style like handleWebViewLongPress hit-test
                         * note did before for similar uncertain API).
                         */
                        setWebView = null;
                        for (Method m : transport.getClass().getMethods()) {
                            if (m.getName().equals("setWebView") && m.getParameterTypes().length == 1) {
                                setWebView = m;
                                break;
                            }
                        }
                        logLocal("onCreateWindowRequested: transport class=" + transport.getClass().getName()
                            + " fallback setWebView lookup found=" + (setWebView != null));
                    }
                    if (setWebView == null) {
                        logLocal("onCreateWindowRequested: no setWebView() method found on transport, abandon.");
                        mTabManager.tabs.remove(popupTab);
                        return;
                    }
                    setWebView.invoke(transport, newWebViewAsView);
                    resultMsg.sendToTarget();
                    logLocal("onCreateWindowRequested: transport attached, resultMsg sent.");
                } catch (Throwable t) {
                    logException("onCreateWindowRequested", t);
                }
            }
        });
    }

    /*
     * Call (via reflection) from XposedInit static
     * applyUserAgentIfConfigured(), as fallback default when site have no
     * match rule/global UA, see that method note. uaSettingsContext there
     * only ApplicationContext, can not reach this instance field, that
     * why need reflect round-trip.
     */
    public String getDefaultUserAgent() {
        return mDefaultUserAgent;
    }

    /*
     * Call (via reflection) from XposedInit ImeAdapterImpl.t() hook, t()
     * is confirm real Chromium method that really trigger soft keyboard
     * (see that hook note for smali trail). Return true make hook skip
     * t() completely, so keyboard never show for that focus event. Off
     * by default (SettingsStore own default), and even when setting on,
     * only suppress when no genuine touch on WebView recent, a page call
     * element.focus() right after you tap input self should still show
     * keyboard normal.
     */
    public boolean shouldSuppressAutoKeyboard() {
        if (!SettingsStore.getPreventAutoKeyboardWake(this)) return false;
        return (System.currentTimeMillis() - mLastGenuineTouchTimeMs) > KEYBOARD_TOUCH_GRACE_MS;
    }

    /*
     * Call (via reflection) from XposedInit WebChromeClient
     * onProgressChanged hook. webView pass direct as param.args[0]
     * there (no bridge-correlation trick need, unlike navigate/favicon,
     * this hook expose WebView instance self), so plain identity check
     * against mWebView is all that need to ignore background tab.
     */
    public void onPageProgressChanged(final Object webView, final int progress) {
        /*
         * TEMP DIAGNOSTIC (2026-09-04): log every progress value report,
         * no matter if for active tab, confirm whether engine really
         * only fire one jump-to-100 callback for fast/simple page (very
         * plausible for something as light as google.com, modern
         * Chromium progress report is coarse by nature) versus something
         * really being skip/lost on our end. Safe to remove once confirm
         * either way.
         */
        logLocal("onPageProgressChanged: progress=" + progress + " isActiveTab=" + (webView == mWebView));
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mProgressBar == null || webView != mWebView) return;
                if (progress >= 100) {
                    mProgressBar.setVisibility(View.GONE);
                    mProgressBar.setProgress(0);
                } else {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressBar.setProgress(progress);
                }
            }
        });
    }

    /*
     * Call (via reflection) from XposedInit WebChromeClient
     * onReceivedTitle hook. Always update the report tab own store
     * title (for tab switcher list), no matter if it currently active
     * tab, unlike progress/favicon, a background tab title still useful
     * to know without touch any foreground UI.
     */
    public void onRealTitleUpdate(final Object webView, final String title) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                TabManager.Tab tab = mTabManager.findTab(webView);
                if (tab != null && !TextUtils.isEmpty(title)) {
                    tab.title = title;
                    syncTabSwitcherList();
                    if (mDb != null && !TextUtils.isEmpty(tab.url)) {
                        mDb.updateLatestVisitTitle(tab.url, title);
                    }
                }
            }
        });
    }

    /*
     * Call (via reflection) from XposedInit AwContentsClientBridge
     * constructor hook. Fire sync, on the SAME thread that currently
     * inside createTabWebView() ctor.newInstance() call (main thread),
     * on purpose NOT post to mMainHandler like most other host callback,
     * since whole correlation trick depend on this run inline, before
     * newInstance() return.
     */
    public void onWebViewBridgeCreated(Object bridgeInstance) {
        mBridgesSeenDuringCurrentConstruction.add(bridgeInstance);
        logLocal("onWebViewBridgeCreated: captured bridge " + System.identityHashCode(bridgeInstance)
            + " (pending correlation to the WebView currently under construction)");
    }

    public void onRealNavigationUrlUpdate(final String url) {
        onRealNavigationUrlUpdate(url, null);
    }

    /*
     * bridgeInstance is the AwContentsClientBridge that fire this update,
     * if know (null for hook site that not have one available, see
     * XposedInit i6.onUpdateUrl hook, which still just assume foreground,
     * same as before this change). Resolve it to specific tab via
     * mBridgeToWebView so BACKGROUND tab own navigate update that tab
     * store title/url for tab switcher list, without touch foreground
     * address bar/security badge/last-URL persist, those only apply when
     * resolve WebView is the one really on screen.
     */
    public void onRealNavigationUrlUpdate(final String url, final Object bridgeInstance) {
        logLocal("onRealNavigationUrlUpdate called with: " + url + " bridge=" + System.identityHashCode(bridgeInstance));
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            logLocal("onRealNavigationUrlUpdate: filtered out (not http/https).");
            return;
        }

        Object resolvedWebView = bridgeInstance != null ? mBridgeToWebView.get(bridgeInstance) : null;
        TabManager.Tab tab = resolvedWebView != null ? mTabManager.findTab(resolvedWebView) : null;
        if (tab != null) {
            tab.url = url;
        }
        /*
         * If resolution didn't find a tab (unknown bridge, or no bridge passed at
         * all), default to treating it as the active tab rather than silently
         * dropping the update - matches this method's old, pre-tabs behavior.
         */
        final boolean isActiveTab = (resolvedWebView != null) ? (resolvedWebView == mWebView) : true;
        logLocal("onRealNavigationUrlUpdate: resolvedTab=" + (tab != null) + " isActiveTab=" + isActiveTab);
        if (!isActiveTab) {
            return; // background tab - its own Tab.url is already updated above
        }

        resetFaviconToPlaceholder();
        mCurrentUrl = url;
        TabManager.Tab activeTabForRecording = mTabManager.activeTab();
        if (activeTabForRecording == null || !activeTabForRecording.incognito) {
            SettingsStore.setLastUrl(this, url);
            recordHistoryVisit(url);
        }
        syncBookmarkStarIcon(url);
        saveOpenTabsToDisk();
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                applyBottomBarVisibilityForUrl(url);
                updateSecurityBadge(url);
                if (mUrlInput != null && !mUrlInput.hasFocus()) {
                    logLocal("onRealNavigationUrlUpdate: setting address bar text to " + url);
                    mUrlInput.setText(url);
                } else {
                    logLocal("onRealNavigationUrlUpdate: NOT updating - mUrlInput=" + mUrlInput + ", hasFocus=" + (mUrlInput != null && mUrlInput.hasFocus()));
                }
            }
        });
    }

    // real favicon practically always <= 256x256, generous cap well above that, anything bigger almost certainly abusive not a real icon
    private static final int MAX_FAVICON_SOURCE_PX = 512;

    public void onRealFaviconUpdate(final Bitmap icon) {
        if (icon == null) return;
        /*
         * Nothing stop a page <link rel="icon"> point at a huge image
         * instead of a real small icon - by the time engine hand it to
         * this callback, the actual expensive/dangerous decode already
         * happen INTERNAL to the engine own image loader, before we
         * ever see it, nothing at this Java/Xposed layer can prevent
         * THAT part (would need native-level decode-size-limit, well
         * outside what a WebChromeClient hook reach). What we CAN
         * control: not make it worse ourself - createScaledBitmap() on
         * a huge source is itself another big allocation, likely the
         * actual straw that push OOM even when the initial decode alone
         * survive - and not RETAIN a giant bitmap long-term in
         * activeTab.favicon (extra sustained pressure, worse the more
         * tab open). Recycle and bail, keep whatever placeholder
         * already show instead.
         */
        if (icon.getWidth() > MAX_FAVICON_SOURCE_PX || icon.getHeight() > MAX_FAVICON_SOURCE_PX) {
            logLocal("onRealFaviconUpdate: rejecting oversized favicon " + icon.getWidth() + "x" + icon.getHeight()
                + " (cap " + MAX_FAVICON_SOURCE_PX + "px), keeping placeholder.");
            icon.recycle();
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mFaviconView != null) {
                    int targetPx = dpToPx(20);
                    // Ensure the icon scales up to a clean, crisp 20x20dp target
                    Bitmap scaledIcon = Bitmap.createScaledBitmap(icon, targetPx, targetPx, true);
                    mFaviconView.setImageBitmap(scaledIcon);
                    /*
                     * This hook has no bridge/webview reference to correlate against
                     * (same gap as the favicon-side of the still-open background-tab
                     * question) so it assumes the event is for whichever tab is
                     * currently on screen - true for the common case, wrong only if a
                     * BACKGROUND tab's favicon happened to load at this exact moment.
                     */
                    TabManager.Tab activeTab = mTabManager.activeTab();
                    if (activeTab != null) {
                        activeTab.favicon = scaledIcon;
                    }
                }
            }
        });
    }

    /*
     * Plain android.app.DownloadManager - a normal public Android API, not
     * anything internal to UC or AOSP Browser. No need to reflect into
     * whatever AOSP Browser calls internally; this is the same mechanism
     * it (and every other browser) ultimately uses.
     * Find in page 
     */

    private void showFindBar() {
        if (mFindBar == null) return;
        mFindBar.setVisibility(View.VISIBLE);
        if (mFindBarDivider != null) mFindBarDivider.setVisibility(View.VISIBLE);
        mFindQueryInput.setText("");
        mFindMatchCountText.setText(R.string.find_match_count_default);
        mFindQueryInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mFindQueryInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        logLocal("showFindBar: opened.");
    }

    private void hideFindBar() {
        if (mFindBar == null || mFindBar.getVisibility() != View.VISIBLE) return;
        mFindBar.setVisibility(View.GONE);
        if (mFindBarDivider != null) mFindBarDivider.setVisibility(View.GONE);
        try {
            if (mWebView != null) {
                Method clearMatches = mWebView.getClass().getMethod("clearMatches");
                clearMatches.invoke(mWebView);
            }
        } catch (Throwable t) {
            logException("hideFindBar.clearMatches", t);
        }
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mFindQueryInput.getWindowToken(), 0);
        logLocal("hideFindBar: closed.");
    }

    private boolean isFindBarShown() {
        return mFindBar != null && mFindBar.getVisibility() == View.VISIBLE;
    }

    private void findInPage(String query) {
        if (mWebView == null) return;
        try {
            Method findAllAsync = mWebView.getClass().getMethod("findAllAsync", String.class);
            findAllAsync.invoke(mWebView, query);
        } catch (Throwable t) {
            logException("findInPage.findAllAsync", t);
        }
    }

    private void findNextMatch(boolean forward) {
        if (mWebView == null) return;
        try {
            Method findNext = mWebView.getClass().getMethod("findNext", boolean.class);
            findNext.invoke(mWebView, forward);
        } catch (Throwable t) {
            logException("findNextMatch.findNext", t);
        }
    }

    /*
     * Call (via reflection, from FindListener proxy in
     * createTabWebView()) on main thread. activeMatchOrdinal is 0-based
     * per stock android.webkit.WebView.FindListener contract, that why
     * +1 for display.
     */
    private void updateFindMatchCount(int activeMatchOrdinal, int numberOfMatches) {
        if (mFindMatchCountText == null) return;
        if (numberOfMatches <= 0) {
            mFindMatchCountText.setText(R.string.find_match_count_default);
        } else {
            mFindMatchCountText.setText((activeMatchOrdinal + 1) + "/" + numberOfMatches);
        }
    }

    private void startSystemDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (TextUtils.isEmpty(url)) return;
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        /*
         * DownloadManager.Request only accept http(s) URI (confirm real
         * device log: "Can only download HTTP/HTTPS URIs" for a
         * blob:https://... url) - blob: object only ever exist inside
         * the page own JS heap/IndexedDB (canvas.toBlob(),
         * MediaSource, etc, common on site like Facebook "save video"),
         * no real network endpoint anyone (DownloadManager include) can
         * fetch it from outside. Have to read it back out FROM the page
         * own JS instead - see downloadBlobUrl() below, own separate
         * path complete, none of the DownloadManager.Request code below
         * apply to it at all.
         */
        if (url.startsWith("blob:")) {
            downloadBlobUrl(url, mimeType, fileName);
            return;
        }
        /*
         * Same Yes/No confirm dialog as blob download now (before this
         * just auto-enqueue with no prompt at all) - reuse same
         * download_blob_title/download_blob_content string, wording
         * generic enough ("Download file? / Save this file: %1$s") to
         * fit either path, no need separate string pair just for this.
         */
        final String finalUrl = url;
        final String finalUserAgent = userAgent;
        final String finalMimeType = mimeType;
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_blob_title))
            .setMessage(getString(R.string.download_blob_content, fileName))
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    enqueueHttpDownload(finalUrl, finalUserAgent, finalMimeType);
                }
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    /*
     * Real enqueue, split out of startSystemDownload() so the confirm
     * dialog above only need decide whether to call this, not duplicate
     * the DownloadManager.Request build. fileName re-guess here (not
     * pass down from caller) purely so a possible future site rule/
     * content-disposition change between prompt-show and "Yes" tap
     * (rare, but request object build here anyway not before) stay
     * consistent with whatever get shown in the dialog - in practice
     * always same value, guessFileName() pure function of its 3 arg.
     */
    private void enqueueHttpDownload(String url, String userAgent, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, null, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (!TextUtils.isEmpty(mimeType)) {
                request.setMimeType(mimeType);
            }
            if (!TextUtils.isEmpty(userAgent)) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            /*
             * NOTE: no cookie attach here. If site need login/session cookie
             * to really serve file, this will download error page instead,
             * export.WebView own CookieManager not part of this pass, so
             * plumb that through is possible follow-up later.
             */
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, getString(R.string.downloading_format, fileName), Toast.LENGTH_SHORT).show();
                logLocal("enqueueHttpDownload: enqueued " + fileName + " from " + url);
            }
        } catch (Throwable t) {
            logException("enqueueHttpDownload", t);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /*
     * blob: url only readable from INSIDE the page own JS (it point at
     * an in-memory Blob object on the renderer, nothing external can
     * fetch it, confirm by real DownloadManager crash log). Only way
     * out: add a tiny @JavascriptInterface bridge, run script in the
     * page that fetch() the blob url self (work fine for blob: from
     * same document that create it), read it back as base64 data URL
     * via FileReader, then call bridge method with that string - bridge
     * method fire on some background JS-bridge thread (not main), hop
     * back to mMainHandler before touch file I/O/UI same as every other
     * async host callback in this file.
     *
     * addJavascriptInterface() confirm mirror stock android.webkit.WebView
     * shape (same 1:1 export.* pattern every other class already show),
     * reflect same way as always for a UC-internal instance call.
     */

    /*
     * TEST CONFIRM (2026-09-05, THIRD attempt): even a real named PUBLIC
     * inner class still hit same exact "onBlobData is not a function" on
     * real device. Standard StackOverflow-documented fix (anonymous
     * class -> named public class) apparently not the real cause here -
     * most likely this project own specific architecture (export.WebView
     * internal live under a SEPARATE classloader, mUcClassLoader, loaded
     * via reflection from UC own dex, not the host app classloader that
     * load MainActivity/BlobDownloadBridge) confuse whatever
     * classloader-identity check Chromium own JS-method-enumerate
     * reflection do internally - two different classloader see
     * "same-name" class as different Class object, likely reject/filter
     * method that way. Whatever the exact mechanism, addJavascriptInterface()
     * itself proven unreliable in THIS specific setup after two real
     * device test both fail same way, not going try a third variant of
     * the same broken tool.
     *
     * Fix: skip addJavascriptInterface() ENTIRE, use plain evaluateJavascript()
     * both direction instead - inject script that just stash result into
     * an ordinary "window.__evoBlobResult"/"window.__evoBlobError"
     * property (plain JS property write, page own existing realm, no
     * Java-exposed interface/binding involve at all for this half), then
     * Java side POLL it back with repeat evaluateJavascript() call until
     * one show up or timeout. evaluateJavascript() itself already proven
     * work fine both direction elsewhere in this file (UA/userscript
     * inject, filename click-intent lookup few message ago), only the
     * addJavascriptInterface() push-callback style ever broke.
     */
    private static final int BLOB_POLL_INTERVAL_MS = 300;
    private static final int BLOB_POLL_MAX_ATTEMPTS = 60; // ~18s total before give up

    /*
     * fallbackFileName here is the old URLUtil.guessFileName() result
     * (usually just the blob own random UUID plus a generic extension,
     * blob: url never carry real Content-Disposition header, confirm by
     * real device log contentDisposition always empty for it) - user
     * right that is a bad name.
     *
     * Real intended name come from XposedInit.injectDownloadIntentTracker()
     * (install once per page load, capture-phase click listener stash
     * {href, download} of whatever <a download> element really get click,
     * INTO window.__evoLastDownloadIntent, at the moment of the actual
     * click) - just read that back here and match by href, NOT scan
     * every anchor currently in DOM (that already too late for the
     * common "create temp anchor, click, remove right away" pattern
     * anyway, and per explicit ask, not want blind-scan even where it
     * happen to still work).
     *
     * KNOWN LIMIT: two download click in quick succession before the
     * first one resolve could see the second one own intent overwrite
     * the first (single shared variable, no queue) - rare edge case,
     * fall back to guess name same as always if href not match.
     */
    private void downloadBlobUrl(final String blobUrl, final String mimeType, final String fallbackFileName) {
        if (mWebView == null) return;
        try {
            String lookupScript = "(function(){"
                + "try{"
                + "var i=window.__evoLastDownloadIntent;"
                + "if(i && i.href===" + jsStringLiteral(blobUrl) + "){ return i.download||''; }"
                + "}catch(e){}"
                + "return '';"
                + "})();";

            Method evalJs = mWebView.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
            android.webkit.ValueCallback<String> callback = new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    String realName = unquoteJsStringResult(value);
                    String finalName = TextUtils.isEmpty(realName) ? fallbackFileName : realName;
                    logLocal("downloadBlobUrl: filename lookup -> " + (TextUtils.isEmpty(realName) ? "(no click-intent match, using guess) " : "(matched click intent) ") + finalName);
                    confirmAndFetchBlob(blobUrl, mimeType, finalName);
                }
            };
            evalJs.invoke(mWebView, lookupScript, callback);
        } catch (Throwable t) {
            logException("downloadBlobUrl.lookupFilename", t);
            confirmAndFetchBlob(blobUrl, mimeType, fallbackFileName); // still try, just with the weaker guessed name
        }
    }

    /** evaluateJavascript() callback give back a JSON-quoted string ("\"name.pdf\"") or literal "null" - unwrap to a plain Java String or null */
    private static String unquoteJsStringResult(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /*
     * Confirm dialog show BEFORE any fetch/network work happen this
     * time (filename already know at this point from click-intent
     * lookup above, no need wait for the actual byte fetch just to ask),
     * only "Yes" trigger startBlobFetchAndPoll() below - nicer than
     * before (fetch first, ask after) both for not waste bandwidth on a
     * "No" answer, and for not depend on addJavascriptInterface working
     * at all for the confirm gate itself.
     */
    private void confirmAndFetchBlob(final String blobUrl, final String mimeType, String filename) {
        final String safeFilename = TextUtils.isEmpty(filename) ? "download" : filename;
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_blob_title))
            .setMessage(getString(R.string.download_blob_content, safeFilename))
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startBlobFetchAndPoll(blobUrl, mimeType, safeFilename);
                }
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    /*
     * webView pin to a local (not re-read mWebView later) on purpose -
     * user could switch tab mid-poll (18s max window), fetch/poll must
     * keep target the ORIGINAL tab it start on, not silently jump to
     * whatever tab happen be foreground by the time a later poll tick
     * fire.
     */
    private void startBlobFetchAndPoll(final String blobUrl, final String mimeType, final String fileName) {
        if (mWebView == null) return;
        final Object webView = mWebView;
        try {
            String script = "(function(){"
                + "window.__evoBlobResult=null;window.__evoBlobError=null;"
                + "fetch(" + jsStringLiteral(blobUrl) + ").then(function(r){return r.blob();}).then(function(b){"
                + "var reader=new FileReader();"
                + "reader.onloadend=function(){window.__evoBlobResult=reader.result;};"
                + "reader.onerror=function(e){window.__evoBlobError='FileReader error: '+e;};"
                + "reader.readAsDataURL(b);"
                + "}).catch(function(e){window.__evoBlobError=String(e);});"
                + "})();";

            try {
                Method evalJs = webView.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
                evalJs.invoke(webView, script, null);
            } catch (NoSuchMethodException nsme) {
                Method loadUrl = webView.getClass().getMethod("loadUrl", String.class);
                loadUrl.invoke(webView, "javascript:" + script);
            }
            logLocal("startBlobFetchAndPoll: script injected, start poll -> " + blobUrl);
            pollForBlobResult(webView, mimeType, fileName, 0);
        } catch (Throwable t) {
            logException("startBlobFetchAndPoll", t);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void pollForBlobResult(final Object webView, final String mimeType, final String fileName, final int attempt) {
        if (attempt >= BLOB_POLL_MAX_ATTEMPTS) {
            logLocal("pollForBlobResult: timeout waiting for blob data (" + fileName + ")");
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // read-and-clear both slot in one round trip, JSON.stringify so a single evaluateJavascript() return carries both
            String poll = "(function(){var r=window.__evoBlobResult;var e=window.__evoBlobError;"
                + "if(r||e){window.__evoBlobResult=null;window.__evoBlobError=null;}"
                + "return JSON.stringify([r||null, e||null]);"
                + "})();";
            Method evalJs = webView.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
            android.webkit.ValueCallback<String> callback = new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    handleBlobPollResult(webView, value, mimeType, fileName, attempt);
                }
            };
            evalJs.invoke(webView, poll, callback);
        } catch (Throwable t) {
            logException("pollForBlobResult", t);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleBlobPollResult(final Object webView, String rawValue, final String mimeType, final String fileName, final int attempt) {
        try {
            // rawValue is evaluateJavascript() own JSON-string-encode of our already-JSON.stringify() result, unwrap ONE layer first
            String unquoted = unquoteJsStringResult(rawValue);
            org.json.JSONArray arr = unquoted == null ? null : new org.json.JSONArray(unquoted);
            String dataUrl = (arr != null && !arr.isNull(0)) ? arr.optString(0, null) : null;
            String errorMsg = (arr != null && !arr.isNull(1)) ? arr.optString(1, null) : null;

            if (!TextUtils.isEmpty(errorMsg)) {
                logException("pollForBlobResult", new Exception(errorMsg));
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.isEmpty(dataUrl)) {
                saveBlobDataToDownloads(dataUrl, fileName, mimeType);
                return;
            }
            // neither ready yet, poll again
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pollForBlobResult(webView, mimeType, fileName, attempt + 1);
                }
            }, BLOB_POLL_INTERVAL_MS);
        } catch (Throwable t) {
            logException("handleBlobPollResult", t);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** wrap a Java string as a single-quote JS string literal, escape backslash/quote so blobUrl embed safe into the script text */
    private static String jsStringLiteral(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /*
     * dataUrl look like "data:application/octet-stream;base64,AAAA..." -
     * split off everything before the comma, base64-decode the rest,
     * write plain file, then DownloadManager.addCompletedDownload() to
     * register it same as a normal finish download (show in system
     * Downloads app, trigger media scan), since this path never go
     * through DownloadManager.Request/enqueue() at all. Call from
     * evaluateJavascript() own callback, which run main thread by
     * contract, no mMainHandler.post() need here.
     */
    private void saveBlobDataToDownloads(String dataUrl, String suggestedFileName, String mimeType) {
        try {
            if (dataUrl == null || dataUrl.indexOf(',') < 0) {
                throw new IllegalArgumentException("blob data url missing base64 payload: " + dataUrl);
            }
            byte[] bytes = android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), android.util.Base64.DEFAULT);

            String fileName = TextUtils.isEmpty(suggestedFileName)
                ? ("download_" + System.currentTimeMillis()) : suggestedFileName;
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) downloadsDir.mkdirs();
            File outFile = new File(downloadsDir, fileName);

            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(bytes);
            fos.close();

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.addCompletedDownload(fileName, fileName, true,
                    TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType,
                    outFile.getAbsolutePath(), bytes.length, true);
            }
            Toast.makeText(this, getString(R.string.downloading_format, fileName), Toast.LENGTH_SHORT).show();
            logLocal("saveBlobDataToDownloads: saved blob download to " + outFile.getAbsolutePath());
        } catch (Throwable t) {
            logException("saveBlobDataToDownloads", t);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /*
     * android.webkit.WebView.HitTestResult getType()/getExtra() turn out
     * not reliable for this build (see handleWebViewLongPress below), not
     * use anymore, keep only as note what not work.
     */

    private static final int MSG_REQUEST_IMAGE_REF = 8801;
    private static final int MSG_REQUEST_FOCUS_NODE_HREF = 8802;

    /*
     * getHitTestResult() (before approach) turn out not reliable this
     * build, confirm via log (2026-09-03): even after defer read with
     * mMainHandler.post(), type/extra still UNKNOWN_TYPE/null every time.
     * requestImageRef(Message)/requestFocusNodeHref(Message) is the real
     * Android WebView API build for this, both confirm present by smali,
     * and give answer async by callback Message once engine really
     * resolve it, instead of race a sync read. Standard bundle key is
     * "url" (requestFocusNodeHref also have "title"/"src" for anchor that
     * wrap image), match every other export.* class 1:1 mirror of stock
     * android.webkit API. Log either way so wrong guess here show up
     * right away instead of silent do nothing.
     */
    private void handleWebViewLongPress() {
        if (mWebView == null) return;
        try {
            final String[] results = new String[2]; // [0]=image url (from requestImageRef), [1]=link url (from requestFocusNodeHref)
            final boolean[] responded = new boolean[2];
            final boolean[] resolved = new boolean[1];

            final Handler resultHandler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    Bundle data = msg.getData();
                    logLocal("longPress async result: what=" + msg.what + " data=" + (data == null ? "null" : data.toString()));
                    int idx = (msg.what == MSG_REQUEST_IMAGE_REF) ? 0 : 1;
                    results[idx] = data == null ? null : data.getString("url");
                    responded[idx] = true;
                    if (responded[0] && responded[1] && !resolved[0]) {
                        resolved[0] = true;
                        finishLongPress(results[0], results[1]);
                    }
                }
            };

            Method requestImageRef = mWebView.getClass().getMethod("requestImageRef", Message.class);
            requestImageRef.invoke(mWebView, resultHandler.obtainMessage(MSG_REQUEST_IMAGE_REF));

            Method requestFocusNodeHref = mWebView.getClass().getMethod("requestFocusNodeHref", Message.class);
            requestFocusNodeHref.invoke(mWebView, resultHandler.obtainMessage(MSG_REQUEST_FOCUS_NODE_HREF));

            /*
             * Safety net, if touch point have neither image nor link, at
             * least one of these two callback maybe never arrive at all
             * (instead of arrive with empty bundle). Not wait forever for
             * it.
             */
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!resolved[0]) {
                        resolved[0] = true;
                        finishLongPress(results[0], results[1]);
                    }
                }
            }, 400);
        } catch (Throwable t) {
            logException("handleWebViewLongPress", t);
        }
    }

    private void finishLongPress(String imageUrl, String linkUrl) {
        if (TextUtils.isEmpty(imageUrl) && TextUtils.isEmpty(linkUrl)) {
            logLocal("finishLongPress: nothing resolved under the touch point (no image, no link).");
            return;
        }
        showOptionsMenu(TextUtils.isEmpty(imageUrl) ? null : imageUrl, TextUtils.isEmpty(linkUrl) ? null : linkUrl);
    }

    /*
     * imageUrl OR linkUrl expect non-null (not both), see caller above.
     * "Open in new tab" now offer for link (not image, open image just
     * mean view it full-screen in current tab, new tab not make sense
     * for that one), use existing openNewTab(url) same as bookmark/
     * history entry "open in new tab" action already use.
     */
    private void showOptionsMenu(final String imageUrl, final String linkUrl) {
        final String targetUrl = imageUrl != null ? imageUrl : linkUrl;
        List<String> items = new ArrayList<String>();
        if (imageUrl != null) {
            items.add(getString(R.string.open_image));
            items.add(getString(R.string.download_image));
            items.add(getString(R.string.copy_image_address));
        } else {
            items.add(getString(R.string.open_link));
            items.add(getString(R.string.open_link_new_tab));
            items.add(getString(R.string.copy_link_address));
        }
        final boolean isImage = imageUrl != null;
        new AlertDialog.Builder(this)
            .setTitle(targetUrl)
            .setItems(items.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    /*
                     * index meaning depend which item list build above:
                     * image -> [0]=Open, [1]=Download, [2]=Copy ;
                     * link -> [0]=Open, [1]=Open in new tab, [2]=Copy
                     */
                    if (which == 0) {
                        navigateTo(targetUrl);
                    } else if (isImage && which == 1) {
                        startSystemDownload(targetUrl, null, null, null);
                    } else if (!isImage && which == 1) {
                        openNewTab(targetUrl);
                    } else {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setText(targetUrl);
                        Toast.makeText(MainActivity.this, R.string.address_copied, Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .show();
    }

    private void resetFaviconToPlaceholder() {
        if (mFaviconView == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mFaviconView.setImageResource(R.drawable.ic_web_holo_dark);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (isHistoryViewShown()) {
            hideHistoryView();
            return;
        }
        if (isBookmarksViewShown()) {
            hideBookmarksView();
            return;
        }
        if (isTabSwitcherShown()) {
            hideTabSwitcher();
            return;
        }
        if (isFindBarShown()) {
            hideFindBar();
            return;
        }
        Object canGoBack = invokeNoArg("canGoBack");
        if (canGoBack instanceof Boolean && (Boolean) canGoBack) {
            navigateHistory(-1, "goBack");
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS) {
            /*
             * Not auto-reload on return anymore, rule/UA/userscript change
             * still apply live to next real navigate either way (see
             * applyResolvedUserAgent()/runMatchingScripts() own fresh
             * read), this just stop forcing unrequested reload of
             * whatever page user already look at. Manual refresh if they
             * want current page specific to pick up change right away.
             */
            logLocal("Returned from Settings.");
        } else if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            handleFileChooserResult(resultCode, data);
        }
    }

    /*
     * Safety-net save on top of the per-navigate/open/close save already
     * scatter through tab lifecycle method, cover anything those maybe
     * miss, and this also just normal place a browser would save state
     * before maybe get kill in background.
     */
    @Override
    protected void onPause() {
        super.onPause();
        try {
            saveOpenTabsToDisk();
        } catch (Throwable t) {
            logException("onPause.saveOpenTabsToDisk", t);
        }
    }

    /*
     * Call (via reflection) from XposedInit onShowFileChooser hook. Run
     * on whatever thread hook fire on, so hop to main thread before touch
     * Activity/start picker.
     */
    public void onShowFileChooserRequested(final Intent chooserIntent, final ValueCallback<Uri[]> callback) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                logLocal("onShowFileChooserRequested: action=" + chooserIntent.getAction()
                    + " type=" + chooserIntent.getType()
                    + " extras=" + (chooserIntent.getExtras() != null ? chooserIntent.getExtras().keySet() : "none")
                    + " allowMultiple=" + chooserIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    + " mimeTypes=" + java.util.Arrays.toString(chooserIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
                if (mPendingFileChooserCallback != null) {
                    /*
                     * previous chooser never resolve, fail it out instead of
                     * leak it or silent drop new request
                     */
                    logLocal("onShowFileChooserRequested: a previous pending callback existed - resolving it with null first.");
                    mPendingFileChooserCallback.onReceiveValue(null);
                    mPendingFileChooserCallback = null;
                }
                mPendingFileChooserCallback = callback;
                try {
                    startActivityForResult(Intent.createChooser(chooserIntent, getString(R.string.choose_file)), REQUEST_CODE_FILE_CHOOSER);
                    logLocal("onShowFileChooserRequested: startActivityForResult() succeeded, chooser should be showing.");
                } catch (Throwable t) {
                    logException("onShowFileChooserRequested.startActivityForResult", t);
                    mPendingFileChooserCallback = null;
                    callback.onReceiveValue(null);
                }
            }
        });
    }

    /*
     * Use FileChooserParams own static parseResult(int, Intent) helper
     * (confirm by smali) instead of hand-roll getData()/getClipData()
     * handling, it same helper android.webkit.WebChromeClient.
     * FileChooserParams expose, already cover single- and multi-select
     * result too.
     * Copy a picker-issue URI bytes into our own app private cache dir
     * and return FileProvider-backed content:// URI for the copy
     * instead, see note in handleFileChooserResult() for why. Best
     * effort: fall back to return original URI unchanged if anything
     * here fail, instead of silent drop the file.
     */
    private Uri copyToLocalFileProviderUri(Uri original) {
        try {
            String displayName = null;
            try {
                android.database.Cursor cursor = getContentResolver().query(original, null, null, null, null);
                if (cursor != null) {
                    try {
                        int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (nameIdx >= 0 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIdx);
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Throwable t) {
                logException("copyToLocalFileProviderUri.queryDisplayName", t);
            }
            if (TextUtils.isEmpty(displayName)) {
                displayName = "upload_" + System.currentTimeMillis();
            }

            File uploadsDir = new File(getCacheDir(), "uploads");
            if (!uploadsDir.exists()) uploadsDir.mkdirs();
            File destFile = new File(uploadsDir, System.currentTimeMillis() + "_" + displayName);

            InputStream in = getContentResolver().openInputStream(original);
            if (in == null) {
                logLocal("copyToLocalFileProviderUri: openInputStream() returned null for " + original);
                return original;
            }
            FileOutputStream out = new FileOutputStream(destFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            out.flush();
            out.close();

            Uri providerUri = SimpleFileProvider.getUriForFile(destFile);
            try {
                grantUriPermission(getPackageName(), providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                logLocal("copyToLocalFileProviderUri: grantUriPermission() succeeded for " + providerUri);
            } catch (Throwable t) {
                logException("copyToLocalFileProviderUri.grantUriPermission", t);
            }
            logLocal("copyToLocalFileProviderUri: copied " + original + " (" + destFile.length() + " bytes) -> " + providerUri);
            return providerUri;
        } catch (Throwable t) {
            logException("copyToLocalFileProviderUri", t);
            return original;
        }
    }

    private void handleFileChooserResult(int resultCode, Intent data) {
        logLocal("handleFileChooserResult: resultCode=" + resultCode
            + " data.getData()=" + (data != null ? data.getData() : "null")
            + " data.getClipData()=" + (data != null && data.getClipData() != null
                ? (data.getClipData().getItemCount() + " item(s)") : "null")
            + " pendingCallback=" + (mPendingFileChooserCallback != null));
        if (mPendingFileChooserCallback == null) {
            logLocal("handleFileChooserResult: no pending callback - nothing to resolve (chooser result arrived with nothing waiting for it?).");
            return;
        }
        Uri[] results = null;
        try {
            if (mWebView != null) {
                Class<?> fileChooserParamsClass = Class.forName(
                    "com.uc.webview.export.WebChromeClient$FileChooserParams",
                    true,
                    mWebView.getClass().getClassLoader());
                Method parseResult = fileChooserParamsClass.getMethod("parseResult", int.class, Intent.class);
                results = (Uri[]) parseResult.invoke(null, resultCode, data);
            } else {
                logLocal("handleFileChooserResult: mWebView is null - cannot resolve via parseResult().");
            }
        } catch (Throwable t) {
            logException("handleFileChooserResult.parseResult", t);
        }
        logLocal("handleFileChooserResult: parseResult() -> " + (results == null ? "null" : java.util.Arrays.toString(results)));

        /*
         * CONFIRM (2026-09-04): before fix here (grantUriPermission on
         * ORIGINAL picker-issue URI) succeed from our own process side,
         * but page still never see file, because sForceSingleProcess=false
         * (XposedInit) mean this engine run multi-process, and real file
         * read almost sure happen in separate, sandbox renderer process
         * with own UID that not inherit a grant scope to MainActivity
         * process. Copy file into our own app storage and hand back
         * FileProvider URI instead sidestep this completely, it is
         * standard, well-test Android way to share app-own file across
         * process/UID boundary, instead of rely on third-party provider
         * grant survive the hop.
         */
        if (results != null) {
            Uri[] remapped = new Uri[results.length];
            for (int i = 0; i < results.length; i++) {
                remapped[i] = results[i] != null ? copyToLocalFileProviderUri(results[i]) : null;
            }
            results = remapped;
        }

        logLocal("handleFileChooserResult: now calling onReceiveValue().");
        mPendingFileChooserCallback.onReceiveValue(results);
        mPendingFileChooserCallback = null;
        logLocal("handleFileChooserResult: onReceiveValue() call completed.");
    }

    /*
     * Call (via reflection) from XposedInit onPermissionRequest hook. Run
     * on whatever thread hook fire on, so hop to main thread before touch
     * permission/Activity API.
     */
    public void onWebViewPermissionRequested(final Object permissionRequest) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                handlePermissionRequest(permissionRequest);
            }
        });
    }

    private void handlePermissionRequest(Object permissionRequest) {
        try {
            /*
             * Ground truth for PermissionRequest shape, we only confirm
             * WebChromeClient.onPermissionRequest() self by smali, not the
             * PermissionRequest class it hand us.
             */
            StringBuilder sb = new StringBuilder("PermissionRequest class=" + permissionRequest.getClass().getName() + " methods: ");
            for (Method m : permissionRequest.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                sb.append(m.getName()).append("() ");
            }
            logLocal(sb.toString());

            Method getResources = permissionRequest.getClass().getMethod("getResources");
            final String[] resources = (String[]) getResources.invoke(permissionRequest);
            Method getOrigin = permissionRequest.getClass().getMethod("getOrigin");
            Object origin = getOrigin.invoke(permissionRequest);
            logLocal("handlePermissionRequest: origin=" + origin + " resources=" + java.util.Arrays.toString(resources));

            boolean needsCamera = false;
            boolean needsMic = false;
            if (resources != null) {
                for (String r : resources) {
                    if (r == null) continue;
                    String lower = r.toLowerCase(java.util.Locale.US);
                    if (lower.contains("video")) needsCamera = true;
                    if (lower.contains("audio")) needsMic = true;
                }
            }

            List<String> osPermissionsNeeded = new ArrayList<String>();
            if (needsCamera && needsRuntimeGrant(Manifest.permission.CAMERA)) {
                osPermissionsNeeded.add(Manifest.permission.CAMERA);
            }
            if (needsMic && needsRuntimeGrant(Manifest.permission.RECORD_AUDIO)) {
                osPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
            }

            if (osPermissionsNeeded.isEmpty()) {
                /*
                 * either not need, or already grant at OS level (on pre-M
                 * device, manifest-declare permission IS already grant at
                 * install time, so this also normal path there)
                 */
                grantPermissionRequest(permissionRequest, resources);
                return;
            }

            mPendingPermissionRequest = permissionRequest;
            requestPermissions(osPermissionsNeeded.toArray(new String[0]), REQUEST_CODE_RUNTIME_PERMISSIONS);
        } catch (Throwable t) {
            logException("handlePermissionRequest", t);
            denyPermissionRequest(permissionRequest);
        }
    }

    /*
     * Only meaning on API 23+ (Marshmallow runtime permission), on older
     * device a manifest-declare permission already grant at install time,
     * so nothing more to request.
     */
    private boolean needsRuntimeGrant(String permission) {
        return Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }

    private void grantPermissionRequest(Object permissionRequest, String[] resources) {
        try {
            Method grant = permissionRequest.getClass().getMethod("grant", String[].class);
            grant.invoke(permissionRequest, (Object) resources);
            logLocal("grantPermissionRequest: granted " + java.util.Arrays.toString(resources));
        } catch (Throwable t) {
            logException("grantPermissionRequest", t);
        }
    }

    private void denyPermissionRequest(Object permissionRequest) {
        if (permissionRequest == null) return;
        try {
            Method deny = permissionRequest.getClass().getMethod("deny");
            deny.invoke(permissionRequest);
            logLocal("denyPermissionRequest: denied.");
        } catch (Throwable t) {
            logException("denyPermissionRequest", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_RUNTIME_PERMISSIONS) return;

        Object pending = mPendingPermissionRequest;
        mPendingPermissionRequest = null;
        if (pending == null) return;

        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            try {
                Method getResources = pending.getClass().getMethod("getResources");
                String[] resources = (String[]) getResources.invoke(pending);
                grantPermissionRequest(pending, resources);
            } catch (Throwable t) {
                logException("onRequestPermissionsResult.grant", t);
                denyPermissionRequest(pending);
            }
        } else {
            denyPermissionRequest(pending);
        }
    }

    /*
     * One-time UC engine bootstrap: find UC package, sync U4 SDK dir,
     * build DexClassLoader, Instrumentation.newApplication(),
     * aerieApp.onCreate(). This used to be step 0-5 of old single-shot
     * bootExportWebView(), now split out so it only run ONCE per process,
     * no matter how many tab get open. Fill mUcClassLoader/
     * mExportWebViewClass and lock the core type, every next tab just
     * call the much cheaper createTabWebView(). onReady post to main
     * thread once init finish (or right away, if already init).
     */
    private void initUcEngineOnce(final Runnable onReady) {
        if (mEngineInitialized) {
            logLocal("initUcEngineOnce: already initialized, skipping straight to onReady.");
            mMainHandler.post(onReady);
            return;
        }
        if (mEngineInitFailed) {
            /*
             * Already establish this can not come up (see
             * BOOT_CRITICAL_FAILURE catch below), not spin up another
             * background thread to fail same way again, just re-show the
             * explain.
             */
            logLocal("initUcEngineOnce: engine already failed previously - re-showing failure dialog instead of retrying.");
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showEngineFailureDialog(classifyEngineFailure());
                }
            });
            return;
        }
        logLocal("=== UcKitKatWebview: initializing UC engine (one-time) ===");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    logLocal("Step 0: Locating UC Package...");
                    final ApplicationInfo ucInfo = getPackageManager().getApplicationInfo("com.UCMobile", 0);

                    logLocal("Step 1: Synchronizing Full U4 SDK environment...");
                    final File nativeLibDir = synchronizeU4SdkStructure(ucInfo);

                    logLocal("Step 1.5: Explicitly loading UC's bundled libc++_shared.so...");
                    try {
                        File ucCppShared = new File("/data/app-lib/com.UCMobile-1/libc++_shared.so");
                        if (ucCppShared.exists()) {
                            System.load(ucCppShared.getAbsolutePath());
                            logLocal("SUCCESS: Loaded " + ucCppShared.getAbsolutePath());
                        }
                    } catch (Throwable t) {
                        logException("Load UC's libc++_shared.so", t);
                    }

                    logLocal("Step 2: Constructing Full MultiDex ClassLoader...");
                    final ClassLoader ucCl = createFullUcClassLoader(ucInfo, nativeLibDir);
                    mUcClassLoader = ucCl;

                    logLocal("Step 3: Triggering Xposed Framework Patches...");
                    applyCorePatches(ucCl, MainActivity.this);

                    logLocal("Step 3.5: Syncing modulelisting directory...");
                    try {
                        File srcModuleListing = new File("/data/data/com.UCMobile/modulelisting");
                        File dstModuleListing = new File(getApplicationInfo().dataDir, "modulelisting");
                        if (srcModuleListing.exists()) {
                            copyDirRecursive(srcModuleListing, dstModuleListing);
                            logLocal("SUCCESS: Synced modulelisting.");
                        }
                    } catch (Throwable t) {
                        logException("Sync modulelisting", t);
                    }

                    logLocal("Step 4: Instrumentation.newApplication(ucCl, \"com.uc.browser.UCAerieApplication\", context)...");
                    Instrumentation instrumentation = new Instrumentation();
                    Context ucClassLoaderContext = new ContextWrapper(getApplicationContext()) {
                        @Override
                        public ClassLoader getClassLoader() {
                            return ucCl;
                        }
                    };
                    Application aerieApp = instrumentation.newApplication(
                        ucCl, "com.uc.browser.UCAerieApplication", ucClassLoaderContext);
                    logLocal("SUCCESS: Instrumentation.newApplication() returned " + aerieApp);

                    logLocal("Step 5: Calling aerieApp.onCreate()...");
                    aerieApp.onCreate();
                    logLocal("SUCCESS: aerieApp.onCreate() returned cleanly!");

                    logLocal("Step 6: Posting one-time core-type lock + class lookup to main thread...");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                logLocal("Locking Factory Core Selector to U4 (d.a(3, 1))...");
                                Class<?> dClass = ucCl.loadClass("com.uc.webview.internal.d");
                                Method setCoreType = dClass.getDeclaredMethod("a", int.class, int.class);
                                setCoreType.setAccessible(true);
                                setCoreType.invoke(null, 3, 1);
                                logLocal("Locked core selector to 3 via d.a(3, 1)");

                                mExportWebViewClass = ucCl.loadClass("com.uc.webview.export.WebView");
                                logLocal("Cached export.WebView class for reuse by every future tab.");

                                try {
                                    Method setDebug = mExportWebViewClass.getDeclaredMethod("setWebContentsDebuggingEnabled", boolean.class);
                                    setDebug.setAccessible(true);
                                    setDebug.invoke(null, true);
                                    logLocal("SUCCESS: setWebContentsDebuggingEnabled(true).");
                                } catch (Throwable t) {
                                    logException("setWebContentsDebuggingEnabled", t);
                                }

                                mEngineInitialized = true;
                                logLocal("=== UC engine initialization complete - ready for tabs ===");
                                onReady.run();
                            } catch (Throwable t) {
                                logException("Core-type lock / class lookup", t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    logException("BOOT_CRITICAL_FAILURE", t);
                    dumpAllActiveThreads();
                    checkAndDumpTombstones();
                    mEngineInitFailed = true;
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showEngineFailureDialog(classifyEngineFailure());
                        }
                    });
                }
            }
        }, "uc-engine-init-thread").start();
    }


    /*
     * The cheap, repeatable per-tab half of what used to be
     * bootExportWebView() step 6, construct one export.WebView instance
     * and attach all same client/listener every tab need. Must call on
     * main thread, and only after mEngineInitialized true (see
     * initUcEngineOnce()). On purpose not attach result to mWebFrame or
     * navigate anywhere, caller (openNewTab()/switchToTab()) own that,
     * since freshly create tab not always the one show right away.
     * isIncognito only control this ONE WebView instance own WebSettings
     * (cache mode / DOM storage / database / autofill), every one of
     * those is really per-instance setting (confirm via WebSettings.smali:
     * setCacheMode/setAppCacheEnabled/setDatabaseEnabled/
     * setDomStorageEnabled/setSaveFormData/setSavePassword all live on
     * instance own WebSettings object, not anything global), so flip them
     * here can not affect any other tab, incognito or not.
     *
     * On purpose not touch cookie. This engine cookie handling not wire
     * up anywhere in this project yet (see NOTE at startSystemDownload()
     * cookie comment) and, per TabManager.java own 2026-09-04 history
     * note, CookieManager already confirm by smali is single process-wide
     * singleton with no per-tab isolation possible. No method on this
     * engine public WebSettings/WebView surface that scope cookie
     * accept/reject or clear to one instance only, only global one.
     * Call any global cookie-clear/block API from here to make incognito
     * "more private" would also blank or block cookie for every already-
     * open normal tab, exactly the outcome you ask this NOT to cause. So:
     * incognito tab here get private cache/storage/history, but still
     * share same cookie jar like your normal tab (already-login site stay
     * login, anything it set still there after incognito tab close), same
     * limit lot of embed-WebView "incognito" implementation quietly have.
     * Real cookie isolation need second, fully separate engine instance,
     * same native-process-level change TabManager.java note already flag
     * as bigger job than simple additive setting change.
     */
    private View createTabWebView(final boolean isIncognito) {
        try {
            logLocal("createTabWebView: constructing new export.WebView (incognito=" + isIncognito + ")...");
            Constructor<?> ctor = mExportWebViewClass.getConstructor(Context.class);
            mBridgesSeenDuringCurrentConstruction.clear();
            Object exportWebViewInstance = ctor.newInstance(MainActivity.this);
            for (Object bridge : mBridgesSeenDuringCurrentConstruction) {
                mBridgeToWebView.put(bridge, exportWebViewInstance);
                logLocal("createTabWebView: correlated bridge " + System.identityHashCode(bridge) + " -> this tab's WebView.");
            }
            mBridgesSeenDuringCurrentConstruction.clear();
            logLocal("SUCCESS: Constructed export.WebView: " + exportWebViewInstance);

            if (!(exportWebViewInstance instanceof View)) {
                logLocal("createTabWebView: NOTE: constructed object is not a View (" + exportWebViewInstance.getClass().getName() + ") - unexpected.");
                return null;
            }
            final View webViewAsView = (View) exportWebViewInstance;

            try {
                Method getSettings = mExportWebViewClass.getMethod("getSettings");
                Object settings = getSettings.invoke(exportWebViewInstance);
                if (settings != null) {
                    invokeSetter(settings, "setJavaScriptEnabled", true);
                    invokeSetter(settings, "setDomStorageEnabled", true);
                    invokeSetter(settings, "setUseWideViewPort", true);
                    invokeSetter(settings, "setLoadWithOverviewMode", true);

                    /*
                     * Pinch-to-zoom need BOTH setSupportZoom AND
                     * setBuiltInZoomControls, setSupportZoom alone not
                     * enough for multi-touch gesture self to register on
                     * stock-shape WebView/WebSettings surface like this
                     * one, that combination just never get call at all
                     * before (this engine not default it on), that why
                     * pinch-zoom not work before. setDisplayZoomControls
                     * stay false no matter mZoomEnabled, that one only
                     * control on-screen +/- widget, not pinch gesture, and
                     * we not want that widget either way.
                     */
                    invokeSetter(settings, "setSupportZoom", mZoomEnabled);
                    invokeSetter(settings, "setBuiltInZoomControls", mZoomEnabled);
                    invokeSetter(settings, "setDisplayZoomControls", false);

                    /*
                     * Need both for window.open()/target="_blank" to ever
                     * reach WebChromeClient.onCreateWindow at all -
                     * without setSupportMultipleWindows(true) engine just
                     * silent no-op the whole thing, never call hook.
                     * setJavaScriptCanOpenWindowsAutomatically(true) on
                     * top so a script-call window.open() (no click behind
                     * it) also reach hook, not just target="_blank" link
                     * tap - MainActivity.onAnyPageStarted() confirm
                     * dialog is the real gate either way, engine-level
                     * "automatically" here just mean "callback fire at
                     * all", not "skip confirm".
                     */
                    invokeSetter(settings, "setSupportMultipleWindows", true);
                    invokeSetter(settings, "setJavaScriptCanOpenWindowsAutomatically", true);

                    if (isIncognito) {
                        try {
                            /*
                             * LOAD_NO_CACHE = 2 (android.webkit.WebSettings
                             * constant, this engine export.WebSettings
                             * mirror same int value). Stop this instance
                             * from ever write to shared HTTP cache in first
                             * place, that also why closeTab() below never
                             * need to (and must not) call instance-level
                             * clearCache(true), that call clear the on-disk
                             * cache share by every WebView in process, not
                             * just caller own.
                             */
                            invokeIntSetter(settings, "setCacheMode", 2);
                            invokeSetter(settings, "setAppCacheEnabled", false);
                            invokeSetter(settings, "setDatabaseEnabled", false);
                            invokeSetter(settings, "setDomStorageEnabled", false);
                            invokeSetter(settings, "setSaveFormData", false);
                            invokeSetter(settings, "setSavePassword", false);
                            logLocal("createTabWebView: incognito WebSettings applied (no cache/appcache/db/domstorage/autofill).");
                        } catch (Throwable t) {
                            logException("createTabWebView: Configure incognito WebSettings", t);
                        }
                    }
                    logLocal("createTabWebView: WebSettings configured.");

                    /*
                     * Only capture once, from first tab ever create, it
                     * same engine default no matter which tab ask.
                     */
                    if (mDefaultUserAgent == null) {
                        try {
                            Method getUa = settings.getClass().getMethod("getUserAgentString");
                            mDefaultUserAgent = (String) getUa.invoke(settings);
                            logLocal("Captured engine default UA (first tab only): " + mDefaultUserAgent);
                        } catch (Throwable t) {
                            logException("Capture default UA", t);
                        }
                    }
                }
            } catch (Throwable t) {
                logException("createTabWebView: Configure WebSettings", t);
            }

            try {
                Class<?> chromeClientClass = mUcClassLoader.loadClass("com.uc.webview.export.WebChromeClient");
                Object chromeClientInstance = chromeClientClass.getConstructor().newInstance();
                Method setWebChromeClient = mExportWebViewClass.getMethod("setWebChromeClient", chromeClientClass);
                setWebChromeClient.invoke(exportWebViewInstance, chromeClientInstance);
                logLocal("createTabWebView: registered blank WebChromeClient.");
            } catch (Throwable t) {
                logException("createTabWebView: Register WebChromeClient", t);
            }
            try {
                Class<?> viewClientClass = mUcClassLoader.loadClass("com.uc.webview.export.WebViewClient");
                Object viewClientInstance = viewClientClass.getConstructor().newInstance();
                Method setWebViewClient = mExportWebViewClass.getMethod("setWebViewClient", viewClientClass);
                setWebViewClient.invoke(exportWebViewInstance, viewClientInstance);
                logLocal("createTabWebView: registered blank WebViewClient.");
            } catch (Throwable t) {
                logException("createTabWebView: Register WebViewClient", t);
            }

            /*
             * DownloadListener is a plain interface (unlike WebChromeClient/
             * WebViewClient, which are concrete classes we have to hook instead
             * of subclass) - java.lang.reflect.Proxy lets us implement a
             * ucCl-loaded interface directly from our own compiled code, no
             * Xposed hook needed for this one. One proxy per tab, since
             * setDownloadListener is an instance-level call.
             */
            try {
                final Class<?> downloadListenerClass = mUcClassLoader.loadClass("com.uc.webview.export.DownloadListener");
                Object downloadListenerProxy = Proxy.newProxyInstance(
                    mUcClassLoader,
                    new Class<?>[]{downloadListenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("toString".equals(name)) return "EvoDownloadListenerProxy";
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);

                            logLocal("DownloadListener proxy invoked: " + name + "(" + (args == null ? 0 : args.length) + " args)"
                                + (args == null ? "" : " -> " + java.util.Arrays.toString(args)));

                            if ("onDownloadStart".equals(name) && args != null && args.length >= 4) {
                                final String url = args[0] == null ? null : String.valueOf(args[0]);
                                final String userAgent = args[1] == null ? null : String.valueOf(args[1]);
                                final String contentDisposition = args[2] == null ? null : String.valueOf(args[2]);
                                final String mimeType = args[3] == null ? null : String.valueOf(args[3]);
                                mMainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        startSystemDownload(url, userAgent, contentDisposition, mimeType);
                                    }
                                });
                            }
                            return null;
                        }
                    }
                );
                Method setDownloadListener = mExportWebViewClass.getMethod("setDownloadListener", downloadListenerClass);
                setDownloadListener.invoke(exportWebViewInstance, downloadListenerProxy);
                logLocal("createTabWebView: registered DownloadListener proxy.");
            } catch (Throwable t) {
                logException("createTabWebView: Register DownloadListener", t);
            }

            /*
             * Find-in-page. FindListener is plain interface (confirm by
             * smali, same as DownloadListener), same Proxy trick. Only
             * ever matter for whichever tab really visible, since
             * findAllAsync()/findNext() only ever call on mWebView (active
             * tab) in first place, no cross-tab correlate need here,
             * unlike navigate/favicon callback.
             */
            try {
                final Class<?> findListenerClass = mUcClassLoader.loadClass("com.uc.webview.export.WebView$FindListener");
                try {
                    StringBuilder sb = new StringBuilder("FindListener interface shape: ");
                    for (Method dm : findListenerClass.getMethods()) {
                        sb.append(dm.getName()).append("(").append(dm.getParameterTypes().length).append("args) ");
                    }
                    logLocal(sb.toString());
                } catch (Throwable ignored) {}
                Object findListenerProxy = Proxy.newProxyInstance(
                    mUcClassLoader,
                    new Class<?>[]{findListenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("toString".equals(name)) return "EvoFindListenerProxy";
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);

                            if ("onFindResultReceived".equals(name) && args != null && args.length >= 3) {
                                final int activeMatchOrdinal = (Integer) args[0];
                                final int numberOfMatches = (Integer) args[1];
                                mMainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateFindMatchCount(activeMatchOrdinal, numberOfMatches);
                                    }
                                });
                            } else {
                                logLocal("FindListener proxy invoked: " + name + "(" + (args == null ? 0 : args.length) + " args)");
                            }
                            return null;
                        }
                    }
                );
                Method setFindListener = mExportWebViewClass.getMethod("setFindListener", findListenerClass);
                setFindListener.invoke(exportWebViewInstance, findListenerProxy);
                logLocal("createTabWebView: registered FindListener proxy.");
            } catch (Throwable t) {
                logException("createTabWebView: Register FindListener", t);
            }

            /*
             * Long-press image/link menu, set setLongClickable(true)
             * explicit since export.WebView own setOnLongClickListener()
             * override forward straight to internal engine instead of
             * call through View own implementation, which is what normal
             * flip this flag on automatic. Only the ACTIVE tab listener
             * can ever really fire in practice, since background tab not
             * attach to mWebFrame so never receive touch event,
             * handleWebViewLongPress() work on mWebView field
             * ("whichever tab is on screen") is safe as is.
             */
            webViewAsView.setLongClickable(true);
            webViewAsView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    logLocal("WebView onLongClick fired.");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleWebViewLongPress();
                        }
                    });
                    return true;
                }
            });

            /*
             * Feed shouldSuppressAutoKeyboard() below, return false (not
             * consume) so touch still flow through to WebView own handling
             * completely normal, this purely just observer.
             */
            webViewAsView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        mLastGenuineTouchTimeMs = System.currentTimeMillis();
                    }
                    return false;
                }
            });

            logLocal("createTabWebView: done.");
            return webViewAsView;
        } catch (Throwable t) {
            logException("createTabWebView", t);
            return null;
        }
    }

    /*
     * Call when 3-dot menu "Can zoom" checkbox toggle. Walk every tab that
     * really have WebView materialize (still-dormant restore tab, see
     * TabManager.Tab webView==null note, have nothing to update, it will
     * pick up current mZoomEnabled value from createTabWebView() self
     * whenever lazy create) and flip setSupportZoom/setBuiltInZoomControls
     * on each own WebSettings instance direct, these are per-WebView
     * setting, so this reach background tab too, not just whichever one
     * on screen right now.
     */
    private void applyZoomSettingToAllTabs() {
        for (TabManager.Tab tab : mTabManager.tabs) {
            if (tab.webView == null) continue;
            try {
                Method getSettings = tab.webView.getClass().getMethod("getSettings");
                Object settings = getSettings.invoke(tab.webView);
                if (settings == null) continue;
                invokeSetter(settings, "setSupportZoom", mZoomEnabled);
                invokeSetter(settings, "setBuiltInZoomControls", mZoomEnabled);
            } catch (Throwable t) {
                logException("applyZoomSettingToAllTabs", t);
            }
        }
        logLocal("applyZoomSettingToAllTabs: mZoomEnabled=" + mZoomEnabled + " applied to " + mTabManager.tabs.size() + " tab(s).");
    }

    /*
     * Entry point at app start, init engine once, then either restore tab
     * from previous session (lazy, see restoreSavedTabs()) or open single
     * fresh tab if nothing save (first-ever launch, or save list empty).
     */
    private void bootExportWebView() {
        initUcEngineOnce(new Runnable() {
            @Override
            public void run() {
                List<String> savedUrls = SettingsStore.loadOpenTabUrls(MainActivity.this);
                if (savedUrls.isEmpty()) {
                    /*
                     * Before just plain openNewTab() here, which always start
                     * from SettingsStore.getDefaultUrl(), silently ignore
                     * "Load last URL" whenever no save tab session to
                     * restore (first-ever launch, or every tab close last
                     * time). That also what peekInitialAddressBarUrl() fall
                     * back compute for SAME "no save tabs" case, so pass its
                     * result here instead keep whatever address bar already
                     * show and what really load in agree.
                     */
                    logLocal("bootExportWebView: no saved tabs - opening a fresh one at resolveInitialUrl()=" + resolveInitialUrl());
                    openNewTab(resolveInitialUrl());
                } else {
                    int savedActiveIndex = SettingsStore.getActiveTabIndex(MainActivity.this);
                    restoreSavedTabs(savedUrls, savedActiveIndex);
                }
                /*
                 * openNewTab()/restoreSavedTabs() above already sync
                 * kick off first real navigateTo() by this point, this
                 * match "until engine init AND site start loading" instead
                 * of dismiss right as engine become ready but before
                 * anything really loading yet.
                 */
                hideLoadingDialog();
            }
        });
    }

    /*
     * Full-screen, fully opaque overlay show from moment onCreate() finish
     * build UI until engine init and first page load really start, cover
     * the multi-second gap where empty WebView frame would just sit blank
     * otherwise.
     */
	
	/*
    private void showLoadingOverlay() {
        ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.parseColor("#FF121212"));
        overlay.setClickable(true); // block touch to whatever under while show

        ProgressBar spinner = new ProgressBar(this); // default platform spinner, indeterminate
        overlay.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText(R.string.loading_engine_message);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setPadding(0, dpToPx(16), 0, 0);
        overlay.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLoadingOverlay = overlay;
    }

    private void hideLoadingOverlay() {
        if (mLoadingOverlay == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mLoadingOverlay == null) return;
                ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
                root.removeView(mLoadingOverlay);
                mLoadingOverlay = null;
            }
        });
    }
	*/
	private void showLoadingDialog() {
		// dismiss existing instance first, avoid window leak
		hideLoadingDialog();

		// use THEME_HOLO_DARK (or THEME_HOLO_LIGHT) to match Android 4.4 system dialog
		mLoadingDialog = new ProgressDialog(this, ProgressDialog.THEME_HOLO_DARK);
		
		mLoadingDialog.setTitle(R.string.loading_engine_title); // example "Shutting down" / "Loading"
		mLoadingDialog.setMessage(getString(R.string.loading_engine_message));
		mLoadingDialog.setIndeterminate(true);

		// stop close by back button
		mLoadingDialog.setCancelable(false);
		
		// stop close by tap outside dialog window
		mLoadingDialog.setCanceledOnTouchOutside(false);

		mLoadingDialog.show();
	}

	private void hideLoadingDialog() {
		if (mLoadingDialog != null && mLoadingDialog.isShowing()) {
			mLoadingDialog.dismiss();
			mLoadingDialog = null;
		}
	}

    private static final int ENGINE_FAIL_XPOSED_NOT_INSTALLED = 0;
    private static final int ENGINE_FAIL_MODULE_NOT_ENABLED = 1;
    private static final int ENGINE_FAIL_U4_INIT_FAILED = 2;

    /*
     * Tell WHY initUcEngineOnce() background thread hit
     * BOOT_CRITICAL_FAILURE, so showEngineFailureDialog() can tell user
     * something really actionable instead of generic "it broke".
     *
     * de.robv.android.xposed.XposedBridge is compileOnly dependency (see
     * build.gradle), it is NOT package into this app own dex. At runtime
     * it only resolve at all if real, active Xposed/LSPosed framework
     * inject it into this process, which happen for every hook process
     * once framework active on device, independent of whether this
     * specific module enable for this app. So:
     * - unresolvable completely -> no Xposed/LSPosed framework active on
     * device at all.
     * - resolvable, but mXposedModuleActive still false -> framework IS
     * active, but this module hook on applyCorePatches() never fire for
     * this process (module uncheck in manager, or scope not include
     * this app, or need reboot/soft-reboot after freshly enable).
     * - resolvable AND mXposedModuleActive true -> hook side of thing
     * fully confirm working, whatever really throw inside try block
     * (missing/incompatible UCMobile install, U4 sync fail,
     * aerieApp.onCreate() crash, etc) is genuine UC core init fail, not
     * Xposed setup problem.
     */
    private int classifyEngineFailure() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
        } catch (Throwable t) {
            return ENGINE_FAIL_XPOSED_NOT_INSTALLED;
        }
        if (!mXposedModuleActive) {
            return ENGINE_FAIL_MODULE_NOT_ENABLED;
        }
        return ENGINE_FAIL_U4_INIT_FAILED;
    }

    /*
     * Replace loading dialog with explain, not-easy-dismiss-by-accident
     * warning once engine confirm never coming up (see
     * BOOT_CRITICAL_FAILURE catch in initUcEngineOnce()). Nothing useful
     * app can do from this state, every entry point
     * (openNewTab()/switchToTab()/etc) only ever get here by first defer
     * behind initUcEngineOnce(), which now short-circuit back to this same
     * dialog on any further call (see mEngineInitFailed) instead of
     * retry, so OK just close the app instead of leave it sit on
     * permanently broken, tab-less screen.
     */
    private void showEngineFailureDialog(int reason) {
        hideLoadingDialog();
        String message;
        switch (reason) {
            case ENGINE_FAIL_XPOSED_NOT_INSTALLED:
                message = getString(R.string.loading_engine_fail_xposedinit);
                break;
            case ENGINE_FAIL_MODULE_NOT_ENABLED:
                message = getString(R.string.loading_engine_fail_xposednotenable);
                break;
            default:
                message = getString(R.string.loading_engine_fail_u4fail);
                break;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.loading_engine_fail_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            })
            .show();
    }

    /*
     * Recreate the tab LIST from previous session without construct any
     * WebView instance yet (see TabManager.Tab webView field / note top of
     * class), each restore Tab start with webView == null and only get
     * materialize when user really select it (switchToTab() lazy part).
     * savedActiveIndex is whichever tab really on screen when session
     * last save (see saveOpenTabsToDisk()), that the one switch to right
     * away (and so the one that get load right away), match what real
     * browser reopen to last page look like, while rest stay dormant.
     * Fall back to index 0 if save index out of range for any reason
     * (list shrink, corrupt value, etc).
     */
    private void restoreSavedTabs(List<String> savedUrls, int savedActiveIndex) {
        logLocal("restoreSavedTabs: restoring " + savedUrls.size() + " saved tab(s), savedActiveIndex=" + savedActiveIndex + " (lazy - not loading WebViews until selected).");
        for (String url : savedUrls) {
            TabManager.Tab tab = new TabManager.Tab();
            tab.url = url;
            mTabManager.tabs.add(tab);
        }
        int indexToActivate = savedActiveIndex;
        if (indexToActivate < 0 || indexToActivate >= mTabManager.tabs.size()) {
            logLocal("restoreSavedTabs: savedActiveIndex out of range, falling back to 0.");
            indexToActivate = 0;
        }
        switchToTab(mTabManager.tabs.get(indexToActivate));
    }

    /*
     * Save the order list of tab URL plus which one active (not full Tab
     * state, see SettingsStore.saveOpenTabUrls() note). Call on every tab
     * open/close/navigate/switch, plus final safety-net save in onPause(),
     * cheap enough (tiny JSON, async apply()) to call this liberal instead
     * of try track exact which mutation "count".
     */
    private void saveOpenTabsToDisk() {
        /*
         * Incognito tab never write here, see TabManager.Tab.incognito
         * note. That mean save list index no longer line up 1:1 with
         * mTabManager.tabs, so active index must recompute against the
         * FILTERED list, not read straight from mTabManager.activeIndex.
         * If tab currently on screen happen to be incognito,
         * filteredActiveIndex stay -1 and
         * restoreSavedTabs()/getActiveTabIndex() fall back to its
         * existing "out of range -> 0" behavior on next launch, mean app
         * reopen to first save normal tab, never to incognito one.
         */
        List<String> urls = new ArrayList<String>();
        TabManager.Tab active = mTabManager.activeTab();
        int filteredActiveIndex = -1;
        for (TabManager.Tab t : mTabManager.tabs) {
            if (t.incognito) continue;
            urls.add(t.url == null ? "" : t.url);
            if (t == active) filteredActiveIndex = urls.size() - 1;
        }
        SettingsStore.saveOpenTabUrls(this, urls);
        SettingsStore.setActiveTabIndex(this, filteredActiveIndex);
        logLocal("saveOpenTabsToDisk: saved " + urls.size() + " non-incognito tab(s), filteredActiveIndex=" + filteredActiveIndex);
    }

    /*
     * Open new tab and switch to it right away (match how mobile browser
     * behave, new tab not left in background). Safe to call before engine
     * ready, it will queue behind initUcEngineOnce().
     */
    private void openNewTab() {
        openNewTab(null, false);
    }

    /*
     * explicitStartUrl let caller (example: open bookmark in new tab) skip
     * straight to specific URL instead of default/home page, avoid
     * pointless double-navigate (default page, then right away
     * overwritten) that separate navigateTo() call right after would
     * cause.
     */
    private void openNewTab(final String explicitStartUrl) {
        openNewTab(explicitStartUrl, false);
    }

    /*
     * Convenience entry point for tab switcher Incognito mode, see
     * buildTabSwitcherPanel(). Always start blank (no explicitStartUrl),
     * incognito tab never carry inherit destination.
     */
    private void openNewIncognitoTab() {
        openNewTab(null, true);
    }

    private void openNewTab(final String explicitStartUrl, final boolean incognito) {
        logLocal("openNewTab(explicitStartUrl=" + explicitStartUrl + ", incognito=" + incognito + ") requested.");
        if (!mEngineInitialized) {
            logLocal("openNewTab: engine not ready yet, deferring behind initUcEngineOnce().");
            initUcEngineOnce(new Runnable() {
                @Override
                public void run() {
                    openNewTab(explicitStartUrl, incognito);
                }
            });
            return;
        }
        View webViewAsView = createTabWebView(incognito);
        if (webViewAsView == null) {
            Toast.makeText(this, R.string.tab_create_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        TabManager.Tab tab = new TabManager.Tab(webViewAsView);
        tab.incognito = incognito;
        mTabManager.tabs.add(tab);
        logLocal("openNewTab: created tab #" + (mTabManager.tabs.size() - 1) + " (total=" + mTabManager.tabs.size() + ", incognito=" + incognito + ")");
        switchToTab(tab);

        /*
         * On purpose NOT resolveInitialUrl(), that one honor "load last
         * URL", this is boot-time-only concept (restore where app was
         * when you last close it). Brand new tab should always open to
         * the really configure home/default page, never wherever another
         * tab happen to be, that was the bug: every "+" tap was land on
         * last-browse URL instead. Incognito tab never honor "load last
         * URL" either way (see openNewIncognitoTab()), since
         * resolveInitialUrl() not call here at all.
         */
        String startUrl = !TextUtils.isEmpty(explicitStartUrl) ? explicitStartUrl : SettingsStore.getDefaultUrl(this);
        if (TextUtils.isEmpty(startUrl)) {
            startUrl = DEFAULT_TARGET_URL;
        }
        navigateTo(startUrl);
        saveOpenTabsToDisk();
    }

    /*
     * Detach whichever tab currently attach to mWebFrame (if any) and
     * attach this one instead. This whole reason mWebView stay plain
     * reassignable field instead of something tab-scope, every existing
     * method that already read mWebView (navigateTo,
     * applyResolvedUserAgent, handleWebViewLongPress, download listener,
     * permission handling, file chooser, etc) keep working completely
     * unmodified, since as far as they concern there still just "the"
     * WebView, it just now mean "whichever tab is on screen".
     *
     * Also handle lazy materialize: tab restore from save session (see
     * restoreSavedTabs()) have webView == null until moment it really
     * select, that handle here, right before attach it, instead of need
     * separate code path.
     * Capture small, downscale snapshot of tab current render content
     * into its own thumbnail field. RGB_565 (no alpha need) and right
     * away downscale to nav_tab_width/height keep memory use bound,
     * holding full-resolution screenshot for every open tab would add up
     * fast on a phone.
     */
    private void captureThumbnail(TabManager.Tab tab) {
        if (tab == null || tab.webView == null || !(tab.webView instanceof View)) return;
        try {
            View v = (View) tab.webView;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(full);
            v.draw(canvas);
            int thumbW = (int) getResources().getDimension(R.dimen.nav_tab_width);
            int thumbH = (int) getResources().getDimension(R.dimen.nav_tab_height);
            /*
             * Before scale direct to thumbW x thumbH, which force exact
             * target aspect ratio no matter source real one, stretch/
             * distort the image. nav_tab_view.xml tab_view ImageView
             * already have scaleType="centerCrop" to crop-to-fill proper,
             * but that only work if bitmap it give really COVER the
             * target box at right aspect ratio, a bitmap already force-
             * stretch to exact target size leave nothing for centerCrop
             * to crop. Scale uniform (same factor both axis) to cover the
             * box, then let ImageView centerCrop handle the real fit/crop,
             * is the correct "fill without stretch" behavior.
             */
            float scale = Math.max((float) thumbW / w, (float) thumbH / h);
            int scaledW = Math.round(w * scale);
            int scaledH = Math.round(h * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(full, scaledW, scaledH, true);
            full.recycle();
            if (tab.thumbnail != null) tab.thumbnail.recycle();
            tab.thumbnail = scaled;
        } catch (Throwable t) {
            logException("captureThumbnail", t);
        }
    }

    private void switchToTab(final TabManager.Tab tab) {
        if (tab == null) return;
        logLocal("switchToTab: url=" + tab.url + " loaded=" + (tab.webView != null));

        /*
         * Capture the OUTGOING tab current on-screen content into its own
         * thumbnail field, before detach it, this what tab switcher grid
         * show instead of generic favicon. Standard View.draw(Canvas)
         * technique, work for any View including WebView render content,
         * no special engine API need.
         */
        TabManager.Tab outgoingTab = mTabManager.activeTab();
        if (outgoingTab != null && outgoingTab != tab) {
            captureThumbnail(outgoingTab);
        }

        if (mWebView != null && mWebView.getParent() == mWebFrame) {
            mWebFrame.removeView(mWebView);
        }

        /*
         * Track whether THIS call really have to construct WebView
         * (restore-but-never-load tab), only that case need real
         * navigateTo(). Before this check by "tab.webView != null" AFTER
         * lazy part above already assign it, which make check always
         * true and force needless reload on EVERY tab switch, including
         * already-load one. That extra reload async complete could race
         * with next tab switch and, via onUpdateUrl hook (which have no
         * bridge correlation and always assume it for foreground tab),
         * stomp address bar of whichever tab REALLY on screen by time it
         * finish load, that what was show "the previous tab URL" after
         * switch.
         */
        boolean justMaterialized = false;

        if (tab.webView == null) {
            if (!mEngineInitialized) {
                logLocal("switchToTab: engine not ready yet for lazy load, deferring.");
                initUcEngineOnce(new Runnable() {
                    @Override
                    public void run() {
                        switchToTab(tab);
                    }
                });
                return;
            }
            logLocal("switchToTab: lazily materializing WebView for restored tab url=" + tab.url + " incognito=" + tab.incognito);
            View webViewAsView = createTabWebView(tab.incognito);
            if (webViewAsView == null) {
                Toast.makeText(this, R.string.tab_load_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            tab.webView = webViewAsView;
            justMaterialized = true;
        }

        mWebView = tab.webView instanceof View ? (View) tab.webView : null;
        if (mWebView != null) {
            mWebFrame.addView(mWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
        mTabManager.activeIndex = mTabManager.indexOf(tab);
        mCurrentUrl = tab.url;
        syncBookmarkStarIcon(tab.url);
        if (mUrlInput != null) {
            mUrlInput.setText(tab.url);
        }
        if (mFaviconView != null) {
            if (tab.favicon != null) {
                mFaviconView.setImageBitmap(tab.favicon);
            } else {
                resetFaviconToPlaceholder();
            }
        }
        if (!TextUtils.isEmpty(tab.url)) {
            applyBottomBarVisibilityForUrl(tab.url);
            updateSecurityBadge(tab.url);
            applyResolvedUserAgent(tab.url);
            if (justMaterialized) {
                /*
                 * its WebView just construct and never load anything, this
                 * the one case that really need it
                 */
                navigateTo(tab.url);
            }
        }
        updateTabCountText();
        /*
         * Save activeIndex even when just switch without navigate,
         * otherwise "which tab was on screen" would only get save as side
         * effect of navigateTo(), and just switch tab without load
         * anything new would silently lost on restart.
         */
        saveOpenTabsToDisk();
    }

    /*
     * Tear down one tab: destroy() its WebView (confirm present by
     * smali) to release native/compositor resource if it ever load (a
     * still-dormant restore tab have webView == null, nothing to
     * destroy), remove it from list, and, if it was the active tab,
     * switch to whatever left, or open fresh tab if that was the very
     * last one open anywhere.
     */
    private void closeTab(TabManager.Tab tab) {
        if (tab == null) return;
        logLocal("closeTab: url=" + tab.url);
        boolean wasActive = (mTabManager.activeTab() == tab);
        int removedIndex = mTabManager.tabs.indexOf(tab);
        mTabManager.tabs.remove(tab);

        try {
            if (tab.webView != null) {
                Method destroy = tab.webView.getClass().getMethod("destroy");
                destroy.invoke(tab.webView);
                logLocal("closeTab: destroy()ed WebView instance.");
            }
        } catch (Throwable t) {
            logException("closeTab.destroy", t);
        }

        if (wasActive) {
            if (mWebView != null && mWebView.getParent() == mWebFrame) {
                mWebFrame.removeView(mWebView);
            }
            mWebView = null;
            if (!mTabManager.tabs.isEmpty()) {
                int nextIndex = Math.min(removedIndex, mTabManager.tabs.size() - 1);
                switchToTab(mTabManager.tabs.get(nextIndex));
            } else {
                logLocal("closeTab: no tabs left - opening a fresh tab.");
                openNewTab();
            }
        }
        syncTabSwitcherList();
        updateTabCountText();
        saveOpenTabsToDisk();
    }

    private void updateTabCountText() {
        if (mTabCountText == null) return;
        mTabCountText.setText(String.valueOf(mTabManager.tabs.size()));
    }

    /*
     * GridView adapter read mTabManager.tabs LIVE via getCount()/
     * getItem(), no internal copy of own at all, unlike the old
     * ListView/ArrayAdapter version, so this just tell it re-query and
     * redraw, nothing to keep sync manual.
     */
    private void syncTabSwitcherList() {
        if (mTabSwitcherAdapter != null) {
            mTabSwitcherAdapter.notifyDataSetChanged();
        }
    }

    /*
     * Same highlight style use for RadioGroup-free segment look: active
     * tab solid-ish, inactive transparent. Value match the two Button
     * background/text color set inline in nav_screen.xml, so this only
     * ever need swap which one look "pressed".
     */
    private void updateModeButtonsHighlight() {
        if (mModeBtnStandard == null || mModeBtnIncognito == null) return;
        if (mTabSwitcherShowingIncognito) {
            mModeBtnStandard.setBackgroundColor(Color.TRANSPARENT);
            mModeBtnStandard.setTextColor(Color.parseColor("#AAAAAA"));
            mModeBtnIncognito.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            mModeBtnIncognito.setTextColor(Color.WHITE);
        } else {
            mModeBtnStandard.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            mModeBtnStandard.setTextColor(Color.WHITE);
            mModeBtnIncognito.setBackgroundColor(Color.TRANSPARENT);
            mModeBtnIncognito.setTextColor(Color.parseColor("#AAAAAA"));
        }
    }

    /*
     * Call after close tab from grid. If that empty out the mode
     * currently view (example: you just close your last incognito tab)
     * but other mode still have tab, flip switcher back to that mode
     * instead of leave it stuck show empty grid.
     */
    private void refreshTabSwitcherModeAfterMutation() {
        if (mTabSwitcherShowingIncognito && mTabManager.countForMode(true) == 0 && mTabManager.countForMode(false) > 0) {
            mTabSwitcherShowingIncognito = false;
            updateModeButtonsHighlight();
        }
        syncTabSwitcherList();
    }

    /*
     * Inflate the real (adapt) AOSP nav_screen.xml/nav_tab_view.xml,
     * add direct to Activity content root so it sit above everything
     * else including WebView. Build once, then just show/hide on next
     * tap, adapter re-sync live from mTabManager.tabs via
     * syncTabSwitcherList() on every open, instead of assume still
     * accurate from whenever first build (tab can be open/close through
     * path other than switcher self, example overflow menu "New tab").
     */
    private void showTabSwitcher() {
        logLocal("showTabSwitcher: tabs=" + mTabManager.tabs.size());
        try {
            /*
             * captureThumbnail() before only call on the OUTGOING tab
             * during switchToTab(), mean whichever tab you were CURRENTLY
             * on (especially freshly-create one you never switch away
             * from) never have thumbnail at all until first time you
             * leave it. Capture active tab here too mean open switcher
             * always show up-to-date preview for tab you really look at
             * right now.
             */
            captureThumbnail(mTabManager.activeTab());
            if (mTabSwitcherPanel == null) {
                buildTabSwitcherPanel();
            }
            syncTabSwitcherList();
            mTabSwitcherPanel.setVisibility(View.VISIBLE);
            mTabSwitcherPanel.bringToFront();
            logLocal("showTabSwitcher: panel visibility now VISIBLE, parent=" + mTabSwitcherPanel.getParent()
                + " width=" + mTabSwitcherPanel.getWidth() + " height=" + mTabSwitcherPanel.getHeight());
        } catch (Throwable t) {
            logException("showTabSwitcher", t);
            Toast.makeText(this, R.string.tab_switcher_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideTabSwitcher() {
        if (mTabSwitcherPanel != null) {
            mTabSwitcherPanel.setVisibility(View.GONE);
        }
    }

    private boolean isTabSwitcherShown() {
        return mTabSwitcherPanel != null && mTabSwitcherPanel.getVisibility() == View.VISIBLE;
    }

    private void buildTabSwitcherPanel() {
        logLocal("buildTabSwitcherPanel: constructing tab switcher UI (nav_screen/nav_tab_view).");
        final ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);

        View panel = getLayoutInflater().inflate(R.layout.nav_screen, root, false);
        GridView grid = (GridView) panel.findViewById(R.id.scroller);
        ImageButton newTabBtn = (ImageButton) panel.findViewById(R.id.newtab);
        ImageButton incognitoBtn = (ImageButton) panel.findViewById(R.id.incognito_placeholder);
        ImageButton bookmarksBtn = (ImageButton) panel.findViewById(R.id.bookmarks);
        ImageButton moreBtn = (ImageButton) panel.findViewById(R.id.more);
        mModeBtnStandard = (Button) panel.findViewById(R.id.mode_btn_standard);
        mModeBtnIncognito = (Button) panel.findViewById(R.id.mode_btn_incognito);

        /*
         * Grid adapter read mTabManager.tabsForMode(mTabSwitcherShowingIncognito)
         * fresh every call instead of hold own filter list, same "always
         * live, no manual resync" way the old unfilter version use against
         * mTabManager.tabs direct. Cheap: tab count here always tiny.
         */
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mTabManager.tabsForMode(mTabSwitcherShowingIncognito).size();
            }

            @Override
            public Object getItem(int position) {
                return mTabManager.tabsForMode(mTabSwitcherShowingIncognito).get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View cardView = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.nav_tab_view, parent, false);
                final TabManager.Tab tab = mTabManager.tabsForMode(mTabSwitcherShowingIncognito).get(position);

                TextView title = (TextView) cardView.findViewById(R.id.title);
                title.setText(!TextUtils.isEmpty(tab.title) ? tab.title
                    : (!TextUtils.isEmpty(tab.url) ? tab.url : getString(R.string.new_tab_default_title)));

                ImageView tabView = (ImageView) cardView.findViewById(R.id.tab_view);
                if (tab.thumbnail != null) {
                    tabView.setImageBitmap(tab.thumbnail);
                } else {
                    tabView.setImageResource(R.drawable.ic_stop_holo_dark);
                }

                ImageView closeBtn = (ImageView) cardView.findViewById(R.id.closetab);
                closeBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        logLocal("Tab switcher (grid): close tapped for url=" + tab.url + " incognito=" + tab.incognito);
                        closeTab(tab);
                        refreshTabSwitcherModeAfterMutation();
                    }
                });

                cardView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        logLocal("Tab switcher (grid): card tapped for url=" + tab.url + " incognito=" + tab.incognito);
                        switchToTab(tab);
                        hideTabSwitcher();
                    }
                });
                return cardView;
            }
        };
        grid.setAdapter(adapter);
        mTabSwitcherAdapter = adapter;
        updateModeButtonsHighlight();

        mModeBtnStandard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTabSwitcherShowingIncognito = false;
                updateModeButtonsHighlight();
                syncTabSwitcherList();
            }
        });
        mModeBtnIncognito.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTabSwitcherShowingIncognito = true;
                updateModeButtonsHighlight();
                syncTabSwitcherList();
            }
        });

        newTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLocal("Tab switcher: new tab tapped (incognito=" + mTabSwitcherShowingIncognito + ").");
                if (mTabSwitcherShowingIncognito) {
                    openNewIncognitoTab();
                } else {
                    openNewTab();
                }
                hideTabSwitcher();
            }
        });

        /*
         * Quick-create shortcut: always open fresh incognito tab and flip
         * switcher into Incognito mode to show it, no matter which mode
         * currently select, mirror the dedicated "New incognito tab" icon
         * real browser keep alongside own Standard/Incognito switcher.
         */
        incognitoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLocal("Tab switcher: incognito shortcut tapped.");
                openNewIncognitoTab();
                mTabSwitcherShowingIncognito = true;
                updateModeButtonsHighlight();
                hideTabSwitcher();
            }
        });

        bookmarksBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLocal("Tab switcher: Bookmarks tapped -> showBookmarksView(true).");
                hideTabSwitcher();
                showBookmarksView(true);
            }
        });

        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsMenu(v);
            }
        });

        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mTabSwitcherPanel = panel;
        logLocal("buildTabSwitcherPanel: done.");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installUniversalSafetyWrapper();

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {}
        try {
            if (getActionBar() != null) getActionBar().hide();
        } catch (Throwable ignored) {}

        if (IS_INTERNAL_WIFI_DEBUG) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    enableAdbOverTcpSync();
                }
            }, "adb-wifi-enable-thread").start();
        }
        mMainHandler = new Handler(Looper.getMainLooper());
        mDb = new BrowserDatabase(this);
        final int historyExpiryDays = SettingsStore.getHistoryExpiryDays(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mDb.purgeExpiredHistory(historyExpiryDays);
                } catch (Throwable t) {
                    logException("purgeExpiredHistory", t);
                }
            }
        }, "history-purge-thread").start();

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout contentColumn = new LinearLayout(this);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        contentColumn.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentColumn.setFocusable(true);
        contentColumn.setFocusableInTouchMode(true);

        View topBarView = getLayoutInflater().inflate(R.layout.browser_title_bar, contentColumn, false);
        bindTopBarViews(topBarView);
        contentColumn.addView(topBarView);

        /*
         * Build here instead of in browser_title_bar.xml, and add direct
         * to `root` (outermost FrameLayout) instead of live inside
         * contentColumn own vertical LinearLayout flow. Reason: topBarView
         * have wrap_content height, and contentColumn is vertical
         * LinearLayout with mWebFrame give weight=1 to fill whatever left,
         * so progress bar living INSIDE topBarView own layout would grow
         * topBarView measure height every time it become visible, which
         * squeeze mWebFrame down by that same amount (the real bug this
         * replace). As true overlay on `root` instead, show/hide it never
         * change anything else measure size, it just draw on top of the
         * boundary between title bar and WebView. OnLayoutChangeListener
         * keep it correct position at topBarView real bottom edge even if
         * that height change for another reason later (example find bar
         * open/close).
         */
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(0);
        mProgressBar.setProgressDrawable(getResources().getDrawable(R.drawable.progress));
        /*
         * progressBarStyleHorizontal bake in own top/bottom padding around
         * the draw bar, without strip it, visible indicator sit inset few
         * px below the view own (correct flush) bound, read as gap under
         * title bar even though it not one.
         */
        mProgressBar.setPadding(0, 0, 0, 0);
        mProgressBar.setVisibility(View.GONE);
        final FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(10));
        progressParams.gravity = Gravity.TOP;
        progressParams.topMargin = topBarView.getHeight() - dpToPx(5);
        mProgressBar.setLayoutParams(progressParams);
        topBarView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int newHeight = bottom - top - dpToPx(5);
                if (progressParams.topMargin != newHeight) {
                    progressParams.topMargin = newHeight;
                    mProgressBar.setLayoutParams(progressParams);
                }
            }
        });

        mWebFrame = new FrameLayout(this);
        mWebFrame.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));
        contentColumn.addView(mWebFrame);

        root.addView(contentColumn);
        root.addView(mProgressBar);

        View bottomBarView = getLayoutInflater().inflate(R.layout.browser_bottom_bar, root, false);
        bindBottomBarViews(bottomBarView);
        root.addView(bottomBarView);

        if (SettingsStore.getHideBottomBarGlobal(this)) {
            mBottomBarForceHidden = true;
            mBottomBarVisible = false;
            mBottomBar.setTranslationY(mBottomBarHeightPx);
        }

        setContentView(root);
        showLoadingDialog();
        bootExportWebView();
    }

    private void bindTopBarViews(View topBar) {
        mFaviconView = (ImageView) topBar.findViewById(R.id.favicon);
        mLockView = (ImageView) topBar.findViewById(R.id.lock);
        /*
         * Tap target is R.id.iconcombo (the 44dip FrameLayout wrapping
         * favicon+lock, style="@style/HoloButton" same as
         * tab_switcher_container/more elsewhere in browser_title_bar.xml
         * - confirm by that layout file this IS the intended tappable
         * area, not the small 21dip favicon ImageView itself inside it).
         */
        View iconComboView = topBar.findViewById(R.id.iconcombo);
        if (iconComboView != null) {
            iconComboView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showConnectionInfoDialog();
                }
            });
        }
        mUrlInput = (EditText) topBar.findViewById(R.id.url);
        mBtnClear = (ImageView) topBar.findViewById(R.id.clear);
        mBtnStopRefresh = (ImageView) topBar.findViewById(R.id.stop_refresh);
        mTabCountText = (TextView) topBar.findViewById(R.id.tab_count_text);
        /*
         * Progress bar no longer part of browser_title_bar.xml, see
         * construct in onCreate() right after topBarView add to
         * contentColumn, and its note on why.
         */

        mFindBar = topBar.findViewById(R.id.find_in_page_bar);
        mFindBarDivider = topBar.findViewById(R.id.find_bar_divider);
        mFindQueryInput = (EditText) topBar.findViewById(R.id.find_query);
        mFindMatchCountText = (TextView) topBar.findViewById(R.id.find_match_count);
        ImageButton findPrevBtn = (ImageButton) topBar.findViewById(R.id.find_prev);
        ImageButton findNextBtn = (ImageButton) topBar.findViewById(R.id.find_next);
        ImageButton findCloseBtn = (ImageButton) topBar.findViewById(R.id.find_close);

        mFindQueryInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                findInPage(s.toString());
            }
        });
        findPrevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findNextMatch(false);
            }
        });
        findNextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findNextMatch(true);
            }
        });
        findCloseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideFindBar();
            }
        });

        FrameLayout tabSwitcherContainer = (FrameLayout) topBar.findViewById(R.id.tab_switcher_container);
        mTabSwitcherContainerRef = tabSwitcherContainer;
        /*
         * Layout have full-size ImageButton (R.id.tab_switcher) sit inside
         * tab_switcher_container, cover whole tappable area. ImageButton
         * clickable by default no matter if listener attach to it, it was
         * silently eat every tap before container own listener ever see
         * it (and its android:background="@null" is why no visible press
         * feedback either). Bind same listener to both here.
         */
        ImageButton tabSwitcherButton = (ImageButton) topBar.findViewById(R.id.tab_switcher);
        ImageButton moreBtn = (ImageButton) topBar.findViewById(R.id.more);

        mUrlInput.setText(peekInitialAddressBarUrl());
        updateSecurityBadge(mUrlInput.getText().toString());

        mUrlInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    navigateTo(mUrlInput.getText().toString());
                    mUrlInput.clearFocus();
                    return true;
                }
                return false;
            }
        });
		
		final boolean[] needsSelectAll = new boolean[]{false};
		
        mUrlInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    mUrlInput.setBackgroundResource(R.drawable.textfield_active_holo_dark);
                    mBtnClear.setVisibility(mUrlInput.getText().length() > 0 ? View.VISIBLE : View.GONE);
                    mBtnStopRefresh.setVisibility(View.GONE);

                    // post to message queue so select happen AFTER touch and keyboard init
                    mUrlInput.post(new Runnable() {
                        @Override
                        public void run() {
                            mUrlInput.selectAll();
                        }
                    });
                } else {
                    mUrlInput.setBackground(null);
                    mBtnClear.setVisibility(View.GONE);
                    mBtnStopRefresh.setVisibility(View.VISIBLE);
                }
            }
        });

        mBtnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mUrlInput.setText("");
            }
        });

        mBtnStopRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mIsLoading) {
                    invokeNoArg("stopLoading");
                } else {
                    invokeNoArg("reload");
                }
            }
        });

        View.OnClickListener tabSwitcherClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLocal("Tabs tapped (via " + v.getId() + ") - showing tab switcher.");
                showTabSwitcher();
            }
        };
        tabSwitcherContainer.setOnClickListener(tabSwitcherClickListener);
        if (tabSwitcherButton != null) {
            tabSwitcherButton.setOnClickListener(tabSwitcherClickListener);
        }

        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsMenu(v);
            }
        });
    }

    private void bindBottomBarViews(View bar) {
        mBottomBar = (LinearLayout) bar;
        mBottomBarHeightPx = dpToPx(49);

        mBtnBack = (ImageView) bar.findViewById(R.id.btn_back);
        mBtnForward = (ImageView) bar.findViewById(R.id.btn_forward);
        mBtnBookmark = (ImageView) bar.findViewById(R.id.btn_bookmarks);
        ImageView btnHide = (ImageView) bar.findViewById(R.id.btn_hide_bar);

        mBtnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateHistory(-1, "goBack");
            }
        });

        mBtnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateHistory(1, "goForward");
            }
        });

        mBtnBookmark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBookmarkButtonTapped();
            }
        });

        btnHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setBottomBarVisible(false);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        trackBottomBarGesture(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void trackBottomBarGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mGestureLastRawY = ev.getRawY();
                mGestureAccumPx = 0f;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mGestureLastRawY >= 0f) {
                    float rawY = ev.getRawY();
                    mGestureAccumPx += (rawY - mGestureLastRawY);
                    mGestureLastRawY = rawY;
                    int thresholdPx = dpToPx(GESTURE_THRESHOLD_DP);
                    if (mGestureAccumPx <= -thresholdPx) {
                        setBottomBarVisible(false);
                        mGestureAccumPx = 0f;
                    } else if (mGestureAccumPx >= thresholdPx) {
                        setBottomBarVisible(true);
                        mGestureAccumPx = 0f;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mGestureLastRawY = -1f;
                mGestureAccumPx = 0f;
                break;
            default:
                break;
        }
    }

    private void setBottomBarVisible(boolean visible) {
        if (mBottomBar == null) return;
        if (mBottomBarForceHidden) visible = false;
        if (visible == mBottomBarVisible) return;
        mBottomBarVisible = visible;
        mBottomBar.animate()
            .translationY(visible ? 0f : mBottomBarHeightPx)
            .setDuration(180)
            .start();
    }

    private void applyBottomBarVisibilityForUrl(String url) {
        mBottomBarForceHidden = SettingsStore.resolveHideBottomBar(this, url);
        setBottomBarVisible(!mBottomBarForceHidden);
    }

    private void showSettingsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, 1, 1, getString(R.string.menu_new_tab));
        menu.add(Menu.NONE, 2, 2, getString(R.string.menu_bookmarks));
        menu.add(Menu.NONE, 3, 3, getString(R.string.menu_history));
        menu.add(Menu.NONE, 4, 4, getString(R.string.menu_find_in_page));
        MenuItem desktopSiteItem = menu.add(Menu.NONE, 5, 5, getString(R.string.menu_desktop_site));
        desktopSiteItem.setCheckable(true);
        TabManager.Tab activeTabForMenu = mTabManager.activeTab();
        desktopSiteItem.setChecked(activeTabForMenu != null && activeTabForMenu.desktopSiteEnabled);
        /*
         * Sit right under Desktop site in menu (order=6, right after
         * Desktop site order=5), see mZoomEnabled own field comment for
         * why this on purpose in-memory/per-session only, unlike every
         * other checkable/persist setting in this menu.
         */
        MenuItem zoomItem = menu.add(Menu.NONE, 6, 6, getString(R.string.menu_can_zoom));
        zoomItem.setCheckable(true);
        zoomItem.setChecked(mZoomEnabled);
        menu.add(Menu.NONE, 7, 7, R.string.settings);
        menu.add(Menu.NONE, 8, 8, getString(R.string.menu_downloads));
		menu.add(Menu.NONE, 9, 9, getString(R.string.menu_about));
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == 1) {
                    logLocal("Settings menu: New tab tapped -> openNewTab().");
                    openNewTab();
                    return true;
                }
                if (item.getItemId() == 2) {
                    logLocal("Settings menu: Bookmarks tapped -> showBookmarksView(false).");
                    showBookmarksView(false);
                    return true;
                }
                if (item.getItemId() == 3) {
                    logLocal("Settings menu: History tapped -> showHistoryView().");
                    showHistoryView();
                    return true;
                }
                if (item.getItemId() == 4) {
                    logLocal("Settings menu: Find in page tapped.");
                    showFindBar();
                    return true;
                }
                if (item.getItemId() == 5) {
                    TabManager.Tab activeTab = mTabManager.activeTab();
                    if (activeTab != null) {
                        activeTab.desktopSiteEnabled = !activeTab.desktopSiteEnabled;
                        logLocal("Settings menu: Desktop site toggled -> " + activeTab.desktopSiteEnabled + " (tab-local, in-memory only).");
                        /*
                         * navigateTo() already call applyResolvedUserAgent()
                         * as part of own flow, which now pick up desktop
                         * override just set above, this both apply new UA
                         * and do the request auto-refresh in one call.
                         */
                        navigateTo(mCurrentUrl);
                    }
                    return true;
                }
                if (item.getItemId() == 6) {
                    mZoomEnabled = !mZoomEnabled;
                    logLocal("Settings menu: Can zoom toggled -> " + mZoomEnabled + " (global, in-memory only - resets next launch).");
                    applyZoomSettingToAllTabs();
                    return true;
                }
                if (item.getItemId() == 7) {
                    startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQUEST_CODE_SETTINGS);
                    return true;
                }
				if (item.getItemId() == 8) {
                    /*
                     * ACTION_VIEW_DOWNLOADS open system Downloads app
                     * (com.android.providers.downloads.ui on stock 4.4),
                     * same list startSystemDownload() DownloadManager
                     * entry land in. No package name need/hardcode, this
                     * public platform action present since API 12.
                     */
                    logLocal("Settings menu: Downloads tapped -> ACTION_VIEW_DOWNLOADS.");
                    try {
                        startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                    } catch (Throwable t) {
                        logException("Settings menu: Downloads", t);
                        Toast.makeText(MainActivity.this, getString(R.string.not_implemented_format, item.getTitle()), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                if (item.getItemId() == 9) {
                    logLocal("Settings menu: About tapped -> AboutActivity.");
                    startActivity(new Intent(MainActivity.this, AboutActivity.class));
                    return true;
                }
                logLocal("Settings menu: \"" + item.getTitle() + "\" tapped (placeholder).");
                Toast.makeText(MainActivity.this, getString(R.string.not_implemented_format, item.getTitle()), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        popup.show();
    }
    // Bookmarks

    private void handleBookmarkButtonTapped() {
        if (mDb == null || TextUtils.isEmpty(mCurrentUrl)) return;
        final BrowserDatabase.Bookmark existing = mDb.getBookmarkByUrl(mCurrentUrl);
        if (existing != null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.remove_bookmark_confirm)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mDb.deleteBookmark(existing.id);
                        syncBookmarkStarIcon(mCurrentUrl);
                        Toast.makeText(MainActivity.this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        } else {
            showCreateBookmarkDialog();
        }
    }

    private void showCreateBookmarkDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.create_bookmark_title)
            .setMessage(getString(R.string.create_bookmark_message, mCurrentUrl))
            .setPositiveButton(R.string.bookmark_folder, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showFolderPickerDialog();
                }
            })
            .setNegativeButton(R.string.bookmark_global, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    createBookmarkInFolder(null);
                }
            })
            .show();
    }

    private void showFolderPickerDialog() {
        final List<BrowserDatabase.Folder> folders = mDb.listFolders();
        List<String> items = new ArrayList<String>();
        items.add(getString(R.string.new_folder_item));
        for (BrowserDatabase.Folder f : folders) {
            items.add(f.name);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.choose_folder_title)
            .setItems(items.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        showNewFolderDialog();
                    } else {
                        createBookmarkInFolder(folders.get(which - 1).id);
                    }
                }
            })
            .show();
    }

    private void showNewFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.folder_name_hint);
        new AlertDialog.Builder(this)
            .setTitle(R.string.new_folder_title)
            .setView(input)
            .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) return;
                    long folderId = mDb.createFolder(name);
                    createBookmarkInFolder(folderId);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void createBookmarkInFolder(Long folderId) {
        TabManager.Tab activeTab = mTabManager.activeTab();
        Bitmap favicon = activeTab != null ? activeTab.favicon : null;
        String title = (activeTab != null && !TextUtils.isEmpty(activeTab.title)) ? activeTab.title : mCurrentUrl;
        mDb.addBookmark(title, mCurrentUrl, favicon, folderId);
        syncBookmarkStarIcon(mCurrentUrl);
        Toast.makeText(this, R.string.bookmarked, Toast.LENGTH_SHORT).show();
        logLocal("createBookmarkInFolder: added bookmark url=" + mCurrentUrl + " folderId=" + folderId);
    }

    /*
     * Call wherever current URL/active tab change (navigateTo,
     * onRealNavigationUrlUpdate, switchToTab) so star always reflect real
     * DB state instead of UI-only toggle that could drift out of sync
     * (that exactly what old placeholder version did).
     */
    private void syncBookmarkStarIcon(String url) {
        if (mBtnBookmark == null || mDb == null) return;
        mIsBookmarked = mDb.isBookmarked(url);
        mBtnBookmark.setImageResource(mIsBookmarked
            ? R.drawable.ic_bookmark_on_holo_dark
            : R.drawable.ic_bookmark_off_holo_dark);
    }

    /*
     * Record on every real navigate complete (see navigateTo()/
     * onRealNavigationUrlUpdate() call site), on purpose not chase exact
     * Chromium history-record parity for pushState/replaceState edge case
     * (that Chromium own internal product policy, not a spec, see our
     * earlier talk on this). Title correct once real one arrive via
     * onRealTitleUpdate() -> updateLatestVisitTitle().
     */
    private void recordHistoryVisit(String url) {
        if (mDb == null || TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        mDb.recordVisit(url, url);
    }

    private void openBookmark(String url, boolean newTab) {
        if (newTab) {
            openNewTab(url);
        } else {
            navigateTo(url);
        }
    }

    private boolean isBookmarksViewShown() {
        return mBookmarksPanel != null;
    }

    private void hideBookmarksView() {
        if (mBookmarksPanel != null) {
            ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            root.removeView(mBookmarksPanel);
            mBookmarksPanel = null;
        }
    }

    /*
     * Rebuild fresh every open (unlike tab switcher/find bar, which build
     * once and re-sync), bookmark/folder data change far less often than
     * tab do, and this keep every mutation (add/edit/delete, folder
     * rename/delete) able to just call this again to refresh instead of
     * need separate incremental-update path.
     * Shared header for bookmarks/history overlay panel: title + search
     * icon (toggle search box) + close icon, with blue divider under,
     * use the real AOSP icon already confirm present in this project
     * (ic_search_holo_dark, ic_close_window_holo_dark, same one the find
     * bar close button already use). Return the search EditText so
     * caller wire own filter to it.
     */
    private EditText buildOverlayHeader(LinearLayout panel, String titleText, final Runnable onClose) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dpToPx(16), dpToPx(10), dpToPx(6), dpToPx(10));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        headerRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.history_search_hint);
        searchInput.setSingleLine(true);
        searchInput.setVisibility(View.GONE);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.parseColor("#888888"));
        searchInput.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));

        int iconSize = dpToPx(40);

        ImageButton searchBtn = new ImageButton(this);
        searchBtn.setImageResource(R.drawable.ic_search_holo_dark);
        searchBtn.setBackground(null);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchInput.getVisibility() == View.VISIBLE) {
                    searchInput.setVisibility(View.GONE);
                    searchInput.setText("");
                } else {
                    searchInput.setVisibility(View.VISIBLE);
                    searchInput.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
        headerRow.addView(searchBtn, new LinearLayout.LayoutParams(iconSize, iconSize));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(R.drawable.ic_close_window_holo_dark);
        closeBtn.setBackground(null);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClose.run();
            }
        });
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(iconSize, iconSize));

        panel.addView(headerRow);
        panel.addView(searchInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33B5E5")); // old Android Holo Blue color
        panel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(2)));

        return searchInput;
    }

    /*
     * Build group row with proper lay-out expand/collapse arrow next to
     * label, ExpandableListView own BUILT-IN indicator never explicit
     * position for programmatic-build row like this one, so it was draw
     * on top of text instead of beside it. Disable it
     * (setGroupIndicator(null), do by caller) and draw our own arrow
     * (reuse arrow_up_float/arrow_down_float, the same framework drawable
     * already prove working for find bar prev/next button) avoid that
     * completely.
     */
    private View buildGroupRow(String label, boolean isExpanded) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
        row.setBackgroundColor(Color.parseColor("#1A1A1A"));

        ImageView arrow = new ImageView(this);
        int arrowSize = dpToPx(18);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(arrowSize, arrowSize);
        arrowParams.rightMargin = dpToPx(12);
        arrow.setImageResource(isExpanded ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
        row.addView(arrow, arrowParams);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    private void showBookmarksView(final boolean openInNewTab) {
        logLocal("showBookmarksView: openInNewTab=" + openInNewTab);
        try {
            hideBookmarksView();
            mBookmarksViewOpenInNewTab = openInNewTab;
            final ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(getResources().getDrawable(R.drawable.browser_background_holo));
            panel.setClickable(true);

            EditText searchInput = buildOverlayHeader(panel, getString(R.string.bookmarks_title), new Runnable() {
                @Override
                public void run() {
                    hideBookmarksView();
                }
            });

            final ExpandableListView listView = new ExpandableListView(this);
            listView.setGroupIndicator(null); // custom arrow draw in buildGroupRow() instead
            panel.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            /*
             * Non-search mode: group 0 always "Global" (folder_id IS
             * NULL), group 1..N are real folder. Search mode: single flat
             * "Results" group, folder long-press context menu only mean
             * something in non-search mode, that why isSearchMode below.
             */
            final List<BrowserDatabase.Folder>[] foldersHolder = new List[]{mDb.listFolders()};
            final List<String>[] groupNamesHolder = new List[1];
            final List<List<BrowserDatabase.Bookmark>>[] groupChildrenHolder = new List[1];
            final boolean[] isSearchMode = {false};

            final Runnable[] refreshHolder = new Runnable[1];
            refreshHolder[0] = new Runnable() {
                @Override
                public void run() {
                    String query = searchInput.getText().toString().trim();
                    List<BrowserDatabase.Folder> folders = mDb.listFolders();
                    foldersHolder[0] = folders;
                    List<String> groupNames = new ArrayList<String>();
                    List<List<BrowserDatabase.Bookmark>> groupChildren = new ArrayList<List<BrowserDatabase.Bookmark>>();

                    if (TextUtils.isEmpty(query)) {
                        isSearchMode[0] = false;
                        groupNames.add(getString(R.string.bookmark_global));
                        groupChildren.add(mDb.listGlobalBookmarks());
                        for (BrowserDatabase.Folder f : folders) {
                            groupNames.add(f.name);
                            groupChildren.add(mDb.listBookmarksInFolder(f.id));
                        }
                    } else {
                        isSearchMode[0] = true;
                        groupNames.add(getString(R.string.bookmark_results));
                        groupChildren.add(mDb.searchBookmarks(query));
                    }
                    groupNamesHolder[0] = groupNames;
                    groupChildrenHolder[0] = groupChildren;

                    BaseExpandableListAdapter adapter = new BaseExpandableListAdapter() {
                        @Override
                        public int getGroupCount() {
                            return groupNamesHolder[0].size();
                        }

                        @Override
                        public int getChildrenCount(int groupPosition) {
                            return groupChildrenHolder[0].get(groupPosition).size();
                        }

                        @Override
                        public Object getGroup(int groupPosition) {
                            return groupNamesHolder[0].get(groupPosition);
                        }

                        @Override
                        public Object getChild(int groupPosition, int childPosition) {
                            return groupChildrenHolder[0].get(groupPosition).get(childPosition);
                        }

                        @Override
                        public long getGroupId(int groupPosition) {
                            return groupPosition;
                        }

                        @Override
                        public long getChildId(int groupPosition, int childPosition) {
                            return childPosition;
                        }

                        @Override
                        public boolean hasStableIds() {
                            return false;
                        }

                        @Override
                        public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
                            return buildGroupRow(groupNamesHolder[0].get(groupPosition), isExpanded);
                        }

                        @Override
                        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
                            final BrowserDatabase.Bookmark bm = groupChildrenHolder[0].get(groupPosition).get(childPosition);
                            LinearLayout row = new LinearLayout(MainActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dpToPx(24), dpToPx(10), dpToPx(16), dpToPx(10));

                            ImageView favicon = new ImageView(MainActivity.this);
                            int faviconSize = dpToPx(20);
                            LinearLayout.LayoutParams faviconParams = new LinearLayout.LayoutParams(faviconSize, faviconSize);
                            faviconParams.rightMargin = dpToPx(12);
                            if (bm.favicon != null) {
                                favicon.setImageBitmap(bm.favicon);
                            }
                            row.addView(favicon, faviconParams);

                            TextView tv = new TextView(MainActivity.this);
                            tv.setText(!TextUtils.isEmpty(bm.title) ? bm.title : bm.url);
                            tv.setTextColor(Color.WHITE);
                            tv.setSingleLine(true);
                            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                            row.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    logLocal("Bookmarks: link tapped url=" + bm.url + " newTab=" + openInNewTab);
                                    openBookmark(bm.url, openInNewTab);
                                    hideBookmarksView();
                                }
                            });
                            return row;
                        }

                        @Override
                        public boolean isChildSelectable(int groupPosition, int childPosition) {
                            return true;
                        }
                    };
                    listView.setAdapter(adapter);
                    if (isSearchMode[0]) {
                        listView.expandGroup(0);
                    } else {
                        listView.expandGroup(0); // Global expand by default
                    }
                }
            };

            /*
             * Long-press handle at LIST level (via packed position)
             * instead of attach OnLongClickListener to individual
             * group/child row view, that was the real bug behind "the
             * second folder won't open": per-view long-click listener on
             * group row interfere with ExpandableListView own tap-to-
             * expand gesture dispatch for that same row. This way every
             * group and child stay correctly tappable AND long-pressable.
             */
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int flatPosition, long id) {
                    long packedPosition = listView.getExpandableListPosition(flatPosition);
                    int type = ExpandableListView.getPackedPositionType(packedPosition);
                    int groupPos = ExpandableListView.getPackedPositionGroup(packedPosition);
                    if (type == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
                        if (!isSearchMode[0] && groupPos > 0) {
                            long folderId = foldersHolder[0].get(groupPos - 1).id;
                            String folderName = groupNamesHolder[0].get(groupPos);
                            showFolderContextMenu(folderId, folderName);
                        }
                        return true;
                    } else if (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
                        int childPos = ExpandableListView.getPackedPositionChild(packedPosition);
                        showBookmarkContextMenu(groupChildrenHolder[0].get(groupPos).get(childPos));
                        return true;
                    }
                    return false;
                }
            });

            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    refreshHolder[0].run();
                }
            });

            refreshHolder[0].run();

            root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mBookmarksPanel = panel;
        } catch (Throwable t) {
            logException("showBookmarksView", t);
            Toast.makeText(this, R.string.bookmarks_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarkContextMenu(final BrowserDatabase.Bookmark bm) {
        final CharSequence[] items = {getString(R.string.open_in_new_tab), getString(R.string.copy_url), getString(R.string.edit_bookmark_title), getString(R.string.open_in_current_tab), getString(R.string.delete)};
        new AlertDialog.Builder(this)
            .setTitle(!TextUtils.isEmpty(bm.title) ? bm.title : bm.url)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0:
                            openBookmark(bm.url, true);
                            hideBookmarksView();
                            break;
                        case 1:
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setText(bm.url);
                            Toast.makeText(MainActivity.this, R.string.address_copied, Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            showEditBookmarkDialog(bm);
                            break;
                        case 3:
                            openBookmark(bm.url, false);
                            hideBookmarksView();
                            break;
                        case 4:
                            mDb.deleteBookmark(bm.id);
                            syncBookmarkStarIcon(mCurrentUrl);
                            showBookmarksView(mBookmarksViewOpenInNewTab);
                            break;
                    }
                }
            })
            .show();
    }

    private void showEditBookmarkDialog(final BrowserDatabase.Bookmark bm) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.bookmark_name_hint);
        nameInput.setText(bm.title);
        layout.addView(nameInput);
        final EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.bookmark_url_hint);
        urlInput.setText(bm.url);
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
            .setTitle(R.string.edit_bookmark_title)
            .setView(layout)
            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mDb.updateBookmark(bm.id, nameInput.getText().toString().trim(), urlInput.getText().toString().trim());
                    syncBookmarkStarIcon(mCurrentUrl);
                    showBookmarksView(mBookmarksViewOpenInNewTab);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showFolderContextMenu(final long folderId, final String folderName) {
        final CharSequence[] items = {getString(R.string.rename_folder_title), getString(R.string.delete_folder_item)};
        new AlertDialog.Builder(this)
            .setTitle(folderName)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        showRenameFolderDialog(folderId);
                    } else {
                        mDb.deleteFolder(folderId);
                        syncBookmarkStarIcon(mCurrentUrl);
                        showBookmarksView(mBookmarksViewOpenInNewTab);
                    }
                }
            })
            .show();
    }

    private void showRenameFolderDialog(final long folderId) {
        final EditText input = new EditText(this);
        input.setHint(R.string.folder_name_hint);
        new AlertDialog.Builder(this)
            .setTitle(R.string.rename_folder_title)
            .setView(input)
            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) return;
                    mDb.renameFolder(folderId, name);
                    showBookmarksView(mBookmarksViewOpenInNewTab);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // History

    private View mHistoryPanel;

    private boolean isHistoryViewShown() {
        return mHistoryPanel != null;
    }

    private void hideHistoryView() {
        if (mHistoryPanel != null) {
            ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            root.removeView(mHistoryPanel);
            mHistoryPanel = null;
        }
    }

    /*
     * "Today"/"Yesterday"/full date, match how most history UI label day
     * group, compute from device local timezone/calendar so it line up
     * with what user would really call "today".
     */
    private String formatHistoryDayLabel(long visitedAtMillis) {
        java.util.Calendar entryCal = java.util.Calendar.getInstance();
        entryCal.setTimeInMillis(visitedAtMillis);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

        if (sameDay(entryCal, today)) return getString(R.string.history_today);
        if (sameDay(entryCal, yesterday)) return getString(R.string.history_yesterday);
        return new java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(entryCal.getTime());
    }

    private boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
            && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /*
     * Rebuild fresh every open, same reason as showBookmarksView(). With
     * no search text, entry group by day (today group expand by default,
     * as ask). While search, day-group drop in favor of single flat group
     * of match, simpler than try keep day header meaning for filter
     * result set.
     */
    private void showHistoryView() {
        logLocal("showHistoryView: opening.");
        try {
            hideHistoryView();
            final ViewGroup root = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(getResources().getDrawable(R.drawable.browser_background_holo));
            panel.setClickable(true);

            final EditText searchInput = buildOverlayHeader(panel, getString(R.string.history_title), new Runnable() {
                @Override
                public void run() {
                    hideHistoryView();
                }
            });

            Button btnClear = new Button(this);
            btnClear.setText(R.string.history_clear_all);
            LinearLayout clearRow = new LinearLayout(this);
            clearRow.setOrientation(LinearLayout.HORIZONTAL);
            clearRow.setGravity(Gravity.RIGHT);
            clearRow.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            clearRow.addView(btnClear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(clearRow);

            final ExpandableListView listView = new ExpandableListView(this);
            listView.setGroupIndicator(null); // custom arrow draw in buildGroupRow() instead
            panel.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            final List<List<BrowserDatabase.HistoryEntry>>[] groupEntriesHolder = new List[1];

            /*
             * Rebuild adapter grouping from mDb each time, call once for
             * initial (day-group) view, and again on every search text
             * change.
             */
            final Runnable[] refreshHolder = new Runnable[1];
            refreshHolder[0] = new Runnable() {
                @Override
                public void run() {
                    String query = searchInput.getText().toString().trim();
                    final List<String> groupLabels = new ArrayList<String>();
                    final List<List<BrowserDatabase.HistoryEntry>> groupEntries = new ArrayList<List<BrowserDatabase.HistoryEntry>>();

                    if (TextUtils.isEmpty(query)) {
                        List<BrowserDatabase.HistoryEntry> all = mDb.listAllHistory();
                        String lastLabel = null;
                        List<BrowserDatabase.HistoryEntry> currentGroup = null;
                        for (BrowserDatabase.HistoryEntry e : all) {
                            String label = formatHistoryDayLabel(e.visitedAt);
                            if (!label.equals(lastLabel)) {
                                currentGroup = new ArrayList<BrowserDatabase.HistoryEntry>();
                                groupLabels.add(label);
                                groupEntries.add(currentGroup);
                                lastLabel = label;
                            }
                            currentGroup.add(e);
                        }
                    } else {
                        groupLabels.add(getString(R.string.bookmark_results));
                        groupEntries.add(mDb.searchHistory(query));
                    }
                    groupEntriesHolder[0] = groupEntries;

                    BaseExpandableListAdapter adapter = new BaseExpandableListAdapter() {
                        @Override
                        public int getGroupCount() {
                            return groupLabels.size();
                        }

                        @Override
                        public int getChildrenCount(int groupPosition) {
                            return groupEntriesHolder[0].get(groupPosition).size();
                        }

                        @Override
                        public Object getGroup(int groupPosition) {
                            return groupLabels.get(groupPosition);
                        }

                        @Override
                        public Object getChild(int groupPosition, int childPosition) {
                            return groupEntriesHolder[0].get(groupPosition).get(childPosition);
                        }

                        @Override
                        public long getGroupId(int groupPosition) {
                            return groupPosition;
                        }

                        @Override
                        public long getChildId(int groupPosition, int childPosition) {
                            return childPosition;
                        }

                        @Override
                        public boolean hasStableIds() {
                            return false;
                        }

                        @Override
                        public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
                            return buildGroupRow(groupLabels.get(groupPosition), isExpanded);
                        }

                        @Override
                        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
                            final BrowserDatabase.HistoryEntry entry = groupEntriesHolder[0].get(groupPosition).get(childPosition);
                            LinearLayout row = new LinearLayout(MainActivity.this);
                            row.setOrientation(LinearLayout.VERTICAL);
                            row.setPadding(dpToPx(24), dpToPx(10), dpToPx(16), dpToPx(10));

                            TextView titleView = new TextView(MainActivity.this);
                            titleView.setText(!TextUtils.isEmpty(entry.title) ? entry.title : entry.url);
                            titleView.setTextColor(Color.WHITE);
                            titleView.setSingleLine(true);
                            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            row.addView(titleView);

                            TextView urlView = new TextView(MainActivity.this);
                            urlView.setText(entry.url);
                            urlView.setTextColor(Color.parseColor("#888888"));
                            urlView.setTextSize(12);
                            urlView.setSingleLine(true);
                            urlView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            row.addView(urlView);

                            row.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    logLocal("History: entry tapped url=" + entry.url);
                                    navigateTo(entry.url);
                                    hideHistoryView();
                                }
                            });
                            return row;
                        }

                        @Override
                        public boolean isChildSelectable(int groupPosition, int childPosition) {
                            return true;
                        }
                    };
                    listView.setAdapter(adapter);
                    if (TextUtils.isEmpty(query)) {
                        /*
                         * "Today" always group 0 if any history exist today,
                         * since listAllHistory() is newest-first.
                         */
                        if (groupLabels.size() > 0 && getString(R.string.history_today).equals(groupLabels.get(0))) {
                            listView.expandGroup(0);
                        }
                    } else {
                        for (int i = 0; i < groupLabels.size(); i++) listView.expandGroup(i);
                    }
                }
            };

            /*
             * Long-press handle at LIST level (via packed position), see
             * showBookmarksView() note on why per-view
             * OnLongClickListener on group row was the real bug behind
             * folder not expand. History have no group-level long-press
             * action (day header/"Results" not editable), only child
             * entry.
             */
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int flatPosition, long id) {
                    long packedPosition = listView.getExpandableListPosition(flatPosition);
                    int type = ExpandableListView.getPackedPositionType(packedPosition);
                    if (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
                        int groupPos = ExpandableListView.getPackedPositionGroup(packedPosition);
                        int childPos = ExpandableListView.getPackedPositionChild(packedPosition);
                        showHistoryEntryContextMenu(groupEntriesHolder[0].get(groupPos).get(childPos), refreshHolder[0]);
                        return true;
                    }
                    return false;
                }
            });

            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    refreshHolder[0].run();
                }
            });

            btnClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.history_clear_all_confirm)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mDb.clearAllHistory();
                                refreshHolder[0].run();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                }
            });

            refreshHolder[0].run();

            root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mHistoryPanel = panel;
        } catch (Throwable t) {
            logException("showHistoryView", t);
            Toast.makeText(this, R.string.history_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showHistoryEntryContextMenu(final BrowserDatabase.HistoryEntry entry, final Runnable refresh) {
        final CharSequence[] items = {getString(R.string.open_in_new_tab), getString(R.string.copy_url), getString(R.string.delete)};
        new AlertDialog.Builder(this)
            .setTitle(!TextUtils.isEmpty(entry.title) ? entry.title : entry.url)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0:
                            openNewTab(entry.url);
                            hideHistoryView();
                            break;
                        case 1:
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setText(entry.url);
                            Toast.makeText(MainActivity.this, R.string.address_copied, Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            mDb.deleteHistoryEntry(entry.id);
                            refresh.run();
                            break;
                    }
                }
            })
            .show();
    }
}