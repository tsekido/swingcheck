package jp.co.updates.swingcheck.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.display.HeightInput
import jp.co.updates.swingcheck.display.HeightParse
import jp.co.updates.swingcheck.settings.LengthUnit

/**
 * 身長の入力欄。単位が cm なら 1 つ、インチならフィートとインチの 2 つ。
 * 入力が正しい（または空の）ときだけ [onChange] を呼ぶ（空は null）。
 * 欄の初期値は [initialCm]（最初に表示したときだけ使う。入力のたびに書き換えて邪魔をしない）。
 */
@Composable
fun HeightField(
    unit: LengthUnit,
    initialCm: Float?,
    onChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun report(parsed: HeightParse) {
        when (parsed) {
            is HeightParse.Valid -> onChange(parsed.cm)
            HeightParse.Empty -> onChange(null)
            HeightParse.Invalid -> Unit
        }
    }

    when (unit) {
        LengthUnit.CM -> {
            var text by remember(unit) { mutableStateOf(HeightInput.cmText(initialCm)) }
            val parsed = HeightInput.parseCm(text)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    report(HeightInput.parseCm(it))
                },
                label = { Text(stringResource(R.string.height_label)) },
                suffix = { Text(stringResource(R.string.height_cm_suffix)) },
                isError = parsed == HeightParse.Invalid,
                supportingText = if (parsed == HeightParse.Invalid) {
                    { Text(stringResource(R.string.height_invalid)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = modifier.fillMaxWidth(),
            )
        }
        LengthUnit.INCH -> {
            val initial = remember(unit) { HeightInput.feetInchesText(initialCm) }
            var feet by remember(unit) { mutableStateOf(initial.first) }
            var inches by remember(unit) { mutableStateOf(initial.second) }
            val parsed = HeightInput.parseFeetInches(feet, inches)
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = feet,
                    onValueChange = {
                        feet = it
                        report(HeightInput.parseFeetInches(it, inches))
                    },
                    label = { Text(stringResource(R.string.height_feet_label)) },
                    isError = parsed == HeightParse.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = inches,
                    onValueChange = {
                        inches = it
                        report(HeightInput.parseFeetInches(feet, it))
                    },
                    label = { Text(stringResource(R.string.height_inches_label)) },
                    isError = parsed == HeightParse.Invalid,
                    supportingText = if (parsed == HeightParse.Invalid) {
                        { Text(stringResource(R.string.height_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
