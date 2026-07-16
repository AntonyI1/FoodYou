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
   stored encrypted with the device's hardware-backed key; a missing scheme is auto-prefixed with
   `http://`).
4. Toggle **Enable sync** on. The app then syncs every ~15 minutes, when it comes to the
   foreground, and on demand via **Sync now**. The screen shows the last-sync time or error.

### First-run checklist

After enabling, do a quick round-trip to confirm the setup end to end:

1. Tap **Sync now** and confirm the status line shows a recent "Last synced" time (not an error).
2. Ask Claude to log a test food; **Sync now** again and confirm it appears in the app's diary.
3. Add an entry in the app; **Sync now** and confirm Claude can read it via `get_diary`.

## How entries map

The server stores each entry as pre-computed **totals** (kcal, protein, carbs, fat, plus optional
fiber, sugar, saturated fat, salt). The fork maps between that and Food You's model:

- **Claude/server-originated entries** materialize locally as **manual diary entries**. Their
  totals, name, meal and date sync both ways (last-write-wins by `updated_at`).
- **App-logged foods** (product/recipe measurements) are pushed to the server as totals so Claude
  can see them, with a descriptive quantity per unit (g / ml / serving / piece).
- **Meals** match by name, case-insensitively. An unknown meal name creates a new "any time"
  (00:00–23:59) meal so the categorization is preserved. **Locale note:** meals are matched by their
  stored (localized) names, so if the server sends a meal name in a different language than the app
  is set to, a new meal is created rather than matched.
- **Goals** sync only when you use a single daily goal (not per-weekday goals); in per-day mode the
  app keeps its goals and skips goal sync. **First contact:** the first time goals sync, if the
  server already has goals set (e.g. Claude configured them) the server's win; otherwise the app
  seeds the server with its own.

### v1 limitation (by design)

A manual/Claude entry can only store name + date + the eight nutrient totals + meal — it cannot hold
a brand, barcode, notes, or a specific quantity/unit. So when Claude logs a rich entry, the app
shows a simplified copy while **the server keeps the full record**, and the app never overwrites the
server's richer copy on a later sync.

App-logged food entries push **outward only**: a server-side *edit* to one (e.g. Claude changing the
calories of a food you logged in the app) is **not** written back onto the app's structured entry —
reversing a totals edit into a product + quantity is undefined. Such an edit stays on the server;
the app copy is unchanged. Server-side **deletes** of these entries *are* honored in-app.

## Building the APK

> **JDK 21 is required** (not 17). `:shared:barcodescanner` compiles Java at `--release 21`, so a
> JDK 17 toolchain fails with `invalid source release: 21`. Upstream's dev flake uses Temurin 21;
> use the same. The Android SDK must have `platforms;android-36` and `build-tools;36.0.0`.

> **One-way-door warning.** This build ships **Room schema v33**. Once the app has migrated its
> database to v33, an older upstream build (v32) **cannot open it** and will crash-loop on launch.
> If you ever want to go back to upstream, back up and clear the app's data first (Settings → Apps →
> Food You → Storage), or export your diary beforehand.

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

## Home-screen widget (fork feature)

A 4x2 Android widget (Jetpack Glance) showing today's calories + macros, with an apple-shaped
calorie meter. Additive, confined to the `com.maksimowiczm.foodyou.widget` package plus a manifest
`<receiver>`. It refreshes automatically — after a sync, when the app is backgrounded, and on
Android's periodic update — and honours the kcal/kJ setting. No GMS/FCM; the periodic update is the
platform's own AppWidget mechanism.

### Why the widget's read is wrapped in `withTimeout`

**Do not remove `readTimeoutMs` from `ObserveWidgetSummaryUseCase` — it is not defensive padding.**

`ObserveDiaryMealsUseCase.observe(date)` builds `combine(diaryEntries)` over one flow per meal. With
**zero meals** that array is empty, and kotlinx-coroutines' `combineInternal` bails out on an empty
input without emitting (`flow/internal/Combine.kt:18`, coroutines 1.10.2). The outer flow never
completes either, because the use case combines a 1-second poller that runs forever. Net: the flow
**never emits and never completes**, so a bare `.first()` suspends forever — inside a
BroadcastReceiver's window, leaving the widget stuck on its loading layout permanently.
`runCatching` does **not** catch a hang; only a timeout does.

Zero meals is user-reachable: `MealSettingsViewModel.deleteMeal()` has no "keep at least one" guard.

**Upstream has the same latent bug** (its home card shimmers forever in that state) and is knowingly
**not fixed here** — `ObserveDiaryMealsUseCase` is upstream-owned and changing it would cost
rebasability. The widget defends itself instead.

The read also catches `Exception` (rethrowing `CancellationException`, per `DefaultSyncEngine`'s
idiom): the goals blob is JSON-decoded and validated with `error(...)`/`require(...)`, and DataStore
surfaces `IOException`, any of which would otherwise escape before Glance renders anything.

## Tests

- Host JVM unit tests (`./gradlew :app:testDebugUnitTest`): `SyncMapperTest` (mapping, units, goals,
  loop-closure hash), `DefaultSyncEngineTest` (push/pull/LWW/tombstones/loop-closure/ruling-A/goals/
  uuid-reservation/fault-isolation via fakes), `KtorSyncApiTest` (Ktor MockEngine, typed errors),
  `SyncRunnerTest` (status + concurrency skip).
- `SyncMappingMigrationTest` validates the v32→v33 auto-migration via `MigrationTestHelper`; it is
  instrumented (needs a device/emulator), so run it with `./gradlew :app:connectedDebugAndroidTest`,
  not the host suite.
- Widget: `CalorieMeterTest` (meter math — over-goal clamp, zero/absent goal, kcal↔kJ) and
  `ObserveWidgetSummaryUseCaseTest` (the three read failure modes: hang→timeout, throw→catch,
  cancellation→rethrow). The apple stroking, the bitmap render and the Glance composition are
  **on-device only** — there is no Glance/Compose UI-test harness in this repo.
