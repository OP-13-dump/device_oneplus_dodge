/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Background service that monitors bypass charging state.
 * - Monitors battery level changes
 * - Monitors foreground apps for app-based bypass
 *
 * Note: Power connect/disconnect is handled by DeviceSettingsService
 */

package org.lineageos.device.settings.bypasschrg;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.ForegroundAppDetector;

import java.util.HashSet;
import java.util.Set;

public class BypassChargingManager extends Service {

    private static final String TAG = "BypassChargingManager";

    private static volatile BypassChargingManager sInstance;

    private Handler mHandler;
    private ForegroundAppDetector mForegroundDetector;
    private boolean mAppMonitoringActive = false;

    private BroadcastReceiver mBatteryReceiver;

    // ===== Lifecycle =====

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mHandler = new Handler();
        mForegroundDetector = ForegroundAppDetector.getInstance(this);
        if (Constants.DEBUG) Log.i(TAG, "Created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterBatteryReceiver();
        stopAppMonitoring();
        sInstance = null;
        if (Constants.DEBUG) Log.i(TAG, "Destroyed");
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

    public static void notifyStateChanged(Context context, BypassChargingController.BypassState state) {
        if (state == null) return;

        BypassChargingManager instance = sInstance;
        if (instance == null) {
            Intent serviceIntent = new Intent(context, BypassChargingManager.class);
            try {
                context.startService(serviceIntent);
                if (Constants.DEBUG) Log.i(TAG, "Service started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service", e);
            }
            return;
        }

        if (Constants.DEBUG) Log.i(TAG, "State changed: " + state.toString());
        instance.mHandler.post(instance::handleStateChanged);
    }

    // ===== State handling =====

    private void handleStateChanged() {
        BypassChargingController controller = BypassChargingController.getInstance(this);
        BypassChargingController.BypassState state = controller.getState();

        if (Constants.DEBUG) Log.i(TAG, "State: " + (state != null ? state.toString() : "null"));

        if (state != null) {
            boolean globalActive = state.globalStatus != Constants.BYPASS_OFF;
            boolean hasAppList = hasBypassAppsInPrefs();

            if (! globalActive && !hasAppList) {
                if (Constants.DEBUG) Log.i(TAG, "Nothing to monitor - stopping service");
                unregisterBatteryReceiver();
                stopAppMonitoring();
                stopSelf();
                return;
            }
        }

        // Monitor battery level
        registerBatteryReceiverIfNeeded();

        // Determine if we need app monitoring
        if (state != null) {
            boolean globalActive = state.globalStatus != Constants.BYPASS_OFF;
            boolean hasAppList = hasBypassAppsInPrefs();
            boolean shouldMonitorApps = ! globalActive && hasAppList;

            if (shouldMonitorApps && !mAppMonitoringActive) {
                if (Constants.DEBUG) Log.i(TAG, "Starting app monitoring");
                startAppMonitoring();
            } else if (!shouldMonitorApps && mAppMonitoringActive) {
                if (Constants.DEBUG) Log.i(TAG, "Stopping app monitoring");
                stopAppMonitoring();
            }
        }
    }

    // ===== App monitoring =====

    private void startAppMonitoring() {
        if (mAppMonitoringActive) {
            if (Constants.DEBUG) Log.w(TAG, "App monitoring already active");
            return;
        }

        mAppMonitoringActive = true;
        BypassChargingManager self = this;
        mForegroundDetector.startMonitoring("BypassCharging", packageName -> {
            if (Constants.DEBUG) Log.i(TAG, "Foreground app: " + packageName);

            BypassChargingController controller = BypassChargingController.getInstance(self);
            Set<String> bypassApps = self.getBypassAppsFromPrefs();

            if (bypassApps.contains(packageName)) {
                if (Constants.DEBUG) Log.i(TAG, "App in bypass list - activating");
                controller.setActiveApp(packageName);
            } else {
                if (Constants.DEBUG) Log.i(TAG, "App not in bypass list - clearing");
                controller.clearActiveApp();
            }
        });
        if (Constants.DEBUG) Log.i(TAG, "App monitoring started");
    }

    private void stopAppMonitoring() {
        if (!mAppMonitoringActive) return;

        mAppMonitoringActive = false;
        mForegroundDetector.stopMonitoring("BypassCharging");
        if (Constants.DEBUG) Log.i(TAG, "App monitoring stopped");
    }

    private Set<String> getBypassAppsFromPrefs() {
        String raw = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Constants.KEY_BYPASS_CHARGING_APPS, "");
        Set<String> apps = new HashSet<>();
        if (!TextUtils.isEmpty(raw)) {
            String[] packages = TextUtils.split(raw, "\\|");
            for (String pkg : packages) {
                if (!TextUtils.isEmpty(pkg)) {
                    apps.add(pkg.trim());
                }
            }
        }
        return apps;
    }

    private boolean hasBypassAppsInPrefs() {
        String raw = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Constants.KEY_BYPASS_CHARGING_APPS, "");
        return ! TextUtils.isEmpty(raw);
    }

    // ===== Battery monitoring (level only) =====

    private void registerBatteryReceiverIfNeeded() {
        if (mBatteryReceiver != null) return;

        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;

                // Only handle battery level changes
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level < 0 || scale <= 0) return;

                int pct = (int) ((level / (float) scale) * 100);

                BypassChargingController controller =
                        BypassChargingController.getInstance(getApplicationContext());
                controller.onBatteryLevelChanged(pct);
            }
        };

        try {
            registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (Constants.DEBUG) Log.i(TAG, "Battery receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register battery receiver", e);
            mBatteryReceiver = null;
        }
    }

    private void unregisterBatteryReceiver() {
        if (mBatteryReceiver != null) {
            try {
                unregisterReceiver(mBatteryReceiver);
            } catch (Exception ignored) {}
            mBatteryReceiver = null;
        }
    }
}
