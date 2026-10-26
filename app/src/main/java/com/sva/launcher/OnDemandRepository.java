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

/** Stream provider JSON and retain only the requested result page. */
final class OnDemandRepository {
    static final int PAGE_SIZE = 120;
    static final class Category {
        final String id, name;
        Category(String id, String name) { this.id = id; this.name = name; }
    }
    static final class Entry {
        final String id, name, artwork, extension, season;
        final boolean series, episode;
        Entry(String id, String name, String artwork, String extension, String season, boolean series, boolean episode) {
            this.id = id; this.name = name; this.artwork = artwork; this.extension = extension;
            this.season = season; this.series = series; this.episode = episode;
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

    Page catalogue(boolean series, String category, String search, int page) throws Exception {
        Page result = new Page();
        read(account.api(series ? "get_series" : "get_vod_streams", category), reader -> {
            reader.beginArray();
            while (reader.hasNext()) {
                checkCancelled();
                result.add(entry(reader, series, false, ""), search.toLowerCase(Locale.ROOT), page);
            }
            reader.endArray();
        });
        return result;
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
        String id = "", name = "", artwork = "", extension = "mp4";
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.equals(series ? "series_id" : episode ? "id" : "stream_id")) id = text(reader);
            else if (key.equals(episode ? "title" : "name")) name = text(reader);
            else if (key.equals("stream_icon") || key.equals("cover")) artwork = text(reader);
            else if (key.equals("container_extension")) extension = text(reader);
            else reader.skipValue();
        }
        reader.endObject();
        return new Entry(id, name, artwork, extension, season, series, episode);
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
