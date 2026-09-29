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
Install on a phone with USB debugging: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Modules

| Module | Contents |
|---|---|
| `core` | Pure Kotlin (JVM target now, iOS in Phase 2): money, currencies, price-tag parser, sale detector, FX indicator math, list classifier, QR frame codec, CSV |
| `shared` | KMP Android library: Room database, rate providers (Ktor), controllers, Compose Multiplatform UI |
| `androidApp` | Android app: CameraX, ML Kit, Tesseract (Georgian), location, QR bitmap encoding (ZXing) |

## Status

- [x] M0 scaffold: Gradle (AGP 9.4.1, Kotlin 2.4.20, Compose MP 1.12.1), three modules, debug APK builds
- [x] M0 assets: Source Sans 3 fonts, tessdata (`kat`, `eng`, `rus`)
- [x] core: money + currency/country/language catalogs
- [x] core: price-tag parser + sale detector + stabilizer (+ tests)
- [x] core: FX indicator, no dead-band, ≥3 significant digits (+ tests)
- [x] core: shopping-list parser + classifier + purchase matcher (+ tests)
- [x] core: QR frame codec ("i of N"), base45, CRC-32, SHA-256, reassembler (+ tests)
- [x] core: CSV codec + ZIP (+ tests)
- [x] shared: Room entities/DAOs, repositories (shopping, lists, settings, backup)
- [x] shared: rate providers (6) + cache + pivots + crypto legs via CoinGecko
- [x] shared: theme (dark, Source Sans 3, pills), navigation shell
- [x] shared: Home (camera, total bar, result card, quantity/cart sheets, finalize dialog, list strip, manual entry)
- [x] shared: List, History (+ detail), Analytics (Phase 1 summary), Settings, Onboarding, QR send/receive
- [x] androidApp: CameraX + ML Kit OCR/barcode, Tesseract names, ML Kit translation, location, SAF files, ZXing, haptics
- [x] Emulator smoke test (Pixel_4a API 33): onboarding, manual price, live USD→GEL rate, cart, finalize
      warning, history + detail, list classification + green strikethrough, QR send/receive screens,
      ▲/▼ indicator (+0.189 % after switching to CoinGecko), analytics
- [x] Scan a photo (Home → photo icon); used to verify Georgian OCR on the emulator with synthetic tags:
      Tesseract reads ხაჭაპური იმერული / არაჟანი სოფლის 20% exactly; ML Kit translates on-device;
      superscript tetri (4⁴⁹ ₾ → 4.49) and sale tags (-20 %, was 12.49) parse correctly
- [ ] Real-device test on a phone (camera OCR, Georgian names) — needs the user's phone over USB
- [ ] Georgian OCR on real photos (synthetic tags pass; real tags have glare, fonts, angles) — needs the phone
- [x] M4: encrypted all-data transfer by QR (12-char code, PBKDF2 + AES-GCM); lists by QR (unencrypted, per design)
- [x] Store names: History → open a session → tap the store name to rename it ("Store 1" by default)
- [x] 0.2.0 multi-buy deals (design §7.1): detection (EN/KA/RU), Georgian second OCR pass, "just 1 or
      the deal" choice, cart + History + CSV; DB v2 auto-migration verified by upgrading 0.1.0 → 0.2.0
      on the emulator with data; typed names in manual entry kept (bug in 0.1.0 in Georgia)
