# ThermalPhoto

Print any photo from your gallery on a thermal receipt printer.

Pick a picture, see exactly how it will come out — real dots, real paper width, live — and print
it. Printers are connected once and remembered, so from then on printing a photo is one tap.

Kotlin, Jetpack Compose, Material 3, Hilt, coroutines. Two Gradle modules:

| Module     | What is in it                                                                        |
|------------|--------------------------------------------------------------------------------------|
| `:app`     | UI, view models, use cases, persistence, DI                                            |
| `:printer` | Everything printer: domain contracts, drivers, discovery, transports, engine, raster   |

`:printer` depends on nothing but AndroidX core, coroutines and the vendor SDKs. It never depends
on `:app`. The rasterizer lives in `:printer`, which is what makes the on-screen preview a true
WYSIWYG: the preview screen and the driver call the exact same code.

---

## Two ways in, and neither blocks the other

* **Set up first** — Printers → add → discover → connect → test → saved as default.
* **Just print** — Home → pick photo → preview → Print. If a printer is already saved, it prints
  immediately. If none is, the preview still works and offers an inline printer picker that hands
  you straight back to your photo.

Photo selection is never blocked on printer setup. The printer connection is an app-level resource,
not a step in a wizard: you can add, rename, re-order, test, reconnect and switch printers at any
moment, including from inside the preview screen via the printer chip in the top bar.

---

## Supported printers

| Family                   | Transports              | SDK                                     | Bundled?                          |
|--------------------------|-------------------------|-----------------------------------------|-----------------------------------|
| **Generic ESC/POS**      | LAN · Bluetooth · USB   | none — raw `GS v 0`                     | always                            |
| **Star Micronics**       | LAN · Bluetooth · USB   | `com.starmicronics:stario10`            | always (needs Android 8.0+)       |
| **Sunmi built-in**       | built-in                | `com.sunmi:printerlibrary`              | always (Sunmi hardware only)      |
| **Sunmi external BT**    | Bluetooth               | `com.sunmi:external-printerlibrary`     | always                            |
| **Sunmi external LAN/USB** | LAN · USB             | `com.sunmi:external-printerlibrary2`    | always                            |
| **Epson TM series**      | LAN · Bluetooth · USB   | `ePOS2.jar` + `libepos2.so`             | only if dropped into `printer/libs/` |
| **Volcora / Xprinter**   | LAN · Bluetooth · USB   | `printer-lib-3.2.0.aar`                 | only if dropped into `printer/libs/` |
| **Volcora V2 / SPRT**    | LAN · Bluetooth · USB   | `printersdkv5.7.2.jar`                  | only if dropped into `printer/libs/` |
| **Landi built-in**       | built-in                | `xsuite-omnidriver-api-*.aar`           | only if dropped into `printer/libs/` |
| **Dejavoo / Kozen**      | built-in                | `peripheral_v1.0.aar`                   | only if dropped into `printer/libs/` |

The generic ESC/POS driver is the workhorse and drives most third-party thermal printers on the
market with no vendor SDK at all. Vendor drivers exist only where the hardware genuinely cannot be
reached over a socket (built-in heads behind a system service) or where the vendor SDK does
something meaningfully better.

### The five optional SDKs

**The project builds and runs with `printer/libs/` completely empty.** Nothing is stubbed: a family
whose SDK is absent is simply not compiled, is listed greyed out in the add-printer screen with the
reason, and reports the error code `PRN-ROUTE-SDK-ABSENT` if a saved printer still points at it.

Drop the artifacts into `printer/libs/` to light them up — see
[`printer/libs/README.md`](printer/libs/README.md) for exact filenames, the Epson native libraries,
and how to verify a family is enabled. The wiring is in `printer/build.gradle.kts`: per SDK, it
checks the file exists and only then adds **both** the dependency **and** the source directory
containing the driver written against it. That is why nothing under `printer/src/main` may import
`com.epson.*`, `net.posprinter.*`, `com.printer.sdk.*`, `com.sdksuite.*` or `com.denovo.*` — those
imports live exclusively under `printer/src/optional/<family>/java`, and each family hands itself to
`PrinterDriverRegistry` by name through reflection.

---

## Paper widths

