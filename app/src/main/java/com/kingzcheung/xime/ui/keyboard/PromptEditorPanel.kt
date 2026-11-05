package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr

internal fun promptPanelHeightDp(screenHeightDp: Int, expanded: Boolean = false): Int =
    if (expanded) (screenHeightDp / 2).coerceIn(200, 360)
    else (screenHeightDp / 5).coerceIn(144, 176)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PromptEditorPanel(
    canSend: Boolean,
    onClose: () -> Unit,
    onSubmit: (Boolean) -> Unit,
    onAttach: (EditText?) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit = {},
    floating: Boolean = false,
    onToggleFloating: () -> Unit = {},
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var popupBounds by remember { mutableStateOf(IntRect.Zero) }
    var popupWindowWidth by remember { mutableIntStateOf(0) }
    var availableHeightPx by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("prompt_window", android.content.Context.MODE_PRIVATE) }
    val initialHeight = promptPanelHeightDp(configuration.screenHeightDp).toFloat()
    var floatingWidth by remember { mutableFloatStateOf(prefs.getFloat("width", 400f)) }
    var floatingHeight by remember { mutableFloatStateOf(prefs.getFloat("height", initialHeight)) }
    var fixedPosition by remember {
        mutableStateOf(if (prefs.contains("x") && prefs.contains("y")) {
            with(density) { Offset(prefs.getFloat("x", 0f).dp.toPx(), prefs.getFloat("y", 0f).dp.toPx()) }
        } else null)
    }
    val saveGeometry by rememberUpdatedState<() -> Unit>({
        prefs.edit().apply {
            putFloat("width", floatingWidth)
            putFloat("height", floatingHeight)
            val position = fixedPosition
            if (position == null) {
                remove("x")
                remove("y")
            } else {
                putFloat("x", with(density) { position.x.toDp().value })
                putFloat("y", with(density) { position.y.toDp().value })
            }
        }.apply()
    })
    LaunchedEffect(floatingWidth, floatingHeight, fixedPosition) {
        delay(250)
        saveGeometry()
    }
    DisposableEffect(Unit) { onDispose { saveGeometry() } }
    val rawPointer = remember { arrayOf(Offset.Zero) }
    val currentContent by rememberUpdatedState<@Composable (Boolean) -> Unit>({ isFloating ->
        PromptEditorBody(
            canSend, onClose, onSubmit, onAttach, onClear, onCopy, expanded,
            {
                if (isFloating) {
                    floatingHeight = promptPanelHeightDp(configuration.screenHeightDp, !expanded).toFloat()
                }
                onToggleExpanded()
            }, isFloating, onToggleFloating,
            {
                if (expanded) onToggleExpanded()
                floatingWidth = 400f
                floatingHeight = initialHeight
                fixedPosition = null
                prefs.edit().clear().apply()
            },
            Modifier.pointerInput(isFloating) {
                if (isFloating) detectDragGestures { change, delta ->
                    change.consume()
                    fixedPosition = Offset(popupBounds.left.toFloat(), popupBounds.top.toFloat()) + delta
                }
            },
        )
    })
    val content = remember { movableContentOf<Boolean> { currentContent(it) } }
    if (floating) {
        val position = remember(fixedPosition) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val keyboardTop = anchorBounds.top.coerceIn(0, windowSize.height)
                    availableHeightPx = keyboardTop
                    val maxY = (keyboardTop - popupContentSize.height).coerceAtLeast(0)
                    val offset = IntOffset(
                        (fixedPosition?.x?.roundToInt() ?: (maxX / 2)).coerceIn(0, maxX),
                        (fixedPosition?.y?.roundToInt() ?: maxY).coerceIn(0, maxY),
                    )
                    popupWindowWidth = windowSize.width
                    popupBounds = IntRect(offset, popupContentSize)
                    return offset
                }
            }
        }
        Popup(
            popupPositionProvider = position,
            properties = PopupProperties(focusable = false, clippingEnabled = true),
        ) {
            BoxWithConstraints {
                val maxWidthDp = maxWidth.value.coerceAtMost(configuration.screenWidthDp.toFloat())
                val aboveKeyboardDp = with(density) { (availableHeightPx ?: 0).toDp().value }
                val maxHeightDp = maxHeight.value
                    .coerceAtMost(configuration.screenHeightDp.toFloat())
                    .coerceAtMost(aboveKeyboardDp)
                val minWidthDp = 160f.coerceAtMost(maxWidthDp)
                val minHeightDp = 144f.coerceAtMost(maxHeightDp)
                val width = floatingWidth.coerceIn(minWidthDp, maxWidthDp)
                val height = floatingHeight.coerceIn(minHeightDp, maxHeightDp)
                val resize by rememberUpdatedState<(Float) -> Unit>({ zoom ->
                    floatingWidth = (width * zoom).coerceIn(minWidthDp, maxWidthDp)
                    floatingHeight = (height * zoom).coerceIn(minHeightDp, maxHeightDp)
                })
                val resizeEdge by rememberUpdatedState<(Int, IntRect, Offset) -> Unit>({ edges, bounds, delta ->
                    val minW = with(density) { minWidthDp.dp.toPx() }
                    val minH = with(density) { minHeightDp.dp.toPx() }
                    val bottomLimit = (availableHeightPx ?: bounds.bottom).toFloat()
                    var left = bounds.left.toFloat()
                    var right = bounds.right.toFloat()
                    var top = bounds.top.toFloat()
                    var bottom = bounds.bottom.toFloat()
                    if (edges and 1 != 0) left = (left + delta.x).coerceIn(0f, (right - minW).coerceAtLeast(0f))
                    if (edges and 2 != 0) right = (right + delta.x).coerceIn((left + minW).coerceAtMost(popupWindowWidth.toFloat()), popupWindowWidth.toFloat())
                    if (edges and 4 != 0) top = (top + delta.y).coerceIn(0f, (bottom - minH).coerceAtLeast(0f))
                    if (edges and 8 != 0) bottom = (bottom + delta.y).coerceIn((top + minH).coerceAtMost(bottomLimit), bottomLimit)
                    val newWidth = right - left
                    val newHeight = bottom - top
                    floatingWidth = with(density) { newWidth.toDp().value }
                    floatingHeight = with(density) { newHeight.toDp().value }
                    fixedPosition = Offset(left, top)
                })
                Box(
                    Modifier.size(width.dp, height.dp).alpha(if (availableHeightPx == null) 0f else 1f).motionEventSpy { event ->
                        rawPointer[0] = Offset(event.rawX, event.rawY)
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val edgeSize = 12.dp.toPx()
                            val distances = listOf(
                                down.position.x to 1,
                                (size.width - down.position.x) to 2,
                                down.position.y to 4,
                                (size.height - down.position.y) to 8,
                            )
                            val nearest = distances.minBy { it.first }
                            val edges = if (nearest.first < edgeSize) nearest.second else 0
                            val initialBounds = popupBounds
                            val initialPointer = rawPointer[0]
                            var resizing = false
                            if (edges != 0) down.consume()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    resizing = true
                                    val zoom = event.calculateZoom()
                                    if (zoom.isFinite() && zoom > 0f) resize(zoom)
                                }
                                if (!resizing && edges != 0) {
                                    event.changes.firstOrNull { it.id == down.id && it.pressed }?.let {
                                        resizeEdge(edges, initialBounds, rawPointer[0] - initialPointer)
                                    }
                                }
                                if (resizing || edges != 0) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                ) { content(true) }
            }
        }
    } else {
        content(false)
    }
}

