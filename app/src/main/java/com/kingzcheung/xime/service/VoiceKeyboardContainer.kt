package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

class VoiceKeyboardContainer(
    context: Context,
    private val uiStateProvider: () -> InputUIState,
    private val onUiStateChanged: (InputUIState) -> Unit,
    private val onPerformVibration: (View) -> Unit,
    private val onPerformUndo: () -> Unit,
    private val onPerformSearch: () -> Unit,
    private val onStopRecognition: () -> Unit,
    private val isRecording: () -> Boolean,
    private val setRecording: (Boolean) -> Unit,
    private val onVoiceDismiss: () -> Unit = {},
    private val onTouchCancel: () -> Unit = {},
) : FrameLayout(context) {

    private var isTrackingVoiceButtons = false
    private var lastLeftActive = false
    private var lastRightActive = false
    private var lastHoldActive = false
    var voiceHoldBounds: Rect? = null
    private val locationInWindow = IntArray(2)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    fun enableVoiceButtonTracking() {
        isTrackingVoiceButtons = true
    }

    fun performHeldVoiceAction(send: Boolean) {
        val state = uiStateProvider()
        if (!state.isVoiceMode || state.voiceSticky || !state.voiceButtonState.held || !isRecording()) return
        onPerformVibration(this)
        if (send) onPerformSearch() else onPerformUndo()
        setRecording(false)
        // 与滑动动作共用封口、撤销/发送及结束流程，避免迟到结果再次上屏。
        onVoiceDismiss()
    }

    fun updateHeight(heightDp: Int) {
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()
        val params = layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            layoutParams = params
            requestLayout()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(it)
                }

                MotionEvent.ACTION_UP -> {
                    handleActionMove(it)
                    handleActionUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 系统手势（如三指截图）截走触摸流时，IME 收不到 UP，Compose 手势也不会被取消。
                    // 这里把 cancel 上抛，触发活动键盘 remount 来取消所有进行中的手势协程。
                    handleActionUp(cancelled = true)
                    onTouchCancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(it)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleActionDown(ev: MotionEvent) {
        val uiState = uiStateProvider()
        val isVoiceMode = uiState.isVoiceMode && !uiState.voiceSticky && !uiState.voiceButtonState.held

        lastLeftActive = false
        lastRightActive = false
        lastHoldActive = false

        if (isVoiceMode) {
            val yThreshold = height * 0.6f

            if (ev.y > yThreshold) {
                isTrackingVoiceButtons = true
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(bottomActive = true)
                ))
            }
        }
    }

    private fun handleActionUp(cancelled: Boolean = false) {
        val state = uiStateProvider()

        // 常驻语音（工具栏进入）不拦截触摸：空格键/工具栏自行结束语音
        if (state.voiceSticky || state.voiceButtonState.held) {
            isTrackingVoiceButtons = false
            lastLeftActive = false
            lastRightActive = false
            lastHoldActive = false
            return
        }

        if (!cancelled && isTrackingVoiceButtons && state.voiceButtonState.holdActive && isRecording()) {
            onUiStateChanged(state.copy(voiceButtonState = VoiceButtonState(held = true)))
            isTrackingVoiceButtons = false
            lastLeftActive = false
            lastRightActive = false
            lastHoldActive = false
            return
        }

        if (state.isVoiceMode || isRecording()) {
            if (!cancelled && state.voiceButtonState.leftActive) {
                onPerformUndo()
            } else if (!cancelled && state.voiceButtonState.rightActive) {
                onPerformSearch()
            }

            if (isRecording()) {
                onStopRecognition()
                setRecording(false)
            }

            if (state.isVoiceMode) {
                onVoiceDismiss()
            }
        }

        isTrackingVoiceButtons = false
        lastLeftActive = false
        lastRightActive = false
        lastHoldActive = false
    }

    private fun handleActionMove(ev: MotionEvent) {
        val state = uiStateProvider()
        val isVoiceMode = state.isVoiceMode && !state.voiceSticky && !state.voiceButtonState.held

        if (isVoiceMode && isTrackingVoiceButtons) {
            getLocationInWindow(locationInWindow)
            val overHold = voiceHoldBounds?.contains(
                Offset(ev.x + locationInWindow[0], ev.y + locationInWindow[1])
            ) == true
            if (overHold) {
                if (!lastHoldActive) onPerformVibration(this)
                lastHoldActive = true
                lastLeftActive = false
                lastRightActive = false
                onUiStateChanged(state.copy(voiceButtonState = VoiceButtonState(holdActive = true)))
                return
            }
            lastHoldActive = false
            val yThreshold = height * 0.6f
            val leftButtonEnd = width * 0.25f
            val rightButtonStart = width * 0.75f

            if (ev.y > yThreshold) {
                when {
                    ev.x < leftButtonEnd -> {
                        if (!lastLeftActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastLeftActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(leftActive = true)
                        ))
                    }
                    ev.x > rightButtonStart -> {
                        if (!lastRightActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastRightActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(rightActive = true)
                        ))
                    }
                    else -> {
                        lastLeftActive = false
                        lastRightActive = false
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(bottomActive = true)
                        ))
                    }
                }
            } else if (ev.x < leftButtonEnd) {
                if (!lastLeftActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastLeftActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(leftActive = true)
                ))
            } else if (ev.x > rightButtonStart) {
                if (!lastRightActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastRightActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(rightActive = true)
                ))
            } else {
                lastLeftActive = false
                lastRightActive = false
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState()
                ))
            }
        }
    }
}