- [x] 0.3.0-beta.2 fixes from the first phone test (design §6, §7, §20): gibberish names on Georgian
      tags (0.2.0 fell back to ML Kit's reading when Tesseract found nothing; now Tesseract retries in
      sparse mode and at 2×, and ML Kit's reading is never used); label language follows a local
      currency set by hand; "Downloading Georgian…" / "Translating…" on the card and translations
      filled in later (History too); deal-only tags ("შეიძინეთ 3 ცალი ₾11.20-ად"); misread letters
      dropped as prices; strict live scanning (shelf-price format, prominence, not in sentences);
      frames cropped to the preview; shutter button; cart button with count; cart header with both
      totals and "1 USD = 2.60 GEL"; torch removed. Verified on the emulator with crops of the phone
      screenshots (photo scans), a fresh install, and US + GEL set by hand
- [x] 0.3.0-beta.3 from the second phone test (design §6.1, §6.2, §14, §20): tags priced in another
      currency (¥ while in euros) convert from that currency; Georgian names read from a
      full-resolution still taken on lock; Tesseract input scaled to ~36 px letters (0.5–3×) and
      contrast-stretched; reading area widened to whole lines; name lines touching the price row no
      longer dropped; stray marks trimmed; OCR near-misses of deal and sale words accepted; deal
      wording kept out of names; shutter falls back to the latest frame where stills aren't
      supported. Verified with the emulator's live camera on the phone screenshots (see below)
- [x] 0.3.0-beta.4 (design §6.2, §14, §20): every country readable (label language for all 245;
      ML Kit Chinese/Japanese/Korean/Devanagari built in; Tesseract models for other scripts
      downloaded on demand, SHA-256 pinned; other digit systems); online translation (MyMemory) with
      candidate ranking, offline packs downloaded automatically on arrival in a new country and
      managed in Settings (DB v3: translateOnline, autoPack); advertising never taken for names;
      Latin-script source language from the country, not language ID; Japanese bracketed counts and
      zero-decimal currencies. Verified on the emulator: live Japanese (¥2,150 → Carrots (5)), Spanish
      banner tags (Whole milk Lala 1 L; Olive oil Carbonell 500 ml), and photo scans in 12 more scripts
      (all prices right; names in 11 of 12, not Burmese; Arabic-Indic digits don't read)
- [ ] Phone check of 0.3.0-beta.4: Spanish store tags and speed on the phone
- [x] 0.3.0-beta.5 (design §6.1, §6.2, §14, §20; branch `tag-crop-and-ocr-confidence`):
      - Tag outlines: a pure-Kotlin finder (`core/.../scan/TagFinder.kt`, no OpenCV) on a ~480 px grey
        copy of each live frame, still and photo; the parser picks the aimed tag by its text and reads
        prices, names and banners only from it; Home outlines it, or shows centre corner marks.
      - The script pass (Tesseract) reads only the lock still, the shutter's or a gallery photo, never
        live frames; the tag is read straightened. Only the latest live frame is kept (was 12).
      - Georgian model is tessdata_best (see "Decisions"); every Tesseract line carries its
        confidence, and below 70 a reading is never a name. The price's own crop is read with a
        digits/separators/currency-signs whitelist to confirm the digits.
      - Names: deal and discount patterns count as advertising, unit lines ("წონა 1 კგ", "each")
        aren't names, letter case never rules a line out, store phrases seen on 3 tags lose.
      - Finalize moved from Home into the cart sheet (user request).
      Verified on the emulator's live camera (a ShelfGen juice tag on the virtual poster, GEL): the
      outline hugs the tag, the lock still is read straightened, "ფორთოხლის წვენი" (confidence 96) →
      "Orange juice", price crop "4.49" (96). 150 core tests.
- [ ] Phone check of 0.3.0-beta.5: Georgian tags on the monitor and in a store (outline, names, speed)
- [x] 0.3.0-beta.6 (from a phone report of a Thai "ราคา 88 บาท" sign named "A @s @a Ma ลห QR 0ป";
      design §6.2, §14): that junk was Tesseract's (tha+eng), not ML Kit's. Names now need a real word
      (in a label's script, a word of it); "price" in ~40 languages, currency names and OCR near-misses
      of them ("ฐาคา") aren't names; ML Kit's line confidence gates its names like Tesseract's (so a
      Georgian or Thai tag read with Latin labels shows no name instead of gibberish); words aren't
      split at vowel signs (Thai, Indic); the shutter's whole-photo fallback only takes the labels'
      own digits. Verified on the emulator with the sign and with Mexico + a Georgian tag.
      Open: on that sign, when ML Kit misses the stylised 88 but reads "ราคา" as 5101, the shutter
      still shows ฿5,101 (Tesseract also reads it as 5101, and reads the 88 as Thai letters).
- [ ] Phone check of 0.3.0-beta.6: the Thai sign on the monitor (฿88, no junk name)
- [x] 0.3.0-beta.7 (branch `ai-label-reading`): on-device reading keeps more of the product name,
      reads a Central American 2X$ price as a bundle, and no longer takes a store logo, a chopped
      slogan, a bare integer, or a date as the name, the old price, or a deal. AI is a button on
      every price card. Nothing is sent until it's tapped; the answer replaces the reading; the
      checkmark is the only time the phone remembers the correction. Settings take a SpaceXAI,
      OpenAI, or custom key, left out of backups. The recognizers are not retrained. Core tests
      pass. Not yet checked on a phone.
- [ ] Phone check of 0.3.0-beta.7: Selectos-style tags (2X$ bundles, full names) and the AI button
- [ ] M5 (after Phase 1): full analytics charts
- [x] Release build: R8 keep rules for ML Kit + Tesseract, per-ABI APKs (arm64 release ≈ 59 MB); verified on emulator

## Decisions made during the build

- `applicationId = "app.parity"` is a placeholder for the sideloaded build (see androidApp/build.gradle.kts).
  Kotlin packages (`app.parity.*`) are independent of it.
- No DI framework and no ViewModel library: an app-scoped `AppGraph` holds controllers with their own
  coroutine scope. MainActivity handles config changes itself and is locked to portrait.
- The shared theme receives its `FontFamily` from the platform, so Compose resources are not needed.
- QR payloads are JSON + DEFLATE (design said CBOR + zstd); simpler and still ~4× smaller than raw.
- QR frames are Base45 text so they use the QR alphanumeric mode; 700-byte chunks per frame.
- Rate API keys are stored in the app database (not the Keystore yet) and are never exported.
- Classifier tier 2 (on-device embeddings) is not built; the lexicon + user overrides cover Phase 1.
- Every locked scan is recorded as an observation; repeats of the same product+price within
  10 minutes reuse the earlier one.
- **Georgian Tesseract model: tessdata_best 4.1.0** (0.3.0-beta.5), not tessdata_fast or tessdata.
  Measured with Homebrew Tesseract 5.5.3 on 176 rendered Georgian shelf lines (4 fonts, letters
  10/14/20/28 px, blurred, noisy, JPEG), `kat+eng`, single-line mode:

  | kat model | size | CER 10 px | CER 14 px | CER 20–28 px | all | time |
  |---|---|---|---|---|---|---|
  | tessdata_fast (before) | 2.5 MB | 56.9 % | 5.7 % | ≤0.3 % | 15.3 % | 42 ms/line |
  | **tessdata_best** | 4.5 MB | 53.5 % | 4.7 % | ≤0.6 % | **14.3 %** | 50 ms/line |
  | tessdata (legacy + integer LSTM) | 8.7 MB | 64.4 % | 8.2 % | ≤0.4 % | 17.8 % | 40 ms/line |

  The app's Tesseract 5.5.1 (tesseract4android 4.9.0) loads the float model: "loaded kat+eng in
  ~1 s (Tesseract 5.5.1)" on the emulator. Russian and English stay tessdata_fast (unchanged,
  bundled); downloads stay tessdata_fast, pinned. Resolution matters more than the model: every
  model reads 20 px letters almost perfectly and fails at 10 px, hence stills only.
- **Name confidence threshold 70** (`NameText.MIN_CONFIDENCE`), from the same lines: it dropped 46
  of 51 misread lines (CER > 10 %) and 3 of 125 good ones.
- **No Jev (TypeSafe).** Considered for classifying tag lines; it's a hosted API that needs a paid
  key and can't be bundled in the app, so name/promo/deal classification stays in-process.

## Install on a phone (Phase 1, sideloaded)

Serve the APK on the local network and open the page on the phone (same Wi-Fi):

```bash
./gradlew :androidApp:assembleRelease
python3 tools/serve_apk.py        # prints e.g. http://192.168.1.254:8420/
```

Tap the download, open the file, and allow "Install unknown apps" for the browser if asked.
Installing a newer build over an older one keeps the data (same signing key). If macOS asks
whether Python may accept incoming connections, allow it.

Alternative over USB: `adb install -r androidApp/build/outputs/apk/release/androidApp-arm64-v8a-release.apk`.

## Releases (GitHub)

- Public repo: https://github.com/Orthobro83/parity. Releases carry the per-ABI APKs.
- Release builds are signed with `signing/parity-release.p12`; its passwords are in
  `keystore.properties`. **Both are git-ignored and exist only on this Mac, so back them up**
  (e.g. in a password manager). Without them, no update can ever be installed over an existing
  install. Signing certificate SHA-256:
  `89791c7d8123a8d9fab2810e6bab773eb42fb0bc0cd51bd9d9a6c5e26a1b6303`.
- To cut a release: bump `versionCode`/`versionName` in androidApp/build.gradle.kts, run
  `./gradlew :core:jvmTest :androidApp:assembleRelease`, then create a tag and a GitHub release
  with the APKs (see the v0.3.0-beta.1 release for the notes format).

## Testing Georgian OCR on the emulator

The emulator's camera shows a virtual room, so tags are tested as photos (Home → photo icon):
push PNGs with `adb push tag.png /sdcard/Pictures/` and pick them. For OCR details, run
`adb shell setprop log.tag.ParityOcr VERBOSE`: the log then shows what ML Kit read from each photo
(`photo …`) and what Tesseract read (`lines psm=…`). `ParityText` logs language-pack status and
translation failures, and `ParityCamera` the analysed frame size.

## Testing the live camera on the emulator

The emulator's back camera is a virtual room with a poster on the wall, and the poster can show
any image, so the live-camera path (lock, still, Tesseract) can be tested without a phone:

```bash
adb emu automation play ~/Library/Android/sdk/emulator/resources/macros/Walk_to_image_room
adb emu virtualscene-image wall /path/to/tag.png   # no path restores the default poster
```

After the walk, the camera faces the poster. Swap images between scans, and show the default
poster for a few seconds in between so the scanner resets.

The emulator's camera takes real stills, but at 1280×960 (cropped to 660×1280), so a tag's name is
only ~28 px tall on it: expect lower Tesseract confidence than on a phone. `ParityOcr` logs show
each Tesseract line with its confidence ("ფორთოხლის წვენი (96)") and the tag outlines found
("tags [Box(…)]"). Tag scenes for the poster: `java tools/ShelfGen.java <dir>`, then crop a
landscape band around the rail (`sips -c 900 1080 --cropOffset 380 0 shelf_juice.png --out …`).

## Screenshots

README screenshots are taken on the emulator with a still store scene in place of its virtual
room. Debug builds only: if `files/demo_backdrop.png` exists in the app's storage, Home shows it
instead of the camera.

```bash
java tools/ShelfGen.java /tmp/scenes          # shelf_juice.png, shelf_pasta.png + tag crops
adb push /tmp/scenes/shelf_juice.png /data/local/tmp/backdrop.png
adb shell run-as app.parity cp /data/local/tmp/backdrop.png files/demo_backdrop.png
adb push /tmp/scenes/tag_juice.png /sdcard/Pictures/     # then scan it with Home → photo icon
```

Restart the app after changing the backdrop. For the ▲/▼ shot, scan the same tag twice more than
10 minutes apart (repeats within 10 minutes reuse the first sighting), e.g. after switching the
rate provider to CoinGecko. Screenshots are cropped from 1080 px wide and scaled to 540.

## Next step

See the first unchecked item above. Also worth doing next:
- Try real Georgian tags from photos (Home → photo icon) as soon as possible.
