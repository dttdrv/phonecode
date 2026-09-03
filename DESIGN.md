---
version: alpha
name: "Misul Agent — Mobile Field Instrument"
description: "A private, transcript-first engineering agent whose computer is the phone itself."
colors:
  canvas-dark: "#141414"
  surface-dark: "#1E1E1E"
  surface-input-dark: "#212121"
  surface-raised-dark: "#2A2A2A"
  surface-high-dark: "#333333"
  ink-dark: "#FFFFFF"
  text-secondary-dark: "#C6C6CE"
  text-muted-dark: "#9A9AA0"
  rule-dark: "#2A2A2A"
  rule-strong-dark: "#383838"
  canvas-light: "#FDFDFD"
  surface-light: "#F5F5F5"
  surface-raised-light: "#F8F8F8"
  surface-input-light: "#EFEFEF"
  surface-high-light: "#E9E9E9"
  ink-light: "#000000"
  text-secondary-light: "#5F6368"
  text-muted-light: "#6B6F73"
  rule-light: "#E5E5E5"
  rule-strong-light: "#D9D9D9"
  primary: "oklch(0.720 0.190 255)"
  primary-light: "oklch(0.500 0.220 255)"
  success-dark: "#30D158"
  success-light: "#248A3D"
  warning-dark: "#FFD60A"
  warning-light: "#A66F00"
  caution-dark: "#FF9F0A"
  caution-light: "#C2410C"
  error-dark: "#FF453A"
  error-light: "#B3261E"
  scrim: "rgba(0, 0, 0, 0.50)"
typography:
  brand-display:
    fontFamily: "Instrument Sans, system-ui, sans-serif"
    fontSize: "34px"
    fontWeight: 500
    lineHeight: 1.12
    letterSpacing: "-0.03em"
  headline-lg:
    fontFamily: "system-ui, sans-serif"
    fontSize: "28px"
    fontWeight: 700
    lineHeight: 1.21
    letterSpacing: "-0.03em"
  headline-md:
    fontFamily: "system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.27
    letterSpacing: "-0.03em"
  title-lg:
    fontFamily: "system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.29
    letterSpacing: "-0.025em"
  title-md:
    fontFamily: "system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "-0.02em"
  body-lg:
    fontFamily: "system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.47
    letterSpacing: "-0.015em"
  body-md:
    fontFamily: "system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "-0.01em"
  body-sm:
    fontFamily: "system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "0em"
  label-md:
    fontFamily: "system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: 1.23
    letterSpacing: "0em"
  label-sm:
    fontFamily: "system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.25
    letterSpacing: "0em"
  code:
    fontFamily: "JetBrains Mono, ui-monospace, monospace"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: "0em"
rounded:
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "20px"
  composer: "9999px"
  sheet: "28px"
  full: "9999px"
spacing:
  hairline: "1px"
  base: "4px"
  xs: "8px"
  sm: "12px"
  md: "16px"
  transcript-gutter: "18px"
  lg: "20px"
  xl: "24px"
  xxl: "32px"
  control-compact: "36px"
  control: "40px"
  touch-target: "48px"
  nav-height: "52px"
