package cn.dh.shiftschedule.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftCycleTest {

    private val preset = ShiftPreset(
        id = "five_three",
        name = "五班三倒",
        cycle = listOf("early", "mid", "study", "night", "rest"),
        anchorDateId = 20260101,
    )

    @Test
    fun anchorDayIsTheFirstShiftOfTheCycle() {
        assertEquals("early", ShiftCycle.typeIdFor(preset, 20260101))
        assertEquals("mid", ShiftCycle.typeIdFor(preset, 20260102))
        assertEquals("study", ShiftCycle.typeIdFor(preset, 20260103))
        assertEquals("night", ShiftCycle.typeIdFor(preset, 20260104))
        assertEquals("rest", ShiftCycle.typeIdFor(preset, 20260105))
    }

    @Test
    fun cycleWrapsAroundAndHandlesDatesBeforeTheAnchor() {
        assertEquals("early", ShiftCycle.typeIdFor(preset, 20260106))
        assertEquals(0, ShiftCycle.indexFor(preset, 20260111))
        assertEquals(4, ShiftCycle.indexFor(preset, 20251231))
        assertEquals(0, ShiftCycle.indexFor(preset, 20251227))
    }

    @Test
    fun emptyCycleHasNoShift() {
        val empty = preset.copy(cycle = emptyList())
        assertNull(ShiftCycle.typeIdFor(empty, 20260101))
        assertEquals(-1, ShiftCycle.indexFor(empty, 20260101))
    }

    @Test
    fun dayNumberCountsCalendarDaysAcrossMonthsAndYears() {
        assertEquals(1L, ShiftDateMath.dayNumber(20260102) - ShiftDateMath.dayNumber(20260101))
        assertEquals(1L, ShiftDateMath.dayNumber(20260201) - ShiftDateMath.dayNumber(20260131))
        assertEquals(1L, ShiftDateMath.dayNumber(20270101) - ShiftDateMath.dayNumber(20261231))
        assertEquals(0L, ShiftDateMath.dayNumber(19700101))
        assertEquals(28L, ShiftDateMath.dayNumber(20260301) - ShiftDateMath.dayNumber(20260201))
    }

    @Test
    fun plusDaysCrossesMonthBoundaries() {
        assertEquals(20260201, ShiftDateMath.plusDays(20260131, 1))
        assertEquals(20251231, ShiftDateMath.plusDays(20260101, -1))
        assertEquals(20240229, ShiftDateMath.plusDays(20240228, 1))
    }

    @Test
    fun datePartsRoundTrip() {
        val dateId = 20261029
        assertEquals(2026, ShiftDateMath.yearOf(dateId))
        assertEquals(10, ShiftDateMath.monthOf(dateId))
        assertEquals(29, ShiftDateMath.dayOf(dateId))
        assertEquals(dateId, ShiftDateMath.dateIdOf(2026, 10, 29))
    }

    @Test
    fun anchorRecoveredFromTodaysShift() {
        val preset = ShiftPreset(
            id = "p",
            name = "Five in three",
            cycle = listOf("a", "b", "c", "d", "e"),
            anchorDateId = 20260101,
        )
        // "Today is c" must produce a preset that really yields c today, whatever the date.
        val today = 20261029
        val anchor = ShiftCycle.anchorDateFor(preset, today, "c")
        assertNotNull(anchor)
        assertEquals("c", ShiftCycle.typeIdFor(preset.copy(anchorDateId = anchor!!), today))
        // And the cycle index keeps working before and after the anchor.
        assertEquals("c", ShiftCycle.typeIdFor(preset.copy(anchorDateId = anchor), today - 5))
        assertEquals("c", ShiftCycle.typeIdFor(preset.copy(anchorDateId = anchor), today + 10))
    }

    @Test
    fun offsetRecoveredFromTodaysShift() {
        val preset = ShiftPreset(
            id = "p",
            name = "Five in three",
            cycle = listOf("a", "b", "c", "d", "e"),
            anchorDateId = 20260101,
        )
        val today = 20261029
        val offset = ShiftCycle.offsetFor(preset, today, "b")
        assertNotNull(offset)
        assertEquals("b", ShiftCycle.typeIdFor(preset, today, offset!!))
        val currentShift = ShiftCycle.typeIdFor(preset, today)!!
        assertEquals(0, ShiftCycle.offsetFor(preset, today, currentShift))
    }

    @Test
    fun anchorFromTodayRejectsUnknownShift() {
        val preset = ShiftPreset("p", "P", listOf("a", "b"), 20260101)
        assertNull(ShiftCycle.anchorDateFor(preset, 20261029, "zzz"))
        assertNull(ShiftCycle.offsetFor(preset, 20261029, "zzz"))
    }
}
