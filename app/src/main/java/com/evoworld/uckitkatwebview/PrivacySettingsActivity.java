package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class PrivacySettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_section_privacy));

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#1E1E1E"));
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        final CheckBox hideBottomBarGlobalCheck = new CheckBox(this);
        hideBottomBarGlobalCheck.setText(getString(R.string.settings_hide_bottom_bar_global));
        hideBottomBarGlobalCheck.setChecked(SettingsStore.getHideBottomBarGlobal(this));
        layout.addView(hideBottomBarGlobalCheck);

        final CheckBox preventAutoKeyboardWakeCheck = new CheckBox(this);
        preventAutoKeyboardWakeCheck.setText(getString(R.string.settings_prevent_auto_keyboard));
        preventAutoKeyboardWakeCheck.setChecked(SettingsStore.getPreventAutoKeyboardWake(this));
        layout.addView(preventAutoKeyboardWakeCheck);

        TextView historyExpiryLabel = new TextView(this);
        historyExpiryLabel.setText(getString(R.string.settings_history_expiry_title));
        historyExpiryLabel.setTextSize(16);
        historyExpiryLabel.setPadding(0, dp(20), 0, dp(8));
        layout.addView(historyExpiryLabel);

        final RadioGroup historyExpiryGroup = new RadioGroup(this);
        historyExpiryGroup.setOrientation(RadioGroup.VERTICAL);
        final int[] historyExpiryDaysValues = {30, 90, 180, 365, SettingsStore.HISTORY_EXPIRY_FOREVER};
        final String[] historyExpiryLabels = {
            getString(R.string.settings_history_expiry_1_month),
            getString(R.string.settings_history_expiry_3_months),
            getString(R.string.settings_history_expiry_6_months),
            getString(R.string.settings_history_expiry_1_year),
            getString(R.string.settings_history_expiry_forever)
        };
        int currentExpiryDays = SettingsStore.getHistoryExpiryDays(this);
        for (int i = 0; i < historyExpiryDaysValues.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(historyExpiryLabels[i]);
            rb.setId(i + 1); // radio group need id different, if not can not know which one check
            if (historyExpiryDaysValues[i] == currentExpiryDays) {
                rb.setChecked(true);
            }
            historyExpiryGroup.addView(rb);
        }
        layout.addView(historyExpiryGroup);

        Button btnSave = new Button(this);
        btnSave.setText(getString(R.string.settings_save_app));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsStore.setHideBottomBarGlobal(PrivacySettingsActivity.this, hideBottomBarGlobalCheck.isChecked());
                SettingsStore.setPreventAutoKeyboardWake(PrivacySettingsActivity.this, preventAutoKeyboardWakeCheck.isChecked());
                int checkedId = historyExpiryGroup.getCheckedRadioButtonId();
                if (checkedId > 0 && checkedId <= historyExpiryDaysValues.length) {
                    SettingsStore.setHistoryExpiryDays(PrivacySettingsActivity.this, historyExpiryDaysValues[checkedId - 1]);
                }
                Toast.makeText(PrivacySettingsActivity.this, getString(R.string.settings_app_saved), Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnSave);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}