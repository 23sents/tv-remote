package com.example.tvremote.core

/** Standard Android KeyEvent codes used by `input keyevent`. */
object RemoteKeys {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23      // DPAD_CENTER (confirm / OK)
    const val BACK = 4
    const val HOME = 3
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
}

/** One app shortcut button. [pkg] is what gets launched; it can be edited in Settings if it is wrong for a
 * particular TV/region, since package names are not fully standardised across app stores and devices. */
class AppShortcut(val id: String, val label: String, var pkg: String)

object DefaultApps {
    /** Best-known package names; YouTube and Netflix are the official Android TV app ids, confirmed from current
     * store listings. RuTube and Kinopoisk vary more by region/build, so treat those two as starting guesses. */
    fun defaults(): List<AppShortcut> = listOf(
        AppShortcut("youtube", "YouTube", "com.google.android.youtube.tv"),
        AppShortcut("rutube", "RuTube", "ru.rutube.app"),
        AppShortcut("netflix", "Netflix", "com.netflix.ninja"),
        AppShortcut("kinopoisk", "Кинопоиск", "ru.kinopoisk"),
    )
}
