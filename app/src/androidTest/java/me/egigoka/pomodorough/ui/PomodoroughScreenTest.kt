package me.egigoka.pomodorough.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AccountSwitchState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.HistoryResolutionState
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.integration.testHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PomodoroughScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resolutionConfirmationsWarnAndCancelHasNoSideEffect() {
        var resolved: BootstrapStrategy? = null
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                syncStatus = SyncStatus.Conflict,
                historyResolution = HistoryResolutionState(2, 3),
            ),
            onResolve = { resolved = it },
        )

        composeRule.onNodeWithText("Keep Local").performClick()
        composeRule.onNodeWithText(
            "Remote account history will be replaced by this device's local history. This cannot be undone.",
        ).assertExists()
        composeRule.onNodeWithContentDescription("Cancel history choice").performClick()

        assertNull(resolved)
        composeRule.onNodeWithText("Choose your history").assertExists()

        composeRule.onNodeWithText("Keep Both").performClick()
        composeRule.onNodeWithText(
            "Local and remote operations will be combined. Conflicting operations may be ignored or rejected, and errors are possible.",
        ).assertExists()
        composeRule.onNode(hasText("Confirm Keep Both") and hasClickAction()).performClick()

        assertEquals(BootstrapStrategy.Merge, resolved)
    }

    @Test
    fun signedOutStateStillShowsLocalTimerHistoryAndSignIn() {
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedOut,
                history = listOf(testHistory("local-history", TimerPhase.Focus).copy(pending = true)),
                syncStatus = SyncStatus.Queued,
                pendingCount = 2,
                deviceId = "device-local",
            ),
        )

        composeRule.onNodeWithText("Sign in").assertExists()
        composeRule.onNodeWithText("Recent focus").assertExists()
        composeRule.onNodeWithText("Local · 2 saved").assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Focus · queued"))
        composeRule.onNodeWithText("Focus · queued").assertExists()
    }

    @Test
    fun loadingDisablesTimerAndDurationMutations() {
        setScreen(AppState(ready = true, authStatus = AuthStatus.Loading))

        composeRule.onNodeWithText("Start focus").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Increase Focus duration").assertIsNotEnabled()
    }

    @Test
    fun signingInDisablesMutationsAndSavedChoiceSignInButton() {
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SigningIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 0,
                    pendingStrategy = BootstrapStrategy.ReplaceRemote,
                    requestId = "request-1",
                ),
            ),
        )

        composeRule.onNodeWithText("Start focus").assertIsNotEnabled()
        composeRule.onNode(hasText("Signing in to retry...") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun savedChoiceSignInActionIsEnabledOnlyWhenSignedOut() {
        var signInCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedOut,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 0,
                    pendingStrategy = BootstrapStrategy.ReplaceRemote,
                    requestId = "request-1",
                ),
            ),
            onSignIn = { signInCalls += 1 },
        )

        composeRule.onNodeWithText("Sign in to retry").performClick()

        assertEquals(1, signInCalls)
    }

    @Test
    fun corruptedResolutionOffersMetadataOnlyRepreview() {
        var recoverCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 1,
                    corrupted = true,
                    recovery = ResolutionRecovery.Repreview,
                ),
            ),
            onRecover = { recoverCalls += 1 },
        )

        composeRule.onNodeWithText("Discard request and re-check").performClick()

        assertEquals(1, recoverCalls)
    }

    @Test
    fun oversizedResolutionRequiresKeepRemoteConfirmation() {
        var resolved: BootstrapStrategy? = null
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 1,
                    corrupted = true,
                    recovery = ResolutionRecovery.KeepRemote,
                ),
            ),
            onResolve = { resolved = it },
        )

        composeRule.onNodeWithText("Review Keep Remote").performClick()
        composeRule.onNodeWithText(
            "Local history and unsynced operations will be removed, then remote account history will be installed. This cannot be undone.",
        ).assertExists()
        composeRule.onNode(hasText("Confirm Keep Remote") and hasClickAction()).performClick()

        assertEquals(BootstrapStrategy.KeepRemote, resolved)
    }

    @Test
    fun differentAccountDialogRequiresExplicitSafeChoice() {
        var confirmCalls = 0
        var cancelCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                accountSwitch = AccountSwitchState(
                    localAccount = "old@example.com",
                    incomingAccount = "new@example.com",
                ),
            ),
            onConfirmAccountSwitch = { confirmCalls += 1 },
            onCancelAccountSwitch = { cancelCalls += 1 },
        )

        composeRule.onNodeWithText("Different account detected").assertExists()
        composeRule.onNodeWithText(
            "Local data belongs to old@example.com, but new@example.com is signed in. Switching accounts permanently removes this device's timer, history, tasks, and unsynced operations.",
        ).assertExists()
        composeRule.onNodeWithText("Keep local data").performClick()
        composeRule.onNodeWithText("Switch and remove local data").performClick()

        assertEquals(1, cancelCalls)
        assertEquals(1, confirmCalls)
    }

    private fun setScreen(
        state: AppState,
        onSignIn: () -> Unit = {},
        onResolve: (BootstrapStrategy) -> Unit = {},
        onRecover: () -> Unit = {},
        onConfirmAccountSwitch: () -> Unit = {},
        onCancelAccountSwitch: () -> Unit = {},
    ) {
        composeRule.setContent {
            PomodoroughTheme {
                PomodoroughScreen(
                    state = state,
                    onSignIn = onSignIn,
                    onLogout = {},
                    onToggleTimer = {},
                    onFinishTimer = {},
                    onCancelTimer = {},
                    onClearTimer = {},
                    onSelectPhase = {},
                    onChangeDuration = { _, _ -> },
                    onSetAutoStart = {},
                    onSelectTask = {},
                    onAddTask = {},
                    onDeleteTask = {},
                    onResolveHistory = onResolve,
                    onRecoverHistoryResolution = onRecover,
                    onConfirmAccountSwitch = onConfirmAccountSwitch,
                    onCancelAccountSwitch = onCancelAccountSwitch,
                    onDismissConflict = {},
                    onDismissNotice = {},
                )
            }
        }
    }
}
