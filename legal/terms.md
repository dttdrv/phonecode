# PhoneCode Terms of Service

_Last updated: 26 June 2026_

These Terms cover your use of **PhoneCode**, an Android app that runs an AI coding agent directly on your device. PhoneCode is a small, independent project. By installing or using the app, you agree to these Terms. If you don't agree, please don't use the app.

This document is written in plain English on purpose. It isn't legal advice, and it doesn't replace the terms of the AI providers or other services you connect to PhoneCode.

## 1. What PhoneCode is

PhoneCode is an on-device AI coding assistant. It lets you give instructions to an AI agent that can read and write files in a workspace on your phone, run Git operations, search the web, and help you write and edit code.

PhoneCode does **not** include its own AI model. To do anything useful, you connect it to a third-party AI model provider of your choice, for example OpenAI, Anthropic, OpenRouter, OpenCode Zen/Go, Google, xAI, Groq, DeepSeek, or Mistral, using your own account and API key with that provider. PhoneCode is a client that talks to whichever provider you pick.

There is no PhoneCode account, no PhoneCode login, and no server run by us that sits between you and your provider. The app runs on your device and connects directly to the services you choose.

## 2. You bring your own keys and accounts

To use PhoneCode you supply your own API key(s) for the AI provider(s) you want to use. If you use Git features, you supply your own Git credentials (for example a GitHub personal access token).

Your keys and credentials are stored encrypted on your device using the Android Keystore. They are not uploaded to us (we don't run servers that could receive them), and they only leave your device when the app uses them to authenticate directly with the provider or Git host you chose.

You are responsible for keeping your keys and credentials safe, for any usage and charges incurred under them, and for complying with the terms of the accounts they belong to. If your device is lost, stolen, or compromised, you should revoke and rotate the affected keys with the relevant provider.

## 3. Your data goes to the providers you choose

When you use the agent, PhoneCode sends your prompts and the code, files, or other content you include to the AI model provider you selected, so the provider can generate a response. That's how the app works: the model that does the reasoning runs on the provider's servers, not on your phone.

**Once your content reaches a provider, that provider's own terms of service and privacy policy apply to it**, including how they handle, retain, log, or use your data, and whether they use it to train models. We have no control over and take no responsibility for what third-party providers do with your data. Before sending anything sensitive, confidential, or subject to obligations you owe to others, review the relevant provider's terms and choose a provider and settings you're comfortable with.

Web search in PhoneCode uses DuckDuckGo, or the search capability built into the model you're using, depending on how the agent is configured. Search queries go to that service, and that service's terms and privacy practices apply to them.

We don't operate any of these third-party services and don't endorse them. Your relationship for those services is directly between you and the provider.

## 4. What the agent can do on your device

When you ask it to, the PhoneCode agent can:

- **Read and write files** in its on-device workspace, including creating, modifying, and deleting files.
- **Run Git operations**, including commits and, using the Git credentials you provide, pushing to and pulling from remote repositories such as GitHub.
- **Run web searches** to gather information.
- **Set up and use a Linux sandbox** on your device, which downloads a base Linux system from Alpine Linux's servers and can install and run additional software, such as Python, inside the app's sandbox.

These actions are powerful. The agent acts on your instructions and on the output of an AI model, which can be wrong, incomplete, or unexpected. **You are responsible for the code the agent writes and for any action it takes, including pushes and other changes to your repositories.** Review changes before you rely on them, before you run generated code, and before you push to shared or production repositories. Keep backups and use version control so you can undo mistakes.

We recommend reviewing the agent's proposed changes rather than accepting them blind, especially when it can modify files or push to a remote.

## 5. AI output: no guarantees

AI-generated code and text can contain errors, security vulnerabilities, insecure patterns, licensing issues, or content that simply doesn't do what you wanted. PhoneCode does not verify, test, or guarantee anything the model produces.

You decide whether to use, run, ship, or rely on any output. Test it, review it, and treat it the way you'd treat code from any unverified source. You are solely responsible for the consequences of running or deploying generated code, including any data loss, downtime, security issues, or costs that result.

## 6. Your responsibilities

By using PhoneCode you agree that:

- You will comply with all laws that apply to you, and with the terms of every third-party provider and service you connect.
- You will not use PhoneCode for any unlawful, harmful, or abusive purpose, or to produce or distribute malware or content that infringes others' rights.
- You own or have the right to use the code, files, and content you give to the agent and send to providers.
- You are responsible for all API usage, fees, and charges billed by your providers. PhoneCode does not bill you and has no visibility into or control over those costs.
- You will review the agent's changes and output before relying on them.

## 7. Age requirement

PhoneCode is intended for users aged 13 and over. If you are under the age of majority where you live, you should only use PhoneCode with the involvement of a parent or guardian. Some AI providers set their own, higher minimum ages. You must meet the age requirement of any provider you connect.

## 8. The app is provided "as is"

PhoneCode is provided **"as is" and "as available", without warranties of any kind**, express or implied, including any implied warranties of merchantability, fitness for a particular purpose, accuracy, or non-infringement. We don't promise the app will be uninterrupted, error-free, secure, or compatible with your device, your provider, or your workflow.

This is an independent project. Features may change, break, or be removed, and providers may change their APIs in ways that affect the app.

## 9. Limitation of liability

To the maximum extent allowed by law, the developer of PhoneCode is not liable for any indirect, incidental, special, consequential, or punitive damages, or for any loss of data, code, profits, revenue, or business, arising out of or related to your use of (or inability to use) PhoneCode, including anything caused by AI output, by the agent's file or Git actions, by third-party providers, or by API costs you incur.

To the extent any liability cannot be excluded, it is limited to the amount you paid for PhoneCode, which for most users is nothing.

Some jurisdictions don't allow certain warranty or liability exclusions, so some of the above may not apply to you. In that case, the exclusions apply to the fullest extent permitted.

## 10. Third-party software and licenses

PhoneCode is built on and includes open-source software, and connects to third-party services. Those components and services are governed by their own licenses and terms, which apply in addition to these Terms.

## 11. Changes to these Terms

We may update these Terms as the app evolves. The "Last updated" date at the top shows the current version, which is bundled with the app. Continuing to use PhoneCode after an update means you accept the revised Terms.

## 12. Contact

For questions about these Terms, contact the developer through the project's public repository at [github.com/dttdrv/phonecode](https://github.com/dttdrv/phonecode).
