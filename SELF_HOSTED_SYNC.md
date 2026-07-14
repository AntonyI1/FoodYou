# Self-hosted sync (fork feature)

This fork of [Food You](https://github.com/maksimowiczm/FoodYou) adds optional sync between the
app's diary and a self-hosted [`foodyou-mcp`](https://github.com/AntonyI1/foodyou-mcp) server, so
Claude can read and log food while the phone app shows and edits the same diary.

Everything here is additive and confined to the `com.maksimowiczm.foodyou.sync` package plus the
settings screen — the branch stays rebasable on upstream. Upstream's own features are unchanged.

## What it does

- **One-way toward your server.** The app only ever contacts the server URL you configure. No AI
  SDKs, no GMS/FCM, no push — background sync is WorkManager polling only (GrapheneOS-friendly).
- **Scope:** diary entries and daily goals. Recipes and the custom-food library are **not** synced.

## Setup

1. Deploy `foodyou-mcp` (see that repo) and note its URL (LAN or Tailscale, e.g.
   `http://192.168.1.10:8365`) and bearer token.
2. In the app: **Settings → Self-hosted sync**.
3. Enter the **Server URL** and **Access token**, tap **Save & test connection** (the token is
   stored encrypted with the device's hardware-backed key).
4. Toggle **Enable sync** on. The app then syncs every ~15 minutes, when it comes to the
   foreground, and on demand via **Sync now**. The screen shows the last-sync time or error.

## How entries map

The server stores each entry as pre-computed **totals** (kcal, protein, carbs, fat, plus optional
fiber, sugar, saturated fat, salt). The fork maps between that and Food You's model:

- **Claude/server-originated entries** materialize locally as **manual diary entries** and sync
  both ways (last-write-wins by `updated_at`).
- **App-logged foods** (product/recipe measurements) are pushed to the server as totals so Claude
  can see them.
- **Meals** match by name (case-insensitive); an unknown meal name creates a new "any time"
  (00:00–23:59) meal so the categorization is preserved.
- **Goals** sync only when you use a single daily goal (not per-weekday goals); in per-day mode the
  app keeps its goals and skips goal sync.

### v1 limitation (by design)

App-logged food entries push **outward only**. A server-side *edit* to one of them (e.g. Claude
changing the calories of a food you logged in the app) is **not** written back onto the app's
structured entry — reversing a totals edit into a product + quantity is undefined. Such an edit
stays on the server; the app copy is unchanged. Server-side **deletes** of these entries *are*
honored in-app. Entries Claude creates (manual entries) have no such limitation and sync fully.

## Building the APK

> **JDK 21 is required** (not 17). `:shared:barcodescanner` compiles Java at `--release 21`, so a
> JDK 17 toolchain fails with `invalid source release: 21`. Upstream's dev flake uses Temurin 21;
> use the same. The Android SDK must have `platforms;android-36` and `build-tools;36.0.0`.

Debug build (unsigned, for testing):

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Release build (signed, for sideloading on GrapheneOS):

```bash
# One-time: create a keystore
keytool -genkey -v -keystore foodyou.keystore -alias foodyou \
  -keyalg RSA -keysize 2048 -validity 10000

# Build, align, sign (mirrors the repo's justfile `release` target)
./gradlew --no-daemon :app:assembleRelease
zipalign -f -p -v 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --alignment-preserved \
  --ks foodyou.keystore --ks-key-alias foodyou \
  --out foodyou-release-signed.apk aligned.apk
```

`zipalign` and `apksigner` live in `$ANDROID_HOME/build-tools/36.0.0`. Transfer
`foodyou-release-signed.apk` to the phone and install it with the GrapheneOS installer (or point
Obtainium at your own build). The app declares only the `INTERNET` permission and bundles no Google
Play Services.

## Tests

- JVM unit tests (`./gradlew :app:testDebugUnitTest`): `SyncMapperTest`, `DefaultSyncEngineTest`
  (push/pull/LWW/tombstones/loop-closure/ruling-A/goals via fakes), `KtorSyncApiTest` (Ktor
  MockEngine), `SyncRunnerTest`.
- The v33 Room migration is additive (auto-migration) and validated at build time against the
  exported `app/schemas/.../33.json`. An instrumented `MigrationTestHelper` test can be added for
  on-device runs.
