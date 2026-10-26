package com.sva.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Remote-friendly, paged movie/series browser using the configured IPTV account. */
final class OnDemandPanel extends LinearLayout {
    private final Activity activity;
    private final IptvRepository iptv;
    private final ChannelLogoLoader artwork;
    private final View navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<OnDemandRepository.Category> categories = new ArrayList<>();
    private final List<OnDemandRepository.Entry> entries = new ArrayList<>();
    private final TextView title, status, pageLabel;
    private final Button movies, series, category, search, refresh, previous, next;
    private final RecyclerView grid;
    private final Cards adapter = new Cards();
    private XtreamAccount account;
    private OnDemandRepository repository;
    private String configured = "", query = "", selectedCategory = "";
    private OnDemandRepository.Entry selectedSeries;
    private boolean seriesMode, closed;
    private int page, generation, total;
    private Future<?> pending;

    OnDemandPanel(Activity activity, IptvRepository iptv, ChannelLogoLoader artwork, View navigation) {
        super(activity);
        this.activity = activity; this.iptv = iptv; this.artwork = artwork; this.navigation = navigation;
        setOrientation(VERTICAL); setPadding(dp(28), dp(16), dp(28), dp(10));
        title = text(26); title.setText(R.string.vod_title); addView(title, new LayoutParams(-1, dp(40)));
        LinearLayout controls = row();
        movies = button(R.string.vod_movies, controls, v -> switchMode(false));
        series = button(R.string.vod_series, controls, v -> switchMode(true));
        category = button(R.string.vod_category, controls, v -> chooseCategory());
        search = button(R.string.vod_search, controls, v -> search());
        refresh = button(R.string.vod_refresh, controls, v -> { if (repository != null) loadPage(); else onShown(); });
        addView(controls, new LayoutParams(-1, dp(46)));
        status = text(13); status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END);
        addView(status, new LayoutParams(-1, dp(28)));
        grid = new RecyclerView(activity); grid.setId(View.generateViewId());
        grid.setLayoutManager(new GridLayoutManager(activity, 2)); grid.setItemAnimator(null);
        grid.setItemViewCacheSize(4); grid.setAdapter(adapter); grid.setClipToPadding(false);
        addView(grid, new LayoutParams(-1, 0, 1));
        LinearLayout footer = row();
        previous = button(R.string.vod_previous, footer, v -> { if (page > 0) { page--; loadPage(); } });
        pageLabel = text(13); pageLabel.setGravity(Gravity.CENTER);
        footer.addView(pageLabel, new LayoutParams(0, -1, 1));
        next = button(R.string.vod_next, footer, v -> { if ((page + 1) * OnDemandRepository.PAGE_SIZE < total) { page++; loadPage(); } });
        previous.setNextFocusDownId(navigation.getId()); next.setNextFocusDownId(navigation.getId());
        addView(footer, new LayoutParams(-1, dp(44)));
        updatePager();
    }

    View entryFocus() { return movies; }

    void onShown() {
        String latest = iptv.playlistUrl();
        if (repository != null && configured.equals(latest)) return;
        configured = latest; account = XtreamAccount.fromPlaylists(latest);
        if (account == null) {
            cancel(); repository = null; entries.clear(); adapter.notifyDataSetChanged();
            total = 0; page = 0; categories.clear(); updatePager(); title.setText(R.string.vod_title);
            status.setText(R.string.vod_configure); return;
        }
        repository = new OnDemandRepository(account); loadCategories();
    }

    boolean back() {
        if (selectedSeries == null) return false;
        selectedSeries = null; page = 0; query = ""; loadPage(); return true;
    }

    void close() { closed = true; cancel(); main.removeCallbacksAndMessages(null); worker.shutdownNow(); }

    private void switchMode(boolean selected) {
        seriesMode = selected; selectedSeries = null; query = ""; page = 0;
        if (repository != null) loadCategories();
    }

    private void loadCategories() {
        selectedSeries = null; page = 0; selectedCategory = ""; categories.clear();
        boolean type = seriesMode;
        load(() -> repository.categories(type), loaded -> {
            categories.addAll(loaded);
            if (categories.isEmpty()) {
                entries.clear(); adapter.notifyDataSetChanged(); total = 0; updatePager();
                status.setText(R.string.vod_empty); return;
            }
            selectedCategory = categories.get(0).id; category.setText(categories.get(0).name); loadPage();
        });
    }

    private void chooseCategory() {
        if (categories.isEmpty()) return;
        String[] names = new String[categories.size()];
        for (int i = 0; i < names.length; i++) names[i] = categories.get(i).name;
        new AlertDialog.Builder(activity).setTitle(R.string.vod_category).setItems(names, (dialog, index) -> {
            selectedCategory = categories.get(index).id; category.setText(names[index]);
            selectedSeries = null; page = 0; query = ""; loadPage();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void search() {
        EditText input = new EditText(activity); input.setSingleLine(true); input.setText(query);
        input.setHint(R.string.vod_search_hint);
        new AlertDialog.Builder(activity).setTitle(R.string.vod_search).setView(input)
                .setPositiveButton(R.string.vod_search, (dialog, which) -> { query = input.getText().toString().trim(); page = 0; loadPage(); })
                .setNeutralButton(R.string.vod_clear, (dialog, which) -> { query = ""; page = 0; loadPage(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void loadPage() {
        if (repository == null) return;
        OnDemandRepository source = repository;
        boolean type = seriesMode; String filter = selectedCategory, term = query;
        int number = page; OnDemandRepository.Entry show = selectedSeries;
        title.setText(show == null ? activity.getString(R.string.vod_title) : show.name);
        load(() -> show == null ? source.catalogue(type, filter, term, number) : source.episodes(show.id, term, number), loaded -> {
            entries.clear(); entries.addAll(loaded.entries); total = loaded.total;
            adapter.notifyDataSetChanged(); grid.scrollToPosition(0); updatePager();
            status.setText(total == 0 ? activity.getString(R.string.vod_empty)
                    : activity.getString(R.string.vod_results, total) + (query.isEmpty() ? "" : " · " + query));
        });
    }

    private <T> void load(Callable<T> task, Consumer<T> complete) {
        cancel(); int request = generation;
        status.setText(R.string.vod_loading); previous.setEnabled(false); next.setEnabled(false);
        entries.clear(); adapter.notifyDataSetChanged();
        pending = worker.submit(() -> {
            try {
                T result = task.call();
                main.post(() -> { if (!closed && request == generation) complete.accept(result); });
            } catch (Exception error) {
                // Network exception messages can contain account URLs; never expose them.
                main.post(() -> { if (!closed && request == generation) status.setText(R.string.vod_failed); });
            }
        });
    }

    private void cancel() { generation++; if (pending != null) pending.cancel(true); }

    private void updatePager() {
        previous.setEnabled(page > 0); next.setEnabled((page + 1) * OnDemandRepository.PAGE_SIZE < total);
        pageLabel.setText(activity.getString(R.string.vod_page, page + 1,
                Math.max(1, (total + OnDemandRepository.PAGE_SIZE - 1) / OnDemandRepository.PAGE_SIZE)));
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void open(OnDemandRepository.Entry entry) {
        if (entry.series) { selectedSeries = entry; page = 0; query = ""; loadPage(); return; }
        try {
            Intent payload = new Intent().putExtra("url", account.stream(entry.episode, entry.id, entry.extension))
                    .putExtra("name", entry.name).putExtra("onDemand", "true");
            activity.startActivity(PlaybackSession.createIntent(activity, payload));
        } catch (Exception error) { status.setText(R.string.vod_failed); }
    }

    private LinearLayout row() { LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(int size) {
        TextView view = new TextView(activity); view.setTextColor(LiquidTheme.text(activity));
        view.setTextSize(size); view.setTypeface(LiquidTheme.font(activity)); return view;
    }
    private Button button(int label, LinearLayout parent, View.OnClickListener click) {
        Button button = new Button(activity); button.setId(View.generateViewId()); button.setText(label);
        button.setTextSize(13); button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setTextColor(LiquidTheme.text(activity)); button.setTypeface(LiquidTheme.font(activity));
        button.setPadding(dp(12), 0, dp(12), 0); button.setBackground(background()); button.setOnClickListener(click);
        LayoutParams params = new LayoutParams(-2, dp(38)); params.setMargins(dp(3), dp(2), dp(3), dp(2));
        parent.addView(button, params); return button;
    }
    private StateListDrawable background() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xFF315C68, 0xFF7BE7F4));
        states.addState(new int[]{}, shape(0x99232E35, 0x665D727B)); return states;
    }
    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), stroke); return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Cards extends RecyclerView.Adapter<Card> {
        @Override public int getItemCount() { return entries.size(); }
        @Override public Card onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout item = row(); item.setPadding(dp(10), dp(7), dp(10), dp(7));
            item.setFocusable(true); item.setClickable(true); item.setBackground(background());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(88)); lp.setMargins(dp(4), dp(4), dp(4), dp(4)); item.setLayoutParams(lp);
            FrameLayout art = new FrameLayout(activity); TextView fallback = text(22); fallback.setText("▶"); fallback.setGravity(Gravity.CENTER);
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1)); art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            item.addView(art, new LayoutParams(dp(48), -1));
            LinearLayout copy = new LinearLayout(activity); copy.setOrientation(VERTICAL); copy.setGravity(Gravity.CENTER_VERTICAL); copy.setPadding(dp(12), 0, 0, 0);
            TextView name = text(15); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); copy.addView(name);
            TextView detail = text(11); copy.addView(detail); item.addView(copy, new LayoutParams(0, -1, 1));
            return new Card(item, name, detail, image, fallback);
        }
        @Override public void onBindViewHolder(Card card, int position) {
            OnDemandRepository.Entry entry = entries.get(position); card.name.setText(entry.name);
            card.detail.setText(entry.episode ? activity.getString(R.string.vod_season, entry.season)
                    : activity.getString(entry.series ? R.string.vod_series : R.string.vod_movies));
            card.itemView.setContentDescription(entry.name); card.itemView.setOnClickListener(v -> open(entry));
            card.itemView.setNextFocusUpId(position < 2 ? movies.getId() : View.NO_ID);
            card.itemView.setNextFocusDownId(position >= Math.max(0, entries.size() - 2)
                    ? (next.isEnabled() ? next.getId() : previous.isEnabled() ? previous.getId() : navigation.getId()) : View.NO_ID);
            artwork.load(entry.artwork, card.image, card.fallback);
        }
        @Override public void onViewRecycled(Card card) { card.image.setTag(null); card.image.setImageDrawable(null); }
    }
    private static final class Card extends RecyclerView.ViewHolder {
        final TextView name, detail, fallback; final ImageView image;
        Card(View root, TextView name, TextView detail, ImageView image, TextView fallback) {
            super(root); this.name = name; this.detail = detail; this.image = image; this.fallback = fallback;
        }
    }
}
