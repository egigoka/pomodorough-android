package me.egigoka.pomodorough.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.domain.TimerReducer

@Composable
fun PomodoroughScreen(
    state: AppState,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.authStatus) {
            AuthStatus.Loading -> LoadingScreen()
            AuthStatus.SignedOut, AuthStatus.SigningIn -> SignInScreen(
                signingIn = state.authStatus == AuthStatus.SigningIn,
                notice = state.notice,
                onSignIn = onSignIn,
                onDismissNotice = onDismissNotice,
            )
            AuthStatus.SignedIn -> TimerScreen(
                state = state,
                onLogout = onLogout,
                onToggleTimer = onToggleTimer,
                onFinishTimer = onFinishTimer,
                onCancelTimer = onCancelTimer,
                onClearTimer = onClearTimer,
                onSelectPhase = onSelectPhase,
                onChangeDuration = onChangeDuration,
                onSetAutoStart = onSetAutoStart,
                onDismissConflict = onDismissConflict,
                onDismissNotice = onDismissNotice,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(88.dp),
                containerColor = Lavender,
                indicatorColor = Violet,
            )
            Spacer(Modifier.height(28.dp))
            Text("Syncing your clock", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SignInScreen(
    signingIn: Boolean,
    notice: String?,
    onSignIn: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(7_000)
            onDismissNotice()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BrandMark()
            Spacer(Modifier.height(30.dp))
            Text(
                text = "Make time\nfeel yours.",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "One focused clock, in sync everywhere.",
                color = Lavender,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(30.dp))
            Surface(
                color = Butter,
                contentColor = Ink,
                shape = RoundedCornerShape(
                    topStart = 48.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 48.dp,
                ),
            ) {
                Column(Modifier.padding(24.dp)) {
                    SectionLabel("YOUR CLOCK, ANYWHERE")
                    Spacer(Modifier.height(10.dp))
                    Text("Pick up where you left off", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Google sign-in keeps timers and completed sessions shared across your devices. Offline actions wait safely for sync.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MutedInk,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onSignIn,
                        enabled = !signingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Violet,
                            contentColor = Color.White,
                        ),
                    ) {
                        if (signingIn) {
                            ContainedLoadingIndicator(
                                modifier = Modifier.size(36.dp),
                                containerColor = Color.White,
                                indicatorColor = Violet,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("CONTACTING GOOGLE")
                        } else {
                            Text("SIGN IN WITH GOOGLE")
                        }
                    }
                }
            }
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                NoticeCard(notice, onDismissNotice)
            }
        }
    }
}

