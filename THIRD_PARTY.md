# Vendored third-party artifacts

Prebuilt third-party files committed into the app, with pinned sources so each is auditable: re-fetch
from the pinned version and compare the SHA-256.

## Mermaid (diagram rendering)

- **File:** `app/src/main/assets/mermaid.min.js`
- **What:** the Mermaid diagram engine, inlined into a WebView to render ` ```mermaid ` blocks (trees,
  graphs, flowcharts, sequence/state/ER diagrams) on-device with no network.
- **Source:** https://www.npmjs.com/package/mermaid, `dist/mermaid.min.js` (the UMD build exposing
  `window.mermaid`).
- **Pinned version:** 10.9.3
- **SHA-256:** `5a8ec91820bd55afef049068489369910e5d6ce70c8103952f27e29d3e76e8bc`
- **License:** MIT.

To re-verify:

    curl -fsSL https://cdn.jsdelivr.net/npm/mermaid@10.9.3/dist/mermaid.min.js | shasum -a 256

## PRoot (Linux userland)

See `app/src/main/jniLibs/PROVENANCE.md` for the arm64 `libproot.so` + `libproot-loader.so` provenance
(green-green-avk/build-proot-android, pinned commit, SHA-256, licenses).
