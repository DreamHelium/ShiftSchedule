package cn.dh.shiftschedule.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Formats a date with a SimpleDateFormat pattern from resources, following the system locale. */
@Composable
fun formatDate(@StringRes patternRes: Int, year: Int, month: Int, day: Int): String {
    val pattern = stringResource(patternRes)
    val locale = Locale.getDefault()
    return remember(pattern, locale, year, month, day) {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
        SimpleDateFormat(pattern, locale).format(calendar.time)
    }
}