Thermal heads are 203 dpi, i.e. exactly 8 dots per millimetre.

| Profile          | Dots | Graphics | Cutter | Notes                                          |
|------------------|------|----------|--------|------------------------------------------------|
| `58mm`           | 384  | yes      | yes    | Small and portable printers                    |
| `80mm`           | 576  | yes      | yes    | Standard desktop receipt head, global fallback |
| `pat`            | 384  | yes      | no     | Dejavoo / Kozen built-in, tear off by hand     |
| `76mm`           | 0    | **no**   | yes    | Impact / dot-matrix — cannot print images      |
| `custom-<dots>`  | any  | yes      | yes    | User-entered width, floored to a multiple of 8 |

`PaperWidthResolver` maps a model string onto a profile through an **ordered** table, first match
wins. The ordering is part of the contract, and several rows exist only to shadow a broader one
below them:

* `T2 Mini` (58 mm) must be tested before the generic Sunmi `T2` row (80 mm);
* `V2s Plus` (80 mm) must be tested before `V2s` (58 mm);
* the specific Star 58 mm models must be tested before the catch-all `star` row;
* `P3 Mix` ships with either width and defaults to the **narrower** 58 mm, because narrow content
  prints fine on a wide head while wide content is clipped on a narrow one.

112 mm wide-format heads (TUP900, SM-T400i) are explicitly unsupported: the resolver falls back to
80 mm and warns rather than silently producing a clipped print. An impact head resolves to `76mm`,
which disables the Print button with "This printer cannot print images".

The resolution is only ever a **default**. The user can override the width per printer and per job,
and the override is persisted.

---

## How the image pipeline works

`:printer/raster`, pure and deterministic. The heavy per-pixel work lives in `RasterCore`, which has
no Android dependency at all — no `Bitmap`, no `Context`, no coroutines — so it is unit-tested on a
plain host JVM. `RasterPipeline` owns only the parts that genuinely need Android.

1. **Decode, stubbornly.** `PhotoDecoder` runs a LADDER rather than a single attempt, because
   `ImageDecoder` refuses a whole class of perfectly good files (some HEIC/HEIF encodings, HDR and
   gainmap JPEGs from recent phone cameras, images from providers that cannot supply a seekable
   descriptor) with `DecodeException: unimplemented`. The rungs are `ImageDecoder` →
   `BitmapFactory` over a stream → `BitmapFactory` over a file descriptor → `BitmapFactory` at
   RGB_565; if all four fail, the sample size doubles and the whole ladder runs again, up to 64×.
   Because the output is dithered to one bit per dot, dropping to a smaller sample size or to
   16-bit colour costs the printed result essentially nothing — so trading quality for "it opened"
   is always the right trade. EXIF orientation is applied from the file itself via the framework
   `ExifInterface` (no extra dependency) for the rungs that do not handle it. Never decodes larger
   than 4 × the paper width on the long edge, so a 50 MP photo never allocates full size.
2. **Rotate and flip.** Includes the optional auto-turn for wide photos, which is offered as a
   visible toggle and never applied as a silent surprise.
3. **Crop.** A user crop in normalized coordinates, then the fit mode.
4. **Resize.** The target width is **floored to a multiple of 8** — `GS v 0` is byte-packed per row
   and a non-multiple-of-8 width shears the image diagonally down the page — and **never exceeds
   the source resolution**, because upscaling only prints bigger, blurrier dots.
5. **Tone.** Grayscale → brightness → contrast → gamma → invert, as a single 256-entry lookup table
   applied once per pixel. No per-pixel `pow`, no intermediate bitmaps.
6. **Dither to 1 bit.** Floyd–Steinberg (default, best for photos), Atkinson (lighter, good on
   faces), 8×8 ordered Bayer (even texture, good for graphics), hard threshold (line art), and a
   grey preview mode that never reaches a printer.
7. **Pack.** MSB-first, `(width + 7) / 8` bytes per row, a set bit meaning a black dot.
8. **Band.** The raster is split into bands of 192 rows. A full-page photo is hundreds of times
   taller than a receipt logo and **will** overflow a thermal head's input buffer if pushed in one
   command, so every driver emits one command per band and flushes between them. The split is
   exact: no overlap, no gap, every row printed once. It is also where the progress bar comes from.

