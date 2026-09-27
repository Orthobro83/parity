# Parity — Design

**Parity** is an Android app (with iPhone planned later) that reads price tags in stores through the
camera and converts
prices into the user's home currency, which can be fiat or crypto. It also translates product names,
keeps a cart and a shopping list, and records every trip. Over time it shows whether the user's
currency is gaining or losing purchasing power against the local one. The name comes from
*purchasing-power parity*.

**Context and timeline**
- **Phase 1 – field test.** The first target market is **Georgia (GEL, Georgian script)**. The app
  is installed directly from the build (sideloaded), with no store listing, while the §18
  "Georgia build" milestones are completed and tested.
- **Phase 2 – public release.** Google Play first, then iPhone (App Store). Architecture decisions
  made now must not block that. This is why Parity uses Kotlin Multiplatform (§2).

---

## 1. Goals and non-goals

**Goals**
- Scan a price tag and see the converted price in about a second, with no typing.
- Support any fiat currency (USD, CAD, GEL, …) and crypto (BTC, ZEC, …) as the base currency.
- Record every purchase with the exchange rate at the moment of purchase, so history never has to be
  recalculated.
- Build a record of the same products bought on repeat visits, so the app can report how the base
  currency is performing against the local currency.
- Work offline in the store, using cached rates and on-device OCR and translation.

**Non-goals (v1)**
- Payments, receipts scanning, or bank integration.
- Cloud accounts or sync. All data stays on the device, and backup is done through CSV export.
- Online price comparison between stores.

---

## 2. Platform and tech stack

Parity uses **Kotlin Multiplatform (KMP) + Compose Multiplatform.** Only Android is built in Phase 1,
but all business logic and nearly all UI live in shared code, so the iPhone app later mostly means
writing the platform adapters (camera, OCR, translation, location), not rewriting the app.

| Concern | Shared (`commonMain`) | Android (`androidMain`) | iOS later (`iosMain`) |
|---|---|---|---|
| UI | Compose Multiplatform, Material 3 (dark theme §4.2) | — | same Compose UI |
| Architecture | MVVM + unidirectional state (ViewModel → `StateFlow<UiState>`), Koin DI | | |
| Money math | `com.ionspin.kotlin:bignum` `BigDecimal` (never `Double`) | | |
| Persistence | Room (KMP) + SQLite bundled driver; DataStore (KMP) for settings | | |
| Networking | Ktor client + kotlinx.serialization, one adapter per rate provider | OkHttp engine | Darwin engine |
| Dates | kotlinx-datetime | | |
| Charts | Vico (multiplatform) | | |
| Price parsing, sale detection, FX indicator, classifier, CSV, QR frame codec | **all shared, pure Kotlin** | | |
| Camera | `expect` interface | CameraX | AVFoundation |
| OCR | `expect TextRecognizer` | ML Kit Text Recognition v2 + **Tesseract (`kat`) for Georgian** | Apple Vision + Tesseract |
| Barcode | `expect` | ML Kit Barcode | Vision `VNDetectBarcodesRequest` |
| Language ID / Translation | `expect` | ML Kit Language ID + on-device Translation (Georgian supported) | Apple Translation framework |
| QR generation | shared (pure-Kotlin QR encoder) | | |
| Location | `expect` | Fused Location Provider + `Geocoder` | Core Location |
| Background work | `expect` | WorkManager | BGTaskScheduler |

- Android: minSdk 26, targetSdk 37 (installed SDK). Builds use Android Studio's bundled JDK.
- **Georgian OCR is the top technical risk.** Neither ML Kit nor Apple Vision reads Georgian script,
  so Tesseract (C++, used on both platforms) with the `kat` model does the product names. Prices are
  digits and `₾`/`GEL`, which ML Kit reads fine. See §14.

---

## 3. Onboarding and settings model

First launch runs a three-step flow:

1. **Base currency.** A searchable list containing ISO-4217 fiat codes plus supported crypto (BTC, ZEC,
   ETH, XMR, LTC, …). The list is driven by whichever currencies the selected rate provider supports.
2. **Language.** Any language that ML Kit Translation supports. This is the target language for product
   names. The app UI is localized separately, following the Android system locale by default.
3. **Location permission.** The app asks for foreground (`ACCESS_COARSE_LOCATION`) permission only.
   If the user declines, they set the local currency manually. The app also offers to change it
   whenever OCR sees a currency symbol that doesn't match.

```kotlin
data class UserSettings(
    val baseCurrency: CurrencyCode,        // "USD", "BTC", "ZEC"
    val language: LanguageTag,             // "en"
    val localCurrencyOverride: CurrencyCode?, // null = derive from location
    val manualLocation: ManualLocation?,   // country + city, when location is off/overridden
    val rateProvider: RateProviderId,
    val rateProviderApiKey: String?        // only for providers that need one; stored encrypted
)
```

---

## 4. Screens and navigation

Bottom navigation has five icon buttons:

| Icon | Screen | Purpose |
|---|---|---|
| 🏠 | **Home** (default) | Camera scanning, cart, running total, Finalize |
| 📝 | **List** | Shopping list |
| 🕘 | **History** | Past shopping sessions |
| 📈 | **Analytics** | Price and FX trends |
| ⚙️ | **Settings** | Currency, language, location, rate provider, CSV |

The shopping list gets its own tab. It is also visible from Home as a compact overlay, described in §6.4.

