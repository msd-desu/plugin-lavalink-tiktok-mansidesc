# tiktok-lavalink-plugin

A Lavalink v4 plugin that adds TikTok as an audio source.

- Direct link: queue any `tiktok.com/...` URL like normal
- Search: `tiktoksearch:phonk edit sound`

## How it works

1. `TikTokAudioSourceManager` — registered automatically by Lavalink's Spring
   component scan (it's just an `@Service` implementing `AudioSourceManager`,
   no extra manifest needed).
2. `TikTokApiClient` — resolves a TikTok URL or search query into metadata +
   a raw stream URL. Uses **tikwm.com**, a free third-party resolver, instead
   of reverse-engineering TikTok's own signed endpoints directly.
3. `TikTokAudioTrack` — re-resolves the stream URL right before playback
   (TikTok CDN links expire) and hands it off to lavaplayer's built-in
   `HttpAudioSourceManager`, which already knows how to decode raw mp3/mp4
   streams. This means we didn't have to write our own audio decoder.

## Why tikwm instead of TikTok's own API directly

TikTok has no official public streaming API. Their real endpoints require
constantly-changing signed request params (X-Bogus / msToken / etc.) — this
is exactly why DuncteBot's plugin dropped TikTok support ("breaking so often
it is not worth my time to fix it"). Using a third-party resolver trades
"TikTok breaks your signing" for "the third party breaks or gets rate
limited" — less code to maintain, but a dependency you don't control.

**If tikwm ever goes down or starts blocking requests**, everything you need
to change lives in `TikTokApiClient.kt` — swap `BASE_URL`, adjust the JSON
parsing, or point it at a different resolver. Nothing else in the plugin
needs to change.

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
