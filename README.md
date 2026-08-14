# tiktok-lavalink-plugin

A Lavalink v4 plugin that adds TikTok as an audio source.

- Direct link: queue any `tiktok.com/...` URL like normal
- Search: `tiktoksearch:phonk edit sound`

## How it works

1. `TikTokAudioSourceManager` — registered automatically by Lavalink's Spring
   component scan (it's just an `@Service` implementing `AudioSourceManager`,
   no extra manifest needed).
2. Resolving a video goes through a **fallback chain**, via `resolveTrack()`:
   - **`DirectTikTokResolver`** (primary) — fetches the public TikTok video
     page directly and parses the JSON blob TikTok embeds in the HTML for
     their own web app. No third party involved, no signed API calls needed
     (that's only required for TikTok's internal AJAX endpoints, not for
     loading a public webpage).
   - **`TikTokApiClient`** (fallback, tikwm.com) — used if the direct scrape
     fails, e.g. because TikTok changed the embedded JSON's structure and we
     haven't updated the parser yet. Also the only source of
     `tiktoksearch:` results, since real TikTok search needs their signed
     internal API which page-scraping doesn't give us access to.
3. `TikTokAudioTrack` — re-runs the same resolver chain right before
   playback (TikTok CDN links expire) and hands the fresh URL off to
   lavaplayer's built-in `HttpAudioSourceManager`, which already knows how
   to decode raw mp3/mp4 streams. This means we didn't have to write our own
   audio decoder.

## Why two resolvers instead of just tikwm

TikTok has no official public streaming API. Their *internal* AJAX endpoints
require constantly-changing signed request params (X-Bogus / msToken /
etc.) — this is exactly why DuncteBot's plugin dropped TikTok support
("breaking so often it is not worth my time to fix it"). But loading a
public video *page* doesn't need any of that signing, it's just an HTTP GET
like a browser would do — which is what `DirectTikTokResolver` relies on.

Relying on a direct scrape as primary removes the single point of failure on
a third party for the common case. tikwm stays as a safety net for whenever
TikTok changes their page's JSON structure and `DirectTikTokResolver` hasn't
been updated yet.

**If TikTok changes their page structure**, update the parsing logic in
`DirectTikTokResolver.kt` (it already tries two known formats —
`__UNIVERSAL_DATA_FOR_REHYDRATION__` and the older `SIGI_STATE` — as a hint
for where to look). **If tikwm ever dies or starts blocking you**, everything
you need to change lives in `TikTokApiClient.kt`. Neither failure takes the
whole plugin down on its own.

## Build & run locally

```bash
./gradlew build          # jar lands in build/libs/
./gradlew runLavalink    # spins up a local Lavalink with this plugin loaded
```

(You'll need the Gradle wrapper — run `gradle wrapper` once if it's not
present, or grab one from the official template:
https://github.com/lavalink-devs/lavalink-plugin-template)

## Known limitations / things to watch

- Private videos, region-locked content, and slideshow (photo) posts will
  fail to resolve — `resolveByUrl` returns `null`, which the source manager
  turns into a "not found" for the bot.
- Search quality depends entirely on tikwm's search endpoint, which is less
  robust than TikTok's own in-app search.
- No caching/backoff yet — if you hit tikwm's rate limit, add retry/backoff
  logic in `TikTokApiClient`.
- Scraping TikTok, even via a third party, sits in a legal/ToS gray area —
  worth being aware of if you're distributing this publicly rather than
  running it for personal use.

## Next steps if you want to harden this

- Add response caching (e.g. Caffeine) so repeated queue-adds of the same
  video don't hit tikwm every time.
- Add retry-with-backoff around the HTTP calls.
- Add unit tests around `TikTokApiClient` JSON parsing using recorded fixture
  responses, so you notice fast when tikwm changes its response shape.
