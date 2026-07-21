# Self-hosted sync (fork feature)

This fork of [Food You](https://github.com/maksimowiczm/FoodYou) adds optional sync between the
app's diary and a self-hosted [`foodyou-mcp`](https://github.com/AntonyI1/foodyou-mcp) server, so
Claude can read and log food while the phone app shows and edits the same diary.

Everything here is additive and confined to the `com.maksimowiczm.foodyou.sync` package plus the
settings screen — the branch stays rebasable on upstream. Upstream's own features are unchanged.

## What it does

- **One-way toward your server.** The app only ever contacts the server URL you configure. No AI
  SDKs, no GMS/FCM, no push — background sync is WorkManager polling only (GrapheneOS-friendly).
- **Scope:** diary entries, daily goals, and a one-way **foods catalog** into "My Food" (see
  below). Recipes are **not** synced, and the foods catalog is one-way only (server → app).

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

## My Food / products (foods catalog)

Foods that Claude logs (in grams or millilitres) or saves on the server also appear in the app's
**My Food** list, so you can add them to meals by hand later. This is a **one-way, add/update-only**
pull (server → app):

- The app pulls `GET /api/v1/foods` and materializes each server food as a local **Product** (source
  *User*), keyed by the server food's UUID in a `SyncProductMapping` table (mirroring the entry
  mapping). Nutrition is stored **per 100 g/ml**; serving/package weights and the liquid flag carry
  over. Vitamins and minerals are left empty — the server doesn't track them.
- **Server-authoritative.** A synced product is only rewritten when the server food changes (its
  `updated_at` advances), so routine syncs don't churn it. If you edit a synced product in the app
  and the server food later changes, the server copy wins and overwrites your edit — this is by
  design (foods are one-way; the app never pushes product changes back).
- **No deletes.** There is no way to delete a catalog food from the server in v1, so nothing is ever
  removed from My Food by sync. Products you create in the app are never pushed to the server.
- **Dedupe is by the server's UUID**, not by name. A product you already created in the app by hand
  is independent of a same-named food pulled from the server — they can coexist as two entries.

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

> **One-way-door warning.** This build ships **Room schema v34** (v33 added `SyncEntryMapping`; v34
> adds `SyncProductMapping` for the foods catalog). Once the app has migrated its database to v34, an
> older build (v33, or upstream v32) **cannot open it** and will crash-loop on launch. If you ever
> want to go back, back up and clear the app's data first (Settings → Apps → Food You → Storage), or
> export your diary beforehand.

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

- Host JVM unit tests (`./gradlew :app:testDebugUnitTest`): `SyncMapperTest` (mapping, units, goals,
  loop-closure hash, foods → Product), `DefaultSyncEngineTest` (push/pull/LWW/tombstones/loop-closure/
  ruling-A/goals/uuid-reservation/fault-isolation **and foods pull: insert/update-guard/transaction/
  isolation/cursor** via fakes), `KtorSyncApiTest` (Ktor MockEngine, typed errors, foods pull),
  `SyncRunnerTest` (status + concurrency skip).
- `SyncMappingMigrationTest` (v32→v33) and `SyncProductMappingMigrationTest` (v33→v34) validate the
  additive auto-migrations via `MigrationTestHelper`; they are instrumented (need a device/emulator),
  so run them with `./gradlew :app:connectedDebugAndroidTest`, not the host suite.
