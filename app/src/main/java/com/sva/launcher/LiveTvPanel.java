package com.sva.launcher;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Playlist sidebar, selected-programme hero, and a recycled three-hour EPG grid. */
final class LiveTvPanel extends LinearLayout {
    static final String RECENTS = "recentLiveChannels";
    private static final int CHANNEL_WIDTH = 166;
    private final Activity activity;
    private final ChannelLogoLoader logos;
    private final Consumer<IptvRepository.Channel> watch;
    private final Consumer<IptvRepository.Channel> favorite;
    private final View navigation;
    private final SharedPreferences preferences;
    private final RecyclerView sidebar;
    private final TimelineList grid;
    private final CategoryRows categoryAdapter = new CategoryRows();
    private final ChannelRows channelAdapter = new ChannelRows();
    private final List<IptvRepository.Channel> all = new ArrayList<>(), visible = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private Set<String> hidden = new HashSet<>(), favorites = new HashSet<>();
    private List<String> recent = new ArrayList<>();
    private final TextView categoryTitle, clock, channelName, badge, programmeTitle, timing, detail, upNext, nextTiming, count;
    private final ImageView channelLogo, nextLogo;
    private final TextView channelFallback, nextFallback;
    private final Ruler ruler;
    private final Button dateButton, now;
    private String selectedCategory = LiveTvCatalog.ALL;
    private IptvRepository.Channel selected;
    private IptvRepository.Programme selectedProgramme;
    private long window = GuideTimeline.floor(System.currentTimeMillis()), anchor = System.currentTimeMillis();
    private int focusGeneration;

