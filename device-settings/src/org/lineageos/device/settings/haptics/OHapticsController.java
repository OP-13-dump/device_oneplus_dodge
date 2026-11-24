/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.haptics;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * O-Haptics, ported from OxygenOS.
 *
 * <p>The touch style and the two stepless intensities are system properties read
 * by the vibrator HAL's dodge effect library (vibrator/effect/effect.cpp). The
 * intensity units are the stock ones: 2400 is full drive, the sliders move in
 * steps of 100 like stock's StepLessVibrationUtils.
 */
public final class OHapticsController {
    private static final String TAG = "OHapticsController";

    public static final String PROP_PROFILE = "persist.sys.haptic_profile";
    public static final String PROP_TOUCH_STRENGTH = "persist.sys.haptic_touch_strength";
    public static final String PROP_VIB_STRENGTH = "persist.sys.haptic_vib_strength";

    /** Stock def_style waveforms, the OxygenOS default. */
    public static final String STYLE_CRISP = "crisp";
    /** Stock soft_style waveforms. */
    public static final String STYLE_GENTLE = "gentle";
    /** Built-in RichTap tables. */
    public static final String STYLE_RICHTAP = "richtap";
    public static final String STYLE_DEFAULT = STYLE_CRISP;

    public static final int STRENGTH_STEP = 100;
    public static final int STRENGTH_MAX = 2400;
    /** Touch haptics: 800..2400, default 2000 (stock, LuXun motor). */
    public static final int TOUCH_STRENGTH_MIN = 800;
    public static final int TOUCH_STRENGTH_DEFAULT = 2000;
    /** Calls, notifications and alarms: 700..2400, default full drive. */
    public static final int VIB_STRENGTH_MIN = 700;
    public static final int VIB_STRENGTH_DEFAULT = 2400;

    private static final VibrationAttributes TOUCH_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    // Alarms vibrate in every ringer mode, so the preview is always felt.
    private static final VibrationAttributes VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM);

    private static final long[] STYLE_PREVIEW_DELAYS_MS = {0, 150, 450};
    private static final int[] STYLE_PREVIEW_EFFECTS = {
        VibrationEffect.EFFECT_CLICK,
        VibrationEffect.EFFECT_CLICK,
        VibrationEffect.EFFECT_HEAVY_CLICK,
    };

    private final Context mContext;
    private final Vibrator mVibrator;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public OHapticsController(Context context) {
        mContext = context;
        mVibrator = context.getSystemService(Vibrator.class);
    }

    // ===== Touch style =====

    /** Maps current and pre-O-Haptics profile names onto the three styles. */
    public static String normalizeStyle(String style) {
        if (style == null) return STYLE_DEFAULT;
        switch (style) {
            case STYLE_RICHTAP:
                return STYLE_RICHTAP;
            case STYLE_GENTLE:
            case "soft":
            case "op13gentle":
            case "op13soft":
                return STYLE_GENTLE;
            default:
                // crisp, op13crisp, op13def
                return STYLE_CRISP;
        }
    }

    public static String getStyle() {
        return normalizeStyle(SystemProperties.get(PROP_PROFILE, STYLE_DEFAULT));
    }

    public static void setStyle(String style) {
        SystemProperties.set(PROP_PROFILE, normalizeStyle(style));
    }

    /** Rewrites profile names from older builds (op13crisp etc.) once at boot. */
    public static void migrateLegacyProfile() {
        String current = SystemProperties.get(PROP_PROFILE, "");
        if (TextUtils.isEmpty(current)) return;
        String style = normalizeStyle(current);
        if (!style.equals(current)) {
            Log.i(TAG, "Migrating haptic profile " + current + " -> " + style);
            SystemProperties.set(PROP_PROFILE, style);
        }
    }

    // ===== Intensity =====

    public static int getTouchStrength() {
        return clampStrength(SystemProperties.getInt(PROP_TOUCH_STRENGTH, TOUCH_STRENGTH_DEFAULT),
                TOUCH_STRENGTH_MIN, TOUCH_STRENGTH_DEFAULT);
    }

    public static void setTouchStrength(int strength) {
        SystemProperties.set(PROP_TOUCH_STRENGTH, String.valueOf(
                clampStrength(strength, TOUCH_STRENGTH_MIN, TOUCH_STRENGTH_DEFAULT)));
    }

    public static int getVibrationStrength() {
        return clampStrength(SystemProperties.getInt(PROP_VIB_STRENGTH, VIB_STRENGTH_DEFAULT),
                VIB_STRENGTH_MIN, VIB_STRENGTH_DEFAULT);
    }

    public static void setVibrationStrength(int strength) {
        SystemProperties.set(PROP_VIB_STRENGTH, String.valueOf(
                clampStrength(strength, VIB_STRENGTH_MIN, VIB_STRENGTH_DEFAULT)));
    }

    /** Slider position (0-based steps of 100) for a strength. */
    public static int strengthToLevel(int strength, int min) {
        return (strength - min) / STRENGTH_STEP;
    }

    public static int levelToStrength(int level, int min) {
        return min + level * STRENGTH_STEP;
    }

    public static int maxLevel(int min) {
        return strengthToLevel(STRENGTH_MAX, min);
    }

    private static int clampStrength(int strength, int min, int fallback) {
        if (strength < min || strength > STRENGTH_MAX) return fallback;
        // Keep the stock 100-unit grid so the slider and the HAL always agree.
        return min + ((strength - min) / STRENGTH_STEP) * STRENGTH_STEP;
    }

    // ===== System toggles =====

    public boolean isSystemHapticsEnabled() {
        return Settings.System.getInt(resolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
    }

    public void setSystemHapticsEnabled(boolean enabled) {
        Settings.System.putInt(resolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED,
                enabled ? 1 : 0);
    }

    public boolean isKeyboardHapticsEnabled() {
        return Settings.System.getInt(resolver(),
                Settings.System.KEYBOARD_VIBRATION_ENABLED, 1) != 0;
    }

    public void setKeyboardHapticsEnabled(boolean enabled) {
        Settings.System.putInt(resolver(), Settings.System.KEYBOARD_VIBRATION_ENABLED,
                enabled ? 1 : 0);
    }

    private ContentResolver resolver() {
        return mContext.getContentResolver();
    }

    // ===== Previews =====

    /** A few taps in the current style, like stock's style picker. */
    public void previewStyle() {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        cancelPreview();
        for (int i = 0; i < STYLE_PREVIEW_EFFECTS.length; i++) {
            final int effect = STYLE_PREVIEW_EFFECTS[i];
            mHandler.postDelayed(() -> mVibrator.vibrate(
                    VibrationEffect.createPredefined(effect), TOUCH_ATTRIBUTES),
                    STYLE_PREVIEW_DELAYS_MS[i]);
        }
    }

    public void previewTouch() {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        cancelPreview();
        mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                TOUCH_ATTRIBUTES);
    }

    public void previewVibration() {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        cancelPreview();
        mVibrator.vibrate(VibrationEffect.createWaveform(
                new long[] {0, 220, 120, 220}, /* repeat= */ -1), VIBRATION_ATTRIBUTES);
    }

    public void cancelPreview() {
        mHandler.removeCallbacksAndMessages(null);
    }
}
