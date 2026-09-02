/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.refreshrate;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;
import org.lineageos.device.settings.display.DisplayModeController;

public class RefreshRateTile extends TileService {
    private static final String TAG = "RefreshRateTile";

    private static final int[] RATES = {
            RefreshRateController.REFRESH_RATE_AUTO,
            RefreshRateController.REFRESH_RATE_60,
            RefreshRateController.REFRESH_RATE_90,
            RefreshRateController.REFRESH_RATE_120
    };

    private RefreshRateController mController;
    private DisplayModeController mDisplayController;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = RefreshRateController.getInstance(getApplicationContext());
        mDisplayController = DisplayModeController.getInstance(getApplicationContext());
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        // Also reached via TileService.requestListeningState when HBM/PWM changes
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        // Check if refresh rate changes are blocked (HBM locks to 120Hz)
        if (!mDisplayController.canChangeRefreshRate()) {
            if (Constants.DEBUG) Log.w(TAG, "Cannot change: HBM active");
            updateTile();
            return;
        }

        int current = mController.getGlobalRefreshRate();
        int next = getNextRate(current);

        // Immediately update tile to new state for instant feedback
        updateTileImmediate(next);

        // Then perform the actual operation
        mController.setGlobalRefreshRate(next);

        if (Constants.DEBUG) Log.i(TAG, "Refresh rate: " + current + " -> " + next);
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    private int getNextRate(int current) {
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == current) {
                return RATES[(i + 1) % RATES.length];
            }
        }
        return RefreshRateController.REFRESH_RATE_AUTO;
    }

    /**
     * Update tile to reflect actual state (used for sync)
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean hbmActive = mDisplayController.isHbmEnabled();
        int current = mController.getGlobalRefreshRate();

        if (hbmActive) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.refresh_rate_locked_hbm));
        } else {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setSubtitle(formatRate(current));
        }

        tile.setLabel(getString(R.string.refresh_rate_title));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_refresh_rate));
        tile.updateTile();
    }

    /**
     * Update tile to target state immediately (for instant visual feedback)
     */
    private void updateTileImmediate(int targetRate) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(Tile.STATE_ACTIVE);
        tile.setSubtitle(formatRate(targetRate));
        tile.setLabel(getString(R.string.refresh_rate_title));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_refresh_rate));
        tile.updateTile();
    }

    private String formatRate(int rate) {
        switch (rate) {
            case RefreshRateController.REFRESH_RATE_AUTO:
                return getString(R.string.refresh_rate_auto);
            case RefreshRateController.REFRESH_RATE_60:
                return getString(R.string.refresh_rate_60hz);
            case RefreshRateController.REFRESH_RATE_90:
                return getString(R.string.refresh_rate_90hz);
            case RefreshRateController.REFRESH_RATE_120:
                return getString(R.string.refresh_rate_120hz);
            default:
                return getString(R.string.refresh_rate_auto);
        }
    }
}
