# Ping Up wire protocol, version 1

This document describes protocol version 1 as implemented on `main` at `3a5e4c6`. It is written for an external review and for anyone building a second implementation (an iOS reporting leaf, a hardware relay). It describes what the code does today, including the parts that are known to be weak; those are collected in section 14.

The code is the source of truth. Paths below are relative to `core/src/main/kotlin/ro/safetyplease/core/` unless they start with `app/`:

- `protocol/` (`Packet.kt`, `Wire.kt`, `Payloads.kt`, `Reassembler.kt`): the byte formats;
- `crypto/` (`Crypto.kt`, `Identity.kt`, `StaffCrypto.kt`, `QrCodes.kt`): keys, sealing, signatures, QR codes;
- `mesh/MeshEngine.kt`, `mesh/IncidentCache.kt`, `mesh/SendQueue.kt`: what a node accepts, stores and relays;
- `incidents/IncidentManager.kt`, `chat/ChatManager.kt`: what the endpoints do with the payloads;
- `app/src/main/java/ro/safetyplease/app/platform/ble/`: the Bluetooth transport.

If this document and the code disagree, the document has a bug. The bytes are pinned by golden vectors (section 13); a change to any of them is a change of the wire format.

## 1. Conventions

- Integers are big-endian. `u8`, `u16`, `u32` are unsigned; `i32` and `i64` are two's complement.
- Strings are UTF-8 with a `u8` or `u16` byte count in front and no terminator. Every limit is in bytes. Decoders do not validate UTF-8 (open question 4).
- Coordinates are `i32` micro-degrees, `round(degrees × 10^6)`, latitude first. Resolution is about 0.11 m. Nothing checks the range.
- Timestamps are `u32` seconds since the Unix epoch, from the sender's wall clock. Nothing validates them.
- A decoder "rejects" input by returning `null` (the `Reassembler` returns `Invalid`). Internally the readers throw `MalformedException`, which `parseOrNull` turns into `null`; no other exception may escape a decoder (section 13 tests this). Encoders throw `IllegalArgumentException` when a field is over its limit; that only happens on the sending side.
- There is no compression anywhere in the protocol. This is a security decision: a compressed payload is how the Bridgefy "zip bomb" took a whole network down (ePrint 2021/214).

## 2. Transport: BLE GATT (informative)

Source: `app/.../platform/ble/BleConstants.kt`, `BleRadio.kt`. This lives outside `:core`, but a second implementation has to match it.

- Service `f24a82e1-f0bc-471f-9cae-a9598a2a4dc4` with one characteristic `c5d82591-cfb9-45dd-bea5-8447e1811ba6` (write without response, notify) and its CCCD.
- Every node runs the GATT server and also connects to others as a central. The central requests MTU 517 and enables notifications. The server counts the link as up once notifications are enabled. Central to server: write without response. Server to central: notification. One frame per ATT value.
- `maxFrame = MTU − 3`. A link with `maxFrame < 64` (`PacketCodec.MIN_FRAME`) is refused; an unnegotiated MTU of 23 gives 20 and is refused.
- Both ends send HELLO as soon as the link is up.
- An empty notification from the server means the server is closing the link.
- Advertising: the service UUID goes in the advertisement. Six bytes of service data under the same UUID go in the scan response (legacy set) or in the advertisement itself (Coded PHY set, where the phone supports it):

  | Offset | Size | Field |
  |---|---|---|
  | 0 | 1 | advertising version, 1 |
  | 1 | 1 | `NodeFlags` (section 6.1) |
  | 2 | 4 | prefix: the first 4 bytes of the nodeId |

  A scanner that finds no service data, fewer than 6 bytes or a version other than 1 assumes `flags = ACCEPTS_CONNECTIONS` and an unknown prefix, and may still connect.

## 3. Frame

Every frame starts with a fixed 26-byte header (`PacketCodec`, `protocol/Packet.kt`).

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `version` | 1 |
| 1 | 1 | `type` | section 5 |
| 2 | 1 | `ttl` | 1..7 |
| 3 | 1 | `flags` | below |
| 4 | 8 | `id` | `i64`, random per packet |
| 12 | 8 | `sender` | nodeId of the origin; 0 for anonymous packets |
| 20 | 4 | `timestamp` | `u32` seconds |
| 24 | 2 | `length` | payload bytes in this frame, at most 480 |
| 26 | 8 | `recipient` | only if flag R |
| 26 or 34 | 4 | `fragId u16`, `index u8`, `count u8` | only if flag F |
| then | `length` | payload | |

