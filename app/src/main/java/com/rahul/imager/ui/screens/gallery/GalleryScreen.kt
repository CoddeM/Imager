package com.rahul.imager.ui.screens.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.InlineNotice
import com.rahul.imager.ui.components.LoadingSkeleton
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.rememberThumbnail
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The in-app album browser.
 *
 * Everything about this screen is secondary to the system photo picker, and it says so: when
 * permission is missing, the rationale offers the picker as the easier alternative rather than
 * pushing the user towards granting library-wide access they may not want to give.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoPicked: (Uri) -> Unit,
    onUseSystemPicker: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gridState = rememberLazyGridState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess() }

    // Endless scroll: load the next page a little before the user reaches the bottom.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= state.items.size - PREFETCH_DISTANCE) viewModel.loadNextPage()
            }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ImagerHeader(
                title = stringResource(R.string.picker_title),
                onBack = onBack,
            )
            when (state.access) {
                MediaAccess.DENIED -> PermissionRationale(
                    onGrant = { permissionLauncher.launch(viewModel.requiredPermissions()) },
                    onUseSystemPicker = onUseSystemPicker,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            )
                        )
                    },
                )

                MediaAccess.PARTIAL, MediaAccess.FULL -> {
                    if (state.access == MediaAccess.PARTIAL) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InlineNotice(
                                text = stringResource(R.string.picker_partial_access),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    permissionLauncher.launch(viewModel.requiredPermissions())
                                },
                            ) { Text(stringResource(R.string.picker_partial_access_action)) }
                        }
                    }

                    if (state.albums.size > 1) {
                        SegmentedOptionRow(
                            options = listOf(SegmentedOption<String?>(null, "All")) +
                                state.albums.map { SegmentedOption<String?>(it, it) },
                            selected = state.selectedAlbum,
                            onSelect = viewModel::selectAlbum,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    if (state.items.isEmpty() && !state.loading) {
                        EmptyState(
                            icon = Icons.Default.PhotoLibrary,
                            title = stringResource(R.string.picker_title),
                            message = stringResource(R.string.picker_empty),
                            action = {
                                TextButton(onClick = onUseSystemPicker) {
                                    Text(stringResource(R.string.picker_permission_use_system))
                                }
                            },
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.items, key = { it.uri.toString() }) { item ->
                                GalleryCell(item = item, onClick = { onPhotoPicked(item.uri) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(item: GalleryItem, onClick: () -> Unit) {
    val thumbnail by rememberThumbnail(item.uri, targetPx = 256)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        val bitmap = thumbnail
        if (bitmap == null) {
            LoadingSkeleton(modifier = Modifier.fillMaxSize(), cornerRadius = 6.dp)
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PermissionRationale(
    onGrant: () -> Unit,
    onUseSystemPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            text = stringResource(R.string.picker_permission_rationale_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.picker_permission_rationale_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.picker_permission_grant))
        }
        TextButton(onClick = onUseSystemPicker, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.picker_permission_use_system))
        }
        Text(
            text = stringResource(R.string.picker_permission_denied_forever),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}

private const val PREFETCH_DISTANCE = 24