### "Darkness" is honest

There is no portable ESC/POS density command, so this app does not pretend there is one. Darkness
is implemented in the **image domain** as a gamma curve, and mapped onto a real vendor parameter
only where one exists: Epson's `addImage(..., brightness, ...)`. Everywhere else the dots you see
in the preview are exactly the dots that get sent.

Note the direction: the curve is `255 * (v/255)^(1/gamma)`, so a *larger* gamma prints *lighter*.
The Darkness slider is presented the intuitive way round (right is darker) and inverts on the way
in.

### The generic ESC/POS vocabulary

The whole command set this app emits, and nothing else:

```
ESC @        1B 40           initialize
ESC a n      1B 61 n         alignment: 0 left, 1 centre, 2 right
ESC d n      1B 64 n         feed n lines
GS V 1       1D 56 01        partial cut
DLE EOT n    10 04 n         real-time status (1 printer, 2 offline cause, 4 paper sensor)
GS v 0       1D 76 30 00 xL xH yL yH <bytes>   raster band, little-endian, MSB-first, 1 = black
```

A head that does not answer `DLE EOT` within 3 seconds is treated as "this model has no status
support" and the job proceeds optimistically. That is documented behaviour for much of this
hardware, not a fault.

---

## Reliability: the print engine

`PrintEngine` runs **RESOLVE → BREAKER → LOCK → CONNECT → SEND → FINALIZE**. Nearly every rule in it
exists because of a specific way real printers fail:

* **Resolve is bounded and runs first.** Routing may enumerate USB devices, so an unbounded hang
  there would sit outside every protection the engine has.
* **One mutex per physical printer**, keyed by the driver's live identity where it can supply one
  (USB device paths go stale across a replug), so two saved entries pointing at one unit serialize
  on one lock.
* **The job timeout lives INSIDE the lock.** Outside it, a timeout would expire only the waiters
  while the stuck holder kept the mutex for ever, and the queue would never recover.
* **A circuit breaker per printer**, 15 s cooldown, with exactly one probe allowed through when it
  half-opens. Its bookkeeping is total: every admitted attempt resolves through `onSuccess`,
  `onFailure` **or `releaseProbe`**. A leaked probe token is the classic "printer dead until I
  force-quit the app" bug, and there is a test that reproduces it.
* **Clean versus dirty failures.** A cached connection that died reports failure before anything
  reached paper; that is *clean*, and the engine reconnects and re-sends once, transparently. The
  moment any content is committed the failure is *dirty*, and it is never re-sent, because a
  doubled print is worse than a clear failure the user can retry themselves.
* **`forceClose()` is synchronous and non-suspending.** `withTimeout` cancels a coroutine but cannot
  interrupt a thread blocked in non-interruptible socket I/O; closing the socket from outside is the
  only thing that frees it. The engine force-closes on abort — and deliberately *not* on the happy
  path, where it could cut off a print that is still flushing.
* **Cancellation is the user's choice, never a verdict on the printer:** it releases the probe,
  drops the socket, and re-throws without emitting a result.

Every attempt is recorded as one structured log line — stage transitions, connect latency, status
tokens, vendor codes — and the last 50 are kept in memory for the **Diagnostics** screen (long-press
the version in Settings), with copy-to-clipboard.

---

## Error codes

Every failure has a stable `PRN-…` code, shown in small monospace text under the message and used
verbatim in logs. Codes are stored constants, never derived from enum names, so a Kotlin rename can
never shift a code that has already shipped.

