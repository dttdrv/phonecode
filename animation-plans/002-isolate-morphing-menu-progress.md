# 002 — Isolate morphing-menu progress from composition

- **Status**: TODO
- **Commit**: c742e38
- **Severity**: MEDIUM
- **Category**: Performance
- **Estimated scope**: 1 file, roughly 20 lines

## Problem

`MorphingMenu` is used by context usage, project options, and chat options. Its transition value is unwrapped during composition, so every frame of the 200 ms open and 150 ms close transition invalidates the composable scope. The draw phase also allocates a new `Path` every frame.

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/components/MorphingMenu.kt:74-82 — current
val transition = rememberTransition(state, label = "morphingMenu")
val progress by transition.animateFloat(
    transitionSpec = {
        if (targetState) tween(200, easing = PhoneEasings.iOSStandard)
        else tween(150, easing = PhoneEasings.iOSStandard)
    },
    label = "menuProgress",
) { if (it) 1f else 0f }
val contentProgress = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
```

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/components/MorphingMenu.kt:89-108 — current
modifier.drawWithContent {
    val width = anchorPixels + (size.width - anchorPixels) * progress
    val height = anchorPixels + (size.height - anchorPixels) * progress
    // ...
    val clip = Path().apply {
        addRoundRect(RoundRect(left, top, left + width, top + height, radius, radius))
    }
    clipPath(clip) { this@drawWithContent.drawContent() }
}
```

This is short-lived, but it occurs on top of a busy chat/drawer surface and is avoidable without changing the morph.

## Target

Retain the transition as `State<Float>` and read it only inside `drawWithContent` and `graphicsLayer`. Reuse one remembered `Path`.

```kotlin
// target
val transition = rememberTransition(state, label = "morphingMenu")
val progress = transition.animateFloat(
    transitionSpec = {
        if (targetState) tween(200, easing = PhoneEasings.iOSStandard)
        else tween(150, easing = PhoneEasings.iOSStandard)
    },
    label = "menuProgress",
) { if (it) 1f else 0f }
val clip = remember { Path() }
```

```kotlin
// target draw/layer reads
modifier.drawWithContent {
    val value = progress.value
    val width = anchorPixels + (size.width - anchorPixels) * value
    val height = anchorPixels + (size.height - anchorPixels) * value
    val left = if (alignEnd) size.width - width else 0f
    val top = if (above) size.height - height else 0f
    val radius = anchorPixels / 2f + (finalCorner - anchorPixels / 2f) * value
    // Preserve the existing shadow/background drawing.
    clip.reset()
    clip.addRoundRect(RoundRect(left, top, left + width, top + height, radius, radius))
    clipPath(clip) { this@drawWithContent.drawContent() }
    // Preserve the existing outline drawing.
}
```

```kotlin
Column(
    Modifier.graphicsLayer {
        val contentProgress = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
        alpha = contentProgress
        translationY = (1f - contentProgress) * if (above) 4.dp.toPx() else (-4).dp.toPx()
    },
    content = content,
)
```

## Repo conventions to follow

- Keep the existing `MutableTransitionState` and `rememberTransition`; they make open/close retargetable.
- `app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:714-717` and `app/src/main/kotlin/dev/phonecode/app/ui/components/Kit.kt:131-136` already defer animated state reads to `graphicsLayer`.
- Keep the custom anchor geometry: `alignEnd` controls the horizontal origin and `above` controls the vertical origin.

## Steps

1. In `app/src/main/kotlin/dev/phonecode/app/ui/components/MorphingMenu.kt:74-82`, remove the delegated `by` read and retain the result of `transition.animateFloat(...)` as `State<Float>`.
2. Add one `val clip = remember { Path() }` next to the transition state.
3. In `drawWithContent`, read `val value = progress.value` once and use `value` for width, height, and corner interpolation.
4. Replace `Path().apply { ... }` with `clip.reset()` followed by `clip.addRoundRect(...)`.
5. Move the `contentProgress` calculation into the `graphicsLayer` lambda and derive it from `progress.value` there.
6. Preserve all current geometry, four shadow passes, colors, outline width, durations, popup properties, and dismiss behavior.

## Boundaries

- Do NOT replace the morph with a generic fade or scale.
- Do NOT change the 200 ms enter or 150 ms exit duration in this plan.
- Do NOT change anchor placement, corner radii, shadows, clipping, focusability, or menu content.
- Do NOT add dependencies.
- If a step doesn't match the code you find (drift since commit `c742e38`), STOP and report instead of improvising.

## Verification

- **Mechanical**: run `./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests "dev.phonecode.app.ui.UiSmokeTest"`; both tasks must pass.
- **Feel check**: at normal speed and 10% animation speed:
  - open and close the context-usage menu in chat;
  - open project options and chat options in the drawer;
  - rapidly reverse each menu before it finishes and confirm it retargets from its current shape without flashing or restarting;
  - confirm each menu still grows from its trigger-side corner and content begins after 35% progress;
  - in Layout Inspector, confirm `MorphingMenu` is not recomposed on every transition frame.
- **Done when**: the rendered morph is visually identical, rapid reversals remain continuous, one `Path` is reused, and `progress.value` is read only in draw/layer phases.
