package cn.dh.shiftschedule.shift

import java.util.Calendar

/**
 * One kind of shift, e.g. morning, evening, study, night or a rest day.
 *
 * Names are plain user data (not string resources) because they are editable in the app; the
 * built-in defaults happen to be Chinese, which is the audience this app targets.
 */
data class ShiftType(
    val id: String,
    val name: String,
    val shortName: String,
    /** ARGB colour, e.g. 0xFFF2994A. */
    val color: Long,
    val isRest: Boolean = false,
)

/**
 * A repeating rotation. [cycle] holds [ShiftType.id]s, one entry per day of the cycle, and
 * [anchorDateId] is the day whose shift is `cycle[0]`; every other day is derived from it.
 *
 * "五班三倒" is just a 5-day cycle: 早 -> 中 -> 学习 -> 夜 -> 休息.
 */
data class ShiftPreset(
    val id: String,
    val name: String,
    val cycle: List<String>,
    /** yyyyMMdd of the day that maps to cycle[0]. */
    val anchorDateId: Int,
) {
    val length: Int get() = cycle.size
}

/**
 * One crew (值) on the roster: a person or group that follows [presetId]'s rotation shifted by
 * [offsetDays]. Offset 0 is in phase with the preset anchor, offset 1 is one day behind it, and so
 * on, which is exactly how "一值/二值/三值" teams rotate through the same 5-day cycle.
 */
data class Crew(
    val id: String,
    val name: String,
    val presetId: String,
    val offsetDays: Int,
    /** The phone owner: only this crew shows manual overrides and the "today" summary. */
    val isMe: Boolean = false,
)

/** The shift resolved for one date, plus whether it was overridden by hand. */
data class DayShift(
    val type: ShiftType,
    val isOverride: Boolean,
)

/** A crew plus the shift it works on a given date. */
data class CrewShift(
    val crew: Crew,
    val shift: DayShift,
)

/** Date helpers. Dates are `yyyyMMdd` ints, the same id the calendar uses. */
object ShiftDateMath {

    fun dateIdOf(year: Int, month: Int, day: Int): Int = year * 10_000 + month * 100 + day

    fun yearOf(dateId: Int): Int = dateId / 10_000

    fun monthOf(dateId: Int): Int = dateId / 100 % 100

    fun dayOf(dateId: Int): Int = dateId % 100

    /**
     * Days since 1970-01-01 for a civil date, computed without Calendar/time zone so the
     * rotation maths stays stable on every device (and testable on the JVM).
     */
    fun dayNumber(dateId: Int): Long {
        val (era, yearOfEra) = run {
            var year = yearOf(dateId).toLong()
            val month = monthOf(dateId).toLong()
            if (month <= 2) year -= 1
            Math.floorDiv(year, 400L) to (year - Math.floorDiv(year, 400L) * 400)
        }
        val monthIndex = (monthOf(dateId).toLong() + 9) % 12
        val dayOfYear = (153 * monthIndex + 2) / 5 + dayOf(dateId) - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    fun plusDays(dateId: Int, days: Int): Int {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(yearOf(dateId), monthOf(dateId) - 1, dayOf(dateId))
            add(Calendar.DAY_OF_MONTH, days)
        }
        return dateIdOf(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}

/** Pure rotation maths, kept free of Android so it can be unit tested. */
object ShiftCycle {

    /**
     * Position inside [preset]'s cycle for [dateId], or -1 when the cycle is empty.
     * [offsetDays] shifts the phase, which is how crews (值) of the same rotation differ.
     */
    fun indexFor(preset: ShiftPreset, dateId: Int, offsetDays: Int = 0): Int {
        val size = preset.length
        if (size == 0) {
            return -1
        }
        val diff = ShiftDateMath.dayNumber(dateId) - ShiftDateMath.dayNumber(preset.anchorDateId) +
            offsetDays
        return Math.floorMod(diff, size.toLong()).toInt()
    }

    /** Shift type id scheduled for [dateId], ignoring manual overrides. */
    fun typeIdFor(preset: ShiftPreset, dateId: Int, offsetDays: Int = 0): String? {
        val index = indexFor(preset, dateId, offsetDays)
        return if (index < 0 || index >= preset.cycle.size) null else preset.cycle[index]
    }

    /**
     * Anchor date that makes [preset] show [typeId] on [targetDateId], i.e. the start date to use
     * when the user says "today I am on the morning shift" instead of naming a cycle start date.
     */
    fun anchorDateFor(preset: ShiftPreset, targetDateId: Int, typeId: String): Int? {
        val index = preset.cycle.indexOf(typeId)
        if (index < 0) {
            return null
        }
        return ShiftDateMath.plusDays(targetDateId, -index)
    }

    /**
     * Offset that makes a crew following [preset] show [typeId] on [targetDateId]; the inverse of
     * [indexFor] with a fixed date.
     */
    fun offsetFor(preset: ShiftPreset, targetDateId: Int, typeId: String): Int? {
        val size = preset.length
        val index = preset.cycle.indexOf(typeId)
        if (size == 0 || index < 0) {
            return null
        }
        val diff = ShiftDateMath.dayNumber(targetDateId) -
            ShiftDateMath.dayNumber(preset.anchorDateId)
        return Math.floorMod(index - diff, size.toLong()).toInt()
    }
}
