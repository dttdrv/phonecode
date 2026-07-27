# 003 — Use semantic curves for each motion role

- **Status**: TODO
- **Commit**: c742e38
- **Severity**: MEDIUM
- **Category**: Easing & duration / Cohesion & tokens
- **Estimated scope**: 6 files, roughly 45 lines

## Problem

The app has one cubic-bezier token, named for a platform style rather than a motion role, and applies it to screen movement, banners, fades, popovers, and action swaps. Other transitions silently use Compose's default tween easing.

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt:19-25 — current
object PhoneEasings {
    val iOSStandard = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

object PhoneTweens {
    val popEnter get() = tween<Float>(durationMillis = 220, easing = PhoneEasings.iOSStandard)
    val popExit get() = tween<Float>(durationMillis = 150, easing = PhoneEasings.iOSStandard)
}
```

Full-screen movement and pure fades consequently share a curve:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/onboarding/OnboardingScreen.kt:76-79 — current
(slideInHorizontally(tween(240, easing = PhoneEasings.iOSStandard), enterOffset) +
    fadeIn(tween(180, easing = PhoneEasings.iOSStandard))) togetherWith
    (slideOutHorizontally(tween(160, easing = PhoneEasings.iOSStandard), exitOffset) +
        fadeOut(tween(120, easing = PhoneEasings.iOSStandard)))
```

Several transitions omit the shared token entirely:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:2091-2097 — current
AnimatedContent(
    targetState = page,
    transitionSpec = {
        val direction = if (targetState > initialState) 1 else -1
        (slideInHorizontally(tween(220)) { direction * it / 4 } + fadeIn(tween(160))) togetherWith
            (slideOutHorizontally(tween(180)) { -direction * it / 4 } + fadeOut(tween(120)))
    },
    label = "questionPage",
)
```

All existing durations are within the under-300 ms UI budget; the issue is curve semantics and token cohesion, not speed.

## Target

Define exact semantic curves from the animation audit catalog and assign them by role:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt — target
object PhoneEasings {
    /** Enter/exit, fades, banners, and small UI feedback. */
    val easeOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** Elements moving or morphing while remaining on screen. */
    val easeInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** Reserved for drawer-like movement; the current drawer itself remains spring-driven. */
    val drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

object PhoneTweens {
    val popEnter get() = tween<Float>(durationMillis = 220, easing = PhoneEasings.easeOut)
    val popExit get() = tween<Float>(durationMillis = 150, easing = PhoneEasings.easeOut)
}
```

Use `easeInOut` for bidirectional screen/page movement and the morphing menu. Use `easeOut` for elements entering or exiting, pure fades, banners, and action swaps. Preserve `LinearEasing` for constant shimmer/stream-tail progress.

## Repo conventions to follow

- Shared curves and spring specs live only in `app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt`.
- Preserve the crisp, critically damped `PhoneSprings` object in `Motion.kt:8-17`.
- Preserve all current durations and offsets. This plan changes curve selection only.
- `NeuralAccent.kt:54,69` correctly uses `LinearEasing` for constant looping progress; `Markdown.kt:250` intentionally uses it for a constant 140 ms tail fade.

## Steps

1. In `app/src/main/kotlin/dev/phonecode/app/ui/theme/Motion.kt`, replace the ambiguous `iOSStandard` token with the exact `easeOut`, `easeInOut`, and `drawer` tokens shown above. Point `PhoneTweens.popEnter` and `popExit` to `easeOut`.
2. In `app/src/main/kotlin/dev/phonecode/app/ui/onboarding/OnboardingScreen.kt:70-80`, use `easeInOut` for both horizontal slides and `easeOut` for both fades. Keep 240/180 ms enter and 160/120 ms exit durations and the current directional offsets.
3. In `app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt:182-199`, use `easeInOut` for all four full-screen slide tweens. Keep predictive-commit behavior and z-index ordering unchanged.
4. In `app/src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt`, apply:
   - `easeInOut` to NavHost horizontal slides at lines 328 and 333;
   - `easeOut` to the onboarding exit slide/fade at lines 397-398;
   - `easeOut` to the title/search pure fades at lines 807-808 and 818-819;
   - `easeInOut` to the search field's `expandHorizontally`/`shrinkHorizontally` at lines 818-819.
5. In `app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt`, apply:
   - `easeOut` to pure fades at lines 350, 375-376, 557-558, and 957;
   - `easeOut` to the error/retry/notice banner slide and fade tweens at lines 576-577, 591-592, and 601-602;
   - `easeOut` to the direct composer action crossfade at lines 1647-1648;
   - `easeInOut` to question-page slides and `easeOut` to their fades at lines 2095-2096.
6. In `app/src/main/kotlin/dev/phonecode/app/ui/components/MorphingMenu.kt:75-81`, use `easeInOut` for both directions of the shell morph; retain 200 ms enter and 150 ms exit.
7. Run `rg -n 'tween\\(' app/src/main/kotlin/dev/phonecode/app/ui -g '*.kt'` and verify every non-linear tween either names `PhoneEasings.easeOut`/`easeInOut` or is deliberately documented. Leave the two `NeuralAccent.kt` linear loops and `Markdown.kt:250` linear tail fade unchanged.

## Boundaries

- Do NOT change any duration, spring stiffness, damping ratio, slide offset, scale value, or visibility threshold.
- Do NOT replace gesture-driven drawer or predictive-back springs with tweens.
- Do NOT alter `LinearEasing` in `NeuralAccent.kt` or `Markdown.kt`.
- Do NOT add animation dependencies.
- If a step doesn't match the code you find (drift since commit `c742e38`), STOP and report instead of improvising.

## Verification

- **Mechanical**: run `./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests "dev.phonecode.app.ui.OnboardingFlowTest" --tests "dev.phonecode.app.ui.UiSmokeTest"`; both tasks must pass.
- **Feel check**: set Android animation duration scale to 10x and confirm:
  - onboarding, settings, and question pages accelerate and decelerate smoothly while moving;
  - banners and fades react immediately without a slow start;
  - the menu shell morph is continuous in both directions and still completes in 200/150 ms at normal speed;
  - the drawer and predictive-back gesture retain their current spring behavior;
  - neural shimmer and streaming-tail progress remain linear with no visible loop seam.
- **Done when**: motion roles use the semantic shared tokens, no callsite uses `PhoneEasings.iOSStandard`, default tween easing is absent from the audited UI transitions, and all existing timing/geometry is unchanged.
