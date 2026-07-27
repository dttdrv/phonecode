# PhoneCode animation plans

Audit baseline: commit `c742e38` on branch `temp/play-release-hardening`.

The source audit covered all eight categories in the animation playbook across the Compose UI, with focused inspection of `Motion.kt`, `ChatScreen.kt`, `OnboardingScreen.kt`, `Kit.kt`, and `MorphingMenu.kt`. Source code was not modified by the audit.

| # | Plan | Severity | Status |
| --- | --- | --- | --- |
| 001 | [Defer neural animation reads to render phases](001-defer-neural-animation-reads.md) | MEDIUM | TODO |
| 002 | [Isolate morphing-menu progress from composition](002-isolate-morphing-menu-progress.md) | MEDIUM | TODO |
| 003 | [Use semantic curves for each motion role](003-use-semantic-motion-curves.md) | MEDIUM | TODO |

## Recommended execution order

1. **001** first: it removes continuous composition work during model turns and does not overlap the other plans.
2. **002** second: it narrows menu animation invalidation while preserving the current easing identifier.
3. **003** last: it renames and redistributes easing tokens, including the `MorphingMenu` callsite touched by 002.

## Dependencies

- 001 has no dependency.
- 002 has no dependency.
- 003 has no dependency, but should run after 002 to avoid trivial easing-name churn in `MorphingMenu.kt`.

Each plan is stamped to commit `c742e38`. If source code has drifted, the executor must stop and report the mismatch rather than infer a new design.