components:
  button-primary-dark:
    backgroundColor: "{colors.ink-dark}"
    textColor: "{colors.canvas-dark}"
    typography: "{typography.title-md}"
    rounded: "{rounded.lg}"
    padding: "12px 20px"
    height: "48px"
  button-primary-light:
    backgroundColor: "{colors.ink-light}"
    textColor: "{colors.canvas-light}"
    typography: "{typography.title-md}"
    rounded: "{rounded.lg}"
    padding: "12px 20px"
    height: "48px"
  button-quiet-dark:
    backgroundColor: "{colors.surface-input-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label-md}"
    rounded: "{rounded.full}"
    padding: "8px 12px"
    height: "40px"
  button-quiet-light:
    backgroundColor: "{colors.surface-input-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.label-md}"
    rounded: "{rounded.full}"
    padding: "8px 12px"
    height: "40px"
  composer-dark:
    backgroundColor: "{colors.surface-input-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-md}"
    rounded: "{rounded.full}"
    padding: "12px 12px"
    height: "56px"
  composer-light:
    backgroundColor: "{colors.surface-input-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.body-md}"
    rounded: "{rounded.full}"
    padding: "12px 12px"
    height: "56px"
  user-turn-dark:
    backgroundColor: "{colors.surface-input-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-md}"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
  user-turn-light:
    backgroundColor: "{colors.surface-input-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.body-md}"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
  tool-row-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.text-secondary-dark}"
    typography: "{typography.label-md}"
    rounded: "{rounded.sm}"
    padding: "10px 12px"
  tool-row-light:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.text-secondary-light}"
    typography: "{typography.label-md}"
    rounded: "{rounded.sm}"
    padding: "10px 12px"
  status-active-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.primary}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
    height: "36px"
  status-active-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.primary-light}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
    height: "36px"
  icon-control-dark:
    backgroundColor: "{colors.surface-high-dark}"
    textColor: "{colors.ink-dark}"
    rounded: "{rounded.full}"
    size: "40px"
  icon-control-light:
    backgroundColor: "{colors.surface-high-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.full}"
    size: "40px"
  metadata-dark:
    backgroundColor: "{colors.canvas-dark}"
    textColor: "{colors.text-muted-dark}"
    typography: "{typography.label-sm}"
  metadata-light:
    backgroundColor: "{colors.canvas-light}"
    textColor: "{colors.text-muted-light}"
    typography: "{typography.label-sm}"
  divider-dark:
    backgroundColor: "{colors.rule-dark}"
    height: "1px"
  divider-light:
    backgroundColor: "{colors.rule-light}"
    height: "1px"
  divider-strong-dark:
    backgroundColor: "{colors.rule-strong-dark}"
    height: "1px"
  divider-strong-light:
    backgroundColor: "{colors.rule-strong-light}"
    height: "1px"
  status-success-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-success-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-warning-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-warning-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-caution-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-caution-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-error-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-error-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.ink-light}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: "6px 10px"
  status-success-indicator-dark:
    backgroundColor: "{colors.success-dark}"
    rounded: "{rounded.full}"
    size: "8px"
  status-success-indicator-light:
    backgroundColor: "{colors.success-light}"
    rounded: "{rounded.full}"
    size: "8px"
  status-warning-indicator-dark:
    backgroundColor: "{colors.warning-dark}"
    rounded: "{rounded.full}"
    size: "8px"
  status-warning-indicator-light:
    backgroundColor: "{colors.warning-light}"
    rounded: "{rounded.full}"
    size: "8px"
  status-caution-indicator-dark:
    backgroundColor: "{colors.caution-dark}"
    rounded: "{rounded.full}"
    size: "8px"
  status-caution-indicator-light:
    backgroundColor: "{colors.caution-light}"
    rounded: "{rounded.full}"
    size: "8px"
  status-error-indicator-dark:
    backgroundColor: "{colors.error-dark}"
    rounded: "{rounded.full}"
    size: "8px"
  status-error-indicator-light:
    backgroundColor: "{colors.error-light}"
    rounded: "{rounded.full}"
    size: "8px"
  modal-scrim:
    backgroundColor: "{colors.scrim}"
    width: "100%"
    height: "100%"
---

# Design System: Misul Agent — Mobile Field Instrument

## Overview

**Creative North Star: “The Field Instrument.”**

Misul Agent is a private engineering workspace that happens to fit in a phone. Its functional analogy is ChatGPT Work with the computer moved from a cloud VM into the device: the conversation controls local projects, files, Git, a development runtime, skills, MCP servers, model access, and permission-reviewed tools. The product must feel like a precise instrument that is already awake, never like a dashboard describing what an agent might do.

The visual system joins two proven languages. Misul contributes the harmonic-arcs mark, editorial discipline, crisp hierarchy, luminous cobalt energy, and the principle of zero superficial decoration. PhoneCode contributes the native mobile transcript, one-handed drawer navigation, floating composer, inspectable tools, explicit approvals, restrained monochrome surfaces, and durable local-state feedback. The result is brandful at the edges and quiet at the center.

