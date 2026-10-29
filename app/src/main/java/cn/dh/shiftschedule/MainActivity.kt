package cn.dh.shiftschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import cn.dh.shiftschedule.settings.AppSettings
import cn.dh.shiftschedule.shift.ShiftScheduleStore
import cn.dh.shiftschedule.ui.ShiftEditorScreen
import cn.dh.shiftschedule.ui.ProfileScreen
import cn.dh.shiftschedule.ui.ScheduleScreen
import cn.dh.shiftschedule.ui.theme.ShiftScheduleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)
        ShiftScheduleStore.init(this)
        setContent {
            ShiftScheduleTheme {
                ShiftScheduleApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun ShiftScheduleApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.SCHEDULE) }
    var showShiftEditor by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showShiftEditor) { showShiftEditor = false }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                AppDestinations.entries.forEach { destination ->
                    item(
                        icon = {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = stringResource(destination.labelRes),
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                        selected = destination == currentDestination,
                        onClick = { currentDestination = destination },
                    )
                }
            }
        ) {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                val contentModifier = Modifier.padding(innerPadding)
                when (currentDestination) {
                    AppDestinations.SCHEDULE -> ScheduleScreen(
                        modifier = contentModifier,
                        onEditShiftSchedule = { showShiftEditor = true },
                    )

                    AppDestinations.TOOLS -> PlaceholderScreen(
                        icon = R.drawable.ic_handyman,
                        title = R.string.tab_tools,
                        message = R.string.placeholder_tools,
                        modifier = contentModifier,
                    )

                    AppDestinations.PROFILE -> ProfileScreen(
                        modifier = contentModifier,
                        onEditShiftSchedule = { showShiftEditor = true },
                    )
                }
            }
        }

        if (showShiftEditor) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ShiftEditorScreen(onBack = { showShiftEditor = false })
            }
        }
    }
}

enum class AppDestinations(
    @get:StringRes val labelRes: Int,
    @get:DrawableRes val icon: Int,
) {
    SCHEDULE(R.string.tab_schedule, R.drawable.ic_calendar_month),
    TOOLS(R.string.tab_tools, R.drawable.ic_handyman),
    PROFILE(R.string.tab_profile, R.drawable.ic_account_box),
}

@Composable
fun PlaceholderScreen(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes message: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PlaceholderScreenPreview() {
    ShiftScheduleTheme {
        PlaceholderScreen(
            icon = R.drawable.ic_handyman,
            title = R.string.tab_tools,
            message = R.string.placeholder_tools,
        )
    }
}
