package com.rahul.imager.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.DitherMode
import com.rahul.imager.ui.components.CodeText
import com.rahul.imager.ui.components.DetailRow
import com.rahul.imager.ui.components.DitherSwatch
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.ErrorPanel
import com.rahul.imager.ui.components.Histogram
import com.rahul.imager.ui.components.InlineNotice
import com.rahul.imager.ui.components.LabeledSlider
import com.rahul.imager.ui.components.PrinterStatusChip
import com.rahul.imager.ui.components.SectionHeader
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.SoftDivider
import com.rahul.imager.ui.theme.ImagerTheme
import kotlin.math.exp

/**
 * Design-time previews.
 *
 * The screens themselves need a `ViewModel` and cannot be previewed directly, so the previews here
 * cover the building blocks the screens are made of — which is where layout actually breaks. Each
 * one renders at 320 dp (the narrowest phone this app supports) and at 1280 dp (a tablet in
 * landscape), plus in dark mode, because those are the three cases that regress silently.
 */

private val samplePrinter = SavedPrinter(
    id = "preview",
    displayName = "Kitchen TSP143",
    brand = PrinterBrand.STAR,
    transport = TransportType.LAN,
    identifier = "192.168.1.50",
    paperProfileId = PaperProfiles.STANDARD_80MM.id,
    model = "TSP143IIIW",
    isDefault = true,
)

/** A plausible histogram: a broad midtone hump, like a real photograph. */
private val sampleHistogram = IntArray(256) { level ->
    val x = (level - 128) / 48f
    (900 * exp(-0.5 * x * x)).toInt() + 20
}

@PreviewScreenSizes
@Preview(name = "Narrow 320dp", widthDp = 320, showBackground = true)
@Preview(name = "Wide 1280dp", widthDp = 1280, showBackground = true)
@Preview(
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 360,
    showBackground = true,
)
@Composable
private fun ComponentGalleryPreview() {
    ImagerTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Printer")
                PrinterStatusChip(
                    printer = samplePrinter,
                    state = ConnectionState.Connected(0L),
                    onClick = {},
                )
                PrinterStatusChip(
                    printer = null,
                    state = ConnectionState.Disconnected,
                    onClick = {},
                )

                SoftDivider()

                SectionHeader("Options")
                SegmentedOptionRow(
                    options = listOf(
                        SegmentedOption("58mm", "58 mm"),
                        SegmentedOption("80mm", "80 mm"),
                        SegmentedOption("custom", "Custom"),
                    ),
                    selected = "80mm",
                    onSelect = {},
                )
                LabeledSlider(
                    label = "Darkness",
                    value = 1.4f,
                    valueRange = 0.4f..2.5f,
                    valueLabel = "52 %",
                    onValueChange = {},
                )
                Histogram(counts = sampleHistogram, threshold = 128)

                SoftDivider()

                DitherSwatch(
                    label = "Floyd–Steinberg",
                    description = "Best for photos and faces",
                    swatch = null,
                    selected = true,
                    onClick = {},
                )
                DitherSwatch(
                    label = "Hard cut",
                    description = "For line art, logos and text",
                    swatch = null,
                    selected = false,
                    onClick = {},
                )

                SoftDivider()

                DetailRow(label = "Model", value = "TSP143IIIW")
                DetailRow(label = "Address", value = "192.168.1.50:9100")
                CodeText(code = "PRN-STATUS-PAPER-OUT")

                InlineNotice(text = "This photo is wide. It has been turned 90 degrees.")
            }
        }
    }
}

@Preview(name = "Error narrow", widthDp = 320, showBackground = true)
@Preview(name = "Error wide", widthDp = 900, showBackground = true)
@Preview(
    name = "Error dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 360,
    showBackground = true,
)
@Composable
private fun ErrorPanelPreview() {
    ImagerTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorPanel(
                    error = PrintError(
                        category = PrintCategory.PAPER_OUT,
                        context = PrinterContext(
                            brand = PrinterBrand.STAR,
                            transport = TransportType.LAN,
                            identifier = "192.168.1.50",
                            vendorErrorCode = "paper_empty",
                        ),
                    ),
                    actions = { TextButton(onClick = {}) { Text("Retry") } },
                )
                ErrorPanel(
                    error = PrintError(category = PrintCategory.SDK_NOT_BUNDLED),
                )
            }
        }
    }
}

@Preview(name = "Empty narrow", widthDp = 320, heightDp = 400, showBackground = true)
@Preview(name = "Empty wide", widthDp = 1280, heightDp = 400, showBackground = true)
@Composable
private fun EmptyStatePreview() {
    ImagerTheme(dynamicColor = false) {
        Surface {
            EmptyState(
                icon = Icons.Default.Print,
                title = "No printers yet",
                message = "Add a printer once and it stays connected, ready for every photo " +
                    "you print.",
                action = { TextButton(onClick = {}) { Text("Add printer") } },
            )
        }
    }
}

/** Every dither mode side by side, so the labels can be checked for truncation. */
@Preview(name = "Dither modes", widthDp = 320, showBackground = true)
@Composable
private fun DitherModesPreview() {
    ImagerTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DitherMode.entries.forEach { mode ->
                    DitherSwatch(
                        label = mode.name,
                        description = "Preview of ${mode.name.lowercase()}",
                        swatch = null,
                        selected = mode == DitherMode.FLOYD_STEINBERG,
                        onClick = {},
                    )
                }
            }
        }
    }
}
