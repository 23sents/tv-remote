package com.example.tvremote.core

/** Quoting for a string embedded in an `adb shell` command line (which Android passes through `/system/bin/sh`). */
object ShellQuote {
    /** Wraps in single quotes, escaping any single quote as '\'' (end quote, escaped quote, reopen quote). */
    fun single(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
