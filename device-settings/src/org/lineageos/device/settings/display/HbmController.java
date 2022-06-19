/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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
    private static final float HBM_FRAMERATE = 90.0f;
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
        return mSharedPrefs.getBoolean(Constants.KEY_HBM,
                FileUtils.getFileValueAsBoolean(Constants.NODE_HBM, false));
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

        // 2. Backup refresh rates
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

        Log.i(TAG, "Backing up refresh rates - MIN: " + currentMinRefreshRate + ", MAX: " + currentMaxRefreshRate);

        mSharedPrefs.edit()
                .putFloat(KEY_BACKUP_MIN_REFRESH_RATE, currentMinRefreshRate)
                .putFloat(KEY_BACKUP_MAX_REFRESH_RATE, currentMaxRefreshRate)
                .apply();

        // 3.Set constant framerate to 90Hz (workaround for kernel bug with dynamic framerate)
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, HBM_FRAMERATE,
                UserHandle.USER_CURRENT);

        Log.i(TAG, "HBM enabled - set MIN and MAX to: " + HBM_FRAMERATE);

        // 4.Write HBM sysfs node with delay
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            FileUtils.writeLine(Constants.NODE_HBM, "1");
            mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
            Log.i(TAG, "HBM sysfs node enabled");
        }, 100);
    }

    private void disableHbmInternal() {
        // 1. Restore auto-brightness if it was enabled before
        boolean wasAutoBrightnessEnabled = mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false);
        if (wasAutoBrightnessEnabled) {
            setAutoBrightness(true);
            Log.i(TAG, "Auto-brightness restored");
        }

        // 2.Restore refresh rates
        float backedUpMinRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MIN_REFRESH_RATE, MIN);
        float backedUpMaxRefreshRate = mSharedPrefs.getFloat(KEY_BACKUP_MAX_REFRESH_RATE, MAX);

        Log.i(TAG, "Restoring refresh rates - MIN: " + backedUpMinRefreshRate + ", MAX: " + backedUpMaxRefreshRate);

        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, backedUpMinRefreshRate,
                UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, backedUpMaxRefreshRate,
                UserHandle.USER_CURRENT);

        Log.i(TAG, "HBM disabled - refresh rates restored");

        // 3. Disable HBM sysfs node with delay
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            FileUtils.writeLine(Constants.NODE_HBM, "0");
            mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, false).commit();

            // Clear backed up values
            mSharedPrefs.edit()
                    .remove(KEY_BACKUP_MIN_REFRESH_RATE)
                    .remove(KEY_BACKUP_MAX_REFRESH_RATE)
                    .remove(KEY_BACKUP_AUTO_BRIGHTNESS)
                    .apply();

            Log.i(TAG, "HBM sysfs node disabled, backup cleared");
        }, 100);
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