The conversation is always the primary surface. Projects, chats, Skills, MCP, providers, models, permissions, runtime management, and settings support that surface; they never compete with it as equal dashboard modules. The user should be able to open the app, understand the current workspace and agent state in one glance, and issue the next instruction without passing through a home screen.

### Experience principles

1. **Thoughtful engineering.** Every visible element must explain an action, state, relationship, or consequence.
2. **Local sovereignty.** Make local execution, selected folders, provider boundaries, permissions, and persisted state legible without turning privacy into marketing chrome.
3. **Conversation first.** Preserve one vertical path: session identity, transcript, active state, composer.
4. **Proof over theater.** Show tool activity, approvals, failures, recovery, and completion evidence. Never substitute an animation for state.
5. **Fast by restraint.** Frequent actions are immediate. Motion is reserved for spatial transitions, meaningful state changes, and rare identity moments.
6. **Progressive disclosure.** The timeline gives a compact answer first; tool arguments, raw output, reasoning, context accounting, and diagnostics expand on demand.
7. **Native behavior.** Respect Android safe areas, predictive back, system sheets, keyboard behavior, font scaling, platform overscroll, and accessibility services.

### Signature expression

The harmonic-arcs `m` is the persistent identity. Use the canonical Misul path rather than redrawing it:

```svg
<path d="M5 27 V15 a6 6 0 0 1 12 0 a6 6 0 0 1 12 0 V27 H22 V18 a2.5 2.5 0 0 0 -5 0 V27 H12 V15 a1 1 0 0 0 -2 0 V27 Z"/>
```

The luminous field is a rare environmental signature, not a chat background. It may appear behind onboarding, the model-download state, an empty first session, or a genuinely rare completion milestone. Once the user starts working, the field recedes to a static accent or disappears entirely. Disable live shader rendering under reduced motion, battery saver, thermal pressure, background execution, or while a turn is consuming meaningful compute.

### Key characteristics

- Soft-black or clean-white canvas; never a persistent blue-black app shell.
- Monochrome operational controls with cobalt reserved for agency, focus, and selected state.
- Native system typography for daily work; Instrument Sans only for Misul identity and rare editorial headings.
- Unboxed assistant prose, compact user bubbles, and tightly grouped tool activity.
- Tonal layering and fine rules instead of card grids and decorative shadows.
- One-handed navigation with no dashboard, bottom-tab maze, or detached project selector.
- Motion that is fast, interruptible, frequency-aware, and optional.

## Colors

The palette is neutral enough for long engineering sessions. Cobalt is energy passing through the instrument, not blue paint spread across the interface.

### Foundation

- **Soft Black** (`{colors.canvas-dark}`): the dark canvas. It avoids OLED smear while remaining visually black.
- **Clean White** (`{colors.canvas-light}`): the light canvas; slightly softened to avoid a sterile blue-white glare.
- **Dark Surface Steps** (`{colors.surface-dark}` through `{colors.surface-high-dark}`): hierarchy for tool rows, composer, sheets, and transient chrome.
- **Light Surface Steps** (`{colors.surface-light}` through `{colors.surface-high-light}`): the matching light hierarchy.
- **Primary Ink** (`{colors.ink-dark}` / `{colors.ink-light}`): primary text and high-emphasis monochrome actions.
- **Secondary Ink** (`{colors.text-secondary-dark}` / `{colors.text-secondary-light}`): supporting prose and values.
- **Muted Ink** (`{colors.text-muted-dark}` / `{colors.text-muted-light}`): timestamps, metadata, and unavailable context. Never use it for required instructions.

### Misul cobalt

- **Live Cobalt** (`{colors.primary}`): focus rings, running-agent indicators, selected semantic controls, active links, and the luminous field in dark mode.
- **Grounded Cobalt** (`{colors.primary-light}`): the corresponding light-mode accent.

**The Current Rule.** Cobalt means that Misul is selected, focused, or actively doing work. It must not fill general navigation, every icon, every button, or large permanent surfaces. A screen should normally have one cobalt focal region or less.

Primary actions remain high-contrast monochrome. This prevents the generic “blue SaaS button” look and makes cobalt activity states more meaningful.

