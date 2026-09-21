package dev.phonecode.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.provider.domain.MessagePart

internal val ComposerHeight = 52.dp
internal val ComposerActionTarget = 48.dp
internal val ComposerActionVisual = 36.dp
internal const val ComposerMaxLines = 6

internal enum class ComposerVisualState {
    EMPTY,
    READY,
    RUNNING,
    RUNNING_WITH_QUEUE,
    DISABLED,
}

internal fun composerVisualState(
    enabled: Boolean,
    loading: Boolean,
    running: Boolean,
    sendable: Boolean,
    queueAllowed: Boolean,
): ComposerVisualState = when {
    !enabled || loading -> ComposerVisualState.DISABLED
    running && queueAllowed && sendable -> ComposerVisualState.RUNNING_WITH_QUEUE
    running -> ComposerVisualState.RUNNING
    sendable -> ComposerVisualState.READY
    else -> ComposerVisualState.EMPTY
}

/** Mirrors [ChatViewModel.send]: runtime turns queue text only and reject photo payloads. */
internal fun canQueueComposerDraft(value: String, photos: List<MessagePart.Image>): Boolean =
    value.isNotBlank() && photos.isEmpty()

internal data class ComposerActionSlots(
    val primaryWidth: Dp,
    val queueWidth: Dp?,
)

internal fun composerActionSlots(state: ComposerVisualState): ComposerActionSlots =
    ComposerActionSlots(
        primaryWidth = ComposerActionTarget,
        queueWidth = if (state == ComposerVisualState.RUNNING_WITH_QUEUE) ComposerActionTarget else null,
    )

@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    photos: List<MessagePart.Image>,
    onRemovePhoto: (Int) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onQueue: (() -> Unit)?,
    enabled: Boolean,
    loading: Boolean,
    running: Boolean,
    sendOnEnter: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val canEdit = enabled && !loading
    val sendable = value.isNotBlank() || photos.isNotEmpty()
    val visualState = composerVisualState(
        enabled = enabled,
        loading = loading,
        running = running,
        sendable = sendable,
        queueAllowed = onQueue != null,
    )
    val actionSlots = composerActionSlots(visualState)
    val submit = when (visualState) {
        ComposerVisualState.READY -> onSend
        ComposerVisualState.RUNNING_WITH_QUEUE -> onQueue
        else -> null
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surfaceContainerHigh)
            .testTag("chat-composer"),
    ) {
        if (photos.isNotEmpty()) {
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                photos.forEachIndexed { index, image ->
                    Box(Modifier.size(72.dp)) {
                        PhotoThumbnail(image, Modifier.size(72.dp))
                        Box(
                            Modifier.align(Alignment.TopEnd).size(ComposerActionTarget)
                                .clickable(enabled = canEdit) { onRemovePhoto(index) }
                                .semantics { contentDescription = "Remove photo" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(24.dp).offset(x = 12.dp, y = (-12).dp)
                                    .clip(CircleShape).background(colors.inverseSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    null,
                                    tint = colors.inverseOnSurface,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = ComposerHeight).padding(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MisulIconButton(
                Icons.Filled.Add,
                "Add attachment",
                enabled = canEdit,
                visualSize = ComposerActionVisual,
                onClick = onAttach,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = canEdit,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
                cursorBrush = SolidColor(LocalMisulAccent.current),
                maxLines = ComposerMaxLines,
                keyboardOptions = if (sendOnEnter) {
                    KeyboardOptions(imeAction = ImeAction.Send)
                } else {
                    KeyboardOptions.Default
                },
                keyboardActions = KeyboardActions(onSend = { submit?.invoke() }),
                modifier = Modifier.weight(1f).heightIn(min = ComposerActionTarget)
                    .semantics { contentDescription = "Message" },
                decorationBox = { innerTextField ->
                    Box(Modifier.padding(horizontal = 4.dp, vertical = 13.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                if (loading) "Opening chat…" else "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Box(
                Modifier.size(actionSlots.primaryWidth),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = visualState,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.92f, animationSpec = tween(140)) + fadeIn(tween(140)))
                            .togetherWith(scaleOut(targetScale = 0.92f, animationSpec = tween(100)) + fadeOut(tween(100)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    contentAlignment = Alignment.Center,
                    label = "composerAction",
                ) { state ->
                    val isStop = state == ComposerVisualState.RUNNING || state == ComposerVisualState.RUNNING_WITH_QUEUE
                    MisulIconButton(
                        if (isStop) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                        if (isStop) "Stop" else "Send",
                        filled = state != ComposerVisualState.RUNNING_WITH_QUEUE,
                        enabled = state != ComposerVisualState.EMPTY && state != ComposerVisualState.DISABLED,
                        visualSize = ComposerActionVisual,
                        iconSize = 20.dp,
                        onClick = if (isStop) onStop else onSend,
                    )
                }
            }
            actionSlots.queueWidth?.let {
                MisulIconButton(
                    Icons.Filled.ArrowUpward,
                    "Queue message",
                    filled = true,
                    visualSize = ComposerActionVisual,
                    iconSize = 20.dp,
                    onClick = { onQueue?.invoke() },
                )
            }
        }
    }
}
