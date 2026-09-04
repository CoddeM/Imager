# Optional vendor SDKs

Drop the artifacts below into **this directory** (`printer/libs/`) to enable the matching driver
family. Nothing here is required: the project compiles, installs and runs with this directory
completely empty, and any family whose artifact is missing simply reports the error code
`PRN-ROUTE-SDK-ABSENT` and is shown greyed out in the add-printer screen.

The wiring lives in `printer/build.gradle.kts` (`optionalSdk(...)`). For each artifact it checks
whether the file exists and, only then, adds **both** the dependency **and** the source directory
that contains the driver written against it. That is why no file under `printer/src/main` may ever
import a vendor package.

| File to drop here                              | Unlocks                                   | SDK package                            | Source directory                    |
|------------------------------------------------|-------------------------------------------|----------------------------------------|-------------------------------------|
| `ePOS2.jar` (+ native libs, see below)          | Epson TM series — LAN / Bluetooth / USB   | `com.epson.epos2.*`                    | `src/optional/epson/java`           |
| `ePOSEasySelect.jar` *(optional companion)*     | Epson helper, additive only               | `com.epson.eposeasyselect.*`           | —                                   |
| `printer-lib-3.2.0.aar`                         | Volcora / Xprinter-OEM — LAN / BT / USB   | `net.posprinter.*`                     | `src/optional/volcora/java`         |
| `printersdkv5.7.2.jar`                          | Volcora V2 / SPRT — LAN / BT / USB        | `com.printer.sdk.*`                    | `src/optional/volcora_v2/java`      |
| `xsuite-omnidriver-api-0.2.0+240924.aar`        | Landi built-in printer                    | `com.sdksuite.omnidriver.*`            | `src/optional/landi/java`           |
| `peripheral_v1.0.aar`                           | Dejavoo / Kozen P8 + P18 built-in         | `com.denovo.app.invokekozen.printer.*` | `src/optional/dejavoo/java`         |

The Landi filename is matched **exactly**, including the version. If your copy is named
differently, either rename it or update the `optionalSdk(...)` call in `printer/build.gradle.kts`.

## Epson native libraries

ePOS2 needs its JNI library alongside the jar. Copy `libepos2.so` into each ABI directory:

```
printer/src/main/jniLibs/arm64-v8a/libepos2.so
printer/src/main/jniLibs/armeabi-v7a/libepos2.so
printer/src/main/jniLibs/x86/libepos2.so
printer/src/main/jniLibs/x86_64/libepos2.so
```

The module's `packaging { jniLibs { pickFirsts += "**/*.so" } }` block keeps a duplicate `.so`
coming from another AAR from breaking the build.

## Verifying a family is enabled

Gradle logs one line per artifact at configuration time:

```
:printer — optional SDK enabled: ePOS2.jar
:printer — optional SDK ABSENT, driver family disabled: peripheral_v1.0.aar
```

At runtime `PrinterDriverRegistry` logs the same conclusion under the tag
`PrinterDriverRegistry`, and the add-printer screen shows unavailable families greyed out with the
artifact name as the reason.

## A note on the two built-in-terminal SDKs

The Landi (`com.sdksuite.omnidriver`) and Dejavoo (`com.denovo.app.invokekozen.printer`) drivers
were written against the API described in the vendor integration notes rather than against a copy
of the jar. If your build of either artifact packages its classes differently, the **imports at the
top of the driver file** are the only thing that needs adjusting — the rest of the driver, and
every other family, is unaffected. Both files call this out in their class KDoc.

## Licensing

These SDKs are redistributed under their own vendor licences and are deliberately **not** checked
into this repository.