### Semantic states

- **Success:** completion verified or connection established. Pair with a check icon and explicit text.
- **Warning:** approaching a limit or a reversible degraded state, such as context pressure or slower execution.
- **Caution:** thermal, storage, or memory pressure that may affect the active turn.
- **Error:** failed, denied, disconnected, or unrecoverable state. Red is not used for decoration.

Never communicate status by color alone. Every state also needs a label, icon, position, or change in action availability. Context usage may progress through neutral, warning, caution, and error, but its percentage remains readable.

### Contrast and themes

- Meet WCAG AA at minimum: 4.5:1 for normal text and 3:1 for large text and essential graphical controls.
- Follow the system theme by default. A user override may select light, dark, or system.
- Do not derive dark mode by mechanically inverting light mode. Preserve the named surface hierarchy and test code blocks, syntax colors, scrims, disabled controls, and the field independently.
- Never place body text directly over the animated field. Introduce a stable canvas or stop the field first.

## Typography

- **Brand font:** Instrument Sans with the system sans fallback.
- **Operational font:** the platform system sans (Roboto and device variants on Android).
- **Code font:** JetBrains Mono.

The brand and the instrument speak at different frequencies. Instrument Sans gives the Misul wordmark, onboarding thesis, and rare empty-state heading the editorial clarity of misul.org. The platform font makes chat, navigation, settings, permissions, and tools feel native and fast. JetBrains Mono marks literal machine material: source, commands, paths, hashes, model identifiers, tool arguments, token counts, and logs.

### Hierarchy

- **Brand display** (`{typography.brand-display}`): rare identity headings only. Sentence case; never all caps.
- **Large headline** (`{typography.headline-lg}`): onboarding and empty-state task framing.
- **Medium headline** (`{typography.headline-md}`): screen introductions and sheet titles.
- **Large title** (`{typography.title-lg}`): session title, primary list row, dialog title.
- **Medium title** (`{typography.title-md}`): buttons, compact navigation, model and mode controls.
- **Large body** (`{typography.body-lg}`): onboarding explanation and important permission consequences.
- **Body** (`{typography.body-md}`): conversation and settings. Assistant prose uses a comfortable line height and never justified text.
- **Small body** (`{typography.body-sm}`): supporting copy and secondary explanations.
- **Labels** (`{typography.label-md}` / `{typography.label-sm}`): status, metadata, short values, and compact control labels.
- **Code** (`{typography.code}`): literal technical content only.

### Typesetting rules

- Use at most three clear levels on one screen region: title, body, metadata.
- Keep assistant paragraphs to a readable measure. On phones this is naturally screen-bound; on tablets cap prose around 680px and keep it left-aligned.
- Support system font scaling without clipping, fixed-height text containers, or inaccessible truncation. Critical values may wrap.
- Use weight before color to establish hierarchy. Avoid indiscriminate semibold text.
- Use tabular figures for token counts, durations, context percentages, and other changing numeric data.
- Do not use uppercase section labels as a substitute for hierarchy. Short labels may use sentence case with medium weight.

## Layout

### Primary mobile anatomy

The default screen has one vertical working path:

```text
safe-area status region
floating session chrome
scrolling transcript
inline active / recovery state
floating composer
safe-area navigation region
```

The transcript fills the viewport behind the top and bottom chrome. At rest, content padding clears the controls; during user-driven scrolling, content may move beneath carefully bounded dissolve bands. The page itself remains a stable canvas.

### Session chrome

- **Leading:** one 48px target containing the harmonic mark or menu affordance. It opens the workspace drawer.
- **Center:** session title on the first line and compact model selection on the second. Truncate only after preserving a useful project/session identity.
- **Trailing:** context ring and, only when meaningful, a running/background status affordance.
- The project identity belongs to the session title and drawer hierarchy. Never float a detached folder selector above the conversation.

### Transcript geometry

