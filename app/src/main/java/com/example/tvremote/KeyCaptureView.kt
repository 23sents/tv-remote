package com.example.tvremote

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible 1x1 input field. While it is focused, the phone's own on-screen keyboard shows, and everything
 * typed is forwarded to the TV via `send` (one of core.RemoteKeys or raw text), instead of being typed locally.
 */
class KeyCaptureView(ctx: Context, private val sendText: (String) -> Unit, private val sendKey: (Int) -> Unit) : View(ctx) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return Conn(this)
    }

    private inner class Conn(v: View) : BaseInputConnection(v, false) {
        private var composing = ""

        private fun flush(new: String) {
            var common = 0
            while (common < composing.length && common < new.length && composing[common] == new[common]) common++
            repeat(composing.length - common) { sendKey(RemoteKeycodes.DEL) }
            if (new.length > common) sendText(new.substring(common))
            composing = new
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            flush(text.toString())
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (composing.isNotEmpty()) { flush(text.toString()); composing = "" } else sendText(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) composing = composing.dropLast(minOf(beforeLength, composing.length))
            repeat(beforeLength) { sendKey(RemoteKeycodes.DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> sendKey(RemoteKeycodes.DEL)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> sendKey(RemoteKeycodes.ENTER)
                else -> {
                    val u = event.unicodeChar
                    if (u > 0) sendText(String(Character.toChars(u)))
                }
            }
            return true
        }
    }
}

object RemoteKeycodes {
    const val DEL = 67     // KEYCODE_DEL
    const val ENTER = 66   // KEYCODE_ENTER
}
