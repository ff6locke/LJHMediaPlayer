# LJHPlayer

LJHPlayer is a Kotlin Android TV / Android TV box player that reads TVBox-style JSON configurations and plays compatible VOD streams with AndroidX Media3.

## Implemented VOD flow

- Load a TVBox JSON configuration from an editable HTTP(S) URL.
- Read the `sites` or `data` site array and inspect each site's type and API.
- For standard CMS JSON sites (`type: 0`), load categories and video lists, filter by category, and page through results.
- Search a CMS site by keyword, load a video's detail response, split `vod_play_from` / `vod_play_url` into source lines and episodes, and open an episode in the player.
- Run compatible CatVod-style Spider sites when the configured JAR and class ABI are compatible. The loader attempts the CatVod Spider `Init.init(Context)` bootstrap before constructing the site class and refreshes the site JAR when explicitly run. Third-party JARs are executable code; only use sources you trust.
- Read the top-level `lives` array, list live sources, fetch M3U/M3U8 channel playlists or JSON channel lists, group channels by `group-title`, and launch HTTP(S) channels in the Media3 player. M3U `#EXTVLCOPT:http-referrer` is passed as a Referer header.
- Play direct HTTP(S) media URLs with Media3, including common MP4, HLS/M3U8, and DASH/MPD streams. JSON headers such as Referer and Cookie can be passed to the player.

## Configure a TVBox source

1. Install and open LJHPlayer on an Android TV device, TV box, or Android emulator.
2. Paste a **direct URL to a TVBox JSON file** into the source field. A navigation page, HTML webpage, or image URL is not a configuration file.
3. Tap **检测接口** to check the HTTP response and JSON shape.
4. Tap **加载点播站点**, then choose a `type: 0` CMS JSON site to browse categories and videos.
5. Search for a title or open a video, choose an episode, and use the player's on-screen controls or remote control.

The default source URL is only a convenience and may become unavailable. Replace it with a JSON configuration you are authorized to use. A valid configuration does not guarantee every listed site works: sites may be offline, require headers, use unsupported parser types, or return a URL that is not a direct media stream.

## Build

Requirements:

- Android Studio with Android SDK Platform 35 installed
- JDK 17
- Gradle compatible with Android Gradle Plugin 8.7.3 (Gradle 8.9 recommended)

Open the repository root in Android Studio and wait for Gradle Sync. To build a debug APK from a terminal with Gradle installed:

```bash
gradle assembleDebug
```

The APK path is:

```
app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow builds a debug APK on pushes to `main`, pull requests targeting `main`, and manual workflow dispatch. Download the `LJHPlayer-debug-apk` artifact from a successful workflow run.

## Limitations

- CMS JSON `type: 0` is the primary supported direct API format. XPath and other custom engines need their own adapters.
- Live playlist support currently covers HTTP(S) M3U playlists and common JSON `channels` arrays; provider-specific live formats, EPG, catch-up, and complex per-channel request headers are not fully implemented.
- Spider execution is best-effort and depends on the JAR's class names, ABI, runtime dependencies, and initialization contract.
- The player does not bypass DRM, paywalls, authentication, or a provider's access controls. A stream requiring an external resolver or DRM integration will not play as a plain direct URL.
- Playback has not been claimed successful for every third-party configuration or source. Use only streams and content you are authorized to access.
