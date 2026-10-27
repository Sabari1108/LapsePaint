package com.lapsepaint.app

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/**
 * Menus and dialogs in the Miuix (HyperOS) style, callable from the View-based screens.
 * Each call adds a small ComposeView to the screen; it removes itself once the dialog
 * has finished animating out. The activity must be a ComponentActivity.
 */

/** A slider row: whole-number value from [min] to [max]; [label] describes the current value. */
class SliderRow(val min: Int, val max: Int, val start: Int, val label: (Int) -> String, val onChange: (Int) -> Unit)

/** An on/off row. */
class SwitchRow(val title: String, val start: Boolean, val onChange: (Boolean) -> Unit)

@Composable
fun LapseTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme()
    // Plain Text and text fields take this colour; Miuix's default is black.
    MiuixTheme(colors = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

private fun Activity.showDialog(
    title: String?,
    summary: String? = null,
    cancelable: Boolean = true,
    onDismissed: () -> Unit = {},
    body: @Composable (close: () -> Unit) -> Unit,
) {
    val root = findViewById<ViewGroup>(android.R.id.content)
    val host = ComposeView(this)
    root.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    host.setContent {
        var show by remember { mutableStateOf(true) }
        LapseTheme {
            WindowDialog(
                show = show,
                title = title,
                summary = summary,
                onDismissRequest = if (cancelable) ({ show = false }) else null,
                onDismissFinished = {
                    onDismissed()
                    root.post { root.removeView(host) }
                },
            ) {
                body { show = false }
            }
        }
    }
}

/** A list of choices; picking one closes the dialog and runs it. */
fun Activity.miuixMenu(title: String, items: List<Pair<String, () -> Unit>>) = showDialog(title) { close ->
    Column {
        items.forEach { (label, action) ->
            SuperArrow(title = label, onClick = {
                close()
                action()
            })
        }
    }
}

/** A question with Cancel and a confirm button. */
fun Activity.miuixConfirm(
    title: String?,
    message: String,
    confirm: String,
    cancel: String = "Cancel",
    cancelable: Boolean = true,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit,
) = showDialog(title, message, cancelable) { close ->
    DialogButtons(cancel, confirm, onCancel = { close(); onCancel() }, onConfirm = { close(); onConfirm() })
}

/** A one-line text field, prefilled with [initial] (all selected). */
fun Activity.miuixInput(title: String, initial: String, onOk: (String) -> Unit) = showDialog(title) { close ->
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    Column {
        TextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        DialogButtons("Cancel", "OK", onCancel = close, onConfirm = { close(); onOk(value.text.trim()) })
    }
}

/** Live sliders (and switches) that apply as they move, with a Done button. */
fun Activity.miuixSliders(
    title: String,
    rows: List<SliderRow>,
    switches: List<SwitchRow> = emptyList(),
    onDone: () -> Unit = {},
) = showDialog(title, onDismissed = onDone) { close ->
    Column {
        rows.forEach { row ->
            var v by remember { mutableIntStateOf(row.start) }
            Text(row.label(v), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Slider(
                value = v.toFloat(),
                onValueChange = {
                    val n = Math.round(it)
                    if (n != v) {
                        v = n
                        row.onChange(n)
                    }
                },
                valueRange = row.min.toFloat()..row.max.toFloat(),
            )
        }
        switches.forEach { sw ->
            var on by remember { mutableStateOf(sw.start) }
            SuperSwitch(checked = on, onCheckedChange = { on = it; sw.onChange(it) }, title = sw.title)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(text = "Done", onClick = close, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

@Composable
private fun DialogButtons(cancel: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(text = cancel, onClick = onCancel, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        TextButton(text = confirm, onClick = onConfirm, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
    }
}