    LiveTvPanel(Activity activity, ChannelLogoLoader logos, View navigation,
                Consumer<IptvRepository.Channel> watch, Consumer<IptvRepository.Channel> favorite, Runnable fullGuide) {
        super(activity); this.activity = activity; this.logos = logos; this.navigation = navigation;
        this.watch = watch; this.favorite = favorite; preferences = activity.getSharedPreferences("launcher", 0);
        try { JSONArray values = new JSONArray(preferences.getString(RECENTS, "[]"));
            for (int i = 0; i < values.length() && i < 30; i++) recent.add(values.getString(i));
        } catch (Exception ignored) { }
        setOrientation(VERTICAL); setPadding(dp(12), dp(10), dp(12), dp(8));
        LinearLayout header = row();
        TextView heading = text(activity.getString(R.string.ui_live_tv), 25); heading.setTypeface(LiquidTheme.font(activity), android.graphics.Typeface.BOLD);
        header.addView(heading, new LayoutParams(-2, -1));
        categoryTitle = text("", 18); categoryTitle.setSingleLine(true); categoryTitle.setEllipsize(TextUtils.TruncateAt.END); categoryTitle.setPadding(dp(16), 0, dp(12), 0);
        header.addView(categoryTitle, new LayoutParams(0, -1, 1));
        now = button(activity.getString(R.string.ui_now_e3b8204)); now.setOnClickListener(v -> jumpNow()); header.addView(now, new LayoutParams(dp(52), dp(34)));
        Button guide = button(activity.getString(R.string.live_full_guide)); guide.setOnClickListener(v -> fullGuide.run());
        header.addView(guide, new LayoutParams(dp(85), dp(34)));
        clock = text("", 13); clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(clock, new LayoutParams(dp(124), -1)); addView(header, new LayoutParams(-1, dp(44)));
        LinearLayout body = row(); body.setGravity(Gravity.TOP); addView(body, new LayoutParams(-1, 0, 1));
        sidebar = new RecyclerView(activity); sidebar.setId(View.generateViewId()); sidebar.setLayoutManager(new LinearLayoutManager(activity));
        sidebar.setAdapter(categoryAdapter); sidebar.setItemAnimator(null); sidebar.setClipToPadding(false);
        body.addView(sidebar, new LayoutParams(dp(158), -1));
        LinearLayout main = new LinearLayout(activity); main.setOrientation(VERTICAL); main.setPadding(dp(10), 0, 0, 0);
        body.addView(main, new LayoutParams(0, -1, 1));
        LinearLayout hero = row(); hero.setPadding(dp(14), dp(8), dp(14), dp(8));
        hero.setBackground(shape(0xB30B182A, 0x66546D87, 16));
        LinearLayout current = new LinearLayout(activity); current.setOrientation(VERTICAL); hero.addView(current, new LayoutParams(0, -1, 1));
        LinearLayout station = row(); FrameLayout logo = new FrameLayout(activity);
        channelFallback = text("TV", 16); channelFallback.setGravity(Gravity.CENTER);
        channelLogo = new ImageView(activity); channelLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.addView(channelFallback, new FrameLayout.LayoutParams(-1, -1)); logo.addView(channelLogo, new FrameLayout.LayoutParams(-1, -1));
        station.addView(logo, new LayoutParams(dp(36), dp(34)));
        channelName = text("", 14); channelName.setSingleLine(true); channelName.setEllipsize(TextUtils.TruncateAt.END); channelName.setPadding(dp(8), 0, 0, 0);
        station.addView(channelName, new LayoutParams(0, -1, 1)); current.addView(station, new LayoutParams(-1, dp(36)));
        badge = text(activity.getString(R.string.live_on_now), 10); badge.setTextColor(0xFF9EAEFF); current.addView(badge, new LayoutParams(-1, dp(17)));
        programmeTitle = text(activity.getString(R.string.live_select_channel), 23); programmeTitle.setTypeface(LiquidTheme.font(activity), android.graphics.Typeface.BOLD); programmeTitle.setMaxLines(2); programmeTitle.setEllipsize(TextUtils.TruncateAt.END);
        current.addView(programmeTitle, new LayoutParams(-1, 0, 1));
        timing = text("", 12); current.addView(timing, new LayoutParams(-1, dp(22)));
        detail = text("", 11); detail.setSingleLine(true); detail.setEllipsize(TextUtils.TruncateAt.END); current.addView(detail, new LayoutParams(-1, dp(19)));
        LinearLayout upcoming = new LinearLayout(activity); upcoming.setOrientation(VERTICAL); upcoming.setPadding(dp(12), dp(4), dp(4), dp(4));
        upcoming.addView(text(activity.getString(R.string.live_up_next), 14), new LayoutParams(-1, dp(25)));
        LinearLayout nextRow = row(); FrameLayout art = new FrameLayout(activity);
        nextFallback = text("TV", 20); nextFallback.setGravity(Gravity.CENTER); nextLogo = new ImageView(activity); nextLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.addView(nextFallback, new FrameLayout.LayoutParams(-1, -1)); art.addView(nextLogo, new FrameLayout.LayoutParams(-1, -1)); nextRow.addView(art, new LayoutParams(dp(56), dp(64)));
        LinearLayout nextCopy = new LinearLayout(activity); nextCopy.setOrientation(VERTICAL); nextCopy.setPadding(dp(10), 0, 0, 0);
        upNext = text("", 15); upNext.setMaxLines(2); upNext.setEllipsize(TextUtils.TruncateAt.END); nextCopy.addView(upNext);
        nextTiming = text("", 11); nextCopy.addView(nextTiming); nextRow.addView(nextCopy, new LayoutParams(0, -2, 1)); upcoming.addView(nextRow, new LayoutParams(-1, 0, 1));
        hero.addView(upcoming, new LayoutParams(dp(220), -1)); main.addView(hero, new LayoutParams(-1, dp(156)));
        LinearLayout timelineHeader = row();
        dateButton = button(""); dateButton.setOnClickListener(v -> chooseDate()); timelineHeader.addView(dateButton, new LayoutParams(dp(CHANNEL_WIDTH), dp(33)));
        ruler = new Ruler(); timelineHeader.addView(ruler, new LayoutParams(0, dp(33), 1)); main.addView(timelineHeader, new LayoutParams(-1, dp(36)));
        grid = new TimelineList(); grid.setId(View.generateViewId()); grid.setLayoutManager(new LinearLayoutManager(activity));
        grid.setAdapter(channelAdapter); grid.setItemAnimator(null); grid.setClipToPadding(false); grid.setItemViewCacheSize(4);
        main.addView(grid, new LayoutParams(-1, 0, 1));
        count = text("", 10); main.addView(count, new LayoutParams(-1, dp(18))); tick();
    }

