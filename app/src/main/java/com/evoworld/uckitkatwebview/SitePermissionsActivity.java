package com.evoworld.uckitkatwebview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/*
 * View/revoke per-site geolocation permission already granted -
 * reads/writes the REAL, engine-level com.uc.webview.export.
 * GeolocationPermissions store directly (confirm exact by smali), the
 * SAME store the engine itself check before ever calling
 * onGeolocationPermissionsShowPrompt at all. Single real source of
 * truth - shows a decision regardless of whether it came from OUR OWN
 * dialog (MainActivity.onGeolocationPermissionRequested()) or some
 * other internal UC flow outside anything we hook (real motivating
 * case: a non-English prompt a user saw once that never asked again).
 *
 * getOrigins() only give back the SET of origin, not each one own
 * allowed/denied status - fan out one getAllowed() call per origin,
 * wait for all before populate the list, simplest correct way to
 * handle this bit of async fan-out without a heavier dependency.
 */
public class SitePermissionsActivity extends Activity {

    private static final class Entry {
        String origin;
        boolean allowed;
    }

    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private ArrayAdapter<Entry> listAdapter;
    private TextView emptyView;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_section_site_permissions));

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setBackgroundColor(Color.parseColor("#1E1E1E"));

        emptyView = new TextView(this);
        emptyView.setText(getString(R.string.site_permissions_loading));
        emptyView.setTextColor(Color.parseColor("#AAAAAA"));
        emptyView.setPadding(dp(20), dp(20), dp(20), dp(20));
        emptyView.setGravity(Gravity.CENTER);
        listLayout.addView(emptyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listView = new ListView(this);
        listAdapter = new ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_1, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView text1 = (TextView) row.findViewById(android.R.id.text1);
                Entry item = getItem(position);
                String status = item.allowed ? getString(R.string.site_permissions_allowed) : getString(R.string.site_permissions_denied);
                text1.setText(item.origin + " - " + status);
                text1.setTextColor(Color.WHITE);
                return row;
            }
        };
        listView.setAdapter(listAdapter);
        listView.setEmptyView(emptyView);

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                final Entry item = entries.get(position);
                new AlertDialog.Builder(SitePermissionsActivity.this)
                    .setTitle(getString(R.string.site_permissions_revoke_title))
                    .setMessage(getString(R.string.site_permissions_revoke_confirm_format, item.origin))
                    .setPositiveButton(getString(R.string.site_permissions_revoke_button), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            MainActivity.clearRealGeoOrigin(item.origin);
                            entries.remove(position);
                            listAdapter.notifyDataSetChanged();
                            Toast.makeText(SitePermissionsActivity.this, getString(R.string.site_permissions_revoked_toast), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
                return true;
            }
        });

        listLayout.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        setContentView(listLayout);

        loadEntries();
    }

    private void loadEntries() {
        MainActivity.listRealGeoOrigins(new MainActivity.GeoOriginsCallback() {
            @Override
            public void onOrigins(final java.util.Set<String> origins) {
                if (origins.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            emptyView.setText(getString(R.string.site_permissions_empty));
                        }
                    });
                    return;
                }
                final List<Entry> collected = new ArrayList<Entry>();
                final int[] remaining = {origins.size()};
                for (final String origin : origins) {
                    MainActivity.getRealGeoAllowed(origin, new MainActivity.GeoAllowedCallback() {
                        @Override
                        public void onAllowed(Boolean allowed) {
                            if (allowed != null) {
                                Entry e = new Entry();
                                e.origin = origin;
                                e.allowed = allowed;
                                synchronized (collected) {
                                    collected.add(e);
                                }
                            }
                            synchronized (remaining) {
                                remaining[0]--;
                                if (remaining[0] == 0) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            entries.clear();
                                            entries.addAll(collected);
                                            listAdapter.notifyDataSetChanged();
                                            emptyView.setText(getString(R.string.site_permissions_empty));
                                        }
                                    });
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}