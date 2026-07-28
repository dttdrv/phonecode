# 004 — Protect system chrome while preserving content-under-header depth

- **Status**: DONE
- **Baseline commit**: d069f0b
- **Severity**: HIGH
- **Categories**: Polish; spatial model; cohesion
- **Files**: `SettingsScreen.kt`, `PhoneCodeApp.kt`,
  `ContentOverscroll.kt`

## Current defect

The shared page and drawer apply `statusBarsPadding()` before the header
background. That leaves the status-bar strip unpainted, so scrolling content
shows behind clock and notification icons while the visible header disappears
at the system edge. Skills delegates overscroll entirely to the platform
default, so the expected stretch is not deterministic across supported
versions.

## Target

- The header surface owns `statusInset + navBarHeight`.
- Header content is padded below the status inset, while scrolling content can
  continue behind the complete painted chrome.
- The drawer paints/frosts the status-bar strip as part of the header.
- Retain Android's native stretch where available and verify it on the target
  API; add product-controlled stretch only if the supported platform does not
  expose it.

## Boundaries

- Keep edge-to-edge window configuration.
- Do not add a second scroll container or animate layout dimensions.
- Do not obscure system icons or remove safe-drawing bottom padding.

## Verification

- Scroll Skills until a chip reaches the top and confirm no content is visible
  behind status icons.
- Repeat for drawer search/header.
- Pull past the top and bottom of Skills on API 34 and verify native stretch
  and rebound.
