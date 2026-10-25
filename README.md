# LJHPlayer

LJHPlayer is an Android TV / Android TV box media-player starter project written in Kotlin.

## Features in this starter version

- Android TV launcher entry and landscape UI
- Default source URL: `http://我不是.摸鱼儿.top`
- Editable source URL saved with SharedPreferences
- HTTP connectivity probe, response Content-Type, JSON/HTML classification, and common TVBox field detection
- Entry points for VOD, live TV, search, and source testing
- Media3 ExoPlayer / HLS dependencies for the next playback integration phase

## Source validation note

The configured root URL may serve a navigation page rather than a TVBox JSON API. A successful page load does not prove the JSON API, VOD, live TV, or search functions work. Open the provider's primary/backup entries and set the actual configuration URL in the app's source settings before testing.

## Build

Requirements: Android Studio, JDK 17, and Android SDK Platform 35.

Open this repository in Android Studio and wait for Gradle Sync, then run the app or execute:

```bash
gradle assembleDebug
```

The debug APK should be generated at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow builds the debug APK on pushes to `main` and manual workflow dispatch. Download it from the workflow run's Artifacts section.

## Current scope

This is an initial source-probe and configuration-switching scaffold. Real VOD listings, live channel parsing, cross-source search, and playback URL resolution still require the provider's actual API response schema. No successful APK build or live playback has been claimed yet.

Use only streams and content you are authorized to access.
