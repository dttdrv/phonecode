package dev.phonecode.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.phonecode.app.R
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulContentRow
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulNavigationRow
import dev.phonecode.app.ui.navigation.MisulNavigationMotion
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PhoneEasings

@Composable
fun OnboardingScreen(
    step: Int,
    onStepChange: (Int) -> Unit,
    onConnectModels: () -> Unit,
    onConnectGitHub: () -> Unit,
    onCreateProject: () -> Unit,
    modelReady: Boolean = false,
    githubReady: Boolean = false,
    projectReady: Boolean = false,
    errorMessage: String? = null,
    onDone: () -> Unit,
    onSkip: () -> Unit = onDone,
) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    androidx.activity.compose.BackHandler(enabled = step > 0) { onStepChange(0) }

    Box(
        Modifier.fillMaxSize()
            .background(colors.background)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.12f), Color.Transparent, Color.Transparent),
                ),
            ),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                if (forward) {
                    (MisulNavigationMotion.forwardEnter() + fadeIn(tween(180, easing = PhoneEasings.easeOut))) togetherWith
                        (MisulNavigationMotion.forwardExit() + fadeOut(tween(120, easing = PhoneEasings.easeOut)))
                } else {
                    (MisulNavigationMotion.backEnter() + fadeIn(tween(180, easing = PhoneEasings.easeOut))) togetherWith
                        (MisulNavigationMotion.backExit() + fadeOut(tween(120, easing = PhoneEasings.easeOut)))
                }
            },
            label = "onboarding",
        ) { currentStep ->
            if (currentStep == 0) {
                Welcome(onNext = { onStepChange(1) })
            } else {
                Connect(
                    onBack = { onStepChange(0) },
                    onConnectModels = onConnectModels,
                    onConnectGitHub = onConnectGitHub,
                    onCreateProject = onCreateProject,
                    modelReady = modelReady,
                    githubReady = githubReady,
                    projectReady = projectReady,
                    errorMessage = errorMessage,
                    onDone = onDone,
                    onSkip = onSkip,
                )
            }
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_misul_mark),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    "Misul Agent",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onBackground,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Build real projects from your phone",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Run Misul Agent in a private local workspace, with the models and tools you trust and access to phone folders you choose.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            MisulGroup {
                FeatureRow(Icons.Outlined.Folder, "Private project workspaces", "Keep each project and its chats together")
                FeatureRow(Icons.Outlined.AccountTree, "Local tools and Git", "Build, test, and manage source control on device")
                FeatureRow(
                    Icons.Outlined.Cloud,
                    "Your choice of model",
                    "Sign in or add provider access",
                    showDivider = false,
                )
            }
        }
        MisulActionButton(
            label = "Get started",
            role = ActionRole.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            onClick = onNext,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Connect(
    onBack: () -> Unit,
    onConnectModels: () -> Unit,
    onConnectGitHub: () -> Unit,
    onCreateProject: () -> Unit,
    modelReady: Boolean,
    githubReady: Boolean,
    projectReady: Boolean,
    errorMessage: String?,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MisulIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Text(
                "Setup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                "Step 2 of 2",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                "Get ready to build",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect a model to start. Link a phone folder and GitHub when you need them.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            MisulGroup {
                OptionRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Connect a model",
                    sub = if (modelReady) "Model configured on this device" else "Required for agent work",
                    complete = modelReady,
                    active = !modelReady,
                    onClick = onConnectModels,
                )
                OptionRow(
                    icon = Icons.Outlined.Folder,
                    title = "Link a phone folder",
                    sub = if (projectReady) "Folder linked for shared file access" else "Optional access to files already on your phone",
                    complete = projectReady,
                    active = false,
                    onClick = onCreateProject,
                )
                OptionRow(
                    icon = Icons.Outlined.AccountTree,
                    title = "Connect GitHub",
                    sub = if (githubReady) "GitHub account connected" else "Optional for repository sync",
                    complete = githubReady,
                    active = false,
                    showDivider = false,
                    onClick = onConnectGitHub,
                )
            }
        }
        errorMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics {
                        error(message)
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        MisulActionButton(
            label = "Start building",
            role = ActionRole.PRIMARY,
            enabled = modelReady,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            onClick = onDone,
        )
        if (!modelReady) {
            Text(
                "A model is required to run an agent. Optional setup can wait.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (!modelReady) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MisulActionButton(
                    label = "Explore without a model",
                    role = ActionRole.QUIET,
                    onClick = onSkip,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    sub: String,
    showDivider: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    MisulContentRow(showDivider = showDivider) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    sub: String,
    complete: Boolean,
    active: Boolean,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    val state = when {
        complete -> "Complete"
        active -> "Required"
        else -> "Optional"
    }
    MisulNavigationRow(
        label = title,
        supportingText = sub,
        value = state,
        icon = icon,
        showDivider = showDivider,
        modifier = Modifier.semantics { stateDescription = state },
        onClick = onClick,
    )
}
