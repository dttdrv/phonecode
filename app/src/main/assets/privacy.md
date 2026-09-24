# PhoneCode Privacy Policy

_Last updated: 24 September 2026_

This Privacy Policy explains how PhoneCode handles data. PhoneCode is an on-device AI coding client published by Deyan Todorov. The developer does not operate a general-purpose PhoneCode backend and does not receive your prompts, files, chats, or credentials. The only developer-operated service is a narrowly scoped reporting endpoint that receives AI-output reports you deliberately submit in the app.

## 1. Data PhoneCode keeps on your device

PhoneCode stores the following locally so the app can work:

- Provider API keys, sign-in tokens, Git credentials, GitHub sign-in tokens, and header values for MCP servers and plugins (for example a GitHub personal access token or a Stripe restricted key), encrypted with Android Keystore-backed encryption. If secure storage is unavailable, PhoneCode does not save credentials.
- Projects, workspace files, linked-folder references, chat history including photos you attached, todo lists, skills, MCP and plugin configuration, provider configuration, and app settings.
- Your optional personalization: preferred name, occupation, "About you" text, response style, and custom instructions.
- A cached copy of the public model catalog.
- Where your build includes the local command runtime, its on-device Linux environment and any packages installed by you or the agent.

Android cloud backup is disabled for PhoneCode. You can create a manual export of supported chats and settings. Manual exports are not encrypted, so store and share them carefully. PhoneCode does not intentionally include saved provider, sign-in, Git, or MCP header credentials, but exports may contain sensitive content from chats and tool activity.

## 2. Files and photos you choose

PhoneCode does not request storage or photo-library permissions. It can access files or photos only after you select them through Android's system file picker or photo picker. PhoneCode re-encodes a selected photo at up to 1600 pixels before attaching it; the re-encoded copy does not carry over the original file's embedded metadata. If you link a folder, Android grants PhoneCode continuing access to that folder until you unlink it, revoke access in system settings, clear the app's data, or uninstall the app. The agent may read, create, change, rename, or delete content within a linked folder according to your permission setting and instructions.

Files remain on your device unless you or the agent send relevant content to one of the destinations described below. Review linked-folder access and agent permission settings before allowing automatic changes.

## 3. Data sent to the AI provider you choose

PhoneCode does not contain an AI model. When you use the agent, the app sends your request directly from your device to the AI provider you selected. The request may include your prompt, earlier messages in the chat, attached photos or text files, your personalization and custom instructions when personalization is on, project instructions, tool results, and content the agent reads from a workspace or linked folder.

Built-in providers include OpenCode Go, OpenCode Zen, OpenAI, Anthropic, OpenRouter, Google Gemini, xAI, DeepSeek, and Mistral. You can also add a custom OpenAI-compatible provider. Builds that offer ChatGPT sign-in connect to OpenAI's sign-in and ChatGPT services and store the resulting tokens on your device. Requests to OpenCode include a random per-conversation identifier that OpenCode uses to group requests; requests to OpenRouter include PhoneCode's app name for attribution. PhoneCode sends each credential only to the service it belongs to.

The selected provider's privacy policy, retention rules, and terms apply once it receives data. The developer cannot access or delete data held by those services.

## 4. Other destinations

Depending on the features you use, PhoneCode may connect directly from your device to:

- **Web search and fetch.** When you or the agent search the web, the query is sent to DuckDuckGo. When the agent fetches a web page, PhoneCode requests that public HTTPS address. Fetched content is then passed to your AI provider as context.
- **GitHub.** If you sign in with GitHub, PhoneCode uses GitHub's device sign-in and reads your GitHub username. The agent's Git pull and push tools use HTTPS; saved Git credentials are sent only to GitHub.
- **MCP servers and plugins.** When you add a plugin from the in-app catalog (for example GitHub, Stripe, DeepWiki, Microsoft Learn, AWS Knowledge, or Cloudflare documentation) or your own MCP server, PhoneCode connects to that server and sends it the tool calls the agent makes, including arguments that may contain your content, plus any token you entered for it. Servers that the agent adds or edits stay off until you turn them on in Settings.
- **models.dev**, to refresh public provider and model metadata. Prompts and workspace files are not included.
- **Package sources**, where your build includes the local command runtime and you or the agent install software.
- **dttdrv.xyz**, only when you deliberately submit an AI-output report.

