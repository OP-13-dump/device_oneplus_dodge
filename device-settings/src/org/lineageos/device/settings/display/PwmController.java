/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

public class PwmController {
    private static final String TAG = "PwmController";
    private static PwmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private PwmController(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public static synchronized PwmController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PwmController(context);
        }
        return sInstance;
    }

    public boolean isPwmEnabled() {
        // The kernel state resets on reboot, so the node is the source of truth;
        // the preference is only a fallback while the node is unreadable
        String value = FileUtils.readLineTrimmed(Constants.NODE_ONEPULSE_PWM);
        if (value != null) {
            return "1".equals(value);
        }
        return mSharedPrefs.getBoolean(Constants.KEY_ONEPULSE_PWM, false);
    }

    /**
     * Re-apply the persisted PWM choice after boot: the panel always comes up with
     * one-pulse disabled, so a user selection would otherwise be lost on reboot.
     */
    public void restorePwmSetting() {
        boolean wanted = mSharedPrefs.getBoolean(Constants.KEY_ONEPULSE_PWM, false);
        if (wanted && !isPwmEnabled()) {
            if (FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
                if (setPwm(true)) {
                    Log.i(TAG, "Restored PWM setting after boot");
                } else {
                    Log.w(TAG, "Failed to restore PWM setting after boot");
                }
            } else {
                Log.w(TAG, "PWM node is not writable, cannot restore setting");
            }
        }
    }

    public boolean enablePwm() {
        if (!FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            Log.w(TAG, "PWM node is not writable");
            return false;
        }

        // PWM has priority: tear HBM down fully, then wait out any recent mode change
        // (including a two-tap HBM-off tile → PWM-on that skips the in-line disable).
        HbmController hbmController = HbmController.getInstance(mContext);
        if (hbmController.isHbmEnabled()) {
            Log.i(TAG, "HBM is active, disabling it (PWM has priority)");
            if (!hbmController.disableHbm()) {
                Log.w(TAG, "Failed to disable HBM before enabling PWM");
                return false;
            }
        }

        PanelModeSettle.awaitIfNeeded("before PWM on");
        if (!setPwm(true)) {
            return false;
        }
        PanelModeSettle.mark();
        return true;
    }

    public boolean disablePwm() {
        if (!FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            Log.w(TAG, "PWM node is not writable");
            return false;
        }

        if (!setPwm(false)) {
            return false;
        }
        // Kernel re-applies BL so 1P→DC runs now; mark so a following HBM on waits.
        PanelModeSettle.mark();
        return true;
    }

    /**
     * Write onepulse sysfs and only persist the pref when the node matches.
     * Kernel can refuse (e.g. hbm_max still active) with -EFAULT; treat that as failure.
     */
    private boolean setPwm(boolean enable) {
        String want = enable ? "1" : "0";
        if (!FileUtils.writeLine(Constants.NODE_ONEPULSE_PWM, want)) {
            Log.w(TAG, "PWM sysfs write failed (enable=" + enable + ")");
            return false;
        }
        String got = FileUtils.readLineTrimmed(Constants.NODE_ONEPULSE_PWM);
        if (got == null || !want.equals(got)) {
            Log.w(TAG, "PWM node mismatch after write: want=" + want + " got=" + got);
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_ONEPULSE_PWM, enable).commit();
        Log.i(TAG, "PWM set to: " + enable);
        return true;
    }
}