| Stage        | Codes                                                                                                                                                                                        |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Image        | `PRN-IMG-DECODE-FAILED` · `PRN-IMG-TOO-LARGE` · `PRN-IMG-EMPTY`                                                                                                                              |
| Routing      | `PRN-ROUTE-NO-PRINTER` · `PRN-ROUTE-NO-DRIVER` · `PRN-ROUTE-MISSING-ADDRESS` · `PRN-ROUTE-SDK-ABSENT`                                                                                         |
| Permission   | `PRN-PERM-BT-DENIED` · `PRN-PERM-USB-DENIED` · `PRN-PERM-MEDIA-DENIED`                                                                                                                       |
| Connection   | `PRN-CONN-BT-OFF` · `PRN-CONN-BT-NOT-PAIRED` · `PRN-CONN-BT-PAIRING-FAILED` · `PRN-CONN-BT-PAIRING-TIMEOUT` · `PRN-CONN-BT-UNREACHABLE` · `PRN-CONN-LAN-UNREACHABLE` · `PRN-CONN-LAN-TIMEOUT` · `PRN-CONN-USB-NOT-ENUMERATED` · `PRN-CONN-SERVICE-NOT-BOUND` · `PRN-CONN-TIMEOUT` · `PRN-CONN-FAILED` |
| Send         | `PRN-SEND-TIMEOUT` · `PRN-SEND-DISCONNECTED-MID-PRINT` · `PRN-SEND-UNPRINTABLE` · `PRN-SEND-PARTIAL` · `PRN-SEND-FAILED`                                                                      |
| Status       | `PRN-STATUS-PAPER-OUT` · `PRN-STATUS-PAPER-LOW` · `PRN-STATUS-PAPER-JAM` · `PRN-STATUS-COVER-OPEN` · `PRN-STATUS-OFFLINE` · `PRN-STATUS-OVERHEAT` · `PRN-STATUS-CUTTER-ERROR` · `PRN-STATUS-BATTERY-LOW` · `PRN-STATUS-BUSY` |
| Post-process | `PRN-POST-DISCONNECT-FAILED`                                                                                                                                                                 |
| Unknown      | `PRN-UNKNOWN`                                                                                                                                                                                |