### 4.1 Home layout

```
┌──────────────────────────────────────────┐
│  $42.18  (₾113.40)            ▾ 6 items  │  ← Running total bar (tap = expand cart)
├──────────────────────────────────────────┤
│                                          │
│            CAMERA  PREVIEW               │
│      ┌──────────────────────────┐        │
│      │  $3.71  (₾9.99)     ▲ 2.1%│       │  ← Result card anchored near detected tag
│      │  Sour cream 20%           │       │  ← Translated name
│      │  SALE                     │       │  ← Sale chip (if detected)
│      │   [ Cancel ]   [ Buy ]    │       │
│      └──────────────────────────┘        │
│                                          │
│  List: ☐ coffee ☐ cheese ~~chicken~~ …   │  ← Collapsible shopping-list strip
├──────────────────────────────────────────┤
│           [   Finalize (6)   ]           │  ← Visible once cart is non-empty
├──────────────────────────────────────────┤
│   🏠     📝     🕘     📈     ⚙️          │
└──────────────────────────────────────────┘
```

### 4.2 Visual design

**Direction:** sleek, dark and premium, like a camera app crossed with a trading terminal. The camera
feed is the hero. Everything else floats above it on dark, softly blurred glass surfaces.

**Theme**
- The app is **dark mode only** in v1. An optional "true black" setting for OLED phones sets the
  background to `#000000`.
- Material 3 is the base, with a custom color scheme. Dynamic (wallpaper) color is off, so the brand
  looks the same on every phone.

**Color tokens**

| Token | Hex | Use |
|---|---|---|
| `bg` | `#0B0D10` | App background |
| `surface` | `#15181D` | Cards, sheets, nav bar |
| `surfaceRaised` | `#1D2127` | Result card, dialogs |
| `glass` | `#15181D` at 72 % + 24 dp blur | Overlays on the camera (total bar, result card) |
| `outline` | `#2A2F37` | 1 dp hairlines |
| `textPrimary` | `#ECEEF1` | |
| `textSecondary` | `#9BA3AE` | Original-language names, captions |
| `accent` | `#7C8CFF` (periwinkle) | Primary buttons, focus, active nav icon |
| `up` | `#2BD67B` | ▲ indicator, **green strikethrough** on the list |
| `down` | `#FF5A5F` | ▼ indicator, destructive actions |
| `neutral` | `#6B7380` | "=" indicator |
| `sale` | `#FFB547` (amber) | SALE chip |

- Green and red are **reserved for meaning** (currency up or down, checked off). The accent is
  deliberately neither, so the ▲ and ▼ always stand out.
- The ▲ and ▼ also differ in shape and in their `+`/`−` sign, so colour-blind users can still read
  them.
- All text meets WCAG AA contrast against its surface.

**Typography: Source Sans 3**
- The variable font is bundled in `res/font/` (SIL Open Font License), so it doesn't depend on
  downloadable fonts and works offline.
- Its **tabular figures** (`fontFeatureSettings = "tnum"`) are used for every price, total and
  percentage, so digits line up and don't jitter as values change.

| Style | Size / weight | Use |
|---|---|---|
| Display | 40 sp / 600, tnum | Converted price on the result card |
| Headline | 24 sp / 600 | Screen titles, running total |
| Title | 18 sp / 600 | Card titles, product names |
| Body | 16 sp / 400 | General text |
| Label | 14 sp / 600, +0.2 letter-spacing | Buttons, chips |
| Caption | 13 sp / 400 | Original-language names, timestamps |

**Shapes: everything rounded**
- **Buttons are fully rounded pills** (`RoundedCornerShape(50%)`), 52 dp tall for primary actions
  (**Buy**, **Finalize**) and 44 dp for secondary ones.
- Round icon buttons are used for **✕**, the quantity steppers and the camera controls.
- Cards and sheets use a 24 dp corner radius, and chips are pills.
- Primary buttons are filled with `accent` and have dark text. Secondary buttons are `surfaceRaised`
  with a hairline outline. Destructive actions use `down` as the text colour, not the fill.

**Motion (the "impressive" part, kept subtle)**
- The result card springs up from the detected tag's position, with a light haptic tick when a price
  locks in.
- The converted price counts up quickly (about 250 ms) to its value. The ▲ or ▼ fades in with a small
  bounce.
- When something is added to the cart, a small chip flies up into the running-total bar, and the
  total rolls to the new value digit by digit, like an odometer.
- On the shopping list, the green strikethrough draws left to right (about 300 ms), then the row
  dims.
- While scanning, detected text regions get a soft animated outline in `accent` that shows where the
  app is reading.
- Swipe-to-remove reveals a `down`-tinted background with a trash icon.
- All animations respect the system "Remove animations" accessibility setting.

**Icons:** Material Symbols Rounded, weight 400, to match the rounded buttons.

---

## 5. Location and "new location" detection

**What counts as a location:**
- **Country** decides the local currency.
- **Store**: a cluster of scans within about 75 m of each other. A store record is created on the first
  scan at a place, and the user can name it ("Carrefour, Tbilisi Mall"). Repeat visits to the same
  store make the price comparisons in §9 meaningful.

**Detection strategy.** Foreground only, so the app doesn't need the background-location permission,
which is hard to get approved on Google Play.
- On app start and resume, and while the camera is active at most every 5 min, the app requests a
  `PRIORITY_BALANCED_POWER_ACCURACY` fix.
