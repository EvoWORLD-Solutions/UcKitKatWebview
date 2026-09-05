package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/*
 * About screen, reach from MainActivity overflow menu
 * (showSettingsMenu(), "About" item)
 * plain style like GeneralSettingsActivity/PrivacySettingsActivity.
 * Content is single string resource (R.string.about_placeholder)
 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.menu_about));

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#1E1E1E"));
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView aboutText = new TextView(this);
        aboutText.setText(getString(R.string.about_placeholder));
        aboutText.setTextColor(Color.WHITE);
        aboutText.setTextSize(15);
        layout.addView(aboutText);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}