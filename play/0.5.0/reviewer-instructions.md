# Reviewer access and test instructions

Status: **BLOCKED — dedicated review credentials and final-build steps are not yet verified.**

## App access declaration

PhoneCode has no first-party account and can be opened without signing in. Core AI-agent output,
however, requires a third-party model API key. The release build disables Codex/ChatGPT OAuth, so it
must not appear in the reviewer path or be offered as a fallback. Select **All or some functionality
is restricted** unless the exact final build provides a complete credential-free core path. Google
requires valid, reusable review access for restricted functionality; see [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-EN).

Before submission, create a dedicated reviewer API key for one supported built-in provider that
explicitly permits this use. It must be non-personal, minimally scoped where the provider supports
scopes, capped, monitored, valid for the full review period, and usable without MFA or region
restrictions. Put the secret only in Play Console's app-access fields, never in this repository,
screenshots, listing text, or the evidence video. Do not use a custom endpoint for the primary review
path; the exact provider and model must be selected and tested before submission.

| Play Console field | Value |
| --- | --- |
| Instruction name | `PhoneCode model review access` |
| Username | Not applicable for the selected API-key-only provider |
| Password/API key | BLOCKED — enter the dedicated secret in Play Console only |
| Other instructions | TODO — name the exact built-in provider row, model to use, key-expiry policy, and a monitored support contact that can restore access during review |

GitHub, MCP, custom endpoints, package repositories, Codex OAuth, and paid ChatGPT access must not be
required to complete the primary review path. Do not give reviewers any personal account.

## Primary review path

Validate these labels and steps against the exact signed AAB before pasting them into Play Console.

1. Launch PhoneCode and tap **Get started**.
2. On **Setup**, tap **Connect a model**. **Skip setup for now** does finish onboarding, but leaves
   agent input disabled until a model is configured, so do not use it for the primary review path.
3. On **Set up a model**, open the built-in API-key provider named in the private Play Console
   instructions. Enter the dedicated key in **API key**, then tap **Save and continue**.
4. A successful save returns directly to **Setup**, where **Connect a model** shows **Ready to use**
   and **Start building** is enabled. PhoneCode has already activated an available model for the
   configured provider; the private instructions must name the exact model verified for review.
5. On **Setup**, tap **Choose a project folder**, create or select a disposable folder in Android's
   system folder picker, then tap **Start building**.
6. Start a new chat in that project and use this prompt:

   `Using the linked-folder file tools, create review.txt in the linked phone folder containing PhoneCode review test, then read it back.`
7. Keep the default **Settings > Files & permissions > Approval policy > Ask before each change**.
   When **Approve agent action?** appears, review the details and tap **Approve once**. Verify the
   response and file content.
8. Open the project drawer to show the project-scoped chat, then open **Settings** and inspect
   **Skills** and **MCP servers**. MCP setup is optional and not required for the test.
9. Under a completed AI response, confirm the flag action labeled **Send safety feedback** is available
   in-app.
   Do not submit a test report unless the review credential owner has approved that test.
10. Use the separate foreground-service script in [`foreground-service.md`](foreground-service.md)
   to test background execution and the Stop action.
11. Follow **Accurate cleanup** below; project removal by itself is not data deletion.

## Accurate cleanup

1. Delete `review.txt` from the linked phone folder, either through an approved PhoneCode file action
   or Android's file manager, and verify that the file is gone.
2. Delete the disposable chat from PhoneCode's project drawer. This removes that chat from the device.
3. Open **Settings > Providers > TODO provider** and clear the **API key** field. A blank value removes
   the saved key; confirm the provider no longer shows **Key set**.
4. If the project link should be removed, choose **Delete project**. Despite that label, this unlinks
   the selected phone folder, moves any remaining project chats to **Unsorted**, and preserves private
   workspace files under **Recovered projects**. It does not delete the linked phone folder.
5. For a complete wipe of PhoneCode's remaining app-private review data, clear the app's storage or
   uninstall it after deleting any test files from the linked phone folder. Clearing storage or
   uninstalling does not delete files in that external folder or data held by the model provider.

## Reviewer context

- PhoneCode is a coding tool whose agent can modify selected files and run development actions.
- File and photo access uses Android's system picker. A linked folder remains accessible until the
  user unlinks it, revokes the system grant, clears app data, or uninstalls the app.
- The app has no ads, analytics, telemetry, PhoneCode account, subscription, or in-app purchase based
  on the candidate source. Verify those statements against the final AAB.
- Ko-fi and Stripe are intentionally absent from the Play app and review flow. External support does
  not unlock app features or services.
- AI output may vary. The dedicated review model and prompt must be tested repeatedly before
  submission; provide a fallback API key for the same documented provider in Play Console if
  permitted and necessary.
- Codex/ChatGPT OAuth is disabled in the release build and is not a release review option.
- PhoneCode's in-app report action must remain reachable without leaving the app, and its handling
  must satisfy the current [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en).
- The current PRoot/Alpine prototype is not acceptable as the Play release runtime. Do not submit a
  build exposing it as the release execution boundary. Complete and verify the isolated VM gate first.

## Pre-submission access check

- Install the signed AAB through a Play internal-testing track on a clean, non-developer device.
- Follow these instructions with no repository knowledge and no developer shell access.
- Confirm the credential can be entered and works after a clean reinstall and from a second network.
- Confirm every screen is reachable without hidden gestures or an unavailable personal account.
- Confirm the reviewer can stop active work, delete the test file and chat, remove the API key, and
  understand that project removal preserves recovered app-private files rather than erasing them.
- Keep the credential active until review and any appeal are complete, then revoke it.
- Record the test date, device/API, Play-delivered version, provider/model, and redacted success proof
  in the release evidence index.
