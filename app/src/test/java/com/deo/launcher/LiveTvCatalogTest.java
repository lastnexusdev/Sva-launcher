package com.sva.launcher;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class LiveTvCatalogTest {
    @Test public void categoriesUsePlaylistNamesAndRespectHiddenChannels() {
        IptvRepository.Channel news = channel("one", "Provider News");
        IptvRepository.Channel sports = channel("two", "Sports / English");
        IptvRepository.Channel hidden = channel("three", "Hidden category");
        Map<String, List<IptvRepository.Channel>> groups = LiveTvCatalog.groups(Arrays.asList(news, sports, hidden), Collections.singleton(hidden.stableKey()));
        assertEquals(Arrays.asList("Provider News", "Sports / English"), new ArrayList<>(groups.keySet()));
        assertSame(news, groups.get("Provider News").get(0));
    }

    @Test public void allFavoritesRecentAndProviderCategoriesSelectActualChannels() {
        IptvRepository.Channel one = channel("one", "News"), two = channel("two", "Sports"), hidden = channel("hidden", "Sports");
        List<IptvRepository.Channel> all = Arrays.asList(one, two, hidden);
        Set<String> hide = Collections.singleton(hidden.stableKey());
        Set<String> favorites = new HashSet<>(Arrays.asList(two.stableKey(), hidden.stableKey()));
        List<String> recent = Arrays.asList(two.stableKey(), one.stableKey());
        assertEquals(Arrays.asList(one, two), LiveTvCatalog.select(all, LiveTvCatalog.ALL, hide, favorites, recent));
        assertEquals(Collections.singletonList(two), LiveTvCatalog.select(all, LiveTvCatalog.FAVORITES, hide, favorites, recent));
        assertEquals(Arrays.asList(two, one), LiveTvCatalog.select(all, LiveTvCatalog.RECENT, hide, favorites, recent));
        assertEquals(Collections.singletonList(one), LiveTvCatalog.select(all, "News", hide, favorites, recent));
    }

    @Test public void recentChannelsMoveToFrontWithoutDuplicatesAndStayBounded() {
        List<String> previous = new ArrayList<>(); for (int i = 0; i < 40; i++) previous.add("id:" + i);
        List<String> recent = LiveTvCatalog.remember(previous, "id:4");
        assertEquals(30, recent.size()); assertEquals("id:4", recent.get(0));
        assertEquals(30, new HashSet<>(recent).size()); assertEquals("id:0", recent.get(1));
    }

    private static IptvRepository.Channel channel(String id, String group) {
        return new IptvRepository.Channel(id, "https://tv.test/live/" + id, id, group, "", "", "", Collections.emptyMap());
    }
}