    View entryFocus() { return now; }
    String guideCategory() {
        return LiveTvCatalog.FAVORITES.equals(selectedCategory) ? FavoriteChannels.CATEGORY_FAVORITES
                : LiveTvCatalog.ALL.equals(selectedCategory) || LiveTvCatalog.RECENT.equals(selectedCategory) ? null : selectedCategory;
    }

    void setChannels(List<IptvRepository.Channel> channels, Set<String> hidden, Set<String> favorites) {
        all.clear(); all.addAll(channels); this.hidden = new HashSet<>(hidden); this.favorites = new HashSet<>(favorites);
        categories.clear(); Collections.addAll(categories, LiveTvCatalog.ALL, LiveTvCatalog.FAVORITES, LiveTvCatalog.RECENT);
        categories.addAll(LiveTvCatalog.groups(all, hidden).keySet());
        if (!categories.contains(selectedCategory)) selectedCategory = LiveTvCatalog.ALL;
        categoryAdapter.notifyDataSetChanged(); selectCategory(selectedCategory, false);
    }

    void tick() {
        clock.setText(time(System.currentTimeMillis()) + "\n" + format(System.currentTimeMillis(), "EEE, d MMM"));
        dateButton.setText(format(window, "EEE, d MMM") + "  ▾");
        if (selectedProgramme != null && selectedProgramme.endMs <= System.currentTimeMillis()) selectedProgramme = GuideTimeline.current(selected, System.currentTimeMillis());
        if (selected != null) hero(selected, selectedProgramme);
        ruler.invalidate(); grid.invalidate();
    }

    private String categoryName(String key) {
        if (LiveTvCatalog.ALL.equals(key)) return activity.getString(R.string.live_all);
        if (LiveTvCatalog.FAVORITES.equals(key)) return activity.getString(R.string.live_favorites);
        if (LiveTvCatalog.RECENT.equals(key)) return activity.getString(R.string.live_recent);
        return key.isEmpty() ? activity.getString(R.string.ui_uncategorized) : key;
    }

    private void selectCategory(String category, boolean moveFocus) {
        focusGeneration++;
        selectedCategory = category; categoryTitle.setText("│  " + categoryName(category));
        String previousKey = selected == null ? "" : selected.stableKey();
        visible.clear(); visible.addAll(LiveTvCatalog.select(all, category, hidden, favorites, recent));
        selected = null;
        for (IptvRepository.Channel channel : visible) if (channel.stableKey().equals(previousKey)) { selected = channel; break; }
        if (selected == null) {
            for (IptvRepository.Channel channel : visible) {
                if (GuideTimeline.current(channel, System.currentTimeMillis()) != null) { selected = channel; break; }
            }
        }
        if (selected == null && !visible.isEmpty()) selected = visible.get(0);
        selectedProgramme = selected == null ? null : GuideTimeline.current(selected, System.currentTimeMillis());
        int selectedIndex = Math.max(0, visible.indexOf(selected));
        channelAdapter.notifyDataSetChanged(); grid.scrollToPosition(selectedIndex);
        for (int i = 0; i < sidebar.getChildCount(); i++) {
            View child = sidebar.getChildAt(i); int position = sidebar.getChildAdapterPosition(child);
            if (position >= 0 && position < categories.size()) child.setSelected(categories.get(position).equals(category));
        }
        count.setText(visible.isEmpty() ? activity.getString(R.string.live_empty)
                : activity.getString(R.string.live_channels_hint, visible.size()));
        if (selected != null) hero(selected, selectedProgramme); else clearHero();
        if (moveFocus && !visible.isEmpty()) focusRow(selectedIndex, false);
    }

    private void clearHero() {
        channelName.setText(""); programmeTitle.setText(R.string.live_select_channel); timing.setText(""); detail.setText("");
        upNext.setText(R.string.live_no_schedule); nextTiming.setText("");
        channelLogo.setImageDrawable(null); nextLogo.setImageDrawable(null);
    }

