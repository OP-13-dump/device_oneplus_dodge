/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.fastcharge;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

/**
 * Sole writer of {@link Constants#NODE_FAST_CHARGING}. SystemUI is platform_app and is
 * denied {search} on oplus_chg, so the charging HUD reads the mirrored Settings.System
 * keys instead of the node.
 */
public class FastChargeController {

    private static final String TAG = "FastChargeController";

    private static FastChargeController sInstance;

    private final Context mContext;
    private final Object mLock = new Object();

    /** One-shot uncap for the current session. Never persisted. */
    private boolean mSessionBoost = false;

    public static synchronized FastChargeController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FastChargeController(context.getApplicationContext());
        }
        return sInstance;
    }

    private FastChargeController(Context context) {
        mContext = context;
    }

    public boolean isSupported() {
        return FileUtils.isFileWritable(Constants.NODE_FAST_CHARGING);
    }

    public boolean isFastChargingEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(Constants.KEY_FAST_CHARGING, true);
    }

    public boolean isNightModeEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(Constants.KEY_NIGHT_CHARGING, false);
    }

    public void setFastChargingEnabled(boolean enabled) {
        synchronized (mLock) {
            PreferenceManager.getDefaultSharedPreferences(mContext)
                    .edit()
                    .putBoolean(Constants.KEY_FAST_CHARGING, enabled)
                    .commit();
            mSessionBoost = false;
            apply();
        }
    }

    public void setNightModeEnabled(boolean enabled) {
        synchronized (mLock) {
            PreferenceManager.getDefaultSharedPreferences(mContext)
                    .edit()
                    .putBoolean(Constants.KEY_NIGHT_CHARGING, enabled)
                    .commit();
            mSessionBoost = false;
            apply();
        }
    }

    /** SystemUI long-press. In memory only: prefs keep the persisted cap for the next plug. */
    public void boostSession() {
        synchronized (mLock) {
            if (mSessionBoost) return;
            mSessionBoost = true;
            apply();
            if (Constants.DEBUG) Log.i(TAG, "session boost");
        }
    }

    /**
     * Re-apply the persisted cap and drop any session boost. USER_VOTER does not survive
     * a reboot, so this has to run at boot; it survives unplug, so disconnect re-applies
     * for the next plug even if this process is not up then.
     */
    public void restore() {
        synchronized (mLock) {
            mSessionBoost = false;
            apply();
        }
    }

    private void apply() {
        if (!isSupported()) {
            if (Constants.DEBUG) Log.w(TAG, "cool_down not writable, skipping");
            return;
        }

        final String value;
        final int hudMode;
        if (mSessionBoost || isFastChargingEnabled()) {
            value = Constants.COOL_DOWN_UNLIMITED;
            hudMode = Constants.HUD_MODE_UNLIMITED;
        } else if (isNightModeEnabled()) {
            value = Constants.COOL_DOWN_NIGHT;
            hudMode = Constants.HUD_MODE_NIGHT;
        } else {
            value = Constants.COOL_DOWN_STANDARD;
            hudMode = Constants.HUD_MODE_STANDARD;
        }

        FileUtils.writeLine(Constants.NODE_FAST_CHARGING, value);

        // COOL_DOWN is VOTE_MIN on the VOOC votable: USB temp and friends can hold it
        // lower than we asked, so a differing readback is not a failure.
        if (Constants.DEBUG) {
            Log.i(TAG, "cool_down=" + value
                    + " readback=" + FileUtils.readLineTrimmed(Constants.NODE_FAST_CHARGING)
                    + " boost=" + mSessionBoost);
        }

        publish(hudMode);
    }

    private void publish(int hudMode) {
        final boolean boostAvailable = !mSessionBoost && !isFastChargingEnabled();
        try {
            Settings.System.putInt(mContext.getContentResolver(),
                    Constants.SETTINGS_CHARGE_HUD_MODE, hudMode);
            Settings.System.putInt(mContext.getContentResolver(),
                    Constants.SETTINGS_CHARGE_BOOST_AVAILABLE, boostAvailable ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to publish charge HUD state", e);
        }
    }
}
