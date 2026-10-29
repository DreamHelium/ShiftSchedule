package cn.dh.shiftschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dh.shiftschedule.R
import cn.dh.shiftschedule.calendar.CalendarDay
import cn.dh.shiftschedule.calendar.ChineseCalendar
import cn.dh.shiftschedule.settings.AppSettings
import cn.dh.shiftschedule.shift.Crew
import cn.dh.shiftschedule.shift.DayShift
import cn.dh.shiftschedule.shift.ShiftScheduleStore
import cn.dh.shiftschedule.ui.theme.ShiftScheduleTheme
import kotlinx.coroutines.launch

private const val WEEKS_PER_MONTH = 6
private const val CELL_HEIGHT_DP = 60

/** In landscape, cells stop shrinking at this height; below it the grid scrolls instead. */
private const val MIN_FIT_CELL_HEIGHT_DP = 52

/** Navigable year range (1900-2100); swiping and the header arrows are clamped to it. */
private const val BASE_YEAR = 1900
private const val MONTH_COUNT = (2101 - BASE_YEAR) * 12

private fun pageMonth(page: Int): Pair<Int, Int> =
    BASE_YEAR + page / 12 to page % 12 + 1

private fun monthPage(year: Int, month: Int): Int = (year - BASE_YEAR) * 12 + (month - 1)