Header flags:

| Bit | Name | Meaning |
|---|---|---|
| `0x01` | R | `recipient` is present |
| `0x02` | F | this frame is a fragment |
| `0x04` | E | the payload is encrypted. A hint only: nothing authenticates it, but PRIVATE must carry it |
| `0x08`..`0x80` | | reserved; a frame with any of them is rejected |

`PacketCodec.decode` accepts a frame only if, in this order: it has at least 26 bytes; `version == 1`; `type` is one of the eight in section 5; `1 ≤ ttl ≤ 7`; no reserved flag is set; `length ≤ 480`; for a fragment, `count ≥ 2`, `index < count` and `length ≥ 1`; and the frame ends exactly after `length` payload bytes. The encoding is canonical: a decoded unfragmented frame re-encodes to the same bytes.

Sizes: the header is 26 bytes, 34 with a recipient, plus 4 for a fragment. The largest unfragmented frame is 26 + 8 + 480 = 514 bytes, which fills an MTU of 517.

The codec accepts R and E on any type. Which types use them, and what receivers enforce, is in sections 5 and 7.

## 4. Fragmentation and reassembly

Fragmentation is per link. A node that receives fragments reassembles the packet, and fragments it again for each link it relays on, according to that link's `maxFrame`.

Splitting (`PacketCodec.toFrames`): `chunk = maxFrame − 26 − (8 if R) − 4` and `count = ceil(payload / chunk)`. Every fragment carries the full header of the packet (same `type`, `ttl`, `id`, `sender`, `timestamp`, `recipient`, E), its own `length` (the size of its chunk), `index` from 0, and the same `fragId`. `fragId` is a per-link `u16` counter that wraps.

Frames for a 480-byte payload:

| `maxFrame` | With recipient | Without recipient |
|---|---|---|
| 514 (MTU 517) | 1 | 1 |
| 182 (MTU 185, typical on iPhone) | 4 (chunks of 144) | 4 (chunks of 152) |
| 64 (minimum) | 19 (chunks of 26) | 15 (chunks of 34) |

Reassembly (`protocol/Reassembler.kt`), one instance per link:

- At most 8 sets in progress, keyed by `fragId`. A ninth set evicts the oldest.
- A set expires 30 s after its first fragment. Expiry is checked when a fragment arrives.
- Every fragment of a set must have the same `count` and the same header, `ttl` included. A mismatch drops the whole set and returns `Invalid`.
- A repeated `index` is ignored.
- If the fragments add up to more than 480 bytes, the set is dropped and the result is `Invalid`.
- When all `count` indices have arrived, the payload is their concatenation in index order. Nothing requires the non-final chunks to be full.
- An unfragmented frame skips reassembly and leaves the pending sets alone.

So no object larger than 480 bytes can travel. Fragmentation has only run on emulators, which negotiate MTU 517; it has not been exercised on real Bluetooth.

## 5. Packet types

| Type | Name | Reach | Origin sets | Payload | Send priority | Store-and-forward |
|---|---|---|---|---|---|---|
| `0x01` | HELLO | one link | `ttl 1`, `sender` = own nodeId | 6.1 | 0 (first) | no |
| `0x02` | SUMMARY | one link | `ttl 1`, `sender` = own nodeId | 6.2 | 4 | no |
| `0x03` | REQUEST | one link | `ttl 1`, `sender` = own nodeId | 6.2 | 4 | no |
| `0x10` | INCIDENT_REPORT | flood | `sender 0`, E | 6.3 | 1 | yes |
| `0x11` | INCIDENT_ACK | flood | `sender` = the staff phone's nodeId | 6.4 | 2 | yes |
| `0x12` | INCIDENT_CANCEL | flood | `sender 0` | 6.5 | 2 | no; the author resends it |
| `0x20` | PRIVATE | flood (unicast is a flood) | `sender` = own nodeId, R, E | 6.6 | 3 | no |
| `0x7F` | TEST | flood | anything | opaque | 4 | no; debug builds only |

