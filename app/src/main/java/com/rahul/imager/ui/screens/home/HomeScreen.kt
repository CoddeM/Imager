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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.data.RecentPrint
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.InlineNotice
import com.rahul.imager.ui.components.PrinterPickerSheet
import com.rahul.imager.ui.components.PrinterStatusChip
import com.rahul.imager.ui.components.SectionHeader
import com.rahul.imager.ui.components.rememberCachedThumbnail
import com.rahul.imager.ui.theme.ImagerTheme

/**
 * The home screen: the shortest possible path from "I want to print this" to a preview.
 *
 * Photo selection is NEVER blocked on printer setup. A user with no printer can still pick a
 * photo, see exactly how it would print, and only then be asked where to send it — which is the
 * order that makes sense when you are trying an app for the first time.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    PrinterStatusChip(
                        printer = uiState.defaultPrinter,
                        state = uiState.defaultPrinterState,
                        onClick = { showPrinterSheet = true },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (uiState.showNoPrinterBanner) {
                NoPrinterBanner(
                    onSetUp = onAddPrinter,
                    onDismiss = viewModel::dismissBanner,
                )
            }

            ChoosePhotoCard(
                onClick = {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            TextButton(
                onClick = onBrowseGallery,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Default.Collections, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_browse_gallery))
            }

            SectionHeader(text = stringResource(R.string.home_recent_prints))

            if (uiState.recents.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.home_recent_prints),
                    message = stringResource(R.string.home_recent_empty),
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
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

/** The hero action. Deliberately the largest, most obvious thing on the screen. */
@Composable
private fun ChoosePhotoCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = stringResource(R.string.home_choose_photo),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.home_choose_photo_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The dismissible, non-blocking setup nudge. */
@Composable
private fun NoPrinterBanner(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineNotice(
            text = stringResource(R.string.home_no_printer_banner),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onSetUp) {
            Text(stringResource(R.string.home_no_printer_banner_action))
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss))
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

    Card(modifier = modifier.width(132.dp)) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onClick),
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
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = recent.printerName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (recent.success) {
                    stringResource(R.string.printing_success)
                } else {
                    recent.errorCode ?: stringResource(R.string.printing_failed)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (recent.success) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "Home phone", widthDp = 360, heightDp = 780, showBackground = true)
@Preview(name = "Home small", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
private fun ChoosePhotoCardPreview() {
    ImagerTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoosePhotoCard(onClick = {})
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
