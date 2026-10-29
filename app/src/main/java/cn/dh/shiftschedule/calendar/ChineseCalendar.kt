package cn.dh.shiftschedule.calendar

import com.nlf.calendar.Solar
import com.nlf.calendar.util.HolidayUtil
import java.util.Calendar

/**
 * Everything a calendar cell needs: Gregorian date, lunar date, solar terms, festivals, and the
 * statutory holiday / make-up workday flags.
 */
data class CalendarDay(
    val year: Int,
    val month: Int,
    val day: Int,
    /** Whether this day belongs to the displayed month (the grid pads its edges with neighbouring days). */
    val inCurrentMonth: Boolean,
    /** 0 = Monday, 6 = Sunday. */
    val weekdayIndex: Int,
    /** Small label under the day number: festival, solar term or lunar day (month name on the 1st). */
    val lunarLabel: String,
    /** Full lunar date such as "九月二十", shown by the detail card. */
    val lunarText: String,
    /** Sexagenary (stem-branch) year name, e.g. "丙午". */
    val yearGanZhi: String,
    /** Chinese zodiac animal, e.g. "马". */
    val zodiac: String,
    /** Solar term name when this day is one (立春, 冬至, ...), otherwise null. */
    val solarTerm: String?,
    /** Festival name when this day is one (中秋节, 元旦节, ...), otherwise null. */
    val festival: String?,
    /** Official holiday name from the State Council schedule, e.g. "春节"; null for years without data. */
    val holidayName: String?,
    /** Statutory day off (includes the days off created by the holiday swap). */
    val isRestDay: Boolean,
    /** Make-up workday (a weekend day worked to offset a long holiday). */
    val isWorkDay: Boolean,
) {
    /** Stable id used for selection state. */
    val id: Int get() = year * 10_000 + month * 100 + day
}

/**
 * Month grid data source. Lunar dates, solar terms, festivals and the statutory holiday schedule
 * are all computed or bundled locally by cn.6tail:lunar, so no network access is needed. The
 * holiday schedule currently ends at 2026, so later years simply have no "off / work" badge.
 */
object ChineseCalendar {

    private const val CACHE_SIZE = 24

    /** A month needs 42 lunar conversions and swiping rebuilds them constantly, so cache the last 24. */
    private val monthCache = object : LinkedHashMap<Int, List<CalendarDay>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<CalendarDay>>): Boolean =
            size > CACHE_SIZE
    }
    private val cacheLock = Any()

    /** Today. */
    fun today(): CalendarDay {
        val calendar = Calendar.getInstance()
        return dayOf(calendar)
    }

    /** Calendar info for the given Gregorian date. */
    fun day(year: Int, month: Int, dayOfMonth: Int): CalendarDay {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, dayOfMonth)
        return dayOf(calendar)
    }

    /**
     * Builds a fixed 6-week (42-cell) month grid that starts on Monday.
     * The first and last rows are padded with days from the neighbouring months so the grid
     * keeps a stable height.
     */
    fun monthGrid(year: Int, month: Int): List<CalendarDay> {
        val key = year * 100 + month
        synchronized(cacheLock) { monthCache[key] }?.let { return it }
        val grid = buildMonthGrid(year, month)
        synchronized(cacheLock) { monthCache[key] = grid }
        return grid
    }

    private fun buildMonthGrid(year: Int, month: Int): List<CalendarDay> {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, 1)

        // Calendar.DAY_OF_WEEK starts the week on Sunday (1); convert it to a Monday-based offset.
        val leading = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -leading)

        return List(42) {
            val day = dayOf(calendar).let {
                it.copy(inCurrentMonth = it.year == year && it.month == month)
            }
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            day
        }
    }

    private fun dayOf(calendar: Calendar): CalendarDay {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)

        val solar = Solar.fromYmd(year, month, dayOfMonth)
        val lunar = solar.getLunar()

        val lunarFestival = lunar.festivals.firstOrNull()
        val solarFestival = solar.festivals.firstOrNull()
        val solarTerm = lunar.jieQi.takeIf { it.isNotEmpty() }
        val holiday = HolidayUtil.getHoliday(year, month, dayOfMonth)

        val lunarText = lunar.monthInChinese + "月" + lunar.dayInChinese
        val lunarLabel = when {
            lunarFestival != null -> lunarFestival
            solarFestival != null -> solarFestival
            solarTerm != null -> solarTerm
            lunar.day == 1 -> lunar.monthInChinese + "月"
            else -> lunar.dayInChinese
        }

        return CalendarDay(
            year = year,
            month = month,
            day = dayOfMonth,
            inCurrentMonth = true,
            weekdayIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7,
            lunarLabel = lunarLabel,
            lunarText = lunarText,
            yearGanZhi = lunar.yearInGanZhi,
            zodiac = lunar.yearShengXiao,
            solarTerm = solarTerm,
            festival = lunarFestival ?: solarFestival,
            holidayName = holiday?.name,
            isRestDay = holiday != null && !holiday.isWork,
            isWorkDay = holiday != null && holiday.isWork,
        )
    }
}