Flooded packets start with `ttl 7`, a random `id ≠ 0` and `timestamp = wall clock / 1000`. HELLO, SUMMARY and REQUEST are never relayed. "Send priority" is the level in the per-link send queue (`mesh/SendQueue.kt`, 128 packets). A lower level is sent first. When the queue is full, a new packet pushes out the newest packet of the least important level below its own, or is refused if there is none.

## 6. Payloads

### 6.1 HELLO (`Hello`)

| Size | Field | Rule |
|---|---|---|
| 1 | `version` | the engine requires 1; the codec doesn't check it |
| 8 | `nodeId` | not 0, and equal to the header `sender` (checked by the engine) |
| 1 | `flags` | `NodeFlags` |
| 1 | `nickLen` | at most 24 |
| `nickLen` | nickname | sent only by debug builds; release sends `nickLen 0` |

11 to 35 bytes. Nothing may follow the nickname.

`NodeFlags` (`protocol/Payloads.kt`): `0x01` STAFF, `0x02` ANCHOR, `0x04` PENDING_INCIDENT (one of this phone's reports has no ACK yet), `0x08` ACCEPTS_CONNECTIONS (inbound slots free). Bits `0x10`..`0x80` are unassigned and ignored, not rejected, both in HELLO and in advertising. The roles are self-declared; nothing authenticates them.

Rules (`MeshEngine.onHello`): each end sends HELLO first on a new link. Until the peer's HELLO arrives, its other packets are dropped; without a HELLO within 10 s the link is closed. A later HELLO on the same link may change the flags and the nickname, but a different nodeId counts as malformed. A HELLO carrying the receiver's own nodeId, or a banned one, closes the link. Two links to the same peer: both ends keep the one initiated by the lower nodeId (unsigned comparison).

### 6.2 SUMMARY and REQUEST (`SummaryCodec`, `RequestCodec`)

| Size | Field |
|---|---|
| 1 | `count`, at most 50 |
| `count` × 9 | `id8 i64`, `state u8` |

`id8` is the first 8 bytes of an `incidentId` read as `i64`. At most 451 bytes; nothing may follow the entries.

- SUMMARY `state`: bit `0x80` = "I hold the report"; bits 0..6 = the ACK status I hold (0..4, section 8). Status values above 4 are rejected.
- REQUEST `state`: `0x80` = send me the report, `0x01` = send me the ACK. Any other bit is rejected.

A node sends SUMMARY after the peer's HELLO and then every 60 s on each ready link, only when it has something to list: the 50 incidents it stored most recently. On a SUMMARY it requests the report if the peer holds it, neither side knows a CANCELLED ACK, and it has never seen that report; and the ACK if the peer's status is higher than its own. On a REQUEST it sends the stored packets on that link only, as relays.

### 6.3 INCIDENT_REPORT (`IncidentReportCodec`, `IncidentBody`)

| Size | Field |
|---|---|
| 16 | `incidentId`, random |
| rest | sealed body: `crypto_box_seal(IncidentBody)` to the staff box key, 48 bytes of overhead (section 9) |

Relays cannot open the body. They check only that the payload is longer than 64 bytes (16 + 48). At most 16 + 391 + 48 = 455 bytes.

`IncidentBody`, the plaintext only staff can read:

| Size | Field | Rule |
|---|---|---|
| 1 | `category` | 1..7 |
| 1 | `severity` | 1..3 |
| 4 | `timestamp` | reporter's clock |
| 1 | `flags` | `0x01` coordinates, `0x02` nickname, `0x04` cancel hash; other bits rejected |
| 1 | `zoneLen` | at most 32 |
| `zoneLen` | zone | free text, may be empty |
| 4 + 4 | `lat i32`, `lon i32` | only if `0x01` |
| 2 | `descLen` (`u16`) | at most 300 |
| `descLen` | description | the app also limits it to 140 characters |
| 1 | `nickLen` | only if `0x02`; at most 24 |
| `nickLen` | nickname | only if `0x02`; may be empty; absent means anonymous |
| 16 | `cancelHash` | only if `0x04`; section 8 |

At most 391 bytes. Nothing may follow.

Categories: 1 medical, 2 violence, 3 lost person, 4 harassment, 5 crowd, 6 fire, 7 other. Severity: 1 low, 2 medium, 3 urgent.

### 6.4 INCIDENT_ACK (`IncidentAck`)

| Size | Field | Rule |
|---|---|---|
| 16 | `incidentId` | |
| 1 | `status` | 1..4 |
| 4 | `timestamp` | staff phone's clock |
| 1 | `teamLen` | at most 24 |
| `teamLen` | team name | from the staff QR |
| 64 | `signature` | Ed25519 |

At most 110 bytes. Nothing may follow.

The signature is `crypto_sign_detached` with the staff signing key over the ASCII domain `SP-ACK-v1` (9 bytes) followed by every payload byte before the signature. The verifier uses the bytes as received, not a re-encoding of the decoded fields. Every relay verifies the signature before it stores or relays an ACK.

### 6.5 INCIDENT_CANCEL (`IncidentCancel`)

| Size | Field |
|---|---|
| 16 | `incidentId` |
| 16 | `token`, random per report, known only to the author |

Exactly 32 bytes; relays check only the size.

### 6.6 PRIVATE (`InnerCodec`)

| Size | Field |
|---|---|
| 24 | nonce, random per packet |
| rest | `crypto_box_easy(inner, nonce, recipient box key, sender box key)`: 16-byte MAC, then the ciphertext |

The overhead is 40 bytes. Relays check only that R is set, `sender ≠ 0` and E is set.

The plaintext (`Inner`) starts with `type u8` and `msgId i64`. `msgId` identifies the message across retries and group fan-out.

| Type | Name | After `type` and `msgId` |
|---|---|---|
| 1 | TEXT | `flags u8` (`0x01` group), `[groupId i64]`, text: all remaining bytes, at most 400 |
| 2 | QUICK | `flags u8` (`0x01` group), `[groupId i64]`, `code u8` (1..4) |
| 3 | ZONE | `flags u8` (`0x01` group, `0x02` coordinates), `[groupId i64]`, `zoneLen u8` (≤ 32), zone, `[lat i32, lon i32]` |
| 4 | PING | nothing |
| 5 | PONG | `pingId i64` |
| 6 | DELIVERED | `deliveredId i64` (the `msgId` being confirmed) |
| 7 | GROUP_INVITE | `groupId i64`, `nameLen u8` (≤ 32), name, `count u8` (≤ 6), then `count` × (`nodeId i64`, `boxKey` 32 bytes, `nickLen u8` (≤ 16), nickname) |

A flag outside the ones listed for the type is rejected, and nothing may follow the last field. Quick codes: 1 where are you, 2 coming, 3 low battery, 4 meet at the meeting point. Largest plaintexts: TEXT 418 bytes (458 once boxed), GROUP_INVITE 393 (433), ZONE 59 (99), QUICK 19, PONG and DELIVERED 17, PING 9.

What the recipient does (`ChatManager`): it opens the box with the key of the header `sender`, looked up among its friends and group members; anything else is dropped silently. An unknown inner type fails to decode and is dropped silently too: relays never see it, and the recipient doesn't count it as malformed. TEXT, QUICK and ZONE are accepted from friends, or from members of the named group, and each copy, duplicates included, is answered with DELIVERED. Invites are confirmed with DELIVERED too. A PING from a friend gets a PONG. A group of at most 8 members, creator included, is sent pairwise: one PRIVATE per member, all with the same `msgId`. An invite lists at most 6 members, because the inviter and the invitee are implicit; it is accepted only from a friend.

### 6.7 TEST

An opaque payload of at most 480 bytes, used by the debug demo. Release builds drop it on receipt, after decoding and rate limiting, without counting it as malformed.

## 7. Receiving and relaying

The receive path of `MeshEngine.onFrame`, in order:

1. Decode the frame (section 3); on failure, malformed.
2. Reassemble (section 4); `Invalid` is malformed, `Pending` waits.
3. Per-link receive rate limit: a burst of 30, then 3 packets per second. The excess is dropped.
4. TEST is dropped unless the build allows it.
5. HELLO is handled (section 6.1). Other packets from a link without HELLO are dropped.
6. SUMMARY and REQUEST are handled on the link (section 6.2) and go no further.
7. Every other packet: dedup; the per-type check below; delivery to the app if there is no recipient or the recipient is this node; relay if it isn't addressed to this node and `ttl > 1`.

Per-type checks, at every hop:

| Type | Check | On failure |
|---|---|---|
| INCIDENT_REPORT | payload longer than 64 bytes; then stored if the `incidentId` is new | malformed; a known `incidentId` is dropped and not relayed |
| INCIDENT_ACK | decodes and the signature verifies; then stored if it supersedes the stored ACK (section 8) | malformed; an ACK that doesn't supersede is dropped and not relayed |
| INCIDENT_CANCEL | payload exactly 32 bytes | malformed |
| PRIVATE | R set, `sender ≠ 0`, E set | malformed |

Nobody checks that a report or a cancel has `sender = 0`, or that a report has E set.

Relaying: after a random delay of 50..250 ms, the packet goes with `ttl − 1` to every ready link except the one it came from. A node never relays a packet addressed to itself. The send side paces itself to 2.8 packets per second per link (burst 28) and at least 50 ms between packets, just under the neighbour's receive limit.

Hops: a receiver computes `hops = 8 − ttl`, so a direct neighbour sees 1.

Store-and-forward: a stored report or ACK keeps `max(ttl − 1, 1)` and is served with that `ttl` when a neighbour requests it; the origin stores its own report or ACK with `ttl 7`. The TTL therefore limits only the live flood. Through SUMMARY and REQUEST a report can travel any number of hops. Reports and ACKs are kept 30 minutes (60 on anchors), at most 50 reports per node, in memory only.

Dedup key (`Packet.dedupKey`): the first 8 bytes, as `i64`, of SHA-256 over

```
type u8 | E u8 (0 or 1) | id i64 | sender i64 | timestamp u32 | recipient i64 (0 if absent) | has recipient i64 (0 or 1) | payload
```

It covers everything except `ttl`, so a node can't block a packet by sending garbage with the same `id` first. Keys are kept 15 minutes, 4,096 at most. The origin records the key of its own packets, so echoes are dropped. Dedup runs after the rate limit and before the per-type checks. The key never goes on the wire, so changing it is not a wire change; it is in the golden vectors only for reference.

## 8. Incidents and the ACK chain

- The reporter draws a random 16-byte `incidentId` and a random 16-byte cancel token, puts `cancelHash = SHA-256("SP-CANCEL-v1" ‖ token)`, first 16 bytes, into the body, seals the body and floods the report with `sender 0`.
- The first staff phone that opens a report sends RECEIVED by itself, unless an ACK for it is already known. Staff then send ACKNOWLEDGED and RESOLVED by hand.
- ACK status values: 0 NONE (only in SUMMARY), 1 RECEIVED, 2 ACKNOWLEDGED, 3 RESOLVED, 4 CANCELLED.
- Supersede order (`IncidentCache.isBetter`): a higher status wins; for equal status the older `timestamp` wins; for equal timestamps the smaller team name wins (Kotlin string order, by UTF-16 code units). The status therefore never goes down, and a RESOLVED incident cannot be reopened.
- Cancelling: the author floods INCIDENT_CANCEL with `sender 0`, again every 60 s and after each new link, for up to 30 minutes or until the status reaches RESOLVED or CANCELLED. A staff phone that holds the report checks `SHA-256("SP-CANCEL-v1" ‖ token)[0..16] == cancelHash` and that the status is below RESOLVED, then signs a CANCELLED ACK. A cancel that arrives before its report is kept for 60 minutes (64 at most). A body without `cancelHash` cannot be cancelled.
- Relays can verify ACKs and nothing else about an incident.

## 9. Keys and cryptography

All cryptography is libsodium (lazysodium on Android, lazysodium-java in the JVM tests).

| What | Algorithm | Key |
|---|---|---|
| Report body | `crypto_box_seal`: ephemeral X25519, XSalsa20-Poly1305 | staff box public key |
| ACK | `crypto_sign_detached`, Ed25519, domain `SP-ACK-v1` | staff signing key |
| Cancel | SHA-256 over `SP-CANCEL-v1` ‖ token, first 16 bytes | none |
| PRIVATE | `crypto_box_easy`: static X25519 on both sides, XSalsa20-Poly1305, random 24-byte nonce | sender's box secret key, recipient's box public key |

- **Node identity** (`crypto/Identity.kt`): an X25519 pair and an Ed25519 pair, random at first start. `nodeId` = the first 8 bytes of SHA-256(Ed25519 public key ‖ X25519 public key), read as `i64`. 0 is reserved for anonymous packets; an identity that hashes to 0 is generated again. The advertised prefix is the first 4 bytes of the nodeId. The Ed25519 identity key is not used by the protocol today; it only feeds the nodeId and the friend QR.
- **Event keys** (`crypto/StaffCrypto.kt`): one X25519 pair (opens reports) and one Ed25519 pair (signs ACKs) per build. The public keys ship in `app/src/main/assets/staff_public.json` as `{"box": hex, "sign": hex}`. The secret keys come from the two 32-byte seeds in the staff QR (section 11): `crypto_box_seed_keypair(boxSeed)`, whose secret key is the first 32 bytes of SHA-512(seed), and `crypto_sign_seed_keypair(signSeed)`. A phone accepts a staff QR only if the derived public keys match the bundled ones. The same seeds go to every team and every anchor.
- **Sealed box** layout: `ephemeral public key (32) ‖ MAC (16) ‖ ciphertext`, with nonce = BLAKE2b-192(ephemeral public key ‖ staff public key). Overhead 48 bytes. It hides the reporter: there is no sender key to check.
  - Encrypted: the whole `IncidentBody` (category, severity, time, zone, coordinates, description, nickname, cancel hash).
  - In clear: the header (`sender 0`, `id`, `timestamp`, `ttl`) and the `incidentId`.
- **ACK signature**: section 6.4. It covers the payload, not the packet header.
- **Chat**: a successful `crypto_box_open_easy` with the claimed sender's key authenticates the sender to the recipient only. A third party can't verify it, and the recipient could forge it, so it proves nothing to anyone else. There is no forward secrecy. Header fields are not authenticated. Replays are caught only by the `msgId` history of the last 2,000 messages.

## 10. Malformed input and bans

A link collects a "malformed" mark for: a frame that doesn't decode; an `Invalid` fragment set; a bad HELLO (payload doesn't decode, version ≠ 1, nodeId ≠ header `sender`, or a different nodeId on the same link); a SUMMARY or REQUEST that doesn't decode; a report of 64 bytes or less; an ACK that doesn't decode or doesn't verify; a cancel that isn't 32 bytes; a PRIVATE without R, `sender` or E.

