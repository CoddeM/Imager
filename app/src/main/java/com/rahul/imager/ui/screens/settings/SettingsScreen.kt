package com.rahul.imager.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.rahul.imager.ui.components.IconTile
import com.rahul.imager.ui.components.ImagerCard
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.RowDivider
import com.rahul.imager.ui.components.ScreenGutter
import com.rahul.imager.ui.components.SectionTitle
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.SettingGroup
import com.rahul.imager.ui.components.SettingRow
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
 * Grouped cards rather than one long list: the four things this screen controls have nothing to do
 * with each other, and a card apiece is what stops them reading as an undifferentiated column of
 * switches. The version row is also the way into diagnostics, on a long press — hidden rather than
 * absent, because print traces are for the rare bad day, not for the everyday UI.
 */
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ImagerHeader(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            SectionTitle(stringResource(R.string.settings_default_printer))
            ImagerCard {
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
            }

            Spacer(Modifier.height(4.dp))
            SectionTitle(stringResource(R.string.settings_theme))
            ImagerCard {
                SegmentedOptionRow(
                    options = listOf(
                        SegmentedOption(
                            ThemeMode.SYSTEM,
                            stringResource(R.string.settings_theme_system),
                        ),
                        SegmentedOption(
                            ThemeMode.LIGHT,
                            stringResource(R.string.settings_theme_light),
                        ),
                        SegmentedOption(
                            ThemeMode.DARK,
                            stringResource(R.string.settings_theme_dark),
                        ),
                    ),
                    selected = state.settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }

            Spacer(Modifier.height(4.dp))
            SectionTitle(stringResource(R.string.settings_preferences))
            SettingGroup {
                SettingRow(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.settings_default_options),
                    subtitle = stringResource(R.string.settings_default_options_summary),
                    trailing = {
                        Text(
                            text = stringResource(R.string.action_reset),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClick = viewModel::resetDefaultOptions)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.settings_keep_recents),
                    subtitle = stringResource(R.string.settings_keep_recents_summary),
                    trailing = {
                        Switch(
                            checked = state.settings.keepRecents,
                            onCheckedChange = viewModel::setKeepRecents,
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.settings_clear_cache),
                    onClick = viewModel::clearCache,
                )
            }

            Spacer(Modifier.height(4.dp))
            SectionTitle(stringResource(R.string.settings_about))
            SettingGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { }, onLongClick = onOpenDiagnostics)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(icon = Icons.Default.Info)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.settings_version,
                                BuildConfig.VERSION_NAME,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_version_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
