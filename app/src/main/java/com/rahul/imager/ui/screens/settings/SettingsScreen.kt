package com.rahul.imager.ui.screens.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rahul.imager.BuildConfig
import com.rahul.imager.R
import com.rahul.imager.data.AppSettings
import com.rahul.imager.data.AppSettingsStore
import com.rahul.imager.data.PrintOptionsStore
import com.rahul.imager.data.PrinterStore
import com.rahul.imager.data.RecentPrintsStore
import com.rahul.imager.data.ThemeMode
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.PrintPreset
import com.rahul.imager.ui.components.SectionHeader
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.SoftDivider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val printers: List<SavedPrinter> = emptyList(),
    val defaultPrinter: SavedPrinter? = null,
    val defaultOptions: PrintOptions = PrintOptions(),
)

/** Settings. Small, and deliberately kept that way. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsStore: AppSettingsStore,
    private val printerStore: PrinterStore,
    private val printOptionsStore: PrintOptionsStore,
    private val recentPrintsStore: RecentPrintsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                appSettingsStore.settings,
                printerStore.printers,
                printerStore.defaultPrinter,
                printOptionsStore.lastUsedOptions,
            ) { settings, printers, default, options ->
                SettingsUiState(settings, printers, default, options)
            }.collect { _uiState.value = it }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettingsStore.setThemeMode(mode) }
    }

    fun setKeepRecents(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setKeepRecents(enabled)
            if (!enabled) recentPrintsStore.clear()
        }
    }

    fun setDefaultPrinter(printer: SavedPrinter) {
        viewModelScope.launch { printerStore.setDefault(printer.id) }
    }

    /** Resets the remembered print options back to the Photo preset. */
    fun resetDefaultOptions() {
        viewModelScope.launch {
            printOptionsStore.saveLastUsed(PrintPreset.PHOTO.applyTo(PrintOptions()))
        }
    }

    fun clearCache() {
        viewModelScope.launch { recentPrintsStore.clear() }
    }
}

/**
 * Settings.
 *
 * The version row is also the way into the diagnostics screen: a long press. Hidden rather than
 * absent, because print traces are for the rare bad day, not for the everyday UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_default_printer))
            if (state.printers.isEmpty()) {
                Text(
                    text = stringResource(R.string.printers_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SegmentedOptionRow(
                    options = state.printers.map { SegmentedOption(it.id, it.displayName) },
                    selected = state.defaultPrinter?.id ?: "",
                    onSelect = { id ->
                        state.printers.firstOrNull { it.id == id }
                            ?.let(viewModel::setDefaultPrinter)
                    },
                )
            }

            SoftDivider()

            SectionHeader(stringResource(R.string.settings_theme))
            SegmentedOptionRow(
                options = listOf(
                    SegmentedOption(ThemeMode.SYSTEM, stringResource(R.string.settings_theme_system)),
                    SegmentedOption(ThemeMode.LIGHT, stringResource(R.string.settings_theme_light)),
                    SegmentedOption(ThemeMode.DARK, stringResource(R.string.settings_theme_dark)),
                ),
                selected = state.settings.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            SoftDivider()

            SectionHeader(stringResource(R.string.settings_default_options))
            Text(
                text = stringResource(R.string.settings_default_options_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = viewModel::resetDefaultOptions) {
                Text(stringResource(R.string.action_reset))
            }

            SoftDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_keep_recents),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_keep_recents_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.keepRecents,
                    onCheckedChange = viewModel::setKeepRecents,
                )
            }

            TextButton(onClick = viewModel::clearCache) {
                Text(stringResource(R.string.settings_clear_cache))
            }

            SoftDivider()

            SectionHeader(stringResource(R.string.settings_about))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { },
                        onLongClick = onOpenDiagnostics,
                    )
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_version_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
