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

public class PwmTile extends TileService {
    private static final String TAG = "PwmTile";

    private DisplayModeController mController;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = DisplayModeController.getInstance(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        boolean currentState = mController.isPwmEnabled();

        // Instant visual feedback; work (incl. settle sleep) runs off the main thread
        boolean targetState = !currentState;
        updateTileImmediate(targetState);

        final boolean turningOff = currentState;
        new Thread(() -> {
            // enablePwm forces HBM off → DisplayModeController requestListeningState(HbmTile)
            // and RefreshRateTile; disablePwm unlocks HbmTile the same way.
            boolean success = turningOff
                    ? mController.disablePwm()
                    : mController.enablePwm();
            getMainExecutor().execute(() -> {
                if (!success) {
                    Log.w(TAG, "PWM toggle failed, reverting tile state");
                } else if (Constants.DEBUG) {
                    Log.i(TAG, "PWM toggled to: " + targetState);
                }
                updateTile();
            });
        }, "PwmTile-toggle").start();
    }

    /**
     * Update tile to reflect actual state (used for sync)
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean pwmEnabled = mController.isPwmEnabled();

        tile.setState(pwmEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
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
        tile.setSubtitle(targetPwmState ? getString(R.string.on) : getString(R.string.off));
        tile.setLabel(getString(R.string.onepulse_pwm_mode_title));
        tile.setContentDescription(getString(R.string.onepulse_pwm_mode_summary));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_pwm));
        tile.updateTile();
    }
}