- If the country code differs from the current one, a banner appears:
  *"Looks like you're in Georgia 🇬🇪. Local currency set to GEL."* with an **Undo** / **Change** action.
- Country → currency mapping comes from a bundled table (`CountryCode → CurrencyCode`, with
  multi-currency countries such as Panama, Zimbabwe, and Cambodia prompting a choice).
- Store matching: nearest existing `Store` within 75 m, otherwise create a provisional store.

**Manual override** in Settings sets the country, city, or local currency. The manual value takes
precedence until the user clears it.

---

## 6. Scanning pipeline

```
CameraX frame (ImageAnalysis, ~3 fps, STRATEGY_KEEP_ONLY_LATEST)
   │
   ├─► Barcode scanner ──────────────► barcode (optional)
   └─► Text recognizer ──► text blocks with bounding boxes + line heights
                              │
                              ▼
                    PriceTagParser
                     ├─ price candidates
                     ├─ product-name candidate
                     └─ sale signals
                              │
                   stable across N=3 frames?  ──no──► keep scanning
                              │yes
                              ▼
            convert ─► translate ─► FX indicator ─► show Result card
```

### 6.1 Price extraction
- Regex over each line for amounts with an optional currency symbol or code on either side:
  `₾ 9.99`, `9,99 ₾`, `9.99 GEL`, `$3.49`, `1 299,00 ₽`.
- **Decimal and grouping separators:** decide from the locale of the detected country first, then fall
  back to a heuristic. If there is exactly 2 digits after the last `,` or `.`, treat that character as
  the decimal separator.
- **Which number is the price?** Candidates are scored by:
  - glyph height (the biggest number usually wins),
  - presence of a currency symbol,
  - not being next to per-unit markers (`/kg`, `за 1 кг`, `per 100g`, `1კგ`),
  - not looking like a barcode, date, or weight.
- Superscript cents (`9⁹⁹`, rendered as a large "9" plus small "99") are merged when a small 2-digit
  block sits at the top-right of a large integer block.
- If two candidates score within 10 % of each other, the card shows both as chips and the user taps
  the right one. That choice is saved as a training signal for that store's tag layout.
- If the detected currency symbol contradicts the current local currency, the card asks the user to
  confirm.

### 6.2 Product name and translation
- Name candidate: the longest non-numeric text block near the price, excluding boilerplate words
  ("price", "цена", "ფასი", unit strings).
- ML Kit Language ID runs on it. If the language differs from the user's language and confidence is at
  least 0.5, it is translated on-device.
- The card shows the translated name, with the original in smaller grey text underneath.
- Users can edit the name. Edits are stored on the `Product` so the next scan uses them.

### 6.3 Sale detection
A price observation is flagged `isPromo = true` when any of these hold:
- Sale keywords in any supported language, from a bundled dictionary: `sale`, `promo`, `% off`,
  `was/now`, `скидка`, `акция`, `ფასდაკლება`, `აქცია`, `rebaja`, `soldes`, `-20%`, …
- Two price candidates where one is struck through, or explicitly labelled "old" or "was".
- A percentage token (`-15%`) near the price.
- The user toggles the **SALE** chip manually. This overrides auto-detection in both directions.

When two prices are present, the higher one is stored as `regularPrice` and the lower as `price`.
Analytics can then still use the regular price, as described in §10.

### 6.4 Product identity (for "same item again")
Matching is done in order of preference:
1. **Barcode**, if one was read.
2. **Store + normalized original-language name**: lowercased, whitespace and punctuation collapsed,
   size tokens kept ("500g").
3. Fuzzy match (Jaro-Winkler ≥ 0.92) on the same store's products. If it is ambiguous, the card asks
   *"Same as 'Sour cream 20% 400g'?"*.

---

## 7. Cart, Buy, running total

- **Buy** opens a quantity sheet with a stepper and a numeric field, default 1. Decimals are allowed
  for weighed goods (`1.35 kg`). Confirming adds a `CartLine`. **Cancel** closes the card, and the
  scan is still recorded as a `PriceObservation`, because it is useful data even without a purchase.
- **Running total bar** (top): `Σ base` with `(Σ local)` in brackets.
  - Each line converts using the rate captured when it was scanned. The total is therefore the sum of
    locked-in values, and it doesn't drift while the user shops.
  - If the cart contains more than one local currency (for example, the user crosses a border
    mid-session), the brackets show the currency of the current location, and the expanded list shows
    each line in its own currency.
- **Tapping the bar** expands it into a bottom sheet listing the cart:
  - each row shows the name, `qty × unit price`, and the line total in base (local), with −/+ steppers;
  - **swipe right** removes a row, with a 5-second **Undo** snackbar;
  - collapse by tapping the bar again, swiping the sheet down, or pressing Back. The sheet has a drag
    handle and snaps to peek, half, and full heights.
- Buying an item that matches a shopping-list entry crosses it off automatically (§8.3).

---

### 7.1 Multi-buy deals (added in 0.2.0)

Stores often price a single unit and add a deal for several: "from 3, ₾7.99 each" next to a
₾9.99 regular price, "3 for ₾20", "1+1", "2+1", "2nd at 50 % off", "2/$5".