Five marks within 60 s ban the peer's nodeId (if it sent HELLO) and its Bluetooth address for 10 minutes and close the link (`MeshEngine.malformed`).

Silent drops, which don't count: rate limit, TEST in release, before HELLO, a demo-muted peer, duplicate, known incident, an ACK that doesn't supersede.

Consequence: a new version, packet type or header flag, or a new field in a payload that relays parse (HELLO, SUMMARY, REQUEST, ACK, the size of CANCEL), is malformed to a v1 node, so a node that sends five of them within a minute gets banned by its v1 neighbours. A new field in the sealed report body is invisible to relays, but v1 staff phones fail to decode the body and drop the report silently, so staff phones would have to be updated first. Section 14 lists what can change without breaking v1.

## 11. QR codes (`crypto/QrCodes.kt`)

Both formats are a prefix followed by the payload in base64url without padding. The decoder trims whitespace, refuses text longer than 512 characters and also accepts padding. The prefix carries the format version.

`SPF1.` friend card, at most 124 characters:

| Size | Field |
|---|---|
| 32 | X25519 public key |
| 32 | Ed25519 public key |
| 1 | `nickLen`, at most 24 (the encoder cuts longer names on a character boundary) |
| `nickLen` | nickname |

`SPS1.` staff card, at most 169 characters. It contains secret seeds. `tools/gen_staff_keys.py` writes the same format:

