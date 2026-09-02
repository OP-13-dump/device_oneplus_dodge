/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Centralized controller for display mode state coordination.
 *
 * Business rules:
 * - PWM has priority over HBM
 * - Enabling PWM will disable HBM automatically
 * - HBM cannot be enabled while PWM is active (must disable PWM first)
 * - HBM locks refresh rate to 120Hz
 * - Refresh rate tile should be disabled while HBM is active
 * - All mode mutations are synchronized so tile spam cannot interleave HBM/PWM cmds
 * - PanelModeSettle: every enter path waits out SETTLE_MS since the last mode
 *   change (covers two-tap HBM-off tile then PWM-on, not only in-line teardown)
 * - Cross-tile UI sync via TileService.requestListeningState (not a broadcast):
 *   PWM change → HBM tile (HBM available only when PWM off)
 *   HBM change → RefreshRate tile (locked while HBM on); also when PWM tears HBM
 *   down, RR must unlock
 */
package org.lineageos.device.settings.display;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.refreshrate.RefreshRateTile;

public class DisplayModeController {
    private static final String TAG = "DisplayModeController";

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
     * Refresh rate changes blocked when HBM is active (locked to 120Hz)
     */
    public boolean canChangeRefreshRate() {
        return !mHbmController.isHbmEnabled();
    }

    // ===== State Mutations (serialized) =====

    public synchronized boolean enableHbm() {
        if (!canEnableHbm()) {
            if (Constants.DEBUG) Log.w(TAG, "Cannot enable HBM: PWM is active");
            return false;
        }

        boolean success = mHbmController.enableHbm();
        if (success) {
            // RR greys out while HBM is on
            requestTileListening(RefreshRateTile.class);
        }
        return success;
    }

    public synchronized boolean disableHbm() {
        boolean success = mHbmController.disableHbm();
        if (success) {
            // RR unlocks when HBM is off
            requestTileListening(RefreshRateTile.class);
        }
        return success;
    }

    public synchronized boolean enablePwm() {
        // PwmController tears HBM down + settles, then enables PWM
        boolean success = mPwmController.enablePwm();
        if (success) {
            // HBM becomes unavailable (PWM has priority); RR unlocks if HBM was forced off
            requestTileListening(HbmTile.class);
            requestTileListening(RefreshRateTile.class);
        }
        return success;
    }

    public synchronized boolean disablePwm() {
        // PwmController disables + settles so a following HBM on is safe
        boolean success = mPwmController.disablePwm();
        if (success) {
            // HBM can be enabled again
            requestTileListening(HbmTile.class);
        }
        return success;
    }

    /**
     * Ask SystemUI to put a QS tile into listening so onStartListening → updateTile
     * runs immediately. Faster and more reliable than a package broadcast.
     */
    public void requestTileListening(Class<? extends TileService> tileClass) {
        try {
            TileService.requestListeningState(mContext,
                    new ComponentName(mContext, tileClass));
            if (Constants.DEBUG) {
                Log.i(TAG, "requestListeningState: " + tileClass.getSimpleName());
            }
        } catch (Exception e) {
            Log.w(TAG, "requestListeningState failed for " + tileClass.getSimpleName(), e);
        }
    }

    /**
     * Refresh all display-mode tiles (screen-on HBM sync, settings preference path).
     */
    public void broadcastStateChange() {
        requestTileListening(HbmTile.class);
        requestTileListening(PwmTile.class);
        requestTileListening(RefreshRateTile.class);
    }
}
