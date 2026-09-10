package com.rahul.imager.ui.screens.printers

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.rahul.imager.ui.components.IconTile
import com.rahul.imager.ui.components.ImagerCard
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.MetaChip
import com.rahul.imager.ui.components.RowDivider
import com.rahul.imager.ui.components.ScreenGutter
import com.rahul.imager.ui.components.SectionTitle
import com.rahul.imager.ui.components.SettingGroup
import com.rahul.imager.ui.components.StatusPill
import com.rahul.imager.ui.theme.LocalStatusColors
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.FamilyAvailability
import com.rahul.imager.printer.driver.registry.FamilySupport
import com.rahul.imager.ui.components.DetailRow
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.ErrorPanel
import com.rahul.imager.ui.components.SectionHeader
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.SoftDivider

/**
 * The add-printer flow: choose a connection, scan, confirm, test, save.
 *
 * Families whose SDK is not in this build are LISTED and greyed out with the reason, never hidden.
 * A user looking for their Epson needs to learn that this build cannot drive it, not to conclude
 * the app has never heard of Epson.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPrinterScreen(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddPrinterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Bluetooth needs a runtime permission from API 31, and only when the user actually chooses
    // Bluetooth — nothing is requested at launch.
    val bluetoothPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted.values.all { it }
        viewModel.setNeedsBluetoothPermission(!ok)
        if (ok) viewModel.chooseTransport(TransportType.BLUETOOTH)
    }

    LaunchedEffect(state.savedPrinter) {
        if (state.savedPrinter != null) onFinished()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // The header lives inside the content rather than in the topBar slot so that it picks up
        // the Scaffold's status bar inset like everything else does.
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ImagerHeader(
                title = stringResource(R.string.add_title),
                onBack = { if (!viewModel.back()) onBack() },
            )
            StepProgress(
                current = state.step.ordinal,
                total = AddStep.entries.size,
                modifier = Modifier.padding(horizontal = ScreenGutter),
            )

            AnimatedContent(targetState = state.step, label = "addStep") { step ->
                when (step) {
                    AddStep.CONNECTION -> ConnectionStep(
                        state = state,
                        hasBuiltIn = viewModel.hasBuiltInPrinter,
                        onChoose = { transport ->
                            if (transport == TransportType.BLUETOOTH &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ) {
                                bluetoothPermission.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN,
                                    )
                                )
                            } else {
                                viewModel.chooseTransport(transport)
                            }
                        },
                    )

                    AddStep.SCAN -> ScanStep(state = state, viewModel = viewModel)

                    AddStep.IDENTIFY -> IdentifyStep(state = state, viewModel = viewModel)

                    AddStep.TEST -> TestStep(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Step 1: how is it connected
// -------------------------------------------------------------------------------------------

@Composable
private fun ConnectionStep(
    state: AddPrinterUiState,
    hasBuiltIn: Boolean,
    onChoose: (TransportType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.add_step_connection),
            style = MaterialTheme.typography.headlineMedium,
        )

        if (hasBuiltIn) {
            ConnectionCard(
                icon = Icons.Default.Memory,
                title = stringResource(R.string.connection_built_in),
                description = stringResource(R.string.connection_built_in_description),
                onClick = { onChoose(TransportType.INNER) },
            )
        }
        ConnectionCard(
            icon = Icons.Default.Bluetooth,
            title = stringResource(R.string.connection_bluetooth),
            description = stringResource(R.string.connection_bluetooth_description),
            onClick = { onChoose(TransportType.BLUETOOTH) },
        )
        ConnectionCard(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.connection_lan),
            description = stringResource(R.string.connection_lan_description),
            onClick = { onChoose(TransportType.LAN) },
        )
        ConnectionCard(
            icon = Icons.Default.Usb,
            title = stringResource(R.string.connection_usb),
            description = stringResource(R.string.connection_usb_description),
            onClick = { onChoose(TransportType.USB) },
        )

        if (state.needsBluetoothPermission) {
            Text(
                text = stringResource(R.string.add_bt_permission_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle(stringResource(R.string.diagnostics_families))
        SettingGroup {
            state.families.forEachIndexed { index, family ->
                if (index > 0) RowDivider(inset = 62.dp)
                FamilyRow(family)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ImagerCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = icon, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The four-step tracker across the top of the add flow.
 *
 * A segmented bar rather than a continuous one: the user is being walked through a fixed number of
 * steps, and being able to count the remaining segments is the whole point.
 */
@Composable
private fun StepProgress(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ),
            )
        }
    }
}

