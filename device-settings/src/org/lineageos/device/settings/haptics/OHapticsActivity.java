/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.haptics;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.lineageos.device.settings.R;

public class OHapticsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.ohaptics_title);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new OHapticsFragment())
                    .commit();
        }
    }
}
