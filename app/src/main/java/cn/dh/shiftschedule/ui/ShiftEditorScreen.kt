package cn.dh.shiftschedule.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.dh.shiftschedule.R
import cn.dh.shiftschedule.calendar.ChineseCalendar
import cn.dh.shiftschedule.shift.Crew
import cn.dh.shiftschedule.shift.ShiftCycle
import cn.dh.shiftschedule.shift.ShiftDateMath
import cn.dh.shiftschedule.shift.ShiftPreset
import cn.dh.shiftschedule.shift.ShiftScheduleStore
import cn.dh.shiftschedule.shift.ShiftType

/**
 * Full-screen editor reachable from the schedule tab and from Profile.
 *
 * It manages three things: presets (a rotation plus its start date), the shift types themselves,
 * and — through [ShiftOverrideDialog], opened from the day details — single-day overrides.
 */
@Composable
fun ShiftEditorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)

    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var editingTypeId by remember { mutableStateOf<String?>(null) }
    var editingCrewId by remember { mutableStateOf<String?>(null) }
    val todayId = remember { ChineseCalendar.today().id }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(R.string.shift_editor_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(stringResource(R.string.shift_section_presets))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editingPresetId = ShiftScheduleStore.createPreset().id }) {
                    Text(stringResource(R.string.shift_preset_add))
                }
            }
            ShiftScheduleStore.presets.forEach { preset ->
                PresetCard(
                    preset = preset,
                    isActive = preset.id == ShiftScheduleStore.activePresetId,
                    canDelete = ShiftScheduleStore.presets.size > 1,
                    onActivate = { ShiftScheduleStore.setActivePreset(preset.id) },
                    onEdit = { editingPresetId = preset.id },
                    onDelete = {
                        if (ShiftScheduleStore.deletePreset(preset.id)) {
                            editingPresetId = null
                        }
                    },
                )
                Spacer(Modifier.size(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(stringResource(R.string.shift_crews_title))
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val crew = ShiftScheduleStore.createCrew(
                            "值${ShiftScheduleStore.crews.size + 1}",
                        )
                        editingCrewId = crew.id
                    },
                ) {
                    Text(stringResource(R.string.shift_crew_add))
                }
            }
            ShiftScheduleStore.crews.forEach { crew ->
                CrewRow(
                    crew = crew,
                    todayId = todayId,
                    onEdit = { editingCrewId = crew.id },
                    onDelete = { ShiftScheduleStore.deleteCrew(crew.id) },
                )
                Spacer(Modifier.size(6.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(stringResource(R.string.shift_section_types))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editingTypeId = ShiftScheduleStore.createType().id }) {
                    Text(stringResource(R.string.shift_type_add))
                }
            }
            ShiftScheduleStore.shiftTypes.forEach { type ->
                TypeRow(type = type, onEdit = { editingTypeId = type.id })
                Spacer(Modifier.size(6.dp))
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    editingPresetId?.let { id ->
        ShiftScheduleStore.presetById(id)?.let { preset ->
            PresetEditDialog(
                preset = preset,
                onDismiss = { editingPresetId = null },
                onSave = { updated ->
                    ShiftScheduleStore.savePreset(updated)
                    editingPresetId = null
                },
                onDelete = {
                    if (ShiftScheduleStore.deletePreset(id)) {
                        editingPresetId = null
                    }
                },
            )
        }
    }

    editingCrewId?.let { id ->
        ShiftScheduleStore.crewById(id)?.let { crew ->
            CrewEditDialog(
                crew = crew,
                todayId = todayId,
                onDismiss = { editingCrewId = null },
                onSave = { updated ->
                    ShiftScheduleStore.saveCrew(updated)
                    editingCrewId = null
                },
                onDelete = {
                    if (ShiftScheduleStore.deleteCrew(crew.id)) {
                        editingCrewId = null
                    }
                },
            )
        }
    }

    editingTypeId?.let { id ->
        ShiftScheduleStore.typeById(id)?.let { type ->
            TypeEditDialog(
                type = type,
                inUse = ShiftScheduleStore.isTypeInUse(type.id),
                onDismiss = { editingTypeId = null },
                onSave = { updated ->
                    ShiftScheduleStore.saveType(updated)
                    editingTypeId = null
                },
                onDelete = {
                    if (ShiftScheduleStore.deleteType(type.id)) {
                        editingTypeId = null
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun PresetCard(
    preset: ShiftPreset,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onActivate)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = stringResource(R.string.shift_days_count, preset.length) + " · " +
                            formatDate(
                                R.string.shift_date_format,
                                ShiftDateMath.yearOf(preset.anchorDateId),
                                ShiftDateMath.monthOf(preset.anchorDateId),
                                ShiftDateMath.dayOf(preset.anchorDateId),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.shift_preset_edit),
                    )
                }
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.shift_preset_delete),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                preset.cycle.forEach { typeId ->
                    ShiftChip(ShiftScheduleStore.typeById(typeId))
                }
            }
        }
    }
}

