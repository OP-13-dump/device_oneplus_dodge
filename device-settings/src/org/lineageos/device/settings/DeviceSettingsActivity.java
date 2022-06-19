/*
 * Copyright (C) 2018-2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.device.settings;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

public class DeviceSettingsActivity extends CollapsingToolbarBaseActivity {
    private View banner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(" ");
        getSupportFragmentManager().beginTransaction().replace(
                R.id.content_frame,
                new DeviceSettings()).commit();

        // Inject banner dynamically into CollapsingToolbarLayout
        CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        if (collapsingToolbar != null) {
            collapsingToolbar.setTitleEnabled(false);

            int margin16dp = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16, collapsingToolbar.getResources().getDisplayMetrics());

            // Load mask drawable and apply 16dp horizontal inset to match banner margins
            Drawable maskDrawable = getDrawable(R.drawable.oplus_mask);
            if (maskDrawable != null) {
                InsetDrawable insetMask = new InsetDrawable(maskDrawable, margin16dp, 0, margin16dp, 0);
                collapsingToolbar.setContentScrim(insetMask);
            }

            collapsingToolbar.setScrimVisibleHeightTrigger((int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 84, collapsingToolbar.getResources().getDisplayMetrics()));
            collapsingToolbar.setScrimAnimationDuration(300);

            View bannerLayout = getLayoutInflater().inflate(R.layout.oplus_banner_layout, collapsingToolbar, false);
            collapsingToolbar.addView(bannerLayout, 0);
            banner = bannerLayout.findViewById(R.id.banner);

            // Fade animation: banner fades out as we collapse, mask fades in
            AppBarLayout appBar = findViewById(R.id.app_bar);
            if (appBar != null) {
                appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
                    if (banner == null) return;
                    int totalScrollRange = appBarLayout.getTotalScrollRange();
                    float offsetFraction = Math.abs(verticalOffset) / (float) totalScrollRange;

                    // Smooth fade: banner alpha goes from 1 to 0
                    float bAlpha = 1f - offsetFraction;
                    banner.setAlpha(Math.max(0f, bAlpha));
                });
            }
        }

        // Set content top margin
        View contentLayout = findViewById(R.id.content_frame);
        if (contentLayout != null) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) contentLayout.getLayoutParams();
            int topMarginPx = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 48, contentLayout.getResources().getDisplayMetrics());
            mlp.setMargins(mlp.leftMargin, topMarginPx, mlp.rightMargin, mlp.bottomMargin);
            contentLayout.setLayoutParams(mlp);
        }
    }
}