- Use an 18px phone gutter, increasing to 24–32px on wider windows.
- Assistant turns occupy the readable column without a card background.
- User turns align to the trailing edge and use a compact tonal bubble with a sensible maximum width.
- Tool rows use 3–6px vertical rhythm when consecutive so they read as one activity sequence. Prose turns use at least 8px internal rhythm and 16–24px between conceptual groups.
- Long code, diffs, tables, and diagrams scroll or expand within bounded containers; they must not force the whole screen wider.
- Preserve the user’s scroll position. Auto-follow streaming output only while the user is already at the end; expose a return-to-latest affordance after they scroll away.

### Navigation architecture

There is one primary mode: the active conversation. Do not add bottom navigation merely to make the app look complete.

The leading-edge drawer contains:

1. Misul mark, search, and immediate “New chat.”
2. Projects with nested sessions, including Unsorted and Archived.
3. Compact destinations for Skills and MCP servers.
4. Settings at the stable bottom edge.

The drawer is 82% of compact phone width, capped near 400px on large phones and tablets. It overlays the conversation and supports drag, edge swipe, scrim tap, explicit close, and predictive back. A gesture never replaces the visible button.

Settings use a hierarchical list with native forward/back navigation. Providers, models, local runtime, phone folders, GitHub, permissions, tools, Skills, MCP, appearance, data, and About remain distinct pages rather than one endless configuration surface.

### Adaptive layout

- **Compact, under 600px:** overlay drawer; single transcript column; full-width sheets.
- **Medium, 600–839px:** wider transcript with capped readable measure; drawer may remain overlay unless the user pins it.
- **Expanded, 840px and above:** optional persistent project rail beside the conversation. The transcript remains dominant; do not convert the space into analytics panels.
- Foldables and landscape layouts preserve the composer near the active thumb region and keep approval actions visible without scrolling past the consequence text.
- All edges account for display cutouts, system bars, gesture navigation, and the IME exactly once.

### Spatial rhythm

The 4px base rhythm governs placement. Use 8px for close relationships, 12px for control interiors, 16–20px for normal groups, and 24–32px for section changes. A dense engineering surface can be information-rich without being cramped: decrease decoration before decreasing touch targets or reading space.

## Elevation & Depth

The system is flat by default. Depth comes first from tonal steps, then fine rules, and only last from shadow or blur.

- **Canvas:** no shadow.
- **Inline surfaces:** one tonal step from the canvas, optionally bounded by a 1px rule.
- **Floating controls:** a higher tonal step with a restrained ambient edge shadow only when overlapping content.
- **Drawer and modal sheets:** structural shadow plus scrim because they occupy a different interaction plane.
- **Top and bottom dissolve bands:** at most a 4px progressive backdrop blur with no tinted slab. Apply only where transcript content actually passes beneath chrome.
- **Frosted controls:** at most 14px backdrop blur with approximately 55% canvas tint, reserved for the floating composer and chrome when live content is visible behind them.

**The One Plane Rule.** A region may use tonal contrast, a rule, blur, or shadow to establish its edge, but rarely more than two together. Never stack a border, large shadow, gradient, and glass effect on the same component.

The luminous field is environmental depth, not component elevation. It never appears inside cards, tool rows, buttons, or approval sheets.

## Shapes

The form language is soft, precise, and functional. Rounded geometry should feel native to a handheld instrument, not inflated or toy-like.

- **8px:** code blocks, compact nested surfaces, and small technical containers.
- **12px:** tool rows, list groups, search fields, and compact menus.
- **16px:** standard content containers and user bubbles.
- **20px:** primary buttons and prominent grouped controls.
- **28px:** full-width sheets and rare large surfaces.
- **Full/pill:** the composer at every height, circular icon controls, status capsules, and compact selectors.

Use continuous-looking corners where the platform supports them. Hairline rules may define an edge without changing the silhouette. The harmonic-arcs mark is the only recurring decorative geometry; do not echo it as arbitrary waves across cards or dividers.

## Components

### App shell and workspace drawer

The shell should disappear during work. The menu control, centered session/model identity, and context state are individually legible but visually quiet. Opening the drawer clears keyboard focus and closes competing overlays.

The drawer groups projects and sessions by hierarchy, not by cards. Project disclosure rotates a small chevron; session rows show title and one useful preview or timestamp. Rename, pin, archive, delete, and project management live behind a contextual action sheet. Search covers project names, session titles, and preview text with immediate filtering and a clear empty result.

