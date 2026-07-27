# 001 — Make model setup a bottom-up modal with predictive back

- **Status**: DONE
- **Commit**: e4a4b5a
- **Severity**: HIGH
- **Category**: Purpose and frequency; missed opportunities; accessibility
- **Estimated scope**: 3 production files, 1-2 test files

## Problem

Model setup is a modal configuration task, but the root `NavHost` gives it the
same horizontal sibling-page transition as Settings:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt:345-364 — current
NavHost(
    navController = navController,
    startDestination = "chat",
    modifier = Modifier.fillMaxSize(),
    enterTransition = {
        slideInHorizontally(tween(240, easing = PhoneEasings.easeInOut)) { it }
    },
    exitTransition = { androidx.compose.animation.ExitTransition.None },
    popEnterTransition = { androidx.compose.animation.EnterTransition.None },
    popExitTransition = {
        slideOutHorizontally(tween(180, easing = PhoneEasings.easeInOut)) { it }
    },
)
```

`ModelSetupScreen` also installs a legacy `BackHandler` unconditionally:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/onboarding/ModelSetupScreen.kt:77-84 — current
val navigateBack = {
    if (selectedProviderId == null) onBack() else selectedProviderId = null
}
BackHandler(onBack = navigateBack)
```

At the root choice screen this intercepts Navigation Compose's built-in
predictive-back handler, so gesture progress cannot seek the route transition.

## Target

- Navigating to `model-setup` from chat enters vertically from the bottom.
- Popping `model-setup` dismisses vertically toward the bottom.
- Entrances/exits use the repo's exact strong `PhoneEasings.easeOut`.
- Enter duration: 260ms; exit duration: 200ms; fade 160ms/120ms.
- The root model-setup page does not install a custom back handler. Navigation
  Compose 2.9.8 owns root predictive back.
- A selected provider detail uses the existing `rememberPredictiveBackMotion`
  custom handler because it is nested state rather than a NavHost destination.
- A cancelled predictive gesture restores the detail page without changing
  `selectedProviderId`; a completed gesture returns to provider choice.

## Repo conventions to follow

- Strong easing tokens live in
  `app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt`.
- Existing nested predictive-back behavior is implemented by
  `rememberPredictiveBackMotion` and `predictiveBackTransform` in
  `app/src/main/kotlin/dev/phonecode/app/ui/components/Kit.kt`.
- Settings forward/pop timing is 260ms/220ms in
  `app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt`.
- Keep all motion on transforms and opacity.

## Steps

1. Add a small internal route-motion classification in `PhoneCodeApp.kt` so a
   unit test can prove `model-setup` is modal while settings/skills/MCP remain
   horizontal hierarchy destinations.
2. Give the `model-setup` composable destination explicit enter/pop-exit
   transitions:
   - `slideInVertically(tween(260, easing = PhoneEasings.easeOut)) { it }`
     plus `fadeIn(tween(160, easing = PhoneEasings.easeOut))`
   - `slideOutVertically(tween(200, easing = PhoneEasings.easeOut)) { it }`
     plus `fadeOut(tween(120, easing = PhoneEasings.easeOut))`
3. Keep settings, skills, and MCP horizontal. Change only entering surfaces to
   `easeOut`; use `easeInOut` only for content that remains visibly on screen.
4. In `ModelSetupScreen.kt`, replace unconditional `BackHandler` with
   `rememberPredictiveBackMotion(enabled = selectedProviderId != null)`.
   Apply `predictiveBackTransform` to the selected-provider detail only. When
   no provider is selected, let Navigation Compose own Back.
5. Ensure the visible Back button still calls `navigateBack`.
6. Add regression tests before implementation:
   - route classification reports modal for `model-setup`;
   - provider choice/detail still navigate by visible Back;
   - no regression in onboarding model setup return.

## Boundaries

- Do not change provider configuration behavior, copy, fields, or persistence.
- Do not change Settings/Skills/MCP UI.
- Do not add dependencies.
- Do not disable predictive back or replace it with a non-progress `BackHandler`.
- If Navigation Compose APIs differ from the current source, stop and report.

## Verification

- **Mechanical**:
  - `./gradlew --no-daemon :app:testDebugUnitTest --tests '*OnboardingFlowTest' --tests '*AppNavigationMotionTest'`
  - `./gradlew --no-daemon :app:assembleDebug`
- **Feel check**:
  - From an unconfigured chat, tap the model button. Confirm setup rises from
    the bottom rather than entering from the right.
  - Start predictive Back from provider choice. Confirm chat is progressively
    revealed and cancellation restores setup.
  - Open an API-key provider, begin Back, cancel, then complete. Confirm the
    nested page tracks gesture progress and returns to provider choice.
  - Trigger model setup from onboarding and confirm there is no double slide.
- **Done when**: modal direction, root predictive gesture, nested predictive
  gesture, visible Back, and automated tests all agree.
