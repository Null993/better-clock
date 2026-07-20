package io.github.betterclock;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String LOG_PREFIX = "LockscreenCarrierClock: ";
    private static final int TARGET_NONE = 0;
    private static final int TARGET_CARRIER = 1;
    private static final int TARGET_STATUS_CLOCK = 2;
    private static final Map<TextView, ClockTicker> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<TextView, ClockTicker>());
    private static final Set<TextView> CONFIGURED =
            Collections.newSetFromMap(new WeakHashMap<TextView, Boolean>());
    private static final Set<String> LOGGED_CANDIDATES =
            Collections.synchronizedSet(new HashSet<String>());
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
        if (!SYSTEM_UI.equals(lpparam.packageName) || !isSystemUiProcess(lpparam.processName)) {
            return;
        }

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (WRITING_CLOCK.get() || !(param.thisObject instanceof TextView)) return;
                TextView view = (TextView) param.thisObject;
                logOplusCandidate(view);
                if (targetType(view) == TARGET_NONE) return;
                installTicker(view);
                // ColorOS writes its minute-only value while switching from keyguard to the
                // desktop. Replace it before setText returns instead of waiting for the ticker.
                refreshClock(view);
            }
        });

        XposedBridge.hookAllMethods(TextView.class, "onDraw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (WRITING_CLOCK.get() || !(param.thisObject instanceof TextView)) return;
                TextView view = (TextView) param.thisObject;
                if (targetType(view) != TARGET_NONE) refreshClock(view);
            }
        });

        XposedBridge.log(LOG_PREFIX + "hook installed in SystemUI; device="
                + Build.MANUFACTURER + "/" + Build.BRAND
                + "; process=" + lpparam.processName + "; format=HH:mm:ss");
    }

    private static void installTicker(final TextView view) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(view)) return;
            ClockTicker ticker = new ClockTicker(view);
            ACTIVE.put(view, ticker);
            configureClockView(view);
            // View.post is safe even when SystemUI loads the module before the main Looper exists:
            // Android queues the action on the view until it becomes attached.
            view.post(ticker);
            XposedBridge.log(LOG_PREFIX + "managed view matched: target="
                    + targetType(view) + "; " + resourceName(view)
                    + " (" + view.getClass().getName() + ")");
        }
    }

    private static int targetType(TextView view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return TARGET_NONE;

        final String name = resourceName(view);
        if (name.isEmpty()) return TARGET_NONE;

        if (isXiaomiDevice()) {
            // Confirmed by Xiaomi 17 / HyperOS 3 logs. Do not use contains(): SystemUI also has
            // separator/shade/no-carrier views that otherwise produce overlapping clocks.
            return "carrier_text".equals(name) ? TARGET_CARRIER : TARGET_NONE;
        }

        if (isOplusDevice()) {
            String className = view.getClass().getName().toLowerCase(Locale.ROOT);
            boolean carrierRelated = name.contains("carrier")
                    || name.contains("operator")
                    || name.contains("plmn")
                    || className.contains("carriertext")
                    || className.contains("operatorname");
            boolean excluded = name.contains("separator")
                    || name.startsWith("no_carrier")
                    || name.contains("shade_")
                    || name.contains("qs_")
                    || name.contains("quick_setting")
                    || name.contains("status_bar");
            if (carrierRelated && !excluded) return TARGET_CARRIER;

            boolean clockRelated = name.equals("clock")
                    || name.equals("status_bar_clock")
                    || name.equals("header_clock")
                    || name.equals("qs_clock")
                    || name.equals("oplus_qs_clock")
                    || name.equals("qs_footer_clock")
                    || name.contains("status_clock")
                    || name.contains("clock_text")
                    || className.contains("oplusqsclock")
                    || className.contains("statclock")
                    || className.contains("statusbar.policy.clock")
                    || className.contains("statusbar.widget.clock");
            boolean lockscreenClock = name.contains("keyguard")
                    || name.contains("lockscreen")
                    || name.contains("aod")
                    || name.contains("date")
                    || className.contains("keyguard")
                    || className.contains("aod");
            if (clockRelated && !lockscreenClock) return TARGET_STATUS_CLOCK;
        }

        return ("carrier_text".equals(name) || "keyguard_carrier_text".equals(name))
                ? TARGET_CARRIER : TARGET_NONE;
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
            view.setText(styledTime(view, time));
            // Prevent an overly narrow carrier field from clipping the seconds.
            view.setSingleLine(true);
            view.setEllipsize(null);
        } finally {
            WRITING_CLOCK.set(false);
        }
    }

    private static CharSequence styledTime(TextView view, String time) {
        if (!isOplusStatusClock(view)) return time;

        int red = resolveOplusRed(view);
        if (red == 0) return time;

        SpannableString styled = new SpannableString(time);
        // OnePlus colors only digit "1" in the two-position hour field.
        for (int i = 0; i < Math.min(2, time.length()); i++) {
            if (time.charAt(i) == '1') {
                styled.setSpan(new ForegroundColorSpan(red), i, i + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }

    private static int resolveOplusRed(TextView view) {
        int id = view.getResources().getIdentifier(
                "red_clock_hour_color", "color", SYSTEM_UI);
        if (id != 0) {
            try {
                return view.getResources().getColor(id, view.getContext().getTheme());
            } catch (Resources.NotFoundException ignored) {
                // Fall through and reuse the color span installed by ColorOS.
            }
        }
        CharSequence current = view.getText();
        if (current instanceof Spanned) {
            ForegroundColorSpan[] spans = ((Spanned) current).getSpans(
                    0, current.length(), ForegroundColorSpan.class);
            if (spans.length > 0) return spans[0].getForegroundColor();
        }
        return 0;
    }

    private static void configureClockView(TextView view) {
        if (!isOplusStatusClock(view)) return;
        synchronized (CONFIGURED) {
            if (!CONFIGURED.add(view)) return;
        }

        // Keep ColorOS' native proportional glyph spacing. Enabling OpenType "tnum" makes
        // StatClock noticeably looser than the QS clock on OPlusSans. A fixed container width
        // alone is sufficient to keep the adjacent date from moving as the seconds change.
        float widestDigit = 0f;
        for (char digit = '0'; digit <= '9'; digit++) {
            widestDigit = Math.max(widestDigit,
                    view.getPaint().measureText(String.valueOf(digit)));
        }
        int width = (int) Math.ceil(widestDigit * 6f
                + view.getPaint().measureText("::"))
                + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
        view.setMinWidth(width);
        view.setMaxWidth(width);
    }

    private static boolean isOplusStatusClock(TextView view) {
        return isOplusDevice() && targetType(view) == TARGET_STATUS_CLOCK;
    }

    private static void refreshClock(TextView view) {
        int target = targetType(view);
        if (target == TARGET_CARRIER
                && isKeyguardLocked(view)
                && isVisibleTopLeft(view)
                && acquireClockOwner(view)) {
            writeTime(view);
        } else if (target == TARGET_STATUS_CLOCK && isOplusDevice()) {
            // ColorOS prepares the desktop status bar while it is still hidden during the
            // keyguard exit animation. Keeping the backing TextView updated prevents its
            // minute-only value from becoming visible for one frame to one second on unlock.
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
            XposedBridge.log(LOG_PREFIX + "active clock selected: "
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

    private static boolean isXiaomiDevice() {
        String vendor = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return vendor.contains("xiaomi") || vendor.contains("redmi") || vendor.contains("poco");
    }

    private static boolean isSystemUiProcess(String processName) {
        // AOSP/HyperOS hosts lock-screen UI in the package's main process. ColorOS uses a
        // dedicated :ui process; screenshot, tuner and fgservices processes are unrelated.
        return SYSTEM_UI.equals(processName) || (SYSTEM_UI + ":ui").equals(processName);
    }

    private static boolean isOplusDevice() {
        String vendor = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return vendor.contains("oneplus") || vendor.contains("oppo")
                || vendor.contains("realme") || vendor.contains("oplus");
    }

    private static void logOplusCandidate(TextView view) {
        if (!isOplusDevice()) return;
        String name = resourceName(view);
        String className = view.getClass().getName();
        String searchable = (name + " " + className).toLowerCase(Locale.ROOT);
        if (!(searchable.contains("carrier") || searchable.contains("operator")
                || searchable.contains("plmn") || searchable.contains("clock"))) {
            return;
        }
        String key = name + "|" + className;
        if (LOGGED_CANDIDATES.add(key)) {
            XposedBridge.log(LOG_PREFIX + "OnePlus candidate: " + key);
        }
    }
}