@Composable
private fun PromptEditorBody(
    canSend: Boolean,
    onClose: () -> Unit,
    onSubmit: (Boolean) -> Unit,
    onAttach: (EditText?) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    floating: Boolean,
    onToggleFloating: () -> Unit,
    onReset: () -> Unit,
    dragModifier: Modifier,
) {
    val height = promptPanelHeightDp(LocalConfiguration.current.screenHeightDp, expanded)
    val textColor = MaterialTheme.colorScheme.onSurface
    val attach by rememberUpdatedState(onAttach)
    DisposableEffect(Unit) { onDispose { attach(null) } }
    Surface(
        tonalElevation = 2.dp,
        shape = if (floating) MaterialTheme.shapes.large else RectangleShape,
        border = if (floating) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = if (floating) Modifier.fillMaxSize().padding(top = 5.dp) else Modifier.fillMaxWidth().height(height.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Prompt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.then(dragModifier).padding(top = 12.dp))
                TextButton(onClick = onToggleFloating) { Text(if (floating) "停靠" else "悬浮") }
                TextButton(onClick = onToggleExpanded) {
                    Text(tr(if (expanded) R.string.prompt_shrink else R.string.prompt_expand))
                }
                if (floating) TextButton(onClick = onReset) { Text("复位") }
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
                TextButton(onClick = onCopy) { Text(tr(R.string.prompt_copy)) }
                TextButton(onClick = { onSubmit(false) }) { Text(tr(R.string.prompt_insert)) }
                if (canSend) Button(onClick = { onSubmit(true) }) { Text(tr(R.string.s_fasong)) }
            }
        }
    }
}
