package dev.phonecode.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonecode.app.R
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.SettingsViewModel
import dev.phonecode.app.ui.chat.MarkdownBlocks
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulDialog
import dev.phonecode.app.ui.components.MisulDialogAction
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ExportPage(vm: ChatViewModel, settingsVm: SettingsViewModel, onBack: () -> Unit) {
    val state by collectSettingsState(vm)
    val importing = state.sessionLoading
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportTo(uri) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri) { settingsVm.reload() } }
    SettingsPageShell("Export & import", onBack, backEnabled = !importing) {
        SettingsDataSection("Your data")
        SettingsNote("Exports are not encrypted. Saved provider and sign-in credentials are excluded, but chats and tool activity may contain sensitive content.")
        SettingsNote("Import replaces chats and settings with the backup. Linked phone folders, provider keys, MCP servers, and skills are not included. Approval always returns to Ask before each change.")
        Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            MisulActionButton("Export chats & settings", onClick = { exporter.launch("phonecode-backup-$stamp.zip") }, role = ActionRole.SECONDARY, enabled = !importing, modifier = Modifier.weight(1f))
            MisulActionButton("Import from a file", onClick = { confirmImport = true }, role = ActionRole.SECONDARY, enabled = !importing, modifier = Modifier.weight(1f))
        }
        if (importing) SettingsNote("Importing backup…", announce = true)
        state.notice?.let { notice ->
            SettingsNote(notice, announce = true)
            LaunchedEffect(notice) { kotlinx.coroutines.delay(4000); vm.clearNotice() }
        }
    }
    if (confirmImport) {
        ConfirmImportDialog(
            title = "Replace chats and settings?",
            message = "The backup replaces current chats and settings. Existing chats not in the backup are removed, and approval returns to Ask before each change. This cannot be undone unless you export first.",
            action = "Choose backup file",
            secondaryAction = "Export first",
            onSecondary = { confirmImport = false; exporter.launch("phonecode-backup-$stamp.zip") },
            onDismiss = { confirmImport = false },
        ) { confirmImport = false; importer.launch(arrayOf("application/zip", "application/octet-stream")) }
    }
}

@Composable
private fun ConfirmImportDialog(
    title: String,
    message: String,
    action: String,
    secondaryAction: String,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    MisulDialog(
        title = title,
        onDismissRequest = onDismiss,
        body = { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        actions = {
            MisulDialogAction("Cancel", onDismiss)
            Spacer(Modifier.weight(1f))
            MisulDialogAction(secondaryAction, onSecondary)
            MisulDialogAction(action, onConfirm, destructive = true)
        },
    )
}

@Composable
internal fun AboutPage(vm: ChatViewModel, onOpenDoc: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val configPath = remember(vm) { vm.configDirPath() }
    var browserError by remember { mutableStateOf<String?>(null) }
    var copiedConfigPath by remember { mutableStateOf(false) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.1" }
    SettingsPageShell("About", onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_misul_mark), null, tint = LocalMisulAccent.current, modifier = Modifier.height(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("Misul Agent", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("version $version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MisulGroup {
            SettingsNavigationRow("Website", "misul.org", onClick = { browserError = openExternalUrl(context, "https://misul.org") })
            MisulContentRow {
                Column(Modifier.weight(1f)) {
                    Text("Config directory", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(configPath, style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MisulIconButton(Icons.Outlined.ContentCopy, "Copy config directory path", onClick = { clipboard.setText(AnnotatedString(configPath)); copiedConfigPath = true })
            }
            SettingsNavigationRow("Terms of Service") { onOpenDoc("doc:terms") }
            SettingsNavigationRow("Privacy Policy") { onOpenDoc("doc:privacy") }
            SettingsNavigationRow("Open-source licenses", showDivider = false) { onOpenDoc("doc:licenses") }
        }
        browserError?.let { SettingsErrorText(it, Modifier.padding(top = Spacing.xs)) }
        if (copiedConfigPath) SettingsNote("Copied config path", announce = true)
    }
}

@Composable
internal fun DocPage(title: String, assetName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(assetName) { runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }.getOrDefault("Document unavailable.") }
    SettingsPageShell(title, onBack) {
        Box(Modifier.padding(vertical = Spacing.xs)) {
            MarkdownBlocks(remember(text) { text.replace(Regex("^#\\s+.*(\\R+)?"), "") }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsDataSection(label: String) = Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.s, top = Spacing.m, bottom = Spacing.xs))