    private void hero(IptvRepository.Channel channel, IptvRepository.Programme programme) {
        selected = channel; selectedProgramme = programme;
        long now = System.currentTimeMillis();
        if (programme == null) programme = GuideTimeline.current(channel, now);
        channelName.setText(channel.name); logos.load(channel.logoUrl, channelLogo, channelFallback);
        boolean current = programme != null && programme.startMs <= now && programme.endMs > now;
        badge.setText(current ? R.string.live_on_now : programme == null ? R.string.live_channel : R.string.live_coming_up);
        programmeTitle.setText(programme == null ? channel.name : programme.title);
        timing.setText(programme == null ? activity.getString(R.string.live_no_schedule)
                : time(programme.startMs) + " – " + time(programme.endMs) + (current
                ? "  │  " + activity.getString(R.string.live_minutes_left, Math.max(1, (programme.endMs - now + 59999) / 60000)) : ""));
        detail.setText(categoryName(channel.group.trim()) + "  ·  " + activity.getString(R.string.live_ok_watch));
        IptvRepository.Programme next = GuideTimeline.next(channel, programme == null ? now : programme.endMs - 1);
        upNext.setText(next == null ? activity.getString(R.string.live_no_schedule) : next.title);
        nextTiming.setText(next == null ? "" : time(next.startMs) + " – " + time(next.endMs));
        logos.load(channel.logoUrl, nextLogo, nextFallback);
    }

    private void play(IptvRepository.Channel channel) {
        recent = LiveTvCatalog.remember(recent, channel.stableKey());
        preferences.edit().putString(RECENTS, new JSONArray(recent).toString()).apply(); watch.accept(channel);
    }

