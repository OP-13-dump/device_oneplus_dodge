/*
 * SPDX-FileCopyrightText: 2026
 * SPDX-License-Identifier: Apache-2.0
 *
 * Quick Settings tile for the LTPO (adaptive refresh rate) master switch.
 * Mirrors the switch in the refresh-rate settings screen: toggles the
 * kernel's min_fps node via RefreshRateController and stays in sync through
 * ACTION_LTPO_STATE_CHANGED.
 */

package org.lineageos.device.settings.refreshrate;

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

public class LtpoTile extends TileService {
    private static final String TAG = "LtpoTile";

    private RefreshRateController mController;
    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = RefreshRateController.getInstance(this);
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
        boolean current = mController.isLtpoEnabled();
        boolean target = !current;

        // Immediate visual feedback, then commit
        updateTileImmediate(target);
        mController.setLtpoEnabled(target);

        if (Constants.DEBUG) Log.i(TAG, "LTPO toggled to: " + target);
    }

    /**
     * Update tile to reflect actual state (used for sync)
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean enabled = mController.isLtpoEnabled();
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(enabled ? getString(R.string.ltpo_adaptive)
                : getString(R.string.ltpo_fixed));
        tile.setLabel(getString(R.string.ltpo_tile_label));
        tile.setContentDescription(getString(R.string.ltpo_tile_description));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_ltpo));
        tile.updateTile();
    }

    /**
     * Update tile to target state immediately (for instant visual feedback)
     */
    private void updateTileImmediate(boolean targetEnabled) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(targetEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(targetEnabled ? getString(R.string.ltpo_adaptive)
                : getString(R.string.ltpo_fixed));
        tile.setLabel(getString(R.string.ltpo_tile_label));
        tile.setContentDescription(getString(R.string.ltpo_tile_description));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_ltpo));
        tile.updateTile();
    }

    private void registerReceiver() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.ACTION_LTPO_STATE_CHANGED.equals(intent.getAction())) {
                    updateTile();
                }
            }
        };

        IntentFilter filter = new IntentFilter(Constants.ACTION_LTPO_STATE_CHANGED);
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
