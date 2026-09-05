package com.evoworld.uckitkatwebview;

/*
 * Port from the UcWebview dev project (2026-09-02), prune down to only
 * what the proven, working export.WebView construct routine really
 * need. Remove completely: all checkAndHookUcClass/hookAllMethodsWithLogs
 * trace infrastructure, transplant/strip-mode test hook, the
 * dword_2664B30 poller and sub_7C35C0 trampoline (native-only diagnostic,
 * no longer relevant now that export.WebView construct clean through own
 * real constructor), and every native (crashcatcher.c/plthook.c) JNI
 * declare, this project have no native code at all. What remain is
 * exactly the set of hook confirm, through the dev project own
 * investigation, to be require for real UC startup (UCAerieApplication.
 * onCreate() -> ... -> export.WebView own constructor) to complete
 * success.
 */

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Environment;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class XposedInit implements IXposedHookLoadPackage {

    /*
     * Real multi-process render confirm work and require, this stay
     * false. Keep as name constant (not mutable field with UI toggle,
     * unlike dev project) since no diagnostic UI here to flip it from.
     */
    private static final boolean sForceSingleProcess = false;

    private static File sLogFile;
    private static Context uaSettingsContext;

    /*
     * The real host Activity instance (not just its ApplicationContext,
     * which uaSettingsContext above is), need to reflect call instance
     * method on MainActivity like getDefaultUserAgent() from static
     * helper outside patchCoreFramework own closure.
     */
    private static Context sHostActivity;

    public static synchronized void logToSd(String tag, String msg) {
        logToSd(tag, msg, false);
    }

    /** forceSync=true always fsync right away, use for anything error-related. */
    public static synchronized void logToSd(String tag, String msg, boolean forceSync) {
        XposedBridge.log(tag + msg);
        try {
            if (sLogFile == null) {
                File sdcard = Environment.getExternalStorageDirectory();
                sLogFile = new File(sdcard, "uckitkatwebview.log");
            }
            FileOutputStream fos = new FileOutputStream(sLogFile, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write("[tid=" + android.os.Process.myTid() + "] " + tag + msg + "\n");
            osw.flush();
            if (forceSync || tag.contains("ERROR") || tag.contains("EXCEPTION")) {
                fos.getFD().sync();
            }
            osw.close();
            fos.close();
        } catch (Throwable ignored) {}
    }

    public static void logErr(String label, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        logToSd("[EXCEPTION] ", label + ":\n" + sw.toString());
    }

    private static String findFirstUcOrChromiumCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : stack) {
            String cn = el.getClassName();
            if (cn.startsWith("org.chromium.") || cn.startsWith("unet.org.chromium.")
                    || cn.startsWith("com.uc.") || cn.startsWith("com.UCMobile")) {
                return el.toString();
            }
        }
        return null;
    }

    private static void installPackageIdentityHook(ClassLoader cl, final boolean spoofToUcMobile, final String tag) {
        try {
            Class<?> contextImplClz = XposedHelpers.findClass("android.app.ContextImpl", cl);

            XposedBridge.hookAllMethods(contextImplClz, "getPackageName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String caller = findFirstUcOrChromiumCaller();
                    if (caller == null) return;
                    String original = (String) param.getResult();
                    if (spoofToUcMobile && !"com.UCMobile".equals(original)) {
                        param.setResult("com.UCMobile");
                        logToSd(tag, "SPOOFED getPackageName() -> com.UCMobile (was: " + original + ") for caller " + caller, true);
                    }
                }
            });

            logToSd("[EVO_CORE] ", "SUCCESS: Installed package-identity hook (spoof=" + spoofToUcMobile + ") tag=" + tag);
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on package-identity hook: " + t.getMessage());
        }
    }

    /*
     * The random-generate numeric subdirectory name (app_u4sdk/dlibs/
     * _<random>/lib/armeabi-v7a/) can not hardcode, since it regenerate
     * on every sync, search for it dynamic instead.
     */
    private static String findU4SdkLibDir(Context hostContext) {
        try {
            File dlibsDir = new File(hostContext.getApplicationInfo().dataDir, "app_u4sdk/dlibs");
            File[] subdirs = dlibsDir.listFiles();
            if (subdirs == null) return null;
            for (File subdir : subdirs) {
                File candidate = new File(subdir, "lib/armeabi-v7a");
                if (new File(candidate, "libwebviewuc.so").exists()) {
                    return candidate.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void patchCoreFramework(ClassLoader cl, final Context hostContext) {
        logToSd("[EVO_CORE] ", ">>> Applying Canonical Xposed Framework Patches to DexClassLoader: " + cl);

        /*
         * Application context, keep for UA/userscript static helper
         * below (applyUserAgentIfConfigured/runMatchingScripts), just a
         * Context to hand to SettingsStore stateless static method, not
         * hostContext-identity concern like MainActivity.sInstance was,
         * getApplicationContext() stay stable no matter which
         * classloader load this copy of XposedInit.
         */
        uaSettingsContext = hostContext.getApplicationContext();
        sHostActivity = hostContext;

        installPackageIdentityHook(cl, true, "[EVO_CORE-PKGSPOOF] ");

        final File dataDir = new File(hostContext.getApplicationInfo().dataDir, "app_ucwebview_data");
        final File cacheDir = new File(hostContext.getCacheDir(), "ucwebview_cache");
        final File nativeLibDir = new File(hostContext.getApplicationInfo().dataDir, "app_native_libs");
        final File paksDir = new File(hostContext.getApplicationInfo().dataDir, "paks");
        if (!dataDir.exists()) dataDir.mkdirs();
        if (!cacheDir.exists()) cacheDir.mkdirs();
        if (!paksDir.exists()) paksDir.mkdirs();

        try {
            Class<?> actThreadClass = XposedHelpers.findClass("org.chromium.base.helper.ActivityThread", cl);
            Application app = (Application) hostContext.getApplicationContext();
            XposedBridge.hookAllMethods(actThreadClass, "currentApplication", XC_MethodReplacement.returnConstant(app));
            XposedBridge.hookAllMethods(actThreadClass, "getApplication", XC_MethodReplacement.returnConstant(app));
            logToSd("[EVO_CORE] ", "SUCCESS: Mocked ActivityThread.currentApplication()");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on ActivityThread hook: " + t.getMessage());
        }

        try {
            Class<?> baseZ = XposedHelpers.findClass("org.chromium.base.z", cl);
            XposedBridge.hookAllMethods(baseZ, "c", XC_MethodReplacement.returnConstant(hostContext.getApplicationContext()));
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked org.chromium.base.z.c context");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on org.chromium.base.z hook: " + t.getMessage());
        }

        String[] pathUtilsClasses = new String[] {
            "org.chromium.base.PathUtils",
            "unet.org.chromium.base.PathUtils"
        };
        for (String puName : pathUtilsClasses) {
            try {
                Class<?> puClass = XposedHelpers.findClass(puName, cl);
                XposedBridge.hookAllMethods(puClass, "getDataDirectory", XC_MethodReplacement.returnConstant(dataDir.getAbsolutePath()));
                XposedBridge.hookAllMethods(puClass, "getCacheDirectory", XC_MethodReplacement.returnConstant(cacheDir.getAbsolutePath()));
                XposedBridge.hookAllMethods(puClass, "getPackageDirectory", XC_MethodReplacement.returnConstant(hostContext.getApplicationInfo().dataDir));
                logToSd("[EVO_CORE] ", "SUCCESS: Hooked " + puName + " directory getters.");
            } catch (Throwable t) {
                logToSd("[EVO_CORE] ", "Note on " + puName + " hook: " + t.getMessage());
            }
        }

        String[] ncnClasses = new String[] {
            "org.chromium.net.NetworkChangeNotifier",
            "unet.org.chromium.net.NetworkChangeNotifier"
        };
        for (String ncnName : ncnClasses) {
            try {
                Class<?> ncnClass = XposedHelpers.findClass(ncnName, cl);
                XposedBridge.hookAllMethods(ncnClass, "isOnline", XC_MethodReplacement.returnConstant(true));
                XposedBridge.hookAllMethods(ncnClass, "getCurrentConnectionType", XC_MethodReplacement.returnConstant(2));
                XposedBridge.hookAllMethods(ncnClass, "getCurrentConnectionSubtype", XC_MethodReplacement.returnConstant(0));
                logToSd("[EVO_CORE] ", "SUCCESS: Hooked " + ncnName + " connectivity methods.");
            } catch (Throwable t) {
                logToSd("[EVO_CORE] ", "Note on " + ncnName + ": " + t.getMessage());
            }
        }

        /*
         * Real multi-process render confirm work, not lock ServiceConfig
         * to single-process (sForceSingleProcess=false). Leave as
         * explicit no-op branch (instead of remove) so the reason stay
         * document in case this ever need revisit.
         */
        if (sForceSingleProcess) {
            try {
                Class<?> serviceConfig = XposedHelpers.findClass("com.uc.proc.ServiceConfig", cl);
                XposedHelpers.setStaticBooleanField(serviceConfig, "j", true);
                XposedBridge.hookAllMethods(serviceConfig, "e", XC_MethodReplacement.returnConstant(0));
                XposedBridge.hookAllMethods(serviceConfig, "b", XC_MethodReplacement.DO_NOTHING);
                logToSd("[EVO_CORE] ", "SUCCESS: Locked ServiceConfig to single-process");
            } catch (Throwable t) {}
        }

        try {
            PackageInfo pInfo = new PackageInfo();
            pInfo.packageName = "com.UCMobile";
            pInfo.applicationInfo = new ApplicationInfo(hostContext.getApplicationInfo());
            pInfo.applicationInfo.packageName = "com.UCMobile";
            pInfo.applicationInfo.className = "android.app.Application";
            pInfo.applicationInfo.sharedLibraryFiles = new String[0];

            Class<?> t0Class = XposedHelpers.findClass("com.uc.aosp.android.webkit.t0", cl);
            XposedBridge.hookAllMethods(t0Class, "b", XC_MethodReplacement.returnConstant(pInfo));
            XposedBridge.hookAllMethods(t0Class, "a", XC_MethodReplacement.returnConstant(pInfo));
            logToSd("[EVO_CORE] ", "SUCCESS: Mocked PackageInfo getters on t0 (com.UCMobile)");
        } catch (Throwable t) {
            logErr("t0 hook error", t);
        }

        try {
            Class<?> awBrowserProcess = XposedHelpers.findClass("org.chromium.android_webview.AwBrowserProcess", cl);
            XposedBridge.hookAllMethods(awBrowserProcess, "c", XC_MethodReplacement.returnConstant("com.UCMobile"));
            XposedBridge.hookAllMethods(awBrowserProcess, "b", XC_MethodReplacement.DO_NOTHING);

            XposedHelpers.findAndHookMethod(awBrowserProcess, "a", ApplicationInfo.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ApplicationInfo appInfo = (ApplicationInfo) param.args[0];
                    if (appInfo == null) {
                        appInfo = new ApplicationInfo(hostContext.getApplicationInfo());
                        param.args[0] = appInfo;
                    }
                    appInfo.packageName = "com.UCMobile";
                    if (appInfo.className == null) {
                        appInfo.className = "android.app.Application";
                    }
                    if (appInfo.sharedLibraryFiles == null) {
                        appInfo.sharedLibraryFiles = new String[0];
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked AwBrowserProcess metadata and identity methods");
        } catch (Throwable t) {
            logErr("AwBrowserProcess hook error", t);
        }

        try {
            Class<?> verifyD = XposedHelpers.findClass("com.uc.webview.internal.setup.verify.d", cl);
            XposedBridge.hookAllMethods(verifyD, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.method instanceof Method && ((Method) param.method).getReturnType().equals(boolean.class)) {
                        param.setResult(true);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Bypassed verification engine (verify.d)");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on verify.d hook: " + t.getMessage());
        }

        try {
            Class<?> libLoaderClz = XposedHelpers.findClass("org.chromium.base.library_loader.c", cl);
            XposedBridge.hookAllMethods(libLoaderClz, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 2 && param.args[0] instanceof ApplicationInfo) {
                        if (param.args[1] == null) {
                            String correctDir = findU4SdkLibDir(hostContext);
                            if (correctDir != null) {
                                param.args[1] = correctDir;
                                logToSd("[EVO_CORE] ", "Redirected LibraryLoader.a(AppInfo, null) -> libDir: " + correctDir);
                            } else {
                                param.args[1] = nativeLibDir.getAbsolutePath();
                                logToSd("[EVO_CORE] ", "NOTE: couldn't find U4 SDK lib dir, falling back to: " + nativeLibDir.getAbsolutePath());
                            }
                        }
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked LibraryLoader.a redirect");
        } catch (Throwable t) {
            logErr("LibraryLoader hook error", t);
        }

        try {
            Class<?> cmdLineV = XposedHelpers.findClass("org.chromium.base.v", cl);
            XposedBridge.hookAllMethods(cmdLineV, "a", XC_MethodReplacement.DO_NOTHING);
            logToSd("[EVO_CORE] ", "SUCCESS: Bypassed native CommandLine destroy collision (org.chromium.base.v.a)");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on org.chromium.base.v.a hook: " + t.getMessage());
        }

        /*
         * Real UC own browser-process CommandLine flag, restore when
         * real constructor call site receive null/empty instead, confirm
         * via trace diff against working session.
         */
        final String[] REAL_UC_BROWSER_CMDLINE_FLAGS = new String[] {
            "--thread-watchdog-watch-list=render:1",
            "--enable-hw-acceleration",
            "--enable-direct-compositing",
            "--top-controls-show-threshold=0",
            "--top-controls-hide-threshold=0"
        };
        String[] cmdLineCtorCandidates = new String[] {
            "org.chromium.base.v",
            "org.chromium.base.w",
            "org.chromium.base.u"
        };
        for (final String candidateName : cmdLineCtorCandidates) {
            try {
                Class<?> candidateClz = XposedHelpers.findClass(candidateName, cl);
                Constructor<?> ctor = candidateClz.getDeclaredConstructor(String[].class);
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object arg0 = param.args[0];
                        boolean isEmpty = (arg0 == null) || (arg0 instanceof String[] && ((String[]) arg0).length == 0);
                        String[] baseFlags = isEmpty ? REAL_UC_BROWSER_CMDLINE_FLAGS : (String[]) arg0;
                        param.args[0] = new ArrayList<String>(Arrays.asList(baseFlags)).toArray(new String[0]);
                    }
                });
                logToSd("[EVO_CORE] ", "SUCCESS: Hooked " + candidateName + ".<init>(String[]) for CommandLine flag patch");
            } catch (NoSuchMethodException nsme) {
                logToSd("[EVO_CORE] ", "Note: " + candidateName + " has no <init>(String[]) ctor, skipping.");
            } catch (Throwable t) {
                logToSd("[EVO_CORE] ", "Note on " + candidateName + " ctor hook: " + t.getMessage());
            }
        }

        try {
            Class<?> jniNClz = XposedHelpers.findClass("com.uc.webview.J.N", cl);
            XposedBridge.hookAllMethods(jniNClz, "M6H_IiaF", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 2 && param.args[0] instanceof Integer) {
                        int code = (Integer) param.args[0];
                        if (code == 3003 || code == 0xbbb) {
                            param.args[1] = paksDir.getAbsolutePath();
                            logToSd("[EVO_CORE] ", "Redirected M6H_IiaF PAK path -> " + paksDir.getAbsolutePath());
                        } else if (code == 3) {
                            param.args[1] = nativeLibDir.getAbsolutePath() + "/";
                            logToSd("[EVO_CORE] ", "Redirected M6H_IiaF native lib path -> " + param.args[1]);
                        }
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked com.uc.webview.J.N.M6H_IiaF path redirects");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on M6H_IiaF hook: " + t.getMessage());
        }

        try {
            Class<?> childLauncherClz = XposedHelpers.findClass("com.uc.content.browser.ChildProcessLauncherHelperImpl", cl);
            XposedBridge.hookAllMethods(childLauncherClz, "createAndStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sForceSingleProcess) {
                        param.setResult(null);
                    }
                    // else: let real sandbox child process spawn completely untouched
                }
            });
            logToSd("[EVO_CORE] ", "Hooked ChildProcessLauncherHelperImpl.createAndStart (conditional, dormant unless sForceSingleProcess).");
        } catch (Throwable t) {
            logToSd("[EVO_CORE] ", "Note on ChildProcessLauncherHelperImpl hook: " + t.getMessage());
        }

        /*
         * Confirm via real trace (2026-09-02):
         * AwContentsClientBridge.onPageStartedEx(String url) fire direct
         * with URL as plain string argument, really event-driven navigate
         * callback, replace an earlier polling-base approach completely.
         *
         * CORRECT (2026-09-02): confirm via exact diagnostic
         * (sInstance=null on every single hook fire) that MainActivity.
         * sInstance was real, subtle Xposed gotcha, a direct, compile-time
         * reference to MainActivity resolve via whatever classloader load
         * XposedInit self, which can really be a DIFFERENT classloader
         * instance than the one that load real, run MainActivity on
         * screen. Static field are per-classloader in JVM, two separate
         * load of "same" class get two completely independent copy of any
         * static field. hostContext self already IS real, run
         * MainActivity instance (pass direct as MainActivity.this from
         * applyCorePatches), call the method reflect on it direct avoid
         * whole classloader-identity problem, same proven reflect pattern
         * use everywhere else in this project.
         */
        try {
            Class<?> awccBridgeClz = XposedHelpers.findClass("org.chromium.android_webview.AwContentsClientBridge", cl);

            /*
             * Confirm via smali (2026-09-03): AwContentsClientBridge hold
             * private AwContents field, so each bridge instance IS really
             * 1:1 with one WebView native side, but no confirm way to
             * walk from there back to export.WebView Java object without
             * deeper UC-internal class we not have. Work around instead
             * by hook the bridge own constructor: WebView construct
             * always sync and single-thread in this project (see
             * MainActivity.createTabWebView()), so report every bridge
             * construct let host correlate "whichever bridge(s) appear
             * while I was inside ctor.newInstance()" to the tab it build.
             */
            XposedBridge.hookAllConstructors(awccBridgeClz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Method m = hostContext.getClass().getMethod("onWebViewBridgeCreated", Object.class);
                        m.invoke(hostContext, param.thisObject);
                    } catch (Throwable t) {
                        logErr("onWebViewBridgeCreated callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked AwContentsClientBridge constructor for per-tab bridge correlation.");

            XposedBridge.hookAllMethods(awccBridgeClz, "onPageStartedEx", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logToSd("[EVO_CORE-NAV] ", "onPageStartedEx FIRED, arg0=" + (param.args.length >= 1 ? param.args[0] : "(no args)"), true);
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        try {
                            Method m = hostContext.getClass().getMethod("onRealNavigationUrlUpdate", String.class, Object.class);
                            m.invoke(hostContext, (String) param.args[0], param.thisObject);
                        } catch (Throwable t) {
                            logErr("onPageStartedEx callback invoke failed", t);
                        }
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked AwContentsClientBridge.onPageStartedEx for real-time address bar updates.");
        } catch (Throwable t) {
            logErr("Error hooking AwContentsClientBridge.onPageStartedEx", t);
        }

        /*
         * i6.onUpdateUrl(GURL) - seen in the same real trace as
         * onPageStartedEx, sound like the more generic "the URL change"
         * event. Take GURL object, not plain String, extract via
         * getSpec() (Chromium own standard method name for this),
         * fall back to toString() if that not present.
         *
         * NOTE (2026-09-03): back/forward navigate now handle direct in
         * MainActivity.navigateHistory() by read WebView own back-forward
         * list before call goBack()/goForward(), it not depend on
         * onPageStartedEx or onUpdateUrl fire at all (confirm neither
         * does, for that case). Both hook stay in place as passive
         * secondary path, example: redirect, or in-page URL change not
         * drive by our own goBack/goForward call (JS pushState/
         * replaceState, SPA navigate), onRealNavigationUrlUpdate own
         * hasFocus() guard mean whichever of these fire just overwrite
         * bar again with authoritative value, which is fine.
         */
        try {
            Class<?> i6Clz = XposedHelpers.findClass("org.chromium.android_webview.i6", cl);
            XposedBridge.hookAllMethods(i6Clz, "onUpdateUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logToSd("[EVO_CORE-NAV] ", "onUpdateUrl FIRED, arg0=" + (param.args.length >= 1 ? param.args[0] : "(no args)"), true);
                    if (param.args.length < 1 || param.args[0] == null) return;
                    Object gurl = param.args[0];
                    String url = null;
                    try {
                        Method getSpec;
                        try {
                            getSpec = gurl.getClass().getMethod("getSpec");
                        } catch (NoSuchMethodException nsme) {
                            getSpec = gurl.getClass().getDeclaredMethod("getSpec");
                            getSpec.setAccessible(true);
                        }
                        Object result = getSpec.invoke(gurl);
                        if (result instanceof String) url = (String) result;
                    } catch (Throwable ignored) {}
                    if (url == null) url = gurl.toString();
                    logToSd("[EVO_CORE-NAV] ", "onUpdateUrl extracted url=" + url, true);
                    try {
                        Method m = hostContext.getClass().getMethod("onRealNavigationUrlUpdate", String.class);
                        m.invoke(hostContext, url);
                    } catch (Throwable t) {
                        logErr("onUpdateUrl callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked i6.onUpdateUrl for real-time address bar updates (covers back/forward navigation too).");
        } catch (Throwable t) {
            logErr("Error hooking i6.onUpdateUrl", t);
        }

        /*
         * Favicon / UA-spoof / userscript inject.
         *
         * Confirm via smali (2026-09-03): com.uc.webview.export.
         * WebChromeClient and .WebViewClient both plain concrete class
         * with public no-arg constructor and public callback method
         * (onReceivedIcon, onPageStarted, onPageFinished), not abstract,
         * not interface. MainActivity register blank, un-subclass
         * instance of each via setWebChromeClient()/setWebViewClient()
         * (see its bootExportWebView note) purely so export.WebView have
         * *something* register and really dispatch these callback at
         * all. We never need override these method in real Java code,
         * hook them direct on the class here, same way onPageStartedEx/
         * onUpdateUrl above get hook, intercept every call to them no
         * matter which instance (blank or not) receive it. This sidestep
         * the whole "can not dynamic subclass a concrete class without
         * bytecode generate" problem completely.
         *
         * SettingsStore call direct here (not route through hostContext
         * reflect), see SettingsStore own header note on why that safe
         * despite the classloader-duplicate situation that made
         * MainActivity.sInstance broken.
         */

        try {
            Class<?> webChromeClientClz = XposedHelpers.findClass("com.uc.webview.export.WebChromeClient", cl);
            XposedBridge.hookAllMethods(webChromeClientClz, "onReceivedIcon", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof Bitmap)) return;
                    try {
                        Method m = hostContext.getClass().getMethod("onRealFaviconUpdate", Bitmap.class);
                        m.invoke(hostContext, (Bitmap) param.args[1]);
                    } catch (Throwable t) {
                        logErr("onReceivedIcon callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebChromeClient.onReceivedIcon for real favicons.");
        } catch (Throwable t) {
            logErr("Error hooking WebChromeClient.onReceivedIcon", t);
        }

        /*
         * Progress bar. Confirm via smali (2026-09-04): onProgressChanged(WebView, int)
         * is trivial no-op default, same mirror-of-android.webkit pattern
         * as everything else, and unlike onPageStartedEx/onUpdateUrl, it
         * hand us WebView instance direct as param.args[0], so no
         * bridge-correlation trick need here.
         */
        try {
            Class<?> webChromeClientClzForProgress = XposedHelpers.findClass("com.uc.webview.export.WebChromeClient", cl);
            XposedBridge.hookAllMethods(webChromeClientClzForProgress, "onProgressChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof Integer)) return;
                    try {
                        Method m = hostContext.getClass().getMethod("onPageProgressChanged", Object.class, int.class);
                        m.invoke(hostContext, param.args[0], (Integer) param.args[1]);
                    } catch (Throwable t) {
                        logErr("onProgressChanged callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebChromeClient.onProgressChanged for the progress bar.");
        } catch (Throwable t) {
            logErr("Error hooking WebChromeClient.onProgressChanged", t);
        }

        /*
         * Real page title, for tab switcher list. Same no-op-default,
         * WebView-instance-in-args pattern as onProgressChanged above.
         */
        try {
            Class<?> webChromeClientClzForTitle = XposedHelpers.findClass("com.uc.webview.export.WebChromeClient", cl);
            XposedBridge.hookAllMethods(webChromeClientClzForTitle, "onReceivedTitle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                    try {
                        Method m = hostContext.getClass().getMethod("onRealTitleUpdate", Object.class, String.class);
                        m.invoke(hostContext, param.args[0], (String) param.args[1]);
                    } catch (Throwable t) {
                        logErr("onReceivedTitle callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebChromeClient.onReceivedTitle for real tab titles.");
        } catch (Throwable t) {
            logErr("Error hooking WebChromeClient.onReceivedTitle", t);
        }

        /*
         * File chooser (image/file upload, example <input type=file> on
         * site like WhatsApp Web). Confirm via smali (2026-09-03):
         * WebChromeClient.onShowFileChooser(WebView, ValueCallback<Uri[]>,
         * FileChooserParams):boolean is real, public, non-abstract method
         * (default body just return false), exact mirror of android.
         * webkit.WebChromeClient Lollipop+ public API, right down to
         * FileChooserParams.createIntent() and the static
         * FileChooserParams.parseResult(int, Intent) helper. That mean we
         * get modern public upload flow for free, no hidden-API
         * openFileChooser(...) reflect hackery like real KitKat AOSP
         * Browser had to do.
         *
         * We hook it same way as onReceivedIcon above (concrete class, no
         * subclass) and set boolean result to true so original always-
         * false body get skip. android.webkit.ValueCallback and
         * android.content.Intent are framework class, identical Class
         * object no matter which classloader call here, so no
         * classloader-duplicate concern for those two. FileChooserParams
         * self is UC own class though, so createIntent()/parseResult()
         * get call reflect.
         */
        try {
            Class<?> webChromeClientClzForChooser = XposedHelpers.findClass("com.uc.webview.export.WebChromeClient", cl);
            XposedBridge.hookAllMethods(webChromeClientClzForChooser, "onShowFileChooser", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 3 || !(param.args[1] instanceof android.webkit.ValueCallback)) {
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        final android.webkit.ValueCallback<android.net.Uri[]> valueCallback =
                            (android.webkit.ValueCallback<android.net.Uri[]>) param.args[1];
                        Object fileChooserParams = param.args[2];

                        /*
                         * Ground truth for what page/engine really ask for,
                         * help answer both "why upload not register"
                         * (compare against what MainActivity log receive)
                         * and "does this engine offer camera/gallery
                         * capture sub-intent at all" (EXTRA_INITIAL_INTENTS
                         * is where those would show up).
                         */
                        try {
                            StringBuilder sb = new StringBuilder("FileChooserParams: ");
                            try {
                                Method getMode = fileChooserParams.getClass().getMethod("getMode");
                                sb.append("mode=").append(getMode.invoke(fileChooserParams)).append(" ");
                            } catch (Throwable ignored) {}
                            try {
                                Method getAcceptTypes = fileChooserParams.getClass().getMethod("getAcceptTypes");
                                Object accept = getAcceptTypes.invoke(fileChooserParams);
                                sb.append("acceptTypes=").append(accept instanceof String[] ? java.util.Arrays.toString((String[]) accept) : accept).append(" ");
                            } catch (Throwable ignored) {}
                            try {
                                Method isCapture = fileChooserParams.getClass().getMethod("isCaptureEnabled");
                                sb.append("isCaptureEnabled=").append(isCapture.invoke(fileChooserParams)).append(" ");
                            } catch (Throwable ignored) {}
                            logToSd("[EVO_CORE-FILECHOOSER] ", sb.toString());
                        } catch (Throwable ignored) {}

                        Intent intent = null;
                        try {
                            Method createIntent = fileChooserParams.getClass().getMethod("createIntent");
                            intent = (Intent) createIntent.invoke(fileChooserParams);
                        } catch (Throwable t) {
                            logErr("onShowFileChooser.createIntent failed", t);
                        }
                        if (intent == null) {
                            intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("*/*");
                        }
                        logToSd("[EVO_CORE-FILECHOOSER] ", "createIntent() -> action=" + intent.getAction()
                            + " type=" + intent.getType()
                            + " hasInitialIntents=" + intent.hasExtra(Intent.EXTRA_INITIAL_INTENTS)
                            + " extras=" + (intent.getExtras() != null ? intent.getExtras().keySet() : "none"));

                        try {
                            Method m = hostContext.getClass().getMethod(
                                "onShowFileChooserRequested", Intent.class, android.webkit.ValueCallback.class);
                            m.invoke(hostContext, intent, valueCallback);
                            param.setResult(Boolean.TRUE);
                            logToSd("[EVO_CORE-FILECHOOSER] ", "Dispatched file chooser request to host.");
                        } catch (Throwable t) {
                            logErr("onShowFileChooserRequested callback invoke failed", t);
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable t) {
                        logErr("onShowFileChooser hook failed", t);
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebChromeClient.onShowFileChooser for native file/image picker.");
        } catch (Throwable t) {
            logErr("Error hooking WebChromeClient.onShowFileChooser", t);
        }

        /*
         * Camera/mic (getUserMedia) permission request.
         *
         * Confirm via smali (2026-09-03): WebChromeClient.onPermissionRequest
         * (PermissionRequest) is real, public, non-abstract, and its default
         * body just call request.deny() no matter what. That the whole
         * reason camera/mic access silently fail right now: with blank
         * WebChromeClient register, every getUserMedia() call get deny
         * before page JS promise even have chance resolve.
         *
         * We only need PermissionRequest.getResources()/grant(String[])/deny()
         * to exist, not their exact RESOURCE_* constant string value, since
         * we just grant back whatever resources[] the page ask for instead
         * of hardcode the constant literal. hostContext resolve OS-level
         * camera/mic permission (and runtime prompt on API 23+) before
         * really call grant().
         */
        try {
            Class<?> webChromeClientClzForPermission = XposedHelpers.findClass("com.uc.webview.export.WebChromeClient", cl);
            XposedBridge.hookAllMethods(webChromeClientClzForPermission, "onPermissionRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 1 || param.args[0] == null) return;
                        final Object permissionRequest = param.args[0];
                        try {
                            Method m = hostContext.getClass().getMethod("onWebViewPermissionRequested", Object.class);
                            m.invoke(hostContext, permissionRequest);
                            /*
                             * Skip original body (which would just call
                             * request.deny()), host now own resolve this
                             * request.
                             */
                            param.setResult(null);
                        } catch (Throwable t) {
                            logErr("onWebViewPermissionRequested callback invoke failed - falling back to default deny()", t);
                        }
                    } catch (Throwable t) {
                        logErr("onPermissionRequest hook failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebChromeClient.onPermissionRequest for camera/mic grants.");
        } catch (Throwable t) {
            logErr("Error hooking WebChromeClient.onPermissionRequest", t);
        }

        try {
            Class<?> webViewClientClz = XposedHelpers.findClass("com.uc.webview.export.WebViewClient", cl);

            XposedBridge.hookAllMethods(webViewClientClz, "onPageStarted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                    Object webView = param.args[0];
                    String url = (String) param.args[1];
                    applyUserAgentIfConfigured(webView, url);
                    runMatchingScripts(webView, url, SettingsStore.RUN_AT_START);
                }
            });

            XposedBridge.hookAllMethods(webViewClientClz, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                    Object webView = param.args[0];
                    String url = (String) param.args[1];
                    runMatchingScripts(webView, url, SettingsStore.RUN_AT_END);
                }
            });

            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebViewClient.onPageStarted/onPageFinished for UA + userscript timing.");
        } catch (Throwable t) {
            logErr("Error hooking WebViewClient onPageStarted/onPageFinished", t);
        }

        /*
         * shouldOverrideUrlLoading fire BEFORE page/link navigate request
         * go out (confirm via smali: the (WebView, WebResourceRequest)
         * overload just extract URL and delegate straight to the
         * (WebView, String) overload, which default to "return false",
         * mean stock WebViewClient behavior, not take over navigate).
         * That earlier than onPageStarted, which fire after request for
         * THAT navigate already go out, so this the hook that let us
         * resolve UA in time for the CURRENT navigate on link click, not
         * just the next one. We only resolve UA here and on purpose
         * never call param.setResult(), we not take over navigate, just
         * front-run it, so original method still run and return its
         * normal false.
         */
        try {
            Class<?> webViewClientClzForUrlLoading = XposedHelpers.findClass("com.uc.webview.export.WebViewClient", cl);
            Class<?> webResourceRequestClz = XposedHelpers.findClass("com.uc.webview.export.WebResourceRequest", cl);
            Class<?> ucWebViewClz = XposedHelpers.findClass("com.uc.webview.export.WebView", cl);

            XposedHelpers.findAndHookMethod(webViewClientClzForUrlLoading, "shouldOverrideUrlLoading",
                ucWebViewClz, webResourceRequestClz, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 2 || param.args[1] == null) return;
                            Method getUrl = param.args[1].getClass().getMethod("getUrl");
                            Object uri = getUrl.invoke(param.args[1]);
                            String url = uri == null ? null : uri.toString();
                            if (url == null) return;
                            try {
                                Method m = hostContext.getClass().getMethod("onWillNavigateTo", String.class);
                                m.invoke(hostContext, url);
                            } catch (Throwable t) {
                                logErr("onWillNavigateTo callback invoke failed", t);
                            }
                        } catch (Throwable t) {
                            logErr("shouldOverrideUrlLoading hook failed", t);
                        }
                    }
                }
            );
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked WebViewClient.shouldOverrideUrlLoading for pre-navigation UA resolution.");
        } catch (Throwable t) {
            logErr("Error hooking WebViewClient.shouldOverrideUrlLoading", t);
        }

        /*
         * "Prevent auto keyboard wake" setting, confirm via smali
         * (2026-09-04): org.chromium.content.browser.input.ImeAdapterImpl.t()
         * is real Chromium own soft-keyboard-show gatekeeper, it start
         * with precondition check (call its own i() helper, return right
         * away if that false) and, when that pass, is the method that end
         * up construct/use a ShowKeyboardResultReceiver to really request
         * the IME. Skip whole method via setResult(null) when host say to
         * (see MainActivity.shouldSuppressAutoKeyboard()) stop the
         * keyboard, and the window resize that come with it, from ever
         * start, instead of reactive hide it after the fact.
         */
        try {
            Class<?> imeAdapterClz = XposedHelpers.findClass("org.chromium.content.browser.input.ImeAdapterImpl", cl);
            XposedBridge.hookAllMethods(imeAdapterClz, "t", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Method m = hostContext.getClass().getMethod("shouldSuppressAutoKeyboard");
                        Object result = m.invoke(hostContext);
                        if (Boolean.TRUE.equals(result)) {
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        logErr("shouldSuppressAutoKeyboard callback invoke failed", t);
                    }
                }
            });
            logToSd("[EVO_CORE] ", "SUCCESS: Hooked ImeAdapterImpl.t() for prevent-auto-keyboard-wake.");
        } catch (Throwable t) {
            logErr("Error hooking ImeAdapterImpl.t()", t);
        }
    }

    /*
     * Apply SettingsStore resolve UA for this URL, if any configure,
     * direct onto WebView instance own WebSettings. This the
     * WebViewClient-hook-side backup for navigate MainActivity not self
     * initiate (in-page link click), it take effect one page later than
     * MainActivity own applyResolvedUserAgent() pre-set (see that method
     * note), which is the well-know, unavoidable lag for this style of
     * UA hook: current page own request already go out by time
     * onPageStarted fire for it. (shouldOverrideUrlLoading, hook
     * separate below, close that lag for page/link-initiate navigate
     * specific, by resolve UA *before* request go out instead of after.)
     *
     * Before this have same bug MainActivity version had: when
     * resolveUserAgent() return null (no match rule, no global UA), it
     * just skip set anything, leave whatever PREVIOUS site rule had set
     * still active, since setUserAgentString() never reset self. Now it
     * always active set something, fall back to engine real default UA
     * (fetch from MainActivity, which capture it once at WebView create
     * time, before this hook ever override it).
     */
    private static void applyUserAgentIfConfigured(Object webView, String url) {
        if (webView == null) return;
        try {
            String ua = SettingsStore.resolveUserAgent(uaSettingsContext, url);
            String effectiveUa = ua != null ? ua : getHostDefaultUserAgent();
            if (effectiveUa == null) return; // no rule, and could not fetch default either, leave engine current UA alone
            Object settings = XposedHelpers.callMethod(webView, "getSettings");
            if (settings != null) {
                XposedHelpers.callMethod(settings, "setUserAgentString", effectiveUa);
                logToSd("[EVO_CORE-UA] ", (ua != null ? "Set rule UA" : "Reset default UA") + " for [" + url + "] -> " + effectiveUa);
            }
        } catch (Throwable t) {
            logErr("applyUserAgentIfConfigured failed", t);
        }
    }

    private static String getHostDefaultUserAgent() {
        if (sHostActivity == null) return null;
        try {
            Method m = sHostActivity.getClass().getMethod("getDefaultUserAgent");
            Object result = m.invoke(sHostActivity);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            logErr("getHostDefaultUserAgent failed", t);
            return null;
        }
    }

    /*
     * Run every userscript rule match this URL and timing.
     * XposedHelpers.callMethod() resolve overload from each argument
     * *runtime* type, a literal null have none, so evaluateJavascript
     * call below can fail resolve against WebView.smali confirm
     * evaluateJavascript(String, ValueCallback) signature. That exactly
     * why catch block fall back to classic loadUrl("javascript:...")
     * inject style instead of try fix the overload lookup (example
     * XposedHelpers.findMethodBestMatch), it simpler, and work identical
     * across any WebView-like implementation no matter this
     * reflection-library detail.
     */
    private static void runMatchingScripts(Object webView, String url, String runAt) {
        if (webView == null) return;
        List<SettingsStore.Rule> matches = SettingsStore.matchingScripts(uaSettingsContext, url, runAt);
        for (SettingsStore.Rule rule : matches) {
            String wrapped = "(function(){try{" + rule.script + "}catch(e){console.error('[UserScript Error]',e);}})();";
            try {
                XposedHelpers.callMethod(webView, "evaluateJavascript", wrapped, (android.webkit.ValueCallback<String>) null);
                logToSd("[EVO_CORE-SCRIPT] ", "Ran [" + runAt + "] script for [" + url + "] (pattern: " + rule.pattern + ")");
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(webView, "loadUrl", "javascript:" + wrapped);
                } catch (Throwable t2) {
                    logErr("runMatchingScripts failed for pattern " + rule.pattern, t2);
                }
            }
        }
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.evoworld.uckitkatwebview")) {
            return;
        }

        /*
         * Clear log at start of every run, right here since this the
         * very first thing execute in our process (handleLoadPackage run
         * before MainActivity.onCreate()), both this class logToSd() and
         * MainActivity own writeToSdCard() write to this same physical
         * path, so clear it once here cover both.
         */
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File existingLog = new File(sdcard, "uckitkatwebview.log");
            if (existingLog.exists()) {
                existingLog.delete();
            }
        } catch (Throwable ignored) {}

        logToSd("[EVO_CORE] ", "Hooking host package: " + lpparam.packageName);

        try {
            Class<?> mainActClz = XposedHelpers.findClass("com.evoworld.uckitkatwebview.MainActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mainActClz, "applyCorePatches", ClassLoader.class, Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ClassLoader cl = (ClassLoader) param.args[0];
                    Context ctx = (Context) param.args[1];
                    /*
                     * Tell host Activity this hook really fire,
                     * MainActivity.classifyEngineFailure() rely on this to
                     * tell "module not enable" apart from genuine UC core
                     * init fail when something later go wrong. Call via
                     * reflect (not direct cast) since ctx was load through
                     * TARGET app own classloader (param.args[1], same as
                     * every other host callback in this file), which
                     * maybe not assignment-compatible with whatever
                     * classloader load this MainActivity reference inside
                     * Xposed module own class space.
                     */
                    try {
                        ctx.getClass().getMethod("markXposedModuleActive").invoke(ctx);
                    } catch (Throwable t) {
                        logErr("markXposedModuleActive callback invoke failed", t);
                    }
                    patchCoreFramework(cl, ctx);
                }
            });
            logToSd("[EVO_CORE] ", "MainActivity.applyCorePatches successfully intercepted via Xposed.");
        } catch (Throwable t) {
            logErr("Error hooking MainActivity.applyCorePatches", t);
        }
    }
}