- **Detection.** The parser recognizes deal wording in English, Georgian ("3 ცალის ყიდვისას",
  "აქცია 1+1") and Russian ("от 3 шт. по 79.90", "2 по цене 1"). The deal price is never taken
  for the regular price, even when it is printed larger. A price below the regular one is read as a
  per-unit deal price; a price between the regular price and N × regular is a bundle total. A plain
  pack size ("Eggs 10 pcs") is not a deal.
- **Georgian tags.** The fast recognizer can't read Georgian script, so once a price locks,
  Tesseract re-reads the area around it; that second pass finds deal wording, Georgian sale words
  and the product name (skipping promotion banners such as "აქცია 1+1").
- **Not a sale.** A deal leaves the single-unit price unchanged, so deal wording alone doesn't mark
  the scan as a sale; price tracking keeps using the regular price (§10).
- **At Buy.** The quantity sheet asks: "Just 1 at the regular price" or "N with the deal", each
  with both currencies and the saving, plus "Another quantity…" (a stepper with a deal toggle).
- **Cart and History.** Each line stores whether the deal was chosen. The effective unit price, the
  exact line total, the regular unit price and the deal are saved with the purchase and shown in
  History ("3 × $3.07 (₾7.99) · Deal: 3+ at ₾7.99 each · regular ₾9.99") and in `purchases.csv`.
- **Corrections.** The DEAL chip on the result card adds, edits or removes a deal the camera
  missed; manual price entry has optional deal fields.
- **Data.** Schema v2 adds `price_observation.multiBuyJson`, `cart_line.multiBuy`, and
  `purchase_line.regularUnitPriceLocal`, `multiBuyJson`, `lineTotalLocal` (automatic migration from
  v1; existing data is kept).

---

## 8. Shopping list

### 8.1 Entry
- A free-text box that accepts commas, "and", or newlines:
  *"chicken, ground beef, coffee, creamer, chocolate, yogurt, and cheese"*.
- It is split into items, trimmed, singularized for matching, and de-duplicated.

### 8.2 Classification
Categories: Meat & Fish, Dairy & Eggs, Produce, Bakery, Beverages, Desserts & Snacks, Pantry,
Frozen, Household, Personal Care, Baby, Pet, Other.

Items are classified in three tiers:
1. **Bundled lexicon.** About 3,000 common grocery terms mapped to categories, stored in English plus
   the top UI languages. Examples: chicken → Meat, creamer → Beverages, yogurt → Dairy.
2. **On-device text embedding** (MediaPipe Text Embedder, a small USE-lite model). The item is compared
   against category centroids and assigned the nearest one if cosine similarity is at least 0.45.
3. **Otherwise "Other."** Long-pressing an item lets the user move it to another category, and that
   choice is remembered in a `user_category_override` table, which takes priority over tiers 1 and 2.

The list is displayed grouped by category, with collapsible headers.

### 8.3 Checking items off
- Tapping an item, or buying a product that matches it, draws a **green strikethrough line** across
  the text with a short animation, and the row dims.
- Matching a purchase to a list item uses the translated name first, then the original name, then the
  category embedding. The match needs similarity ≥ 0.6, and the user can undo it.

### 8.4 Import from another phone ✅ *(requirement)*
Shopping lists must be importable from another phone. **QR code is the primary mechanism.** The full
QR transfer design, including large datasets, is in §8.5. The other two mechanisms are there for
phones that aren't side by side. All three use the same payload:

```json
{ "v": 1, "type": "parity.list", "name": "Weekend",
  "items": [{ "t": "chicken", "c": "MEAT" }, { "t": "coffee", "c": "BEVERAGE" }] }
```

| Mechanism | How | Good for |
|---|---|---|
| **QR code** (primary) | The sender taps *Share → QR*, and the receiver scans it with the app's camera. It uses a single static code when the data fits, and a cycling multi-frame sequence when it doesn't (§8.5). The receiver exits to Home automatically when complete, and the sender closes with a round ✕. | Two phones side by side, offline |
| **Android share sheet** | Sends the payload as a `.parity` file (`application/vnd.parity+json`) **and** as plain text. The app registers intent filters for both. Plain text from any app (WhatsApp, SMS, a notes app) is parsed like typed input. | Remote sharing; the sender doesn't need the app |
| **Deep link** | `https://<domain>/l#<base64url payload>`. The fragment is never sent to a server. Android App Links open it in the app. | Messaging apps that strip attachments |

On import, a preview dialog offers **Merge into current list** or **Create new list**.
(Android Beam/NFC was removed in Android 14 and is not used.)

### 8.5 QR transfer: capacity and large datasets

The same QR transport carries any app data, not just shopping lists: a list, selected history
sessions, a store's price history, or a full backup (the §15 export).

**Single-QR capacity (hard limits)**

| QR version, error correction | Max binary payload | Reliable phone-to-phone scan? |
|---|---|---|
| v40, L (177×177 modules, the absolute maximum) | 2,953 bytes | Poor. It needs a large, bright screen, a steady hand and good focus |
| v25, M | ~1,000 bytes | Yes, from screen to screen at 15–25 cm |
| v15, M | ~400 bytes | Instant, even in poor store lighting |

The app never produces a single code above about 1 KB, and uses error-correction level M.

**Typical sizes after compression.** Data is packed with CBOR, compressed with zstd using a bundled
dictionary of common grocery words, and encoded in QR byte mode.

