package dev.glorioustr.bakimporter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.ui.theme.HyperOSBlue

@Composable
fun StepGuideDialog(
    onDismiss: () -> Unit,
    onOpenRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.guide_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                GuideStepItem(
                    number = "1",
                    title = stringResource(R.string.guide_step_1_title),
                    description = stringResource(R.string.guide_step_1_desc),
                )
                Spacer(modifier = Modifier.height(14.dp))
                GuideStepItem(
                    number = "2",
                    title = stringResource(R.string.guide_step_2_title),
                    description = stringResource(R.string.guide_step_2_desc),
                )
                Spacer(modifier = Modifier.height(14.dp))
                GuideStepItem(
                    number = "3",
                    title = stringResource(R.string.guide_step_3_title),
                    description = stringResource(R.string.guide_step_3_desc),
                )
                Spacer(modifier = Modifier.height(14.dp))
                GuideStepItem(
                    number = "4",
                    title = stringResource(R.string.guide_step_4_title),
                    description = stringResource(R.string.guide_step_4_desc),
                )
                Spacer(modifier = Modifier.height(14.dp))
                GuideStepItem(
                    number = "5",
                    title = stringResource(R.string.guide_step_5_title),
                    description = stringResource(R.string.guide_step_5_desc),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onOpenRestore()
                }
            ) {
                Text(stringResource(R.string.action_open_restore_screen))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_ok))
            }
        }
    )
}

@Composable
fun GuideStepItem(
    number: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(HyperOSBlue),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
