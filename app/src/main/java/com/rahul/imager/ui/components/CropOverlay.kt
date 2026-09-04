package com.rahul.imager.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.rahul.imager.printer.raster.CropRect
import kotlin.math.abs

/**
 * An interactive crop rectangle over the source photo.
 *
 * The rectangle is kept in NORMALIZED coordinates so it survives rotation, a re-decode at a
 * different sample size, and being persisted with the print options. The four corners are
 * draggable; dragging anywhere else moves the whole rectangle.
 */
@Composable
fun CropOverlay(
    image: ImageBitmap,
    crop: CropRect,
    onCropChanged: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val aspect = image.width.toFloat() / image.height
        val containerAspect: Float = maxWidth / maxHeight

        // Letterbox the photo inside the container so the crop maths lines up with what is drawn.
        val displayWidthPx: Float
        val displayHeightPx: Float
        with(androidx.compose.ui.platform.LocalDensity.current) {
            val containerWidthPx = maxWidth.toPx()
            val containerHeightPx = maxHeight.toPx()
            if (aspect > containerAspect) {
                displayWidthPx = containerWidthPx
                displayHeightPx = containerWidthPx / aspect
            } else {
                displayHeightPx = containerHeightPx
                displayWidthPx = containerHeightPx * aspect
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            val handleColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
            var activeHandle = remember { arrayOf(Handle.NONE) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(image) {
                        detectDragGestures(
                            onDragStart = { position ->
                                activeHandle[0] = handleAt(
                                    position,
                                    crop,
                                    displayWidthPx,
                                    displayHeightPx,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                )
                            },
                            onDragEnd = { activeHandle[0] = Handle.NONE },
                            onDragCancel = { activeHandle[0] = Handle.NONE },
                        ) { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x / displayWidthPx
                            val dy = dragAmount.y / displayHeightPx
                            onCropChanged(applyDrag(crop, activeHandle[0], dx, dy))
                        }
                    },
            ) {
                val left = (size.width - displayWidthPx) / 2f
                val top = (size.height - displayHeightPx) / 2f
                val rectLeft = left + crop.left * displayWidthPx
                val rectTop = top + crop.top * displayHeightPx
                val rectWidth = (crop.right - crop.left) * displayWidthPx
                val rectHeight = (crop.bottom - crop.top) * displayHeightPx

                // Dim everything outside the crop so the kept area reads instantly.
                drawRect(color = Scrim, size = Size(size.width, rectTop))
                drawRect(
                    color = Scrim,
                    topLeft = Offset(0f, rectTop + rectHeight),
                    size = Size(size.width, size.height - rectTop - rectHeight),
                )
                drawRect(
                    color = Scrim,
                    topLeft = Offset(0f, rectTop),
                    size = Size(rectLeft, rectHeight),
                )
                drawRect(
                    color = Scrim,
                    topLeft = Offset(rectLeft + rectWidth, rectTop),
                    size = Size(size.width - rectLeft - rectWidth, rectHeight),
                )

                drawRect(
                    color = handleColor,
                    topLeft = Offset(rectLeft, rectTop),
                    size = Size(rectWidth, rectHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )

                listOf(
                    Offset(rectLeft, rectTop),
                    Offset(rectLeft + rectWidth, rectTop),
                    Offset(rectLeft, rectTop + rectHeight),
                    Offset(rectLeft + rectWidth, rectTop + rectHeight),
                ).forEach { corner ->
                    drawCircle(color = handleColor, radius = HandleRadiusPx, center = corner)
                }
            }
        }
    }
}

/** Which part of the rectangle a drag is moving. */
private enum class Handle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, WHOLE }

private val Scrim = Color(0x99000000)
private const val HandleRadiusPx = 14f
private const val HandleHitFraction = 0.12f

private fun handleAt(
    position: Offset,
    crop: CropRect,
    displayWidth: Float,
    displayHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Handle {
    val left = (canvasWidth - displayWidth) / 2f
    val top = (canvasHeight - displayHeight) / 2f
    val x = (position.x - left) / displayWidth
    val y = (position.y - top) / displayHeight

    fun near(a: Float, b: Float) = abs(a - b) < HandleHitFraction

    return when {
        near(x, crop.left) && near(y, crop.top) -> Handle.TOP_LEFT
        near(x, crop.right) && near(y, crop.top) -> Handle.TOP_RIGHT
        near(x, crop.left) && near(y, crop.bottom) -> Handle.BOTTOM_LEFT
        near(x, crop.right) && near(y, crop.bottom) -> Handle.BOTTOM_RIGHT
        x in crop.left..crop.right && y in crop.top..crop.bottom -> Handle.WHOLE
        else -> Handle.NONE
    }
}

private fun applyDrag(crop: CropRect, handle: Handle, dx: Float, dy: Float): CropRect =
    when (handle) {
        Handle.NONE -> crop
        Handle.TOP_LEFT -> crop.copy(left = crop.left + dx, top = crop.top + dy)
        Handle.TOP_RIGHT -> crop.copy(right = crop.right + dx, top = crop.top + dy)
        Handle.BOTTOM_LEFT -> crop.copy(left = crop.left + dx, bottom = crop.bottom + dy)
        Handle.BOTTOM_RIGHT -> crop.copy(right = crop.right + dx, bottom = crop.bottom + dy)
        Handle.WHOLE -> {
            // Move the whole rectangle, clamped so it can never be dragged off the photo.
            val width = crop.right - crop.left
            val height = crop.bottom - crop.top
            val left = (crop.left + dx).coerceIn(0f, 1f - width)
            val top = (crop.top + dy).coerceIn(0f, 1f - height)
            CropRect(left, top, left + width, top + height)
        }
    }.sanitized()
