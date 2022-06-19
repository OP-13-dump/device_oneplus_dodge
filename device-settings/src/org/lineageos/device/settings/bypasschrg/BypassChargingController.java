/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Pure state machine for bypass charging logic.
 * No UI concerns, no service management, just state.
 */

package org.lineageos.device.settings.bypasschrg;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

import java.util.Objects;

public class BypassChargingController {

    private static final String TAG = "BypassChargingController";
    private static final String BYPASS_ENABLED = "0";
    private static final String BYPASS_DISABLED = "1";
    private static final String KEY_BATTERY_LEVEL = "current_battery_level";

    private int mBatteryLevel;
    private final Context mContext;
    private final Object mLock = new Object();

    private static BypassChargingController sInstance;

    // Transient app-specific state (not persisted)
    private String mActiveApp = null;

    public static final class BypassState {
        public final int globalStatus;     // BYPASS_OFF / WAITING / ON
        public final String activeApp;     // nullable
        public final int targetLevel;
        public final int currentLevel;

        public BypassState(int globalStatus, String activeApp, int targetLevel, int currentLevel) {
            this.globalStatus = globalStatus;
            this.activeApp = activeApp;
            this.targetLevel = targetLevel;
            this.currentLevel = currentLevel;
        }

        @Override
        public String toString() {
            return "BypassState{" +
                    "global=" + globalStatus +
                    ", app=" + activeApp +
                    ", level=" + currentLevel + "/" + targetLevel +
                    "}";
        }
    }

