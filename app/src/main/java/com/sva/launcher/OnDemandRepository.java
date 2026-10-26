package com.sva.launcher;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;

/** Stream provider JSON and retain only the requested result page. */
final class OnDemandRepository {
    static final int PAGE_SIZE = 120;
    static final class Category {
        final String id, name;
        Category(String id, String name) { this.id = id; this.name = name; }
    }
    static final class Entry {
        final String id, name, artwork, extension, season, categoryId;
        final boolean series, episode;
        final long added;
        Entry(String id, String name, String artwork, String extension, String season, boolean series, boolean episode) {
            this(id, name, artwork, extension, season, series, episode, "", 0L);
        }
        Entry(String id, String name, String artwork, String extension, String season, boolean series, boolean episode,
              String categoryId, long added) {
            this.id = id; this.name = name; this.artwork = artwork; this.extension = extension;
            this.season = season; this.series = series; this.episode = episode; this.categoryId = categoryId; this.added = added;
        }
    }

    static final Comparator<Entry> NEWEST = (a, b) -> {
        int time = Long.compare(b.added, a.added);
        if (time != 0) return time;
        int length = Integer.compare(b.id.length(), a.id.length());
        return length != 0 ? length : b.id.compareTo(a.id);
    };

    /** A cursor and bounded heap keep deep paging independent of catalogue size. */
    static final class NewestPicker {
        final PriorityQueue<Entry> heap = new PriorityQueue<>(NEWEST.reversed());
        final int limit;
        final String search;
        final Entry before;
        int total;
        NewestPicker(int limit, String search, Entry before) {
            if (limit < 1) throw new IllegalArgumentException("A result limit is required");
            this.limit = limit; this.search = search.toLowerCase(Locale.ROOT); this.before = before;
        }
        void add(Entry entry) {
            if (entry.id.isEmpty() || !entry.id.matches("[0-9]+") || entry.name.isEmpty()
                    || !entry.name.toLowerCase(Locale.ROOT).contains(search)) return;
            total++;
            if (before != null && NEWEST.compare(entry, before) <= 0) return;
            if (heap.size() < limit) heap.add(entry);
            else if (NEWEST.compare(entry, heap.peek()) < 0) { heap.poll(); heap.add(entry); }
        }
        Page page() {
            Page page = new Page(); page.total = total; page.entries.addAll(heap); page.entries.sort(NEWEST); return page;
        }
    }

    static final class Shelf {
        final Category category;
        final Page titles;
        Shelf(Category category, Page titles) { this.category = category; this.titles = titles; }
    }
    static final class Home {
        final List<Shelf> shelves = new ArrayList<>();
        Entry featured;
    }
    static final class HomeBuilder {
        final List<Category> categories;
        final Map<String, NewestPicker> newest = new LinkedHashMap<>();
        HomeBuilder(List<Category> categories) {
            this.categories = new ArrayList<>(categories);
            for (Category category : categories) newest.put(category.id, new NewestPicker(12, "", null));
        }
        void add(Entry item) { NewestPicker picker = newest.get(item.categoryId); if (picker != null) picker.add(item); }
        Home finish() {
            Home home = new Home();
            for (Category category : categories) {
                Page page = newest.get(category.id).page();
                if (page.entries.isEmpty()) continue;
                home.shelves.add(new Shelf(category, page));
                Entry first = page.entries.get(0);
                if (home.featured == null || NEWEST.compare(first, home.featured) < 0) home.featured = first;
            }
            return home;
        }
    }
    static final class Details {
        String plot = "", backdrop = "", poster = "", year = "", duration = "", genre = "", rating = "", cast = "", director = "";
        String metadata() {
            List<String> parts = new ArrayList<>();
            if (!year.isEmpty()) parts.add(year);
            if (!duration.isEmpty()) parts.add(durationLabel(duration));
            if (!genre.isEmpty()) parts.add(genre);
            if (!rating.isEmpty() && !rating.equals("0")) parts.add("★ " + rating);
            return String.join("  ·  ", parts);
        }
        private static String durationLabel(String value) {
            if (!value.matches("[0-9]{1,3}:[0-9]{2}:[0-9]{2}")) return value;
            String[] parts = value.split(":"); int hours = Integer.parseInt(parts[0]), minutes = Integer.parseInt(parts[1]);
            return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
        }
    }
    static final class Page {
        final List<Entry> entries = new ArrayList<>();
        int total;
        void add(Entry entry, String search, int page) {
            if (entry.id.isEmpty() || !entry.id.matches("[0-9]+") || entry.name.isEmpty()
                    || !entry.name.toLowerCase(Locale.ROOT).contains(search)) return;
            if (total >= page * PAGE_SIZE && entries.size() < PAGE_SIZE) entries.add(entry);
            total++;
        }
    }

    private final XtreamAccount account;
    OnDemandRepository(XtreamAccount account) { this.account = account; }

