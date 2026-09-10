package com.rahul.imager.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.data.RecentPrint
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.ui.components.ImagerCard
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.IconTile
import com.rahul.imager.ui.components.InlineBanner
import com.rahul.imager.ui.components.PrinterPickerSheet
import com.rahul.imager.ui.components.PrinterStatusChip
import com.rahul.imager.ui.components.ScreenGutter
import com.rahul.imager.ui.components.SectionTitle
import com.rahul.imager.ui.components.rememberCachedThumbnail
import com.rahul.imager.ui.theme.ImagerTheme
import com.rahul.imager.ui.theme.LocalStatusColors

/**
 * The home screen: the shortest possible path from "I want to print this" to a preview.
 *
 * Photo selection is NEVER blocked on printer setup. A user with no printer can still pick a
 * photo, see exactly how it would print, and only then be asked where to send it — which is the
 * order that makes sense when you are trying an app for the first time. The layout says the same
 * thing: the photo hero is the loudest element, and the printer lives in a quiet chip above it.
 */
@Composable
fun HomeScreen(
    onPhotoPicked: (Uri) -> Unit,
    onBrowseGallery: () -> Unit,
    onAddPrinter: () -> Unit,
    onReprint: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPrinterSheet by rememberSaveable { mutableStateOf(false) }

    // The system photo picker. It needs NO runtime permission on any API level: the user chooses
    // in a system UI and the app is handed a grant for exactly that item.
    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onPhotoPicked) }

    val openPicker = {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ImagerHeader(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.home_subtitle),
            actions = {
                PrinterStatusChip(
                    printer = uiState.defaultPrinter,
                    state = uiState.defaultPrinterState,
                    onClick = { showPrinterSheet = true },
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            if (uiState.showNoPrinterBanner) {
                NoPrinterBanner(onSetUp = onAddPrinter, onDismiss = viewModel::dismissBanner)
            }

            ChoosePhotoHero(onClick = { openPicker() })

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction(
                    icon = Icons.Default.Collections,
                    label = stringResource(R.string.home_browse_gallery),
                    onClick = onBrowseGallery,
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Default.Print,
                    label = stringResource(R.string.action_add_printer),
                    onClick = onAddPrinter,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionTitle(text = stringResource(R.string.home_recent_prints))

            if (uiState.recents.isEmpty()) {
                RecentsEmpty()
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(uiState.recents, key = { it.id }) { recent ->
                        RecentPrintCard(
                            recent = recent,
                            onClick = { viewModel.prepareReprint(recent, onReprint) },
                            onRemove = { viewModel.removeRecent(recent) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPrinterSheet) {
        PrinterPickerSheet(
            printers = uiState.printers,
            states = uiState.connectionStates,
            selectedId = uiState.defaultPrinter?.id,
            onSelect = {
                viewModel.selectPrinter(it)
                showPrinterSheet = false
            },
            onReconnect = viewModel::reconnect,
            onAddPrinter = {
                showPrinterSheet = false
                onAddPrinter()
            },
            onDismiss = { showPrinterSheet = false },
        )
    }
}

/**
 * The hero action. Deliberately the largest, loudest thing on the screen.
 *
 * It is the only gradient in the app, which is what makes it impossible to miss on a page of white
 * cards — and the reason nothing else on Home is allowed to compete with it.
 */
@Composable
private fun ChoosePhotoHero(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(status.gradientStart, status.gradientEnd),
                )
            )
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(24.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.White.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.home_choose_photo),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_choose_photo_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(20.dp))
            Surface(shape = CircleShape, color = Color.White) {
                Text(
                    text = stringResource(R.string.home_choose_photo_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = status.gradientEnd,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** One of the two shortcut tiles under the hero. */
@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ImagerCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        IconTile(icon = icon)
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The dismissible, non-blocking setup nudge. */
@Composable
private fun NoPrinterBanner(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    InlineBanner(
        text = stringResource(R.string.home_no_printer_banner),
        icon = Icons.Default.Info,
        color = status.warning,
        tint = status.warningTint,
        modifier = modifier,
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_no_printer_banner_action),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onSetUp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(6.dp),
                )
            }
        },
    )
}

/** Recents before anything has been printed. */
@Composable
private fun RecentsEmpty(modifier: Modifier = Modifier) {
    ImagerCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(
                icon = Icons.Default.History,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceContainer,
                size = 52.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.home_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One entry in the recents row. Tapping it reprints with the same options. */
@Composable
private fun RecentPrintCard(
    recent: RecentPrint,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail by rememberCachedThumbnail(recent.thumbnailPath)
    val description = stringResource(R.string.home_reprint_content_description)
    val status = LocalStatusColors.current

    ImagerCard(
        modifier = modifier.width(146.dp),
        onClick = onClick,
        contentPadding = PaddingValues(10.dp),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                thumbnail?.let {
                    Image(
                        bitmap = it,
                        contentDescription = description,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = recent.printerName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (recent.success) {
                stringResource(R.string.printing_success)
            } else {
                recent.errorCode ?: stringResource(R.string.printing_failed)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (recent.success) status.connected else status.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "Home phone", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun HomePiecesPreview() {
    ImagerTheme {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoosePhotoHero(onClick = {})
            NoPrinterBanner(onSetUp = {}, onDismiss = {})
            RecentPrintCard(
                recent = RecentPrint(
                    id = "1",
                    imageUri = "",
                    thumbnailPath = null,
                    printerName = "Kitchen TSP143",
                    printerId = null,
                    printedAtEpochMs = 0,
                    success = true,
                    options = PrintOptions(),
                ),
                onClick = {},
                onRemove = {},
            )
        }
    }
}