    public static synchronized BypassChargingController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BypassChargingController(context.getApplicationContext());
        }
        return sInstance;
    }

    private BypassChargingController(Context context) {
        mContext = context.getApplicationContext();
        mBatteryLevel = getLevelFromIntent();
        if (isValidLevel(mBatteryLevel)) {
            saveCurrentBatteryLevel(mBatteryLevel);
        }
    }

    // ===== Battery helpers =====

    private int getLevelFromIntent() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = mContext.registerReceiver(null, filter);
        if (intent == null) {
            if (Constants.DEBUG) Log.w(TAG, "Battery intent null");
            return -1;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (level >= 0 && scale > 0) ? (int) ((level / (float) scale) * 100) : -1;
    }

    private int getPlugTypeFromIntent() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = mContext.registerReceiver(null, filter);
        if (intent == null) return 0;
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    }

    private boolean isPlugged() {
        return getPlugTypeFromIntent() != 0;
    }

    private boolean isValidLevel(int level) {
        return level >= 0 && level <= 100;
    }

    // ===== Hardware control =====

    private boolean enableHardwareBypass() {
        try {
            FileUtils.writeLine(Constants.NODE_BYPASS_CHARGING, BYPASS_ENABLED);
            String verify = FileUtils.readLine(Constants.NODE_BYPASS_CHARGING);
            if (!BYPASS_ENABLED.equals(verify)) {
                Log.e(TAG, "Hardware bypass enable verification failed");
                return false;
            }
            if (Constants.DEBUG) Log.i(TAG, "Hardware bypass enabled");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable hardware bypass", e);
            return false;
        }
    }

    private boolean disableHardwareBypass() {
        try {
            FileUtils.writeLine(Constants.NODE_BYPASS_CHARGING, BYPASS_DISABLED);
            String verify = FileUtils.readLine(Constants.NODE_BYPASS_CHARGING);
            if (!BYPASS_DISABLED.equals(verify)) {
                Log.e(TAG, "Hardware bypass disable verification failed");
                return false;
            }
            if (Constants.DEBUG) Log.i(TAG, "Hardware bypass disabled");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable hardware bypass", e);
            return false;
        }
    }

    public boolean isBypassChargingSupported() {
        try {
            FileUtils.readLine(Constants.NODE_BYPASS_CHARGING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== Power events =====

    public void handlePowerConnected() {
        synchronized (mLock) {
            if (Constants.DEBUG) Log.i(TAG, "Power connected");

            mBatteryLevel = getLevelFromIntent();
            if (isValidLevel(mBatteryLevel)) {
                saveCurrentBatteryLevel(mBatteryLevel);
            }

            int status = getBypassChargingStatus();
            int target = getBypassChargingTarget();
            int current = getCurrentBatteryLevel();

            if (status == Constants.BYPASS_ON) {
                if (current >= target) {
                    enableHardwareBypass();
                    if (Constants.DEBUG) Log.i(TAG, "Re-enabled global (level >= target)");
                } else {
                    disableHardwareBypass();
                    saveBypassChargingStatus(Constants.BYPASS_WAITING);
                    if (Constants.DEBUG) Log.i(TAG, "Below target, now WAITING");
                }
            } else if (status == Constants.BYPASS_WAITING && current >= target) {
                if (enableHardwareBypass()) {
                    saveBypassChargingStatus(Constants.BYPASS_ON);
                    if (Constants.DEBUG) Log.i(TAG, "Reached target, now ON");
                }
            }

            notifyStateChanged();
        }
    }

    public void handlePowerDisconnected() {
        synchronized (mLock) {
            if (Constants.DEBUG) Log.i(TAG, "Power disconnected");
            disableHardwareBypass();
            mActiveApp = null;
            // Don't modify global prefs
            notifyStateChanged();
        }
    }

    // ===== UI trigger: enable global bypass =====

    public boolean enableBypassCharging() {
        synchronized (mLock) {
            int current = getCurrentBatteryLevel();
            if (!isValidLevel(current)) {
                Log.w(TAG, "Invalid battery level: " + current);
                return false;
            }

            mActiveApp = null;
            int target = getBypassChargingTarget();

            if (current >= target) {
                if (enableHardwareBypass()) {
                    saveBypassChargingStatus(Constants.BYPASS_ON);
                    if (Constants.DEBUG) Log.i(TAG, "Global enabled immediately");
                    notifyStateChanged();
                    return true;
                }
                return false;
            } else {
                saveBypassChargingStatus(Constants.BYPASS_WAITING);
                if (Constants.DEBUG) Log.i(TAG, "Global enabled, waiting for target");
                notifyStateChanged();
                return true;
            }
        }
    }

    // ===== UI trigger: disable global bypass =====

    public boolean disableBypassCharging() {
        synchronized (mLock) {
            disableHardwareBypass();
            mActiveApp = null;
            saveBypassChargingStatus(Constants.BYPASS_OFF);
            if (Constants.DEBUG) Log.i(TAG, "Global disabled");
            notifyStateChanged();
            return true;
        }
    }

    // ===== Manager: app monitoring trigger =====

    public void setActiveApp(String packageName) {
        synchronized (mLock) {
            if (getBypassChargingStatus() != Constants.BYPASS_OFF) {
                if (Constants.DEBUG) Log.w(TAG, "Cannot set app: global bypass is active");
                return;
            }

            if (Objects.equals(mActiveApp, packageName)) {
                return;
            }

            mActiveApp = packageName;
            int current = getCurrentBatteryLevel();
            int target = getBypassChargingTarget();

            if (current >= target) {
                enableHardwareBypass();
                if (Constants.DEBUG) Log.i(TAG, "App bypass enabled for " + packageName);
            } else {
                disableHardwareBypass();
                if (Constants.DEBUG) Log.i(TAG, "App bypass waiting for target for " + packageName);
            }

            notifyStateChanged();
        }
    }

    public void clearActiveApp() {
        synchronized (mLock) {
            if (mActiveApp == null) return;
            disableHardwareBypass();
            mActiveApp = null;
            if (Constants.DEBUG) Log.i(TAG, "App bypass cleared");
            notifyStateChanged();
        }
    }

    // ===== Battery level update from Manager =====

    public void onBatteryLevelChanged(int level) {
        synchronized (mLock) {
            if (!isValidLevel(level)) return;

            if (mBatteryLevel != level) {
                mBatteryLevel = level;
                saveCurrentBatteryLevel(level);
            }

            int status = getBypassChargingStatus();
            int target = getBypassChargingTarget();

            // Check if global waiting reached target
            if (status == Constants.BYPASS_WAITING && level >= target) {
                if (enableHardwareBypass()) {
                    saveBypassChargingStatus(Constants.BYPASS_ON);
                    if (Constants.DEBUG) Log.i(TAG, "Global: reached target at " + level + "%");
                    notifyStateChanged();
                    return;
                }
            }

            // Check if app waiting reached target
            if (mActiveApp != null && status == Constants.BYPASS_OFF && level >= target) {
                if (enableHardwareBypass()) {
                    if (Constants.DEBUG) Log.i(TAG, "App: reached target at " + level + "%");
                    notifyStateChanged();
                    return;
                }
            }

            notifyStateChanged();
        }
    }

    // ===== Target level changes =====

    public void setBypassChargingTarget(int target) {
        synchronized (mLock) {
            if (target < Constants.BYPASS_TARGET_MIN || target > Constants.BYPASS_TARGET_MAX) {
                Log.w(TAG, "Invalid target: " + target);
                return;
            }

            int old = getBypassChargingTarget();
            if (old == target) return;

            saveBypassChargingTarget(target);
            int status = getBypassChargingStatus();
            int current = getCurrentBatteryLevel();

            if (status == Constants.BYPASS_ON && current < target) {
                disableHardwareBypass();
                saveBypassChargingStatus(Constants.BYPASS_WAITING);
                if (Constants.DEBUG) Log.i(TAG, "Target raised, now WAITING");
            } else if (status == Constants.BYPASS_WAITING && current >= target) {
                if (enableHardwareBypass()) {
                    saveBypassChargingStatus(Constants.BYPASS_ON);
                    if (Constants.DEBUG) Log.i(TAG, "Target lowered, now ON");
                }
            }

            notifyStateChanged();
        }
    }

    // ===== State snapshot =====

    public BypassState getState() {
        synchronized (mLock) {
            return new BypassState(
                    getBypassChargingStatus(),
                    mActiveApp,
                    getBypassChargingTarget(),
                    getCurrentBatteryLevel()
            );
        }
    }

    // ===== Preferences =====

    private void saveBypassChargingStatus(int status) {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putInt(Constants.KEY_BYPASS_CHARGING, status)
                .apply();
    }

    public int getBypassChargingStatus() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getInt(Constants.KEY_BYPASS_CHARGING, Constants.BYPASS_OFF);
    }

    private void saveBypassChargingTarget(int target) {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putInt(Constants.KEY_BYPASS_CHARGING_TARGET, target)
                .apply();
    }

    public int getBypassChargingTarget() {
        int target = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getInt(Constants.KEY_BYPASS_CHARGING_TARGET, Constants.BYPASS_TARGET_DEFAULT);
        if (target < Constants.BYPASS_TARGET_MIN || target > Constants.BYPASS_TARGET_MAX) {
            Log.w(TAG, "Invalid target: " + target);
            saveBypassChargingTarget(Constants.BYPASS_TARGET_DEFAULT);
            return Constants.BYPASS_TARGET_DEFAULT;
        }
        return target;
    }

    private void saveCurrentBatteryLevel(int level) {
        if (isValidLevel(level)) {
            PreferenceManager.getDefaultSharedPreferences(mContext)
                    .edit()
                    .putInt(KEY_BATTERY_LEVEL, level)
                    .apply();
        }
    }

    public int getCurrentBatteryLevel() {
        int level = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getInt(KEY_BATTERY_LEVEL, -1);
        if (!isValidLevel(level)) {
            level = getLevelFromIntent();
            if (isValidLevel(level)) {
                saveCurrentBatteryLevel(level);
            }
        }
        return level;
    }

    // ===== Notification =====

    private void notifyStateChanged() {
        BypassState state = getState();
        BypassChargingManager.notifyStateChanged(mContext, state);
    }
}
