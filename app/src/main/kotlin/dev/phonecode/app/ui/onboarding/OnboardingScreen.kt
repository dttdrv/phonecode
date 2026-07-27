package dev.phonecode.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.phonecode.app.R
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcGroup
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.PcRow
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
    onDone: () -> Unit,
    onSkip: () -> Unit = onDone,
) {
    val colors = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler(enabled = step > 0) { onStepChange(0) }

    Box(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val enterOffset: (Int) -> Int = { if (forward) it / 3 else -it / 3 }
                val exitOffset: (Int) -> Int = { if (forward) -it / 4 else it / 4 }
                (slideInHorizontally(tween(240, easing = PhoneEasings.easeInOut), enterOffset) +
                    fadeIn(tween(180, easing = PhoneEasings.easeOut))) togetherWith
                    (slideOutHorizontally(tween(160, easing = PhoneEasings.easeInOut), exitOffset) +
                        fadeOut(tween(120, easing = PhoneEasings.easeOut)))
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
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
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
                        painter = painterResource(R.drawable.ic_phonecode_mark),
                        contentDescription = null,
                        tint = colors.onBackground,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    "PhoneCode",
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
                "Run an AI coding agent inside folders you choose, with the models and tools you trust.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            PcGroup {
                FeatureRow(Icons.Outlined.Folder, "Real project folders", "Keep chats and files together")
                FeatureRow(Icons.Outlined.AccountTree, "A complete local workspace", "Run tools and manage source control")
                FeatureRow(Icons.Outlined.Cloud, "Your choice of model", "Sign in or use your own provider keys")
            }
        }
        PcButton(
            text = "Get started",
            modifier = Modifier.heightIn(min = 56.dp),
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
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PcIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
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
                "Connect a model to start. Add a project folder and GitHub when you need them.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PcGroup {
                OptionRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Connect a model",
                    sub = if (modelReady) "Ready to use" else "Required for agent work",
                    complete = modelReady,
                    onClick = onConnectModels,
                )
                OptionRow(
                    icon = Icons.Outlined.Folder,
                    title = "Choose a project folder",
                    sub = if (projectReady) "Project folder ready" else "Recommended for project work",
                    complete = projectReady,
                    onClick = onCreateProject,
                )
                OptionRow(
                    icon = Icons.Outlined.AccountTree,
                    title = "Connect GitHub",
                    sub = if (githubReady) "Repository sync ready" else "Optional for repository sync",
                    complete = githubReady,
                    onClick = onConnectGitHub,
                )
            }
        }
        PcButton(
            text = "Start building",
            enabled = modelReady,
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 56.dp),
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
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp),
        ) {
            Text("Skip setup for now")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, sub: String) {
    val colors = MaterialTheme.colorScheme
    PcRow {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, sub: String, complete: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcRow(onClick = onClick) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(
            if (complete) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = if (complete) colors.primary else colors.tertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}
