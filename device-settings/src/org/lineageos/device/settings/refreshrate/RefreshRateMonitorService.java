/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Background service that monitors and applies refresh rate changes.
 * Runs only when app override list is not empty.
 * Uses Settings.System MIN/PEAK_REFRESH_RATE for framework integration.
 */

package org.lineageos.device.settings.refreshrate;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.ForegroundAppDetector;

public class RefreshRateMonitorService extends Service {

    private static final String TAG = "RefreshRateMonitorService";

    private static volatile RefreshRateMonitorService sInstance;

    private RefreshRateController mRefreshRateController;
    private Handler mHandler;
    private ForegroundAppDetector mForegroundDetector;
    private boolean mAppMonitoringActive = false;

    private static final String KEY_BACKUP_MIN_REFRESH_RATE = "rr_backup_min_refresh_rate";
    private static final String KEY_BACKUP_MAX_REFRESH_RATE = "rr_backup_max_refresh_rate";

    private static final float REFRESH_RATE_AUTO_MIN = 60f;
    private static final float REFRESH_RATE_AUTO_MAX = 120f;

    private float mBackedUpMinRate = -1f;
    private float mBackedUpMaxRate = -1f;

    // ===== Lifecycle =====

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mRefreshRateController = RefreshRateController.getInstance(this);
        mHandler = new Handler();
        mForegroundDetector = ForegroundAppDetector.getInstance(this);
        if (Constants.DEBUG) Log.i(TAG, "Service created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAppMonitoring();
        restoreRefreshRates();
        sInstance = null;
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHandler.post(this::handleStateChanged);
        return START_STICKY;
    }

    // ===== Public API =====

    public static void notifyStateChanged(Context context) {
        RefreshRateMonitorService instance = sInstance;
        if (instance == null) {
            // Start service
            Intent serviceIntent = new Intent(context, RefreshRateMonitorService.class);
            try {
                context.startService(serviceIntent);
                if (Constants.DEBUG) Log.i(TAG, "Service started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service", e);
            }
            return;
        }

        if (Constants.DEBUG) Log.i(TAG, "State changed notification received");
        instance.mHandler.post(instance::handleStateChanged);
    }

    // ===== State handling =====

    private void handleStateChanged() {
        RefreshRateController.RefreshRateState state = mRefreshRateController.getState();

        if (Constants.DEBUG) Log.i(TAG, "State: " + state.toString());

        // Determine if we need to monitor
        boolean hasAppOverrides = mRefreshRateController.hasAppOverrides();

        if (!hasAppOverrides) {
            if (Constants.DEBUG) Log.i(TAG, "No app overrides - stopping app monitoring");
            stopAppMonitoring();
            // Still apply global rate even without app overrides
            applyRefreshRate(state.globalRefreshRate);
            return;
        }

        // App override list exists - start monitoring
        if (!mAppMonitoringActive) {
            if (Constants.DEBUG) Log.i(TAG, "Starting app monitoring");
            startAppMonitoring();
        }
    }

    // ===== App monitoring =====

    private void startAppMonitoring() {
        if (mAppMonitoringActive) {
            if (Constants.DEBUG) Log.w(TAG, "App monitoring already active");
            return;
        }

        mAppMonitoringActive = true;

        mForegroundDetector.startMonitoring("RefreshRate", packageName -> {
            if (Constants.DEBUG) Log.i(TAG, "Foreground app: " + packageName);

            int targetFps = mRefreshRateController.getEffectiveRefreshRate(packageName);

            if (Constants.DEBUG) {
                String desc = targetFps == 0 ? "auto" : targetFps + " fps";
                Log.i(TAG, "Applying refresh rate: " + desc + " for " + packageName);
            }
            applyRefreshRate(targetFps);
        });

        if (Constants.DEBUG) Log.i(TAG, "App monitoring listener registered");
    }

    private void stopAppMonitoring() {
        if (!mAppMonitoringActive) {
            return;
        }

        mAppMonitoringActive = false;
        mForegroundDetector.stopMonitoring("RefreshRate");
        if (Constants.DEBUG) Log.i(TAG, "App monitoring stopped");
    }

    // ===== Refresh rate application via Settings =====