### Conversation turns

- **User:** compact trailing bubble using the input surface. Preserve attachments and exact submitted text. Long input may use full width.
- **Assistant:** unboxed, left-aligned prose. Markdown, lists, tables, citations, code, and diagrams receive their own semantic treatments rather than one giant bubble.
- **Reasoning:** collapsed by default into a quiet disclosure such as “Reasoning.” Expansion preserves selection and scroll position. Never present hidden reasoning as verified fact.
- **Streaming:** append text without repeatedly animating layout. Use a subtle caret or activity mark only while no complete text is available.
- **Actions:** copy, retry, report, and related actions appear after a completed assistant turn or on deliberate selection; they should not create a toolbar after every paragraph.

Sending a message has no entrance spectacle. Echo the user turn immediately, preserve the draft until submission is accepted, clear it only after durable handoff, and provide direct haptic feedback. If a turn is already active, make queuing explicit and editable.

### Composer

The composer is the primary control and remains reachable with one hand.

- Resting height: 56px; multiline growth rises upward to six lines before the field scrolls internally.
- Leading action: attachment or contextual add menu with a separate visible 48px circular target.
- Text field: plain-language placeholder tied to readiness, such as “Ask Misul” or “Connect a model to start.”
- The text surface is one true capsule, with a stable 48px trailing slot: send when ready, stop while running. Its size never animates as the icon changes.
- When a sendable draft can be queued during a running turn, queue receives one distinct external 48px action; idle states do not reserve that empty slot.
- Model selection belongs in session chrome, not inside the typing line.
- Voice, camera, or future tools appear only when implemented and permission-ready.
- Keyboard actions are immediate and unanimated. `Enter` behavior follows the user’s setting; multiline entry remains discoverable.

While the model is active, a quiet cobalt/monochrome hairline may travel around the composer. It must not pulse the entire surface, compete with text, or run when animations are disabled.

### Tool activity rail

Tools form a compact, chronological rail inside the transcript. Each row contains:

1. A semantic icon.
2. A past- or present-tense action label: “Reading `ChatScreen.kt`,” “Ran tests,” “Waiting for approval.”
3. State: queued, awaiting approval, running, done, failed, or stopped.
4. Optional duration or bounded result summary.
5. Disclosure for arguments, affected paths, stdout/stderr, and complete bounded output.

Running rows may use a small linear or rotating activity indicator. Completed rows settle instantly into a static check or neutral icon. Consecutive tools remain visually connected. Avoid a separate card for every read, search, or command.

Tool output must distinguish model-authored explanation from actual process output. Monospace text, labels, and containment communicate provenance. Large output is summarized with a clear “Show output” path; truncation states the limit and how to inspect more.

### Permission and approval sheets

Approval is an interruption with consequences, so it uses a native modal sheet or dialog rather than a tiny inline chip.

Every approval states:

- the proposed action in human language;
- the exact tool and target;
- what data leaves the phone, what files change, or what command runs;
- whether the effect is local, external, destructive, or difficult to undo;
- the narrow scope of any remembered permission.

Actions are ordered by safety and clarity: **Allow once**, a narrowly described persistent choice when supported, and **Deny**. Destructive actions use explicit verbs such as “Delete chat,” never “Continue.” Denial returns the conversation to an actionable state without discarding the user’s draft or losing the pending tool record.

### Local runtime and model states

The phone is the computer. Surface its limits honestly and locally:

- **Ready:** provider and runtime are available.
- **Model required:** explain the missing setup and offer the exact route.
- **Loading model/runtime:** show the named artifact, phase, bytes, and cancel/background choices.
- **Offline:** distinguish local work that remains available from provider actions that do not.
- **Low memory:** explain likely impact, preserve the session, and offer a smaller model or retry.
- **Thermal throttling:** state that execution may slow; do not present an invented ETA.
- **Low storage:** show required and available space before download or package installation.
- **Background:** show that the turn continues under the foreground service and how to return or stop it.
- **Interrupted:** identify Android termination, user stop, network loss, provider failure, or uncertain side effect separately.
- **Recovered:** show what was restored and flag any tool that started without a confirmed result. Never replay an uncertain side effect automatically.

