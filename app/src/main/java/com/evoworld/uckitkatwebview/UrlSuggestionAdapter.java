package com.evoworld.uckitkatwebview;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/*
 * Address bar suggestion dropdown - own version of AOSP Browser own
 * SuggestionsAdapter, same dual tap target idea (row body navigate
 * direct, icon2 fill address bar text only) and same 4-icon-by-type
 * concept, but back by BrowserDatabase direct instead of AOSP own
 * ContentProvider/Cursor machinery - this project already have
 * searchBookmarks()/searchHistory(), no need replicate that whole
 * older, heavier architecture just to get the same result.
 *
 * Local-only for now (bookmark + history match), no live online search
 * suggestion - can add a third CursorSource-equivalent later if want,
 * this class own Filter design already leave room for it (just add
 * another loop in performFiltering()).
 */
public class UrlSuggestionAdapter extends BaseAdapter implements Filterable {

    public enum SuggestionType { BOOKMARK, HISTORY }

    public static final class Suggestion {
        public final String title;
        public final String url;
        public final SuggestionType type;

        Suggestion(String title, String url, SuggestionType type) {
            this.title = title;
            this.url = url;
            this.type = type;
        }
    }

    /*
     * Call back into MainActivity for the two distinct tap action -
     * "navigate now" (row body tap) vs "fill address bar text only,
     * keep editing" (icon2 tap, matches AOSP own onSearch()/
     * onCopySuggestion() split).
     */
    public interface OnSuggestionActionListener {
        void onSuggestionNavigate(String url);
        void onSuggestionFillOnly(String text);
    }

    // cap visible result same spirit as AOSP own (dropdown that scroll forever not great UX on a phone screen)
    private static final int MAX_RESULTS = 6;

    private final BrowserDatabase mDb;
    private final OnSuggestionActionListener mListener;
    private final LayoutInflater mInflater;
    private List<Suggestion> mResults = new ArrayList<Suggestion>();
    private final UrlSuggestionFilter mFilter = new UrlSuggestionFilter();

    public UrlSuggestionAdapter(Context ctx, BrowserDatabase db, OnSuggestionActionListener listener) {
        mDb = db;
        mListener = listener;
        mInflater = LayoutInflater.from(ctx);
    }

    @Override
    public int getCount() {
        return mResults.size();
    }

    @Override
    public Suggestion getItem(int position) {
        return mResults.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final View row = convertView != null ? convertView : mInflater.inflate(R.layout.suggestion_item, parent, false);
        final Suggestion item = getItem(position);

        TextView titleView = (TextView) row.findViewById(android.R.id.text1);
        TextView urlView = (TextView) row.findViewById(android.R.id.text2);
        ImageView icon1 = (ImageView) row.findViewById(R.id.icon1);
        View suggestionArea = row.findViewById(R.id.suggestion);
        View icon2 = row.findViewById(R.id.icon2);

        titleView.setText(TextUtils.isEmpty(item.title) ? item.url : item.title);
        urlView.setText(item.url);
        icon1.setImageResource(item.type == SuggestionType.BOOKMARK
            ? R.drawable.ic_search_category_bookmark
            : R.drawable.ic_search_category_history);

        /*
         * Two SEPARATE click listener on two SEPARATE sub-view, same
         * trick AOSP own bindView() use - a child view with its own
         * OnClickListener claim the touch before the row-wide default
         * AdapterView item-click mechanism ever get a chance to fire,
         * that how "tap row body vs tap icon2" can behave different
         * even though both live inside the same ListView row. No need
         * touch AutoCompleteTextView.setOnItemClickListener() at all
         * given this (both sub-view between them already cover the
         * row full width, no dead space left for the default handler
         * to ever catch).
         */
        suggestionArea.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onSuggestionNavigate(item.url);
            }
        });
        icon2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onSuggestionFillOnly(item.url);
            }
        });

        return row;
    }

    @Override
    public Filter getFilter() {
        return mFilter;
    }

    /*
     * AutoCompleteTextView require its adapter implement Filterable,
     * and itself already run performFiltering() off the UI thread
     * (Filter class own internal Handler+Thread), publishResults() back
     * on UI thread - exactly the threading a SQLite query need, no
     * extra AsyncTask/Thread management need write ourself for this.
     */
    private final class UrlSuggestionFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            List<Suggestion> out = new ArrayList<Suggestion>();
            String q = constraint == null ? "" : constraint.toString().trim();
            if (q.length() > 0 && mDb != null) {
                try {
                    for (BrowserDatabase.Bookmark b : mDb.searchBookmarks(q)) {
                        if (out.size() >= MAX_RESULTS) break;
                        out.add(new Suggestion(b.title, b.url, SuggestionType.BOOKMARK));
                    }
                } catch (Throwable ignored) {}
                if (out.size() < MAX_RESULTS) {
                    try {
                        for (BrowserDatabase.HistoryEntry h : mDb.searchHistory(q)) {
                            if (out.size() >= MAX_RESULTS) break;
                            if (containsUrl(out, h.url)) continue; // already show as a bookmark match, don't list twice
                            out.add(new Suggestion(h.title, h.url, SuggestionType.HISTORY));
                        }
                    } catch (Throwable ignored) {}
                }
            }
            results.values = out;
            results.count = out.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            mResults = results != null && results.values != null ? (List<Suggestion>) results.values : new ArrayList<Suggestion>();
            if (results != null && results.count > 0) {
                notifyDataSetChanged();
            } else {
                notifyDataSetInvalidated();
            }
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            return resultValue instanceof Suggestion ? ((Suggestion) resultValue).url : "";
        }

        private boolean containsUrl(List<Suggestion> list, String url) {
            for (Suggestion s : list) {
                if (s.url != null && s.url.equals(url)) return true;
            }
            return false;
        }
    }
}