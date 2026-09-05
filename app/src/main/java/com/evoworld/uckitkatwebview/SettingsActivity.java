package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/*
 * Settings landing page. Only three row can tap, each open own screen
 * (General / Privacy / Site rules), same ideea like AOSP Browser own
 * settings, not one long scroll form. Build by hand in code layout.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));

        addRow(list, getString(R.string.settings_section_general), GeneralSettingsActivity.class);
        addRow(list, getString(R.string.settings_section_privacy), PrivacySettingsActivity.class);
        addRow(list, getString(R.string.settings_section_site_rules), SiteRulesActivity.class);

        setContentView(list);
    }

    private void addRow(LinearLayout parent, String label, final Class<? extends Activity> target) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(android.graphics.Color.WHITE);
        row.setTextSize(16);
        row.setPadding(dp(20), dp(20), dp(20), dp(20));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, target));
            }
        });
        parent.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"));
        parent.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}