| Data | Raw | Compressed | Transfer |
|---|---|---|---|
| Shopping list, 50 items | ~1.5 KB | ~0.4 KB | **1 static QR** |
| One shopping session, 30 items with rates | ~6 KB | ~1.5 KB | 2–3 cycling frames, about 1 s |
| One year of data (about 2,000 observations and 100 sessions) | ~350 KB | ~70–90 KB | ~100 cycling frames, **about 15–25 s** |
| Several years of heavy use | several MB | ~0.5 MB | ~640 cycling frames, **about 1.5–3 min** |

**Cycling QR sequence (for anything over 1 KB)**

QR is the only transport for phone-to-phone data. There is no Bluetooth or Wi-Fi fallback. Large
datasets simply cycle for longer.

*Frame format.* The payload is split into ~800-byte chunks, numbered `1..N`. Each frame carries a
small header:

```
magic "PY" | version | transfer ID (8 bytes, random per transfer) | frame index i | total frames N
| payload SHA-256 (first 8 bytes) | chunk bytes | CRC-32 of the frame
```

**Every code says where it sits in the set.** All codes use this same header, including a single
static code, so the receiver never has to guess:

| Header | Meaning to the receiver |
|---|---|
| `1 of 1` | **This is the only code needed.** Verify it, import it, and exit immediately. There is no waiting for repeats. |
| `1 of 4`, `2 of 4`, `3 of 4`, `4 of 4` | A multi-code set. Keep scanning until every number from 1 to 4 has been seen for this transfer ID. |

The transfer ID ties the codes of one set together. A code from a different set (for example, the
sender restarted with new data) is never mixed in. If the receiver sees a new transfer ID part-way
through, it asks: *"A different transfer started. Start over?"*

*Sender screen*
- Shows frames `1 → N` in order at about 8 fps, then loops back to 1 and repeats until closed.
- The screen is full-screen, at maximum brightness, with keep-screen-on enabled, on a white
  background with a quiet zone around the code.
- Small text under the code shows the same position the code carries: **"2 of 4"** (plus "· loop 2"
  after the first pass). A single code shows **"1 of 1 · only code needed"** and does not animate.
  For encrypted transfers, the passphrase also appears here.
- A **round ✕ button in the top-right corner** closes the screen and returns to where the user came
  from. The sender can't know when the receiver has finished, because QR only goes one way, so
  closing it is always the user's job.

*Receiver screen*
- The receiver enters receive mode in either of two ways:
  - an **Import via QR** button, found on the List tab and in Settings;
  - automatically, when the Home camera, which already runs the barcode scanner for price tags,
    sees a Parity transfer frame.
- As soon as the first code is read, the receiver knows `N`. It shows **"Received 2 of 4"** and a row
  of `N` cells, each labelled with its number. Cells fill in green as their codes arrive, so the user
  can see which ones are still missing (for example, "waiting for 3") while the sender loops.
- For a `1 of 1` code, none of this appears. The receiver completes on the first successful read.
- **Frames can be missed or read out of order.** Frames lost to glare or a shaky hand are simply
  picked up on the next loop.
- Frames with a bad CRC, or from a different transfer ID, are ignored.

*Completion and auto-exit*
- The receiver finishes as soon as it holds **all N distinct frames**, and the reassembled payload
  matches the SHA-256 in the header. Then it:
  1. vibrates and plays a short confirmation tone,
  2. **leaves QR scan mode automatically and returns to Home**,
  3. shows the import preview on Home: *Merge / Create new list* for shopping lists, or
     *Merge / Replace all* for data (§15).
- This refines the "stop when you start seeing duplicates" idea. A repeat alone isn't a safe signal,
  because the sender loops back to frame 1 even when frames 37 and 52 were missed. Since every frame
  says how many frames there are in total, the receiver knows exactly when it has everything. In
  practice this happens during the loop where duplicates start appearing.
- If the hash doesn't match (which is very unlikely once the CRC checks pass), the receiver discards
  the data, clears the grid and keeps scanning.
- If no new frame arrives for 20 s, it shows *"Hold the phones steady, about 20 cm apart"* along
  with a Cancel button.

*Expected transfer times* (8 fps, ~800 B per frame, assuming ~5 % of frames missed, so usually just
over one loop)

| Data | Frames | One loop | Typical total |
|---|---|---|---|
| Shopping list | 1 (static) | — | instant |
| One session | 2–3 | < 1 s | ~1 s |
| One year (~80 KB) | ~100 | ~13 s | **~15–25 s** |
| Several years (~500 KB) | ~640 | ~80 s | **~1.5–3 min** |

Throughput is tunable later: a denser code (~1.2 KB per frame) or a higher frame rate on newer phones
could roughly double it without changing the protocol.

**Privacy of shown codes.** A static or animated QR on screen can be photographed by someone nearby.
Transfers of history or backups (anything beyond a shopping list) are therefore encrypted:
- The sender shows a random **4-word passphrase** (about 44 bits), which is never encoded in the QR.
- The user types or reads it into the receiver, and the key is derived with Argon2id.
- A 6-digit PIN was rejected, because anyone who recorded the frames could brute-force it offline in
  seconds.

Shopping lists are sent unencrypted, for convenience.

---

## 9. The FX indicator (▲ / ▼) — core feature

**Question it answers:** *since I last scanned this item, has my currency gained or lost purchasing
power against the local currency?*

### Definitions
- `R` = units of **local** currency per 1 unit of **base** currency at a given moment
  (for example, GEL per USD).
