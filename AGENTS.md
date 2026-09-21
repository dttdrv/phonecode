# PhoneCode

## Product direction

- Work toward PhoneCode 1.0 and Google Play readiness. Do not mark it ready while provider, runtime, or release checks remain unresolved.
- The intended provider set is OpenCode Go, OpenCode Zen, ChatGPT with a menu sign-in option, Anthropic, and OpenRouter.
- Prefer an iOS-like visual design. ChatGPT mobile is the primary reference; Gemini Neural Expressive is a secondary reference. Preserve Android back, keyboard, accessibility, and permission behavior.
- Preserve progressive scroll-edge blur behind the header and composer. Do not replace it with solid toolbar bands.
- Review typography, spacing, button shapes, labels, menus, dialogs, and error states individually. A global theme change does not complete the visual work.

## Working and verification

- Preserve existing work and unrelated files. Check the current diff before editing.
- Reuse the existing Compose components before introducing another component or dependency.
- Inspect rendered screens in the emulator after UI changes, including light/dark mode and keyboard states. Check affected nested screens and long content, not only landing pages.
- Roborazzi tests record screenshots by default. Passing those tests is not a visual comparison; inspect the resulting images.
- Test provider changes through the actual native request path. Serialization tests alone do not prove the headers or payload reach the server. Use synthetic credentials and local fixtures where possible.
- Keep the native library and its source lock consistent. Never bypass artifact verification to make a build pass.
- Run checks appropriate to the change. Do not add tests that merely search source text for a styling choice.
- Use Parallel Search for web research and Mobbin for visual references when available. Distinguish screenshot capture dates from inspection dates.
- Do not push, publish, upload a store build, or change release state without explicit authorization.
