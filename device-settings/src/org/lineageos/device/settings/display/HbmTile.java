/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

public class HbmTile extends TileService {
    private static final String TAG = "HbmTile";

    private DisplayModeController mController;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = DisplayModeController.getInstance(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        // Also reached via TileService.requestListeningState from PWM changes
        updateTile();
    }

    @Override
    public void onClick() {
        boolean currentState = mController.isHbmEnabled();
        boolean pwmEnabled = mController.isPwmEnabled();

        // Can't enable if PWM is active
        if (!currentState && pwmEnabled) {
            if (Constants.DEBUG) Log.w(TAG, "Cannot enable HBM: PWM active");
            updateTile();
            return;
        }

        // Instant visual feedback; work (incl. settle sleep) runs off the main thread
        boolean targetState = !currentState;
        updateTileImmediate(targetState, pwmEnabled);

        final boolean turningOff = currentState;
        new Thread(() -> {
            boolean success = turningOff
                    ? mController.disableHbm()
                    : mController.enableHbm();
            getMainExecutor().execute(() -> {
                if (!success) {
                    Log.w(TAG, "HBM toggle failed, reverting tile state");
                } else if (Constants.DEBUG) {
                    Log.i(TAG, "HBM toggled to: " + targetState);
                }
                updateTile();
            });
        }, "HbmTile-toggle").start();
    }

    /**
     * Update tile to reflect actual state (used for sync)
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean hbmEnabled = mController.isHbmEnabled();
        boolean pwmEnabled = mController.isPwmEnabled();

        if (pwmEnabled) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.hbm_unavailable_pwm_active));
        } else {
            tile.setState(hbmEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(hbmEnabled ? getString(R.string.on) : getString(R.string.off));
        }

        tile.setLabel(getString(R.string.hbm_title));
        tile.setContentDescription(getString(R.string.hbm_summary));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_hbm));
        tile.updateTile();
    }

    /**
     * Update tile to target state immediately (for instant visual feedback)
     */
    private void updateTileImmediate(boolean targetHbmState, boolean pwmEnabled) {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (pwmEnabled) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.hbm_unavailable_pwm_active));
        } else {
            tile.setState(targetHbmState ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(targetHbmState ? getString(R.string.on) : getString(R.string.off));
        }

        tile.setLabel(getString(R.string.hbm_title));
        tile.setContentDescription(getString(R.string.hbm_summary));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_hbm));
        tile.updateTile();
    }
}
