# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Misul Agent serves people who need a capable coding and research agent when their phone is the computer available to them. They work through a conversation, inspect what the agent does, and retain control over local projects, credentials, files, tools, and consequential actions.

## Product Purpose

Misul Agent brings the native Misul runtime to Android. It gives the user one transcript-first workspace for directing an agent that works on local projects and uses the phone's development environment. Success means the user can open the current project, issue an instruction immediately, follow real progress, approve sensitive actions, and recover the work after interruption.

## Positioning

The product follows the interaction model of a mobile coding agent, but the working computer is the phone instead of a cloud virtual machine. The Android application is a presentation and platform adapter over the same native Misul runtime used by the Misul CLI and desktop application. It is not a second agent implementation.

## Operating Context

The primary surface is a streaming conversation. Projects and chats are reached through one-handed navigation. Tool activity, approvals, errors, recovery, context use, and completion evidence appear in the transcript. Android supplies secure credential storage, foreground execution, notifications, network transport, selected phone-folder access, and the local command-runtime boundary.

## Capabilities and Constraints

- The native Zig Misul runtime is the sole owner of agent execution, sessions, prompts, tools, permissions, provider semantics, compaction, and durable agent events.
- The Android application uses Jetpack Compose and communicates with one persistent packaged `misul rpc` process. It does not start one CLI process per turn.
- Kotlin owns Android platform integration only. A host bridge supplies secure credentials, HTTP transport, foreground lifecycle, notifications, Storage Access Framework access, and command execution through the configured private runtime.
- The product does not require remote execution. The current PRoot environment remains a debug prototype. The Google Play command-runtime path remains gated on the isolated QEMU architecture and its release evidence.
- Initial native runtime packaging supports `arm64-v8a`. Other Android application binary interfaces require separate build, compatibility, size, and runtime evidence.

## Brand Commitments

The product name is Misul Agent. Preserve the canonical Misul harmonic-arcs mark and the exact Misul.org cobalt accent. Preserve PhoneCode's strongest mobile interaction conventions while moving the product closer to Codex on mobile. The application must remain a conversation, not a dashboard. Progressive blur is a bounded scroll-edge material used where content passes behind fixed chrome, not a box treatment or a general background effect.

## Evidence on Hand

- `DESIGN.md` records the accepted Misul mobile visual system.
- `app/screenshots/` contains current light and dark conversation, drawer, settings, approval, onboarding, and failure-state captures.
- `/Users/dttdrv/Projects/Misul-Terminal/.dttdrv/project.md` defines the native runtime ownership and production contract.
- `/Users/dttdrv/Projects/Misul-Terminal/MisulAgent/` contains the current native Zig runtime and JSON-RPC adapter.
- Current PhoneCode startup measurements are emulator-local. No current Android measurement yet proves the native Misul runtime's startup, memory, frame-time, battery, or thermal performance.

## Product Principles

1. One runtime owns the truth.
2. Conversation is the front door.
3. Local actions remain inspectable and permission-governed.
4. Performance is measured on the production path.
5. Remove duplicate systems after verified parity.

## Accessibility & Inclusion

Keep 48 dp interaction targets, scalable text, semantic labels, screen-reader announcements, visible focus, sufficient contrast, reduced-motion behavior, predictive back, and Android safe-area behavior. Status must never depend on color alone.
