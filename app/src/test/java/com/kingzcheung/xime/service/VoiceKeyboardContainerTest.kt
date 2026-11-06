package com.kingzcheung.xime.service

import android.content.Context
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

class VoiceKeyboardContainerTest {
    private var state = InputUIState(isVoiceMode = true)
    private var recording = true
    private var stops = 0
    private var dismisses = 0
    private var undos = 0
    private var sends = 0
    private var vibrations = 0
    private var cancellations = 0
    private lateinit var container: VoiceKeyboardContainer

    @Before
    fun setUp() {
        container = spy(VoiceKeyboardContainer(
            context = mock<Context>(),
            uiStateProvider = { state },
            onUiStateChanged = { state = it },
            onPerformVibration = { vibrations++ },
            onPerformUndo = { undos++ },
            onPerformSearch = { sends++ },
            onStopRecognition = { stops++ },
            isRecording = { recording },
            setRecording = { recording = it },
            onVoiceDismiss = { dismisses++ },
            onTouchCancel = { cancellations++ }
        ))
        doReturn(400).whenever(container).width
        doReturn(400).whenever(container).height
        doAnswer {
            val location = it.getArgument<IntArray>(0)
            location[0] = 30
            location[1] = 100
            null
        }.whenever(container).getLocationInWindow(any())
        container.voiceHoldBounds = Rect(170f, 340f, 230f, 400f)
        container.enableVoiceButtonTracking()
    }

    private fun touch(action: Int, x: Float = 170f, y: Float = 270f) {
        val event = mock<MotionEvent>()
        whenever(event.action).thenReturn(action)
        whenever(event.x).thenReturn(x)
        whenever(event.y).thenReturn(y)
        container.dispatchTouchEvent(event)
    }

    @Test
    fun `滑到实际按钮范围松手保持录音且后续触摸不结束`() {
        touch(MotionEvent.ACTION_MOVE)
        assertTrue(state.voiceButtonState.holdActive)
        assertEquals(1, vibrations)
        touch(MotionEvent.ACTION_UP)
        assertTrue(state.voiceButtonState.held)
        assertTrue(recording)
        touch(MotionEvent.ACTION_DOWN, 20f, 300f)
        touch(MotionEvent.ACTION_UP, 20f, 300f)
        assertEquals(0, stops)
        assertEquals(0, dismisses)
        assertEquals(0, undos)
        assertEquals(0, sends)
    }

    @Test
    fun `移出保持按钮后松手正常结束`() {
        touch(MotionEvent.ACTION_MOVE)
        touch(MotionEvent.ACTION_UP, 250f, 350f)
        assertFalse(state.voiceButtonState.held)
        assertFalse(recording)
        assertEquals(1, stops)
        assertEquals(1, dismisses)
    }

    @Test
    fun `抬手位置命中按钮即保持且移出重入重新反馈`() {
        touch(MotionEvent.ACTION_MOVE)
        touch(MotionEvent.ACTION_MOVE, 250f, 350f)
        touch(MotionEvent.ACTION_UP)
        assertEquals(2, vibrations)
        assertTrue(state.voiceButtonState.held)
    }

    @Test
    fun `保持目标上的系统取消结束录音不锁定`() {
        touch(MotionEvent.ACTION_MOVE)
        touch(MotionEvent.ACTION_CANCEL)
        assertFalse(state.voiceButtonState.held)
        assertEquals(1, stops)
        assertEquals(1, cancellations)
    }

    @Test
    fun `原撤销和发送松手动作仍然执行`() {
        touch(MotionEvent.ACTION_UP, 20f, 300f)
        assertEquals(1, undos)
        assertEquals(0, sends)
        assertEquals(1, stops)
        recording = true
        container.enableVoiceButtonTracking()
        touch(MotionEvent.ACTION_UP, 380f, 300f)
        assertEquals(1, sends)
        assertEquals(2, stops)
    }

    @Test
    fun `发送区域的取消不触发发送`() {
        touch(MotionEvent.ACTION_MOVE, 380f, 300f)
        touch(MotionEvent.ACTION_CANCEL)
        assertEquals(0, sends)
        assertEquals(1, stops)
    }

    @Test
    fun `工具栏常驻语音不受保持命中影响`() {
        state = state.copy(voiceSticky = true)
        touch(MotionEvent.ACTION_MOVE)
        touch(MotionEvent.ACTION_UP)
        assertFalse(state.voiceButtonState.held)
        assertTrue(recording)
        assertEquals(0, stops)
    }

    @Test
    fun `收尾已停止录音时不进入保持`() {
        recording = false
        touch(MotionEvent.ACTION_UP)
        assertFalse(state.voiceButtonState.held)
        assertEquals(1, dismisses)
    }

    @Test
    fun `保持后点击撤销执行动作并结束且重复点击无效`() {
        touch(MotionEvent.ACTION_UP)
        container.performHeldVoiceAction(send = false)
        assertEquals(1, undos)
        assertEquals(0, sends)
        assertEquals(1, dismisses)
        assertFalse(recording)
        container.performHeldVoiceAction(send = false)
        container.performHeldVoiceAction(send = true)
        assertEquals(1, undos)
        assertEquals(0, sends)
        assertEquals(1, dismisses)
    }

    @Test
    fun `保持后点击发送执行动作并结束`() {
        touch(MotionEvent.ACTION_UP)
        container.performHeldVoiceAction(send = true)
        assertEquals(0, undos)
        assertEquals(1, sends)
        assertEquals(1, dismisses)
        assertFalse(recording)
        assertEquals(2, vibrations)
    }

    @Test
    fun `未保持时点击回调不提前执行滑动动作`() {
        container.performHeldVoiceAction(send = false)
        container.performHeldVoiceAction(send = true)
        assertEquals(0, undos)
        assertEquals(0, sends)
        assertEquals(0, dismisses)
        assertTrue(recording)
    }

    @Test
    fun `保持会话已经收尾时不再执行撤销和发送`() {
        state = state.copy(voiceButtonState = VoiceButtonState(held = true))
        recording = false
        container.performHeldVoiceAction(send = false)
        container.performHeldVoiceAction(send = true)
        assertEquals(0, undos)
        assertEquals(0, sends)
        assertEquals(0, dismisses)
    }
}
