package dev.busung.s25uroot

import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RecoveryTool {
    RestartZygote,
    ReloadModules,
    SoftReboot,
    RebootUnroot,
}

@Composable
internal fun AdvancedRecoverySettings(
    rootActive: Boolean,
    autoRootEnabled: Boolean,
    onAutoRootEnabledChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val acceptedMessageTemplate = stringResource(R.string.recovery_action_accepted)
    val failedMessageTemplate = stringResource(R.string.recovery_action_failed)
    var runningTool by remember { mutableStateOf<RecoveryTool?>(null) }

    fun runTool(tool: RecoveryTool, operation: suspend () -> RootRecoveryResult) {
        if (runningTool != null || !rootActive) return
        runningTool = tool
        scope.launch {
            val result = runCatching { operation() }
                .getOrElse { error ->
                    RootRecoveryResult(
                        accepted = false,
                        detail = error.message ?: error.javaClass.simpleName,
                    )
                }
            val messageTemplate = if (result.accepted) {
                acceptedMessageTemplate
            } else {
                failedMessageTemplate
            }
            Toast.makeText(
                context,
                String.format(Locale.getDefault(), messageTemplate, result.detail),
                Toast.LENGTH_LONG,
            ).show()
            runningTool = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.recovery_tools_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        )
        Text(
            text = stringResource(R.string.recovery_tools_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HoldRecoveryCard(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.recovery_restart_zygote_title),
                description = stringResource(R.string.recovery_restart_zygote_description),
                enabled = rootActive && runningTool == null,
                busy = runningTool == RecoveryTool.RestartZygote,
                shape = recoveryShape(top = true),
                onConfirmed = {
                    runTool(RecoveryTool.RestartZygote) {
                        RootRecoveryActions.restartZygote(context)
                    }
                },
            )
            HoldRecoveryCard(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.recovery_reload_modules_title),
                description = stringResource(R.string.recovery_reload_modules_description),
                enabled = rootActive && runningTool == null,
                busy = runningTool == RecoveryTool.ReloadModules,
                shape = recoveryShape(),
                onConfirmed = {
                    runTool(RecoveryTool.ReloadModules) {
                        RootRecoveryActions.reloadKernelSuModules(context)
                    }
                },
            )
            HoldRecoveryCard(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.recovery_soft_reboot_title),
                description = stringResource(R.string.recovery_soft_reboot_description),
                enabled = rootActive && runningTool == null,
                busy = runningTool == RecoveryTool.SoftReboot,
                shape = recoveryShape(),
                onConfirmed = {
                    runTool(RecoveryTool.SoftReboot) {
                        RootRecoveryActions.kernelSuSoftReboot(context)
                    }
                },
            )
            HoldRecoveryCard(
                icon = Icons.Rounded.Warning,
                title = stringResource(R.string.recovery_reboot_unroot_title),
                description = stringResource(R.string.recovery_reboot_unroot_description),
                enabled = rootActive && runningTool == null,
                busy = runningTool == RecoveryTool.RebootUnroot,
                destructive = true,
                shape = recoveryShape(bottom = true),
                onConfirmed = {
                    if (runningTool != null || !rootActive) return@HoldRecoveryCard
                    runTool(RecoveryTool.RebootUnroot) {
                        val result = RootRecoveryActions.rebootAndUnroot(context)
                        if (result.accepted) onAutoRootEnabledChanged(false)
                        result
                    }
                },
            )
        }

        Text(
            text = if (rootActive) {
                stringResource(R.string.recovery_hold_hint)
            } else {
                stringResource(R.string.recovery_root_required)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp),
        )
        Text(
            text = stringResource(R.string.recovery_unroot_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error.copy(alpha = if (autoRootEnabled) 1f else 0.82f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun HoldRecoveryCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    busy: Boolean,
    shape: RoundedCornerShape,
    destructive: Boolean = false,
    onConfirmed: () -> Unit,
) {
    val view = LocalView.current
    var holding by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = when {
            !enabled && !busy -> 0.48f
            holding -> 0.72f
            else -> 1f
        },
        label = "recovery-hold-alpha",
    )
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .semantics { role = Role.Button }
            .pointerInput(enabled, busy, title) {
                if (!enabled || busy) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    holding = true
                    val releasedBeforeThreshold = withTimeoutOrNull(
                        RootRecoveryActions.HOLD_TO_CONFIRM_MILLIS,
                    ) {
                        waitForUpOrCancellation()
                        true
                    } ?: false

                    if (!releasedBeforeThreshold) {
                        performHoldHaptic(view)
                        holding = false
                        onConfirmed()
                        waitForUpOrCancellation()
                    } else {
                        holding = false
                    }
                }
            },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = contentColor,
                )
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun recoveryShape(top: Boolean = false, bottom: Boolean = false) = RoundedCornerShape(
    topStart = if (top) 24.dp else 6.dp,
    topEnd = if (top) 24.dp else 6.dp,
    bottomStart = if (bottom) 24.dp else 6.dp,
    bottomEnd = if (bottom) 24.dp else 6.dp,
)

private fun performHoldHaptic(view: android.view.View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}
