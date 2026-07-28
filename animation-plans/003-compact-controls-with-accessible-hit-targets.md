# 003 — Compact high-frequency controls without shrinking hit targets

- **Status**: DONE
- **Baseline commit**: d069f0b
- **Severity**: HIGH
- **Categories**: Choreography; cohesion; accessibility
- **Files**: `Spacing.kt`, `Shapes.kt`, `Kit.kt`, `ChatScreen.kt`,
  `SettingsScreen.kt`, `PhoneCodeApp.kt`

## Current defect

The shared 48dp accessibility target is also the painted size for round
buttons, full-width buttons, chat chrome, the model selector, and filters.
This makes frequently repeated controls visually heavy. The composer adds
12dp of vertical padding around a 48dp row, producing a 60dp resting capsule.
Settings rows use 2dp corners inside a rounded group, leaving most surfaces
visually square.

## Target

- Preserve a minimum 48dp interactive region.
- Paint standard controls at 40dp and compact selectors at 36dp inside that
  target.
- Paint the resting composer at 48dp with a 24dp fixed radius.
- Use the existing small theme shape for every settings row.
- Reduce Skills descriptions to one `bodySmall` line and paint filter pills
  at 36dp while retaining their 48dp hit regions.
- Keep press feedback transform-only and under 300ms.

## Boundaries

- Never reduce semantic or interactive hit targets below 48dp.
- Keep Dynamic Type/font scaling behavior and the two-row filter fallback.
- Do not change labels, provider behavior, or skill enablement behavior.

## Verification

- Run `UiPolishRegressionTest`, Compose smoke tests, and accessibility tests.
- Capture chat and Skills at 1080x2400 and compare against the 0.5.1 baseline.
- Verify every compact visible surface still exposes a 48dp tap region.