Built-in network connections use HTTPS. PhoneCode blocks unencrypted HTTP except to your own device (localhost), which you can use for a custom provider or MCP server that you run locally. Each receiving service can see normal network information such as your IP address and request metadata, and its own privacy policy applies.

These transfers happen only when you use the related feature. Do not send confidential, personal, or regulated information unless you are authorized to do so and accept the receiving service's practices.

## 5. Data the developer collects

PhoneCode contains no advertising SDK, analytics, telemetry, or remote crash-reporting service. The developer does not sell personal data from the app.

If you choose Report response, pick a category, and tap Send, PhoneCode sends the selected category, an optional note of up to 1,000 characters that you write, the app version, and the platform to dttdrv.xyz. It never attaches the AI response, your prompt, files, credentials, tool activity, chat history, provider or model identifiers, or a device identifier. Reports are stored in a private Cloudflare D1 database and are used to investigate harmful output and improve filtering and moderation. They are scheduled for deletion after 89 days; daily and request-triggered cleanup is designed to keep retention within 90 days.

To limit abuse, the reporting endpoint converts the connecting IP address into a keyed hash that changes daily and stores that hash with a request counter and timestamps. It does not store the raw IP address. Rate-limit records are scheduled for deletion after 24 inactive hours; daily and request-triggered cleanup is designed to keep their retention within 48 hours. Cloudflare processes ordinary network and edge request metadata as the hosting provider under its own terms and privacy practices.

## 6. Notifications

While the agent works, PhoneCode shows an ongoing notification with a Stop action. On Android 16 and newer this notification may appear as a Live Update in the status bar and on the lock screen, where it shows the current step or that an approval is waiting. You can dismiss it, turn off Live Updates for PhoneCode, or deny notification permission in Android settings. PhoneCode does not send notification content to the developer or any other service.

## 7. Security

Credentials are stored using Android Keystore-backed encryption and are excluded from manual exports. The MCP configuration file keeps server URLs, header names, and non-secret settings without the header values. Custom endpoints, MCP servers, and software installed in a local development environment are under your control and may have different security properties. Where your build includes the local command runtime, commands run in an on-device virtual machine with only explicitly mounted app resources; installed software can access that workspace and use PhoneCode's network access. No security measure is perfect. Protect your device and revoke credentials if you believe they were exposed.

## 8. Retention and deletion

Local data remains until you delete it in PhoneCode, clear the app's storage, or uninstall the app. You can delete a chat, remove a saved API key, sign out of GitHub, and remove MCP servers and plugins in Settings. Deleting or unlinking a project moves its private workspace files into the Unsorted workspace under Recovered projects so they are not lost. Unlinking a folder removes PhoneCode's saved access but does not delete that phone folder. Uninstalling PhoneCode does not delete remote repositories, provider records, third-party accounts, or a report you already submitted.

A successful report displays a reference you can include in an early-deletion or other privacy request to the developer.

To access or delete data held by an AI provider, GitHub, a search service, a package source, or an MCP server, use that service's account and privacy controls. PhoneCode has no account of its own, so there is no PhoneCode account to delete.

## 9. Children

PhoneCode is not directed to children under 13. Users must also meet the age requirements of every third-party service they connect.

## 10. Changes

This policy may change as PhoneCode changes. The current policy is included in the app and will show a new date when updated.

## 11. Contact

For privacy questions, use the contact form at [dttdrv.xyz](https://dttdrv.xyz/#contact). Product information is available at [dttdrv.xyz/phonecode](https://dttdrv.xyz/phonecode), and source code is available at [github.com/dttdrv/phonecode](https://github.com/dttdrv/phonecode).
