package dev.glorioustr.bakimporter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.model.BakArchiveInfo
import dev.glorioustr.bakimporter.model.ThemeManagerInfo
import dev.glorioustr.bakimporter.ui.theme.HyperOSGreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSOrange

@Composable
fun BakDetailsCard(
    archive: BakArchiveInfo,
    themeManagerInfo: ThemeManagerInfo,
    allowMismatch: Boolean,
    onAllowMismatchChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val versionsMatch = themeManagerInfo.versionCode == 0L || archive.backupVersionCode == themeManagerInfo.versionCode

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.archive_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            DetailRow(label = stringResource(R.string.archive_file_name), value = archive.displayName)
            DetailRow(label = stringResource(R.string.archive_size), value = archive.formattedSize)
            DetailRow(label = stringResource(R.string.archive_version_code), value = "${archive.backupVersionCode}")
            DetailRow(label = stringResource(R.string.archive_entries_count), value = "${archive.entryCount} dosya")
            DetailRow(
                label = stringResource(R.string.archive_apply_rights),
                value = stringResource(
                    if (archive.hasApplyRights) R.string.archive_apply_rights_present
                    else R.string.archive_apply_rights_missing,
                    archive.rightsFileCount,
                ),
            )

            if (!archive.hasApplyRights) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = HyperOSOrange,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.archive_apply_rights_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = HyperOSOrange,
                    )
                }
            }

            if (themeManagerInfo.isInstalled) {
                DetailRow(
                    label = stringResource(R.string.archive_device_version),
                    value = "${themeManagerInfo.versionName} (${themeManagerInfo.versionCode})",
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Version Match / Mismatch indicator
            if (versionsMatch) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = HyperOSGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.version_match_ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HyperOSGreen,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = HyperOSOrange,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.version_mismatch_warning,
                                archive.backupVersionCode,
                                themeManagerInfo.versionCode,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HyperOSOrange,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = allowMismatch,
                            onCheckedChange = onAllowMismatchChange,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.version_patch_checkbox),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