Status lives near the action it affects. Persistent runtime health belongs in setup/settings; active-turn status belongs in the transcript and composer. Do not create a permanent telemetry dashboard.

### Loading and perceived speed

- **Under 100ms:** no indicator.
- **100ms–1s:** subtle local feedback such as pressed state or compact activity glyph.
- **1–10s:** named operation and visible cancel when cancellation is safe.
- **Over 10s:** phase, measurable progress where available, bytes or steps, background option, and recovery guidance.

Never show fake percentages or an endlessly optimistic ETA. Prefer determinate progress for downloads and installations, indeterminate activity for provider thinking, and event-based labels for tools. Keep the transcript interactive while independent work proceeds.

### Error and recovery

Errors answer three questions in one compact region: what failed, what that means now, and what the user can do next. Preserve the input, selected files, scroll position, and completed tool evidence.

Use exact actions—“Retry model request,” “Choose another model,” “Review permission,” “Free 1.4 GB,” “Reconnect MCP server”—rather than “Something went wrong.” Place recoverable errors inline. Use a page-level blocking state only when the current surface cannot function. Toasts acknowledge short-lived outcomes; they do not carry critical instructions.

### Onboarding and empty conversation

Onboarding is two focused steps, not a feature carousel:

1. **Identity and promise:** harmonic mark, “Build real projects from your phone,” and three evidence-based capabilities.
2. **Required setup:** connect a model. Phone folder and GitHub remain clearly optional until needed.

This is the strongest place for the Misul field and Instrument Sans. The field must remain behind stable text contrast and settle once the user interacts.

An empty configured conversation shows the mark, one concise invitation, and at most three text-first suggestions grounded in actual abilities, such as inspecting a linked project, fixing a test failure, or explaining a repository. Avoid a grid of glossy prompt cards. If no model is configured, the empty state becomes a direct setup path.

### Skills, MCP, tools, and settings

Skills and MCP are first-class capabilities in the drawer because they change what the agent can do. Their management screens are still settings-style lists:

- show enabled, unavailable, connecting, failed, and permission-limited states;
- explain whether scope is global or project-specific;
- expose source, configuration, and last meaningful error;
- provide test/reconnect controls where a real check exists;
- never imply a tool is available after its server disconnects.

The base tool catalog uses search and functional grouping, not an icon wall. Prefer verbs and scope: Files, Git, Runtime, Web, Memory, Tasks, Skills, MCP. Dangerous capabilities require clear standing-permission controls with reversible defaults.

### Motion and interaction grammar

Every animation must justify itself through feedback, spatial consistency, state communication, explanation, or prevention of a jarring change.

#### Frequency gate

- **Hundreds of times per day:** no animation. Typing, sending, streaming tokens, list highlight changes, tool log updates, and keyboard actions are immediate.
- **Tens of times per day:** minimal state transition. Session switching uses a short crossfade only when needed to prevent a flash.
- **Occasional:** standard motion for drawer, bottom sheet, disclosure, error insertion, and model selection.
- **Rare or first-time:** restrained delight for initial Misul identity, first successful local turn, or major setup completion.

#### Timing and easing

- Press feedback: 100–160ms, scale to 0.97 or 0.96, then return.
- Fades and compact feedback: 120–180ms.
- Popovers and compact sheets: 150–220ms.
- Hierarchical navigation: 170–240ms.
- Drawer and modal sheets: 220–300ms, or an interruptible critically damped spring.
- Default enter/exit curve: `cubic-bezier(0.23, 1, 0.32, 1)`.
- On-screen morph or movement: `cubic-bezier(0.77, 0, 0.175, 1)`.
- Drawer-like movement: `cubic-bezier(0.32, 0.72, 0, 1)`.
- Never use `ease-in` for UI entrance or exit.

Animate transforms and opacity wherever possible. Do not animate layout properties, blur-heavy full-screen layers, or `transition: all`. Prefer transitions or springs for interactions that users can reverse mid-flight. Keyframes are acceptable only for self-contained, non-retargeting activity such as a bounded spinner.

