# 001 — Defer neural animation reads to render phases

- **Status**: TODO
- **Commit**: c742e38
- **Severity**: MEDIUM
- **Category**: Performance
- **Estimated scope**: 2 files, roughly 45 lines

## Problem

PhoneCode deliberately shares one slow neural phase while the model is running, but several consumers unwrap that `State<Float>` during composition. Every frame therefore invalidates a composable scope while streaming is already updating the transcript.

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:325-335 — current
if (state.isRunning) {
    val breath by rememberNeuralBreath(3000)
    Box(
        Modifier.fillMaxWidth().height(190.dp)
            .graphicsLayer { alpha = 0.4f + 0.5f * breath }
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(colors.onBackground.copy(alpha = 0.09f), androidx.compose.ui.graphics.Color.Transparent),
                ),
            ),
    )
}
```

The same composition-time pattern drives the thinking dot and running-tool icon:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:1175-1195 — current
val alpha = if (active) {
    val pulse by rememberNeuralBreath(1400)
    0.4f + pulse * 0.6f
} else 1f
// ...
Box(
    Modifier.size(8.dp).graphicsLayer { this.alpha = alpha; scaleX = if (open) 1.2f else 1f; scaleY = if (open) 1.2f else 1f }
        .clip(ShapePill).then(dotBackground),
)
```

The composer ring also reads the phase in composition and rebuilds its animated `Brush` there:

```kotlin
// app/src/main/kotlin/dev/phonecode/app/ui/theme/NeuralAccent.kt:92-99 — current
@Composable
fun Modifier.neuralRing(active: Boolean, shape: Shape, width: Dp = 1.dp): Modifier {
    if (!active) return this
    val ink = MaterialTheme.colorScheme.onBackground
    val shared = LocalNeuralPhase.current
    val local = if (shared == null) rememberNeuralPhase() else null
    val phase by requireNotNull(shared ?: local)
    return this.border(width, neuralSweepBrush(phase, ink.copy(alpha = 0.7f)), shape)
}
```

These effects run continuously for the full model turn, not for a one-off 150–250 ms transition.

## Target

Keep the exact visual treatment and existing durations, but read animated state from `graphicsLayer` or draw callbacks so Compose invalidates only the affected layer/draw node.

```kotlin
// target ambient mist
val breath = rememberNeuralBreath(3000)
Box(
    Modifier.fillMaxWidth().height(190.dp)
        .graphicsLayer { alpha = 0.4f + 0.5f * breath.value }
        .background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(colors.onBackground.copy(alpha = 0.09f), androidx.compose.ui.graphics.Color.Transparent),
            ),
        ),
)
```

```kotlin
// target neural ring
@Composable
fun Modifier.neuralRing(active: Boolean, shape: Shape, width: Dp = 1.dp): Modifier {
    if (!active) return this
    val ink = MaterialTheme.colorScheme.onBackground
    val shared = LocalNeuralPhase.current
    val local = if (shared == null) rememberNeuralPhase() else null
    val phase = requireNotNull(shared ?: local)
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(width.toPx())
        onDrawWithContent {
            drawContent()
            drawOutline(
                outline = outline,
                brush = neuralSweepBrush(phase.value, ink.copy(alpha = 0.7f)),
                style = stroke,
            )
        }
    }
}
```

No duration, alpha range, color, shimmer extent, or pulse cadence changes.

## Repo conventions to follow

- Shared animation clocks stay in `app/src/main/kotlin/dev/phonecode/app/ui/theme/NeuralAccent.kt`; do not introduce another infinite transition.
- `app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:714-717` already reads `Animatable.value` inside `graphicsLayer`, which is the pattern to imitate:

```kotlin
return graphicsLayer {
    translationY = offsetY.value
    this.alpha = alpha.value
}
```

- Preserve `ValueAnimator.areAnimatorsEnabled()` checks in `NeuralAccent.kt:50` and `NeuralAccent.kt:65`.

## Steps

1. In `app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt:325-335`, keep `rememberNeuralBreath(3000)` as a `State<Float>` and read `.value` only inside `graphicsLayer`.
2. In `ThinkingDot` at `ChatScreen.kt:1175-1195`, replace the composition-time `alpha` float with `val pulse = if (active) rememberNeuralBreath(1400) else null`. Compute `alpha` inside the existing `graphicsLayer` from `pulse?.value`; preserve the current `0.4f + pulse * 0.6f` range and the existing open-state scale.
3. In `ToolActivityView` at `ChatScreen.kt:1323-1353`, retain the pulse `State<Float>` instead of deriving `iconAlpha` during composition. Keep the icon tint opaque and apply `0.45f + pulse.value * 0.55f` through an icon `graphicsLayer`.
4. In `app/src/main/kotlin/dev/phonecode/app/ui/theme/NeuralAccent.kt:92-99`, replace the animated `.border(...)` construction with the `drawWithCache` target above. Add imports for `drawWithCache`, `Stroke`, and `drawOutline`; remove `androidx.compose.foundation.border` and `androidx.compose.runtime.getValue` only if they are unused after the edit.
5. Leave the animated text brush at `ChatScreen.kt:895-904` unchanged. Text brush replacement is outside this plan; this plan targets the effects that can be moved cleanly to layer/draw invalidation without changing typography.

## Boundaries

- Do NOT change neural colors, alpha ranges, durations, gradient stops, or active-state gating.
- Do NOT add a second animation clock or dependency.
- Do NOT alter transcript streaming, markdown fading, haptics, or model state.
- Do NOT replace the gradient text treatment at `ChatScreen.kt:895-904`.
- If `drawOutline` cannot render `ShapeComposer` identically on the supported Compose BOM, STOP and report the mismatch instead of approximating the border.
- If a step doesn't match the code you find (drift since commit `c742e38`), STOP and report instead of improvising.

## Verification

- **Mechanical**: run `./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests "dev.phonecode.app.ui.UiSmokeTest"`; both tasks must pass.
- **Feel check**: on a real device or emulator, start a model turn lasting at least 30 seconds and confirm:
  - the ambient mist, composer ring, thinking dot, and running-tool icon retain their current cadence and brightness;
  - the composer ring remains a crisp 1 dp outline with no clipped corners;
  - turning Android animations off freezes the neural effects at the existing midpoint treatment;
  - in Layout Inspector with recomposition counts enabled, the ambient mist, thinking dot, tool icon, and ring animate without causing their surrounding chat scopes to recompose every frame.
- **Done when**: all four visual effects match the pre-change appearance, and all deferrable neural `State<Float>` reads occur inside layer/draw callbacks rather than composition.