/** One driver family, greyed out with a reason when it is unavailable. */
@Composable
private fun FamilyRow(family: FamilyAvailability) {
    val statusColors = LocalStatusColors.current
    val compatibility = family.support == FamilySupport.ESCPOS_COMPATIBILITY
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            icon = Icons.Default.Print,
            size = 36.dp,
            tint = when {
                compatibility -> statusColors.warning
                family.available -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            background = when {
                compatibility -> statusColors.warningTint
                family.available -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = family.brand.name.lowercase().replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.titleSmall,
                color = if (family.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = family.reason
                    ?: family.supportedTransports.joinToString { it.name.lowercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            // Three states, not two: a family the generic ESC/POS driver can drive is usable, but
            // saying "Available" would promise the vendor discovery and status it does not have.
            compatibility -> StatusPill(
                text = stringResource(R.string.add_family_escpos_badge),
                color = statusColors.warning,
                tint = statusColors.warningTint,
            )

            family.available -> StatusPill(
                text = stringResource(R.string.diagnostics_family_available),
                color = statusColors.connected,
                tint = statusColors.connectedTint,
            )

            family.support == FamilySupport.DEVICE_UNSUPPORTED ->
                MetaChip(text = stringResource(R.string.add_family_device_badge))

            else -> MetaChip(text = stringResource(R.string.add_family_unavailable_badge))
        }
    }
}

// -------------------------------------------------------------------------------------------
// Step 2: scan
// -------------------------------------------------------------------------------------------

@Composable
private fun ScanStep(state: AddPrinterUiState, viewModel: AddPrinterViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.add_step_scan),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (state.scanning) {
                        stringResource(R.string.add_scanning)
                    } else {
                        stringResource(R.string.add_scan_finished)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = viewModel::startScan) {
                    Text(stringResource(R.string.action_scan_again))
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.results.isEmpty() && !state.scanning) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(R.string.add_step_scan),
                    message = stringResource(R.string.add_nothing_found),
                    action = {
                        TextButton(onClick = viewModel::startScan) {
                            Text(stringResource(R.string.action_scan_again))
                        }
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.key }) { discovered ->
                        DiscoveredRow(
                            discovered = discovered,
                            selected = state.selected?.key == discovered.key,
                            onClick = { viewModel.select(discovered) },
                        )
                    }
                }
            }
        }

        if (state.transport == TransportType.LAN) {
            ManualLanEntry(state = state, viewModel = viewModel)
        }

        Button(
            onClick = viewModel::continueToIdentify,
            enabled = state.canContinueFromScan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun DiscoveredRow(
    discovered: DiscoveredPrinter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ImagerCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = Icons.Default.Print,
                size = 42.dp,
                background = if (selected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = discovered.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(discovered.brand.name.lowercase().replaceFirstChar(Char::uppercase))
                        discovered.detail?.let { append("  ·  ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ManualLanEntry(state: AddPrinterUiState, viewModel: AddPrinterViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(R.string.add_manual_entry))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.manualIp,
                onValueChange = viewModel::setManualIp,
                label = { Text(stringResource(R.string.add_manual_ip)) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.manualPort,
                onValueChange = viewModel::setManualPort,
                label = { Text(stringResource(R.string.add_manual_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = viewModel::addManualLanPrinter,
                enabled = state.manualEntryValid,
            ) { Text(stringResource(R.string.add_manual_add)) }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Step 3: confirm
// -------------------------------------------------------------------------------------------

@Composable
private fun IdentifyStep(state: AddPrinterUiState, viewModel: AddPrinterViewModel) {
    val discovered = state.selected ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.add_step_identify),
            style = MaterialTheme.typography.headlineMedium,
        )

        // The connect happens on the way into this step, so the user finds out whether the printer
        // actually answers while there is still a Back button.
        when {
            state.identifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.add_identify_connecting))
            }

            state.identifyError != null -> ErrorPanel(
                error = state.identifyError,
                onRetry = viewModel::continueToIdentify,
            )

            state.reachable == true -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_identify_reachable))
                }
                state.hardwareStatus?.let { status ->
                    Text(
                        text = stringResource(
                            R.string.add_identify_status,
                            hardwareStatusLabel(status),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::setDisplayName,
            label = { Text(stringResource(R.string.add_identify_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        DetailRow(
            label = stringResource(R.string.add_identify_model),
            value = discovered.model ?: discovered.name,
        )
        DetailRow(
            label = stringResource(R.string.connection_bluetooth).let { _ ->
                discovered.transport.name.lowercase().replaceFirstChar(Char::uppercase)
            },
            value = discovered.identifier.ifBlank { "—" },
        )

        SoftDivider()
        SectionHeader(stringResource(R.string.add_identify_paper))
        Text(
            text = state.resolvedPaperNote ?: stringResource(R.string.add_identify_paper_resolved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SegmentedOptionRow(
            options = PaperProfiles.GRAPHICS_CAPABLE.map { SegmentedOption(it.id, it.label) },
            selected = state.paperProfileId,
            onSelect = viewModel::setPaperProfile,
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::continueToTest,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_continue)) }
    }
}

/**
 * Turns a status snapshot into one short phrase.
 *
 * Faults come first: a printer that is both online and out of paper needs to say "out of paper".
 */
@Composable
private fun hardwareStatusLabel(status: PrinterStatus): String = when {
    status.paperOut -> stringResource(R.string.add_identify_status_paper_out)
    status.coverOpen -> stringResource(R.string.add_identify_status_cover_open)
    status.overheat -> stringResource(R.string.add_identify_status_overheat)
    !status.online -> stringResource(R.string.add_identify_status_offline)
    status.paperLow -> stringResource(R.string.add_identify_status_paper_low)
    else -> stringResource(R.string.add_identify_status_ok)
}

// -------------------------------------------------------------------------------------------
// Step 4: test print
// -------------------------------------------------------------------------------------------

@Composable
private fun TestStep(state: AddPrinterUiState, viewModel: AddPrinterViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.add_step_test),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.add_test_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val test = state.testState) {
            AddTestState.Idle -> Unit

            AddTestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.add_test_running))
            }

            AddTestState.Succeeded -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.add_test_success))
            }

            is AddTestState.Failed -> ErrorPanel(error = test.error)
        }

        Button(
            onClick = viewModel::runTest,
            enabled = state.testState != AddTestState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_test_print)) }

        OutlinedButton(
            onClick = viewModel::skipTest,
            enabled = state.testState != AddTestState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_skip_test)) }
    }
}
