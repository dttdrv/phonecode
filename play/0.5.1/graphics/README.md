# PhoneCode 0.5.1 Play graphics

These assets are deterministic renders of PhoneCode's real Compose UI. Upload the opaque JPEG phone
screenshots, the opaque JPEG feature graphic, and the PNG app icon. The same-name phone PNGs and
`feature-graphic-source.png` are retained render sources, not upload files.
`emulator-final.png` is a clean-install emulator QA capture, not a Play listing upload.

## Upload inventory

| Asset | Dimensions | Format | Suggested alt text |
| --- | ---: | --- | --- |
| `app-icon.png` | 512 × 512 | PNG with alpha | PhoneCode's white folded-code mark on black |
| `feature-graphic.jpg` | 1024 × 500 | JPEG | PhoneCode welcome screen with project, workspace, and model choices |
| `phone/05-welcome.jpg` | 1440 × 2560 | JPEG | PhoneCode welcome screen |
| `phone/01-agent-conversation.jpg` | 1440 × 2560 | JPEG | A coding-agent conversation with tool results and code |
| `phone/02-action-approval.jpg` | 1440 × 2560 | JPEG | Approval dialog explaining a command before it runs |
| `phone/03-project-drawer.jpg` | 1440 × 2560 | JPEG | Project drawer with chats, Skills, and MCP |
| `phone/04-settings.jpg` | 1440 × 2560 | JPEG | PhoneCode settings and workspace controls |
| `phone/06-setup.jpg` | 1440 × 2560 | JPEG | Setup checklist for model, project folder, and GitHub |
| `phone/07-model-providers.jpg` | 1440 × 2560 | JPEG | Recommended model providers and privacy disclosure |

## Source and verification

- `PlayListingAssetTest` renders the icon, feature graphic, welcome, setup, and provider views.
- `ScreenshotTest.playListingPhoneScreenshots` renders the conversation, approval, drawer, and
  settings views.
- Phone assets use the required 9:16 aspect ratio and exceed the 1080 × 1920 recommendation.
- No credential, personal file, account identifier, payment claim, ranking, price, or promotional
  badge appears in the assets.
- The provider screenshot explicitly disables debug-only ChatGPT OAuth so it matches the release
  availability boundary.

Before Play upload, run both render tests, regenerate the JPEG conversions, visually inspect every
asset, and compare the visible behavior with the exact signed AAB.
