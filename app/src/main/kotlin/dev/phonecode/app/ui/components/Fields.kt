package dev.phonecode.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.theme.LocalMisulAccent

@Composable
fun MisulField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secure: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String = label,
) {
    val colors = MaterialTheme.colorScheme
    var secureVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(LocalMisulAccent.current),
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (secure && !secureVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secure) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = MisulMinimumInteractiveSize)
                .focusRequester(focusRequester)
                .pointerInput(focusRequester) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focusRequester.requestFocus()
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    }
                }
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceContainerHighest)
                .semantics {
                    this.contentDescription = contentDescription
                    error?.let { this.error(error) }
                },
            decorationBox = { innerTextField ->
                Column(
                    Modifier.padding(start = 16.dp, end = if (secure) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    TextLabel(label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) {
                                androidx.compose.material3.Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            innerTextField()
                        }
                        if (secure) {
                            MisulIconButton(
                                icon = if (secureVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (secureVisible) "Hide $label" else "Show $label",
                                onClick = { secureVisible = !secureVisible },
                                enabled = enabled,
                            )
                        }
                    }
                }
            },
        )
        val helper = error ?: supportingText
        helper?.let {
            androidx.compose.material3.Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
fun MisulSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentDescription: String = placeholder,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(LocalMisulAccent.current),
        singleLine = true,
        modifier = modifier.fillMaxWidth()
            .heightIn(min = MisulMinimumInteractiveSize)
            .focusRequester(focusRequester)
            .pointerInput(focusRequester) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    focusRequester.requestFocus()
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerHighest)
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { innerTextField ->
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        androidx.compose.material3.Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun TextLabel(label: String) {
    androidx.compose.material3.Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
}
