/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

public class HbmController {
    private static final String TAG = "HbmController";
    private static HbmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private static final float MIN = 60.0f;
    private static final float MAX = 120.0f;
    // While HBM is on the refresh rate is pinned to 120Hz: the kernel freezes ADFR
    // min-fps during HBM (register conflict), but SF timing switches (60<->120) still
    // reprogram the panel and re-latching HBM afterwards shows as a visible flash.
    // Pinning removes the switches entirely; 120 because HBM runs at max min-fps anyway.
    private static final float HBM_FRAMERATE = MAX;
    private static final String KEY_BACKUP_MIN_REFRESH_RATE = "hbm_backup_min_refresh_rate";
    private static final String KEY_BACKUP_MAX_REFRESH_RATE = "hbm_backup_max_refresh_rate";
    private static final String KEY_BACKUP_AUTO_BRIGHTNESS = "hbm_backup_auto_brightness";

    private HbmController(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public static synchronized HbmController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HbmController(context);
        }
        return sInstance;
    }

    public boolean isHbmEnabled() {
        // The panel always boots with HBM off while the preference may still say
        // enabled (e.g. reboot with HBM on), so the node is the source of truth
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        if (value != null) {
            return "1".equals(value);
        }
        return mSharedPrefs.getBoolean(Constants.KEY_HBM, false);
    }

    public boolean enableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        // Check if PWM is enabled (PWM has priority)
        PwmController pwmController = PwmController.getInstance(mContext);
        if (pwmController.isPwmEnabled()) {
            Log.w(TAG, "Cannot enable HBM while PWM is active");
            return false;
        }

        setHbm(true);
        return true;
    }

    public boolean disableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        setHbm(false);
        return true;
    }

    private void setHbm(boolean enable) {
        if (enable) {
            enableHbmInternal();
        } else {
            disableHbmInternal();
        }
    }

    private void enableHbmInternal() {
        // 1.Backup and disable auto-brightness
        boolean autoBrightnessEnabled = isAutoBrightnessEnabled();
        mSharedPrefs.edit()
                .putBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, autoBrightnessEnabled)
                .apply();

        if (autoBrightnessEnabled) {
            setAutoBrightness(false);
            Log.i(TAG, "Auto-brightness disabled for HBM");
        }

        // 2.Backup refresh rates and pin to 120Hz so SF stops timing-switching
        // (each switch re-latches HBM in the kernel, which flashes visibly)
        float currentMinRefreshRate = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE,
                MIN,
                UserHandle.USER_CURRENT);

        float currentMaxRefreshRate = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE,
                MAX,
                UserHandle.USER_CURRENT);

        mSharedPrefs.edit()
                .putFloat(KEY_BACKUP_MIN_REFRESH_RATE, currentMinRefreshRate)
                .putFloat(KEY_BACKUP_MAX_REFRESH_RATE, currentMaxRefreshRate)
                .apply();

        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);

        Log.i(TAG, "HBM: pinned refresh rate to " + HBM_FRAMERATE
                + " (backup MIN: " + currentMinRefreshRate + ", MAX: " + currentMaxRefreshRate + ")");

        // 3.Write HBM sysfs node; the kernel additionally freezes ADFR min-fps
        // at max while hbm_max is active
        FileUtils.writeLine(Constants.NODE_HBM, "1");
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
        Log.i(TAG, "HBM sysfs node enabled");
    }

    private void disableHbmInternal() {
        // 1. Restore auto-brightness if it was enabled before
        boolean wasAutoBrightnessEnabled = mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false);
        if (wasAutoBrightnessEnabled) {
            setAutoBrightness(true);
            Log.i(TAG, "Auto-brightness restored");
        }

        // 2.Restore the refresh rates that were pinned to 120Hz while HBM was on
        // (also covers backups left behind by older builds)
        if (mSharedPrefs.contains(KEY_BACKUP_MIN_REFRESH_RATE)
                || mSharedPrefs.contains(KEY_BACKUP_MAX_REFRESH_RATE)) {
            float backedUpMinRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MIN_REFRESH_RATE, MIN);
            float backedUpMaxRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MAX_REFRESH_RATE, MAX);

            Log.i(TAG, "Restoring refresh rates - MIN: "
                    + backedUpMinRefreshRate + ", MAX: " + backedUpMaxRefreshRate);

            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE, backedUpMinRefreshRate,
                    UserHandle.USER_CURRENT);
            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE, backedUpMaxRefreshRate,
                    UserHandle.USER_CURRENT);
        }

        // 3. Disable HBM sysfs node
        FileUtils.writeLine(Constants.NODE_HBM, "0");
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, false).commit();

        // Clear backed up values
        mSharedPrefs.edit()
                .remove(KEY_BACKUP_MIN_REFRESH_RATE)
                .remove(KEY_BACKUP_MAX_REFRESH_RATE)
                .remove(KEY_BACKUP_AUTO_BRIGHTNESS)
                .apply();

        Log.i(TAG, "HBM sysfs node disabled, backup cleared");
    }

    private boolean isAutoBrightnessEnabled() {
        try {
            int mode = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get auto-brightness state", e);
            return false;
        }
    }

    private void setAutoBrightness(boolean enabled) {
        Settings.System.putIntForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT);
    }
}