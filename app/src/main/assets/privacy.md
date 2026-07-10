# PhoneCode Privacy Policy

_Last updated: 26 June 2026_

This policy explains what happens to your data when you use **PhoneCode**, an Android app that runs an AI coding agent on your device. The short version: PhoneCode runs locally, we don't run any servers, and we don't collect, receive, or see your data. The longer version is below, in plain English.

## The short version

- PhoneCode runs **on your own device**.
- There is **no analytics, no telemetry, and no developer-run server.** We, the people who make PhoneCode, do not receive your prompts, your code, your files, your API keys, or any usage data.
- Your **API keys and credentials are stored encrypted** on your device in the Android Keystore and never leave it except to authenticate directly with the provider or Git host you chose.
- When you use the agent, your prompts and selected code/files are sent to the **third-party AI provider you choose**. From that point, **that provider's privacy policy applies.**

## 1. We don't collect your data

PhoneCode has no analytics SDK, no telemetry, no crash-reporting service, and no backend that we operate. We don't have user accounts, and there's no server in the middle of your sessions. Because of this, **the developer of PhoneCode does not collect, store, transmit, or have access to your prompts, your code, your files, your API keys, your Git credentials, or any record of how you use the app.** We genuinely can't see it.

## 2. What's stored on your device

PhoneCode stores data **locally on your device** so the app can work:

- **API keys and credentials** (such as AI provider API keys and Git tokens), encrypted using the Android Keystore.
- **Your workspace files**: the code and files the agent reads and writes, kept in the app's storage on your device.
- **App settings and configuration**, such as which provider and model you've selected, your preferences, and provider/model configuration.
- **Chat and session history** for your conversations with the agent, kept on the device so you can pick up where you left off.

This data stays on your device. You can remove it by deleting the relevant data within the app, by clearing the app's storage, or by uninstalling PhoneCode. Note that uninstalling removes local data on the device but does **not** revoke API keys with your providers. To fully revoke access, delete or rotate the keys in your provider account.

## 3. Data you send to AI providers

PhoneCode doesn't include an AI model. To answer your requests, it sends data to the **third-party AI model provider you choose**, for example OpenAI, Anthropic, OpenRouter, OpenCode Zen/Go, Google, xAI, Groq, DeepSeek, or Mistral. The data sent typically includes:

- The prompts and instructions you give the agent.
- The code, files, and other content you choose to include or that the agent reads to fulfil your request.
- Related context needed to produce a useful response.

This data travels **directly from your device to the provider you selected**, authenticated with your own API key. It does not pass through us.

**Once your data reaches a provider, it is handled under that provider's own privacy policy and terms**, including how long they keep it, whether they log it, and whether they use it to train or improve their models. We don't control that and can't speak for it. Please read the privacy policy of any provider you connect, and avoid sending sensitive or confidential material to a provider whose practices you haven't reviewed and accepted.

## 4. Git and remote repositories

If you use Git features, PhoneCode uses the Git credentials you supply (for example a GitHub token) to connect **directly** to the Git host you specify. Pushing and pulling sends your code and commit data to that remote service under your account. That service's privacy policy and terms apply to anything you send it. Your credentials are stored encrypted on your device and are only used to authenticate with the host you chose.

## 5. Web search

When the agent searches the web, it uses **DuckDuckGo**, or the search capability built into the AI model you're using, depending on configuration. Search queries go directly to that service, and that service's privacy practices apply to those queries. We don't run a search service and don't receive your queries.

## 6. The Linux sandbox

The agent can set up an optional Linux sandbox on your device so it can install and run software such as Python. The base Alpine Linux system is **bundled in the app** (no download). When you or the agent install additional packages (for example python3), the package manager fetches them from **Alpine's package repositories** (alpinelinux.org). The app does not send your prompts, code, or keys to Alpine; those package requests are subject to Alpine's own terms and privacy practices, and to the terms of any package source you add.

## 7. No selling or sharing by us

Because we don't collect your data in the first place, we don't sell it, rent it, share it, or use it for advertising or profiling. The only places your data goes are the third-party services **you** connect (AI providers, Git hosts, search), and only when you direct the app to use them.

## 8. Children's privacy

PhoneCode is intended for users aged 13 and over and is not directed at children under 13. We don't knowingly collect personal information from children. In fact, we don't collect personal information from anyone, since the app has no telemetry or backend. The third-party providers you connect may set their own age requirements, which you must meet.

## 9. Security

Your API keys and credentials are encrypted on your device using the Android Keystore. The app communicates with providers over encrypted connections. That said, no system is perfectly secure, and the overall security of your data also depends on your device, your provider accounts, and how you handle your keys. Keep your device secured, and revoke and rotate keys with your providers if you suspect they've been exposed.

## 10. Your control

You're in control of your data because it lives on your device and with the providers you chose:

- **On your device:** clear chats, settings, and stored keys within the app, clear the app's storage, or uninstall PhoneCode to remove local data.
- **With providers:** to access, export, or delete data you've sent to an AI provider or Git host, or to revoke a key, use that provider's account tools and follow their privacy policy.

## 11. Changes to this policy

We may update this policy as the app evolves. The "Last updated" date at the top shows the current version, which is bundled with the app. Material changes will be reflected in the version shipped with the app.

## 12. Contact

For questions about this Privacy Policy, visit [dttdrv.xyz/phonecode](https://dttdrv.xyz/phonecode) or the public repository at [github.com/dttdrv/phonecode](https://github.com/dttdrv/phonecode).