| Size | Field |
|---|---|
| 32 | `boxSeed` |
| 32 | `signSeed` |
| 1 | role: 1 staff, 2 anchor |
| 1 + n | team name, `u8` length, at most 24 |
| 1 + n | zone, `u8` length, at most 32 |

## 12. Limits

| Limit | Value | Where |
|---|---|---|
| Payload after reassembly | 480 B | `PacketCodec.MAX_PAYLOAD` |
| Smallest usable frame | 64 B | `PacketCodec.MIN_FRAME` |
| TTL at the origin, and maximum | 7 | `PacketCodec.MAX_TTL` |
| Fragments per packet | 2..255 (`count u8`); 19 at most in practice | `PacketCodec` |
| Fragment sets per link, timeout | 8, 30 s | `Reassembler` |
| SUMMARY / REQUEST entries | 50 | `Limits.SUMMARY_ENTRIES` |
| Nickname | 24 B | `Limits.NICK_BYTES` |
| Zone | 32 B | `Limits.ZONE_BYTES` |
| Description | 300 B and 140 characters | `Limits.DESCRIPTION_BYTES`, `DESCRIPTION_CHARS` |
| Team name | 24 B | `Limits.TEAM_BYTES` |
| Chat text | 400 B | `Limits.TEXT_BYTES` |
| Group name, group nickname | 32 B, 16 B | `Limits.GROUP_NAME_BYTES`, `GROUP_NICK_BYTES` |
| Group size | 8, creator included | `Limits.GROUP_MAX_MEMBERS` |
| Largest report payload | 455 B (25 B to spare) | section 6.3 |
| Largest TEXT payload | 458 B (22 B to spare) | section 6.6 |
| Receive / send rate per link | 3/s burst 30; 2.8/s burst 28 | `MeshConfig` |
| Dedup | 4,096 keys, 15 min | `DedupCache` |
| Bans | 5 malformed in 60 s, 10 min | `MeshConfig` |

