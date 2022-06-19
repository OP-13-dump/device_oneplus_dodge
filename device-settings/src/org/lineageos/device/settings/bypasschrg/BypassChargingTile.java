/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.bypasschrg;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

public class BypassChargingTile extends TileService {


    private BypassChargingController mBypassController;
    private boolean mEnabled;

    @Override
    public void onCreate() {
        super.onCreate();
        mBypassController = BypassChargingController.getInstance(this);
    }

    @Override
    public void onStartListening() {
        int status = mBypassController.getBypassChargingStatus();
        mEnabled = status != Constants.BYPASS_OFF;
        updateTileState(status);
    }

    @Override
    public void onClick() {
        boolean enabled = mBypassController.getBypassChargingStatus() != Constants.BYPASS_OFF;
        if (mEnabled == enabled) {
            boolean success;
            if (mEnabled) {
                success = mBypassController.disableBypassCharging() ? true : false;
            }
            else {
                success = mBypassController.enableBypassCharging() ? true : false;
            }
            if (success) {
                mEnabled = !mEnabled;
                updateTileState(mBypassController.getBypassChargingStatus());
            }
        }
    }

    private void updateTileState(int status) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(status==Constants.BYPASS_OFF ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setLabel(getString(R.string.bypass_charging_title));
        tile.setContentDescription(getString(R.string.bypass_charging_summary));
        if (status==Constants.BYPASS_WAITING) {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bypass_waiting));
        }
        else {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bypass_charging));
        }
        tile.updateTile();
    }
}