Popovers and menus originate from their trigger. Sheets originate at the bottom edge. Center dialogs scale subtly from at least 0.95 with opacity; never from zero. Staggering is limited to rare onboarding or setup reveals, 30–60ms apart, and must never delay interaction.

#### Gesture physics

Drawer and sheet gestures track the finger directly, capture the pointer, reject competing multi-touch input, and use bounded rubber-banding beyond limits. Dismiss based on both distance and velocity; a practical velocity signal is approximately `abs(distance) / elapsedMilliseconds > 0.11`, tuned with device testing. Predictive back visually follows system progress and always lands in the same state as the explicit back action.

#### Reduced motion

Reduced motion means gentler, not broken. Remove translation, scale, parallax, shader movement, shimmer travel, and stagger. Retain short opacity and color transitions where they clarify state. The UI must remain fully understandable with every decorative animation disabled.

### Haptics and sound

Use platform haptics for direct confirmation, completion, warning, and rejection only. Do not vibrate for streaming tokens, every tool call, or passive state updates. Sound is off by default; notifications follow system policy and the user’s explicit preference.

### Accessibility

- Minimum interactive target is 48px even when the painted control is 36–40px.
- Every icon-only action has a concise accessibility label.
- Disclosures expose expanded/collapsed state; running content uses polite live regions without announcing every token.
- Focus order follows visual order. Opening a modal moves focus inside; dismissal restores it to the trigger.
- Compose semantics must identify button, switch, radio, checkbox, heading, error, and progress roles accurately.
- Do not trap essential actions behind swipe, long press, color, hover, or animation.
- Test large text, TalkBack, switch access, reduced motion, high contrast, landscape, and keyboard input.

## Do's and Don'ts

### Do

- **Do** make the transcript the first and dominant screen after setup.
- **Do** preserve the current PhoneCode strengths: floating composer, project/session drawer, inline tools, explicit approvals, context visibility, native sheets, and crash-safe state.
- **Do** use the canonical Misul harmonic-arcs mark and editorial identity.
- **Do** reserve cobalt for focus, selection, and active agency so it keeps meaning.
- **Do** use the luminous field for rare identity moments with static and low-power fallbacks.
- **Do** show the difference between model text, local tool output, external service activity, and verified completion.
- **Do** keep user input, attachments, scroll position, and completed evidence through errors and interruption.
- **Do** expose permissions immediately before the affected tool runs, with the exact scope and consequence.
- **Do** make frequent interactions immediate and keep purposeful UI motion below 300ms.
- **Do** provide visible alternatives to every gesture and support predictive back.
- **Do** measure speed, frame time, memory, battery, and thermal behavior before making performance claims.
- **Do** validate the design on the running app at compact, medium, large-text, light, dark, reduced-motion, offline, low-memory, and keyboard-visible states.

### Don't

- **Don't** build a dashboard, command-center home, analytics overview, or grid of agent-status cards.
- **Don't** turn the entire interface navy or blue-black. The canvas stays neutral; cobalt is a signal.
- **Don't** put a permanent shader, animated gradient, orb, glass sculpture, or field behind the working transcript.
- **Don't** put assistant responses inside decorative cards or wrap every tool event in an isolated container.
- **Don't** detach project selection from the session hierarchy or duplicate it in the composer.
- **Don't** use gradients, glow, blur, borders, and shadows together to simulate sophistication.
- **Don't** animate typing, send, token streaming, keyboard navigation, every list insertion, or other high-frequency work.
- **Don't** use `ease-in`, `transition: all`, scale from zero, fake progress, or non-interruptible motion for reversible interaction.
- **Don't** remove all state transitions under reduced motion; preserve short opacity and color feedback.
- **Don't** hide destructive consequences behind vague labels such as “Proceed” or “Continue.”
- **Don't** use color alone for context pressure, success, warnings, failures, or running state.
- **Don't** claim work is complete because a process exists, a model responded, or a tool started. Show the actual result and any remaining uncertainty.
- **Don't** add a feature, destination, control, or status indicator before there is implemented behavior for it.