@Composable
private fun TimerScreen(
    state: AppState,
    onLogout: () -> Unit,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val completedHistory = remember(state.history) {
        state.history
            .filter { it.status == TimerStatus.Completed }
            .sortedByDescending(::historyEpoch)
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(7_000)
            onDismissNotice()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            AppHeader(state = state, onLogout = { showLogoutDialog = true })
        }
        if (state.conflict != null) {
            item {
                MessageCard(
                    title = "Timer moved to another device",
                    message = state.conflict,
                    containerColor = Tomato,
                    onDismiss = onDismissConflict,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (state.notice != null) {
            item {
                NoticeCard(
                    message = state.notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TimerHero(
                    timer = state.timer,
                    settings = state.settings,
                    ready = state.ready,
                    onToggleTimer = onToggleTimer,
                    onFinishTimer = onFinishTimer,
                    onCancelTimer = onCancelTimer,
                    onClearTimer = onClearTimer,
                )
                PatternSection(
                    settings = state.settings,
                    timer = state.timer,
                    onSelectPhase = onSelectPhase,
                    onChangeDuration = onChangeDuration,
                    onSetAutoStart = onSetAutoStart,
                )
                HistoryTitle(completedHistory.size)
            }
        }
        if (completedHistory.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = Lavender,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        "Finish your first session and it will land here.",
                        modifier = Modifier.padding(22.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MutedInk,
                    )
                }
            }
        } else {
            items(items = completedHistory, key = { it.id }) { item ->
                HistoryRow(
                    item = item,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }
        }
        item {
            Footer(
                deviceId = state.deviceId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
            )
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = Cloud,
            icon = { BrandOrb(42.dp) },
            title = { Text("Log out on this device?") },
            text = {
                Text(
                    if (state.pendingCount > 0) {
                        "${state.pendingCount} action${if (state.pendingCount == 1) " is" else "s are"} still waiting to sync. Logging out discards ${if (state.pendingCount == 1) "it" else "them"}."
                    } else {
                        "Local account data will be removed. Your synced history stays on your account."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                ) { Text("Log out", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Stay signed in") }
            },
        )
    }
}

@Composable
private fun AppHeader(state: AppState, onLogout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Ink,
        contentColor = Color.White,
        shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, top = 18.dp, end = 14.dp, bottom = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(compact = true)
                TextButton(onClick = onLogout) { Text("Log out", color = Lavender) }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = syncColor(state.syncStatus),
                    contentColor = Ink,
                    shape = CircleShape,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Ink, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(syncLabel(state), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = state.user?.name?.ifBlank { state.user.email } ?: "Signed in",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun TimerHero(
    timer: CanonicalTimer?,
    settings: TimerSettings,
    ready: Boolean,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
) {
    val status = timer?.status ?: "idle"
    val phase = timer?.phase ?: settings.selectedPhase
    val active = status == TimerStatus.Running || status == TimerStatus.Paused
    val palette = phasePalette(phase)
    val containerColor by animateColorAsState(palette.container, label = "timer container")
    val corner by animateDpAsState(
        targetValue = when (status) {
            TimerStatus.Running -> 68.dp
            TimerStatus.Paused -> 46.dp
            else -> 56.dp
        },
        label = "timer shape",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = palette.onContainer,
        shape = RoundedCornerShape(
            topStart = corner,
            topEnd = 22.dp,
            bottomStart = 22.dp,
            bottomEnd = corner,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    SectionLabel("CURRENT TIMER")
                    Text(statusLabel(status), style = MaterialTheme.typography.titleLarge)
                }
                Surface(color = palette.accent, contentColor = Ink, shape = CircleShape) {
                    Text(
                        phaseLabel(phase),
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TimerOrbit(timer = timer, settings = settings, palette = palette)
            Text(
                timerInstruction(status),
                modifier = Modifier.fillMaxWidth(),
                color = palette.onContainer.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onToggleTimer,
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    when (status) {
                        TimerStatus.Running -> "Pause"
                        TimerStatus.Paused -> "Resume"
                        else -> "Start ${phaseLabel(phase).lowercase()}"
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onFinishTimer,
                    enabled = ready && active,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("Finish") }
                OutlinedButton(
                    onClick = onCancelTimer,
                    enabled = ready && active,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    border = BorderStroke(1.5.dp, palette.onContainer.copy(alpha = 0.55f)),
                ) { Text("Cancel") }
            }
            if (timer != null && !active) {
                TextButton(
                    onClick = onClearTimer,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear timer", color = palette.onContainer) }
            }
        }
    }
}

@Composable
private fun TimerOrbit(timer: CanonicalTimer?, settings: TimerSettings, palette: PhasePalette) {
    var now by remember(timer?.id, timer?.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer?.id, timer?.status, timer?.anchorAt) {
        while (timer?.status == TimerStatus.Running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val phase = timer?.phase ?: settings.selectedPhase
    val duration = timer?.plannedDurationMs ?: settings.minutesFor(phase) * 60_000L
    val elapsed = if (timer == null) 0 else TimerReducer.elapsedAt(timer, now)
    val remaining = (duration - elapsed).coerceAtLeast(0)
    val totalSeconds = ceil(remaining / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val progress = if (duration > 0) elapsed.toFloat() / duration else 0f
    val status = timer?.status ?: "idle"

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val orbitSize = min(maxWidth.value, 318f).dp
        Box(
            modifier = Modifier
                .size(orbitSize)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                    contentDescription = "$minutes minutes $seconds seconds remaining, ${phaseLabel(phase)}, $status"
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 18.dp.toPx()
                val inset = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                drawArc(
                    color = palette.onContainer.copy(alpha = 0.12f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                val sweep = 360f * progress.coerceIn(0f, 1f)
                drawArc(
                    color = palette.accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                if (progress > 0f) {
                    val radius = (size.minDimension - strokeWidth) / 2
                    val angle = (sweep - 90f) * (PI.toFloat() / 180f)
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        color = palette.onContainer,
                        radius = 5.dp.toPx(),
                        center = Offset(
                            center.x + cos(angle) * radius,
                            center.y + sin(angle) * radius,
                        ),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%02d:%02d".format(minutes, seconds),
                    color = palette.onContainer,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = if (orbitSize < 280.dp) 48.sp else 62.sp,
                    letterSpacing = (-3).sp,
                )
                Text(
                    text = phaseLabel(phase),
                    color = palette.onContainer.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = palette.onContainer.copy(alpha = 0.1f),
                    contentColor = palette.onContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        "${statusLabel(status)} · ${duration / 60_000} min",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternSection(
    settings: TimerSettings,
    timer: CanonicalTimer?,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
) {
    val active = timer?.status == TimerStatus.Running || timer?.status == TimerStatus.Paused
    Column {
        SectionLabel("YOUR RHYTHM")
        Text("Shape your session", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose what comes next, then tune its length.",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        PhaseCard(
            phase = TimerPhase.Focus,
            supportingText = "Deep work",
            settings = settings,
            enabled = !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(10.dp))
        PhaseCard(
            phase = TimerPhase.ShortBreak,
            supportingText = "Quick reset",
            settings = settings,
            enabled = !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(10.dp))
        PhaseCard(
            phase = TimerPhase.LongBreak,
            supportingText = "Full recharge",
            settings = settings,
            enabled = !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(12.dp))
        Surface(color = Ink, contentColor = Color.White, shape = MaterialTheme.shapes.large) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-start breaks", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Short after focus, long every fourth.",
                        color = Lavender,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.autoStartBreaks, onCheckedChange = onSetAutoStart)
            }
        }
    }
}

@Composable
private fun PhaseCard(
    phase: String,
    supportingText: String,
    settings: TimerSettings,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
) {
    val selected = settings.selectedPhase == phase
    val palette = phasePalette(phase)
    val shape = if (selected) {
        RoundedCornerShape(topStart = 36.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 36.dp)
    } else {
        MaterialTheme.shapes.large
    }
    Surface(
        onClick = { onSelect(phase) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) palette.container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Ink,
        shape = shape,
        border = if (selected) BorderStroke(2.dp, palette.accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (selected) palette.accent else Outline, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(phaseLabel(phase), style = MaterialTheme.typography.titleLarge)
                Text(supportingText, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            StepButton("−", "Decrease ${phaseLabel(phase)} duration", enabled) {
                onChangeDuration(phase, -1)
            }
            Text(
                "${settings.minutesFor(phase)}",
                modifier = Modifier.width(45.dp),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
            StepButton("+", "Increase ${phaseLabel(phase)} duration", enabled) {
                onChangeDuration(phase, 1)
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun HistoryTitle(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            SectionLabel("COMPLETED SESSIONS")
            Text("Recent focus", style = MaterialTheme.typography.headlineMedium)
        }
        Surface(color = Violet, contentColor = Color.White, shape = CircleShape) {
            Text(
                count.toString().padStart(2, '0'),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, modifier: Modifier = Modifier) {
    val palette = phasePalette(item.phase)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Ink,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = palette.container,
                contentColor = Ink,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(phaseStamp(item.phase), fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    phaseLabel(item.phase) + if (item.pending) " · queued" else "",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(formatHistoryDate(item), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "${(item.plannedDurationMs / 60_000).coerceAtLeast(1)} min",
                color = Violet,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    containerColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = containerColor, shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Ink) }
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MessageCard(
        title = "Heads up",
        message = message,
        containerColor = Butter,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun Footer(deviceId: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Local clock · shared account", color = MutedInk, style = MaterialTheme.typography.labelMedium)
        Text("${deviceId.takeLast(4).uppercase()}", color = Violet, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BrandMark(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandOrb(if (compact) 38.dp else 52.dp)
        Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
        Column {
            Text(
                "pomodorough",
                color = Color.White,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            )
            Text(
                "FOCUS IN MOTION",
                color = Butter,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun BrandOrb(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Butter, RoundedCornerShape(topStart = 50.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 50.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.4f).background(Tomato, CircleShape))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MutedInk, style = MaterialTheme.typography.labelMedium)
}

private data class PhasePalette(
    val container: Color,
    val accent: Color,
    val onContainer: Color = Ink,
)

private fun phasePalette(phase: String): PhasePalette = when (phase) {
    TimerPhase.ShortBreak -> PhasePalette(container = Mint, accent = Violet)
    TimerPhase.LongBreak -> PhasePalette(container = Lavender, accent = Tomato)
    else -> PhasePalette(container = Butter, accent = Tomato)
}

private fun syncLabel(state: AppState): String = when (state.syncStatus) {
    SyncStatus.Offline -> if (state.pendingCount > 0) "Offline · ${state.pendingCount} queued" else "Offline · local"
    SyncStatus.Conflict -> if (state.pendingCount > 0) "Conflict · ${state.pendingCount} queued" else "Conflict"
    SyncStatus.Syncing -> "Syncing"
    SyncStatus.Retrying -> if (state.pendingCount > 0) "Retrying · ${state.pendingCount} queued" else "Retrying sync"
    SyncStatus.Queued -> "${state.pendingCount} waiting to sync"
    SyncStatus.Checking -> "Checking sync"
    SyncStatus.Synced -> "In sync"
}

private fun syncColor(status: SyncStatus): Color = when (status) {
    SyncStatus.Synced -> Mint
    SyncStatus.Offline -> Color(0xFFD0C8D8)
    SyncStatus.Conflict, SyncStatus.Retrying -> Tomato
    SyncStatus.Syncing, SyncStatus.Queued, SyncStatus.Checking -> Butter
}

private fun statusLabel(status: String): String = when (status) {
    TimerStatus.Running -> "Running"
    TimerStatus.Paused -> "Paused"
    TimerStatus.Completed -> "Complete"
    TimerStatus.Cancelled -> "Cancelled"
    TimerStatus.Superseded -> "Moved"
    else -> "Ready"
}

private fun timerInstruction(status: String): String = when (status) {
    TimerStatus.Running -> "Stay here, or keep going from another device."
    TimerStatus.Paused -> "Your place is held until you are ready."
    TimerStatus.Completed -> "Session complete. Clear it or begin another."
    TimerStatus.Cancelled -> "Session cancelled. Clear it or begin again."
    TimerStatus.Superseded -> "This timer continued on another device."
    else -> "Choose a rhythm, then start when it feels right."
}

private fun phaseLabel(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "Short break"
    TimerPhase.LongBreak -> "Long break"
    else -> "Focus"
}

private fun phaseStamp(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "SB"
    TimerPhase.LongBreak -> "LB"
    else -> "F"
}

private fun historyEpoch(item: HistoryItem): Long {
    val value = item.completedAt ?: item.endedAt ?: return 0
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
}

private fun formatHistoryDate(item: HistoryItem): String {
    val value = item.completedAt ?: item.endedAt ?: return "Time not recorded"
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault("Time not recorded")
}