@Composable
private fun TypeRow(type: ShiftType, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(Color(type.color))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = type.name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = type.shortName,
                style = MaterialTheme.typography.titleMedium,
                color = Color(type.color),
            )
        }
    }
}

@Composable
private fun PresetEditDialog(
    preset: ShiftPreset,
    onDismiss: () -> Unit,
    onSave: (ShiftPreset) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(preset.name) }
    var cycle by remember { mutableStateOf(preset.cycle) }
    var anchor by remember { mutableStateOf(preset.anchorDateId) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    var pickingAnchorShift by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val todayId = remember { ChineseCalendar.today().id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shift_preset_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shift_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))

                Text(
                    text = stringResource(
                        R.string.shift_anchor,
                        formatDate(
                            R.string.shift_date_format,
                            ShiftDateMath.yearOf(anchor),
                            ShiftDateMath.monthOf(anchor),
                            ShiftDateMath.dayOf(anchor),
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { pickingAnchorShift = true }) {
                    Text(stringResource(R.string.shift_anchor_from_today))
                }
                TextButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                anchor = ShiftDateMath.dateIdOf(year, month + 1, day)
                            },
                            ShiftDateMath.yearOf(anchor),
                            ShiftDateMath.monthOf(anchor) - 1,
                            ShiftDateMath.dayOf(anchor),
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.shift_anchor_change))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = stringResource(R.string.shift_cycle_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                cycle.forEachIndexed { index, typeId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.shift_cycle_day, index + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(72.dp),
                        )
                        OutlinedButton(onClick = { pickingSlot = index }) {
                            Text(
                                text = ShiftScheduleStore.typeById(typeId)?.name
                                    ?: stringResource(R.string.shift_no_preset),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val fallback = ShiftScheduleStore.shiftTypes.firstOrNull()?.id
                            if (fallback != null) {
                                cycle = cycle + fallback
                            }
                        },
                        enabled = ShiftScheduleStore.shiftTypes.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.shift_cycle_add))
                    }
                    TextButton(
                        onClick = { if (cycle.size > 1) cycle = cycle.dropLast(1) },
                        enabled = cycle.size > 1,
                    ) {
                        Text(stringResource(R.string.shift_cycle_remove))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(preset.copy(name = name.trim(), cycle = cycle, anchorDateId = anchor)) },
                enabled = name.isNotBlank() && cycle.isNotEmpty(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (ShiftScheduleStore.presets.size > 1) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.shift_preset_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )

    if (pickingAnchorShift) {
        ShiftPickerDialog(
            title = stringResource(R.string.shift_anchor_from_today),
            selectedId = ShiftCycle.typeIdFor(preset.copy(anchorDateId = anchor), todayId),
            onDismiss = { pickingAnchorShift = false },
            onPick = { typeId ->
                // "Today I am on X" is easier than naming a cycle start date, so derive the anchor.
                ShiftCycle.anchorDateFor(preset.copy(cycle = cycle), todayId, typeId)
                    ?.let { anchor = it }
                pickingAnchorShift = false
            },
        )
    }

    pickingSlot?.let { slot ->
        ShiftPickerDialog(
            title = stringResource(R.string.shift_cycle_day, slot + 1),
            selectedId = cycle.getOrNull(slot),
            onDismiss = { pickingSlot = null },
            onPick = { typeId ->
                cycle = cycle.toMutableList().also { it[slot] = typeId }
                pickingSlot = null
            },
        )
    }
}

