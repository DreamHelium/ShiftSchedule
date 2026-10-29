package cn.dh.shiftschedule.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide settings.
 *
 * Kept in memory as Compose state (reads and writes trigger recomposition) and persisted to
 * SharedPreferences. Call [init] once before use, usually from Activity#onCreate.
 */
object AppSettings {
    private const val PREFS_NAME = "settings"
    private const val KEY_JUMP_TO_OTHER_MONTH = "jump_to_other_month"

    private var prefs: SharedPreferences? = null

    /** Whether tapping a padded previous/next-month day flips the calendar to that month. */
    var jumpToOtherMonth by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val store = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        jumpToOtherMonth = store.getBoolean(KEY_JUMP_TO_OTHER_MONTH, true)
    }

    fun updateJumpToOtherMonth(value: Boolean) {
        jumpToOtherMonth = value
        prefs?.edit()?.putBoolean(KEY_JUMP_TO_OTHER_MONTH, value)?.apply()
    }
}
