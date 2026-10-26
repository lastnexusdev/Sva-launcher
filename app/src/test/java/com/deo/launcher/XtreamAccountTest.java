package com.sva.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public final class XtreamAccountTest {
    @Test public void derivesApiFromPlaylistWithoutSendingLocalFilterFragment() {
        XtreamAccount account = XtreamAccount.fromPlaylists("https://tv.test:8443/panel/get.php?username=demo&password=example#sva-live-only");
        assertNotNull(account);
        assertEquals("https://tv.test:8443/panel/player_api.php?username=demo&password=example&action=get_series&category_id=12",
                account.api("get_series", "12"));
        assertEquals("https://tv.test:8443/panel/movie/demo/example/42.mkv", account.stream(false, "42", "mkv"));
        assertEquals("https://tv.test:8443/panel/series/demo/example/43.mp4", account.stream(true, "43", "mp4"));
    }

    @Test public void encodedCredentialsRemainSeparatePathAndQueryComponents() {
        XtreamAccount account = XtreamAccount.fromPlaylists("https://tv.test/get.php?username=demo%2Bname&password=a%2Fb%26c");
        assertEquals("https://tv.test/movie/demo%2Bname/a%2Fb%26c/1.mp4", account.stream(false, "1", "mp4"));
        assertTrue(account.api("get_vod_streams", "a&b").endsWith("category_id=a%26b"));
    }

    @Test public void ignoresOtherPlaylistFormatsAndRejectsMissingAccountFields() {
        assertNull(XtreamAccount.fromPlaylists("https://tv.test/list.m3u"));
        assertNull(XtreamAccount.fromPlaylists("https://tv.test/get.php?username=demo"));
        assertNull(XtreamAccount.fromPlaylists("file:///get.php?username=demo&password=example"));
        assertNotNull(XtreamAccount.fromPlaylists("https://other.test/list.m3u\nhttps://tv.test/get.php?username=demo&password=example"));
    }

    @Test(expected = IllegalArgumentException.class) public void mediaIdentifierCannotInjectAPath() {
        XtreamAccount.fromPlaylists("https://tv.test/get.php?username=demo&password=example").stream(false, "../42", "mp4");
    }

    @Test public void cataloguePaginationRetainsOnlyRequestedPageAndFiltersNames() {
        OnDemandRepository.Page page = new OnDemandRepository.Page();
        for (int i = 0; i < 300; i++) page.add(new OnDemandRepository.Entry("" + i, "Movie " + i, "", "mp4", "", false, false), "", 1);
        assertEquals(300, page.total); assertEquals(120, page.entries.size());
        assertEquals("120", page.entries.get(0).id); assertEquals("239", page.entries.get(119).id);
        OnDemandRepository.Page filtered = new OnDemandRepository.Page();
        filtered.add(new OnDemandRepository.Entry("1", "News Movie", "", "mp4", "", false, false), "news", 0);
        filtered.add(new OnDemandRepository.Entry("2", "Other", "", "mp4", "", false, false), "news", 0);
        filtered.add(new OnDemandRepository.Entry("../3", "News Invalid", "", "mp4", "", false, false), "news", 0);
        assertEquals(1, filtered.total);
    }
}
