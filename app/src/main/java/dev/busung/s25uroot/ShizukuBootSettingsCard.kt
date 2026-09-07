package dev.busung.s25uroot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Settings surface for RMG's independent, optional pre-root Shizuku bootstrap. */
@Composable
internal fun ShizukuBootSettingsCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppPreferences.startShizukuOnBoot(context)) }
    var authToken by remember { mutableStateOf(AppPreferences.shizukuAutomationToken(context)) }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.shizuku_boot_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.shizuku_boot_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        AppPreferences.setStartShizukuOnBoot(context, checked)
                        if (!checked) {
                            ShizukuBootService.stop(context)
                            TemporaryWirelessAdb.forceDisable(context)
                        }
                    },
                )
            }

            AnimatedVisibility(visible = enabled) {
                OutlinedTextField(
                    value = authToken,
                    onValueChange = { value ->
                        authToken = value
                        AppPreferences.setShizukuAutomationToken(context, value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(androidx.compose.ui.res.stringResource(R.string.shizuku_boot_auth_token))
                    },
                    supportingText = {
                        Text(androidx.compose.ui.res.stringResource(R.string.shizuku_boot_auth_token_description))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
    }
}
