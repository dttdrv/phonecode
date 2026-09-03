package dev.phonecode.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulNavigationRow
import dev.phonecode.app.ui.components.MisulSelectionRow
import dev.phonecode.app.ui.components.MisulToggleRow
import dev.phonecode.app.ui.components.StretchSyncedScrollChrome
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.theme.Spacing

/** The only Settings top-bar, inset, scroll-chrome, width, and bottom-padding owner. */
@Composable
internal fun SettingsPageShell(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val scrolled = remember { derivedStateOf { scrollState.canScrollBackward } }
    val hasMoreBelow = remember { derivedStateOf { scrollState.canScrollForward } }
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier.fillMaxSize().background(colors.background)
            .testTag("settings-page-shell"),
    ) {
        StretchSyncedScrollChrome(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(),
            showTop = scrolled.value,
            showBottom = hasMoreBelow.value,
            topHeight = statusInset + Spacing.navBarHeight + 12.dp,
            bottomHeight = bottomInset + 16.dp,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .contentVerticalScroll(scrollState)
                    .background(colors.background)
                    .padding(
                        start = Spacing.m,
                        end = Spacing.m,
                        top = statusInset + Spacing.navBarHeight + 4.dp,
                    ),
            ) {
                content()
                Spacer(Modifier.height(Spacing.xxl + bottomInset))
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth()
                .height(statusInset + Spacing.navBarHeight)
                .zIndex(1f)
                .padding(start = 8.dp, top = statusInset, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MisulIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, enabled = backEnabled)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (action == null) Spacer(Modifier.width(Spacing.touchTarget)) else Box(Modifier.width(Spacing.touchTarget)) { action() }
        }
    }
}

@Composable
internal fun SettingsNavigationRow(
    label: String,
    value: String? = null,
    icon: ImageVector? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) = MisulNavigationRow(
    label = label,
    value = value,
    icon = icon,
    showDivider = showDivider,
    onClick = onClick,
)

@Composable
internal fun SettingsToggleRow(
    label: String,
    sub: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    onChange: (Boolean) -> Unit,
) = MisulToggleRow(
    label = label,
    checked = checked,
    onCheckedChange = onChange,
    supportingText = sub,
    enabled = enabled,
    showDivider = showDivider,
)

@Composable
internal fun SettingsSelectionRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) = MisulSelectionRow(
    label = label,
    selected = selected,
    onClick = onClick,
    supportingText = sub,
    showDivider = showDivider,
)

@Composable
internal fun SettingsNote(text: String, announce: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = Spacing.xs, bottom = Spacing.xs)
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsErrorText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics {
            error(text)
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
internal fun SettingsFieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp),
    )
}
