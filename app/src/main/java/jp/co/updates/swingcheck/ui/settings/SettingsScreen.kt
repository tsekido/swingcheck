package jp.co.updates.swingcheck.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.settings.FpsMode
import jp.co.updates.swingcheck.settings.LengthUnit
import jp.co.updates.swingcheck.ui.common.BackButton
import jp.co.updates.swingcheck.ui.common.HeightField
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenDev: (() -> Unit)?) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_fps))
            Column(Modifier.selectableGroup()) {
                FpsMode.entries.forEach { mode ->
                    RadioRow(
                        label = mode.fps?.let { stringResource(R.string.settings_fps_value, it) }
                            ?: stringResource(R.string.settings_fps_auto),
                        selected = s.fpsMode == mode,
                        onClick = { scope.launch { repo.setFpsMode(mode) } },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle(stringResource(R.string.settings_unit))
            Column(Modifier.selectableGroup()) {
                RadioRow(
                    stringResource(R.string.settings_unit_cm),
                    s.lengthUnit == LengthUnit.CM,
                ) { scope.launch { repo.setLengthUnit(LengthUnit.CM) } }
                RadioRow(
                    stringResource(R.string.settings_unit_inch),
                    s.lengthUnit == LengthUnit.INCH,
                ) { scope.launch { repo.setLengthUnit(LengthUnit.INCH) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle(stringResource(R.string.settings_default_height))
            // 単位を切り替えたら入力欄を作り直す（保存してある cm から表示し直す）
            HeightField(
                unit = s.lengthUnit,
                initialCm = s.defaultHeightCm,
                onChange = { cm -> scope.launch { repo.setDefaultHeightCm(cm) } },
            )
            Text(
                stringResource(R.string.settings_default_height_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_practice_filter), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_practice_filter_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.practiceFilterEnabled,
                    onCheckedChange = { scope.launch { repo.setPracticeFilterEnabled(it) } },
                )
            }

            if (onOpenDev != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onOpenDev) { Text(stringResource(R.string.settings_dev_tools)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
