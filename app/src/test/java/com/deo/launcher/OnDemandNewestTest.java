package com.sva.launcher;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class OnDemandNewestTest {
    private static OnDemandRepository.Entry entry(String id, String category, long added) {
        return new OnDemandRepository.Entry(id, "Title " + id, "", "mp4", "", false, false, category, added);
    }
    @Test public void newestUsesProviderTimestampInsteadOfResponseOrderOrMediaId() {
        OnDemandRepository.NewestPicker picker = new OnDemandRepository.NewestPicker(2, "", null);
        picker.add(entry("999", "a", 100)); picker.add(entry("1", "a", 300)); picker.add(entry("55", "a", 200));
        assertEquals(3, picker.total); assertEquals("1", picker.page().entries.get(0).id); assertEquals("55", picker.page().entries.get(1).id);
    }
    @Test public void cursorPagingHasNoOverlapAndRetainsOnlyOnePage() {
        List<OnDemandRepository.Entry> source = new ArrayList<>(); for (int i = 0; i < 1000; i++) source.add(entry("" + i, "a", i % 7));
        Collections.shuffle(source, new Random(17)); OnDemandRepository.NewestPicker first = new OnDemandRepository.NewestPicker(12, "", null);
        for (OnDemandRepository.Entry item : source) first.add(item);
        List<OnDemandRepository.Entry> one = first.page().entries;
        OnDemandRepository.NewestPicker second = new OnDemandRepository.NewestPicker(12, "", one.get(11));
        for (OnDemandRepository.Entry item : source) second.add(item);
        assertEquals(12, first.heap.size()); assertEquals(12, second.heap.size()); assertEquals(1000, second.total);
        Set<String> ids = new HashSet<>(); for (OnDemandRepository.Entry item : one) ids.add(item.id);
        for (OnDemandRepository.Entry item : second.page().entries) assertFalse(ids.contains(item.id));
        assertTrue(OnDemandRepository.NEWEST.compare(one.get(11), second.page().entries.get(0)) < 0);
    }
    @Test public void everyProviderCategoryGetsItsOwnBoundedNewestShelf() {
        OnDemandRepository.HomeBuilder builder = new OnDemandRepository.HomeBuilder(Arrays.asList(
                new OnDemandRepository.Category("b", "Provider Category B"), new OnDemandRepository.Category("a", "Actual A")));
        for (int i = 0; i < 5000; i++) { builder.add(entry("" + i, "a", i)); builder.add(entry("" + (i + 5000), "b", i + 5000)); }
        builder.add(entry("99999", "not-a-provider-category", 99999)); OnDemandRepository.Home home = builder.finish();
        assertEquals(2, home.shelves.size()); assertEquals("Provider Category B", home.shelves.get(0).category.name);
        assertEquals(12, home.shelves.get(0).titles.entries.size()); assertEquals("9999", home.featured.id);
        assertEquals("4999", home.shelves.get(1).titles.entries.get(0).id); assertEquals(12, builder.newest.get("a").heap.size());
    }
    @Test public void titleSearchIsAppliedBeforeNewestSelection() {
        OnDemandRepository.NewestPicker picker = new OnDemandRepository.NewestPicker(12, "title 9", null);
        picker.add(entry("8", "a", 100)); picker.add(entry("9", "a", 10)); picker.add(entry("90", "a", 20));
        assertEquals(2, picker.total); assertEquals("90", picker.page().entries.get(0).id);
    }
    @Test public void missingAddedTimesUseStableNumericIdOrder() {
        OnDemandRepository.NewestPicker picker = new OnDemandRepository.NewestPicker(3, "", null);
        picker.add(entry("9", "a", 0)); picker.add(entry("100", "a", 0)); picker.add(entry("20", "a", 0));
        assertEquals("100", picker.page().entries.get(0).id); assertEquals("20", picker.page().entries.get(1).id);
    }
    @Test public void metadataShowsOnlyRealValuesAndFormatsDuration() {
        OnDemandRepository.Details details = new OnDemandRepository.Details(); assertEquals("", details.metadata());
        details.year = "2026"; details.duration = "02:03:36"; details.rating = "0";
        assertEquals("2026  ·  2h 3m", details.metadata());
    }
}
