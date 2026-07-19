package io.github.miuiclock;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS deliberately changes and obfuscates SystemUI implementation classes between builds.
 * This hook therefore targets the stable Android TextView API and recognizes the carrier view by
 * its resource entry name instead of depending on a Xiaomi implementation class.
 */
public final class LockScreenClockHook implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<TextView, ClockTicker> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<TextView, ClockTicker>());
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                }
            };
    private static volatile boolean writingClock;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)
                || !SYSTEM_UI.equals(lpparam.processName)) {
            return;
        }

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (writingClock || !(param.thisObject instanceof TextView)) return;
                TextView view = (TextView) param.thisObject;
                if (!isCarrierView(view)) return;
                installTicker(view);
            }
        });

        XposedBridge.log("MiuiLockscreenClock: hook installed in SystemUI");
    }

    private static void installTicker(final TextView view) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(view)) return;
            ClockTicker ticker = new ClockTicker(view);
            ACTIVE.put(view, ticker);
            MAIN.post(ticker);
        }
    }

    private static boolean isCarrierView(TextView view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;

        final String name;
        try {
            Resources resources = view.getResources();
            name = resources.getResourceEntryName(id).toLowerCase(Locale.ROOT);
        } catch (Resources.NotFoundException ignored) {
            return false;
        }

        boolean carrierName =
                name.contains("keyguard_carrier")
                || name.contains("carrier_text")
                || name.contains("carrier_name")
                || name.contains("operator_name")
                || name.contains("keyguard_operator")
                || name.contains("keyguard_sim");
        if (!carrierName) return false;

        // Exclude QS/mobile-network labels. Xiaomi lock-screen resource names normally carry one
        // of these lock-screen signals; generic carrier_text is accepted and gated by Keyguard.
        return !name.contains("qs_")
                && !name.contains("mobile_network")
                && !name.contains("status_bar");
    }

    private static boolean isKeyguardLocked(TextView view) {
        Context context = view.getContext();
        KeyguardManager manager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    private static void writeTime(TextView view) {
        writingClock = true;
        try {
            view.setText(FORMAT.get().format(new Date()));
            // Prevent an overly narrow carrier field from clipping the seconds.
            view.setSingleLine(true);
            view.setEllipsize(null);
        } finally {
            writingClock = false;
        }
    }

    private static final class ClockTicker implements Runnable {
        private final java.lang.ref.WeakReference<TextView> reference;

        ClockTicker(TextView view) {
            reference = new java.lang.ref.WeakReference<>(view);
        }

        @Override
        public void run() {
            TextView view = reference.get();
            if (view == null) return;

            if (view.isAttachedToWindow()) {
                if (isKeyguardLocked(view)) writeTime(view);
                long delay = 1000L - (System.currentTimeMillis() % 1000L);
                MAIN.postDelayed(this, Math.max(50L, delay));
            } else {
                // Inflation often calls setText before attachment. Wait briefly, but stop polling
                // stale views after five seconds; a replacement view will trigger setText again.
                Object firstSeen = view.getTag(io.github.miuiclock.R.id.clock_first_seen);
                long now = SystemClock.uptimeMillis();
                if (!(firstSeen instanceof Long)) {
                    view.setTag(io.github.miuiclock.R.id.clock_first_seen, now);
                    MAIN.postDelayed(this, 100L);
                } else if (now - (Long) firstSeen < 5000L) {
                    MAIN.postDelayed(this, 100L);
                } else {
                    synchronized (ACTIVE) {
                        ACTIVE.remove(view);
                    }
                }
            }
        }
    }
}
