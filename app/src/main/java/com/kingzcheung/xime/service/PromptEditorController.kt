package com.kingzcheung.xime.service

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.Toast
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PromptEditorController(private val service: XimeInputMethodService) {
    var editText: EditText? = null
        private set
    var inputConnection: InputConnection? = null
        private set
    private var draft = ""
    private var selectionStart = 0
    private var selectionEnd = 0
    @Volatile private var epoch = 0
    @Volatile private var submitting = false

    fun attach(view: EditText?) {
        editText?.let {
            draft = it.text.toString()
            selectionStart = it.selectionStart.coerceAtLeast(0)
            selectionEnd = it.selectionEnd.coerceAtLeast(0)
        }
        editText = view
        inputConnection = view?.onCreateInputConnection(EditorInfo())
        view?.apply {
            setText(draft)
            setSelection(selectionStart.coerceAtMost(length()), selectionEnd.coerceAtMost(length()))
            requestFocus()
        }
    }

    fun open() {
        if (service.uiState.value.promptVisible) return
        finishVoiceInput()
        service.finishDeleteSession()
        service.closeToolPanel()
        service.keyboardViewModel.closeOverlay()
        val pending = service.candidateState.value.inputText
        service.endComposingInputBox()
        service.rimeEngine.clearComposition()
        resetCandidates()
        service.predictionManager.invalidatePendingPredictions()
        epoch++
        service.uiState.value = service.uiState.value.copy(
            promptVisible = true,
            promptExpanded = false,
            promptFloating = false,
            promptCanSend = promptCanSend(service.currentInputEditorInfo?.imeOptions ?: 0),
            showQuickSendForm = false,
            quickSendFormFocused = false,
            quickSendCodeFocused = false,
            enterKeyText = tr(R.string.s_huanxing),
        )
        if (pending.isNotEmpty()) insert(pending)
    }

    fun requestClose() {
        if (!service.uiState.value.promptVisible || submitting) return
        submitting = true
        val token = epoch
        service.keyRouter.postRimeJob {
            finishComposition(token)
            withContext(Dispatchers.Main) { if (token == epoch) close() }
        }
    }

    fun close() {
        if (!service.uiState.value.promptVisible) return
        finishVoiceInput()
        epoch++
        submitting = false
        val pending = pendingInput()
        if (pending.isNotEmpty()) insert(pending)
        attach(null)
        service.rimeEngine.clearComposition()
        resetCandidates()
        service.uiState.value = service.uiState.value.copy(promptVisible = false, promptCanSend = false)
        service.currentInputEditorInfo?.let { service.updateEnterKeyText(it) }
    }

    fun insert(text: String) {
        if (!service.uiState.value.promptVisible) return
        val box = editText
        if (box == null) {
            val start = minOf(selectionStart, selectionEnd).coerceIn(0, draft.length)
            val end = maxOf(selectionStart, selectionEnd).coerceIn(start, draft.length)
            draft = draft.replaceRange(start, end, text)
            selectionStart = start + text.length
            selectionEnd = selectionStart
            return
        }
        val start = minOf(box.selectionStart, box.selectionEnd).coerceAtLeast(0)
        val end = maxOf(box.selectionStart, box.selectionEnd).coerceAtLeast(start)
        box.text.replace(start, end, text)
        box.setSelection(start + text.length)
    }

    fun sendKey(code: Int) {
        if (code == KeyEvent.KEYCODE_ENTER) insert("\n")
        else {
            inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    fun clear() {
        val token = epoch
        service.keyRouter.postRimeJob {
            if (token != epoch) return@postRimeJob
            service.rimeEngine.clearComposition()
            withContext(Dispatchers.Main) {
                if (token == epoch) {
                    resetCandidates()
                    draft = ""
                    selectionStart = 0
                    selectionEnd = 0
                    editText?.setText("")
                }
            }
        }
    }

    fun handleKey(key: String, shifted: Boolean): Boolean {
        if (!service.uiState.value.promptVisible) return false
        if (submitting) return true
        when (key) {
            "clear_all" -> { clear(); return true }
            "undo_clear" -> { editText?.onTextContextMenuItem(android.R.id.undo); return true }
        }
        if (key.length != 1 && key !in setOf("space", "enter", "newline", "delete")) return false
        val token = epoch
        service.keyRouter.postRimeJob {
            if (token != epoch) return@postRimeJob
            val composing = service.rimeEngine.getInput().isNotEmpty()
            when {
                key == "enter" || key == "newline" -> {
                    finishComposition(token)
                    withContext(Dispatchers.Main) { if (token == epoch) insert("\n") }
                }
                key == "delete" && !composing -> withContext(Dispatchers.Main) {
                    if (token == epoch) {
                        val box = editText
                        if (box != null) {
                            val range = promptDeleteRange(box.text.toString(), box.selectionStart, box.selectionEnd)
                            box.text.delete(range.first, range.second)
                            box.setSelection(range.first)
                        }
                    }
                }
                key == "space" && !composing -> withContext(Dispatchers.Main) {
                    if (token == epoch) insert(" ")
                }
                !composing && (service.uiState.value.isAsciiMode || key.singleOrNull()?.isLetter() != true) && key.length == 1 ->
                    withContext(Dispatchers.Main) { if (token == epoch) insert(if (shifted) key.uppercase() else key) }
                else -> {
                    val code = when (key) {
                        "delete" -> 0xff08
                        "space" -> 0x20
                        else -> key.lowercase()[0].code
                    }
                    val result = service.rimeEngine.processKeyAndGetResult(code, 0)
                    withContext(Dispatchers.Main) {
                        if (token == epoch) {
                            if (result.committedText.isNotEmpty()) insert(result.committedText)
                            else if (!result.processed && key.length == 1) insert(key)
                            service.sessionController.updateUIWithResult(result)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun finishVoiceInput() {
        service.sealVoiceSessionForSend()
        service.voiceRecognitionHandler.abandonSession()
    }

    private fun pendingInput(): String =
        if (isT9Schema(service.uiState.value.currentSchemaId)) {
            service.candidateState.value.preeditText.replace(" ", "").replace("'", "")
        } else service.rimeEngine.getInput()

    private fun resetCandidates() {
        service.candidateState.value = CandidateState()
        service.t9PartialSegments.clear()
        service.uiState.value = service.uiState.value.copy(
            t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = "",
        )
    }

    private suspend fun finishComposition(token: Int) {
        // 与候选点击共用队列；回车按现有键盘语义保留未选定的编码。
        if (token != epoch) return
        val text = pendingInput()
        service.rimeEngine.clearComposition()
        withContext(Dispatchers.Main) {
            if (token == epoch) {
                if (text.isNotEmpty()) insert(text)
                resetCandidates()
            }
        }
    }

    fun copyToClipboard() = submit(send = false, copy = true)

    fun submit(send: Boolean, copy: Boolean = false) {
        if (!service.uiState.value.promptVisible || submitting) return
        finishVoiceInput()
        submitting = true
        val token = epoch
        val session = service.uiState.value.inputSessionId
        service.keyRouter.postRimeJob {
            if (token != epoch) return@postRimeJob
            finishComposition(token)
            withContext(Dispatchers.Main) {
                if (token != epoch || session != service.uiState.value.inputSessionId) return@withContext
                submitting = false
                val text = editText?.text?.toString() ?: draft
                if (text.isBlank()) return@withContext
                if (copy) {
                    val success = runCatching {
                        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
                            ?: error("Clipboard unavailable")
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prompt", text))
                    }.isSuccess
                    if (success) clearAndClose()
                    else Toast.makeText(service, tr(R.string.prompt_copy_failed), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val ic = service.currentInputConnection
                if (ic == null || !runCatching { ic.commitText(text, 1) }.getOrDefault(false)) {
                    Toast.makeText(service, tr(R.string.prompt_submit_failed), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                service.noteSelfWriteCaretDelta(text.length)
                service.pluginEvents.onTextCommitted(text, true)
                if (send && promptCanSend(service.currentInputEditorInfo?.imeOptions ?: 0)) {
                    val accepted = runCatching { ic.performEditorAction(EditorInfo.IME_ACTION_SEND) }.getOrDefault(false)
                    if (!accepted) Toast.makeText(service, tr(R.string.prompt_send_unavailable), Toast.LENGTH_LONG).show()
                }
                clearAndClose()
            }
        }
    }

    private fun clearAndClose() {
        editText?.setText("")
        draft = ""
        selectionStart = 0
        selectionEnd = 0
        close()
    }
}
