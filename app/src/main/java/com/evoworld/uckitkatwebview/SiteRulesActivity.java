package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import java.util.ArrayList;

/*
 * Per-domain UA/userscript rule. Split out from old big SettingsActivity.
 * List page (page 0) now style same like Bookmarks panel (holo
 * background, header row add/close icon, blue divider) per request, but
 * still own Activity, not overlay panel in MainActivity. So can keep
 * ViewFlipper list/editor structure same, no need big change.
 */
public class SiteRulesActivity extends Activity {

    private ViewFlipper viewFlipper;
    private final ArrayList<SettingsStore.Rule> rules = new ArrayList<SettingsStore.Rule>();
    private ArrayAdapter<SettingsStore.Rule> listAdapter;
    private int currentEditingIndex = -1;

    private EditText editPattern;
    private RadioGroup runAtGroup;
    private RadioButton radioStart;
    private RadioButton radioEnd;
    private EditText editCustomUa;
    private EditText editScript;
    private CheckBox editHideBottomBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_section_site_rules));

        rules.clear();
        rules.addAll(SettingsStore.loadRules(this));

        viewFlipper = new ViewFlipper(this);

        // page 0: rule list, same style like Bookmarks panel
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setBackground(getResources().getDrawable(R.drawable.browser_background_holo));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(16), dp(10), dp(6), dp(10));

        TextView headerTitle = new TextView(this);
        headerTitle.setText(getString(R.string.settings_section_site_rules));
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(18);
        headerRow.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // plain text "+" instead new icon, same way like tab switcher new-tab button
        Button btnAdd = new Button(this);
        btnAdd.setText("+");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(-1);
            }
        });
        headerRow.addView(btnAdd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton btnClose = new ImageButton(this);
        btnClose.setImageResource(R.drawable.ic_close_window_holo_dark);
        btnClose.setBackground(null);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        int iconSize = dp(40);
        headerRow.addView(btnClose, new LinearLayout.LayoutParams(iconSize, iconSize));

        listLayout.addView(headerRow);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33B5E5")); // old Android Holo Blue color
        listLayout.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        ListView listView = new ListView(this);
        listAdapter = new ArrayAdapter<SettingsStore.Rule>(this, android.R.layout.simple_list_item_1, rules);
        listView.setAdapter(listAdapter);
        listLayout.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openEditor(position);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                new AlertDialog.Builder(SiteRulesActivity.this)
                    .setTitle(getString(R.string.settings_delete_rule_title))
                    .setMessage(getString(R.string.settings_delete_rule_confirm_format, rules.get(position).pattern))
                    .setPositiveButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            rules.remove(position);
                            listAdapter.notifyDataSetChanged();
                            SettingsStore.saveRules(SiteRulesActivity.this, rules);
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
                return true;
            }
        });

        viewFlipper.addView(listLayout);

        // page 1: rule editor, no change
        ScrollView editorScroll = new ScrollView(this);
        LinearLayout editLayout = new LinearLayout(this);
        editLayout.setOrientation(LinearLayout.VERTICAL);
        editLayout.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView patternLabel = new TextView(this);
        patternLabel.setText(getString(R.string.settings_match_pattern_label));
        editLayout.addView(patternLabel);

        editPattern = new EditText(this);
        editPattern.setHint(getString(R.string.settings_match_pattern_hint));
        editLayout.addView(editPattern);

        TextView timingLabel = new TextView(this);
        timingLabel.setText(getString(R.string.settings_run_script_at));
        timingLabel.setPadding(0, dp(16), 0, dp(8));
        editLayout.addView(timingLabel);

        runAtGroup = new RadioGroup(this);
        radioStart = new RadioButton(this);
        radioStart.setText(getString(R.string.settings_document_start));
        runAtGroup.addView(radioStart);
        radioEnd = new RadioButton(this);
        radioEnd.setText(getString(R.string.settings_on_loaded));
        runAtGroup.addView(radioEnd);
        editLayout.addView(runAtGroup);

        TextView uaHeader = new TextView(this);
        uaHeader.setText(getString(R.string.settings_custom_ua_label));
        uaHeader.setPadding(0, dp(16), 0, dp(8));
        editLayout.addView(uaHeader);

        editCustomUa = new EditText(this);
        editCustomUa.setLines(2);
        editCustomUa.setHint(getString(R.string.settings_custom_ua_hint));
        editLayout.addView(editCustomUa);

        TextView scriptLabel = new TextView(this);
        scriptLabel.setText(getString(R.string.settings_userscript_label));
        scriptLabel.setPadding(0, dp(16), 0, dp(8));
        editLayout.addView(scriptLabel);

        editScript = new EditText(this);
        editScript.setLines(8);
        editScript.setHint(getString(R.string.settings_userscript_hint));
        editLayout.addView(editScript);

        editHideBottomBar = new CheckBox(this);
        editHideBottomBar.setText(getString(R.string.settings_hide_bottom_bar_domain));
        editHideBottomBar.setPadding(0, dp(16), 0, 0);
        editLayout.addView(editHideBottomBar);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(16), 0, 0);

        Button btnSaveRule = new Button(this);
        btnSaveRule.setText(getString(R.string.settings_save_rule));
        btnSaveRule.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentEditor();
            }
        });
        btnRow.addView(btnSaveRule, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnCancelRule = new Button(this);
        btnCancelRule.setText(getString(R.string.cancel));
        btnCancelRule.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewFlipper.setDisplayedChild(0);
            }
        });
        btnRow.addView(btnCancelRule, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        editLayout.addView(btnRow);
        editorScroll.addView(editLayout);
        viewFlipper.addView(editorScroll);

        setContentView(viewFlipper);
    }

    private void openEditor(int index) {
        currentEditingIndex = index;
        if (index >= 0) {
            SettingsStore.Rule r = rules.get(index);
            editPattern.setText(r.pattern);
            if (SettingsStore.RUN_AT_START.equals(r.runAt)) {
                radioStart.setChecked(true);
            } else {
                radioEnd.setChecked(true);
            }
            editCustomUa.setText(r.customUa != null ? r.customUa : "");
            editScript.setText(r.script != null ? r.script : "");
            editHideBottomBar.setChecked(r.hideBottomBar);
        } else {
            editPattern.setText("");
            radioEnd.setChecked(true);
            editCustomUa.setText("");
            editScript.setText("");
            editHideBottomBar.setChecked(false);
        }
        viewFlipper.setDisplayedChild(1);
    }

    private void saveCurrentEditor() {
        String pattern = editPattern.getText().toString().trim();
        if (TextUtils.isEmpty(pattern)) {
            Toast.makeText(this, getString(R.string.settings_match_pattern_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        String runAt = radioStart.isChecked() ? SettingsStore.RUN_AT_START : SettingsStore.RUN_AT_END;
        String customUa = editCustomUa.getText().toString().trim();
        String script = editScript.getText().toString();
        boolean hideBottomBar = editHideBottomBar.isChecked();

        if (currentEditingIndex >= 0) {
            SettingsStore.Rule existing = rules.get(currentEditingIndex);
            existing.pattern = pattern;
            existing.runAt = runAt;
            existing.customUa = customUa;
            existing.script = script;
            existing.hideBottomBar = hideBottomBar;
        } else {
            rules.add(new SettingsStore.Rule(pattern, customUa, script, runAt, hideBottomBar));
        }

        listAdapter.notifyDataSetChanged();
        SettingsStore.saveRules(this, rules);
        viewFlipper.setDisplayedChild(0);
    }

    @Override
    public void onBackPressed() {
        if (viewFlipper.getDisplayedChild() == 1) {
            viewFlipper.setDisplayedChild(0);
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}