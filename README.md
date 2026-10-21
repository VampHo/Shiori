# Shiori

A personal Android manga and novel reader, based on Kotatsu and QuickNovel.

## 9.5.2 source recovery update

- Manga and novels have separate libraries and equal navigation.
- Novel sources: Inori, NovelFire, Royal Road, Wattpad, FenrirRealm, WuxiaWorld, NovelFull and ReadHive.
- Novel downloads, EPUB/text imports, reading progress, fullscreen reading, themes and font controls.
- Novel requests share cookies with the in-app source browser. Verification and sign-in are user initiated.
- FenrirRealm chapter data is resolved by field references rather than fixed array positions.
- WuxiaWorld chapters use its official lightweight reader; saved library addresses stay unchanged.
- Timeouts identify the source and offer retry and a return to the source list.

Live source availability is separate from build success. Sites can require verification,
sign-in or payment. Shiori does not unlock inaccessible chapters. Fixture and emulator
tests do not guarantee that every source works on every network.

## Build and signing

Use Java 17, Android SDK 36 and Build Tools 35.0.0:

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

The **Build Shiori APK** workflow runs unit and Android emulator tests and builds
the release. It publishes an installable APK only when a persistent signing key
is configured in repository Actions secrets:

- `SHIORI_KEYSTORE_BASE64`: Base64-encoded private PKCS12/JKS keystore.
- `SHIORI_KEYSTORE_PASSWORD`: optional; default `android`, used for store and key.
- Repository variable `SHIORI_KEY_ALIAS`: optional; default `shiori`.

Store the keystore privately and keep a separate backup. Never commit it.
Without the secret, validation can succeed, but no installable APK is published.
The run summary explicitly reports this delivery blocker. Release builds never
fall back to a temporary debug signing key.

The package remains `io.github.vampho.shiori.debug`. Earlier CI builds attempted
to cache a nonexistent key path, so their signatures changed between builds.
A new persistent key cannot update an app signed with a lost old key. Do not
uninstall an existing installation until its data has been backed up and a
migration route has been checked. Novel progress is not included in manga backups.

## Credits and license

Based on [Kotatsu](https://github.com/KotatsuApp/Kotatsu), with source adapters
adapted from [QuickNovel](https://github.com/LagradOst/QuickNovel).
Shiori is an independent fork, not an official continuation by either team.
See [LICENSE](LICENSE) (GPL-3.0) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Original source notices are retained.
