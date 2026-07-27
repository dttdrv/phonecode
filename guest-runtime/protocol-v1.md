# PhoneCode guest protocol v1

The protocol is not wired to QEMU yet. This document and the host codec pin the transport contract
for the future trusted guest daemon.

## Framing and canonical JSON

Each frame is a four-byte unsigned big-endian payload length followed by that many bytes of strict
UTF-8 JSON. The payload is 1 through 65,536 bytes.

JSON objects are canonical:

- keys are ordered lexicographically by Unicode code point at every object depth;
- arrays retain declared order;
- no insignificant whitespace, duplicate keys, comments, trailing data, non-finite numbers, or
  malformed UTF-8 are accepted;
- fields not declared by the selected message schema are rejected.

Every frame contains integer `v`, string `type`, and integer `id`. `v` is exactly `1`. Request IDs
are positive signed 63-bit integers and unique while active. Lifecycle frames use `id: 0`.

## Handshake

The host generates a cryptographically random 32-byte nonce encoded as 64 lowercase hexadecimal
characters:

```json
{"id":0,"nonce":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","type":"hello","v":1}
```

The guest returns the exact nonce, fixed daemon identity, and exact ordered capability list:

```json
{"agent":"phonecode-guestd","capabilities":["exec","shutdown","signal","stdin"],"id":0,"nonce":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","type":"ready","v":1}
```

A missing, late, malformed, mismatched, or duplicate READY frame fails startup and closes the
session. It never enables a compatibility or host-shell fallback.

## Commands and output

`exec` fixes the working directory at `/workspace`, limits the UTF-8 command to 32,768 bytes, and
limits the timeout to 1 through 1,800,000 milliseconds. Output is canonical padded Base64 in chunks
of at most 12,000 decoded bytes. The host retains only the newest 48,000 bytes per request while
continuing to drain frames through exit.

The complete message shapes and field bounds are in
[`schemas/protocol-v1.schema.json`](schemas/protocol-v1.schema.json).

## Security boundary

Protocol v1 carries no Android path, file descriptor number, content URI, provider credential,
OAuth token, API key, arbitrary mount, network destination, or package-download authority.