Each maps to a title, a plain-language explanation and a concrete recovery action ("Load paper and
tap Retry", "Turn on Bluetooth"). `PrintErrorCatalogTest` enforces that the mapping is total, that
no two categories share a code, and that no two read identically.

---

## Permissions

**Nothing is requested at launch.**

* The **system photo picker** is the primary way photos are chosen and needs no runtime permission
  on any API level.
* `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` / `READ_EXTERNAL_STORAGE` are requested
  only if the user opens the optional in-app album browser. Android 14 partial access is handled:
  the app shows what it can see and offers a way to change the selection.
* `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` are requested only when the user chooses Bluetooth in the
  add-printer flow.
* USB permission is acquired by the app itself, before any vendor SDK connect, because the SDKs
  request it with an unflagged receiver and a mutable implicit `PendingIntent` that Android 14+
  rejects outright.

No `MANAGE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES`, no foreground service, no analytics, no
crash SDK, no login, no network beyond the printer socket itself.

---

## Building

```bash
./gradlew assembleDebug        # builds the APK
./gradlew testDebugUnitTest    # runs the host-JVM tests
```

Toolchain, unchanged from the project template except where noted:

| | |
|---|---|
| AGP | 9.2.1 |
| Gradle | 9.4.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| `minSdk` | 24 |
| `targetSdk` | 36 |
| `compileSdk` | **37** |

Two deliberate deviations from the generated template, both forced:

* **`compileSdk` was raised from 36.1 to 37.** The AndroidX artifacts pinned by Compose BOM
  2026.02.01 (`core` 1.19.0, `lifecycle` 2.11.0, ...) refuse to be consumed by a project compiling
  against an older platform, so the template as generated does not build at all.
* **`android.disallowKotlinSourceSets=false`** in `gradle.properties`. AGP 9's built-in Kotlin
  support rejects plugins that register sources through the `kotlin.sourceSets` DSL, which is how
  KSP (used by Hilt) registers its generated output. This is the escape hatch AGP itself points at.

The StarIO10 SDK declares `minSdk 26` while this app supports 24, so `:printer`'s manifest overrides
the merge and every Star code path is gated behind an explicit runtime API check. On API 24/25 the
Star family reports itself unavailable rather than crashing on class load.

---

## Tests

Host-JVM only; everything that needs hardware is behind an interface.

| Suite | What it pins down |
|---|---|
| `PaperWidthResolverTest` | every rule row, the ordering traps, wide-format fallback, host-device fallback |
| `RasterCoreTest` | multiple-of-8 widths, no upscaling, `bytesPerRow`, exact bit packing, banding covers every row once, Floyd–Steinberg determinism, threshold exactness |
| `PhotoDecoderTest` | the decode ladder's sizing: power-of-two sample sizes, the conservative guess when bounds are unknown, orientation-independence, no divide-by-zero on nonsense dimensions |
| `EscPosEncoderTest` | `GS v 0` header bytes, little-endian `xL/xH` and `yL/yH`, status reply decoding |
| `PrinterCircuitBreakerTest` | CLOSED → OPEN → HALF_OPEN with an injected clock, single-probe CAS, and the leaked-token bug `releaseProbe` prevents |
| `PrintEngineTest` | clean-fail retries exactly once, dirty failures never retry, timeouts force-close, cancellation re-throws and emits nothing, exactly one result on every other path |
| `PrinterRouterTest` | routing per family and transport, USB VID overriding a saved brand, absent SDK → `Unroutable(SDK_NOT_BUNDLED)` |
| `PrintErrorCatalogTest` | the error catalog is total, unique and unambiguous |

---

## On-device test checklist

Per family, the things that only fail on real hardware:

**Generic ESC/POS (LAN)** — print at 58 mm and 80 mm and check the edges are square, not sheared
(a shear means the width is not a multiple of 8). Pull the network cable mid-print: expect
`PRN-SEND-PARTIAL`, and expect the app **not** to reprint the whole photo on retry. Power-cycle the
printer, then print again immediately: the stale cached socket should fail *clean* and the engine
should reconnect and print once, with no user-visible error.

**Generic ESC/POS (Bluetooth)** — unpair the printer in system settings and print: expect
`PRN-CONN-BT-NOT-PAIRED`, not a silent success. Turn Bluetooth off and print: expect
`PRN-CONN-BT-OFF`. Deny the runtime permission: expect `PRN-PERM-BT-DENIED`.

**Generic ESC/POS (USB)** — unplug and replug between prints; the saved device path changes and the
driver must re-resolve it. Deny the USB permission dialog once: expect `PRN-PERM-USB-DENIED` and a
working retry.

**Star** — on API 24/25 confirm the family is greyed out rather than crashing. Print two jobs at
once from two screens and confirm they serialize instead of throwing `StarIO10InUseException`. Open
the paper cover mid-job: the generic "unprintable" refusal must be refined into
`PRN-STATUS-COVER-OPEN`.

**Sunmi built-in** — test on both firmware families: one reports the commit through `onRunResult`
and the other only through `onPrintResult`, and both must complete. Cancel mid-print and confirm no
partial slip is emitted (the buffer is discarded, not committed). Run the printer out of paper and
confirm the failure is refined to `PRN-STATUS-PAPER-OUT`.

**Sunmi external Bluetooth** — switch between two Sunmi printers back to back; the 300 ms settle is
what stops the second connect failing.

**Sunmi external LAN / USB** — confirm success is only reported after the result callback fires, not
when the print call returns. For USB, confirm the printer is found through the SDK's own search
(the USB constructors are package-private, so there is no other way).

**Epson** — replug a USB printer and confirm the cached `USB:` target is dropped and re-resolved.
Start and stop discovery repeatedly and confirm `Discovery.stop()` retries through `ERR_PROCESSING`
instead of leaving a scan running.

**Volcora** — pull the cable and print: the pre-print probe should fail *clean* (nothing on paper,
transparent retry), while a failure after the first band must be reported dirty. Confirm a genuine
Epson or Star plugged in over USB is **not** offered under the Volcora brand.

**Volcora V2 / SPRT** — confirm two jobs never overlap (the SDK singleton allows exactly one
connection process-wide) and that a negative return from a write stops the job immediately rather
than timing out.

**Landi / Dejavoo** — built-in heads only. On Dejavoo confirm images actually print, which depends
on `src` being an XML **attribute** and on `Base64.NO_WRAP`; a wrapped payload decodes to garbage.

---

## What this app deliberately does not do

No receipts, orders, payments or any POS concept. No cloud printing, no vendor cloud API, no HTTP
printing, no API keys. No invented ESC/POS commands beyond the six listed above. No orientation
locking. No analytics, crash SDK, ads or login. No hardcoded IPs, MACs, credentials or serials
anywhere in the source.
