package com.rahul.imager.ui.screens.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.rahul.imager.R
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.raster.Alignment as RasterAlignment
import com.rahul.imager.printer.raster.CropRect
import com.rahul.imager.printer.raster.DitherMode
import com.rahul.imager.printer.raster.FitMode
import com.rahul.imager.printer.raster.GrayscaleMode
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.PrintPreset
import com.rahul.imager.ui.components.CropOverlay
import com.rahul.imager.ui.components.DitherSwatch
import com.rahul.imager.ui.components.Histogram
import com.rahul.imager.ui.components.LabeledSlider
import com.rahul.imager.ui.components.OptionsContentPadding
import com.rahul.imager.ui.components.SectionHeader
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.SoftDivider
import kotlin.math.roundToInt

/** The four groups of print options. */
enum class OptionsTab(val labelRes: Int) {
    SIZE(R.string.tab_size),
    CROP(R.string.tab_crop),
    TONE(R.string.tab_tone),
    QUALITY(R.string.tab_quality),
}

/** Renders whichever options tab is selected. */
@Composable
fun OptionsPanel(
    tab: OptionsTab,
    state: PreviewUiState,
    viewModel: PreviewViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(OptionsContentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (tab) {
            OptionsTab.SIZE -> SizeOptions(state, viewModel)
            OptionsTab.CROP -> CropOptions(state, viewModel)
            OptionsTab.TONE -> ToneOptions(state, viewModel)
            OptionsTab.QUALITY -> QualityOptions(state, viewModel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// -------------------------------------------------------------------------------------------
// Size
// -------------------------------------------------------------------------------------------

@Composable
private fun SizeOptions(state: PreviewUiState, viewModel: PreviewViewModel) {
    val options = state.options

    SectionHeader(stringResource(R.string.option_fit_mode))
    SegmentedOptionRow(
        options = listOf(
            SegmentedOption(FitMode.FIT_WIDTH, stringResource(R.string.fit_width)),
            SegmentedOption(FitMode.FIT_WHOLE, stringResource(R.string.fit_whole)),
            SegmentedOption(FitMode.FILL_CROP, stringResource(R.string.fit_fill_crop)),
            SegmentedOption(FitMode.ORIGINAL_CAPPED, stringResource(R.string.fit_original)),
        ),
        selected = options.fitMode,
        onSelect = { mode -> viewModel.updateOptions { it.copy(fitMode = mode) } },
    )

    LabeledSlider(
        label = stringResource(R.string.option_scale),
        value = options.scalePercent.toFloat(),
        valueRange = PrintOptions.MIN_SCALE_PERCENT.toFloat()..
            PrintOptions.MAX_SCALE_PERCENT.toFloat(),
        valueLabel = "${options.scalePercent} %",
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(scalePercent = value.roundToInt()) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    SoftDivider()
    SectionHeader(stringResource(R.string.option_paper_width))
    PaperWidthSelector(
        current = state.paper,
        onSelect = viewModel::setPaperProfile,
    )

    LabeledSlider(
        label = stringResource(R.string.option_margin),
        value = options.marginDots.toFloat(),
        valueRange = 0f..MAX_MARGIN_DOTS,
        valueLabel = "${options.marginDots}",
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(marginDots = value.roundToInt()) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    SectionHeader(stringResource(R.string.option_alignment))
    SegmentedOptionRow(
        options = listOf(
            SegmentedOption(RasterAlignment.LEFT, stringResource(R.string.align_left)),
            SegmentedOption(RasterAlignment.CENTER, stringResource(R.string.align_center)),
            SegmentedOption(RasterAlignment.RIGHT, stringResource(R.string.align_right)),
        ),
        selected = options.alignment,
        onSelect = { alignment -> viewModel.updateOptions { it.copy(alignment = alignment) } },
    )

    SoftDivider()

    LabeledSlider(
        label = stringResource(R.string.option_copies),
        value = options.copies.toFloat(),
        valueRange = 1f..PrintOptions.MAX_COPIES.toFloat(),
        steps = PrintOptions.MAX_COPIES - 2,
        valueLabel = "${options.copies}",
        onValueChange = { value -> viewModel.updateOptions { it.copy(copies = value.roundToInt()) } },
    )

    LabeledSlider(
        label = stringResource(R.string.option_feed_lines),
        value = options.feedLinesAfter.toFloat(),
        valueRange = 0f..PrintOptions.MAX_FEED_LINES.toFloat(),
        valueLabel = "${options.feedLinesAfter}",
        onValueChange = { value ->
            viewModel.updateOptions { it.copy(feedLinesAfter = value.roundToInt()) }
        },
    )

    ToggleRow(
        label = stringResource(R.string.option_cut),
        checked = options.cutAfter,
        enabled = state.paper.supportsCutter,
        onCheckedChange = { checked -> viewModel.updateOptions { it.copy(cutAfter = checked) } },
    )
}

/** The paper width picker, including a free-form custom width in dots. */
@Composable
private fun PaperWidthSelector(
    current: PaperProfile,
    onSelect: (PaperProfile) -> Unit,
) {
    var customText by remember { mutableStateOf("") }

    SegmentedOptionRow(
        options = PaperProfiles.GRAPHICS_CAPABLE.map { profile ->
            SegmentedOption(profile.id, profile.label)
        },
        selected = current.id,
        onSelect = { id -> onSelect(PaperProfiles.resolve(id)) },
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = customText,
            onValueChange = { customText = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.printers_paper_custom)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                customText.toIntOrNull()?.let { onSelect(PaperProfiles.custom(it)) }
            },
            enabled = customText.toIntOrNull() != null,
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

// -------------------------------------------------------------------------------------------
// Crop and rotate
// -------------------------------------------------------------------------------------------

@Composable
private fun CropOptions(state: PreviewUiState, viewModel: PreviewViewModel) {
    val options = state.options
    val crop = options.crop ?: CropRect.FULL

    state.originalBitmap?.let { image ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            CropOverlay(
                image = image,
                crop = crop,
                onCropChanged = viewModel::setCrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(R.string.option_crop_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SegmentedOptionRow(
        options = listOf(
            SegmentedOption(CropAspect.FREE, stringResource(R.string.option_crop_free)),
            SegmentedOption(CropAspect.SQUARE, stringResource(R.string.option_crop_square)),
            SegmentedOption(CropAspect.LANDSCAPE_4_3, stringResource(R.string.option_crop_4_3)),
            SegmentedOption(CropAspect.PORTRAIT_3_4, stringResource(R.string.option_crop_3_4)),
        ),
        selected = CropAspect.FREE,
        onSelect = { aspect -> viewModel.setCrop(aspect.applyTo(crop)) },
    )

    SoftDivider()

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { viewModel.rotate(-90) }) {
            Icon(
                Icons.AutoMirrored.Filled.RotateLeft,
                contentDescription = stringResource(R.string.option_rotate_left),
            )
        }
        IconButton(onClick = { viewModel.rotate(90) }) {
            Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = stringResource(R.string.option_rotate_right),
            )
        }
        IconButton(
            onClick = {
                viewModel.updateOptions { it.copy(flipHorizontal = !it.flipHorizontal) }
            },
        ) {
            Icon(
                Icons.Default.Flip,
                contentDescription = stringResource(R.string.option_flip_horizontal),
            )
        }
        IconButton(
            onClick = { viewModel.updateOptions { it.copy(flipVertical = !it.flipVertical) } },
        ) {
            Icon(
                Icons.Default.Flip,
                contentDescription = stringResource(R.string.option_flip_vertical),
                modifier = Modifier.graphicsLayer { rotationZ = 90f },
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                viewModel.setCrop(null)
                viewModel.updateOptions {
                    it.copy(rotationDegrees = 0, flipHorizontal = false, flipVertical = false)
                }
            },
        ) {
            Text(stringResource(R.string.action_reset))
        }
    }

    ToggleRow(
        label = stringResource(R.string.option_auto_rotate),
        checked = options.autoRotateWideImages,
        onCheckedChange = { checked ->
            viewModel.updateOptions { it.copy(autoRotateWideImages = checked) }
        },
    )
}

/** The aspect presets offered above the crop rectangle. */
private enum class CropAspect(val ratio: Float?) {
    FREE(null),
    SQUARE(1f),
    LANDSCAPE_4_3(4f / 3f),
    PORTRAIT_3_4(3f / 4f);

    /** Re-shapes [crop] around its own centre to this aspect. */
    fun applyTo(crop: CropRect): CropRect {
        val target = ratio ?: return crop
        val centerX = (crop.left + crop.right) / 2f
        val centerY = (crop.top + crop.bottom) / 2f
        val width = crop.right - crop.left
        val height = crop.bottom - crop.top
        val currentRatio = width / height

        val (newWidth, newHeight) = if (currentRatio > target) {
            height * target to height
        } else {
            width to width / target
        }

        return CropRect(
            left = (centerX - newWidth / 2f),
            top = (centerY - newHeight / 2f),
            right = (centerX + newWidth / 2f),
            bottom = (centerY + newHeight / 2f),
        ).sanitized()
    }
}

// -------------------------------------------------------------------------------------------
// Tone
// -------------------------------------------------------------------------------------------

@Composable
private fun ToneOptions(state: PreviewUiState, viewModel: PreviewViewModel) {
    val options = state.options

    SectionHeader(stringResource(R.string.histogram_title))
    Histogram(counts = state.histogram, threshold = options.threshold)

    LabeledSlider(
        label = stringResource(R.string.option_brightness),
        value = options.brightness,
        valueRange = PrintOptions.MIN_BRIGHTNESS..PrintOptions.MAX_BRIGHTNESS,
        valueLabel = options.brightness.roundToInt().toString(),
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(brightness = value) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    LabeledSlider(
        label = stringResource(R.string.option_contrast),
        value = options.contrast,
        valueRange = PrintOptions.MIN_CONTRAST..PrintOptions.MAX_CONTRAST,
        valueLabel = options.contrast.roundToInt().toString(),
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(contrast = value) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    // Darkness is presented so that dragging RIGHT prints darker, which is what everybody
    // expects. The underlying gamma curve runs the other way, so it is inverted here.
    LabeledSlider(
        label = stringResource(R.string.option_darkness),
        value = gammaToDarkness(options.gamma),
        valueRange = PrintOptions.MIN_GAMMA..PrintOptions.MAX_GAMMA,
        valueLabel = "${darknessPercent(options.gamma)} %",
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(gamma = darknessToGamma(value)) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    LabeledSlider(
        label = stringResource(R.string.option_threshold),
        value = options.threshold.toFloat(),
        valueRange = PrintOptions.MIN_THRESHOLD.toFloat()..PrintOptions.MAX_THRESHOLD.toFloat(),
        valueLabel = options.threshold.toString(),
        onValueChange = { value ->
            viewModel.onDragStart()
            viewModel.updateOptions { it.copy(threshold = value.roundToInt()) }
        },
        onValueChangeFinished = viewModel::onDragEnd,
    )

    ToggleRow(
        label = stringResource(R.string.option_invert),
        checked = options.invert,
        onCheckedChange = { checked -> viewModel.updateOptions { it.copy(invert = checked) } },
    )

    SoftDivider()
    SectionHeader(stringResource(R.string.option_grayscale_mode))
    SegmentedOptionRow(
        options = listOf(
            SegmentedOption(GrayscaleMode.LUMINANCE, stringResource(R.string.grayscale_luminance)),
            SegmentedOption(GrayscaleMode.AVERAGE, stringResource(R.string.grayscale_average)),
        ),
        selected = options.grayscaleMode,
        onSelect = { mode -> viewModel.updateOptions { it.copy(grayscaleMode = mode) } },
    )
}

/** Gamma runs light-to-dark in reverse; the slider is mirrored so right means darker. */
private fun gammaToDarkness(gamma: Float): Float =
    PrintOptions.MIN_GAMMA + PrintOptions.MAX_GAMMA - gamma

private fun darknessToGamma(darkness: Float): Float =
    PrintOptions.MIN_GAMMA + PrintOptions.MAX_GAMMA - darkness

private fun darknessPercent(gamma: Float): Int {
    val span = PrintOptions.MAX_GAMMA - PrintOptions.MIN_GAMMA
    return (((gammaToDarkness(gamma) - PrintOptions.MIN_GAMMA) / span) * 100).roundToInt()
}

// -------------------------------------------------------------------------------------------
// Quality
// -------------------------------------------------------------------------------------------

@Composable
private fun QualityOptions(state: PreviewUiState, viewModel: PreviewViewModel) {
    SectionHeader(stringResource(R.string.presets_title))
    SegmentedOptionRow(
        options = listOf(
            SegmentedOption(PrintPreset.PHOTO, stringResource(R.string.preset_photo)),
            SegmentedOption(PrintPreset.HIGH_CONTRAST, stringResource(R.string.preset_high_contrast)),
            SegmentedOption(PrintPreset.LINE_ART, stringResource(R.string.preset_line_art)),
            SegmentedOption(PrintPreset.DOCUMENT, stringResource(R.string.preset_document)),
        ),
        selected = state.preset,
        onSelect = viewModel::applyPreset,
    )
    if (state.preset == PrintPreset.CUSTOM) {
        Text(
            text = stringResource(R.string.preset_custom),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SoftDivider()
    SectionHeader(stringResource(R.string.option_dither_mode))

    DitherMode.entries.forEach { mode ->
        DitherSwatch(
            label = stringResource(mode.labelRes()),
            description = stringResource(mode.descriptionRes()),
            swatch = state.swatches[mode],
            selected = state.options.ditherMode == mode,
            onClick = { viewModel.updateOptions { it.copy(ditherMode = mode) } },
        )
    }
}

private fun DitherMode.labelRes(): Int = when (this) {
    DitherMode.FLOYD_STEINBERG -> R.string.dither_floyd
    DitherMode.ATKINSON -> R.string.dither_atkinson
    DitherMode.ORDERED_BAYER_8 -> R.string.dither_bayer
    DitherMode.THRESHOLD -> R.string.dither_threshold
    DitherMode.NONE_GRAYSCALE -> R.string.dither_none
}

private fun DitherMode.descriptionRes(): Int = when (this) {
    DitherMode.FLOYD_STEINBERG -> R.string.dither_floyd_description
    DitherMode.ATKINSON -> R.string.dither_atkinson_description
    DitherMode.ORDERED_BAYER_8 -> R.string.dither_bayer_description
    DitherMode.THRESHOLD -> R.string.dither_threshold_description
    DitherMode.NONE_GRAYSCALE -> R.string.dither_none_description
}

// -------------------------------------------------------------------------------------------

/** A label with a switch, the shape every boolean option in this app takes. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private const val MAX_MARGIN_DOTS = 96f
