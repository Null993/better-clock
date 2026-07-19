package io.github.miuiclock;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final Map<TextView, ClockTicker> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<TextView, ClockTicker>());
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                }
            };
    private static final AtomicBoolean WRITING_CLOCK = new AtomicBoolean(false);
    private static WeakReference<TextView> clockOwner = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)
                || !SYSTEM_UI.equals(lpparam.processName)) {
            return;
        }

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (WRITING_CLOCK.get() || !(param.thisObject instanceof TextView)) return;
                TextView view = (TextView) param.thisObject;
                if (!isCarrierView(view)) return;
                installTicker(view);
            }
        });

        XposedBridge.hookAllMethods(TextView.class, "onDraw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (WRITING_CLOCK.get() || !(param.thisObject instanceof TextView)) return;
                TextView view = (TextView) param.thisObject;
                if (isCarrierView(view)) refreshClock(view);
            }
        });

        XposedBridge.log("MiuiLockscreenClock: hook installed in SystemUI");
    }

    private static void installTicker(final TextView view) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(view)) return;
            ClockTicker ticker = new ClockTicker(view);
            ACTIVE.put(view, ticker);
            // View.post is safe even when SystemUI loads the module before the main Looper exists:
            // Android queues the action on the view until it becomes attached.
            view.post(ticker);
            XposedBridge.log("MiuiLockscreenClock: carrier view matched: "
                    + resourceName(view) + " (" + view.getClass().getName() + ")");
        }
    }

    private static boolean isCarrierView(TextView view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;

        final String name = resourceName(view);
        if (name.isEmpty()) return false;

        // Confirmed by Xiaomi 17 / HyperOS 3 logs. Do not use contains(): SystemUI also has
        // keyguard_carrier_separator, shade_carrier_text and no_carrier_text, and turning all of
        // those into clocks produces several overlapping tickers.
        return "carrier_text".equals(name);
    }

    private static boolean isKeyguardLocked(TextView view) {
        Context context = view.getContext();
        KeyguardManager manager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    private static void writeTime(TextView view) {
        String time = FORMAT.get().format(new Date());
        if (TextUtils.equals(view.getText(), time)) return;
        WRITING_CLOCK.set(true);
        try {
            view.setText(time);
            // Prevent an overly narrow carrier field from clipping the seconds.
            view.setSingleLine(true);
            view.setEllipsize(null);
        } finally {
            WRITING_CLOCK.set(false);
        }
    }

    private static void refreshClock(TextView view) {
        if (isKeyguardLocked(view)
                && isVisibleTopLeft(view)
                && acquireClockOwner(view)) {
            writeTime(view);
        }
    }

    private static boolean isVisibleTopLeft(TextView view) {
        if (!view.isAttachedToWindow() || !view.isShown()
                || view.getAlpha() <= 0.01f || view.getWindowVisibility() != View.VISIBLE) {
            return false;
        }
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible) || visible.isEmpty()) return false;
        int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = view.getResources().getDisplayMetrics().heightPixels;
        return visible.centerX() < screenWidth / 2 && visible.centerY() < screenHeight / 3;
    }

    private static synchronized boolean acquireClockOwner(TextView candidate) {
        TextView owner = clockOwner.get();
        if (owner == candidate) return true;
        if (owner == null || !isVisibleTopLeft(owner)) {
            clockOwner = new WeakReference<>(candidate);
            XposedBridge.log("MiuiLockscreenClock: active clock selected: "
                    + resourceName(candidate));
            return true;
        }
        return false;
    }

    private static final class ClockTicker implements Runnable {
        private final java.lang.ref.WeakReference<TextView> reference;
        private long firstDetachedAt;

        ClockTicker(TextView view) {
            reference = new java.lang.ref.WeakReference<>(view);
        }

        @Override
        public void run() {
            TextView view = reference.get();
            if (view == null) return;

            if (view.isAttachedToWindow()) {
                firstDetachedAt = 0L;
                refreshClock(view);
                long delay = 1000L - (System.currentTimeMillis() % 1000L);
                view.postDelayed(this, Math.max(50L, delay));
            } else {
                // Inflation often calls setText before attachment. Wait briefly, but stop polling
                // stale views after five seconds; a replacement view will trigger setText again.
                long now = android.os.SystemClock.uptimeMillis();
                if (firstDetachedAt == 0L) {
                    firstDetachedAt = now;
                    view.postDelayed(this, 100L);
                } else if (now - firstDetachedAt < 5000L) {
                    view.postDelayed(this, 100L);
                } else {
                    synchronized (ACTIVE) {
                        ACTIVE.remove(view);
                    }
                }
            }
        }
    }

    private static String resourceName(TextView view) {
        try {
            Resources resources = view.getResources();
            return resources.getResourceEntryName(view.getId()).toLowerCase(Locale.ROOT);
        } catch (Resources.NotFoundException ignored) {
            return "";
        }
    }
}
