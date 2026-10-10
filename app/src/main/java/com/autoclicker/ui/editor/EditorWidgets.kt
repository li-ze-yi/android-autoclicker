package com.autoclicker.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 百分比数值（0..1）显示为 0..100 的整数字符串。 */
fun formatPercent(v: Float): String = (v * 100f).roundToInt().toString()

/** 解析 0..100 的输入为 0..1 浮点，非法时回退。 */
fun parsePercent(s: String, fallback: Float): Float =
    s.trim().toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: fallback

fun parseLong(s: String, fallback: Long): Long = s.trim().toLongOrNull() ?: fallback

fun parseInt(s: String, fallback: Int): Int = s.trim().toIntOrNull() ?: fallback

fun parseFloat(s: String, fallback: Float): Float = s.trim().toFloatOrNull() ?: fallback

/** Int(ARGB) -> "#RRGGBB"。 */
fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

/** "#RRGGBB" 或 "#AARRGGBB" -> Int，非法时回退。 */
fun parseColor(s: String, fallback: Int): Int {
    val hex = s.trim().removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return fallback
    return when (hex.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> value.toInt()
        else -> fallback
    }
}

@Composable
fun TextFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
fun NumberFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onValueChange(s.filter { it.isDigit() || it == '-' || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
fun BoolFieldRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 只读下拉选择器（用只读输入框 + 覆盖层触发菜单，避免实验性 API）。 */
@Composable
fun <T> ChoiceField(
    label: String,
    selected: T?,
    options: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "未选择"
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/** 两个 X/Y（%）输入框。 */
@Composable
fun PercentPointFields(
    xValue: String,
    yValue: String,
    onXChange: (String) -> Unit,
    onYChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = xValue,
            onValueChange = onXChange,
            label = { Text("X（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = yValue,
            onValueChange = onYChange,
            label = { Text("Y（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
    }
}

/** 四个 L/T/R/B（%）输入框。 */
@Composable
fun PercentRectFields(
    lValue: String,
    tValue: String,
    rValue: String,
    bValue: String,
    onLChange: (String) -> Unit,
    onTChange: (String) -> Unit,
    onRChange: (String) -> Unit,
    onBChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = lValue,
            onValueChange = onLChange,
            label = { Text("左（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = tValue,
            onValueChange = onTChange,
            label = { Text("上（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = rValue,
            onValueChange = onRChange,
            label = { Text("右（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = bValue,
            onValueChange = onBChange,
            label = { Text("下（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
    }
}