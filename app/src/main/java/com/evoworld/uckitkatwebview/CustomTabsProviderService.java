package com.evoworld.uckitkatwebview;

import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsSessionToken;

import java.util.List;

/*
 * Real Custom Tabs protocol provider - same open protocol Chrome,
 * Firefox, Samsung Internet etc all implement. androidx.browser own
 * CustomTabsService abstract base class already correctly implement
 * the whole AIDL/Binder wire protocol internal (device-level
 * minSdkVersion 16, well under this project own 19 floor, confirm real
 * current AOSP source before add - see build.gradle) - hand-write the
 * raw AIDL ourself instead would risk a silent transaction-order
 * mistake that break real calling app in a confusing way neither side
 * could easily diagnose, Google own library guarantee protocol
 * correctness instead, much safer than everything else in this project
 * (which all safely catch Throwable on a reflection miss - a raw AIDL
 * mismatch would not be that forgiving).
 *
 * warmup()/newSession()/mayLaunchUrl() are advisory-only calls real
 * client apps use to prewarm/hint likely navigation BEFORE the user
 * actually tap - we don't build a pre-warm WebView pool for this yet,
 * just accept and return true so the calling app own flow proceed
 * normal. The real URL always still arrive at MainActivity as a plain
 * ACTION_VIEW intent when the client actually call
 * CustomTabsIntent.launchUrl() - same intent-filter/onNewIntent() path
 * as a normal "Open with" link, see MainActivity and AndroidManifest.
 */
public class CustomTabsProviderService extends CustomTabsService {

    /*
     * NEW INFO (2026-09-07): "Maps Go" custom-tabs-wrapper open our
     * MainActivity but never navigate anywhere, fall back to normal
     * newtab/last-url. Two very different possible cause: (1) service
     * connection here never even happen (client couldn't bind/resolve us
     * as a provider at all, silently fall back to some bare launch with
     * no URL attached), or (2) connection DOES happen fine but the actual
     * ACTION_VIEW+data intent MainActivity later receive is malformed/
     * missing data somehow. Log every one of these calls (same static
     * MainActivity.writeToSdCard(), only static/public logging entry
     * point reachable from a class that isn't MainActivity itself) so
     * next repro tell us for real which of the two it is - if NONE of
     * these ever appear in the log, that already answer it (case 1).
     */
    @Override
    protected boolean warmup(long flags) {
        MainActivity.writeToSdCard("[EVO_CUSTOMTABS_DIAG] warmup(flags=" + flags + ") called");
        return true;
    }

    @Override
    protected boolean newSession(CustomTabsSessionToken sessionToken) {
        MainActivity.writeToSdCard("[EVO_CUSTOMTABS_DIAG] newSession() called");
        return true;
    }

    @Override
    protected boolean mayLaunchUrl(CustomTabsSessionToken sessionToken, Uri url, Bundle extras, List<Bundle> otherLikelyBundles) {
        MainActivity.writeToSdCard("[EVO_CUSTOMTABS_DIAG] mayLaunchUrl(url=" + url + ")");
        // no pre-warm pool built yet - accept the hint, do nothing with it, real navigation still handle fully correct when it actually arrive
        return true;
    }

    @Override
    protected Bundle extraCommand(String commandName, Bundle args) {
        // vendor-extension escape hatch, no non-standard commands supported
        return null;
    }

    @Override
    protected boolean updateVisuals(CustomTabsSessionToken sessionToken, Bundle bundle) {
        return false;
    }

    @Override
    protected boolean requestPostMessageChannel(CustomTabsSessionToken sessionToken, Uri postMessageOrigin) {
        return false;
    }

    @Override
    protected int postMessage(CustomTabsSessionToken sessionToken, String message, Bundle extras) {
        return CustomTabsService.RESULT_FAILURE_DISALLOWED;
    }

    @Override
    protected boolean validateRelationship(CustomTabsSessionToken sessionToken, int relation, Uri origin, Bundle extras) {
        return false;
    }

    @Override
    protected boolean receiveFile(CustomTabsSessionToken sessionToken, Uri uri, int purpose, Bundle extras) {
        return false;
    }
}