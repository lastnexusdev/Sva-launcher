# On Demand edition

This fork adds **On Demand** next to **Live TV**, while preserving existing tab
identifiers and saved navigation preferences. Its labels currently use English.

Live TV uses a playlist-driven category sidebar, selected-programme details,
Up Next, and a time-aligned guide grid with a current-time marker. All Channels,
Favorites, and Recent are included; provider category names are read from M3U
`group-title` values. Hidden channels stay hidden. Channel logos, titles, and
schedule times come from the imported sources; cinematic programme art and plot
summaries are not invented when absent from the guide. The Full guide button
retains the existing detailed guide and reminder features.

The sidebar and guide recycle their rows. D-pad navigation moves between
categories, channel labels, and programme cells; OK watches, and holding OK toggles
a favorite. The date control changes the timeline's day, and Now returns to the
current time. Recent contains at most 30 locally recorded channel identities.

Configure the provider's authenticated M3U URL in **Settings → Live TV**.
On Demand derives the Xtream-compatible account from that saved URL; credentials
are entered on the device and must never be committed to this repository.

On Demand uses a provider-category sidebar and a compact, full-height poster area.
Movies and TV Shows each have a landing page prefilled with the
newest 12 titles from every nonempty category, keeping the provider's category
names and order. There is no featured banner, Continue Watching row, or
watch-progress tracking. Select a poster to play a movie or open a series; hold OK
on a poster for More Info, including available plot, cast, year and duration.

Newest means the provider's `added` timestamp for movies or `last_modified` for
series, with descending media IDs as a stable fallback/tie breaker. It does not
claim that a newly added older film was released recently. Selecting a category
or See All opens newest-first paging. Search on the landing page covers all titles
in the current media type; search within a category stays scoped to it. Select a
series for its episodes; Back returns to the previous catalogue or landing page.
Movie and episode playback supports pause and seek, separately from live zapping.

Provider JSON is read as a stream. Bounded heaps retain 12 entries per landing-page
category or 120 per browse page; cursor paging does not retain the whole catalogue
or grow a heap for deeper pages. Posters use a bounded image cache. Full metadata
is requested only when More Info is opened and cached for up to 32 titles.
Landing data refreshes on re-entry after ten minutes, or via Refresh.
A network error leaves Refresh available. Catalogue listings require a connection;
artwork has a bounded disk cache. This is not an offline movie downloader.

## Larger playlists and live-only import

The M3U download ceiling is increased from 24 MiB to **128 MiB**. Downloads still
have a finite byte limit, including responses without a Content-Length header.
Parsing reads the response incrementally.

For Xtream playlists that combine channels, movies, and episodes, append the local
fragment `#sva-live-only` to the playlist URL. In this mode, `/movie/` and `/series/`
stream entries are discarded during parsing so Live TV retains only channels.
This fragment is not sent in HTTP requests. On Demand accesses movies and series
through the provider API independently. Without the fragment, normal M3U import
retains its existing behavior.

The fragment is intended for standard Xtream stream paths. It is not a universal
media classifier for arbitrary M3U playlists. Raising the byte limit alone does
not make retaining hundreds of thousands of entries appropriate on a small TV.

## Build

Use the existing release signing environment documented in DEVELOPER.md, then run:

```
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

On Windows, `APP_SHORT_COMMANDS := true` in Application.mk enables response files
for the native build's long object list. This fork retains the upstream license
and third-party notices. It contains no subscriptions, playlists, or credentials.
