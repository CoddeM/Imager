package com.rahul.imager.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The WYSIWYG preview.
 *
 * The bitmap it draws is the SAME raster the driver will send, produced by the same pipeline, so
 * what the user sees here is what comes out of the printer, dot for dot. Three details make that
 * legible rather than merely accurate:
 *
 *  * the paper is drawn to scale, with real edges, so a photo printed at 60 % scale visibly sits
 *    in the middle of a wider roll instead of just looking smaller;
 *  * the image is drawn with NO filtering, so a 1-bit dot stays a hard dot on screen instead of
 *    being smoothed into fake grey by the GPU;
 *  * press and hold swaps in the original colour photo, which is the only honest way to judge what
 *    the dithering has cost.
 */
@Composable
fun PaperCanvas(
    raster: ImageBitmap?,
    original: ImageBitmap?,
    paperWidthDots: Int,
    modifier: Modifier = Modifier,
    horizontalBias: Float = 0f,
    onCompareChanged: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var comparing by remember { mutableStateOf(false) }

    val comparingAlpha by animateFloatAsState(
        targetValue = if (comparing) 1f else 0f,
        animationSpec = tween(160),
        label = "compareAlpha",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(PaperBackdrop)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(original) {
                detectTapGestures(
                    onDoubleTap = {
                        // A double tap is the quickest way back from a lost zoom.
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onPress = {
                        if (original != null) {
                            comparing = true
                            onCompareChanged(true)
                            tryAwaitRelease()
                            comparing = false
                            onCompareChanged(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val paperWidth: Dp = maxWidth * PAPER_WIDTH_FRACTION
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .width(paperWidth)
                .fillMaxSize(),
        ) {
            // The paper itself: a plain white column with a soft edge shadow, drawn to the true
            // proportions of the roll.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.White)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (raster != null) {
                    // The raster occupies exactly its share of the paper width, so margins and
                    // scale below 100 % are visible as real white space.
                    val imageFraction = (raster.width.toFloat() / paperWidthDots).coerceIn(0.05f, 1f)
                    val density = LocalDensity.current
                    val imageWidth = with(density) {
                        (paperWidth.toPx() * imageFraction).toDp()
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = when {
                            horizontalBias < -0.1f -> Alignment.TopStart
                            horizontalBias > 0.1f -> Alignment.TopEnd
                            else -> Alignment.TopCenter
                        },
                    ) {
                        Image(
                            bitmap = raster,
                            contentDescription = null,
                            modifier = Modifier.width(imageWidth),
                            contentScale = ContentScale.FillWidth,
                            // No smoothing: a printed dot is a hard dot, and the preview must not
                            // flatter the output by blurring it into grey.
                            filterQuality = FilterQuality.None,
                        )
                        if (original != null && comparingAlpha > 0f) {
                            Image(
                                bitmap = original,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(imageWidth)
                                    .graphicsLayer { alpha = comparingAlpha },
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The desk the paper sits on. Deliberately neutral so it never tints the preview. */
/** A cool, near-neutral backdrop so the white paper reads as paper and nothing competes. */
private val PaperBackdrop = Color(0xFF394052)

private const val PAPER_WIDTH_FRACTION = 0.72f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