- `R_prev` = the rate stored with the most recent previous `PriceObservation` of the **same product**
  in the **same local currency**.
- `R_now` = the current rate.
- `Δ = R_now / R_prev − 1`

### Display rules
| Condition | Indicator | Meaning |
|---|---|---|
| `Δ > 0` | 🟢 **▲** green, `+Δ%` | Each unit of your currency buys more local currency. The local currency has weakened. |
| `Δ < 0` | 🔴 **▼** red, `Δ%` | Your currency buys less. The local currency has strengthened. |
| `Δ = 0` exactly | ⚪ **=** grey | The rate is identical, usually because both scans used the same published rate |
| no previous observation | nothing shown | First sighting |

- **No dead-band.** Any non-zero movement shows a triangle, so the data stays high-resolution.
- Δ is computed in `BigDecimal` from the full-precision stored rates, never from rounded display
  values.
- The badge shows **at least 3 significant digits** of the percentage, so small moves stay visible:
  `+0.0142 %`, `−0.387 %`, `+2.21 %`. The popover shows the exact rates and Δ to full precision.
- **Rate resolution depends on the provider.** A daily-updated source gives the same rate all day, so
  two scans that day show "=". To keep resolution high, the app fetches a fresh rate at scan time
  whenever it is online and the cached rate is older than the provider's update interval (see §12).
  It also stores the provider's own publication timestamp, so an "=" can be told apart from "rate not
  updated yet".
- Tapping the indicator opens a small popover:
  *"Last scanned 14 Mar 2026 at Carrefour. Then: 1 USD = 2.68 GEL. Now: 1 USD = 2.74 GEL (+2.2 %).
  Shelf price: ₾9.49 → ₾9.99 (+5.3 %). In USD: $3.54 → $3.65 (+3.0 %)."*
- The triangle reflects **only the currency effect**. The shelf-price change (local inflation or a
  price rise) appears as secondary text in the popover, so the two signals aren't mixed together.
- The indicator does not depend on whether either observation was a sale, because it compares rates,
  not prices.

### Why store the rate on every observation
Rates are snapshotted at scan time (`fxRate`, `fxRateTimestamp`, `fxProvider`). History, analytics,
and this indicator therefore never need historical-rate API calls, and they stay correct offline.

---

## 10. Analytics

**Per product** (the list is searchable, and products with at least 2 observations are shown first):
- A line chart of the **price in base currency** over time, using non-promo observations only.
  - If an observation is a promo but has a `regularPrice`, the regular price is used instead of
    dropping the point.
- A second line shows the local-currency price, and a toggle switches it to a dual axis.
- **Change breakdown** between any two points:
  `total Δ in base ≈ (shelf-price Δ in local) − (FX Δ)`, shown as
  *"+3.0 % total = +5.3 % shelf price, −2.2 % currency effect."*

**Overall**
- **Base-currency performance:** the rate `R` over time for each local currency the user has shopped
  in, built from all observations plus cached daily rates.
- **Basket index:** the median change in base-currency price across all products seen in both of two
  chosen periods, excluding promo observations.
- **Spending:** monthly totals by category and by store.

---

## 11. Finalize and history

### 11.1 Finalize
- The **Finalize (n)** button appears once the cart has at least 1 line.
- If there are unchecked shopping-list items, a dialog appears:

  > **Are you sure?**
  > You still haven't purchased these items from the shopping list:
  > • coffee
  > • cheese
  >
  > [ Keep Shopping ]   [ Finalize purchases ]

- Finalizing writes a `ShoppingSession` with its `PurchaseLine`s in a single Room transaction, then
  clears the cart. Unpurchased list items stay on the list.

### 11.2 History
- A list of sessions, newest first. Each row shows date and time, store, item count, and the total in
  base (local).
- **Search:** separate Year / Month / Day fields. Any of them can be left empty, so "2026" alone lists
  the whole year, and "2026 / 03" lists that month. A calendar picker is also available.
- **Session detail:** one row per product showing:
  - the name in the user's language, with the original name under it,
  - qty,
  - unit and line price in base currency,
  - the local price in brackets,
  - a SALE chip where applicable,
  - the exchange rate used.

---

## 12. Exchange-rate providers

The user chooses a provider in Settings. Rates are cached per `(base, quote, provider)` and refreshed:
- **at scan time**, whenever the app is online and the cached rate is older than the provider's update
  interval (for example, CoinGecko about every 1 min, an hourly provider every 60 min),
- in the background once a day by WorkManager, so the offline cache is never very old.

When offline, the app uses the latest cached rate and shows a "rate from 3 h ago" badge. The
observation records both `fxRateAt` (when the provider published the rate) and `observedAt` (when the
scan happened), so analytics can weight or filter stale-rate observations.

The **resolution** column matters for the FX indicator (§9): it is the finest time step at which the
triangle can change.

| Provider | Key needed | Fiat | Crypto | Resolution | Notes |
|---|---|---|---|---|---|
| **fawazahmed0/exchange-api** (jsDelivr CDN) | No | ✅ incl. GEL | ✅ BTC, ZEC, … | Daily | **Default** for coverage, no rate limit |
| **Frankfurter** (ECB) | No | Major currencies only (no GEL) | ❌ | Daily (ECB working days) | Official ECB reference rates |
| **open.er-api.com** (ExchangeRate-API free tier) | No | ✅ incl. GEL | ❌ | Daily | |
| **CoinGecko** | Free demo key | via vs_currencies | ✅ | ~1–5 min | **Recommended when the base is crypto** |
| **Open Exchange Rates** | Yes | ✅ | Limited | Hourly (free tier), finer on paid plans | Best free-ish option for intraday fiat |
| **exchangerate.host** | Yes | ✅ | Some | Depends on plan | |