## 13. Golden vectors and fuzzing

**Golden vectors.** `core/src/test/resources/golden/protocol-v1.txt` holds one line per vector, `name value`. Values are lowercase hex, except the `qr.*` vectors, which are the QR text. The comment above each vector lists its inputs, so the file can be used without reading Kotlin. It covers the derived keys and nodeIds, whole frames (with and without recipient, and three fragments), HELLO, SUMMARY, REQUEST, two report bodies, the cancel hash, a sealed report, the signed ACK bytes and the ACK, the cancel, all seven inner types, a boxed PRIVATE payload, and both QR formats.

Inputs are deterministic. Seeds are 32 consecutive byte values (`seed(0x10)` = `10 11 … 2f`): the staff keys come from `seed(0x10)` and `seed(0x30)`, alice from `seed(0x50)` and `seed(0x70)`, bob from `seed(0x90)` and `seed(0xb0)`. Ed25519 signatures are deterministic. A real sealed box uses a random ephemeral key, so the vector builds `crypto_box_seal` step by step with the ephemeral pair from `seed(0xd0)`. Implementations should check that they open it, not that they produce it. The `crypto_box` nonce is fixed to `e0 e1 … f7` by overriding lazysodium's `randomBytesBuf`, which is the only randomness `SodiumCrypto.box` takes from Java. When the file was created, the key, nodeId, hash, dedup, sealing, signature, box and QR vectors were also checked against PyNaCl 1.6.2 and Python's `hashlib`.

