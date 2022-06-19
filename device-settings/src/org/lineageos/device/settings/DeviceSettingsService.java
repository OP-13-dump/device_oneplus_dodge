/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Central service for DeviceSettings.
 * - Always running after boot
 * - Handles screen on/off events
 * - Handles power connect/disconnect events
 * - Initializes and manages other services/controllers
 */

package org.lineageos.device.settings;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.bypasschrg.BypassChargingController;
import org.lineageos.device.settings.bypasschrg.BypassChargingManager;
import org.lineageos.device.settings.display.DisplayModeController;
import org.lineageos.device.settings.display.HbmController;
import org.lineageos.device.settings.gamebar.GameBar;
import org.lineageos.device.settings.gamebar.GameBarMonitorService;
import org.lineageos.device.settings.refreshrate.RefreshRateController;
import org.lineageos.device.settings.refreshrate.RefreshRateMonitorService;

public class DeviceSettingsService extends Service {
    private static final String TAG = "DeviceSettingsService";

    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Constants.DEBUG) Log.i(TAG, "Service created");

        // Initialize all subsystems
        initializeSubsystems();

        // Register receivers for screen and power events
        registerReceivers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Constants.DEBUG) Log.i(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
        unregisterReceivers();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== Initialization =====

    private void initializeSubsystems() {
        initializeBypassCharging();
        initializeGameBar();
        initializeRefreshRate();
    }

    private void initializeBypassCharging() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing BypassCharging");
        try {
            BypassChargingController controller = BypassChargingController.getInstance(this);
            BypassChargingManager.notifyStateChanged(this, controller.getState());
            if (Constants.DEBUG) Log.i(TAG, "BypassCharging initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BypassCharging", e);
        }
    }

    private void initializeGameBar() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing GameBar");
        try {
            var prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean masterEnabled = prefs.getBoolean("game_bar_enable", false);
            boolean hasAutoApps = ! TextUtils.isEmpty(
                    prefs.getString(Constants.KEY_GAMEBAR_AUTO_APPS, ""));

            if (Constants.DEBUG) Log.i(TAG, "GameBar: master=" + masterEnabled + ", autoApps=" + hasAutoApps);

            if (masterEnabled) {
                GameBar.getInstance(this).applyPreferences();
                GameBar.getInstance(this).show();
            }

            if (masterEnabled || hasAutoApps) {
                GameBarMonitorService.notifyStateChanged(this);
            }

            if (Constants.DEBUG) Log.i(TAG, "GameBar initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize GameBar", e);
        }
    }

    private void initializeRefreshRate() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing RefreshRate");
        try {
            RefreshRateController.getInstance(this);
            RefreshRateMonitorService.notifyStateChanged(this);
            if (Constants.DEBUG) Log.i(TAG, "RefreshRate initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize RefreshRate", e);
        }
    }

    // ===== Receivers =====

    private void registerReceivers() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                final String action = intent.getAction();
                if (Constants.DEBUG) Log.i(TAG, "Received: " + action);

                switch (action) {
                    case Intent.ACTION_SCREEN_OFF:
                        handleScreenOff();
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        handleScreenOn();
                        break;
                    case Intent.ACTION_POWER_CONNECTED:
                        handlePowerConnected();
                        break;
                    case Intent.ACTION_POWER_DISCONNECTED:
                        handlePowerDisconnected();
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        try {
            registerReceiver(mReceiver, filter);
            if (Constants.DEBUG) Log.i(TAG, "Receivers registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receivers", e);
            mReceiver = null;
        }
    }

    private void unregisterReceivers() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mReceiver = null;
        }
    }

    // ===== Screen Event Handlers =====

    private void handleScreenOff() {
        if (Constants.DEBUG) Log.i(TAG, "Screen OFF");

        // Disable HBM (business rule #2)
        try {
            HbmController hbmController = HbmController.getInstance(this);
            if (hbmController.isHbmEnabled()) {
                hbmController.disableHbm();
                DisplayModeController.getInstance(this).broadcastStateChange();
                if (Constants.DEBUG) Log.i(TAG, "HBM disabled on screen off");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable HBM on screen off", e);
        }

        // Stop GameBar
        try {
            GameBar.getInstance(this).hide();
            Intent svc = new Intent(this, GameBarMonitorService.class);
            stopService(svc);
            if (Constants.DEBUG) Log.i(TAG, "GameBar stopped on screen off");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop GameBar on screen off", e);
        }
    }

    private void handleScreenOn() {
        if (Constants.DEBUG) Log.i(TAG, "Screen ON");

        // Restart GameBar if needed
        try {
            var prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean masterEnabled = prefs.getBoolean("game_bar_enable", false);
            boolean hasAutoApps = ! TextUtils.isEmpty(
                    prefs.getString(Constants.KEY_GAMEBAR_AUTO_APPS, ""));

            if (masterEnabled || hasAutoApps) {
                GameBarMonitorService.notifyStateChanged(this);
                if (Constants.DEBUG) Log.i(TAG, "GameBar restarted on screen on");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart GameBar on screen on", e);
        }
    }

    // ===== Power Event Handlers =====

    private void handlePowerConnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power CONNECTED");

        // Notify BypassChargingController
        try {
            BypassChargingController.getInstance(this).handlePowerConnected();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle power connected", e);
        }
    }

    private void handlePowerDisconnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power DISCONNECTED");
        // No action needed - hardware bypass is irrelevant without power
        // BypassChargingController state remains for next connect
    }
}