Settings shows each provider's resolution next to its name. If the user picks a daily provider, Settings
notes that same-day scans will show "=".

**Cross rates.** If the provider can't quote `base→local` directly, the app goes through a pivot:
`BTC→USD→GEL` via USD, or via EUR if USD isn't available. The pivot path is stored on the snapshot.

**Provider interface**

```kotlin
interface RateProvider {
    val id: RateProviderId
    val supports: Set<CurrencyCode>
    suspend fun latest(base: CurrencyCode, quotes: Set<CurrencyCode>): RateSnapshot
}
```

---

## 13. Data model (Room)

```
Store(id, name, lat, lng, countryCode, createdAt)

Product(id, barcode?, originalName, normalizedName, originalLang,
        translatedName, translatedLang, userEditedName?, category?, storeId?)

PriceObservation(id, productId, storeId, sessionId?,           -- every scan
        observedAt, localCurrency, price, regularPrice?, isPromo, promoSource[AUTO|USER],
        baseCurrency, fxRate, fxRateAt, fxProvider, fxPivot?)

CartLine(id, observationId, quantity)                           -- transient, pre-finalize

ShoppingSession(id, startedAt, finalizedAt, storeId?, baseCurrency,
        totalBase, totalsLocalJson)                             -- {"GEL":"113.40"}

PurchaseLine(id, sessionId, productId, observationId,
        quantity, unitPriceLocal, localCurrency, unitPriceBase,
        fxRate, isPromo, nameAtPurchase, nameTranslatedAtPurchase)

ShoppingList(id, name, createdAt)
ShoppingListItem(id, listId, text, category, checked, checkedAt?, matchedPurchaseLineId?)
UserCategoryOverride(normalizedText PK, category)

RateCache(base, quote, rate, fetchedAt, provider)  PK(base, quote, provider)
```

Money is stored as `TEXT` decimal strings, mapped to `BigDecimal` through TypeConverters, so no
precision is lost for satoshi-level BTC values.

Display rounding:
- fiat uses its ISO minor units (JPY 0, USD 2, KWD 3);
- BTC shows 8 decimals, with an optional display in sats;
- ZEC shows 8 decimals.

---

## 14. OCR language coverage (known risk)

- The ML Kit on-device recognizers cover Latin, Chinese, Devanagari, Japanese, and Korean scripts.
  **Georgian (Mkhedruli), Cyrillic, Arabic, Hebrew, Thai, and Greek are not well covered.**
- Mitigations:
  - **Prices** are digits plus symbols, which the Latin model reads reliably in every country, so
    conversion always works.
  - **Product names** in unsupported scripts are routed to Tesseract with the relevant language pack.
    Packs download on the first visit to that country, over Wi-Fi by default.
  - If OCR confidence is low, the card shows "Name not recognized — tap to type or photograph." A
    photo is kept as a thumbnail so the product can still be recognized by barcode or image later.
- Future option: an opt-in cloud vision/LLM fallback for names, off by default for privacy.

---

## 15. CSV export and restore

"Export all data to CSV" produces **one ZIP file** containing one CSV per table, plus
`manifest.json` holding the schema version, export time, and app version. The file is written through
the Storage Access Framework (`ACTION_CREATE_DOCUMENT`), so the user chooses the location.

- The CSVs are RFC 4180 compliant, UTF-8 with a BOM so Excel handles non-Latin names, and use ISO-8601
  timestamps in UTC.
- **Restore** (`ACTION_OPEN_DOCUMENT`):
  1. validate the manifest,
  2. show a summary ("312 observations, 41 sessions, 2 lists"),
  3. offer **Replace all** or **Merge**. Merge de-duplicates by UUID, and all IDs are UUIDs so merges
     are safe.
  The restore runs in a single transaction, and a failure rolls everything back.
- A single "purchases.csv" flat export (one row per purchased item, human-readable) is also offered
  for spreadsheet users.

---

## 16. Permissions and privacy

| Permission | When requested | If denied |
|---|---|---|
| `CAMERA` | First visit to Home | Manual price entry form |
| `ACCESS_COARSE_LOCATION` | Onboarding step 3 | Manual country/currency |
| `ACCESS_FINE_LOCATION` | Optional, for store-level matching | Stores matched by name only |
| `INTERNET` | Install-time | Cached rates only |

- No analytics SDKs and no account. Network calls go only to the chosen rate provider and to Google's
  ML Kit model downloads.
- The API key, if one is used, is stored with the Android Keystore through EncryptedDataStore.

---

## 17. Module layout

```
composeApp/                – Compose Multiplatform UI + navigation (commonMain), Android entry point
  commonMain/ui/…          – home, cart, list, history, analytics, settings, theme (§4.2)
shared/
  core/money               – CurrencyCode, Money, formatting, rounding
  core/data                – Room KMP DB, DAOs, repositories
  core/rates               – RateProvider impls (Ktor), cache, pivot logic
  core/scan                – PriceTagParser, sale detector, product matching (pure Kotlin)
  core/fx                  – FX indicator math
  core/list                – shopping-list parser + classifier
  core/transfer            – QR frame codec, reassembly, passphrase crypto
  core/csv                 – ZIP/CSV export + restore
  platform/                – expect interfaces: Camera, TextRecognizer, Translator, Locator, Scheduler
androidApp/ (androidMain)  – CameraX, ML Kit, Tesseract, Fused Location, WorkManager actuals
iosApp/                    – (Phase 2) Swift host + iosMain actuals
```

