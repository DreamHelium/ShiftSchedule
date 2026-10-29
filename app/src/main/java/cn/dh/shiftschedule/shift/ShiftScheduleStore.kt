package cn.dh.shiftschedule.shift

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for shift types, presets and manual overrides.
 *
 * The data lives in memory as Compose state (so every screen recomposes when something changes)
 * and is mirrored into [SharedPreferences] as JSON. Call [init] once before use, usually from
 * Activity#onCreate right after the other stores.
 */
object ShiftScheduleStore {

    private const val PREFS_NAME = "shift_schedule"
    private const val KEY_TYPES = "types"
    private const val KEY_PRESETS = "presets"
    private const val KEY_ACTIVE = "active_preset"
    private const val KEY_OVERRIDES = "overrides"
    private const val KEY_CREWS = "crews"

    const val DEFAULT_PRESET_ID = "five_three"
    const val DEFAULT_ANCHOR_DATE = 20260101

    private var prefs: SharedPreferences? = null

    var shiftTypes by mutableStateOf(defaultTypes())
        private set

    var presets by mutableStateOf(listOf(defaultPreset()))
        private set

    var activePresetId by mutableStateOf(DEFAULT_PRESET_ID)
        private set

    /** Hand-picked shifts for the owner, keyed by date id (yyyyMMdd); they win over the rotation. */
    var overrides by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    /** Crews (值) sharing the rotation. The first one is always the owner. */
    var crews by mutableStateOf(defaultCrews())
        private set

    fun init(context: Context) {
        val store = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }

