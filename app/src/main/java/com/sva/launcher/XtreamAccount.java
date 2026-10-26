package com.sva.launcher;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Derive the on-demand account from an already configured IPTV playlist. */
final class XtreamAccount {
    private final String base, username, password;

    private XtreamAccount(String base, String username, String password) {
        this.base = base; this.username = username; this.password = password;
    }

    static XtreamAccount fromPlaylists(String playlists) {
        if (playlists == null) return null;
        for (String line : playlists.split("\\r?\\n")) {
            try {
                URI uri = URI.create(line.trim());
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null) continue;
                String path = uri.getRawPath();
                if (path == null || !(path.endsWith("/get.php") || path.endsWith("/player_api.php"))) continue;
                Map<String, String> query = new LinkedHashMap<>();
                if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) query.put(decode(parts[0]), decode(parts[1]));
                }
                String user = query.get("username"), pass = query.get("password");
                if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) continue;
                return new XtreamAccount(uri.getScheme() + "://" + uri.getRawAuthority()
                        + path.substring(0, path.lastIndexOf('/')), user, pass);
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    String api(String action, String category) {
        return base + "/player_api.php?username=" + encode(username) + "&password=" + encode(password)
                + "&action=" + encode(action) + (category == null || category.isEmpty() ? "" : "&category_id=" + encode(category));
    }

    String seriesInfo(String id) {
        requireId(id);
        return api("get_series_info", null) + "&series_id=" + id;
    }

    String vodInfo(String id) {
        requireId(id);
        return api("get_vod_info", null) + "&vod_id=" + id;
    }

    String stream(boolean episode, String id, String extension) {
        requireId(id);
        String suffix = extension != null && extension.matches("[A-Za-z0-9]{1,8}") ? extension : "mp4";
        return base + (episode ? "/series/" : "/movie/") + encode(username) + "/" + encode(password) + "/" + id + "." + suffix;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid media identifier");
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
