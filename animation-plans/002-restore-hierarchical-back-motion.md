# 002 — Restore hierarchical forward and back motion

- **Status**: DONE
- **Baseline commit**: d069f0b
- **Severity**: HIGH
- **Categories**: Purpose and frequency; spatial model; cohesion
- **Files**: `PhoneCodeApp.kt`, `SettingsScreen.kt`

## Current defect

The root `NavHost` explicitly sets `exitTransition` and `popEnterTransition`
to `None`. A forward navigation therefore covers a static parent, and a back
navigation removes the child over a static destination. Nested settings also
returns `None/None` after a predictive-back commit. The resulting hierarchy
does not visibly communicate where the user came from or where Back returns.

Drawer-originated navigation starts closing the drawer asynchronously and
navigates immediately, so the drawer obscures the destination transition.

## Target

- Forward hierarchy: destination enters from the right while the parent moves
  left by one quarter of its width.
- Back hierarchy: parent enters from the left quarter while the child exits
  fully to the right.
- Entrances use `PhoneEasings.easeOut`; no route motion exceeds 240ms.
- Predictive Back remains owned by Navigation Compose at root destinations.
- A drawer destination begins navigating only after the drawer reaches its
  closed anchor.

## Boundaries

- Keep model setup's existing bottom-up modal transition.
- Do not add continuous animation or animate layout dimensions.
- Do not disable predictive Back.
- Do not change route ownership or back-stack behavior.

## Verification

- Run `UiPolishRegressionTest` and existing navigation motion tests.
- Record a forward Settings navigation, completed Back, and cancelled
  predictive Back at 1x animation scale.
- Confirm the drawer is no longer covering route motion.