`GoldenVectorsTest` checks every vector both ways: the encoder must produce the stored bytes, and decoding the stored bytes (opening, verifying) must give back the inputs. It also checks that the file holds exactly the vectors the test knows.

**Changing a vector.** Any change to these bytes, other than `dedup.private`, is a wire change. It needs a new protocol version or a compatibility plan (section 14) and a note with the reason in the project log. To regenerate after an intended change, run

```
.\gradlew.bat :core:test --tests ro.safetyplease.core.protocol.GoldenVectorsTest
```

`encodersProduceTheStoredBytes` then fails and lists the current value of every changed vector, already in the file's format; copy those lines into the file and update the comments if an input changed.

**Fuzzing.** `DecoderFuzzTest` uses `kotlin.random.Random` with a fixed seed and no extra library, and runs in under 3 seconds. Every test has a 10-second timeout. For `PacketCodec.decode`, all payload decoders, the `Reassembler` and both QR decoders, it feeds valid encodings of random objects and mutations of them: random bytes, bit flips, truncation, trailing bytes, boundary values in single bytes (most often a length or a count), cut and inserted ranges, and fragments forged with arbitrary counters and lengths. It checks that:

- nothing throws;
- every call allocates less than 32 KiB, which catches a buffer sized from an untrusted length;
- a successful decode re-encodes to the same bytes, unless a string came back with U+FFFD (open question 4);
- the `Reassembler` never holds more than 8 sets or returns more than 480 bytes, and rebuilds any packet from its frames in any order, with repeats;
- any change to a sealed report, a boxed PRIVATE payload or a signed ACK makes opening or verifying fail.

