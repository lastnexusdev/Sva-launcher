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

On Demand includes Movies and Series, provider categories, search within the
selected category, and Previous/Next pages. Select a series to browse its episodes;
Back returns to the catalogue. Movie and episode playback supports the player's
pause and seek controls. It does not use the live-channel zapping controls.

Provider JSON is read as a stream, retaining at most 120 results per page rather
than holding the full movie/episode catalogue in the launcher's heap. Cover art
uses the existing bounded image loader. A network error leaves Refresh available.
Catalogue listings require a provider connection; only cover art uses the existing
disk cache. This is not an offline movie library or download manager.

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
