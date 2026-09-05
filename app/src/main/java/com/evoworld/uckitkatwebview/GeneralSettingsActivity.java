package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class GeneralSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_section_general));

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#1E1E1E"));
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView defaultUrlLabel = new TextView(this);
        defaultUrlLabel.setText(getString(R.string.settings_default_url_label));
        layout.addView(defaultUrlLabel);

        final EditText defaultUrlInput = new EditText(this);
        defaultUrlInput.setHint("https://www.google.com");
        defaultUrlInput.setText(SettingsStore.getDefaultUrl(this));
        layout.addView(defaultUrlInput);

        final CheckBox loadLastUrlCheck = new CheckBox(this);
        loadLastUrlCheck.setText(getString(R.string.settings_load_last_url));
        loadLastUrlCheck.setChecked(SettingsStore.getLoadLastUrl(this));
        layout.addView(loadLastUrlCheck);

        TextView uaLabel = new TextView(this);
        uaLabel.setText(getString(R.string.settings_global_ua_label));
        uaLabel.setPadding(0, dp(20), 0, dp(8));
        layout.addView(uaLabel);

        final EditText globalUaInput = new EditText(this);
        globalUaInput.setLines(2);
        globalUaInput.setHint(getString(R.string.settings_global_ua_hint));
        globalUaInput.setText(SettingsStore.getGlobalUa(this));
        layout.addView(globalUaInput);

        Button btnSave = new Button(this);
        btnSave.setText(getString(R.string.settings_save_app));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsStore.setDefaultUrl(GeneralSettingsActivity.this, defaultUrlInput.getText().toString());
                SettingsStore.setLoadLastUrl(GeneralSettingsActivity.this, loadLastUrlCheck.isChecked());
                SettingsStore.setGlobalUa(GeneralSettingsActivity.this, globalUaInput.getText().toString());
                Toast.makeText(GeneralSettingsActivity.this, getString(R.string.settings_app_saved), Toast.LENGTH_SHORT).show();
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