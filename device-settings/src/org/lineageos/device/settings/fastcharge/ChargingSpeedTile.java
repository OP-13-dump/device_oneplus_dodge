/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.fastcharge;

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

public class ChargingSpeedTile extends TileService {
    private static final String TAG = "ChargingSpeedTile";

    private FastChargeController mController;
    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = FastChargeController.getInstance(this);
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
        super.onClick();

        if (!mController.isSupported()) {
            return;
        }

        String current = mController.getChargingSpeedMode();
        String next = getNextMode(current);

        // Immediate visual feedback
        updateTileImmediate(next);

        // Apply mode change
        mController.setChargingSpeedMode(next);

        if (Constants.DEBUG) Log.i(TAG, "Charging speed: " + current + " -> " + next);
    }

    private String getNextMode(String current) {
        switch (current) {
            case Constants.CHARGING_SPEED_DEFAULT:
                return Constants.CHARGING_SPEED_FAST;
            case Constants.CHARGING_SPEED_FAST:
                return Constants.CHARGING_SPEED_NIGHT;
            case Constants.CHARGING_SPEED_NIGHT:
            default:
                return Constants.CHARGING_SPEED_DEFAULT;
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (!mController.isSupported()) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(getString(R.string.charging_speed_title));
            tile.setSubtitle(null);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fast_charging));
            tile.updateTile();
            return;
        }

        String mode = mController.getChargingSpeedMode();
        applyTileState(tile, mode);
        tile.updateTile();
    }

    private void updateTileImmediate(String mode) {
        Tile tile = getQsTile();
        if (tile == null) return;

        applyTileState(tile, mode);
        tile.updateTile();
    }

    private void applyTileState(Tile tile, String mode) {
        tile.setLabel(getString(R.string.charging_speed_title));

        switch (mode) {
            case Constants.CHARGING_SPEED_FAST:
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle(getString(R.string.charging_speed_fast));
                tile.setContentDescription(getString(R.string.charging_speed_title) + ": " + getString(R.string.charging_speed_fast));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fast_charging));
                break;
            case Constants.CHARGING_SPEED_NIGHT:
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle(getString(R.string.charging_speed_night));
                tile.setContentDescription(getString(R.string.charging_speed_title) + ": " + getString(R.string.charging_speed_night));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_night_charging));
                break;
            case Constants.CHARGING_SPEED_DEFAULT:
            default:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.charging_speed_default));
                tile.setContentDescription(getString(R.string.charging_speed_title) + ": " + getString(R.string.charging_speed_default));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fast_charging));
                break;
        }
    }

    private void registerReceiver() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.ACTION_CHARGING_SPEED_CHANGED.equals(intent.getAction())) {
                    updateTile();
                }
            }
        };

        IntentFilter filter = new IntentFilter(Constants.ACTION_CHARGING_SPEED_CHANGED);
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