        val savedTypes = store.getString(KEY_TYPES, null)?.let(::parseTypes)
        if (!savedTypes.isNullOrEmpty()) {
            shiftTypes = savedTypes
        }
        val savedPresets = store.getString(KEY_PRESETS, null)?.let(::parsePresets)
        if (!savedPresets.isNullOrEmpty()) {
            presets = savedPresets
        }
        activePresetId = store.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> presets.any { it.id == id } }
            ?: presets.first().id
        overrides = store.getString(KEY_OVERRIDES, null)?.let(::parseOverrides).orEmpty()
        val savedCrews = store.getString(KEY_CREWS, null)?.let(::parseCrews)
        if (!savedCrews.isNullOrEmpty()) {
            crews = withOwner(savedCrews)
        }
    }

    // --- queries ---------------------------------------------------------

    fun typeById(id: String?): ShiftType? = shiftTypes.firstOrNull { it.id == id }

    fun activePreset(): ShiftPreset? = presets.firstOrNull { it.id == activePresetId }

    fun presetById(id: String): ShiftPreset? = presets.firstOrNull { it.id == id }

    fun myCrew(): Crew? = crews.firstOrNull { it.isMe } ?: crews.firstOrNull()

    fun crewById(id: String): Crew? = crews.firstOrNull { it.id == id }

    /** Effective shift for [dateId] for the owner: manual override first, then the rotation. */
    fun shiftFor(dateId: Int): DayShift? = shiftFor(dateId, myCrew())

    /** Effective shift for [dateId] for [crew]; only the owner has manual overrides. */
    fun shiftFor(dateId: Int, crew: Crew?): DayShift? {
        if (crew == null) {
            return null
        }
        if (crew.isMe) {
            val overrideType = typeById(overrides[dateId])
            if (overrideType != null) {
                return DayShift(overrideType, isOverride = true)
            }
        }
        val preset = presetById(crew.presetId) ?: activePreset() ?: return null
        val type = typeById(ShiftCycle.typeIdFor(preset, dateId, crew.offsetDays)) ?: return null
        return DayShift(type, isOverride = false)
    }

    /** Shifts of every crew for [dateId], owner first and then by rotation phase. */
    fun crewShiftsFor(dateId: Int): List<CrewShift> = crews
        .mapNotNull { crew -> shiftFor(dateId, crew)?.let { CrewShift(crew, it) } }
        .sortedWith(compareByDescending<CrewShift> { it.crew.isMe }.thenBy { it.crew.offsetDays })

    /** Phase (offset) that would put [typeId] on [dateId] for [crew]; null when impossible. */
    fun offsetFor(crew: Crew, dateId: Int, typeId: String): Int? {
        val preset = presetById(crew.presetId) ?: return null
        return ShiftCycle.offsetFor(preset, dateId, typeId)
    }

    /** True when some preset cycle or manual override still references [typeId]. */
    fun isTypeInUse(typeId: String): Boolean =
        presets.any { typeId in it.cycle } || overrides.containsValue(typeId)

    // --- mutations -------------------------------------------------------

    fun setActivePreset(id: String) {
        if (presets.none { it.id == id }) {
            return
        }
        activePresetId = id
        persist()
    }

    /** Adds [preset] or replaces the one with the same id. */
    fun savePreset(preset: ShiftPreset) {
        presets = if (presets.any { it.id == preset.id }) {
            presets.map { if (it.id == preset.id) preset else it }
        } else {
            presets + preset
        }
        if (presets.none { it.id == activePresetId }) {
            activePresetId = presets.first().id
        }
        persist()
    }

    /** Creates a preset that starts as a copy of the active one, saves it and returns it. */
    fun createPreset(): ShiftPreset {
        val base = activePreset()
        val preset = ShiftPreset(
            id = nextId("preset"),
            name = base?.let { "${it.name} ${presets.size + 1}" }.orEmpty()
                .ifEmpty { "New preset" },
            cycle = base?.cycle.orEmpty().ifEmpty { listOfNotNull(shiftTypes.firstOrNull()?.id) },
            anchorDateId = base?.anchorDateId ?: DEFAULT_ANCHOR_DATE,
        )
        presets = presets + preset
        activePresetId = preset.id
        persist()
        return preset
    }

    /** Removes a preset; the last remaining preset cannot be deleted. */
    fun deletePreset(id: String): Boolean {
        if (presets.size <= 1) {
            return false
        }
        presets = presets.filterNot { it.id == id }
        if (activePresetId == id) {
            activePresetId = presets.first().id
        }
        // Crews that pointed at the deleted preset fall back to the first remaining one.
        crews = crews.map { crew ->
            if (presetById(crew.presetId) == null) crew.copy(presetId = presets.first().id) else crew
        }
        persist()
        return true
    }

    /** Adds [type] or replaces the one with the same id. */
    fun saveType(type: ShiftType) {
        shiftTypes = if (shiftTypes.any { it.id == type.id }) {
            shiftTypes.map { if (it.id == type.id) type else it }
        } else {
            shiftTypes + type
        }
        persist()
    }

    /** Creates a blank-ish shift type derived from [templateName] and returns it. */
    fun createType(templateName: String = "班次"): ShiftType {
        val type = ShiftType(
            id = nextId("type"),
            name = templateName,
            shortName = templateName.take(1),
            color = TYPE_PALETTE[shiftTypes.size % TYPE_PALETTE.size],
        )
        shiftTypes = shiftTypes + type
        persist()
        return type
    }

    /** Deletes a shift type unless a preset cycle or an override still uses it. */
    fun deleteType(id: String): Boolean {
        if (isTypeInUse(id)) {
            return false
        }
        shiftTypes = shiftTypes.filterNot { it.id == id }
        persist()
        return true
    }

    /** Adds [crew] or replaces the one with the same id, keeping exactly one owner. */
    fun saveCrew(crew: Crew) {
        val normalized = if (crew.isMe) {
            crews.map { if (it.id == crew.id) crew else it.copy(isMe = false) } +
                if (crews.any { it.id == crew.id }) emptyList() else listOf(crew)
        } else {
            crews.map { if (it.id == crew.id) crew else it } +
                if (crews.any { it.id == crew.id }) emptyList() else listOf(crew)
        }
        crews = withOwner(normalized)
        persist()
    }

    /** Makes [id] the crew that belongs to this device; everyone else loses the owner flag. */
    fun setOwner(id: String) {
        if (crews.none { it.id == id }) {
            return
        }
        crews = crews.map { it.copy(isMe = it.id == id) }
        persist()
    }

    /** Creates a crew on the active preset, one phase after the existing ones. */
    fun createCrew(name: String): Crew {
        val preset = activePreset() ?: presets.first()
        val size = preset.length.coerceAtLeast(1)
        val crew = Crew(
            id = nextId("crew"),
            name = name.ifBlank { crewName(crews.size) },
            presetId = preset.id,
            offsetDays = crews.size % size,
        )
        crews = crews + crew
        persist()
        return crew
    }

    /** Deletes a crew; the owner row cannot be deleted. */
    fun deleteCrew(id: String): Boolean {
        val crew = crewById(id) ?: return false
        if (crew.isMe || crews.size <= 1) {
            return false
        }
        crews = crews.filterNot { it.id == id }
        persist()
        return true
    }

    /** Sets [crew]'s phase so that it works [typeId] on [dateId] (the "this is today's shift" path). */
    fun setCrewShiftOn(crewId: String, dateId: Int, typeId: String): Boolean {
        val crew = crewById(crewId) ?: return false
        val offset = offsetFor(crew, dateId, typeId) ?: return false
        saveCrew(crew.copy(offsetDays = offset))
        return true
    }

    fun setOverride(dateId: Int, typeId: String) {
        if (typeById(typeId) == null) {
            return
        }
        overrides = overrides + (dateId to typeId)
        persist()
    }

    fun clearOverride(dateId: Int) {
        if (!overrides.containsKey(dateId)) {
            return
        }
        overrides = overrides - dateId
        persist()
    }

    // --- persistence -----------------------------------------------------

    private fun nextId(prefix: String): String {
        val used = (shiftTypes.map { it.id } + presets.map { it.id } + crews.map { it.id }).toSet()
        var index = used.size + 1
        while ("${prefix}_$index" in used) {
            index++
        }
        return "${prefix}_$index"
    }

    private fun persist() {
        val store = prefs ?: return
        store.edit()
            .putString(KEY_TYPES, typesToJson(shiftTypes))
            .putString(KEY_PRESETS, presetsToJson(presets))
            .putString(KEY_ACTIVE, activePresetId)
            .putString(KEY_OVERRIDES, overridesToJson(overrides))
            .putString(KEY_CREWS, crewsToJson(crews))
            .apply()
    }

    // --- defaults --------------------------------------------------------

    private fun defaultTypes(): List<ShiftType> = listOf(
        ShiftType("early", "早班", "早", 0xFFF2994A),
        ShiftType("mid", "中班", "中", 0xFF2D9CDB),
        ShiftType("study", "学习班", "学", 0xFF9B51E0),
        ShiftType("night", "夜班", "夜", 0xFF3F51B5),
        ShiftType("rest", "休息", "休", 0xFF9AA5B1, isRest = true),
    )

    private fun defaultPreset(): ShiftPreset = ShiftPreset(
        id = DEFAULT_PRESET_ID,
        name = "五班三倒",
        cycle = listOf("early", "mid", "study", "night", "rest"),
        anchorDateId = DEFAULT_ANCHOR_DATE,
    )

    /**
     * Five crews by default, matching the usual five-team rotation chart: every crew follows the
     * same preset, one cycle phase apart from the next. The first one is "me".
     */
    private fun defaultCrews(): List<Crew> = (0 until DEFAULT_CREW_COUNT).map { index ->
        Crew(
            id = if (index == 0) "me" else "crew_${index + 1}",
            name = crewName(index),
            presetId = DEFAULT_PRESET_ID,
            offsetDays = index,
            isMe = index == 0,
        )
    }

    /** One-based crew label, e.g. 一值 … 十值 … 十一值. */
    fun crewName(index: Int): String = "${chineseNumber(index + 1)}值"

    /** Guarantees exactly one owner crew, creating one when needed. */
    private fun withOwner(list: List<Crew>): List<Crew> {
        if (list.isEmpty()) {
            return defaultCrews()
        }
        val owner = list.firstOrNull { it.isMe } ?: list.first().copy(isMe = true)
        return list.map { if (it.id == owner.id) it.copy(isMe = true) else it.copy(isMe = false) }
    }

    private fun chineseNumber(value: Int): String {
        val digits = "零一二三四五六七八九"
        return when {
            value <= 0 -> "零"
            value < 10 -> digits[value].toString()
            value < 20 -> "十" + if (value == 10) "" else digits[value - 10].toString()
            value < 100 -> digits[value / 10] + "十" + if (value % 10 == 0) "" else digits[value % 10].toString()
            else -> value.toString()
        }
    }

    private const val DEFAULT_CREW_COUNT = 5

    val TYPE_PALETTE = listOf(
        0xFFF2994A, // orange
        0xFF2D9CDB, // blue
        0xFF9B51E0, // purple
        0xFF3F51B5, // indigo
        0xFF27AE60, // green
        0xFFEB5757, // red
        0xFF9AA5B1, // grey
    )

    // --- json ------------------------------------------------------------

    private fun typesToJson(types: List<ShiftType>): String {
        val array = JSONArray()
        types.forEach { type ->
            array.put(
                JSONObject()
                    .put("id", type.id)
                    .put("name", type.name)
                    .put("short", type.shortName)
                    .put("color", type.color)
                    .put("rest", type.isRest),
            )
        }
        return array.toString()
    }

    private fun parseTypes(raw: String): List<ShiftType>? = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ShiftType(
                id = item.getString("id"),
                name = item.getString("name"),
                shortName = item.optString("short").ifEmpty { item.getString("name").take(1) },
                color = item.optLong("color", TYPE_PALETTE.first()),
                isRest = item.optBoolean("rest", false),
            )
        }
    }.getOrNull()

    private fun presetsToJson(presets: List<ShiftPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("cycle", JSONArray(preset.cycle))
                    .put("anchor", preset.anchorDateId),
            )
        }
        return array.toString()
    }

    private fun parsePresets(raw: String): List<ShiftPreset>? = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val cycleArray = item.optJSONArray("cycle") ?: JSONArray()
            val cycle = (0 until cycleArray.length()).map { cycleArray.getString(it) }
            ShiftPreset(
                id = item.getString("id"),
                name = item.getString("name"),
                cycle = cycle,
                anchorDateId = item.optInt("anchor", DEFAULT_ANCHOR_DATE),
            )
        }
    }.getOrNull()

    private fun crewsToJson(crews: List<Crew>): String {
        val array = JSONArray()
        crews.forEach { crew ->
            array.put(
                JSONObject()
                    .put("id", crew.id)
                    .put("name", crew.name)
                    .put("preset", crew.presetId)
                    .put("offset", crew.offsetDays)
                    .put("me", crew.isMe),
            )
        }
        return array.toString()
    }

    private fun parseCrews(raw: String): List<Crew>? = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Crew(
                id = item.getString("id"),
                name = item.optString("name").ifEmpty { "值" },
                presetId = item.optString("preset").ifEmpty { DEFAULT_PRESET_ID },
                offsetDays = item.optInt("offset", 0),
                isMe = item.optBoolean("me", false),
            )
        }
    }.getOrNull()

    private fun overridesToJson(overrides: Map<Int, String>): String {
        val json = JSONObject()
        overrides.forEach { (dateId, typeId) -> json.put(dateId.toString(), typeId) }
        return json.toString()
    }

    private fun parseOverrides(raw: String): Map<Int, String>? = runCatching {
        val json = JSONObject(raw)
        buildMap {
            json.keys().forEach { key ->
                key.toIntOrNull()?.let { dateId -> put(dateId, json.getString(key)) }
            }
        }
    }.getOrNull()
}