@Composable
private fun TypeEditDialog(
    type: ShiftType,
    inUse: Boolean,
    onDismiss: () -> Unit,
    onSave: (ShiftType) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(type.name) }
    var shortName by remember { mutableStateOf(type.shortName) }
    var color by remember { mutableStateOf(type.color) }
    var isRest by remember { mutableStateOf(type.isRest) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shift_type_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shift_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(2) },
                    label = { Text(stringResource(R.string.shift_short_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.shift_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShiftScheduleStore.TYPE_PALETTE.forEach { paletteColor ->
                        val selected = paletteColor == color
                        Box(
                            modifier = Modifier
                                .size(if (selected) 30.dp else 26.dp)
                                .clip(CircleShape)
                                .background(Color(paletteColor))
                                .clickable { color = paletteColor },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.shift_rest_day),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = isRest, onCheckedChange = { isRest = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        type.copy(
                            name = name.trim(),
                            shortName = shortName.trim().ifEmpty { name.trim().take(1) },
                            color = color,
                            isRest = isRest,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (!inUse) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.shift_type_delete))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}


/** One crew row in the editor: who it is, which rotation it follows, and its shift today. */
@Composable
private fun CrewRow(
    crew: Crew,
    todayId: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val todayShift = ShiftScheduleStore.shiftFor(todayId, crew)
    val presetName = ShiftScheduleStore.presetById(crew.presetId)?.name.orEmpty()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = crew.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (crew.isMe) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (crew.isMe) {
                        Text(
                            text = stringResource(R.string.shift_crew_me),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = presetName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            todayShift?.let { ShiftChip(it.type) }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.shift_crew_edit),
                )
            }
            IconButton(onClick = onDelete, enabled = !crew.isMe) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.shift_crew_delete),
                )
            }
        }
    }
}

/** Editor for one crew: name, rotation preset, and its phase set through today's shift. */
@Composable
private fun CrewEditDialog(
    crew: Crew,
    todayId: Int,
    onDismiss: () -> Unit,
    onSave: (Crew) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(crew.name) }
    var presetId by remember { mutableStateOf(crew.presetId) }
    var offset by remember { mutableStateOf(crew.offsetDays) }
    var pickingShift by remember { mutableStateOf(false) }
    val preset = ShiftScheduleStore.presetById(presetId) ?: ShiftScheduleStore.activePreset()
    val todayShift = preset?.let { ShiftScheduleStore.typeById(ShiftCycle.typeIdFor(it, todayId, offset)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shift_crew_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shift_crew_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.shift_crew_preset),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                ShiftScheduleStore.presets.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                presetId = option.id
                                offset = 0
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.id == presetId, onClick = null)
                        Text(text = option.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    text = stringResource(R.string.shift_crew_today_shift),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    todayShift?.let { ShiftChip(it) }
                    TextButton(onClick = { pickingShift = true }) {
                        Text(stringResource(R.string.shift_anchor_from_today))
                    }
                }
                if (!crew.isMe) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        text = stringResource(R.string.settings_my_crew_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            ShiftScheduleStore.setOwner(crew.id)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.shift_crew_set_me))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(crew.copy(name = name.trim().ifEmpty { crew.name }, presetId = presetId, offsetDays = offset)) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (!crew.isMe) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.shift_crew_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )

    if (pickingShift) {
        ShiftPickerDialog(
            title = stringResource(R.string.shift_crew_today_shift),
            selectedId = todayShift?.id,
            onDismiss = { pickingShift = false },
            onPick = { typeId ->
                preset?.let { ShiftCycle.offsetFor(it, todayId, typeId) }?.let { offset = it }
                pickingShift = false
            },
        )
    }
}

/** Lets the user pick the shift for a single date, or drop the override again. */
@Composable
fun ShiftOverrideDialog(
    dateId: Int,
    onDismiss: () -> Unit,
) {
    val current = ShiftScheduleStore.overrides[dateId]
    val title = stringResource(
        R.string.shift_override_title,
        formatDate(
            R.string.shift_date_format,
            ShiftDateMath.yearOf(dateId),
            ShiftDateMath.monthOf(dateId),
            ShiftDateMath.dayOf(dateId),
        ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ShiftScheduleStore.shiftTypes.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ShiftScheduleStore.setOverride(dateId, type.id)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == type.id, onClick = null)
                        ColorDot(Color(type.color))
                        Spacer(Modifier.width(12.dp))
                        Text(text = type.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (current != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = {
                            ShiftScheduleStore.clearOverride(dateId)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.shift_override_clear))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ShiftPickerDialog(
    title: String,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ShiftScheduleStore.shiftTypes.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(type.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type.id == selectedId, onClick = null)
                        ColorDot(Color(type.color))
                        Spacer(Modifier.width(12.dp))
                        Text(text = type.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Small coloured pill with the shift's short label, e.g. "早". */
@Composable
fun ShiftChip(type: ShiftType?, modifier: Modifier = Modifier) {
    if (type == null) {
        return
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(type.color).copy(alpha = 0.18f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = type.shortName,
                style = MaterialTheme.typography.labelMedium,
                color = Color(type.color),
            )
        }
    }
}

@Composable
fun ColorDot(color: Color, size: Int = 12) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}