/**
 * Schedule tab: renders a month grid whose cells carry the lunar date, solar term and festivals,
 * with statutory holidays and make-up workdays badged "off" / "work".
 * Swiping sideways (or tapping the header arrows) changes the month; the card below shows the
 * Gregorian and lunar date plus holiday info for the selected day.
 */
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    onEditShiftSchedule: () -> Unit = {},
) {
    val today = remember { ChineseCalendar.today() }
    val pagerState = rememberPagerState(initialPage = monthPage(today.year, today.month)) {
        MONTH_COUNT
    }
    var selectedId by rememberSaveable { mutableIntStateOf(today.id) }
    var tableMode by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val crews = ShiftScheduleStore.crews

    val page = pagerState.currentPage
    val (year, month) = pageMonth(page)
    val selected = remember(selectedId) {
        ChineseCalendar.day(selectedId / 10_000, selectedId / 100 % 100, selectedId % 100)
    }

    // After landing on a new month, keep the same day-of-month when possible, else fall back to the 1st.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            val (settledYear, settledMonth) = pageMonth(settledPage)
            val current = selectedId.let {
                ChineseCalendar.day(it / 10_000, it / 100 % 100, it % 100)
            }
            if (current.year != settledYear || current.month != settledMonth) {
                val inMonth = ChineseCalendar.monthGrid(settledYear, settledMonth)
                    .filter { it.inCurrentMonth }
                val target = inMonth.firstOrNull { it.day == current.day } ?: inMonth.first()
                selectedId = target.id
            }
        }
    }

    fun scrollToMonth(targetPage: Int) {
        scope.launch { pagerState.animateScrollToPage(targetPage.coerceIn(0, MONTH_COUNT - 1)) }
    }

    fun selectDay(day: CalendarDay) {
        selectedId = day.id
        // Tapping a padded day from a neighbouring month may also flip the calendar, per settings.
        if (AppSettings.jumpToOtherMonth && !day.inCurrentMonth) {
            scrollToMonth(monthPage(day.year, day.month))
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Wide screens (landscape and at least 600dp wide) use a two-pane layout: grid left, details right.
        val isWide = maxWidth > maxHeight && maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            MonthHeader(
                year = year,
                month = month,
                onPrevious = { scrollToMonth(page - 1) },
                onNext = { scrollToMonth(page + 1) },
                onToday = {
                    selectedId = today.id
                    scrollToMonth(monthPage(today.year, today.month))
                },
                compact = isWide,
                tableMode = tableMode,
                onToggleView = { tableMode = !tableMode },
            )

            var editingDateId by remember { mutableStateOf<Int?>(null) }
            editingDateId?.let { dateId ->
                ShiftOverrideDialog(dateId = dateId, onDismiss = { editingDateId = null })
            }

            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (tableMode) {
                            CrewTableView(
                                year = year,
                                month = month,
                                crews = crews,
                                todayId = today.id,
                                selectedId = selectedId,
                                onSelectDay = ::selectDay,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        } else {
                        WeekdayHeader()
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            // If there is room, shrink the cells so all 6 weeks fit on screen; otherwise
                            // (phone in landscape) keep the normal cell height and let the grid scroll,
                            // instead of squeezing the lunar text and badges until they are unreadable.
                            val fitCellHeight = maxHeight / WEEKS_PER_MONTH
                            val scrollInsteadOfShrink = fitCellHeight < MIN_FIT_CELL_HEIGHT_DP.dp
                            val cellHeight = if (scrollInsteadOfShrink) {
                                CELL_HEIGHT_DP.dp
                            } else {
                                minOf(CELL_HEIGHT_DP.dp, fitCellHeight)
                            }
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (scrollInsteadOfShrink) {
                                            Modifier.fillMaxSize()
                                        } else {
                                            Modifier.height(cellHeight * WEEKS_PER_MONTH)
                                        },
                                    ),
                                beyondViewportPageCount = 1,
                            ) { pageIndex ->
                                val (pageYear, pageMonthValue) = pageMonth(pageIndex)
                                val days = remember(pageIndex) {
                                    ChineseCalendar.monthGrid(pageYear, pageMonthValue)
                                }
                                if (scrollInsteadOfShrink) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        MonthGrid(
                                            days = days,
                                            selectedId = selectedId,
                                            todayId = today.id,
                                            onSelect = ::selectDay,
                                            cellHeight = cellHeight,
                                        )
                                    }
                                } else {
                                    MonthGrid(
                                        days = days,
                                        selectedId = selectedId,
                                        todayId = today.id,
                                        onSelect = ::selectDay,
                                        cellHeight = cellHeight,
                                    )
                                }
                            }
                        }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ShiftScheduleButton(onClick = onEditShiftSchedule)
                        TodayShiftCard(todayId = today.id)
                        Spacer(Modifier.height(8.dp))
                        DayDetailCard(selected, onEditShift = { editingDateId = selected.id })
                    }
                }
            } else {
                ShiftScheduleButton(onClick = onEditShiftSchedule)
                if (tableMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CrewTableView(
                            year = year,
                            month = month,
                            crews = crews,
                            todayId = today.id,
                            selectedId = selectedId,
                            onSelectDay = ::selectDay,
                            modifier = Modifier.fillMaxWidth(),
                            scrollable = false,
                        )
                        Spacer(Modifier.height(8.dp))
                        TodayShiftCard(todayId = today.id)
                        Spacer(Modifier.height(8.dp))
                        DayDetailCard(selected, onEditShift = { editingDateId = selected.id })
                    }
                } else {
                WeekdayHeader()
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    beyondViewportPageCount = 1,
                ) { pageIndex ->
                    val (pageYear, pageMonthValue) = pageMonth(pageIndex)
                    val days = remember(pageIndex) {
                        ChineseCalendar.monthGrid(pageYear, pageMonthValue)
                    }
                    // A padded day from a neighbouring month must show that day itself, not fall back to the 1st.
                    val pageSelected = if (days.any { it.id == selected.id }) {
                        selected
                    } else {
                        remember(pageIndex) { days.first { it.inCurrentMonth } }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MonthGrid(
                            days = days,
                            selectedId = selectedId,
                            todayId = today.id,
                            onSelect = ::selectDay,
                            cellHeight = CELL_HEIGHT_DP.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        TodayShiftCard(todayId = today.id)
                        Spacer(Modifier.height(8.dp))
                        DayDetailCard(pageSelected, onEditShift = { editingDateId = selected.id })
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    compact: Boolean = false,
    tableMode: Boolean = false,
    onToggleView: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDate(R.string.calendar_month_format, year, month, 1),
            style = if (compact) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineSmall
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.calendar_today))
        }
        IconButton(onClick = onToggleView) {
            Icon(
                painter = painterResource(
                    if (tableMode) R.drawable.ic_calendar_month else R.drawable.ic_table_rows,
                ),
                contentDescription = stringResource(
                    if (tableMode) R.string.view_calendar else R.string.view_table,
                ),
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = stringResource(R.string.calendar_prev_month),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.calendar_next_month),
            )
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val labels = stringArrayResource(R.array.weekday_short)
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val isWeekend = index >= 5
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isWeekend) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    days: List<CalendarDay>,
    selectedId: Int,
    todayId: Int,
    onSelect: (CalendarDay) -> Unit,
    cellHeight: Dp,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(WEEKS_PER_MONTH) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { index ->
                    val day = days[week * 7 + index]
                    DayCell(
                        day = day,
                        shift = ShiftScheduleStore.shiftFor(day.id),
                        isSelected = day.id == selectedId,
                        isToday = day.id == todayId,
                        onClick = { onSelect(day) },
                        cellHeight = cellHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay,
    shift: DayShift?,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    cellHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val isWeekend = day.weekdayIndex >= 5
    val alpha = if (day.inCurrentMonth) 1f else 0.35f
    val contentColor = MaterialTheme.colorScheme
    // Landscape shrinks the cells, so scale text and padding down too or the lunar label gets clipped.
    val compact = cellHeight < 52.dp

    val dayColor = when {
        isSelected -> contentColor.primary
        day.isRestDay -> contentColor.error
        isWeekend -> contentColor.onSurfaceVariant
        else -> contentColor.onSurface
    }.copy(alpha = alpha)

    val labelColor = when {
        day.isRestDay -> contentColor.error
        day.solarTerm != null || day.festival != null -> contentColor.primary
        else -> contentColor.onSurfaceVariant
    }.copy(alpha = alpha)

    val background = when {
        isSelected -> contentColor.primaryContainer
        isToday -> contentColor.secondaryContainer.copy(alpha = 0.55f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(cellHeight)
            .padding(if (compact) 1.dp else 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = day.day.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontSize = if (compact) 14.sp else 16.sp,
                lineHeight = if (compact) 16.sp else 22.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = dayColor,
            )
            Text(
                text = day.lunarLabel,
                fontSize = if (compact) 8.sp else 9.sp,
                lineHeight = if (compact) 9.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = labelColor,
            )
            shift?.let { dayShift ->
                    // Show the shift itself, not just a dot. Hand-edited days get a ring around the label.
                Text(
                    text = dayShift.type.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (compact) 8.sp else 9.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(dayShift.type.color).copy(alpha = alpha),
                    modifier = Modifier
                        .padding(top = if (compact) 1.dp else 2.dp)
                        .then(
                            if (dayShift.isOverride) {
                                Modifier
                                    .border(
                                        width = 1.dp,
                                        color = Color(dayShift.type.color).copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 3.dp)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }

        if (day.isRestDay || day.isWorkDay) {
            Text(
                text = stringResource(
                    if (day.isRestDay) R.string.badge_rest else R.string.badge_work,
                ),
                fontSize = if (compact) 7.sp else 8.sp,
                lineHeight = if (compact) 8.sp else 9.sp,
                color = if (day.isRestDay) contentColor.error else contentColor.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (compact) 1.dp else 3.dp, end = if (compact) 2.dp else 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayDetailCard(day: CalendarDay, onEditShift: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = formatDate(R.string.calendar_full_date_format, day.year, day.month, day.day),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.calendar_lunar_line, day.lunarText, day.yearGanZhi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val extra = buildList {
                day.festival?.let { add(it) }
                day.solarTerm?.let { add(stringResource(R.string.calendar_solar_term, it)) }
                when {
                    day.isRestDay -> add(stringResource(R.string.calendar_rest_day))
                    day.isWorkDay -> add(stringResource(R.string.calendar_work_day))
                }
            }
            if (extra.isNotEmpty()) {
                Text(
                    text = extra.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val shift = ShiftScheduleStore.shiftFor(day.id)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                shift?.let { ColorDot(Color(it.type.color)) }
                Text(
                    text = stringResource(R.string.shift_day_title) + "：" +
                        (shift?.type?.name ?: stringResource(R.string.shift_no_preset)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = shift?.let { Color(it.type.color) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (shift == null) 0.dp else 6.dp),
                )
                if (shift?.isOverride == true) {
                    Text(
                        text = " · " + stringResource(R.string.shift_custom_tag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val crewShifts = ShiftScheduleStore.crewShiftsFor(day.id)
            if (crewShifts.size > 1) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    crewShifts.forEach { crewShift ->
                        CrewShiftChip(crewShift.crew.name, crewShift.shift)
                    }
                }
            }

            TextButton(
                onClick = onEditShift,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.shift_change_day),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Entry point to the shift editor, shown right under the month header. */
@Composable
private fun ShiftScheduleButton(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.shift_add_edit),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** "Today's shift" card shown under the month grid. */
@Composable
private fun TodayShiftCard(todayId: Int) {
    val shift = ShiftScheduleStore.shiftFor(todayId)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shift_today_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = shift?.type?.name ?: stringResource(R.string.shift_no_preset),
                    style = MaterialTheme.typography.titleMedium,
                    color = shift?.let { Color(it.type.color) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shift?.let { dayShift -> ShiftChip(dayShift.type) }
        }
    }
}


/** Small pill in the day card showing one crew and the shift it works that day. */
@Composable
private fun CrewShiftChip(name: String, shift: DayShift) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(shift.type.color).copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = shift.type.shortName,
                style = MaterialTheme.typography.labelMedium,
                color = Color(shift.type.color),
            )
            if (shift.isOverride) {
                Text(
                    text = "*",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Table view: one row per crew, one column per day of the month, so the whole rotation for a month
 * can be compared at a glance. The crew column stays put while the days scroll sideways.
 */
@Composable
private fun CrewTableView(
    year: Int,
    month: Int,
    crews: List<Crew>,
    todayId: Int,
    selectedId: Int,
    onSelectDay: (CalendarDay) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val days = remember(year, month) {
        ChineseCalendar.monthGrid(year, month).filter { it.inCurrentMonth }
    }
    val headerHeight = 30.dp
    val rowHeight = 38.dp
    val cellWidth = 50.dp
    val nameWidth = 78.dp
    val hScroll = rememberScrollState()

    Column(
        modifier = if (scrollable) {
            modifier.verticalScroll(rememberScrollState())
        } else {
            modifier
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(nameWidth)) {
                Box(
                    modifier = Modifier
                        .height(headerHeight)
                        .fillMaxWidth()
                        .padding(end = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.table_date_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                crews.forEach { crew ->
                    Box(
                        modifier = Modifier
                            .height(rowHeight)
                            .fillMaxWidth()
                            .padding(end = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = crew.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (crew.isMe) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (crew.isMe) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Column(modifier = Modifier.horizontalScroll(hScroll)) {
                Row {
                    days.forEach { day ->
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .height(headerHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = day.day.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (day.id == todayId) FontWeight.Bold else FontWeight.Normal,
                                color = if (day.id == todayId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                crews.forEach { crew ->
                    Row {
                        days.forEach { day ->
                            val shift = ShiftScheduleStore.shiftFor(day.id, crew)
                            val selected = day.id == selectedId
                            Box(
                                modifier = Modifier
                                    .width(cellWidth)
                                    .height(rowHeight)
                                    .padding(1.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            selected -> MaterialTheme.colorScheme.primaryContainer
                                            day.weekdayIndex >= 5 ->
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                            else -> Color.Transparent
                                        },
                                    )
                                    .clickable { onSelectDay(day) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (shift != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ColorDot(Color(shift.type.color))
                                        Text(
                                            text = shift.type.shortName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(shift.type.color),
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun ScheduleScreenPreview() {
    ShiftScheduleTheme {
        ScheduleScreen()
    }
}
