/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

public class PwmTile extends TileService {
    private static final String TAG = "PwmTile";

    private DisplayModeController mController;
    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = DisplayModeController.getInstance(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        registerReceiver();
        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        unregisterReceiver();
    }

    @Override
    public void onClick() {
        boolean currentState = mController.isPwmEnabled();

        // Immediately update tile to new state for instant feedback
        boolean targetState = ! currentState;
        updateTileImmediate(targetState);

        // Then perform the actual operation
        boolean success;
        if (currentState) {
            success = mController.disablePwm();
        } else {
            success = mController.enablePwm();
        }

        // If operation failed, revert to actual state
        if (!success) {
            Log.w(TAG, "PWM toggle failed, reverting tile state");
            updateTile();
        } else {
            if (Constants.DEBUG) Log.i(TAG, "PWM toggled to: " + targetState);
        }
    }

    /**
     * Update tile to reflect actual state (used for sync)
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean pwmEnabled = mController.isPwmEnabled();

        tile.setState(pwmEnabled ?  Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(pwmEnabled ? getString(R.string.on) : getString(R.string.off));
        tile.setLabel(getString(R.string.onepulse_pwm_mode_title));
        tile.setContentDescription(getString(R.string.onepulse_pwm_mode_summary));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_pwm));
        tile.updateTile();
    }

    /**
     * Update tile to target state immediately (for instant visual feedback)
     */
    private void updateTileImmediate(boolean targetPwmState) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(targetPwmState ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(targetPwmState ?  getString(R.string.on) : getString(R.string.off));
        tile.setLabel(getString(R.string.onepulse_pwm_mode_title));
        tile.setContentDescription(getString(R.string.onepulse_pwm_mode_summary));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_pwm));
        tile.updateTile();
    }

    private void registerReceiver() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DisplayModeController.ACTION_DISPLAY_MODE_CHANGED.equals(intent.getAction())) {
                    updateTile();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DisplayModeController.ACTION_DISPLAY_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }
    }

    private void unregisterReceiver() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mReceiver = null;
        }
    }
}