    private void applyRefreshRate(int fps) {
        if (mRefreshRateController.shouldSkipRefreshRateChanges()) return;

        // Backup current rates if not already backed up
        if (mBackedUpMinRate < 0 || mBackedUpMaxRate < 0) {
            backupCurrentRates();
        }

        if (fps == 0) {
            // Auto/dynamic mode: set MIN to 60, MAX to 120
            if (Constants.DEBUG) Log.i(TAG, "Setting dynamic refresh rate - MIN: 60Hz, MAX: 120Hz");

            Settings.System.putFloatForUser(
                    getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE,
                    REFRESH_RATE_AUTO_MIN,
                    UserHandle.USER_CURRENT
            );

            Settings.System.putFloatForUser(
                    getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE,
                    REFRESH_RATE_AUTO_MAX,
                    UserHandle.USER_CURRENT
            );
        } else {
            // Fixed fps mode: lock both MIN and MAX to the same value
            float targetRate = (float) fps;

            if (Constants.DEBUG) Log.i(TAG, "Setting fixed refresh rate to: " + targetRate + " Hz");

            Settings.System.putFloatForUser(
                    getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE,
                    targetRate,
                    UserHandle.USER_CURRENT
            );

            Settings.System.putFloatForUser(
                    getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE,
                    targetRate,
                    UserHandle.USER_CURRENT
            );
        }

        if (Constants.DEBUG) Log.i(TAG, "Refresh rate applied successfully");
    }

    private void backupCurrentRates() {
        try {
            mBackedUpMinRate = Settings.System.getFloatForUser(
                    getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE,
                    60f,
                    UserHandle.USER_CURRENT
            );

            mBackedUpMaxRate = Settings.System.getFloatForUser(
                    getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE,
                    120f,
                    UserHandle.USER_CURRENT
            );

            // Also store in SharedPreferences for persistence across service restarts
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit()
                    .putFloat(KEY_BACKUP_MIN_REFRESH_RATE, mBackedUpMinRate)
                    .putFloat(KEY_BACKUP_MAX_REFRESH_RATE, mBackedUpMaxRate)
                    .apply();

            if (Constants.DEBUG) Log.i(TAG, "Backed up refresh rates - MIN: " + mBackedUpMinRate + ", MAX: " + mBackedUpMaxRate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to backup refresh rates", e);
        }
    }

    private void restoreRefreshRates() {
        try {
            // Try to restore from memory first
            if (mBackedUpMinRate > 0 && mBackedUpMaxRate > 0) {
                Settings.System.putFloatForUser(
                        getContentResolver(),
                        Settings.System.MIN_REFRESH_RATE,
                        mBackedUpMinRate,
                        UserHandle.USER_CURRENT
                );

                Settings.System.putFloatForUser(
                        getContentResolver(),
                        Settings.System.PEAK_REFRESH_RATE,
                        mBackedUpMaxRate,
                        UserHandle.USER_CURRENT
                );

                if (Constants.DEBUG) Log.i(TAG, "Restored refresh rates - MIN: " + mBackedUpMinRate + ", MAX: " + mBackedUpMaxRate);
            } else {
                // Try to restore from SharedPreferences
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                float minRate = prefs.getFloat(KEY_BACKUP_MIN_REFRESH_RATE, 60f);
                float maxRate = prefs.getFloat(KEY_BACKUP_MAX_REFRESH_RATE, 120f);

                Settings.System.putFloatForUser(
                        getContentResolver(),
                        Settings.System.MIN_REFRESH_RATE,
                        minRate,
                        UserHandle.USER_CURRENT
                );

                Settings.System.putFloatForUser(
                        getContentResolver(),
                        Settings.System.PEAK_REFRESH_RATE,
                        maxRate,
                        UserHandle.USER_CURRENT
                );

                if (Constants.DEBUG) Log.i(TAG, "Restored refresh rates from prefs - MIN: " + minRate + ", MAX: " + maxRate);
            }

            // Clear backups
            mBackedUpMinRate = -1f;
            mBackedUpMaxRate = -1f;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit()
                    .remove(KEY_BACKUP_MIN_REFRESH_RATE)
                    .remove(KEY_BACKUP_MAX_REFRESH_RATE)
                    .apply();

            if (Constants.DEBUG) Log.i(TAG, "Refresh rates restored - Smooth Display auto-enabled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore refresh rates", e);
        }
    }
}
