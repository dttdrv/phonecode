# PhoneCode

A native Android port of the OpenCode coding agent, built to run entirely on the phone.

PhoneCode runs the agent loop on your device. It reads, writes, and edits files in per-project
workspaces, runs git natively, searches the web, and talks to whichever model provider you choose.
There is no backend, no telemetry, and no account. Your API keys live in the Android Keystore, and
your prompts go only to the provider you pick.

## Features

- **On-device agent** with the full OpenCode tool surface: read, write, edit, glob, grep, ls,
  apply_patch, todo lists, plan and build modes, user questions, subagents (task tool), webfetch
  with free web search, MCP servers (Streamable HTTP and SSE), and progressive-disclosure skills
  (SKILL.md).
- **Providers**: OpenCode Zen and Go, Anthropic, OpenAI, OpenRouter, Google, xAI, Groq, DeepSeek,
  Mistral, and Together, with the catalog sourced from models.dev. You can enable or disable
  providers, hide models, and mark favourites. The agent can add providers and models itself by
  editing `providers.json`, which is hot-reloaded.
- **Sign-in flows**: GitHub uses the OAuth device flow (you type a code, with no tokens to paste)
  for push and pull. The ChatGPT (Codex PKCE) flow is in place as groundwork.
- **Projects and chats**: chats are organized into projects. Each project is its own workspace
  folder and git repository, with snapshots (commits), branch switching, and push and pull from the
  chat's git button.
- **Streaming chat** with reasoning traces, a tool-activity timeline, monochrome syntax
  highlighting, a context-window gauge, and per-model token limits that drive compaction.
- **Privacy by construction**: keys are encrypted on-device, crash logs stay local, export and
  import use a file you choose through the Storage Access Framework, and Auto Backup excludes
  secrets.

## Design

PhoneCode uses its own design language. It is strictly monochrome: AMOLED black, pure white, and the
shades between them. Motion follows Apple HIG physics, with research-grounded springs, instant press
feedback, and iMessage-style message insertion. The layout is true edge-to-edge with theme-aware
system bars.

While the model is thinking, an energy layer appears. It is inspired by Neural Expressive and
rendered purely in luminance: a soft mist, a shimmer, and flowing light that fade out once the
response is done. Light is the only accent.

Design references live in `design/` (interactive HTML prototypes) and `design/specs/`, which holds
the researched specs for Apple motion, Neural Expressive, design tokens, OAuth flows, and the export
format.

## Building

Requirements: JDK 21 and the Android SDK (platform 34 and 36, build-tools 36). Android Studio is not
needed.

```powershell
$env:JAVA_HOME = "<path to JDK 21>"
.\gradlew.bat :app:assembleRelease   # minified release build -> app/build/outputs/apk/release/
.\gradlew.bat :app:assembleDebug     # debuggable build
.\gradlew.bat test                   # all module unit tests (incl. Robolectric UI smoke tests)
```

The project has four modules. `:app` holds the Compose UI and Android glue. `:agent` holds the loop,
prompts, and compaction. `:provider` holds the wire formats and catalog. `:tools` holds the file,
git, web, todo, MCP, and skills tooling. The three library modules are pure JVM and fully
unit-tested.

## Notes

- The GitHub sign-in ships with the gh CLI's public client id for personal builds. Register your own
  OAuth app (one checkbox: Enable Device Flow) before distributing.
- Codex sign-in stores tokens securely. Using a ChatGPT plan as an inference provider requires the
  Responses API wire format, which is not implemented yet.
- The Terms of Service and Privacy Policy live in `legal/` and are also shown in-app under
  Settings > About.
