package com.kingzcheung.xime.ui

import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.ui.keyboard.PromptEditorPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptEditorPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multilineDraftIsSubmittedWithoutChangingWhitespace() {
        var editor: EditText? = null
        var submitted: String? = null
        var sendRequested = true
        compose.setContent {
            MaterialTheme {
                PromptEditorPanel(
                    canSend = false,
                    onClose = {},
                    onSubmit = { send -> submitted = editor?.text.toString(); sendRequested = send },
                    onAttach = { editor = it },
                    onClear = { editor?.setText("") },
                )
            }
        }
        val draft = "第一段 😊\n\n  第二段保留缩进\n".repeat(1000)
        compose.runOnIdle { editor!!.setText(draft) }
        compose.onNodeWithText(tr(R.string.s_fasong)).assertDoesNotExist()
        compose.onNodeWithText(tr(R.string.prompt_insert)).performClick()
        compose.runOnIdle {
            assertEquals(draft, submitted)
            assertFalse(sendRequested)
        }
    }
}