    List<Category> categories(boolean series) throws Exception {
        List<Category> result = new ArrayList<>();
        read(account.api(series ? "get_series_categories" : "get_vod_categories", null), reader -> {
            reader.beginArray();
            while (reader.hasNext()) {
                String id = "", name = "";
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.equals("category_id")) id = text(reader);
                    else if (key.equals("category_name")) name = text(reader);
                    else reader.skipValue();
                }
                reader.endObject();
                if (!id.isEmpty() && !name.isEmpty()) result.add(new Category(id, name));
                if (result.size() > 10000) throw new IOException("Too many categories");
            }
            reader.endArray();
        });
        return result;
    }

    Page newest(boolean series, String category, String search, Entry before, int limit) throws Exception {
        NewestPicker picker = new NewestPicker(limit, search, before);
        read(account.api(series ? "get_series" : "get_vod_streams", category), reader -> {
            reader.beginArray();
            while (reader.hasNext()) {
                checkCancelled();
                picker.add(entry(reader, series, false, ""));
            }
            reader.endArray();
        });
        return picker.page();
    }

    Home home(boolean series, List<Category> categories) throws Exception {
        HomeBuilder builder = new HomeBuilder(categories);
        read(account.api(series ? "get_series" : "get_vod_streams", null), reader -> {
            reader.beginArray();
            while (reader.hasNext()) {
                checkCancelled(); Entry item = entry(reader, series, false, "");
                builder.add(item);
            }
            reader.endArray();
        });
        return builder.finish();
    }

    Details details(Entry item) throws Exception {
        Details details = new Details();
        read(item.series ? account.seriesInfo(item.id) : account.vodInfo(item.id), reader -> {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("info") || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.equals("plot") || key.equals("description")) details.plot = text(reader);
                    else if (key.equals("movie_image") || key.equals("cover")) details.poster = text(reader);
                    else if (key.equals("backdrop_path")) { String path = imagePath(reader); if (!path.isEmpty()) details.backdrop = path; }
                    else if (key.equals("backdrop") && details.backdrop.isEmpty()) details.backdrop = imagePath(reader);
                    else if (key.equals("genre")) details.genre = text(reader);
                    else if (key.equals("rating")) details.rating = text(reader);
                    else if (key.equals("cast")) details.cast = text(reader);
                    else if (key.equals("director")) details.director = text(reader);
                    else if (key.equals("duration")) details.duration = text(reader);
                    else if (key.equals("releasedate") || key.equals("releaseDate") || key.equals("year")) {
                        String value = text(reader); if (value.matches("^[0-9]{4}.*")) details.year = value.substring(0, 4);
                    } else reader.skipValue();
                }
                reader.endObject();
            }
            reader.endObject();
        });
        return details;
    }

    private static String imagePath(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return text(reader);
        String first = ""; reader.beginArray();
        while (reader.hasNext()) { String value = text(reader); if (first.isEmpty()) first = value; }
        reader.endArray(); return first;
    }

    Page episodes(String seriesId, String search, int page) throws Exception {
        Page result = new Page();
        read(account.seriesInfo(seriesId), reader -> {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!key.equals("episodes") || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
                reader.beginObject();
                while (reader.hasNext()) {
                    String season = reader.nextName();
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); continue; }
                    reader.beginArray();
                    while (reader.hasNext()) {
                        checkCancelled();
                        result.add(entry(reader, false, true, season), search.toLowerCase(Locale.ROOT), page);
                    }
                    reader.endArray();
                }
                reader.endObject();
            }
            reader.endObject();
        });
        return result;
    }

    private Entry entry(JsonReader reader, boolean series, boolean episode, String season) throws Exception {
        String id = "", name = "", artwork = "", extension = "mp4", categoryId = "";
        long added = 0L;
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.equals(series ? "series_id" : episode ? "id" : "stream_id")) id = text(reader);
            else if (key.equals(episode ? "title" : "name")) name = text(reader);
            else if (key.equals("stream_icon") || key.equals("cover")) artwork = text(reader);
            else if (key.equals("container_extension")) extension = text(reader);
            else if (key.equals("category_id")) categoryId = text(reader);
            else if (key.equals("added") || key.equals("last_modified")) {
                try { added = Math.max(added, Long.parseLong(text(reader))); }
                catch (NumberFormatException ignored) { }
            }
            else reader.skipValue();
        }
        reader.endObject();
        return new Entry(id, name, artwork, extension, season, series, episode, categoryId, added);
    }

    private static String text(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return ""; }
        return reader.nextString();
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    interface ReadAction { void read(JsonReader reader) throws Exception; }
    private void read(String address, ReadAction action) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "SvaLauncher/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                    IptvRepository.playlistStream(connection.getInputStream()), StandardCharsets.UTF_8)))) {
                action.read(reader);
            }
        } finally { connection.disconnect(); }
    }
}