## 14. Known limits and open questions

1. **No forward compatibility.** An unknown version, type or header flag, or an extra byte in a payload that relays parse, is malformed and leads to a ban (section 10); the HELLO payload can't be extended either. An unknown body flag or inner flag makes the endpoint drop the message silently. Version 1.1 has to make unknown types and flags a silent drop or an opaque relay before any new feature goes on the wire. What can change today without breaking v1 nodes: new `Inner` types, which only the recipient sees; NodeFlags bits `0x10`..`0x80`, which v1 ignores; and a PRIVATE addressed to a "channel" id instead of a nodeId, which v1 relays without store-and-forward.
2. **The header is not authenticated.** `sender`, `timestamp`, `recipient`, `ttl` and the E flag can be changed by anyone on the path. A PRIVATE is still checked end to end by the box, and an ACK by its signature, but neither covers the header.
3. **No freshness.** Timestamps aren't checked against the receiver's clock. A node remembers an incident for 6 hours after it last saw it (`IncidentCache`); a report or ACK replayed after that is accepted as new. The ACK order prefers the older timestamp, so at equal status a phone whose clock runs behind wins. `u32` seconds overflow in 2106.
4. **Strings are not validated as UTF-8.** A malformed sequence decodes to U+FFFD, so a decoded string can re-encode to more bytes than its limit and make the encoder throw. No receiver re-encodes a received string today, so this is latent. Should decoders reject invalid UTF-8?
5. **Coordinates are not range-checked** on decode; a body can carry a latitude of ±2147°.
6. **The `incidentId` is not bound to the sealed body.** Anyone can wrap a captured sealed body under a new `incidentId`, and staff would see it as a new report with the same content and the same cancel hash.
7. **One shared staff key per build, no revocation.** Every holder of the staff QR can read every report, past ones included (a sealed box has no forward secrecy), and can sign any ACK for any team; the team name is free text from the QR. An anchor's QR holds the same seeds.
8. **Reports and cancels can't be checked by relays**, and relays don't enforce `sender = 0` on them. Any node can fill a neighbour's report cache (50) with sealed garbage and push real reports out.
9. **Anonymity is partial.** The first neighbour receives `ttl 7` and knows the sender is the origin. The advertising prefix is static, and PENDING_INCIDENT is broadcast while a report waits for an ACK, so a nearby observer can see who just reported and follow them by signal strength.
10. **HELLO is not authenticated.** A node can claim another's nodeId and get it banned by sending five bad frames ("ban framing"). The STAFF and ANCHOR roles are self-declared, but connection policy prefers them.
11. **Fragment sets are easy to disturb** for a peer on the same link: it can evict the 8 sets or reuse a `fragId` with a different header and invalidate one. It only affects that link.
12. **480 bytes is the ceiling.** Photos and event packs need a new transfer mechanism. The report has 25 bytes to spare and TEXT 22, so a 64-byte signature on a chat message doesn't fit without shortening the text.
13. **The codec allows R on every type.** Only PRIVATE uses it. A report with R set would be stored and served by SUMMARY/REQUEST but delivered only to the named node. Should v1.1 reject it?
14. **The dedup key is truncated to 64 bits.** Collisions are negligible at 4,096 entries, but a key is not a secure identifier.
15. **Chat messages carry no send time.** The displayed time is the arrival time, and a replay within the history is answered with a fresh DELIVERED.
16. **Prefix collisions.** Two phones with the same 32-bit prefix look like one peer to the connection policy, and a ban on one blocks the other. About 0.3 % at 5,000 phones.
