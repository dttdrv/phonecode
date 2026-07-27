package dev.phonecode.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcField
import dev.phonecode.app.ui.components.PcGroup
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.PcRow
import dev.phonecode.app.ui.components.PcSectionLabel
import dev.phonecode.app.ui.components.predictiveBackTransform
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.provider.preset.ProviderPreset

@Composable
fun ModelSetupScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onConfigured: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val providers = remember(state.models) {
        vm.allProviders().filter { it.id != "codex" }
    }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var predictiveCommit by remember { mutableStateOf(false) }
    val navigateBack = {
        if (selectedProviderId == null) onBack() else selectedProviderId = null
    }
    val detailBackMotion = rememberPredictiveBackMotion(enabled = selectedProviderId != null) {
        predictiveCommit = true
        selectedProviderId = null
    }
    LaunchedEffect(selectedProviderId) {
        predictiveCommit = false
    }
    val pageTransition = updateTransition(
        targetState = selectedProviderId,
        label = "modelSetupPage",
    )

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        if (detailBackMotion.active) {
            Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                ProviderChoice(
                    vm = vm,
                    providers = providers,
                    codexOAuthAvailable = state.codexOAuthAvailable,
                    codexConnected = state.codexConnected,
                    errorMessage = state.error,
                    onDismissError = {},
                    onBack = {},
                    onSelectProvider = {},
                    onConfigured = {},
                )
            }
        }
        pageTransition.AnimatedContent(
            transitionSpec = {
                if (predictiveCommit) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState != null
                    (slideInHorizontally(tween(220, easing = PhoneEasings.easeInOut)) {
                        if (forward) it / 4 else -it / 4
                    } + fadeIn(tween(160, easing = PhoneEasings.easeOut))) togetherWith
                        (slideOutHorizontally(tween(180, easing = PhoneEasings.easeInOut)) {
                            if (forward) -it / 4 else it / 4
                        } + fadeOut(tween(120, easing = PhoneEasings.easeOut)))
                }
            },
            contentKey = { it },
        ) { providerId ->
            val provider = providers.firstOrNull { it.id == providerId }
            if (provider == null) {
                ProviderChoice(
                    vm = vm,
                    providers = providers,
                    codexOAuthAvailable = state.codexOAuthAvailable,
                    codexConnected = state.codexConnected,
                    errorMessage = state.error,
                    onDismissError = vm::clearError,
                    onBack = navigateBack,
                    onSelectProvider = { selectedProviderId = it },
                    onConfigured = onConfigured,
                )
            } else {
                Box(Modifier.fillMaxSize().predictiveBackTransform(detailBackMotion)) {
                    ApiKeySetup(
                        vm = vm,
                        provider = provider,
                        globalError = state.error,
                        onDismissError = vm::clearError,
                        onBack = navigateBack,
                        onConfigured = onConfigured,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderChoice(
    vm: ChatViewModel,
    providers: List<ProviderPreset>,
    codexOAuthAvailable: Boolean,
    codexConnected: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onConfigured: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var showAllProviders by rememberSaveable { mutableStateOf(false) }
    val recommendedIds = remember { setOf("openai", "anthropic", "google") }
    val recommendedProviders = providers.filter { it.id in recommendedIds || vm.keyFor(it.id).isNotBlank() }
    val otherProviders = providers.filterNot { it in recommendedProviders }
    SetupPage(
        title = "Set up a model",
        onBack = onBack,
        footer = if (codexConnected) {
            {
                PcButton(
                    text = "Continue with ChatGPT",
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    if (vm.activateProvider("codex")) onConfigured()
                }
            }
        } else {
            null
        },
    ) {
        Text(
            "Choose how to connect",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Keys stay encrypted on this device. Prompts, attachments, and tool results go directly to the provider you choose.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        errorMessage?.let {
            Spacer(Modifier.height(Spacing.m))
            SetupError(it, onDismissError)
        }

        if (codexOAuthAvailable) {
            PcSectionLabel("ChatGPT")
            if (codexConnected) {
                PcGroup {
                    PcRow {
                        Icon(Icons.Outlined.Cloud, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ChatGPT", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                            Text("Configured", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.Check, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                PcButton("Sign in with ChatGPT") {
                    vm.startCodexSignIn()?.let { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            vm.surfaceError("Could not open the sign-in page.")
                        }
                    }
                }
            }
        }

        PcSectionLabel("Recommended providers")
        Column(
            Modifier.fillMaxWidth().animateContentSize(animationSpec = PhoneSprings.standardSpec()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            ProviderGroup(
                providers = recommendedProviders,
                vm = vm,
                onSelectProvider = onSelectProvider,
            )
            if (otherProviders.isNotEmpty()) {
                PcButton(
                    text = if (showAllProviders) "Fewer providers" else "More providers",
                    filled = false,
                ) {
                    showAllProviders = !showAllProviders
                }
            }
            if (showAllProviders) {
                ProviderGroup(
                    providers = otherProviders,
                    vm = vm,
                    onSelectProvider = onSelectProvider,
                )
            }
        }
    }
}

@Composable
private fun ProviderGroup(
    providers: List<ProviderPreset>,
    vm: ChatViewModel,
    onSelectProvider: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    PcGroup {
        providers.forEach { provider ->
            val configured = vm.keyFor(provider.id).isNotBlank()
            PcRow(onClick = { onSelectProvider(provider.id) }) {
                Icon(Icons.Outlined.Cloud, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                    Text(
                        if (configured) "Configured" else "Add an API key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(
                    if (configured) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = if (configured) colors.primary else colors.tertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ApiKeySetup(
    vm: ChatViewModel,
    provider: ProviderPreset,
    globalError: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onConfigured: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hasStoredKey = remember(provider.id) { vm.keyFor(provider.id).isNotBlank() }
    var key by rememberSaveable(provider.id) { mutableStateOf("") }
    var error by rememberSaveable(provider.id) { mutableStateOf<String?>(null) }
    val secureStorageUnavailable = vm.secureStorageUnavailable()
    val canContinue = !secureStorageUnavailable && (key.isNotBlank() || hasStoredKey)

    SetupPage(
        title = provider.displayName,
        onBack = onBack,
        footer = {
            PcButton(
                text = if (key.isBlank() && hasStoredKey) "Use configured provider" else "Save and continue",
                enabled = canContinue,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                val configured = if (key.isBlank()) {
                    vm.activateProvider(provider.id)
                } else {
                    vm.configureProviderKey(provider.id, key)
                }
                if (configured) {
                    onConfigured()
                } else {
                    val requestedKeySaved = key.isBlank() ||
                        vm.keyFor(provider.id) == key.trim()
                    error = providerSetupFailureMessage(requestedKeySaved)
                }
            }
        },
    ) {
        Text(
            "Connect ${provider.displayName}",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasStoredKey) {
                "A key is already configured. Continue with it, or enter a replacement."
            } else {
                "Enter your API key. It is stored in Android secure storage and is never included in exports."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        globalError?.let {
            Spacer(Modifier.height(Spacing.m))
            SetupError(it, onDismissError)
        }
        Spacer(Modifier.height(Spacing.l))
        PcField(
            value = key,
            onValueChange = {
                key = it
                error = null
            },
            placeholder = if (hasStoredKey) "New API key (optional)" else "API key",
            password = true,
            contentDescription = "${provider.displayName} API key",
            label = "API key",
        )
        if (secureStorageUnavailable) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                "Secure storage is unavailable on this device, so PhoneCode cannot save this key.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }
        error?.let {
            Spacer(Modifier.height(Spacing.s))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                modifier = Modifier.semantics {
                    error(it)
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
}

internal fun providerSetupFailureMessage(keySaved: Boolean): String =
    if (keySaved) {
        "API key saved, but PhoneCode could not activate an available model for this provider."
    } else {
        "PhoneCode could not save this API key in secure storage."
    }

@Composable
private fun SetupError(message: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(colors.errorContainer, MaterialTheme.shapes.medium)
            .semantics {
                error(message)
                liveRegion = LiveRegionMode.Polite
            }
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Dismiss", color = colors.onErrorContainer)
        }
    }
}

@Composable
private fun SetupPage(
    title: String,
    onBack: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
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
            PcIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 52.dp),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        ) {
            content()
        }
        footer?.let {
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
                it()
            }
        }
    }
}
