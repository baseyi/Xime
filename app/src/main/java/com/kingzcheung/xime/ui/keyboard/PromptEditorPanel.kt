package com.kingzcheung.xime.ui.keyboard

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr

internal fun promptPanelHeightDp(screenHeightDp: Int, expanded: Boolean = false): Int =
    if (expanded) (screenHeightDp / 2).coerceIn(200, 360)
    else (screenHeightDp / 5).coerceIn(144, 176)

@Composable
fun PromptEditorPanel(
    canSend: Boolean,
    onClose: () -> Unit,
    onSubmit: (Boolean) -> Unit,
    onAttach: (EditText?) -> Unit,
    onClear: () -> Unit,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    val height = promptPanelHeightDp(LocalConfiguration.current.screenHeightDp, expanded)
    val textColor = MaterialTheme.colorScheme.onSurface
    val attach by rememberUpdatedState(onAttach)
    DisposableEffect(Unit) { onDispose { attach(null) } }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Prompt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onToggleExpanded) {
                    Text(tr(if (expanded) R.string.prompt_shrink else R.string.prompt_expand))
                }
                TextButton(onClick = onClose) { Text(tr(R.string.prompt_collapse)) }
            }
            AndroidView(
                factory = { context ->
                    EditText(context).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        gravity = Gravity.TOP or Gravity.START
                        isSingleLine = false
                        setHorizontallyScrolling(false)
                        showSoftInputOnFocus = false
                        textSize = 13f
                        hint = tr(R.string.prompt_hint)
                        setTextColor(textColor.toArgb())
                        setHintTextColor(textColor.copy(alpha = 0.6f).toArgb())
                        attach(this)
                    }
                },
                update = { it.setTextColor(textColor.toArgb()) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClear) { Text(tr(R.string.prompt_clear)) }
                TextButton(onClick = { onSubmit(false) }) { Text(tr(R.string.prompt_insert)) }
                if (canSend) Button(onClick = { onSubmit(true) }) { Text(tr(R.string.s_fasong)) }
            }
        }
    }
}
