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
import org.lineageos.device.settings.refreshrate.RefreshRateMonitorService;
import org.lineageos.device.settings.utils.FileUtils;

public class HbmController {
    private static final String TAG = "HbmController";
    private static HbmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private static final float MAX = 120.0f;
    // HBM (hbm_max, the sunlight boost) needs the panel at one stable rate. Dynamic
    // RR lets SF timing-switch 60<->120, and each switch reprograms the panel drive
    // registers + re-latches, dropping it out of HBM (visible flash); auto would also
    // let the DDIC self-refresh down-clock beneath the mode. So pin BOTH halves - SF
    // MIN=PEAK=120 (no switches) and adfr_min_fps=120 (no down-clock). On disable,
    // hand the refresh rate back to RefreshRateMonitorService (the single owner of
    // the user's baseline); no local backup/restore, which is what used to weld
    // auto->120 when pins overlapped.
    private static final float HBM_FRAMERATE = MAX;
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

    /**
     * Reconcile our persisted state with the actual node. The kernel forces
     * hbm_max off whenever the panel sleeps (safeguard against a frozen UI), so
     * after any screen-off the node is authoritative and our preference/tile may
     * be a stale ON. Call on screen-on: if the node disagrees with the stored
     * preference, adopt the node value. Returns the live state.
     */
    public boolean syncState() {
        boolean nodeState = isHbmEnabled();
        boolean prefState = mSharedPrefs.getBoolean(Constants.KEY_HBM, false);
        if (prefState != nodeState) {
            mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, nodeState).commit();
            Log.i(TAG, "HBM state synced to node: " + nodeState);
            if (prefState && !nodeState) {
                // HBM was released by the kernel (sleep safeguard) while our RR was
                // pinned to 120Hz. Hand the refresh rate back to the monitor so the
                // user's baseline is restored instead of staying welded at 120.
                RefreshRateMonitorService.notifyStateChanged(mContext);
            }
        }
        return nodeState;
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

        // Wait out a recent PWM/HBM mode change (two-tap PWM-off → HBM-on)
        PanelModeSettle.awaitIfNeeded("before HBM on");
        if (!enableHbmInternal()) {
            return false;
        }
        PanelModeSettle.mark();
        return true;
    }

    public boolean disableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        if (!disableHbmInternal()) {
            return false;
        }
        // Mark so a following PWM on (even via a separate tile) waits out EXIT.
        PanelModeSettle.mark();
        return true;
    }

    private boolean enableHbmInternal() {
        // 1. Backup and disable auto-brightness
        boolean autoBrightnessEnabled = isAutoBrightnessEnabled();
        mSharedPrefs.edit()
                .putBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, autoBrightnessEnabled)
                .apply();

        if (autoBrightnessEnabled) {
            setAutoBrightness(false);
            Log.i(TAG, "Auto-brightness disabled for HBM");
        }

        // 2. Pin the refresh rate: adfr_min_fps first (kernel self-refresh floor),
        // then SF MIN=PEAK so SF stops timing-switching. No backup here -
        // RefreshRateMonitorService owns the user's baseline and restores it
        // when the pin is released.
        FileUtils.writeLine(Constants.NODE_ADFR_MIN_FPS,
                String.valueOf((int) HBM_FRAMERATE));
        setRefreshRate(HBM_FRAMERATE, HBM_FRAMERATE);
        Log.i(TAG, "HBM: pinned refresh rate to " + HBM_FRAMERATE);

        // 3. Write HBM sysfs node; only persist pref when the node matches
        if (!writeHbmNode(true)) {
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
        Log.i(TAG, "HBM sysfs node enabled");
        return true;
    }

    private boolean disableHbmInternal() {
        // 1. Disable HBM sysfs node first, so the monitor below sees HBM off.
        if (!writeHbmNode(false)) {
            return false;
        }

        // 2. Restore auto-brightness if it was enabled before
        boolean wasAutoBrightnessEnabled = mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false);
        if (wasAutoBrightnessEnabled) {
            setAutoBrightness(true);
            Log.i(TAG, "Auto-brightness restored");
        }

        mSharedPrefs.edit()
                .putBoolean(Constants.KEY_HBM, false)
                .remove(KEY_BACKUP_AUTO_BRIGHTNESS)
                .commit();

        // 3. Hand the refresh rate back to RefreshRateMonitorService: it re-applies
        // the user's baseline (tile / per-app / auto / LTPO).
        RefreshRateMonitorService.notifyStateChanged(mContext);
        Log.i(TAG, "HBM sysfs node disabled; refresh rate handed to monitor");
        return true;
    }

    private boolean writeHbmNode(boolean enable) {
        String want = enable ? "1" : "0";
        if (!FileUtils.writeLine(Constants.NODE_HBM, want)) {
            Log.w(TAG, "HBM sysfs write failed (enable=" + enable + ")");
            return false;
        }
        String got = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        if (got == null || !want.equals(got)) {
            Log.w(TAG, "HBM node mismatch after write: want=" + want + " got=" + got);
            return false;
        }
        return true;
    }

    private void setRefreshRate(float min, float peak) {
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, min, UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE, peak, UserHandle.USER_CURRENT);
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
