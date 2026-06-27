package dev.phonecode.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.phonecode.app.ui.components.PcGroup
import dev.phonecode.app.ui.components.PcRow
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.PhoneTweens
import dev.phonecode.app.ui.theme.ShapePill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-run onboarding (round-4): a welcome beat, then the three ways into a working setup -
 * Codex sign-in, an API key, GitHub for repos. Each option deep-links into the existing settings
 * flow rather than duplicating it; everything is skippable. Monochrome, staggered spring
 * entrances, connected-card options - the app's own design language from the first frame.
 */
@Composable
fun OnboardingScreen(
    onConnectModels: () -> Unit,
    onConnectGitHub: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var step by rememberSaveable { mutableStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = step > 0) { step = 0 }

    Box(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(PhoneSprings.standardSpec()) { it / 3 } + fadeIn(PhoneTweens.popEnter)) togetherWith
                    (slideOutHorizontally(PhoneSprings.standardSpec()) { -it / 4 } + fadeOut(PhoneTweens.popExit))
            },
            label = "onboarding",
        ) { s ->
            when (s) {
                0 -> Welcome(onNext = { step = 1 })
                else -> Connect(
                    onConnectModels = onConnectModels,
                    onConnectGitHub = onConnectGitHub,
                    onSkip = onDone,
                )
            }
        }
    }
}

/** Staggered entrance: fade + small rise, offset by [delayMs] - the welcome page's rhythm. */
@Composable
private fun Modifier.entrance(delayMs: Int): Modifier {
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(18f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch { alpha.animateTo(1f, tween(380, easing = PhoneEasings.emphasizedDecelerate)) }
        rise.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer {
        this.alpha = alpha.value
        translationY = rise.value.dp.toPx()
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val markScale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { markScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(96.dp)
                .entrance(0)
                .graphicsLayer { scaleX = markScale.value; scaleY = markScale.value }
                .background(colors.onBackground, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "</>",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = PcMono, fontWeight = FontWeight.SemiBold),
                color = colors.background,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "PhoneCode",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onBackground,
            modifier = Modifier.entrance(90),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "An AI coding agent that lives on your phone. Your keys, your repos, your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.entrance(180),
        )
        Spacer(Modifier.weight(1f))
        BigButton("Get started", filled = true, modifier = Modifier.entrance(280)) { onNext() }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Connect(onConnectModels: () -> Unit, onConnectGitHub: () -> Unit, onSkip: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(72.dp))
        Text(
            "Connect a model",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onBackground,
            modifier = Modifier.entrance(0).padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "PhoneCode talks directly to the provider you choose. Keys are encrypted on-device and sent nowhere else.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.entrance(70).padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.entrance(150)) {
            PcGroup {
                // Both model options land on the Providers page on purpose: it hosts BOTH the API-key entry
                // and the "Sign in with ChatGPT (Codex)" button, so the user finishes setup in one place.
                OptionRow(
                    icon = Icons.Outlined.Forum,
                    title = "Sign in with ChatGPT",
                    sub = "Use your Codex subscription - no key needed",
                    onClick = onConnectModels,
                )
                OptionRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Add an API key",
                    sub = "Anthropic, OpenAI, OpenRouter, Gemini and more",
                    onClick = onConnectModels,
                )
                OptionRow(
                    icon = Icons.Outlined.AccountTree,
                    title = "Sign in with GitHub",
                    sub = "Clone, push and pull your repositories",
                    onClick = onConnectGitHub,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.entrance(240).fillMaxWidth()) {
            Text(
                "Skip for now",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcRow(onClick = onClick) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
    }
}

/** Full-width 56dp M3-Expressive pill button. */
@Composable
private fun BigButton(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (filled) colors.primary else colors.surfaceContainerHigh, ShapePill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (filled) colors.onPrimary else colors.onBackground,
        )
    }
}
