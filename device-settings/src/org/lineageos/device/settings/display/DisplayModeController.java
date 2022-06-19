/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Centralized controller for display mode state coordination.
 *
 * Business rules:
 * - PWM has priority over HBM
 * - Enabling PWM will disable HBM automatically
 * - HBM cannot be enabled while PWM is active
 * - HBM locks refresh rate to 90Hz
 * - Refresh rate tile should be disabled while HBM is active
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.lineageos.device.settings.Constants;

public class DisplayModeController {
    private static final String TAG = "DisplayModeController";

    public static final String ACTION_DISPLAY_MODE_CHANGED =
            "org.lineageos.device.settings.action.DISPLAY_MODE_CHANGED";
    public static final String EXTRA_HBM_ENABLED = "hbm_enabled";
    public static final String EXTRA_PWM_ENABLED = "pwm_enabled";

    private static DisplayModeController sInstance;
    private final Context mContext;
    private final HbmController mHbmController;
    private final PwmController mPwmController;

    public static synchronized DisplayModeController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DisplayModeController(context.getApplicationContext());
        }
        return sInstance;
    }

    private DisplayModeController(Context context) {
        mContext = context;
        mHbmController = HbmController.getInstance(context);
        mPwmController = PwmController.getInstance(context);
    }

    // ===== State Queries =====

    public boolean isHbmEnabled() {
        return mHbmController.isHbmEnabled();
    }

    public boolean isPwmEnabled() {
        return mPwmController.isPwmEnabled();
    }

    /**
     * HBM can only be enabled if PWM is off (PWM has priority)
     */
    public boolean canEnableHbm() {
        return !mPwmController.isPwmEnabled();
    }

    /**
     * PWM can always be enabled (it has priority and will disable HBM)
     */
    public boolean canEnablePwm() {
        return true;
    }

    /**
     * Refresh rate changes blocked when HBM is active (locked to 90Hz)
     */
    public boolean canChangeRefreshRate() {
        return ! mHbmController.isHbmEnabled();
    }

    // ===== State Mutations =====

    public boolean enableHbm() {
        if (! canEnableHbm()) {
            if (Constants.DEBUG) Log.w(TAG, "Cannot enable HBM: PWM is active");
            return false;
        }

        boolean success = mHbmController.enableHbm();
        if (success) {
            broadcastStateChange();
        }
        return success;
    }

    public boolean disableHbm() {
        boolean success = mHbmController.disableHbm();
        if (success) {
            broadcastStateChange();
        }
        return success;
    }

    public boolean enablePwm() {
        // PWM has priority - PwmController.enablePwm() already disables HBM
        boolean success = mPwmController.enablePwm();
        if (success) {
            broadcastStateChange();
        }
        return success;
    }

    public boolean disablePwm() {
        boolean success = mPwmController.disablePwm();
        if (success) {
            broadcastStateChange();
        }
        return success;
    }

    // ===== Broadcast =====

    public void broadcastStateChange() {
        Intent intent = new Intent(ACTION_DISPLAY_MODE_CHANGED);
        intent.putExtra(EXTRA_HBM_ENABLED, mHbmController.isHbmEnabled());
        intent.putExtra(EXTRA_PWM_ENABLED, mPwmController.isPwmEnabled());
        intent.setPackage(mContext.getPackageName());
        mContext.sendBroadcast(intent);

        if (Constants.DEBUG) {
            Log.i(TAG, "Broadcast: HBM=" + mHbmController.isHbmEnabled()
                    + ", PWM=" + mPwmController.isPwmEnabled());
        }
    }
}