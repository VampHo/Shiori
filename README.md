# Shiori

An Android manga and novel reader, continued from Kotatsu by VampHo.

## Preview 9.5.0-a1

- Existing Kotatsu manga reader.
- **Novels** in the main screen overflow menu: search Royal Road, open a novel,
  choose a chapter and read with adjustable text size.
- Last-read chapter and scroll position saved per novel, with a Continue button
  for the most recently read novel. Global incognito mode disables novel progress saving.
- Developer notifications and heap dumps are off by default. Developers can enable
  them in debug settings. StrictMode still logs violations; existing leaks are not
  claimed to be fixed.

The novel integration is an early preview adapted from QuickNovel's Royal Road
provider. It currently supports online English prose, not EPUB import, TTS,
chapter downloads, reviews, images, or a full novel bookshelf. Browser verification
and source changes can prevent loading. Opening the website is a fallback, not a
verification bypass or a guarantee that the in-app request will work afterward.
Novel progress is local to this installation and is not included in manga backups.

## Install and test

Download the APK artifact from the **Build test APK** workflow in Actions.
The app is named **Shiori Preview** with application ID
`io.github.vampho.shiori.debug`; it installs alongside Kotatsu Dev. Data is not
migrated automatically. This is a debug-signed test app, not a production release.
CI caches the preview signing key; loss of the cache can require reinstalling a
future preview. Production signing and upgrades remain to be set up.

Open the main screen overflow menu → **Novels**, search a title, open a chapter,
scroll, return to the chapter list, and use Resume reading to verify progress.
Live source access and device behavior require real-device testing in addition
to the fixture tests in CI.

## Build

Use Java 17 and Android SDK 36 / Build Tools 35.0.0:

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Credits and license

Based on [Kotatsu](https://github.com/KotatsuApp/Kotatsu), with Royal Road selector
adaptations from [QuickNovel](https://github.com/LagradOst/QuickNovel).
Shiori is an independent fork, not an official continuation by either team.
See [LICENSE](LICENSE) (GPL-3.0) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Original source notices are retained.