    private void jumpNow() { window = GuideTimeline.floor(System.currentTimeMillis()); anchor = System.currentTimeMillis(); refreshWindow(); }
    private void chooseDate() {
        Calendar selected = Calendar.getInstance(); selected.setTimeInMillis(window);
        new DatePickerDialog(activity, (picker, year, month, day) -> {
            Calendar date = Calendar.getInstance(); date.set(year, month, day); window = GuideTimeline.floor(date.getTimeInMillis()); anchor = window; refreshWindow();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void refreshWindow() { channelAdapter.notifyDataSetChanged(); tick(); }

    private void focusRow(int row, boolean channelLabel) {
        if (row < 0) { now.requestFocus(); return; }
        if (row >= visible.size()) { navigation.requestFocus(); return; }
        grid.scrollToPosition(row);
        int request = ++focusGeneration;
        grid.postOnAnimation(() -> focusAttachedRow(row, channelLabel, request, 0));
    }

    private void focusAttachedRow(int row, boolean channelLabel, int request, int attempt) {
        if (request != focusGeneration || !isShown() || row >= visible.size()) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(row);
        if (!(holder instanceof ChannelRow) || ((ChannelRow) holder).boundChannel != visible.get(row)) {
            if (attempt < 4) grid.postOnAnimation(() -> focusAttachedRow(row, channelLabel, request, attempt + 1));
            return;
        }
        ChannelRow target = (ChannelRow) holder;
        if (channelLabel) target.channel.requestFocus();
        else {
            List<GuideTimeline.Slot> slots = GuideTimeline.slots(visible.get(row), window, window + GuideTimeline.WINDOW);
            int index = GuideTimeline.at(slots, anchor);
            if (index < target.cells.getChildCount()) target.cells.getChildAt(index).requestFocus();
        }
    }

    private final class CategoryRows extends RecyclerView.Adapter<CategoryRow> {
        @Override public int getItemCount() { return categories.size(); }
        @Override public CategoryRow onCreateViewHolder(ViewGroup parent, int type) {
            Button view = button(""); view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); view.setTextSize(13); view.setMaxLines(2);
            StateListDrawable selected = new StateListDrawable();
            selected.addState(new int[]{android.R.attr.state_focused}, shape(0xFF86E9F4, 0xFFDBFCFF, 9));
            selected.addState(new int[]{android.R.attr.state_selected}, shape(0xFF86E9F4, 0x997BE7F4, 9));
            selected.addState(new int[]{}, shape(0x660B192B, 0x22485A70, 9)); view.setBackground(selected);
            view.setTextColor(new android.content.res.ColorStateList(new int[][]{
                    {android.R.attr.state_focused}, {android.R.attr.state_selected}, {}}, new int[]{0xFF092735, 0xFF092735, 0xFFEAF1FB}));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, dp(43)); params.setMargins(dp(2), dp(2), dp(2), dp(2)); view.setLayoutParams(params);
            return new CategoryRow(view);
        }
        @Override public void onBindViewHolder(CategoryRow holder, int position) {
            String key = categories.get(position); Button button = (Button) holder.itemView;
            String icon = LiveTvCatalog.ALL.equals(key) ? "▣  " : LiveTvCatalog.FAVORITES.equals(key) ? "♡  " : LiveTvCatalog.RECENT.equals(key) ? "◷  " : "";
            button.setText(icon + categoryName(key)); button.setSelected(key.equals(selectedCategory));
            button.setOnClickListener(v -> selectCategory(key, true));
            button.setOnFocusChangeListener((v, focus) -> {
                if (focus && !key.equals(selectedCategory)) sidebar.post(() -> { if (v.hasFocus() && !key.equals(selectedCategory)) selectCategory(key, false); });
            });
            button.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusRow(0, false); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && position == categories.size() - 1) { navigation.requestFocus(); return true; }
                return false;
            });
        }
    }
    private static final class CategoryRow extends RecyclerView.ViewHolder { CategoryRow(View view) { super(view); } }

    private final class ChannelRows extends RecyclerView.Adapter<ChannelRow> {
        @Override public int getItemCount() { return visible.size(); }
        @Override public ChannelRow onCreateViewHolder(ViewGroup parent, int type) { return new ChannelRow(); }
        @Override public void onBindViewHolder(ChannelRow row, int position) { row.bind(position); }
        @Override public void onViewRecycled(ChannelRow row) { row.logo.setTag(null); row.logo.setImageDrawable(null); }
    }

    private final class ChannelRow extends RecyclerView.ViewHolder {
        final Button channel;
        final ImageView logo;
        final TextView fallback;
        final Cells cells;
        IptvRepository.Channel boundChannel;
        ChannelRow() {
            super(new LinearLayout(activity)); LinearLayout row = (LinearLayout) itemView; row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(46)));
            FrameLayout label = new FrameLayout(activity); label.setPadding(0, dp(1), dp(2), dp(1));
            channel = button(""); channel.setTextSize(11); channel.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); channel.setPadding(dp(46), 0, dp(4), 0); channel.setMaxLines(2);
            label.addView(channel, new FrameLayout.LayoutParams(-1, -1));
            logo = new ImageView(activity); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER_VERTICAL | Gravity.START); imageParams.leftMargin = dp(5);
            fallback = text("", 10); fallback.setGravity(Gravity.CENTER); label.addView(fallback, imageParams); label.addView(logo, imageParams);
            row.addView(label, new LayoutParams(dp(CHANNEL_WIDTH), -1)); cells = new Cells(); row.addView(cells, new LayoutParams(0, -1, 1));
        }
        void bind(int rowIndex) {
            IptvRepository.Channel station = visible.get(rowIndex); boundChannel = station; channel.setText(station.name); fallback.setText("" + (rowIndex + 1));
            logos.load(station.logoUrl, logo, fallback);
            channel.setOnFocusChangeListener((v, focus) -> { if (focus) hero(station, GuideTimeline.current(station, System.currentTimeMillis())); });
            channel.setOnClickListener(v -> play(station)); channel.setOnLongClickListener(v -> { favorite.accept(station); return true; });
            channel.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusRow(rowIndex, false); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { sidebar.requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) { focusRow(rowIndex + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1), true); return true; }
                return false;
            });
            cells.removeAllViews(); List<GuideTimeline.Slot> slots = GuideTimeline.slots(station, window, window + GuideTimeline.WINDOW);
            for (int index = 0; index < slots.size(); index++) {
                final int slotIndex = index; GuideTimeline.Slot slot = slots.get(index);
                Button tile = button(slot.programme == null ? activity.getString(R.string.live_no_schedule)
                        : slot.programme.title + "\n" + time(slot.programme.startMs) + " – " + time(slot.programme.endMs));
                tile.setTextSize(11); tile.setMaxLines(2); tile.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); tile.setPadding(dp(8), 0, dp(4), 0); tile.setTag(slot);
                tile.setOnFocusChangeListener((v, focus) -> { if (focus) { anchor = slot.start + 1; hero(station, slot.programme); } });
                tile.setOnClickListener(v -> play(station)); tile.setOnLongClickListener(v -> { favorite.accept(station); return true; });
                tile.setOnKeyListener((v, code, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) { focusRow(rowIndex + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1), false); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        int direction = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, target = slotIndex + direction;
                        if (target >= 0 && target < cells.getChildCount()) cells.getChildAt(target).requestFocus();
                        else if (direction < 0) channel.requestFocus();
                        else { window += GuideTimeline.HALF_HOUR; anchor = slot.end + 1; refreshWindow(); focusRow(rowIndex, false); }
                        return true;
                    }
                    return false;
                });
                cells.addView(tile, new FrameLayout.LayoutParams(-2, -1));
            }
        }
    }

    private final class Cells extends FrameLayout {
        Cells() { super(activity); setClipChildren(true); }
        @Override protected void onMeasure(int width, int height) {
            int w = MeasureSpec.getSize(width), h = MeasureSpec.getSize(height);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); GuideTimeline.Slot slot = (GuideTimeline.Slot) child.getTag();
                int x = Math.round(GuideTimeline.fraction(slot.start, window) * w), end = Math.round(GuideTimeline.fraction(slot.end, window) * w);
                child.measure(MeasureSpec.makeMeasureSpec(Math.max(1, end - x - dp(2)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(1, h - dp(2)), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); GuideTimeline.Slot slot = (GuideTimeline.Slot) child.getTag();
                int x = Math.round(GuideTimeline.fraction(slot.start, window) * getWidth()); child.layout(x, dp(1), x + child.getMeasuredWidth(), dp(1) + child.getMeasuredHeight());
            }
        }
    }

    private final class TimelineList extends RecyclerView {
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        TimelineList() { super(activity); line.setColor(0xFF798BFF); line.setStrokeWidth(dp(1)); }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas); float fraction = GuideTimeline.fraction(System.currentTimeMillis(), window);
            if (fraction >= 0 && fraction <= 1) { float x = dp(CHANNEL_WIDTH) + fraction * (getWidth() - dp(CHANNEL_WIDTH)); canvas.drawLine(x, 0, x, getHeight(), line); }
        }
    }
    private final class Ruler extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ruler() { super(activity); paint.setTextSize(dp(10)); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(0xFFDCE8F7);
            for (int i = 0; i < 6; i++) { float x = i * getWidth() / 6f; canvas.drawText(time(window + i * GuideTimeline.HALF_HOUR), x + dp(4), dp(20), paint); }
            float f = GuideTimeline.fraction(System.currentTimeMillis(), window);
            if (f >= 0 && f <= 1) { paint.setColor(0xFF9CA8FF); canvas.drawCircle(f * getWidth(), dp(28), dp(3), paint); }
        }
    }

    private LinearLayout row() { LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(String value, int size) { TextView view = new TextView(activity); view.setText(value); view.setTextColor(0xFFEAF1FB); view.setTextSize(size); view.setTypeface(LiquidTheme.font(activity)); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private Button button(String value) {
        Button view = new Button(activity); view.setId(View.generateViewId()); view.setText(value); view.setAllCaps(false); view.setTextSize(12); view.setTypeface(LiquidTheme.font(activity));
        view.setMinWidth(0); view.setMinimumWidth(0); view.setPadding(dp(8), 0, dp(8), 0); view.setTextColor(0xFFEAF1FB); view.setEllipsize(TextUtils.TruncateAt.END);
        StateListDrawable states = new StateListDrawable(); states.addState(new int[]{android.R.attr.state_focused}, shape(0xFF244D86, 0xFF7BE7F4, 8));
        states.addState(new int[]{android.R.attr.state_selected}, shape(0xDD285477, 0x997BE7F4, 8)); states.addState(new int[]{}, shape(0xC5122335, 0x55485A70, 8)); view.setBackground(states); return view;
    }
    private GradientDrawable shape(int color, int stroke, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), stroke); return shape; }
    private String format(long value, String pattern) { return new SimpleDateFormat(pattern, AppLanguage.locale()).format(new Date(value)); }
    private String time(long value) { return format(value, preferences.getBoolean("24h", false) ? "HH:mm" : "h:mm a"); }
    private int dp(int value) { return LiquidTheme.dp(activity, value); }
}
