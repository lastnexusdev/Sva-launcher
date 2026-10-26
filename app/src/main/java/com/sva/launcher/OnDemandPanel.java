package com.sva.launcher;

import android.app.*;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Compact provider-category shelves, prefilled newest first, with card actions. */
final class OnDemandPanel extends LinearLayout {
    private final Activity activity;
    private final IptvRepository iptv;
    private final View navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledThreadPoolExecutor metadataWorker = new ScheduledThreadPoolExecutor(1);
    private final ChannelLogoLoader posters;
    private final List<OnDemandRepository.Category> categories = new ArrayList<>();
    private final List<OnDemandRepository.Entry> entries = new ArrayList<>(), cursors = new ArrayList<>();
    private final List<OnDemandRepository.Shelf> shelves = new ArrayList<>();
    private final Map<Boolean, OnDemandRepository.Home> homes = new HashMap<>();
    private final Map<Boolean, Long> homeTimes = new HashMap<>();
    private final Map<Boolean, List<OnDemandRepository.Category>> categoryCache = new HashMap<>();
    private final Map<String, OnDemandRepository.Details> detailsCache = new LinkedHashMap<String, OnDemandRepository.Details>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, OnDemandRepository.Details> entry) { return size() > 32; }
    };
    private final RecyclerView sidebar, content;
    private final CategoryRows categoryRows = new CategoryRows();
    private final ShelfRows shelfRows = new ShelfRows();
    private final PosterCards gridCards = new PosterCards(entries, false);
    private final TextView scope, clock, status, pageLabel;
    private final Button movies, shows, previous, next;
    private final LinearLayout footer;
    private XtreamAccount account;
    private OnDemandRepository repository;
    private OnDemandRepository.Entry featured, selectedSeries;
    private String configured = "", selectedCategory = "", query = "";
    private boolean seriesMode, overview = true, closed, returnToOverview, busy;
    private int page, total, generation, heroGeneration, focusGeneration;
    private Future<?> pending, detailsPending;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() { if (closed) return; if (isShown()) updateClock(); main.postDelayed(this, 30000); }
    };

    OnDemandPanel(Activity activity, IptvRepository iptv, ChannelLogoLoader unused, View navigation, Runnable settings) {
        super(activity); this.activity = activity; this.iptv = iptv; this.navigation = navigation;
        metadataWorker.setRemoveOnCancelPolicy(true);
        posters = new ChannelLogoLoader(activity, 384, 6 * 1024 * 1024, "on-demand-posters");
        setOrientation(VERTICAL); setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout heading = row(); TextView title = text(25); title.setText(R.string.vod_title); title.setTypeface(LiquidTheme.font(activity), Typeface.BOLD);
        heading.addView(title, new LayoutParams(-2, -1)); scope = text(18); scope.setPadding(dp(16), 0, dp(12), 0); scope.setSingleLine(); scope.setEllipsize(TextUtils.TruncateAt.END); heading.addView(scope, new LayoutParams(0, -1, 1));
        clock = text(13); clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); heading.addView(clock, new LayoutParams(dp(125), -1)); addView(heading, new LayoutParams(-1, dp(42)));
        LinearLayout body = row(); body.setGravity(Gravity.TOP); addView(body, new LayoutParams(-1, 0, 1));
        LinearLayout rail = new LinearLayout(activity); rail.setOrientation(VERTICAL); body.addView(rail, new LayoutParams(dp(156), -1));
        railButton(rail, R.string.vod_search, v -> search()); railButton(rail, R.string.vod_home, v -> showHome());
        movies = railButton(rail, R.string.vod_movies, v -> switchMode(false)); shows = railButton(rail, R.string.vod_tv_shows, v -> switchMode(true));
        TextView caption = text(10); caption.setText(R.string.vod_provider_categories); caption.setPadding(dp(8), dp(5), 0, 0); rail.addView(caption, new LayoutParams(-1, dp(23)));
        sidebar = new RecyclerView(activity); sidebar.setLayoutManager(new LinearLayoutManager(activity)); sidebar.setAdapter(categoryRows); sidebar.setItemAnimator(null); rail.addView(sidebar, new LayoutParams(-1, 0, 1));
        railButton(rail, R.string.vod_refresh, v -> refresh()); railButton(rail, R.string.vod_settings, v -> settings.run());
        LinearLayout right = new LinearLayout(activity); right.setOrientation(VERTICAL); right.setPadding(dp(10), 0, 0, 0); body.addView(right, new LayoutParams(0, -1, 1));
        status = text(10); status.setSingleLine(); status.setEllipsize(TextUtils.TruncateAt.END); right.addView(status, new LayoutParams(-1, dp(20)));
        content = new RecyclerView(activity); content.setId(View.generateViewId()); content.setItemAnimator(null); content.setItemViewCacheSize(2); content.setClipToPadding(false); right.addView(content, new LayoutParams(-1, 0, 1));
        footer = row(); previous = button(activity.getString(R.string.vod_previous)); previous.setOnClickListener(v -> { if (page > 0) { page--; loadPage(true); } }); footer.addView(previous, new LayoutParams(dp(90), dp(32)));
        pageLabel = text(12); pageLabel.setGravity(Gravity.CENTER); footer.addView(pageLabel, new LayoutParams(0, -1, 1)); next = button(activity.getString(R.string.vod_next)); next.setOnClickListener(v -> nextPage()); footer.addView(next, new LayoutParams(dp(70), dp(32)));
        previous.setNextFocusDownId(navigation.getId()); next.setNextFocusDownId(navigation.getId()); right.addView(footer, new LayoutParams(-1, dp(35)));
        resetPages(); setOverviewLayout(); clearInfoSelection(); updateHeader(); main.post(clockTick);
    }
    View entryFocus() { return movies; }
    void onShown() {
        updateHeader(); String latest = iptv.playlistUrl(); if (repository != null && latest.equals(configured)) { if (!busy && overview && System.currentTimeMillis() - homeTimes.getOrDefault(seriesMode, 0L) > 600000L) { if (categoryCache.containsKey(seriesMode)) showHome(); else switchMode(seriesMode); } return; }
        configured = latest; account = XtreamAccount.fromPlaylists(latest); homeTimes.clear(); homes.clear(); categoryCache.clear(); detailsCache.clear(); categories.clear(); shelves.clear(); entries.clear(); categoryRows.notifyDataSetChanged(); shelfRows.notifyDataSetChanged(); gridCards.notifyDataSetChanged(); clearInfoSelection();
        if (account == null) { cancel(); repository = null; status.setVisibility(VISIBLE); status.setText(R.string.vod_configure); clearInfoSelection(); return; }
        repository = new OnDemandRepository(account); switchMode(false);
    }
    boolean back() {
        if (selectedSeries != null) { selectedSeries = null; query = ""; if (returnToOverview) showHome(); else { resetPages(); updateHeader(); loadPage(true); } return true; }
        if (!overview) { showHome(); return true; } return false;
    }
    void close() { closed = true; cancel(); heroGeneration++; if (detailsPending != null) detailsPending.cancel(true); main.removeCallbacksAndMessages(null); worker.shutdownNow(); metadataWorker.shutdownNow(); posters.close(); }
    private void switchMode(boolean series) {
        cancel(); if (seriesMode != series) { categories.clear(); shelves.clear(); entries.clear(); clearInfoSelection(); }
        seriesMode = series; selectedCategory = ""; selectedSeries = null; query = ""; overview = true; resetPages(); updateHeader(); if (repository == null) return;
        List<OnDemandRepository.Category> cached = categoryCache.get(seriesMode);
        if (cached != null) { categories.clear(); categories.addAll(cached); categoryRows.notifyDataSetChanged(); showHome(); return; }
        boolean kind = seriesMode; OnDemandRepository source = repository;
        load(() -> source.categories(kind), loaded -> { categories.clear(); categories.addAll(loaded); categoryCache.put(kind, new ArrayList<>(loaded)); categoryRows.notifyDataSetChanged(); showHome(); });
    }
    private void showHome() {
        cancel();
        overview = true; selectedCategory = ""; selectedSeries = null; query = ""; resetPages(); setOverviewLayout(); updateHeader(); if (repository == null) return;
        OnDemandRepository.Home cached = homes.get(seriesMode); if (cached != null && System.currentTimeMillis() - homeTimes.getOrDefault(seriesMode, 0L) < 600000L) { displayHome(cached); return; }
        OnDemandRepository source = repository; boolean kind = seriesMode; List<OnDemandRepository.Category> groups = new ArrayList<>(categories);
        load(() -> source.home(kind, groups), loaded -> { homes.put(kind, loaded); homeTimes.put(kind, System.currentTimeMillis()); displayHome(loaded); });
    }
    private void displayHome(OnDemandRepository.Home data) {
        shelves.clear(); shelves.addAll(data.shelves); shelfRows.notifyDataSetChanged(); content.scrollToPosition(0); status.setText(activity.getString(R.string.vod_newest_categories, shelves.size()));
        content.setContentDescription(activity.getString(R.string.vod_newest_categories, shelves.size())); status.setVisibility(shelves.isEmpty() ? VISIBLE : GONE); if (shelves.isEmpty()) status.setText(R.string.vod_empty);
    }
    private void setOverviewLayout() { content.setAdapter(null); content.setLayoutManager(new LinearLayoutManager(activity)); content.setAdapter(shelfRows); footer.setVisibility(GONE); }
    private void chooseCategory(OnDemandRepository.Category category) { entries.clear(); clearInfoSelection(); selectedCategory = category.id; selectedSeries = null; query = ""; overview = false; resetPages(); setGridLayout(); updateHeader(); loadPage(true); }
    private void setGridLayout() { content.setAdapter(null); content.setLayoutManager(new GridLayoutManager(activity, 5)); content.setAdapter(gridCards); footer.setVisibility(VISIBLE); }
    private void resetPages() { page = 0; total = 0; cursors.clear(); cursors.add(null); previous.setEnabled(false); next.setEnabled(false); pageLabel.setText(activity.getString(R.string.vod_page, 1, 1)); }
    private void nextPage() {
        if ((page + 1) * OnDemandRepository.PAGE_SIZE >= total || entries.isEmpty()) return;
        if (cursors.size() <= page + 1) cursors.add(entries.get(entries.size() - 1)); else cursors.set(page + 1, entries.get(entries.size() - 1)); page++; loadPage(true);
    }
    private void loadPage(boolean focus) {
        if (repository == null) return;
        OnDemandRepository source = repository; boolean kind = seriesMode; String group = selectedCategory, term = query; int number = page; OnDemandRepository.Entry before = cursors.get(page), show = selectedSeries;
        load(() -> show == null ? source.newest(kind, group, term, before, OnDemandRepository.PAGE_SIZE) : source.episodes(show.id, term, number), loaded -> {
            entries.clear(); entries.addAll(loaded.entries); total = loaded.total; gridCards.notifyDataSetChanged(); content.scrollToPosition(0);
            previous.setEnabled(page > 0); next.setEnabled((page + 1) * OnDemandRepository.PAGE_SIZE < total); pageLabel.setText(activity.getString(R.string.vod_page, page + 1, Math.max(1, (total + OnDemandRepository.PAGE_SIZE - 1) / OnDemandRepository.PAGE_SIZE)));
            status.setText(activity.getString(R.string.vod_results, total) + (query.isEmpty() ? " · " + activity.getString(R.string.vod_newest_first) : " · " + query));
            content.setContentDescription(activity.getString(R.string.vod_results, total)); status.setVisibility(entries.isEmpty() ? VISIBLE : GONE); if (entries.isEmpty()) status.setText(R.string.vod_empty); if (focus) focusFirst();
        });
    }
    private void refresh() { homes.remove(seriesMode); homeTimes.remove(seriesMode); if (overview) { categoryCache.remove(seriesMode); switchMode(seriesMode); } else loadPage(false); }
    private void search() {
        EditText input = new EditText(activity); input.setSingleLine(); input.setText(query); input.setHint(overview ? R.string.vod_search_all_hint : R.string.vod_search_hint);
        new AlertDialog.Builder(activity).setTitle(R.string.vod_search).setView(input).setPositiveButton(R.string.vod_search, (dialog, which) -> { query = input.getText().toString().trim(); selectedSeries = null; overview = false; resetPages(); setGridLayout(); updateHeader(); loadPage(true); })
            .setNeutralButton(R.string.vod_clear, (dialog, which) -> { query = ""; if (selectedCategory.isEmpty()) showHome(); else { resetPages(); loadPage(true); } }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void updateHeader() {
        String label = activity.getString(seriesMode ? R.string.vod_tv_shows : R.string.vod_movies); for (OnDemandRepository.Category group : categories) if (group.id.equals(selectedCategory)) label = group.name; if (selectedSeries != null) label = selectedSeries.name;
        scope.setText("│  " + label); movies.setSelected(overview && !seriesMode); shows.setSelected(overview && seriesMode); categoryRows.notifyDataSetChanged(); updateClock();
    }
    private void updateClock() { clock.setText(new SimpleDateFormat((activity.getSharedPreferences("launcher", 0).getBoolean("24h", false) ? "HH:mm" : "h:mm a") + "\nEEE, d MMM", AppLanguage.locale()).format(new Date())); }
    private void clearInfoSelection() { featured = null; heroGeneration++; if (detailsPending != null) detailsPending.cancel(true); }
    private String key(OnDemandRepository.Entry item) { return (item.series ? "series:" : item.episode ? "episode:" : "movie:") + item.id; }
    private void requestDetails(OnDemandRepository.Entry item, boolean dialog) {
        if (detailsPending != null) detailsPending.cancel(true); int request = ++heroGeneration; OnDemandRepository source = repository;
        detailsPending = metadataWorker.schedule(() -> { try { OnDemandRepository.Details details = source.details(item); main.post(() -> {
            if (closed || request != heroGeneration || featured == null || !key(featured).equals(key(item))) return; detailsCache.put(key(item), details); if (dialog && isShown()) infoDialog(item, details);
        }); } catch (Exception ignored) { if (dialog) main.post(() -> { if (!closed && request == heroGeneration && isShown()) infoDialog(item, new OnDemandRepository.Details()); }); } }, dialog ? 0 : 250, TimeUnit.MILLISECONDS);
    }
    private void moreInfo(OnDemandRepository.Entry item) {
        featured = item; heroGeneration++;
        OnDemandRepository.Details details = detailsCache.get(key(item));
        if (details == null && !item.episode) requestDetails(item, true);
        else infoDialog(item, details == null ? new OnDemandRepository.Details() : details);
    }
    private void infoDialog(OnDemandRepository.Entry item, OnDemandRepository.Details details) {
        TextView copy = text(16); copy.setPadding(dp(20), dp(15), dp(20), dp(15)); copy.setText(details.metadata() + "\n\n" + android.text.Html.fromHtml(details.plot, android.text.Html.FROM_HTML_MODE_LEGACY) + (details.cast.isEmpty() ? "" : "\n\n" + activity.getString(R.string.vod_cast) + ": " + details.cast) + (details.director.isEmpty() ? "" : "\n\n" + activity.getString(R.string.vod_director) + ": " + details.director));
        ScrollView scroll = new ScrollView(activity); scroll.addView(copy); new AlertDialog.Builder(activity).setTitle(item.name).setView(scroll).setPositiveButton(item.series ? R.string.vod_view_episodes : R.string.vod_watch_now, (dialog, which) -> open(item)).setNegativeButton(android.R.string.cancel, null).show();
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void open(OnDemandRepository.Entry item) {
        if (item.series) { returnToOverview = overview; selectedSeries = item; overview = false; query = ""; resetPages(); setGridLayout(); updateHeader(); loadPage(true); return; }
        try { activity.startActivity(PlaybackSession.createIntent(activity, new Intent().putExtra("url", account.stream(item.episode, item.id, item.extension)).putExtra("name", item.name).putExtra("onDemand", "true"))); } catch (Exception ignored) { status.setText(R.string.vod_failed); }
    }
    private <T> void load(Callable<T> task, Consumer<T> complete) { cancel(); int request = generation; busy = true; previous.setEnabled(false); next.setEnabled(false); status.setVisibility(VISIBLE); status.setText(R.string.vod_loading); pending = worker.submit(() -> { try { T value = task.call(); main.post(() -> { if (!closed && request == generation) { busy = false; complete.accept(value); } }); } catch (Exception ignored) { main.post(() -> { if (!closed && request == generation) { busy = false; status.setText(R.string.vod_failed); } }); } }); }
    private void cancel() { busy = false; generation++; focusGeneration++; if (pending != null) pending.cancel(true); }
    private void focusFirst() { int request = ++focusGeneration; content.postOnAnimation(() -> focusFirst(request, 0)); }
    private void focusFirst(int request, int attempt) {
        if (closed || request != focusGeneration || !isShown()) return;
        int position = Math.max(0, ((LinearLayoutManager) content.getLayoutManager()).findFirstVisibleItemPosition());
        RecyclerView.ViewHolder holder = content.findViewHolderForAdapterPosition(position);
        if (holder instanceof ShelfRow) {
            RecyclerView cards = ((ShelfRow) holder).cards;
            int cardPosition = Math.max(0, ((LinearLayoutManager) cards.getLayoutManager()).findFirstVisibleItemPosition());
            RecyclerView.ViewHolder card = cards.findViewHolderForAdapterPosition(cardPosition);
            if (card != null && card.itemView.requestFocus()) return;
        } else if (holder != null && holder.itemView.requestFocus()) return;
        if (attempt < 4) content.postOnAnimation(() -> focusFirst(request, attempt + 1));
    }
    private Button railButton(LinearLayout rail, int label, View.OnClickListener action) {
        Button button = button(activity.getString(label)); button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); button.setOnClickListener(action); LayoutParams p = new LayoutParams(-1, dp(33)); p.setMargins(dp(2), dp(2), dp(2), dp(2)); rail.addView(button, p);
        button.setOnKeyListener((v, code, event) -> { if (event.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusFirst(); return true; } return false; }); return button;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(int size) { TextView text = new TextView(activity); text.setTextSize(size); text.setTextColor(0xFFF0F4FA); text.setTypeface(LiquidTheme.font(activity)); return text; }
    private Button button(String label) {
        Button button = new Button(activity); button.setId(View.generateViewId()); button.setText(label); button.setAllCaps(false); button.setTextSize(12); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(10), 0, dp(10), 0); button.setTypeface(LiquidTheme.font(activity)); button.setMaxLines(2); button.setEllipsize(TextUtils.TruncateAt.END); button.setBackground(states());
        button.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}}, new int[]{0xFF082432, 0xFF082432, 0xFFF0F4FA})); return button;
    }
    private StateListDrawable states() { StateListDrawable state = new StateListDrawable(); state.addState(new int[]{android.R.attr.state_focused}, shape(0xFF80E9F4, 0xFFDCFCFF)); state.addState(new int[]{android.R.attr.state_selected}, shape(0xFF80E9F4, 0x997BE7F4)); state.addState(new int[]{}, shape(0x9A0C1C2C, 0x334D6C86)); return state; }
    private GradientDrawable shape(int color, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); d.setStroke(dp(1), stroke); return d; }
    private int dp(int value) { return LiquidTheme.dp(activity, value); }
    private final class CategoryRows extends RecyclerView.Adapter<CategoryRow> {
        @Override public int getItemCount() { return categories.size(); }
        @Override public CategoryRow onCreateViewHolder(ViewGroup parent, int type) { Button view = button(""); view.setTextSize(12); view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(-1, dp(37)); p.setMargins(dp(2), dp(2), dp(2), dp(2)); view.setLayoutParams(p); return new CategoryRow(view); }
        @Override public void onBindViewHolder(CategoryRow row, int position) { OnDemandRepository.Category group = categories.get(position); Button button = (Button) row.itemView; button.setText(group.name); button.setSelected(!overview && group.id.equals(selectedCategory)); button.setOnClickListener(v -> chooseCategory(group)); }
    }
    private static final class CategoryRow extends RecyclerView.ViewHolder { CategoryRow(View view) { super(view); } }
    private final class ShelfRows extends RecyclerView.Adapter<ShelfRow> {
        @Override public int getItemCount() { return shelves.size(); }
        @Override public ShelfRow onCreateViewHolder(ViewGroup parent, int type) { return new ShelfRow(); }
        @Override public void onBindViewHolder(ShelfRow row, int position) { OnDemandRepository.Shelf shelf = shelves.get(position); row.heading.setText(shelf.category.name); row.seeAll.setOnClickListener(v -> chooseCategory(shelf.category)); row.cards.setAdapter(new PosterCards(shelf.titles.entries, true)); }
    }
    private final class ShelfRow extends RecyclerView.ViewHolder {
        final TextView heading; final Button seeAll; final RecyclerView cards;
        ShelfRow() { super(new LinearLayout(activity)); LinearLayout root = (LinearLayout) itemView; root.setOrientation(VERTICAL); root.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(137))); LinearLayout header = row(); heading = text(16); heading.setTypeface(LiquidTheme.font(activity), Typeface.BOLD); header.addView(heading, new LayoutParams(0, -1, 1)); seeAll = button(activity.getString(R.string.vod_see_all)); header.addView(seeAll, new LayoutParams(dp(82), dp(26))); root.addView(header, new LayoutParams(-1, dp(29))); cards = new RecyclerView(activity); cards.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)); cards.setItemAnimator(null); cards.setItemViewCacheSize(2); cards.setNestedScrollingEnabled(false); root.addView(cards, new LayoutParams(-1, dp(104))); }
    }
    private final class PosterCards extends RecyclerView.Adapter<PosterCard> {
        final List<OnDemandRepository.Entry> data; final boolean horizontal;
        PosterCards(List<OnDemandRepository.Entry> data, boolean horizontal) { this.data = data; this.horizontal = horizontal; }
        @Override public int getItemCount() { return data.size(); }
        @Override public PosterCard onCreateViewHolder(ViewGroup parent, int type) { return new PosterCard(horizontal); }
        @Override public void onBindViewHolder(PosterCard card, int position) {
            OnDemandRepository.Entry item = data.get(position); card.title.setText(item.name); card.itemView.setContentDescription(item.name); card.itemView.setOnClickListener(v -> open(item)); card.itemView.setOnLongClickListener(v -> { moreInfo(item); return true; }); posters.load(item.artwork, card.image, card.fallback);
            card.itemView.setNextFocusUpId(View.NO_ID); card.itemView.setNextFocusDownId(!horizontal && position >= Math.max(0, data.size() - 5) ? (next.isEnabled() ? next.getId() : navigation.getId()) : View.NO_ID);
        }
        @Override public void onViewRecycled(PosterCard card) { card.image.setTag(null); card.image.setImageDrawable(null); }
    }
    private final class PosterCard extends RecyclerView.ViewHolder {
        final ImageView image; final TextView title, fallback;
        PosterCard(boolean horizontal) { super(new FrameLayout(activity)); FrameLayout root = (FrameLayout) itemView; root.setFocusable(true); root.setClickable(true); root.setBackground(states()); root.setPadding(dp(2), dp(2), dp(2), dp(2)); RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(horizontal ? dp(124) : -1, dp(101)); p.setMargins(dp(3), dp(2), dp(3), dp(2)); root.setLayoutParams(p); fallback = text(22); fallback.setText("▶"); fallback.setGravity(Gravity.CENTER); root.addView(fallback, new FrameLayout.LayoutParams(-1, -1)); image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP); root.addView(image, new FrameLayout.LayoutParams(-1, -1)); View shade = new View(activity); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00071423, 0xEE071423})); root.addView(shade, new FrameLayout.LayoutParams(-1, dp(45), Gravity.BOTTOM)); title = text(12); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(dp(6), 0, dp(5), dp(5)); root.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)); }
    }
}
