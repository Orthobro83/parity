# Parity — build progress

Source of truth for the design: [design.md](design.md). This file tracks what is built, so work can
resume cleanly after an interruption.

## How to build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:jvmTest              # fast unit tests for the pure-Kotlin logic
./gradlew :androidApp:assembleDebug  # APK at androidApp/build/outputs/apk/debug/
```

`local.properties` (git-ignored) must contain `sdk.dir=/Users/<you>/Library/Android/sdk`.

## Modules

| Module | Contents |
|---|---|
| `core` | Pure Kotlin (JVM target now, iOS in Phase 2): money, currencies, price-tag parser, sale detector, FX indicator math, list classifier, QR frame codec, CSV |
| `shared` | KMP Android library: Room database, rate providers (Ktor), app state, Compose Multiplatform UI |
| `androidApp` | Android app: CameraX, ML Kit, Tesseract (Georgian), location, QR bitmap encoding (ZXing) |

## Status

- [x] M0 scaffold: Gradle (AGP 9.4.1, Kotlin 2.4.20, Compose MP 1.12.1), three modules, debug APK builds
- [ ] M0 assets: Source Sans 3 fonts, tessdata (`kat`, `eng`, `rus`)
- [ ] core: money + currency catalog
- [ ] core: price-tag parser + sale detector (+ tests)
- [ ] core: FX indicator (+ tests)
- [ ] core: shopping-list parser + classifier (+ tests)
- [ ] core: QR frame codec, base45 (+ tests)
- [ ] core: CSV/ZIP export model (+ tests)
- [ ] shared: Room entities/DAOs, repositories
- [ ] shared: rate providers + cache + pivots
- [ ] shared: theme (dark, Source Sans 3, pills), navigation shell
- [ ] shared: Home (camera slot, total bar, result card, buy sheet, cart sheet, finalize)
- [ ] shared: List, History, Analytics placeholder, Settings, QR send/receive screens
- [ ] androidApp: CameraX + ML Kit OCR/barcode, Tesseract names, translation, location

## Decisions made during the build

- `applicationId = "app.parity"` is a placeholder for the sideloaded build (see androidApp/build.gradle.kts).
  Kotlin packages (`app.parity.*`) are independent of it.
- No DI framework and no ViewModel library: an app-scoped `AppGraph` holds state holders with their own
  coroutine scopes. MainActivity handles config changes itself and is locked to portrait.
- The shared theme receives its `FontFamily` from the platform, so Compose resources are not needed.

## Next step

See the first unchecked item above.
