# Reviewer access and test instructions

Status: **BLOCKED. No reviewer credential exists and these steps have not been run on a signed
build.** Labels below were read from source on 24 September 2026.

## App access declaration

PhoneCode has no account. It opens without sign-in, but agent output needs a model provider key.
ChatGPT sign-in is compiled out of the release build (`CODEX_OAUTH_ENABLED=false`) and must not
appear in the review path. Select **All or some functionality is restricted** and give reviewers a
working key. See [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en).

Create a dedicated reviewer key for one built-in provider that allows this use (OpenRouter or
OpenCode Zen are the simplest candidates). It must be non-personal, spend-capped, monitored, valid
through review and any appeal, and free of MFA or region limits. Enter it only in Play Console, never
in this repository, screenshots, listing text, or the FGS video.

| Play Console field | Value |
| --- | --- |
| Instruction name | `PhoneCode model review access` |
| Username | Not applicable |
| Password / API key | PENDING: enter only in Play Console |
| Other instructions | PENDING: exact provider row, model, key expiry, and a monitored contact |

GitHub, plugins, custom endpoints, and ChatGPT are not needed for review.

## Review path

1. Launch PhoneCode and tap **Get started**.
2. On **Setup** (Step 2 of 2), tap **Connect a model**. Do not use **Explore without a model**; it
   leaves agent input disabled.
3. On **Set up a model**, open the provider named in the private instructions, paste the key into
   **API key**, and tap **Save and continue**.
4. Back on **Setup**, confirm **Connect a model** is complete, optionally tap **Link a phone
   folder** and pick a disposable folder in Android's picker, then tap **Start building**.
5. Send:
   `Create review.txt containing "PhoneCode review test", then read it back.`
6. With the default **Settings > Files & permissions > Ask before each change**, the app shows
   **Approve agent action?**. Tap **Approve once** and check the reply.
7. While a longer request runs, leave the app and open the notification shade to see
   `PhoneCode is working` and **Stop**. The full script is in
   [`foreground-service.md`](foreground-service.md).
8. On a completed reply, tap the info icon (**Response info**) and confirm **Report response**
   opens **Send safety feedback** inside the app. Do not send a report unless the credential owner
   approved that test.
9. Optionally open **Settings > Plugins** to see the plugin catalog. No plugin is required.

## Cleanup

1. Delete `review.txt` (ask the agent, or use a file manager for a linked folder).
2. Delete the test chat from the drawer.
3. In **Settings > Models & providers**, open the provider and remove the key (**Remove key**).
4. **Delete project** unlinks the folder and moves chats to **Unsorted**; private workspace files
   move to **Recovered projects**. It does not erase data. Clear storage or uninstall for a full wipe.

## Reviewer context

- PhoneCode is a coding tool whose agent edits files the user selects and calls tools the user
  enables. File and photo access uses Android's pickers; no storage or media permission is requested.
- No ads, analytics, telemetry, account, subscription, or in-app purchase.
- The release build has no command runtime: `bash` and `process` are not offered until the isolated
  VM ships. It does not download or execute native code.
- Ko-fi and Stripe payment links are absent from the app. The Stripe entry in **Plugins** is a
  connector to the user's own Stripe account, not a payment method for PhoneCode.

## Pre-submission access check (PENDING)

- Install from an internal track on a clean, non-developer device and follow this file with no
  repository knowledge.
- Confirm the key works after reinstall and from a second network.
- Record date, device/API, version, provider/model, and redacted proof in the evidence index.
