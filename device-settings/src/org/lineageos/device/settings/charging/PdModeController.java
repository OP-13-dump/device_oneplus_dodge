/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.charging;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

/**
 * Persists and applies the user's USB-PD policy. The kernel/Oplus charging
 * stack owns negotiation; this controller only selects the policy.
 */
public final class PdModeController {
    private static final String TAG = "PdModeController";

    private static final String SYSFS_AUTO = "0";
    private static final String SYSFS_FIXED = "1";
    private static final String SYSFS_5V = "2";

    private static PdModeController sInstance;

    private final Context mContext;
    private final Object mLock = new Object();

    public static synchronized PdModeController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PdModeController(context.getApplicationContext());
        }
        return sInstance;
    }

    private PdModeController(Context context) {
        mContext = context;
    }

    public boolean isSupported() {
        return FileUtils.isFileWritable(Constants.NODE_PD_MODE);
    }

    public String getMode() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String mode = prefs.getString(Constants.KEY_PD_MODE, Constants.PD_MODE_AUTOMATIC);
        if (!isValidMode(mode)) {
            mode = Constants.PD_MODE_AUTOMATIC;
        }
        return mode;
    }

    public boolean setMode(String mode) {
        if (!isValidMode(mode)) {
            return false;
        }
        synchronized (mLock) {
            if (!apply(mode)) {
                return false;
            }
            PreferenceManager.getDefaultSharedPreferences(mContext)
                    .edit()
                    .putString(Constants.KEY_PD_MODE, mode)
                    .apply();
            return true;
        }
    }

    /** Re-apply after boot/replug so the policy survives charging-stack resets. */
    public void restore() {
        synchronized (mLock) {
            apply(getMode());
        }
    }

    private boolean apply(String mode) {
        if (!isSupported()) {
            if (Constants.DEBUG) Log.w(TAG, "PD mode node unavailable");
            return false;
        }
        String value = toSysfs(mode);
        if (!FileUtils.writeLine(Constants.NODE_PD_MODE, value)) {
            Log.e(TAG, "Unable to write PD mode " + mode);
            return false;
        }
        String readback = FileUtils.readLineTrimmed(Constants.NODE_PD_MODE);
        boolean ok = value.equals(readback);
        if (Constants.DEBUG) {
            Log.i(TAG, "PD mode=" + mode + " value=" + value + " readback=" + readback);
        }
        return ok;
    }

    private static boolean isValidMode(String mode) {
        return Constants.PD_MODE_AUTOMATIC.equals(mode)
                || Constants.PD_MODE_FIXED.equals(mode)
                || Constants.PD_MODE_5V.equals(mode);
    }

    private static String toSysfs(String mode) {
        if (Constants.PD_MODE_FIXED.equals(mode)) return SYSFS_FIXED;
        if (Constants.PD_MODE_5V.equals(mode)) return SYSFS_5V;
        return SYSFS_AUTO;
    }
}
