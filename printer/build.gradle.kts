plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * One vendor SDK that ships only as a local artifact.
 *
 * @param fileName the exact file expected in `printer/libs/`.
 * @param sourceDir the family's source directory under `src/optional/`, which is added to the
 *   build ONLY when the artifact is present.
 */
data class OptionalSdk(val fileName: String, val sourceDir: String)

/**
 * Conditional wiring for the five vendor SDKs that are distributed only as local artifacts.
 *
 * For each SDK the artifact is looked for in `printer/libs/`. Only when it is present do we
 * (a) add it to the compile classpath and (b) add the source directory containing the driver
 * written against it. When it is absent BOTH are skipped, so nothing in the build ever references
 * the missing classes and the module compiles cleanly — the corresponding driver family simply
 * reports `PRN-ROUTE-SDK-ABSENT` at runtime.
 *
 * This is why no file under `src/main` may import `com.epson.*`, `net.posprinter.*`,
 * `com.printer.sdk.*`, `com.sdksuite.*` or `com.denovo.*`: those imports live exclusively under
 * `src/optional/<family>/java`, and the families hand themselves to the runtime registry through
 * reflection (see PrinterDriverRegistry).
 */
val optionalSdks = listOf(
    OptionalSdk("ePOS2.jar", "epson"),
    OptionalSdk("printer-lib-3.2.0.aar", "volcora"),
    OptionalSdk("printersdkv5.7.2.jar", "volcora_v2"),
    OptionalSdk("xsuite-omnidriver-api-0.2.0+240924.aar", "landi"),
    OptionalSdk("peripheral_v1.0.aar", "dejavoo"),
)

val enabledSdks = optionalSdks.filter { file("libs/${it.fileName}").exists() }

optionalSdks.forEach { sdk ->
    if (sdk in enabledSdks) {
        logger.lifecycle(":printer - optional SDK enabled: ${sdk.fileName}")
    } else {
        logger.lifecycle(":printer - optional SDK ABSENT, driver family disabled: ${sdk.fileName}")
    }
}

android {
    namespace = "com.rahul.imager.printer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            enabledSdks.forEach { sdk ->
                val directory = "src/optional/${sdk.sourceDir}/java"
                // AGP's built-in Kotlin support compiles the `kotlin` source set; registering the
                // directory with both keeps the wiring correct whichever compiler picks it up.
                kotlin.directories.add(directory)
                java.directories.add(directory)
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += "**/*.so" }
    }
}

dependencies {
    enabledSdks.forEach { sdk -> implementation(files("libs/${sdk.fileName}")) }

    // Epson ships a second, optional helper jar alongside ePOS2.jar. It is additive only.
    file("libs/ePOSEasySelect.jar").let { if (it.exists()) implementation(files(it)) }

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Vendor SDKs published to Maven Central — always on the classpath, no local files needed.
    implementation(libs.star.io10) {
        exclude(group = "com.android.support", module = "support-v4")
    }
    implementation(libs.sunmi.inner.printer)
    implementation(libs.sunmi.external.printer)
    implementation(libs.sunmi.external.printer2)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
