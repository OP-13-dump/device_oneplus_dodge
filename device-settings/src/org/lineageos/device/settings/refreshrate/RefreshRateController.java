/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Pure state machine for refresh rate logic.
 * Global rate applies to all apps, per-app overrides take precedence.
 */

package org.lineageos.device.settings.refreshrate;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;

import java.util.HashMap;
import java.util.Map;

public class RefreshRateController {

    private static final String TAG = "RefreshRateController";

    public static final int REFRESH_RATE_AUTO = 0;
    public static final int REFRESH_RATE_60 = 60;
    public static final int REFRESH_RATE_90 = 90;
    public static final int REFRESH_RATE_120 = 120;

    private final Context mContext;
    private final Object mLock = new Object();

    private static RefreshRateController sInstance;

    public static final class RefreshRateState {
        public final int globalRefreshRate;
        public final Map<String, Integer> appOverrides;

        public RefreshRateState(int globalRefreshRate, Map<String, Integer> appOverrides) {
            this.globalRefreshRate = globalRefreshRate;
            this.appOverrides = new HashMap<>(appOverrides);
        }

        @Override
        public String toString() {
            return "RefreshRateState{" +
                    "global=" + globalRefreshRate +
                    ", appOverrides=" + appOverrides.size() +
                    "}";
        }
    }

    public static synchronized RefreshRateController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RefreshRateController(context.getApplicationContext());
        }
        return sInstance;
    }

    private RefreshRateController(Context context) {
        mContext = context.getApplicationContext();
    }

    // ===== UI trigger: set global refresh rate =====

    public void setGlobalRefreshRate(int fps) {
        synchronized (mLock) {
            if (!isValidRefreshRate(fps)) {
                Log.w(TAG, "Invalid refresh rate: " + fps);
                return;
            }

            int old = getGlobalRefreshRate();
            if (old == fps) {
                return;
            }

            saveGlobalRefreshRate(fps);
            if (Constants.DEBUG) {
                String desc = fps == REFRESH_RATE_AUTO ? "auto" : fps + " fps";
                Log.i(TAG, "Global refresh rate set to " + desc);
            }
            notifyStateChanged();
        }
    }

    // ===== UI trigger: set per-app override =====

    public void setAppRefreshRate(String packageName, int fps) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(packageName)) {
                Log.w(TAG, "Empty package name");
                return;
            }

            if (!isValidRefreshRate(fps)) {
                Log.w(TAG, "Invalid refresh rate: " + fps);
                return;
            }

            Map<String, Integer> overrides = getAppOverridesMap();
            Integer old = overrides.get(packageName);

            if (old != null && old == fps) {
                return;
            }

            overrides.put(packageName, fps);
            saveAppOverrides(overrides);
            if (Constants.DEBUG) {
                String desc = fps == REFRESH_RATE_AUTO ? "auto" : fps + " fps";
                Log.i(TAG, "App override set: " + packageName + " -> " + desc);
            }
            notifyStateChanged();
        }
    }

    // ===== UI trigger: remove per-app override =====

    public void removeAppRefreshRate(String packageName) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(packageName)) {
                Log.w(TAG, "Empty package name");
                return;
            }

            Map<String, Integer> overrides = getAppOverridesMap();
            if (!overrides.containsKey(packageName)) {
                return;
            }

            overrides.remove(packageName);
            saveAppOverrides(overrides);
            if (Constants.DEBUG) Log.i(TAG, "App override removed: " + packageName);
            notifyStateChanged();
        }
    }

    // ===== Query: get effective refresh rate for app =====

    public int getEffectiveRefreshRate(String packageName) {
        synchronized (mLock) {
            if (!TextUtils.isEmpty(packageName)) {
                Map<String, Integer> overrides = getAppOverridesMap();
                Integer override = overrides.get(packageName);
                if (override != null) {
                    return override;
                }
            }
            return getGlobalRefreshRate();
        }
    }

    // ===== LTPO master switch =====

    public boolean isLtpoEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(Constants.KEY_LTPO_ENABLED, true);
    }

    public void setLtpoEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putBoolean(Constants.KEY_LTPO_ENABLED, enabled)
                .apply();
        if (Constants.DEBUG) Log.i(TAG, "LTPO " + (enabled ? "enabled" : "disabled"));

        // Keep the QS tile (and any other UI) in sync with this change
        Intent intent = new Intent(Constants.ACTION_LTPO_STATE_CHANGED);
        intent.setPackage(mContext.getPackageName());
        mContext.sendBroadcast(intent);

        notifyStateChanged();
    }

    // ===== State snapshot =====

    public RefreshRateState getState() {
        synchronized (mLock) {
            return new RefreshRateState(
                    getGlobalRefreshRate(),
                    getAppOverridesMap()
            );
        }
    }

    // ===== Preferences =====

    public int getGlobalRefreshRate() {
        try {
            String value = PreferenceManager.getDefaultSharedPreferences(mContext)
                    .getString(Constants.KEY_REFRESH_RATE_MODE, String.valueOf(Constants.REFRESH_RATE_DEFAULT));
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid refresh rate value, using default");
            return Constants.REFRESH_RATE_DEFAULT;
        }
    }

    private void saveGlobalRefreshRate(int fps) {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putString(Constants.KEY_REFRESH_RATE_MODE, String.valueOf(fps))
                .apply();
    }

    public Map<String, Integer> getAppOverridesMap() {
        Map<String, Integer> result = new HashMap<>();
        String raw = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(Constants.KEY_REFRESH_RATE_APPS, "");

        if (TextUtils.isEmpty(raw)) {
            return result;
        }

        String[] entries = raw.split("\\|");
        for (String entry : entries) {
            if (TextUtils.isEmpty(entry)) continue;

            String[] parts = entry.split(":");
            if (parts.length != 2) {
                Log.w(TAG, "Malformed override entry: " + entry);
                continue;
            }

            String pkg = parts[0].trim();
            int fps;
            try {
                fps = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid fps in entry: " + entry);
                continue;
            }

            if (isValidRefreshRate(fps)) {
                result.put(pkg, fps);
            }
        }

        return result;
    }

    private void saveAppOverrides(Map<String, Integer> overrides) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            if (!first) {
                sb.append("|");
            }
            sb.append(entry.getKey()).append(":").append(entry.getValue());
            first = false;
        }

        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putString(Constants.KEY_REFRESH_RATE_APPS, sb.toString())
                .apply();
    }

    public boolean hasAppOverrides() {
        String raw = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(Constants.KEY_REFRESH_RATE_APPS, "");
        return !TextUtils.isEmpty(raw);
    }

    // ===== Validation =====

    private boolean isValidRefreshRate(int fps) {
        return fps == REFRESH_RATE_AUTO || fps == 60 || fps == 90 || fps == 120;
    }

    // ===== Notification =====

    private void notifyStateChanged() {
        RefreshRateMonitorService.notifyStateChanged(mContext);
    }
}
