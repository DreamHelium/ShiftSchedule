package cn.dh.shiftschedule.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseCalendarTest {

    private fun day(year: Int, month: Int, dayOfMonth: Int): CalendarDay =
        ChineseCalendar.monthGrid(year, month)
            .first { it.year == year && it.month == month && it.day == dayOfMonth }

    @Test
    fun `springFestival2026IsFirstLunarDayAndHoliday`() {
        val springFestival = day(2026, 2, 17)
        assertEquals("正月初一", springFestival.lunarText)
        assertEquals("春节", springFestival.festival)
        assertEquals("丙午", springFestival.yearGanZhi)
        assertTrue(springFestival.isRestDay)
        assertFalse(springFestival.isWorkDay)
        assertEquals("周二", listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[springFestival.weekdayIndex])
    }

    @Test
    fun `makeUpWorkdayOn20260214`() {
        val makeUpDay = day(2026, 2, 14)
        assertTrue(makeUpDay.isWorkDay)
        assertFalse(makeUpDay.isRestDay)
        assertEquals("春节", makeUpDay.holidayName)
    }

    @Test
    fun `nationalDay2026IsHoliday`() {
        val nationalDay = day(2026, 10, 1)
        assertEquals("国庆节", nationalDay.festival)
        assertTrue(nationalDay.isRestDay)
    }

    @Test
    fun `solarTermIsReportedOnItsDate`() {
        val coldDew = day(2026, 10, 8)
        assertEquals("寒露", coldDew.solarTerm)
        assertEquals("寒露", coldDew.lunarLabel)
    }

    @Test
    fun `ordinaryDayShowsOnlyTheLunarDay`() {
        val ordinaryDay = day(2026, 10, 29)
        assertEquals("九月二十", ordinaryDay.lunarText)
        assertEquals("二十", ordinaryDay.lunarLabel)
        assertNull(ordinaryDay.solarTerm)
        assertNull(ordinaryDay.festival)
        assertFalse(ordinaryDay.isRestDay)
    }

    @Test
    fun `monthGridIsSixWeeksStartingMonday`() {
        val days = ChineseCalendar.monthGrid(2026, 10)
        assertEquals(42, days.size)
        assertEquals(2026 to 9, days.first().year to days.first().month)
        assertEquals(28, days.first().day)
        assertEquals(0, days.first().weekdayIndex)
        assertFalse(days.first().inCurrentMonth)
        assertEquals(1, days[3].day)
        assertTrue(days[3].inCurrentMonth)
    }

    @Test
    fun `dayLookupByGregorianDate`() {
        val springFestival = ChineseCalendar.day(2026, 2, 17)
        assertEquals("正月初一", springFestival.lunarText)
        assertEquals(springFestival, day(2026, 2, 17))
        assertEquals(2026 * 10_000 + 2 * 100 + 17, springFestival.id)
    }
}
