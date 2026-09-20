package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/*
 * Ad/tracker block management screen, reach from MainActivity overflow
 * menu ("Ad blocking" item). Plain hand-build ScrollView-of-section
 * layout same style as GeneralSettingsActivity/PrivacySettingsActivity
 * (not SiteRulesActivity own ViewFlipper list/editor - add-list/add-
 * domain here only ever need one or two field, a small AlertDialog
 * cover that fine, no need a whole separate editor page).
 *
 * All AdBlockStore read/write happen direct on UI thread here (small
 * table, few row, fast query - same reasoning SiteRulesActivity already
 * use for SettingsStore.loadRules()). AdBlockEngine.reload() (the
 * actual re-parse, can be couple MB/tens-of-thousand-line EasyList)
 * always dispatch to a background Thread though, matches
 * MainActivity own established "heavier work off UI thread" pattern.
 */
public class AdBlockActivity extends Activity {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private CheckBox mGlobalEnabledCheck;
    private TextView mUpdateStatusText;
    private LinearLayout mListsContainer;
    private LinearLayout mWhitelistContainer;
    private LinearLayout mRecentLogContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.menu_ad_blocking));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1E1E1E"));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        mGlobalEnabledCheck = new CheckBox(this);
        mGlobalEnabledCheck.setText(getString(R.string.adblock_enable_master));
        mGlobalEnabledCheck.setTextColor(Color.WHITE);
        mGlobalEnabledCheck.setChecked(SettingsStore.getAdBlockGlobalEnabled(this));
        mGlobalEnabledCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsStore.setAdBlockGlobalEnabled(AdBlockActivity.this, isChecked);
                reloadEngineInBackground();
            }
        });
        root.addView(mGlobalEnabledCheck);

        addSectionDivider(root);

        // update section
        TextView updateHeader = sectionHeader(getString(R.string.adblock_section_update));
        root.addView(updateHeader);

        mUpdateStatusText = new TextView(this);
        mUpdateStatusText.setTextColor(Color.parseColor("#AAAAAA"));
        mUpdateStatusText.setTextSize(13);
        mUpdateStatusText.setPadding(0, dp(4), 0, dp(8));
        root.addView(mUpdateStatusText);

        Button btnUpdateAll = new Button(this);
        btnUpdateAll.setText(getString(R.string.adblock_update_now));
        btnUpdateAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateAllNow();
            }
        });
        root.addView(btnUpdateAll);

        addSectionDivider(root);

        // filter lists section
        LinearLayout listsHeaderRow = new LinearLayout(this);
        listsHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        listsHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView listsHeader = sectionHeader(getString(R.string.adblock_section_lists));
        listsHeaderRow.addView(listsHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnAddList = new Button(this);
        btnAddList.setText(getString(R.string.add_button_symbol));
        btnAddList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddFilterListDialog();
            }
        });
        listsHeaderRow.addView(btnAddList);
        root.addView(listsHeaderRow);

        mListsContainer = new LinearLayout(this);
        mListsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mListsContainer);

        addSectionDivider(root);

        // whitelist section
        LinearLayout whitelistHeaderRow = new LinearLayout(this);
        whitelistHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        whitelistHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView whitelistHeader = sectionHeader(getString(R.string.adblock_section_whitelist));
        whitelistHeaderRow.addView(whitelistHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnAddWhitelist = new Button(this);
        btnAddWhitelist.setText(getString(R.string.add_button_symbol));
        btnAddWhitelist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddWhitelistDialog();
            }
        });
        whitelistHeaderRow.addView(btnAddWhitelist);
        root.addView(whitelistHeaderRow);

        mWhitelistContainer = new LinearLayout(this);
        mWhitelistContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mWhitelistContainer);

        addSectionDivider(root);

        // recent blocked log section (transparency - "see what actually happen")
        TextView logHeader = sectionHeader(getString(R.string.adblock_section_recent));
        root.addView(logHeader);

        mRecentLogContainer = new LinearLayout(this);
        mRecentLogContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mRecentLogContainer);

        scroll.addView(root);
        setContentView(scroll);

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi(); // pick up rule-count/last-update change if an update finish while this screen was in background
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setPadding(0, dp(12), 0, dp(6));
        return tv;
    }

    private void addSectionDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        parent.addView(divider, lp);
    }

    // filter lists

    private void refreshUi() {
        AdBlockStore store = new AdBlockStore(this);
        List<AdBlockStore.FilterList> lists;
        List<String> whitelist;
        try {
            lists = store.listFilterLists();
            whitelist = store.listWhitelist();
        } finally {
            store.close();
        }
        rebuildListsSection(lists);
        rebuildWhitelistSection(whitelist);
        rebuildRecentLogSection();
    }

    private void rebuildListsSection(List<AdBlockStore.FilterList> lists) {
        mListsContainer.removeAllViews();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (final AdBlockStore.FilterList list : lists) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            CheckBox enabledCheck = new CheckBox(this);
            enabledCheck.setChecked(list.enabled);
            enabledCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    AdBlockStore s = new AdBlockStore(AdBlockActivity.this);
                    try {
                        s.setFilterListEnabled(list.id, isChecked);
                    } finally {
                        s.close();
                    }
                    reloadEngineInBackground();
                }
            });
            row.addView(enabledCheck);

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            TextView nameText = new TextView(this);
            nameText.setText(list.name);
            nameText.setTextColor(Color.WHITE);
            nameText.setTextSize(15);
            infoCol.addView(nameText);

            String statusLine;
            if (!TextUtils.isEmpty(list.lastError)) {
                statusLine = getString(R.string.adblock_list_status_error, list.lastError);
            } else if (list.lastUpdated == 0) {
                statusLine = getString(R.string.adblock_list_status_never);
            } else {
                statusLine = getString(R.string.adblock_list_status_ok, list.ruleCount, fmt.format(new java.util.Date(list.lastUpdated)));
            }
            TextView statusText = new TextView(this);
            statusText.setText(statusLine);
            statusText.setTextColor(Color.parseColor("#AAAAAA"));
            statusText.setTextSize(12);
            infoCol.addView(statusText);

            row.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button removeBtn = new Button(this);
            removeBtn.setText(getString(R.string.delete));
            removeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRemoveFilterList(list);
                }
            });
            row.addView(removeBtn);

            mListsContainer.addView(row);
        }
    }

    private void confirmRemoveFilterList(final AdBlockStore.FilterList list) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.adblock_remove_list_title))
            .setMessage(getString(R.string.adblock_remove_list_confirm_format, list.name))
            .setPositiveButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    AdBlockStore s = new AdBlockStore(AdBlockActivity.this);
                    try {
                        s.removeFilterList(AdBlockActivity.this, list.id);
                    } finally {
                        s.close();
                    }
                    reloadEngineInBackground();
                    refreshUi();
                }
            })
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void showAddFilterListDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(0));

        final EditText nameInput = new EditText(this);
        nameInput.setHint(getString(R.string.adblock_add_list_name_hint));
        layout.addView(nameInput);

        final EditText urlInput = new EditText(this);
        urlInput.setHint(getString(R.string.adblock_add_list_url_hint));
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.adblock_add_list_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.adblock_add), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(url)) {
                        Toast.makeText(AdBlockActivity.this, getString(R.string.adblock_add_list_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    addFilterListAndFetch(name, url);
                }
            })
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void addFilterListAndFetch(String name, String url) {
        AdBlockStore store = new AdBlockStore(this);
        final long id;
        try {
            id = store.addFilterList(name, url);
        } finally {
            store.close();
        }
        refreshUi();
        // fetch just this new one right away, nicer than make user tap "Update now" separate for the list they only just add
        final AdBlockStore.FilterList newList = new AdBlockStore.FilterList();
        newList.id = id;
        newList.name = name;
        newList.url = url;
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdBlockEngine.updateListBlocking(AdBlockActivity.this, newList, new AdBlockEngine.UpdateCallback() {
                    @Override
                    public void onListUpdated(long listId, boolean success, int ruleCount, String error) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                refreshUi();
                            }
                        });
                    }
                    @Override
                    public void onAllDone() {}
                });
                AdBlockEngine.reload(getApplicationContext());
            }
        }, "AdBlockAddListFetch").start();
    }

    // whitelist

    private void rebuildWhitelistSection(List<String> whitelist) {
        mWhitelistContainer.removeAllViews();
        if (whitelist.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.adblock_whitelist_empty));
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setTextSize(13);
            mWhitelistContainer.addView(empty);
            return;
        }
        for (final String host : whitelist) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView hostText = new TextView(this);
            hostText.setText(host);
            hostText.setTextColor(Color.WHITE);
            hostText.setTextSize(14);
            row.addView(hostText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button removeBtn = new Button(this);
            removeBtn.setText(getString(R.string.delete));
            removeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AdBlockStore s = new AdBlockStore(AdBlockActivity.this);
                    try {
                        s.removeWhitelistDomain(host);
                    } finally {
                        s.close();
                    }
                    reloadEngineInBackground();
                    refreshUi();
                }
            });
            row.addView(removeBtn);

            mWhitelistContainer.addView(row);
        }
    }

    private void showAddWhitelistDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.adblock_add_whitelist_hint));
        int pad = dp(20);
        input.setPadding(pad, dp(16), pad, 0);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.adblock_add_whitelist_title))
            .setView(input)
            .setPositiveButton(getString(R.string.adblock_add), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String host = input.getText().toString().trim();
                    if (TextUtils.isEmpty(host)) return;
                    AdBlockStore s = new AdBlockStore(AdBlockActivity.this);
                    try {
                        s.addWhitelistDomain(host);
                    } finally {
                        s.close();
                    }
                    reloadEngineInBackground();
                    refreshUi();
                }
            })
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    // recent blocked log (transparency)

    private void rebuildRecentLogSection() {
        mRecentLogContainer.removeAllViews();
        List<AdBlockEngine.BlockEvent> events = AdBlockEngine.recentEvents();
        if (events.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.adblock_recent_empty));
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setTextSize(13);
            mRecentLogContainer.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        // events already newest-first (see AdBlockEngine.recordBlock()), show at most 50 here even though up to 200 kept in memory - keep this screen light
        int shown = 0;
        for (AdBlockEngine.BlockEvent ev : events) {
            if (shown >= 50) break;
            TextView row = new TextView(this);
            row.setText(fmt.format(new java.util.Date(ev.time)) + "  " + ev.url + "  [" + ev.listName + "]");
            row.setTextColor(Color.parseColor("#CCCCCC"));
            row.setTextSize(12);
            row.setPadding(0, dp(2), 0, dp(2));
            mRecentLogContainer.addView(row);
            shown++;
        }
    }

    // shared

    private void updateAllNow() {
        mUpdateStatusText.setText(getString(R.string.adblock_updating));
        AdBlockEngine.updateAllNowAsync(this, new AdBlockEngine.UpdateCallback() {
            @Override
            public void onListUpdated(final long listId, final boolean success, final int ruleCount, final String error) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshUi();
                    }
                });
            }
            @Override
            public void onAllDone() {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mUpdateStatusText.setText(getString(R.string.adblock_update_done));
                        refreshUi();
                    }
                });
            }
        });
    }

    private void reloadEngineInBackground() {
        final android.content.Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdBlockEngine.reload(appCtx);
            }
        }, "AdBlockReload").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}