Everything in `shared/core` is pure Kotlin with thorough unit tests. The parser tests run against a
**golden set of real price-tag photos**, starting with **Georgian supermarket tags** (Carrefour,
Nikora, Spar, Goodwill, 2 Nabiji, Agrohub), collected from public photos.

---

## 18. Milestones

### Phase 1 — Georgia build

| # | Milestone | Contents |
|---|---|---|
| **M0** | Foundations + risk spike (week 1–2) | KMP project, theme, navigation shell. **Georgian OCR spike:** ML Kit for prices and Tesseract `kat` for names, tested against the golden set. The go/no-go for the OCR approach is decided here. |
| **M1** | Core loop | Settings/onboarding, manual local currency, camera → price → conversion, Buy/Cancel, cart sheet, running total, Finalize, History |
| **M2** | Intelligence | Georgian → English translation, sale detection, product identity, **FX indicator ▲/▼**, rate providers with scan-time refresh |
| **M3** | Location + shopping list | Country detection, stores, shopping list with classification and green strikethrough, auto-matching |
| **M4** | Data safety | ZIP/CSV export and restore, QR transfer (§8.5) |
| — | Buffer (2–3 weeks) | Real-device testing and polish; offline-in-store testing |

**Analytics (M5) is deliberately scheduled after Phase 1.** The app records every observation from
day one, so the charts can be built later from real Georgia data without losing anything.

### Phase 2 — public release
- **M5** Analytics screen
- **M6** Play Store readiness: onboarding polish, UI localization, privacy policy, and a
  permissions review
- **M7** iOS app: `iosMain` actuals, Swift host, and App Store submission

---

## 19. Decisions and open questions

**Accepted defaults.** These were approved along with the rest of the design, and can be revisited.
1. "USD" in the running total and in Finalize means the **user's base currency**.
2. **Cancel still records the scan** as a price observation, but not as a purchase.
3. The FX indicator compares against the **last scan of the same product** in the same local
   currency.
4. The shopping list is shown both as a **full tab** and as a **compact strip on Home**.
5. CSV export is a **ZIP of per-table CSVs**, plus an optional flat `purchases.csv`.
6. The FX indicator has **no dead-band**. Any non-zero change shows ▲ or ▼ (§9).
7. Phone-to-phone transfer is **QR only**, using "i of N" frames (§8.5).

8. The app is named **Parity**. It uses **Kotlin Multiplatform**, targeting Android now and iOS later.
9. Distribution is **sideloading for Phase 1**, then **Google Play**, then the **App Store**. Google
   dependencies such as ML Kit are acceptable.

**Still open**
- **Package ID** (`applicationId` / bundle ID). The Phase 1 build uses the placeholder `app.parity`;
  choose the final ID before the first Play Store upload, after which it can never change.
- **Real-device testing:** the emulator verified the whole flow, including Georgian OCR on synthetic
  tags; real shelf photos (glare, angles, store fonts) still need testing on a phone.

---

## 20. As built (Phase 1, 2026-09-27)

Where the Phase 1 build differs from the sections above, and why. PROGRESS.md tracks status.

| Area | Design said | Built | Why |
|---|---|---|---|
| QR payload encoding (§8.5) | CBOR + zstd with a dictionary | JSON + DEFLATE, frames in Base45 (QR alphanumeric mode), 700-byte chunks → QR version 22 | Standard platform zlib; still ~4× smaller than raw. Verified with a real QR encode/decode round-trip test |
| QR backup encryption (§8.5) | 4-word passphrase, Argon2id | 12-character code (Crockford Base32, 60 bits), PBKDF2-SHA256 × 310,000 + AES-256-GCM | Uses built-in platform crypto; no wordlist needed; higher entropy |
| Classifier (§8.2) | Lexicon + on-device embeddings + overrides | Lexicon (phrases beat words) + user overrides | Embeddings deferred; the lexicon covers the design example exactly |
| Scanning (§6) | Camera only | Camera **and "scan a photo"** from the gallery | Lets tags be read from photos later, and made Georgian OCR testable on the emulator |
| Name OCR (§6.2, §14) | Tesseract for unsupported scripts | Tesseract re-reads the **exact frame** the price locked on (frames carry IDs); lines are picked by script; ML Kit's gibberish for non-Latin scripts is never shown | Found in testing: the live camera had moved on before Tesseract ran |
| Rates (§12) | Full precision | Cross rates rounded to 12 significant digits; display shows up to 8–10 | Providers publish 6–10 digits; 34-digit cross rates were false precision |
| API keys (§16) | Android Keystore | Stored in the app database, never exported | Phase 1 simplification; only optional providers need keys |
| Packaging | — | One APK per CPU type (arm64 release ≈ 59 MB) | ML Kit translation/OCR and Tesseract native libraries dominate size |
| Analytics (§10) | Charts | Phase 1 summary: currency vs local per pair, and per-product change split into shelf price and currency effect | Full charts are M5, after Phase 1; all data is already recorded |
