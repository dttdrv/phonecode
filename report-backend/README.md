# PhoneCode report backend

This Cloudflare Worker implements the only developer-operated PhoneCode endpoint:
`POST /api/phonecode/report`. It accepts exactly the Android v1 report payload, returns
`202 {"id":"PCR-..."}`, and stores no prompt, model output, files, credentials, chat history,
provider/model identifier, device identifier, or raw IP address.

The connecting IP is HMAC-SHA-256 hashed with a secret and the UTC date. A daily hash may submit
five reports by default. Reports are scheduled for deletion after 89 days and rate-limit rows after
24 inactive hours. Cleanup runs during later report processing and daily so ordinary retention stays
within the public 90-day and 48-hour maximums.

## Verify locally

Install the pinned toolchain and run both the dependency-free unit contracts and the bounded
Miniflare workerd + local D1 contract:

```sh
npm ci
npm test
npm run check:migrations
npm run check:deploy
```

To exercise the real Workers runtime and local D1 with the configured development binding, run:

```sh
npx wrangler d1 migrations apply phonecode-reports --local
npx wrangler dev
curl -i http://localhost:8787/api/phonecode/report \
  -H 'Content-Type: application/json' \
  -H 'CF-Connecting-IP: 203.0.113.9' \
  --data '{"version":1,"category":"privacy","appVersion":"0.4.0","platform":"android","note":"synthetic test"}'
curl -i 'http://localhost:8787/cdn-cgi/handler/scheduled?format=json'
```

Put development-only values in `.dev.vars`; never commit that file:

```dotenv
IP_HASH_SECRET=replace-with-at-least-32-random-characters
ADMIN_TOKEN=replace-with-at-least-32-random-characters
```

## Production deployment

The production Worker is deployed as `phonecode-report` with:

- D1 database `phonecode-reports` in Western Europe;
- generated `IP_HASH_SECRET` and `ADMIN_TOKEN` secret bindings;
- the route `dttdrv.xyz/api/phonecode/report*`;
- the daily cleanup trigger `17 3 * * *`.

The deployment was verified on 2026-07-27: `GET` returned the contract `405` JSON response, a
contract-valid synthetic `POST` returned `202`, D1 contained only the documented columns, and the
synthetic report and rate-limit row were removed afterward.

## Provision in another Cloudflare account

1. Run `npx wrangler d1 create phonecode-reports` and replace the configured `database_id` in
   `wrangler.jsonc` with the returned database ID.
2. Generate independent high-entropy values, then set them interactively with
   `npx wrangler secret put IP_HASH_SECRET` and `npx wrangler secret put ADMIN_TOKEN`.
3. Run `npx wrangler d1 migrations apply phonecode-reports --remote`.
4. Run `npx wrangler deploy`.
5. Send a synthetic report to `https://dttdrv.xyz/api/phonecode/report`, retain the returned
   reference as private release evidence, and verify the D1 row contains only the documented fields.
6. Exercise the scheduled handler and verify expired synthetic rows are removed before marking the
   Play retention evidence complete.

The route deliberately disables `workers.dev`; only the configured `dttdrv.xyz` route is public.
The Worker emits no application logs containing request bodies, IP addresses, or report contents.

## Honor an early-deletion request

After validating a request received through the privacy contact channel, use its report reference.
The production maintainer token is stored in macOS Keychain under account `PhoneCode` and service
`PhoneCode Report Backend Admin Token`; it is never stored in this repository.

```sh
ADMIN_TOKEN="$(security find-generic-password \
  -a PhoneCode \
  -s 'PhoneCode Report Backend Admin Token' \
  -w)"
curl -i -X DELETE "https://dttdrv.xyz/api/phonecode/report/PCR-REFERENCE" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
unset ADMIN_TOKEN
```

The endpoint returns `204` whether or not the reference existed, so it cannot be used to enumerate
reports. Rotate `ADMIN_TOKEN` immediately if it may have been exposed.
