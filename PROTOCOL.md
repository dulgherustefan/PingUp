# Ping Up wire protocol, versions 1.0 and 1.1

This document describes protocol 1.1 as implemented on `main`, and 1.0, which 1.1 phones still meet in the field. It is written for an external review and for anyone building a second implementation (an iOS reporting leaf, a hardware relay). It describes what the code does today, including the parts that are known to be weak; those are collected in section 14.

1.1 keeps the 1.0 header and every 1.0 byte: a 1.0 phone and a 1.1 phone talk without either counting the other's packets as malformed. What 1.1 adds is tolerance for what newer versions will send, SUMMARY2 and REQUEST2, a reserved type for organiser announcements, a TTL table per type, a per-link limit on new reports, a rotating advertised prefix told in HELLO, bans on the link instead of the claimed nodeId, and an optional chat fan-out, off by default, that passes chat on a few links at each hop instead of all; and, through the extension points this tolerance opens, field states and closing by the control room, reopening (the epoch), triage answers, staff reports, a separate category for a lost friend, and the accuracy of the reporter's fix with the nearest landmark; short sessions between anchors and the phones around them, with a slot each phone keeps for them; anchor heartbeats to the control console on a control channel; and, announced by a capability bit of its own (`HELLO_AUTH`), a key chain: shift certificates proven in HELLO, ACKs signed one by one under them, revocations in the reserved announcement type and a report key per day; in the same type, the control console's notices (alert, heat, schedule and transport changes), stored and forwarded as a SUMMARY2 kind of their own; and, as two new flooded types with store-and-forward of their own, the control console's quick responses to a reporter and the reporter's updates of its incident. Where the two versions differ, the sections below say so; section 15 sums up the differences and the rules a newer version has to follow.

The code is the source of truth. Paths below are relative to `core/src/main/kotlin/ro/safetyplease/core/` unless they start with `app/`:

- `protocol/` (`Packet.kt`, `Wire.kt`, `Payloads.kt`, `Reassembler.kt`, `Compat.kt`): the byte formats, and what may go to a 1.0 peer;
- `crypto/` (`Crypto.kt`, `Identity.kt`, `StaffCrypto.kt`, `QrCodes.kt`, `Certificate.kt`, `KeyChain.kt`, `Revocation.kt`, `Revocations.kt`): keys, sealing, signatures, certificates and revocations, QR codes;
- `announce/` (`Notice.kt`, `Notices.kt`, `NoticeTemplates.kt`): the console's notices and their texts;
- `event/` (`EventPack.kt`, `EventShelf.kt`): the signed event pack and the exercises (section 16);
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
- "Skipped" (1.1) means a decoder reads past something a newer version may add, without rejecting the input and without keeping it, unless the section says it is kept.
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
  | 2 | 4 | prefix: 4 random bytes that change about every 15 minutes (1.0: the first 4 bytes of the nodeId) |

  A scanner that finds no service data, fewer than 6 bytes or a version other than 1 assumes `flags = ACCEPTS_CONNECTIONS` and an unknown prefix. A later sighting of the same address without service data keeps the flags and prefix of the last one that had it.

  A 1.1 node sets `PROTOCOL_1_1` (`0x10`) in these flags, as in HELLO; what it sends on a link depends on the peer's HELLO, not on the advertising. `ANCHOR_SLOT` (`0x80`, 1.1) is only advertised, never sent in HELLO.

- **Rotating prefix** (`mesh/AdvertisedPrefix.kt`, `PREFIX_IN_HELLO`, 1.1). The static nodeId prefix let a scanner follow a phone across the MAC address rotations all day. A current node advertises a random prefix instead, draws a new one every 12 to 18 minutes (15 ± a fifth, so that neighbours don't change at once) and whenever the radio comes back on, and restarts the advertising set with each new prefix, so the address changes with it (`BleRadio`). It tells the new prefix in HELLO (section 6.1) to the peers that set `PREFIX_IN_HELLO`, before it advertises it, so they don't take it for a new peer. An anchor keeps the nodeId prefix: it is a fixed device, and the control console notices a quiet anchor by it. When the radio has to start a new set for a reason of its own (a change of flags or interval it couldn't make in place, a set the stack dropped), it first asks the engine for a new prefix (`RadioEvent.PrefixWanted`, `NewSetPrefix`) and keeps the old set on the air until it has it, so no two addresses show the same prefix; only an anchor's fixed prefix starts again as it is. Anyone who links to a phone still reads its nodeId in HELLO.
- **Which end connects** (`mesh/ConnectionPolicy.kt`, informative). Each node connects as a central to the advertisers it chooses, one connection at a time. To keep two nodes from connecting to each other at once, the one with the lower advertised prefix connects (unsigned comparison of the two prefixes, both random and rotating in 1.1), and the other only after it has seen the first for 20 s without a link. A node that can't advertise connects to anyone. A peer that doesn't set `PREFIX_IN_HELLO` (1.0, or 1.1 before the extension) knows a link with us only by the address it connected to, so it would connect again to each new prefix of ours, and, if the duplicate rule (section 6.1) then closed its link, over and over. A current node therefore connects to such a peer only when its own nodeId's first 4 bytes are higher than the prefix the peer advertises, which is the peer's nodeId prefix: if the peer connects too, the duplicate rule keeps the peer's link. Otherwise the peer connects. Since such a peer knows our link only by the address it connected to, it opens a second link to each new prefix of ours, and the duplicate rule closes the older one: one extra connection about every 15 minutes, no more (`Protocol11Test`). A peer without service data is left to connect.
- **Sessions with anchors** (`mesh/ConnectionPolicy.kt`, 1.1, informative). An anchor doesn't hold links with the phones around it: the first few to reach it would keep its slots, and the rest of the crowd would collide on it while setting up GATT. A link between an anchor and a phone that isn't one is a session: connect, HELLO, SUMMARY and REQUEST and what they bring, then both ends close it 5 s after the last packet other than HELLO, either way, or after 60 s in any case. The anchor goes to each phone it sees every 3 minutes, the one it saw least lately first, so a phone with a new report is reached on the next round without saying so in its advertising; it gives the radio 10 s to set up such a link (`Radio.connect`). After each phone that connects to it, it stops advertising ACCEPTS_CONNECTIONS for 2 to 6 s, at random, so that phones that found it together don't connect at once. A phone goes to an anchor by itself only with a report, ACK or cancel of its own since their last session, or when the anchor hasn't come for 6 minutes plus up to one more that differs from phone to phone; a phone that can't advertise, every 3 minutes. A phone has one session at a time, outside its own limits, and advertises `ANCHOR_SLOT` while it has none: an anchor connects on that slot even when the phone doesn't advertise ACCEPTS_CONNECTIONS, and the phone closes a link on the slot whose HELLO isn't an anchor's. Links between anchors, and between phones, are held and rotated as before. In `SimNet` (`AnchorSessionTest`), one anchor reaches each of 50 phones in range every 3 to 4 minutes, and a phone with a new report of its own reaches it within seconds.
- **Roles** (`mesh/ProvenRoles.kt`). Advertised STAFF and ANCHOR are self-declared; a link shows them as verified only once the peer proved them with a certificate in HELLO (section 6.1). STAFF counts for ranking and rotation only once proven: a link whose peer claims it without proof counts as a participant's, and when it closes, the STAFF its prefix advertises is ignored for 30 minutes (`ProvenRoles.UNPROVEN_MS`), so a fake staff phone is preferred at most once. ANCHOR goes by the claim, proven or not: a phone's link with an anchor is a session (above), outside its limits and at most 60 s, so a fake anchor holds none of its slots. A node seeks one link with a role first; once it has one, the others count like any peer, by signal strength. When outgoing slots are full and an unconnected peer is in sight, a node closes its oldest outgoing link idle for 30 s, every 60 s. Of its links with a role it keeps from this only one: the one whose peer most recently sent it a packet that isn't link-local, and only if that was within 5 minutes (`ConnectionPolicy.ROLE_HEARD_MS`). What the node writes itself doesn't count, so a phone that only claims a role and drops everything rotates like any other link and can't hold a neighbour's slot.

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

`PacketCodec.decode` accepts a frame only if, in this order: it has at least 26 bytes; `version == 1`; `type` is one of the eight in section 5 (1.0 only: 1.1 accepts any type); `1 ≤ ttl ≤ 7`; no reserved flag is set; `length ≤ 480`; for a fragment, `count ≥ 2`, `index < count` and `length ≥ 1`; and the frame ends exactly after `length` payload bytes. The encoding is canonical: a decoded unfragmented frame re-encodes to the same bytes.

A frame that fails these checks is malformed (section 10), with two exceptions in 1.1 (`PacketCodec.isForeign`): a frame whose first byte isn't 1, and a version-1 frame of at least 4 bytes with a reserved flag set. Their layout may be a newer version's, so they are dropped and counted as unsupported, not as malformed. 1.1 still sends version 1; it announces itself with `PROTOCOL_1_1` in HELLO (section 6.1), not in the header.

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

| Type | Name | Since | Reach | Origin sets | Origin TTL | Payload | Send priority | Store-and-forward |
|---|---|---|---|---|---|---|---|---|
| `0x01` | HELLO | 1.0 | one link | `sender` = own nodeId | 1 | 6.1 | 0 (first) | no |
| `0x02` | SUMMARY | 1.0 | one link | `sender` = own nodeId | 1 | 6.2 | 3 | no |
| `0x03` | REQUEST | 1.0 | one link | `sender` = own nodeId | 1 | 6.2 | 3 | no |
| `0x04` | SUMMARY2 | 1.1 | one link, to 1.1 peers | `sender` = own nodeId | 1 | 6.2 | 3 | no |
| `0x05` | REQUEST2 | 1.1 | one link, to 1.1 peers | `sender` = own nodeId | 1 | 6.2 | 3 | no |
| `0x10` | INCIDENT_REPORT | 1.0 | flood | `sender 0`, E | 7 | 6.3 | 1 | yes |
| `0x11` | INCIDENT_ACK | 1.0 | flood | `sender` = the staff phone's nodeId | 7 | 6.4 | 2 | yes |
| `0x12` | INCIDENT_CANCEL | 1.0 | flood | `sender 0` | 7 | 6.5 | 2 | no; the author resends it |
| `0x13` | INCIDENT_RESPONSE | 1.1 | flood, to 1.1 peers; one under a certificate only to `HELLO_AUTH` peers | `sender` = the console's nodeId | 7 | 6.10 | 2 | yes, the latest per incident (SUMMARY2 kind RESPONSES) |
| `0x14` | INCIDENT_UPDATE | 1.1 | flood, to 1.1 peers | `sender 0`, E | 7 | 6.11 | 2 | yes, the latest per incident (SUMMARY2 kind UPDATES) |
| `0x20` | PRIVATE | 1.0 | flood (unicast is a flood); in 1.1 optionally some links at each hop, off by default (section 7) | `sender` = own nodeId, R, E | 6 (1.0: 7) | 6.6 | 4 | no |
| `0x30` | ANNOUNCEMENT | 1.1 | flood, to 1.1 peers; at most 3 hops past a relay that can't verify it | `sender` = own nodeId | 7 (before `HELLO_AUTH`: 3) | 6.8 | 1 | a notice: the latest per topic until it expires (SUMMARY2 kind NOTICES, to `HELLO_AUTH` peers); a revocation: no. The console sends both again, every 10 minutes (heat and changes every 30) |
| `0x7F` | TEST | 1.0 | flood | anything | 7 | opaque | 5 | no; debug builds only |
| other | unknown | | flood, to 1.1 peers, at most 3 hops | | 3 | opaque | 5 | no |

Flooded packets start with a random `id ≠ 0`, `timestamp = wall clock / 1000` and the origin TTL of their type (`PacketType.originTtl`, 1.1). In 1.0 every flooded type started at 7; in 1.1 reports, ACKs, cancels and TEST still do. Chat starts at 6 (`PacketType.CHAT_TTL`) and is flooded like the rest: delivery comes first. The chat fan-out of section 7 cuts what a chat message costs the crowd by 63 to 86 % (table below), but it loses messages between friends far apart in a walking crowd, so it stays off (`MeshEngine.CHAT_FAN_OUT`) until a pilot measures both on real radios.

Both were measured with `core/src/test/kotlin/ro/safetyplease/core/ScaleTest.kt`, changing only `CHAT_TTL` and whether relays fan out (`MeshConfig.chatFanOut`). Its simulated radio has no shared airtime and no loss, so these are counts of transmissions, not latency or capacity.

- The crowd: 200 phones placed at random over 100 × 100 m (`Random(7)`), each heard 15 m away; the phone nearest the middle of each quarter is staff. Links form for 60 s before anything is sent.
- Transmissions (`reportsOvertakeAChatBurst`): the other phones pair up with their nearest neighbour in radio range, and in each of the 90 pairs one sends the other a message, all at the same moment; five reports follow 2 s later. Every PRIVATE transmission until 60 s after the reports is counted, delivery receipts included.
- Reach: the same crowd, every phone moving up to 1 m a second in a random direction (`Random(8)`); ten pairs of friends write to each other at once, and the messages delivered within 180 s are counted, of 20. In `twoHundredPhonesScatteredAndWalking` the friends are about 40 m apart, close to three radio ranges; with the defaults the test asks for all 20. The 25 m column, under two ranges, was measured by changing that distance.

| Chat relays | Chat TTL | PRIVATE transmissions in the burst | Change | Friends 25 m apart, walking | Friends 40 m apart, walking |
|---|---|---|---|---|---|
| every link (1.0) | 7 | 78,328 | | 20 of 20 | 20 of 20 |
| every link (1.1, the default) | 6 | 60,734 | −22 % | 20 of 20 | 20 of 20 |
| ⌈log₂ n⌉ links | 7 | 28,764 | −63 % | 20 of 20 | 18 of 20 |
| ⌈log₂ n⌉ links | 5 | 15,996 | −80 % | 19 of 20 | 11 of 20 |
| ⌈log₂ n⌉ links | 4 | 10,682 | −86 % | 20 of 20 | 2 of 20 |

With the fan-out and TTL 4, friends more than about two radio ranges apart rarely got each other's messages until they walked closer; the outbox keeps retrying for 24 hours. In every row all 90 messages of the burst arrived within 60 s. `CHAT_TTL` and `CHAT_FAN_OUT` are the two values to change, together with the test's distance.

Types `0x00` to `0x0F` never leave their link, known or not (`PacketType.isLinkLocal`, 1.1). "Send priority" is the level in the per-link send queue (`mesh/SendQueue.kt`, 128 packets). A lower level is sent first. When the queue is full, a new packet pushes out the newest packet of the least important level below its own, or is refused if there is none. Store-and-forward (SUMMARY and REQUEST) sits above chat, so chat load can't push it out; types this version only relays sit at the bottom, except the one reserved for organiser announcements, which goes with reports.

ANNOUNCEMENT was reserved so that signed organiser announcements could be added later and still cross 1.1 relays at report priority. A node that sets `HELLO_AUTH` reads it (section 6.8): one its key chain verifies is delivered and relayed like a report, with TTL 7; a forgery of a kind it reads (a signature that doesn't verify, or a notice that doesn't parse) goes no further and is dropped as `UNTRUSTED`, without a malformed mark, since an older relay may have passed it on in good faith; any other, and every announcement at a 1.1 relay from before `HELLO_AUTH`, is treated like an unknown type (section 7.1), except that it may carry the whole 480 bytes. Either way each link has a budget of 4 a minute for them: any phone can send an announcement, and a verified one can be sent again under new packet ids, so at report priority they would otherwise crowd out reports.

## 6. Payloads

### 6.1 HELLO (`Hello`)

| Size | Field | Rule |
|---|---|---|
| 1 | `version` | 1. The 1.0 engine requires 1, the 1.1 engine at least 1; the codec doesn't check it |
| 8 | `nodeId` | not 0, and equal to the header `sender` (checked by the engine) |
| 1 | `flags` | `NodeFlags` |
| 1 | `nickLen` | at most 24 |
| `nickLen` | nickname | sent only by debug builds; release sends `nickLen 0` |
| 4 | `prefix` (1.1, optional) | `i32`, the prefix the sender advertises now (section 2); only to a peer whose HELLO set `PREFIX_IN_HELLO`, and read only from a HELLO that sets it |
| 16 | `nonce` (`HELLO_AUTH`, optional) | random, one per link: the sender's challenge to the peer; only after the prefix, to a peer whose HELLO set `HELLO_AUTH` |
| 1 | `authFlags` | with the nonce. `0x01`: a proof follows; other bits are ignored |
| 115..139 | `certificate` | if `0x01`: the sender's shift certificate (section 9) |
| 64 | `proof` | if `0x01`: Ed25519 by the certificate's key over `SP-HELLO-v1` (11 bytes) ‖ the receiver's nonce ‖ the sender's nodeId (`i64`) |

11 to 35 bytes, 39 with the prefix, 56 with the nonce, up to 259 with a proof. In 1.0 nothing may follow the nickname. 1.1 reads 4 bytes after the nickname as the prefix and skips whatever follows them, or fewer than 4 bytes, so a newer version can append fields after the prefix. A node with `HELLO_AUTH` reads the nonce if more than 16 bytes follow the prefix, then the auth flags, and the proof if their bit says so; a proof that doesn't parse, and whatever follows the fields it reads, is skipped.

`NodeFlags` (`protocol/Payloads.kt`): `0x01` STAFF, `0x02` ANCHOR, `0x04` PENDING_INCIDENT (older builds set it while one of their reports had no ACK; current builds neither send nor read it, and the bit stays taken), `0x08` ACCEPTS_CONNECTIONS (inbound slots free), `0x10` PROTOCOL_1_1 (the node speaks 1.1), `0x20` PREFIX_IN_HELLO (1.1: the node advertises a rotating prefix, sends it in HELLO and reads the peer's; section 2), `0x40` HELLO_AUTH (the node reads the nonce and the proof in HELLO, ACKs under a certificate and revocations; this section, 6.4 and 6.8), `0x80` ANCHOR_SLOT (1.1, advertising only: the phone keeps a slot for an anchor's session; section 2). No bit is left unassigned. A bit a node doesn't know is ignored, not rejected, both in HELLO and in advertising, by 1.0 and 1.1 alike, so `0x20`, `0x40` and `0x80` cost an older peer nothing. The roles are self-declared; only the proof below authenticates them.

A 1.1 node sets PROTOCOL_1_1 in every HELLO. A peer whose HELLO doesn't have it is treated as 1.0: it gets SUMMARY and REQUEST instead of SUMMARY2 and REQUEST2, and nothing 1.0 would count as malformed (section 7).

Rules (`MeshEngine.onHello`): each end sends HELLO first on a new link, without the prefix, since it doesn't know yet what the peer reads. Until the peer's HELLO arrives, its other packets are dropped; without a HELLO within 10 s the link is closed. If the peer's HELLO sets `PREFIX_IN_HELLO`, the node sends a second HELLO with its prefix, and another before each new prefix it advertises; the peer then takes the advertising under that prefix for this link and forgets the sightings of the old one. A later HELLO on the same link may change the flags, the nickname and the prefix, but a different nodeId counts as malformed. A HELLO carrying the receiver's own nodeId closes the link. A banned nodeId is no longer refused (section 10). Two links to the same peer: both ends keep the one initiated by the lower nodeId (unsigned comparison); of two initiated by the same end, the newer.

**Authenticated HELLO** (`MeshEngine.authenticate`, `KeyChain.helloProof`, `provenRoles`). Between two peers that set `HELLO_AUTH`, every HELLO that carries the prefix also carries the sender's nonce for this link. A node that holds a shift certificate answers the peer's nonce, once it has it, with a HELLO that carries the certificate and the proof; the exchange takes the second and third HELLO of each end. The receiver checks the certificate against its key chain (section 9) and the proof against the certificate's key, its own nonce and the sender's nodeId. A staff or control certificate proves STAFF, an anchor's proves ANCHOR; nothing else proves a role, and a proof that fails proves nothing and counts as nothing else either: no malformed mark, since the cause may be a revocation or a clock the relay doesn't share. The proof binds the certificate to a nodeId and a link; a node that relays another's whole HELLO exchange has to claim its nodeId too, and then the duplicate rule applies. A HELLO nickname that passes for staff (`ReservedNames`: "staff", "medic", "securitate", "poliție" and the like, also written with digits for letters) is shown only for a peer that proved its role.

### 6.2 SUMMARY and REQUEST (`SummaryCodec`, `RequestCodec`)

| Size | Field |
|---|---|
| 1 | `count`, at most 50 |
| `count` × 9 | `id8 i64`, `state u8` |

`id8` is the first 8 bytes of an `incidentId` read as `i64`. At most 451 bytes; nothing may follow the entries.

- SUMMARY `state`: bit `0x80` = "I hold the report"; bits 0..6 = the ACK status I hold (0..4, section 8). Status values above 4 are rejected: a SUMMARY goes only to and from 1.0 peers, which get no other status.
- REQUEST `state`: `0x80` = send me the report, `0x01` = send me the ACK. Any other bit is rejected.

**SUMMARY2 and REQUEST2** (1.1, `Summary2Codec`, `Request2Codec`) start with a `kind u8`, so that store-and-forward can carry other things later (organiser announcements, replies to a reporter) without a new packet type. The rest depends on the kind:

| Kind | Name | After the kind |
|---|---|---|
| 1 | INCIDENTS | the SUMMARY or REQUEST payload above; a SUMMARY2 may add the fields below |
| 2 | RESPONSES | SUMMARY2: `count u8` (at most 39, `Limits.SUMMARY2_RESPONSE_ENTRIES`), then per entry `id8 i64` and the `timestamp u32` of the latest response held (section 6.10). REQUEST2: `count u8` (at most 50), then the `id8`s wanted |
| 3 | UPDATES | SUMMARY2: `count u8` (at most 50), then per entry `id8 i64` and the `seq u8` of the latest update held (section 6.11). REQUEST2: as for RESPONSES |
| 4 | NOTICES | SUMMARY2: `count u8` (at most 39), then per entry the topic as an `i64` and the `seq u32` of the latest notice held (section 6.8). REQUEST2: `count u8`, then the topics wanted, each an `i64` |
| other | | not read |

A SUMMARY2 of kind INCIDENTS may carry, after its entries:

| Size | Field | Rule |
|---|---|---|
| 1 | `flags` | `0x01` EPOCHS; other bits belong to newer versions and are ignored |
| `count` | `epoch u8` per entry, in entry order | only if `0x01`; the epoch of the ACK the entry names (section 8) |

A node writes the flags byte only when one of the incidents it lists was reopened, so a summary without epochs keeps the bytes above. With epochs, an entry takes 10 bytes and a summary lists at most 47 incidents (`Limits.SUMMARY2_EPOCH_ENTRIES`, 2 + 470 + 1 = 473 bytes); the node then lists the 47 it stored most recently. EPOCHS with fewer epoch bytes than entries, or with more than 47 entries, is malformed. An entry without an epoch has epoch 0.

Unlike SUMMARY and REQUEST, they tolerate what a newer version may add: bytes after the fields this version reads are skipped; a SUMMARY2 status above 9 is kept as a number, and never requested, because 1.1 doesn't store such an ACK (section 6.4); a REQUEST2 want bit other than `0x80` and `0x01` is ignored. A kind the node doesn't read is dropped as unsupported, not malformed. Entry counts above 50 and truncated entries are still malformed. The largest SUMMARY2 is 452 bytes without epochs and 473 with them.

A node sends a summary after the peer's HELLO and then every 60 s on each ready link, only when it has something to list: the 50 incidents it stored most recently. A 1.1 node sends SUMMARY2 of kind INCIDENTS to a 1.1 peer and SUMMARY to a 1.0 peer, and to a 1.0 peer it lists only the packets it could hand to it (an ACK with a status 1.0 doesn't know or with extensions is left out, section 6.4). On a summary it requests the ACK if the peer's status is known and its (epoch, status) comes later in the supersede order (section 8) than its own; and the report if the peer holds it, it has never seen that report, and the later of the two ACKs isn't CANCELLED. It asks a link for no more reports than that link's budget for new reports has left (section 7); the others wait for a later summary, so none crosses the link only to be dropped. It answers SUMMARY with REQUEST and SUMMARY2 with REQUEST2. To a 1.1 peer it also sends, each only when it holds something to list, a SUMMARY2 of RESPONSES and one of UPDATES, the latest stored first, and to a peer that set `HELLO_AUTH` one of NOTICES; it asks for those whose timestamp or `seq` is later than its own, for no more updates than the link's budget for new ones has left (section 6.11), and for no more notices than its budget for announcements has left. On a request it sends the stored packets on that link only, as relays, and to a 1.0 peer only what 1.0 accepts.

### 6.3 INCIDENT_REPORT (`IncidentReportCodec`, `IncidentBody`)

| Size | Field |
|---|---|
| 16 | `incidentId`, random |
| rest | sealed body: `crypto_box_seal(IncidentBody)` to the staff box key, 48 bytes of overhead (section 9) |

Relays cannot open the body. They check only that the payload is longer than 64 bytes (16 + 48). At most 16 + 396 + 48 = 460 bytes.

`IncidentReportCodec.incidentId` and `IncidentReportCodec.sealed` slice the payload without checking its length and throw on a payload shorter than 16 bytes. Callers must check `IncidentReportCodec.isWellFormed` first; `MeshEngine` does, before it stores, relays or delivers a report, so the app only ever sees well-formed ones.

`IncidentBody`, the plaintext only staff can read:

| Size | Field | Rule |
|---|---|---|
| 1 | `category` | 1..8 |
| 1 | `severity` | 1..3 |
| 4 | `timestamp` | reporter's clock |
| 1 | `flags` | `0x01` coordinates, `0x02` nickname, `0x04` cancel hash, `0x08` triage, `0x10` staff fields, `0x20` location fields, `0x40` reply key (1.1) |
| 1 | `zoneLen` | at most 32 |
| `zoneLen` | zone | free text, may be empty |
| 4 + 4 | `lat i32`, `lon i32` | only if `0x01` |
| 2 | `descLen` (`u16`) | at most 300 |
| `descLen` | description | the app also limits it to 140 characters |
| 1 | `nickLen` | only if `0x02`; at most 24 |
| `nickLen` | nickname | only if `0x02`; may be empty; absent means anonymous. With `0x10`, the team's name |
| 16 | `cancelHash` | only if `0x04`; section 8 |
| 1 | `triage` | 1.1, only if `0x08`: bits 0..1 "does the person respond", bits 2..3 "do they breathe normally"; bits 4..7 ignored |
| 1 + 1 | `people u8`, `staffFlags u8` | 1.1, only if `0x10`: how many people (0 = not given); `staffFlags` `0x01` = the danger continues, other bits ignored |
| 1 + 1 | `accuracy u8`, `landmark u8` | 1.1, only if `0x20`: how far off the coordinates may be, in metres, rounded up and capped at 255 (0 = unknown); the number of the site-plan landmark nearest to the reporter (0 = none) |
| 32 | `replyKey` | 1.1, only if `0x40`: an X25519 public key the reporter draws for this report alone; the console seals its responses to it (section 6.10) |

At most 416 bytes (`IncidentBody.MAX_SIZE`), so that the report fits in a payload; 391 in 1.0. With every field at its limit a body takes 396 bytes before the reply key and 428 with it, so the reporter (`IncidentManager.report`) cuts the description to what is left: at most 12 bytes short of its 300. In 1.0 nothing may follow, flag bits above `0x04` are rejected and so is a category outside 1..7.

Categories: 1 medical, 2 violence, 3 lost child, 4 harassment, 5 crowd, 6 fire, 7 other, 8 lost friend (1.1). 1.0 called 3 "lost person"; a report with 3 from an older phone may be about anyone lost, and 1.1 shows it as a lost child, which errs on the side of a search. Severity: 1 low, 2 medium, 3 urgent.

Triage answers (`Answer`, two bits each): 0 not asked, 1 yes, 2 no, 3 don't know. The app asks both questions for a medical report and writes the byte only if at least one was answered; staff phones show "don't know" as such. The staff fields come only from a phone that holds the staff keys; its nickname field then carries the team from the staff QR. Neither is authenticated: until reports are signed (roadmap 2.18) they say what the author claims.

The reporter sends its coordinates whenever it has a fix from the last 2 minutes, whatever zone was picked; the zone stays the text the reporter chose. The location fields go only when there is something to put in them: a simulated position has no accuracy and a spot farther than 100 m from every landmark has none. Landmarks are the numbered points of interest of the site plan (`number` in `venue.json`, 1..255), so the number means the same on the reporter's phone and on the console only while both hold the same plan. The console computes the grid square from the coordinates. How to recognise the reporter ("Cum te recunoaștem?") isn't a field: it travels as the first line of the description, after the prefix `👀 ` (U+1F440 and a space), so any staff phone shows it at least as text.

The discreet report "mă simt în nesiguranță" is a harassment report (category 4, severity 2) whose description starts with `🤫 ` (U+1F92B and a space), then a short sentence in the reporter's language. It needs no field and no flag of its own: any staff phone, 1.0 included, shows it as a harassment report with those words, and a 1.1 staff phone routes it to the safeguarding role (`Discreet`, `Routing`) instead of security. Like the recognition line it is only a claim of the reporter. It is sealed and flooded like any report, the reporter is anonymous, and nothing about it appears in advertising or in HELLO: the pending flag is never advertised for any report (`DiscreetScenarioTest`).

1.1 lets a newer version extend the body: new fields go after the reply key, each behind a flag bit of its own (`0x80`, the last one left; a version that needs more puts a flags byte of its own behind it). A 1.1 staff phone ignores flag bits it doesn't know and everything after the fields it reads, and keeps a category it doesn't know as a number; the app shows it as "other". Category 0 and a severity outside 1..3 are still rejected. The body is sealed, so only staff phones decode it: a body a staff phone rejects is dropped silently, which is why staff phones must be updated before reporters send anything new here.

What a 1.0 phone makes of the 1.1 fields: as a relay, nothing, since the body is sealed; it stores, relays and serves these reports like any other, and never counts them as malformed. As a staff phone, it rejects a body with triage, staff fields, location fields, a reply key or category 8, so it never shows that report, sends no RECEIVED for it and bans nobody: a sealed body that doesn't decode is dropped silently. The categories are numbered so this costs least: a lost child keeps code 3 and reaches 1.0 staff phones as an urgent "lost person"; only the lost friend, which is for the control room alone, takes the new code. A report with coordinates alone would still reach it, but every current reporter writes a reply key (section 6.10), so no report of a current phone reaches a 1.0 staff phone any more; a 1.1 staff phone from before the reply key skips the key and opens the report as before. Staff phones are a fleet the organiser updates together.

### 6.4 INCIDENT_ACK (`IncidentAck`)

| Size | Field | Rule |
|---|---|---|
| 16 | `incidentId` | |
| 1 | `status` | 1..4 in 1.0, 1..9 in 1.1 (section 8) |
| 4 | `timestamp` | staff phone's clock |
| 1 | `teamLen` | at most 24 |
| `teamLen` | team name | from the staff QR |
| n | `extensions` | 1.1: fields of a newer version; 1.0 allows none |
| 64 | `signature` | Ed25519 |

1.0 ACKs have at most 110 bytes, and in 1.0 nothing may follow the signature.

The signature is `crypto_sign_detached` with the staff signing key over the ASCII domain `SP-ACK-v1` (9 bytes) followed by every payload byte before the signature. The verifier uses the bytes as received, not a re-encoding of the decoded fields. Every relay verifies the signature before it stores or relays an ACK.

In 1.1 the signature is always the last 64 bytes, so a newer version can put fields between the team name and the signature and have them signed. 1.1 keeps them as they came (`IncidentAck.extensions`), and keeps a status above 9 as a number instead of rejecting it; status 0 is still malformed. A verified ACK with a status 1.1 doesn't know is relayed with the usual TTL, but neither stored nor handed to the app, because the supersede order (section 8) has no place for it. An ACK with a status 1.0 doesn't know (above 4), or with extensions, never goes to a 1.0 peer.

The first extension byte holds the ACK flags (`AckFlags`, 1.1); the fields the flags announce follow it in bit order:

| Bit | Name | Meaning |
|---|---|---|
| `0x01` | ASSIGNED | the control console signed this ACKNOWLEDGED in the name of the team it gives the incident to (`IncidentManager.assign`); that team's phones show the incident as assigned to them. A team's own take is not an assignment |
| `0x02` | EPOCH | an `epoch u8` follows the flags byte: how many times the control console reopened the incident (section 8). Without the flag, or with the flag and no byte, the epoch is 0 |
| `0x04` | CERT | the signer's certificate follows the epoch (section 9), and its key, not the build's staff key, made the signature |
| `0x08`..`0x80` | | reserved; ignored |

A signer writes the flags byte only when a flag is set or the epoch is above 0, so an ordinary ACK keeps the 1.0 layout and still reaches 1.0 phones; an ACK without extensions has no flags and epoch 0. Bytes after the fields of the flags this version knows belong to newer versions and are kept unread. The flags and the epoch are under the signature, so a relay can't add, strip or change them. Since an ACK with extensions never goes to a 1.0 peer, 1.0 phones see neither an assignment nor anything after a reopen: they keep the status they had until a take or a later status reaches them (section 8 says what they show). SUMMARY carries only the status, so a take that replaces an assignment (section 8) spreads with the flood, not through store-and-forward; SUMMARY2 carries the epoch (section 6.2).

A phone of the assigned team accepts the assignment with that take: its own ACKNOWLEDGED, without the flag, in its team's name ("Accept alocarea" on its screen). The supersede order puts it over the assignment, so every phone, the console included, sees that the team has accepted; a later ON_SCENE says so too. Nothing new goes on the wire for it, and a 1.0 phone sees an ordinary take. Who is alerted, and how, is not on the wire either: each staff phone decides it from the state it holds (`incidents/Routing.kt`, roadmap 2.7): the insistent alert on the console and on role leads, vibration on the assigned team and on urgencies in a phone's zone, widening to the incident's role and then to all staff while nobody has it.

**ACKs under a certificate** (`KeyChain.checkAck`). A phone activated with an SPS2 code signs every ACK with its shift key and puts its certificate in the ACK: at most 250 bytes, 251 with an epoch. Every node that reads it checks, before it stores or relays it: the certificate against the chain at the node's clock; the signature against the certificate's key; the role (a team signs only in the team name its certificate holds, never CLOSED_BY_CONTROL, UNFOUNDED, REOPENED nor the ASSIGNED flag; a console signs anything; an anchor and a report key sign nothing); and the time (not after the node's clock plus the skew, not before the certificate). The certificate is checked when the ACK arrives, at the receiver's clock, not at the time the ACK carries: an ACK signed during a shift is dropped by every node that gets it more than 10 minutes (the skew) after the shift ended, so ACKs still cached on relays stop spreading then, and a phone that joins later never sees them. A bad signature counts as malformed; any other refusal drops the ACK silently (`UNTRUSTED`), since a relay with an older revocation list or another clock passes such ACKs on in good faith. An ACK without CERT is checked against the build's staff key as before. An ACK with CERT goes only to peers that set `HELLO_AUTH` (`CompatAuth`): a 1.1 peer from before it checks it against the staff key and would count it as malformed, and a 1.0 peer gets no ACK with extensions at all. The signer of an ACK under a certificate sends no 1.0 status ahead of it (section 8): it couldn't reach a 1.0 phone anyway.

### 6.5 INCIDENT_CANCEL (`IncidentCancel`)

| Size | Field |
|---|---|
| 16 | `incidentId` |
| 16 | `token`, random per report, known only to the author |

Exactly 32 bytes in 1.0; relays check only the size. 1.1 relays require at least 32 bytes and skip the rest, and send a 1.0 peer only cancels of exactly 32.

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
| 8 | LEFT_GROUP | `groupId i64` (1.1) |
| 9 | ZONE_REQUEST | nothing (1.1) |
| 10 | ZONE_UPDATE | `zoneLen u8` (1..32), zone: the venue's zone id (1.1) |

A flag outside the ones listed for the type is rejected, and nothing may follow the last field. Quick codes: 1 where are you, 2 coming, 3 low battery, 4 meet at the meeting point. Largest plaintexts: TEXT 418 bytes (458 once boxed), GROUP_INVITE 393 (433), ZONE 59 (99), ZONE_UPDATE 42, QUICK 19, PONG, DELIVERED and LEFT_GROUP 17, PING and ZONE_REQUEST 9.

What the recipient does (`ChatManager`): it opens the box with the key of the header `sender`, looked up among its friends and group members; anything else is dropped silently. An unknown inner type fails to decode and is dropped silently too: relays never see it, and the recipient doesn't count it as malformed. TEXT, QUICK and ZONE are accepted from friends, or from members of the named group, and each copy, duplicates included, is answered with DELIVERED. Invites are confirmed with DELIVERED too. A PING from a friend gets a PONG. A group of at most 8 members, creator included, is sent pairwise: one PRIVATE per member, all with the same `msgId`. An invite lists at most 6 members, because the inviter and the invitee are implicit; it is accepted only from a friend.

Kinds 8 to 10 are additions of 1.1 to what a phone says to a friend, and need no negotiation: a phone that doesn't know a kind fails to decode it exactly as it does any unknown type, drops it silently, sends no DELIVERED and counts nothing as malformed (`PayloadsTest`, `ChatExtrasTest`). So a phone of this version may send them to any friend or group member, and what an old phone loses is only the effect.

- **LEFT_GROUP.** Sent by someone who leaves a group, to every other member, through the outbox like a message: new attempts with backoff until DELIVERED, at most 24 hours. The leaver no longer has the group, so the box key of each member is kept with its outbox entry and is also what opens the member's DELIVERED. A member accepts it from a member of that group, answers DELIVERED, takes the sender off the group's members (so nothing is sent to them any more, and what is still waiting in the outbox for them for that group is dropped) and adds a notice to the group. From someone not a member, or for an unknown group, it is dropped, but a friend whose copy comes after the removal still gets a DELIVERED, because the first one may have been lost. An old phone ignores it, and keeps sending to the leaver for up to 24 hours as before.
- **ZONE_REQUEST and ZONE_UPDATE.** "Where are you?" and its answer. Both go straight to a friend as one PRIVATE, with no retry and no DELIVERED, and only to friends: a request from anyone else is not answered. A phone sends ZONE_UPDATE to each friend when its zone changes (once per friend per zone; a phone that had no link to send it on sends it when one comes up) and when asked, and never on a timer. The recipient keeps the latest it received, with the time it arrived, as the friend's last zone. A phone that doesn't want to share its zone sends neither. ZONE, the zone shared in a chat, also sets a friend's last zone.
- **Blocking** is local: a blocked node's PRIVATE packets are dropped before the box is opened, nothing is answered, and the node is taken off the friends and groups here; they are not told.

### 6.7 TEST

An opaque payload of at most 480 bytes, used by the debug demo. Release builds drop it on receipt, after decoding and rate limiting, without counting it as malformed.

### 6.8 ANNOUNCEMENT (`Revocation`, `Notice`, `HELLO_AUTH`)

The first byte is the kind: 1 a revocation, 2 a notice; other kinds are for newer versions. Kind 1, revocation:

| Size | Field | Rule |
|---|---|---|
| 1 | `kind` | 1 |
| 8 | `signer` | key id (section 9) of the key that signed: the root, the organiser or the event |
| 4 | `issued` | `u32` seconds, the signer's clock |
| 1 | `count` | 1..32 |
| 8 × `count` | certificate ids | the first 8 bytes of SHA-256 over each revoked certificate |
| 64 | `signature` | Ed25519 by the signer over `SP-ANN-v1` (9 bytes) ‖ every byte before it |

At most 334 bytes. A node verifies it before it relays it as a known type (section 5): the signer must be the root or a key of the chain that holds now. It keeps every id it verified across restarts, and the latest 16 announcements, which the console sends again (`Revocations`); a revoked certificate, and everything a revoked key signed, is refused everywhere: in HELLO proofs, in ACKs, in an SPS2 code, and as this phone's own, which then leaves staff mode. The organiser tool prints one as an SPR1 code (section 11); the control console scans it, sends it at once and again every 10 minutes, so phones that were out of range or joined later get it too. An announcement this node can't verify, of another kind or from another event, goes on as an unknown type: 3 hops, the same budget.

What an older phone sees: a 1.1 phone from before `HELLO_AUTH` relays it as the reserved type, 3 hops from where it got it, 4 a minute per link, and keeps no list; a 1.0 phone never gets it. So a revocation protects only phones that set `HELLO_AUTH`.

**Kind 2, notice** (`announce/Notice.kt`, `Notices.kt`). What the control console tells every phone: an alert (information, pause or evacuation), the heat level, a change of schedule or transport. The text isn't on the wire: a notice names one of the event pack's templates (section 16), which every phone shows in its own language, and carries the parameters that fill it in.

| Size | Field | Rule |
|---|---|---|
| 1 | `kind` | 2 |
| 1 | `topic` | 1 alert, 2 heat, 3 schedule, 4 transport; 0 is malformed, a topic this version doesn't know is kept as a number |
| 1 | `level` | alert: 1 information, 2 pause, 3 evacuation; heat: 1 to 4; schedule and transport: 1 |
| 1 | `flags` | `0x01` CANCEL: the notice withdraws its topic's and shows nothing; other bits reserved and ignored |
| 4 | `seq` | `u32`, the notice's number in its topic; a higher one replaces a lower one |
| 4 | `issued` | `u32` seconds, the console's clock |
| 4 | `validUntil` | `u32` seconds; later than `issued`, by at most 24 hours |
| 1 | `template` | the template's id in the pack, 1..255; 0 in a cancel |
| 1 + n | `zone` | `zoneLen u8` (≤ 32), UTF-8; empty for the whole site |
| 1 | `paramCount` | at most 4 |
| per param, 1 + n | `param` | `len u8` (≤ 40), UTF-8: what `{1}` to `{4}` of the template become |
| 115..139 | `certificate` | the signer's shift certificate (section 9) |
| n | extensions | a newer version's fields, kept as they came |
| 64 | `signature` | Ed25519 by the certificate's key over `SP-ANN-v1` (9 bytes) ‖ every byte before it |

At most 418 bytes without extensions. Every node that sets `HELLO_AUTH` and has this version checks it at every hop (`KeyChain.checkAnnouncement`): `issued` isn't after the node's clock plus the skew, the certificate held under the chain at `issued` and isn't revoked, its role is control, the signature verifies, and `validUntil` is within 24 hours of `issued`. The certificate is checked at `issued`, not at the node's clock, so a notice issued just before a shift ends holds until its own `validUntil` for the phones that get it later by store-and-forward; once the shift is over its key can't issue a new one. A notice under a team's, an anchor's, a revoked or an unknown certificate is refused and goes on as an unknown type: 3 hops, within the announcement budget of 4 a minute per link, as a revocation this node can't verify does. Then the node keeps it (`IncidentCache.offerNotice`) only if its `seq` is above the highest the node saw in its topic and it hasn't expired by the node's clock; an older or expired one is dropped as `STALE_ACK` and not relayed. A copy of one already held is dropped before it counts against the link's announcement budget, so the console's resends don't use up what catch-up requests need. A node keeps the latest notice of each topic until it expires, cancels included, at most 16, across restarts (`mesh_cache.json`, whatever their age), and the highest `seq` of each topic after that; it lists them in a SUMMARY2 of kind NOTICES to `HELLO_AUTH` peers (section 6.2), so a phone that comes later gets the notices in force from the first neighbour that holds them.

The console (`Notices`) signs a notice with its control shift, numbers it one above the highest `seq` it knows in the topic, from any console, on its board or in its mesh cache, and sends it at once; a console that knows neither (both wiped, or a second console never in range of the first) starts again at 1, and the phones holding a higher `seq` drop its notices as `STALE_ACK` until it hears one of them and numbers above it; a cancel names the notice's topic, ends when the notice would have, and replaces it. It sends the notices in force again under new packet ids: alerts every 10 minutes, heat and changes every 30. A phone that holds the notice drops the copy at the first hop, so a resend costs a transmission per neighbour of the console, and travels on only into the part of the network that lacks it.

Cost: a notice is one unique packet per region, flooded like a report. A handful an hour, plus the console's resends, at most 1 / 600 + 3 / 1800 ≈ 0.003 unique packets a second with all four topics in force, under 0.2 % of the region's budget of about 2.8 a second; the SUMMARY2 of NOTICES is one link-local packet a minute per link, only while a node holds a notice. No phone but the console sends notices, and nothing about them is periodic on the participants' side.

Phones show a notice only on staff phones and on participants' that opted in (Eu › Anunțurile organizatorilor); the app's text never says that everyone was told. Before an evacuation is signed, the console's screen asks for a word typed by a second operator; that is a check of the screen only, neither `Notices.publish` nor the protocol enforces it, and withdrawing an evacuation takes one confirmation. A pause or an evacuation takes the screen once and sounds on a channel of its own, apart from the incident alarm; the others show as a band above every screen, the heat with the way to water and shade; while a heat notice is in force the map shows its level and puts water and shade first among its shortcuts. In an exercise (section 16) the notice is marked and only vibrates. What older phones see: a 1.1 phone from before notices relays one as an announcement it can't verify (3 hops, 4 a minute per link) and drops the SUMMARY2 kind as unsupported, once a minute per neighbour; a 1.0 phone gets neither.

### 6.9 Control channel (`ControlChannel`, 1.1)

A PRIVATE whose `recipient` is `5350 2d43 5452 4c31` ("SP-CTRL1" in ASCII, `ControlChannel.ID`) is for the control console, not for a phone. Relays pass it on like any PRIVATE, 1.0 ones included, since they check only R, `sender ≠ 0` and E; a leaf relays it too (section 7). A node hands it to the app and passes it on as well, since there may be more than one console. The payload isn't a `crypto_box` but a `crypto_box_seal` to the key a report body is sealed to, the day's report key or else the build's staff box key (section 9, `AnchorBeats`): only staff phones that open reports read it, and the console needs no key of the sender's. The plaintext starts with `kind u8`; kind 1 is HEARTBEAT, and the console drops the others.

HEARTBEAT (`Heartbeat`), what an anchor tells the console about itself, 9 bytes plus the zone:

| Size | Field |
|---|---|
| 1 | `kind`, 1 |
| 1 | `flags`: `0x01` charging; other bits ignored |
| 1 | battery, 0..100 %, `0xff` unknown |
| 1 | battery temperature in whole °C, `i8` from −127 to 127, `0x80` unknown |
| 2 | `u16`, seconds since the anchor last heard a phone, at most 65534; `0xffff` if it never did |
| 1 | links up now |
| 1 | phones heard around it, as a bucket: 0 none, 1 for 1–2, 2 for 3–5, 3 for 6–10, 4 for 11–20, 5 for 21–50, 6 for 51–100, 7 for more |
| 1 + n | the zone of the anchor's QR, `zoneLen u8` (≤ 32), UTF-8 |

A battery above 100 or a bucket above 7 is rejected. Flag bits other than `0x01`, and bytes after the zone, belong to newer versions and are skipped. Sealed, it takes 57 to 89 bytes.

An anchor sends one every 5 minutes, and within a minute of a change in what the console acts on: plugged in or not, the battery crossing 50, 20 or 10 %, the temperature reaching 45 °C, its last link gone (`AnchorHeartbeat`). The crowd bucket changes all the time and waits for the next one. 30 anchors cost the mesh 0.1 packets a second. The console keeps the last heartbeat of each anchor, found by the prefix of the sender's nodeId, which is what an anchor advertises (section 2), and counts it as hearing the anchor: an anchor that is neither heard nor heard from for 10 minutes shows as silent. Nothing signs a heartbeat yet, so anyone can send one in an anchor's name; the console marks what it says as indicative until anchors have certificates (roadmap 2.18). A newer version adds fields after the zone or a new kind.


### 6.10 INCIDENT_RESPONSE (`IncidentResponse`, 1.1)

The control console's answer to the author of a report, out of a fixed set (`ResponseCode`): 1 "Venim, rămâi pe loc" (we're coming, stay put), 2 "Nu te găsim, du-te la reperul X" (we can't find you, go to landmark X), 3 "Mergi la punctul medical" (go to the medical point). Free text isn't part of it.

| Size | Field | Rule |
|---|---|---|
| 16 | `incidentId` | the report's |
| 4 | `timestamp` | console's clock, `u32` seconds; a console never gives two responses to one incident the same second |
| 1 | `flags` | `0x01` CERT; other bits reserved and ignored |
| 1 | `sealedLen` | more than 48 |
| `sealedLen` | `sealed` | `crypto_box_seal(ResponseBody)` to the report's reply key (section 6.3) |
| 115..139 | `certificate` | only with CERT: the signer's shift certificate (section 9) |
| n | extensions | a newer version's fields, kept as they came |
| 64 | `signature` | Ed25519 over `SP-RESP-v1` (10 bytes) ‖ every byte before it |

`ResponseBody`, the plaintext: `code u8` (0 is rejected, a code this version doesn't know is kept as a number), `landmark u8` (the site-plan number for code 2, else 0); bytes after it belong to newer versions and are skipped. 50 bytes sealed. A whole response takes 136 bytes, 251 to 275 under a certificate.

Relays check it like an ACK (`KeyChain.checkResponse`): without CERT, the signature against the build's staff key; with CERT, the certificate against the chain, the signature against its key, the role (only a control shift answers a reporter) and the time (not after the node's clock plus the skew, not before the certificate). A bad signature or a payload that doesn't decode is malformed; any other refusal drops it silently (`UNTRUSTED`). A relay keeps, per `id8`, the response with the latest `timestamp` and drops an older or equal one without relaying it (`STALE_ACK`), so the reporter always gets the latest. Like an ACK, a response under a certificate goes only to `HELLO_AUTH` peers, and like every type 1.0 doesn't know, never to a 1.0 peer.

Only the author can open it: the reporter keeps the reply key's secret half with its report (`MyReport.replySecret`), opens each response that names its incidentId, and shows them in order on its report's screen, with a notification that sounds. A response can't say who it is for beyond the incidentId, and relays can't read it.

What 1.0 sees: nothing. Its staff phones can't read a report with a reply key (section 6.3), and no 1.1 phone sends it a response.

### 6.11 INCIDENT_UPDATE (`IncidentUpdateCodec`, `UpdateBody`, 1.1)

What the author of a report says later: still in need of help, moved (and where to), or sorted out by itself. Anonymous like the report.

| Size | Field |
|---|---|
| 16 | `incidentId` |
| 1 | `seq`, the update's number for this report, from 1 |
| rest | `crypto_box_seal(UpdateBody)` to the key the report was sealed to, more than 48 bytes |

`UpdateBody`:

| Size | Field | Rule |
|---|---|---|
| 1 | `kind` | `UpdateKind`: 1 still active, 2 moved, 3 resolved by itself; 0 is rejected, an unknown one kept as a number |
| 4 | `timestamp` | reporter's clock |
| 16 | `token` | the report's cancel token (section 8) |
| 1 | `flags` | `0x01` coordinates, `0x02` zone, `0x04` location fields; other bits belong to newer versions and are ignored |
| 4 + 4 | `lat i32`, `lon i32` | only if `0x01` |
| 1 + n | `zoneLen u8` (≤ 32), zone | only if `0x02` |
| 1 + 1 | `accuracy u8`, `landmark u8` | only if `0x04`, as in the report |

Bytes after the fields this version reads are skipped. At most 65 bytes, 130 sealed with the incidentId and `seq`.

Relays can't check an update: they require only that it is longer than 65 bytes (malformed otherwise), relay every new one live, and keep per `id8` the one with the highest `seq` (`IncidentCache.offerUpdate`), at most 50 updates in all (the one kept longest goes first, never the phone's own). Each link may bring at most 6 new updates a minute (`MeshConfig.updatesPerMinute`), counted apart from reports; the rest are dropped (`UPDATE_LIMIT`) and not recorded in dedup, so they can come again; a node requests no more updates through REQUEST2 than that budget has left. A staff phone that holds the report opens the update, checks `SHA-256("SP-CANCEL-v1" ‖ token)` against the report's cancel hash and keeps it by `seq`, so its timeline follows the reporter's order whatever order the updates came in. One that comes before the report waits in the mesh cache, the latest of them, and is applied when the report arrives. The reporter sends at most 10 updates per report, one a minute, and none once the report is closed or withdrawn (`IncidentManager.canUpdate`).

The token in the update shows staff that the author wrote it; it also tells every staff phone that opens it the token that cancels the report, which those phones could do anyway. A relay that replays an old update, or sends sealed garbage under a high `seq`, can make relays keep that one instead of the real latest: staff phones drop it, and the real ones still travel live. What 1.0 sees: nothing, as for responses.

A 1.1 phone from before these two types relays them as types it doesn't read (section 7.1): an update, and a response without a certificate, at most 3 hops past it; a response under a certificate, over 256 bytes, not at all. It drops the two SUMMARY2 kinds as unsupported, two a minute from each neighbour that holds something to list, well under the 10 that close a link. So responses and updates need relays of this version to go far; the fleet of an event is updated before the public is let in.

## 7. Receiving and relaying

The receive path of `MeshEngine.onFrame`, in order:

1. Decode the frame (section 3); on failure, malformed. In 1.1 a frame from a newer version (section 3) is dropped as unsupported instead.
2. Reassemble (section 4); `Invalid` is malformed, `Pending` waits.
3. A packet whose dedup key is already known (below) is dropped as a duplicate, except on types `0x00` to `0x0F`. Copies cost no receive token, and a relay of the packet still waiting out its delay skips the link that sent the copy.
4. Per-link receive rate limit: a burst of 30, then 3 packets per second. The excess is dropped.
5. TEST is dropped unless the build allows it.
6. HELLO is handled (section 6.1). Other packets from a link without HELLO are dropped.
7. SUMMARY, REQUEST, SUMMARY2 and REQUEST2 are handled on the link (section 6.2) and go no further.
8. A type the node reads: the per-type check below; delivery to the app if there is no recipient or the recipient is this node; relay if it isn't addressed to this node and `ttl > 1`.
9. In 1.1, any other type: section 7.1.

Per-type checks, at every hop:

| Type | Check | On failure |
|---|---|---|
| INCIDENT_REPORT | payload longer than 64 bytes; then stored if its `id8` is new. 1.1: at most 6 new reports a minute from one link | malformed; a known `id8` is dropped and not relayed; a new report over the limit is dropped (`REPORT_LIMIT`) |
| INCIDENT_ACK | decodes and the signature verifies; then stored if it supersedes the stored ACK (section 8). 1.1: a status above 9 is relayed, but neither stored nor delivered | malformed; an ACK that doesn't supersede is dropped and not relayed |
| INCIDENT_CANCEL | payload exactly 32 bytes; 1.1: at least 32 | malformed |
| INCIDENT_RESPONSE (1.1) | decodes and checks out (section 6.10); then stored if its timestamp is the incident's latest | malformed for a bad payload or signature, `UNTRUSTED` otherwise; an older one is dropped and not relayed |
| INCIDENT_UPDATE (1.1) | payload longer than 65 bytes; at most 6 new ones a minute from one link; stored if its `seq` is the incident's highest | malformed; over the limit, `UPDATE_LIMIT`; an older one is relayed but not stored |
| ANNOUNCEMENT (`HELLO_AUTH`) | at most 4 a minute from one link; the key chain verifies it (section 6.8); a notice is stored if its `seq` is its topic's highest and it holds | over the limit, `OPAQUE_LIMIT`; a forgery, `UNTRUSTED`; one it can't verify goes on as an unknown type (section 7.1); an older or expired notice is dropped and not relayed |
| PRIVATE | R set, `sender ≠ 0`, E set | malformed |

Nobody checks that a report or a cancel has `sender = 0`, or that a report has E set.

The report limit (1.1, `MeshConfig.reportsPerMinute`) is per link because reports are anonymous: a relay can't see who wrote one. 6 a minute is far above what people report, but slows a neighbour that fills the cache of 50 with fakes. Only a new, well-formed report counts; copies and known incidents don't. Reports served after a REQUEST count too, since a neighbour could list fake ids in its summary: a node that catches up on a large cache through one link gets 6 reports a minute from it, the rest with later summaries. It requests no more than the budget has left (section 6.2), so what goes over the limit is a live report, not one it asked for. A report over the limit isn't recorded in dedup, so it can still arrive later, by the same link or another.

Relaying: after a random delay of 50..250 ms, the packet goes with `ttl − 1` to every ready link except the one it came from and those that sent a copy during the delay. A node never relays a packet addressed to itself. The send side paces itself to 2.8 packets per second per link (burst 28) and at least 50 ms between packets, just under the neighbour's receive limit.

Chat fan-out (1.1, optional, `mesh/FanOut.kt`): off by default (`MeshEngine.CHAT_FAN_OUT`, `MeshConfig.chatFanOut`), so a relay floods chat like everything else. When it is on, a PRIVATE goes on every ready link from the phone that writes it, but a relay passes it on only `k = ⌈log₂ n⌉` of the `n` links it could use, at least 1, as Bitchat does: 1 of 1 or 2, 2 of 3 or 4, 3 of 5 to 8. If the recipient is one of its neighbours, that link is always among the `k`. The others are drawn at random for each packet, so a retry of the outbox, which is a new packet, takes other paths. Reports, ACKs, cancels and everything else always keep the full flood and store-and-forward. Section 5 has what it saves and what it costs in delivery.

Leaves (1.1, optional, `MeshConfig.leaves`): a participant that had a link with a phone claiming STAFF or ANCHOR in the last 10 minutes keeps at most one outgoing and one incoming link, and relays only reports, ACKs, cancels, responses, updates and the control channel (section 6.9), not chat or types it doesn't read (`ConnectionPolicy.relays`); its own packets and store-and-forward are unchanged. It is off (`MeshEngine.LEAVES`) for two reasons. The role comes from an unsigned HELLO (section 14), so a phone that only claims one would turn its neighbours into leaves. And with most phones down to two links, the leaves of a crowd split into chains: with leaves on, `ScaleTest` lost 2 of 150 report copies on the 50-phone grid, and chat between friends among 200 walking phones. It comes on with signed roles, and with a rule that keeps leaves attached to relays.

Chat budget (1.1, `ChatManager`, not on the wire): a phone writes at most 30 PRIVATE packets a minute (`PACKETS_PER_MINUTE`): messages, retries, receipts, PING and PONG; over that, a message waits for the next retry and a receipt for the sender's next retry. At most 16 outbox entries, one per message and recipient, are tried at once (`MAX_IN_FLIGHT`); the rest wait as queued until one is delivered or gives up. A relay doesn't limit chat by sender: the header `sender` isn't authenticated, so such a limit could be spent in someone else's name; the per-link limits above bound what one neighbour can push.

A 1.1 node sends a 1.0 peer, whether its own packet, a relay or a store-and-forward reply, only what 1.0 accepts (`Compat10`): no type 1.0 doesn't know, no ACK with a status above 4 or with extensions, no cancel other than 32 bytes. Its HELLO, SUMMARY and REQUEST are 1.0 bytes.

Hops: a receiver computes `hops` from the origin TTL of the type (section 5): `origin − ttl + 1`, so a direct neighbour sees 1. A TTL above the type's origin comes from a sender that started higher, as a 1.0 node did on every type, and counts from 7. A 1.0 receiver counts every type from 7, so it shows a 1.1 phone's chat one hop farther than it is. The other way round, a 1.1 receiver counts a 1.0 phone's chat from 7 only while its TTL is still above 6, that is from a direct neighbour; once relayed, the TTL can't be told from a 1.1 phone's and the count starts at 6, one hop short. The count is only shown (a friend's distance, "direct" at 1), never acted on, so this stays as it is.

Store-and-forward: a stored report or ACK keeps `max(ttl − 1, 1)` and is served with that `ttl` when a neighbour requests it; the origin stores its own report or ACK with `ttl 7`. The TTL therefore limits only the live flood. Through SUMMARY and REQUEST a report can travel any number of hops. Reports and ACKs are kept 30 minutes (60 on anchors), at most 50 reports per node. Earlier builds kept them in memory only, so a relay or an anchor the system killed lost what it carried; the app now saves the cache after every change (`mesh_cache.json`, `mesh/CachedIncidents.kt`): each incident with its trace, the status, epoch and team of its best ACK, the stored report and ACK as frames, and their times on the wall clock, since the monotonic clock starts over at a reboot. At start the node takes it back aged by the time it was off: a packet older than 60 minutes, the longest any role keeps one, or one that doesn't decode is dropped, a trace older than 6 hours too, and the role's own retention applies from then on. A file it can't read, damaged or written by a newer version, is deleted, and the node starts with an empty cache that it saves again from then on; the stores of what the user owns (friends, chat, own reports) are never deleted that way. `IncidentCache` keys both on `id8`, the first 8 bytes of the `incidentId` read as `i64` (the same `id8` as in SUMMARY and REQUEST, section 6.2), not on all 16 bytes: two reports whose ids share their first 8 bytes are one incident to a relay, so the second is dropped as known, and their ACKs compete for one slot.

Dedup key (`Packet.dedupKey`): the first 8 bytes, as `i64`, of SHA-256 over

```
type u8 | E u8 (0 or 1) | id i64 | sender i64 | timestamp u32 | recipient i64 (0 if absent) | has recipient i64 (0 or 1) | payload
```

It covers everything except `ttl`, so a node can't block a packet by sending garbage with the same `id` first. Keys are kept 15 minutes, 4,096 at most. The origin records the key of its own packets, so echoes are dropped. Known keys are dropped before the rate limit; a new packet's key is recorded before its per-type check, except for a report over the per-link limit. The key never goes on the wire, so changing it is not a wire change; it is in the golden vectors only for reference.

### 7.1 Types a node doesn't read (1.1)

1.0 counted a packet of an unknown type as malformed. 1.1 relays it as it is, so that a newer version's packets cross 1.1 phones, but within limits, because a relay can't check what it can't read:

- Types `0x00` to `0x0F` are never relayed, and neither is a packet addressed to this node or arriving with `ttl 1`; all are dropped as unsupported.
- The payload may be at most 256 bytes, 480 for ANNOUNCEMENT; over that, the packet is dropped (`OPAQUE_LIMIT`).
- At most 12 such packets a minute from one link (`MeshConfig.opaquePerMinute`) and, counted apart, at most 4 ANNOUNCEMENT (`MeshConfig.announcementsPerMinute`); the rest are dropped (`OPAQUE_LIMIT`).
- Dedup as for any packet. The relayed copy has `ttl = min(ttl − 1, 3)`, so it goes at most 3 hops past the first 1.1 relay.
- Send priority 5, below chat, except ANNOUNCEMENT at 1. Never to a 1.0 peer. Never delivered to the app.

## 8. Incidents and the ACK chain

- The reporter draws a random 16-byte `incidentId` and a random 16-byte cancel token, puts `cancelHash = SHA-256("SP-CANCEL-v1" ‖ token)`, first 16 bytes, into the body, seals the body and floods the report with `sender 0`.
- The first staff phone that opens a report sends RECEIVED by itself, unless an ACK for it is already known. Staff then send the others by hand: a team ACKNOWLEDGED (it takes the incident), ON_SCENE, HANDED_OVER and RESOLVED; the control console CLOSED_BY_CONTROL, UNFOUNDED, CANCELLED after checking a cancel, and REOPENED. Under a certificate, a team signs only its own takes and field states, and only the console signs CLOSED_BY_CONTROL, UNFOUNDED, REOPENED and assignments (section 6.4); with the build's shared staff key (SPS1) every holder can still sign any of them, and only the screens keep the console's statuses to the console.
- ACK status values (`AckStatus`): 0 NONE (only in summaries), 1 RECEIVED, 2 ACKNOWLEDGED, 3 RESOLVED, 4 CANCELLED; in 1.1 also 5 ON_SCENE, 6 HANDED_OVER, 7 CLOSED_BY_CONTROL, 8 UNFOUNDED, 9 REOPENED. Values above 9 are for newer versions (sections 6.4 and 15). RESOLVED, CLOSED_BY_CONTROL, UNFOUNDED and CANCELLED close an incident; ACKNOWLEDGED, ON_SCENE and HANDED_OVER mean a team has it.
- Relays track an incident by `id8` (section 7): `IncidentCache` stores one report and one ACK per `id8`, and SUMMARY and REQUEST name incidents by it. The reporter and staff phones match reports, ACKs and cancels on all 16 bytes of the `incidentId`.
- Epoch (1.1): the number of times the console reopened the incident, carried in the ACK (section 6.4). REOPENED is signed with the known epoch plus one and every later ACK with that epoch, so a reopen is the first status of its epoch. At 255 the incident can't be reopened again.
- Rank within an epoch, the one table (`AckStatus.rank`), lowest first:

  | Rank | Status | Why there |
  |---|---|---|
  | 0 | NONE | |
  | 1 | RECEIVED | a phone has it, no person yet |
  | 2 | REOPENED | starts an epoch; whatever a team does next replaces it |
  | 3 | ACKNOWLEDGED | |
  | 4 | ON_SCENE | |
  | 5 | HANDED_OVER | |
  | 6 | RESOLVED | a team says it's done |
  | 7 | CLOSED_BY_CONTROL | the console's decision comes after the team's |
  | 8 | UNFOUNDED | a false alarm found out after a closing still says so |
  | 9 | CANCELLED | as in 1.0, nothing in the epoch replaces it |

  1.0's four keep their 1.0 order (1 < 2 < 3 < 4), so 1.0 and 1.1 relays agree on every ACK they both keep.
- Supersede order (`AckOrder`, `IncidentCache.isBetter`, per `id8`): the later epoch wins; within an epoch the higher rank wins; for equal rank an ACK without the ASSIGNED flag beats one with it (1.1, section 6.4) whatever their timestamps, so a team that takes an incident has it even if the console gave it to another team first; then the older `timestamp` wins; for equal timestamps the smaller team name wins (Kotlin string order, by UTF-16 code units). Within an epoch the status never goes down; only a reopen brings an incident back, and no ACK of an earlier epoch closes it again, however late it comes. 1.0 compares status numbers only (section 15). Phones apply the same rule to what they show: a take replaces an assignment of the same status.
- What a 1.0 phone shows. It never receives a status above 4 or an ACK with an epoch (section 7), so it counts nothing malformed. In epoch 0, the signer of a status 1.0 doesn't know first floods, as a separate ACK without extensions, the closest status 1.0 knows (`AckStatus.legacy`) if that beats the ACK the signer holds, and the new status `IncidentManager.ONE_ZERO_LEAD_MS` (2 s) after it, so that relays keep and pass on the first before the second replaces it:

  | 1.1 status | A 1.0 phone shows |
  |---|---|
  | ON_SCENE, HANDED_OVER | ACKNOWLEDGED (usually it has it already from the take) |
  | CLOSED_BY_CONTROL, UNFOUNDED | RESOLVED |
  | REOPENED, and anything after it | what it had: 1.0 cannot go back, so a reopened incident stays closed there |

  1.1 phones see the 1.0 status for those two seconds, then the new one. So does the signer: it shows the 1.0 status until the new one has gone out, so a phone stopped in between doesn't show a status the network never got. The 1.0 status reaches 1.0 phones with the live flood and through 1.0 relays; 1.1 relays keep only the better ACK, so they don't serve it later through store-and-forward. Behind a 1.0 relay a 1.1 phone sees only what 1.0 sees.
- Cancelling: the author floods INCIDENT_CANCEL with `sender 0`, again every 60 s and after each new link, for up to 30 minutes or until the incident is closed. A staff phone that holds the report checks `SHA-256("SP-CANCEL-v1" ‖ token)[0..16] == cancelHash` and that the incident is open. Then it signs a CANCELLED ACK only where a wrong cancel costs little: categories crowd, other and lost friend, not urgent, never reopened (`Priority.cancelNeedsReview`, `IncidentManager.cancelIsChecked`). Everywhere else, a category it doesn't know included, a valid cancel only marks the incident "cancelled by the reporter, to check" on that phone: the alert stays, the reporter keeps the report spreading so every staff phone sees both, and the console closes it with CANCELLED or UNFOUNDED, or carries on. The mark isn't on the wire: every staff phone that holds the report checks the token itself. The reporter knows only the epochs that reached it: after a reopen it missed, it shows its cancel as closing the incident until a summary brings the reopen. So it keeps a report staff already confirmed in its cache, whatever the category; with the cancel it drops only a report no staff phone confirmed, in a category whose cancel closes it by itself. A 1.0 staff phone still signs CANCELLED for any valid cancel, so the check holds only where staff phones are updated together. A cancel that arrives before its report is kept for 60 minutes (64 at most). A body without `cancelHash` cannot be cancelled.
- Severity is the reporter's: urgent for a lost child or after a "no" to a triage question, or when the reporter says so; low for a lost friend; otherwise medium. No other category is urgent by itself (`Priority.severity`). A lost friend raises no alert and is left out of the teams' list; the console shows it as information.
- Relays can verify ACKs and nothing else about an incident.

## 9. Keys and cryptography

All cryptography is libsodium (lazysodium on Android, lazysodium-java in the JVM tests).

| What | Algorithm | Key |
|---|---|---|
| Report body | `crypto_box_seal`: ephemeral X25519, XSalsa20-Poly1305 | staff box public key |
| ACK | `crypto_sign_detached`, Ed25519, domain `SP-ACK-v1` | staff signing key |
| Cancel | SHA-256 over `SP-CANCEL-v1` ‖ token, first 16 bytes | none |
| PRIVATE | `crypto_box_easy`: static X25519 on both sides, XSalsa20-Poly1305, random 24-byte nonce | sender's box secret key, recipient's box public key |
| Response to a reporter (1.1) | `crypto_box_seal` of the answer, then `crypto_sign_detached`, domain `SP-RESP-v1` | the report's reply key (X25519, one per report); the control shift's key or the staff signing key |
| Update (1.1) | `crypto_box_seal`, the cancel token inside | as the report body |

- **Node identity** (`crypto/Identity.kt`): an X25519 pair and an Ed25519 pair, random at first start. `nodeId` = the first 8 bytes of SHA-256(Ed25519 public key ‖ X25519 public key), read as `i64`. 0 is reserved for anonymous packets; an identity that hashes to 0 is generated again. The advertised prefix is the first 4 bytes of the nodeId. The Ed25519 identity key is not used by the protocol today; it only feeds the nodeId and the friend QR.
- **Key chain** (`crypto/Certificate.kt`, `KeyChain.kt`). Ping Up's root Ed25519 key is kept offline; its public key ships in the app (`staff_public.json`, `root`). The root signs an organiser's key, the organiser one key per event (revocable), and the event key signs, per day, the shift certificates of teams, consoles and anchors, and the day's report key. The app ships the organiser's and the event's certificates (`chain`) and the days' report keys (`reportKeys`); a certificate there that doesn't lead to the root is ignored. Certificate, version 1:

  | Size | Field | Rule |
  |---|---|---|
  | 1 | `version` | 1; another is malformed where a certificate is read |
  | 1 | `role` | 1 staff, 2 anchor, 3 control (the codes of the QR roles), `0x10` organiser, `0x11` event, `0x12` report key |
  | 32 | `key` | Ed25519 public key; X25519 for a report key |
  | 4 + 4 | `validFrom`, `validUntil` | `u32` seconds |
  | 8 | `issuer` | key id: the first 8 bytes of SHA-256 over the issuer's Ed25519 public key |
  | 1 + n | `name` | `u8` length, at most 24: the team for staff and control, "Ancora zone" for an anchor, the organiser's or event's name |
  | 64 | `signature` | Ed25519 by the issuer over `SP-CERT-v1` (10 bytes) ‖ every byte before it |

  115 to 139 bytes. Its id is the first 8 bytes of SHA-256 over all of it. A certificate holds (`KeyChain.status`) if every key up to the root signed the one below in the order root → organiser → event → shift or report key, none of them is revoked, and the node's clock is inside every window on the way, give or take **10 minutes of clock skew** (`KeyChain.SKEW_S`) at each end. A phone more than 10 minutes off refuses good certificates and has its own refused; scanning its code then says so.
- **Report key per day.** Reports are sealed to the X25519 key of the day's report key certificate whose window holds now; outside every planned day, to the build's key below. The day's private key reaches only the consoles and the role leads, in their SPS2 code; a team's code carries the day's certificate without it. A staff phone without the key relays reports and ACKs and opens nothing: what it carries tells it only incident ids and statuses, and its incident list stays empty. An SPS2 code can also carry the build's report seed (`--legacy`) for leads and consoles while phones that seal to the build's key are around.
- **What the chain does and doesn't do.** A stolen staff phone signs and proves its role only until its shift ends (plus the skew), or until the console sends a revocation of its certificate; its key opens reports only if it held that day's report key, and then only that day's. It doesn't make theft harmless: within the shift the thief signs as that team, sees what that phone sees, and a lead's phone opens the day's reports, the past hours' included, since a sealed box has no forward secrecy. Phones from before `HELLO_AUTH` and 1.0 phones check nothing of this: they trust the build's shared key, which SPS1 codes still carry.
- **Build keys** (`crypto/StaffCrypto.kt`, legacy): one X25519 pair (opens reports outside the event's days) and one Ed25519 pair (signs ACKs from SPS1 phones) per build. The public keys ship in `app/src/main/assets/staff_public.json` as `{"box": hex, "sign": hex}`. The secret keys come from the two 32-byte seeds in the staff QR (section 11): `crypto_box_seed_keypair(boxSeed)`, whose secret key is the first 32 bytes of SHA-512(seed), and `crypto_sign_seed_keypair(signSeed)`. A phone accepts a staff QR only if the derived public keys match the bundled ones. The same seeds go to every team and every anchor.
- **Sealed box** layout: `ephemeral public key (32) ‖ MAC (16) ‖ ciphertext`, with nonce = BLAKE2b-192(ephemeral public key ‖ staff public key). Overhead 48 bytes. It hides the reporter: there is no sender key to check.
  - Encrypted: the whole `IncidentBody` (category, severity, time, zone, coordinates, description, nickname, cancel hash).
  - In clear: the header (`sender 0`, `id`, `timestamp`, `ttl`) and the `incidentId`.
- **ACK signature**: section 6.4. It covers the payload, not the packet header.
- **Chat**: a successful `crypto_box_open_easy` with the claimed sender's key authenticates the sender to the recipient only. A third party can't verify it, and the recipient could forge it, so it proves nothing to anyone else. There is no forward secrecy. Header fields are not authenticated. Replays are caught only by the `msgId` history of the last 2,000 messages.

## 10. Malformed input and bans

A link collects a "malformed" mark for: a frame that doesn't decode (in 1.1, unless it comes from a newer version, section 3); an `Invalid` fragment set; a bad HELLO (payload doesn't decode, version 0, or in 1.0 any version but 1, nodeId ≠ header `sender`, or a different nodeId on the same link); a SUMMARY, REQUEST, SUMMARY2 or REQUEST2 that doesn't decode; a report of 64 bytes or less; an ACK that doesn't decode or whose signature doesn't verify (one the key chain refuses for any other reason is dropped silently, section 6.4); a cancel shorter than 32 bytes (in 1.0, any size but 32); a PRIVATE without R, `sender` or E.

Five marks within 60 s close the link and ban the Bluetooth address it came from for 10 minutes: links from that address are refused and the node doesn't connect to it (`MeshEngine.malformed`). The ban falls on the link, not on the peer's identity. 1.0 also banned the nodeId the peer claimed in HELLO, and refused it in any later HELLO; since HELLO isn't authenticated, anyone could claim another phone's nodeId, send five bad frames and get that phone refused by its neighbours for 10 minutes ("ban framing"). 1.1 bans no nodeId, and no prefix either, since a phone can advertise or claim anyone's. A peer that comes back from another address, as after its prefix and address change, is accepted, and is banned again if it misbehaves again.

Silent drops, which don't count: rate limit, TEST in release, before HELLO, duplicate, known incident, an ACK that doesn't supersede; in 1.1 also what comes from a newer version and can't be used (`UNSUPPORTED`: frames, types on one link, summary kinds), new reports over the per-link limit (`REPORT_LIMIT`), new updates over theirs (`UPDATE_LIMIT`), responses that don't supersede or that the chain refuses, and unknown types over their limits (`OPAQUE_LIMIT`).

`UNSUPPORTED` never bans, since the peer may be a newer version, but a link that brings more than 10 of them in a minute (`MeshConfig.unsupportedPerMinute`) is closed, and the node waits 2 minutes, as after a rotation, before it connects to that peer again. The peer may connect to it meanwhile.

Consequence: to a 1.0 node, a new version, packet type or header flag, or a new field in a payload that relays parse (HELLO, SUMMARY, REQUEST, ACK, the size of CANCEL), is malformed, so a node that sends five of them within a minute gets banned by its 1.0 neighbours. A 1.1 node never sends a 1.0 peer any of these (section 7). Between 1.1 nodes, what a newer version adds is skipped, relayed or dropped without a mark (section 15). A new field in the sealed report body is invisible to relays; 1.1 staff phones read past it, 1.0 staff phones fail to decode the body and drop the report silently.

## 11. QR codes (`crypto/QrCodes.kt`)

Both formats are a prefix followed by the payload in base64url without padding. The decoder trims whitespace, refuses text longer than 512 characters and also accepts padding. The prefix carries the format version.

`SPF1.` friend card, at most 124 characters:

| Size | Field |
|---|---|
| 32 | X25519 public key |
| 32 | Ed25519 public key |
| 1 | `nickLen`, at most 24 (the encoder cuts longer names on a character boundary) |
| `nickLen` | nickname |

`SPS1.` staff card (legacy, read while phones move to SPS2), at most 169 characters. It contains the build's secret seeds, the same on every card. `tools/gen_staff_keys.py legacy` writes it:

| Size | Field |
|---|---|
| 32 | `boxSeed` |
| 32 | `signSeed` |
| 1 | role: 1 staff, 2 anchor, 3 control (staff keys plus the control-room console; older apps reject it as unknown) |
| 1 + n | team name, `u8` length, at most 24 |
| 1 + n | zone, `u8` length, at most 32 |

`SPS2.` shift card, at most 517 characters (the decoder takes up to 640). It holds secrets for its holder only. `tools/gen_staff_keys.py issue` writes it:

| Size | Field |
|---|---|
| 115..139 | the shift certificate (section 9); its role is the card's: staff, anchor or control |
| 32 | Ed25519 seed of the certificate's key |
| 1 | flags: `0x01` the day's report key certificate follows, `0x02` its X25519 seed follows too (only with `0x01`), `0x04` the build's report seed follows |
| 115 | the day's report key certificate, if `0x01` |
| 32 | its seed, if `0x02`: consoles and role leads |
| 32 | the build's report seed, if `0x04` |
| 1 + n | zone, `u8` length, at most 32 |

A phone accepts it only if the certificate holds now and every seed derives the key it stands for. An anchor's card has no report key; the anchor keeps only its shift key, which proves its role and signs nothing else.

`SPR1.` revocation: an ANNOUNCEMENT payload of kind 1 (section 6.8), at most 451 characters, printed by `tools/gen_staff_keys.py revoke`.

`SPE1.` gate code, 48 characters: the 32-byte hash of an event pack (section 16), printed by `tools/gen_staff_keys.py pack`. It carries no key and no venue: a phone that holds a valid pack with that hash switches to it, any other phone ignores the code. A sticker over the poster can only point to another pack the phone already checked.

## 12. Limits

| Limit | Value | Where |
|---|---|---|
| Payload after reassembly | 480 B | `PacketCodec.MAX_PAYLOAD` |
| Smallest usable frame | 64 B | `PacketCodec.MIN_FRAME` |
| Maximum TTL | 7 | `PacketCodec.MAX_TTL` |
| TTL at the origin | 7 for reports, ACKs, cancels, ANNOUNCEMENT and TEST, 6 for PRIVATE, 1 on one link, 3 for unknown types (section 5) | `PacketType.originTtl`, `CHAT_TTL` |
| Fragments per packet | 2..255 (`count u8`); 19 at most in practice | `PacketCodec` |
| Fragment sets per link, timeout | 8, 30 s | `Reassembler` |
| SUMMARY / REQUEST entries, also SUMMARY2 / REQUEST2 | 50; 47 in a SUMMARY2 with epochs | `Limits.SUMMARY_ENTRIES`, `SUMMARY2_EPOCH_ENTRIES` |
| Nickname | 24 B | `Limits.NICK_BYTES` |
| Zone | 32 B | `Limits.ZONE_BYTES` |
| Description | 300 B and 140 characters | `Limits.DESCRIPTION_BYTES`, `DESCRIPTION_CHARS` |
| Team name | 24 B | `Limits.TEAM_BYTES` |
| Chat text | 400 B | `Limits.TEXT_BYTES` |
| Group name, group nickname | 32 B, 16 B | `Limits.GROUP_NAME_BYTES`, `GROUP_NICK_BYTES` |
| Group size | 8, creator included | `Limits.GROUP_MAX_MEMBERS` |
| Largest report payload | 480 B with the reply key, the description cut to fit; 460 B without it; 455 B in 1.0 | section 6.3 |
| Epoch (1.1) | 255 reopens of one incident | `IncidentAck.MAX_EPOCH` |
| Own reports (local, not on the wire) | 3 in 10 minutes, 20 on a phone with the staff keys | `IncidentManager.RATE_MAX`, `STAFF_RATE_MAX` |
| Largest TEXT payload | 458 B (22 B to spare) | section 6.6 |
| Receive / send rate per link | 3/s burst 30; 2.8/s burst 28 | `MeshConfig` |
| Dedup | 4,096 keys, 15 min | `DedupCache` |
| Bans | 5 malformed in 60 s, 10 min, on the address of the link | `MeshConfig` |
| New reports from one link (1.1) | 6 a minute | `MeshConfig.reportsPerMinute` |
| New updates from one link (1.1) | 6 a minute, apart from reports | `MeshConfig.updatesPerMinute` |
| Updates a reporter sends (1.1) | 10 per report, one a minute | `IncidentManager.MAX_UPDATES`, `UPDATE_INTERVAL_MS` |
| Updates a node keeps (1.1) | the latest per incident, 50 in all | `IncidentCache` |
| SUMMARY2 of RESPONSES / UPDATES (1.1) | 39 / 50 entries | `Limits.SUMMARY2_RESPONSE_ENTRIES`, `SUMMARY_ENTRIES` |
| Largest response (1.1) | 275 B under a certificate, 136 B without | section 6.10 |
| Types relayed opaquely (1.1) | 12 a minute per link; 256 B, 480 B for ANNOUNCEMENT; relayed TTL at most 3 | `MeshConfig.opaquePerMinute`, `PacketType.OPAQUE_MAX_PAYLOAD`, `OPAQUE_MAX_TTL` |
| ANNOUNCEMENT relayed (1.1) | 4 a minute per link, apart from the 12 above, verified or not | `MeshConfig.announcementsPerMinute` |
| Certificate | 115..139 B; clock skew ±10 min | `Certificate`, `KeyChain.SKEW_S` |
| Revocation | 32 ids per announcement; 16 announcements kept; sent again every 10 min by the console | `Revocation.MAX_IDS`, `Revocations` |
| Notice | 4 parameters of 40 B, zone 32 B; holds at most 24 h; the latest per topic kept, 16 in all; sent again every 10 min (alerts) or 30 min by the console | `Notice`, `IncidentCache`, `Notices` |
| SUMMARY2 of NOTICES | 39 entries | `Summary2Codec.maxHeld` |
| Unsupported frames, types and kinds (1.1) | more than 10 a minute from one link close it, without a ban | `MeshConfig.unsupportedPerMinute` |
| Chat fan-out of a relay (1.1) | off by default; when on, ⌈log₂ n⌉ of n links, at least 1, the recipient's first | `MeshEngine.CHAT_FAN_OUT`, `FanOut` |
| Chat a phone writes (1.1) | 30 PRIVATE packets a minute; 16 outbox entries tried at once | `ChatManager.PACKETS_PER_MINUTE`, `MAX_IN_FLIGHT` |
| Advertised prefix (1.1) | new every 12 to 18 min, after the radio restarts and with every new advertising set; anchors keep the nodeId's | `AdvertisedPrefix`, `NewSetPrefix` |
| Store-and-forward on disk | packets up to 60 min old, traces up to 6 h, taken back at start; a file that can't be read is deleted | `IncidentCache.restore` |

## 13. Golden vectors and fuzzing

**Golden vectors.** `core/src/test/resources/golden/protocol-v1.txt` holds one line per vector, `name value`. Values are lowercase hex, except the `qr.*` vectors, which are the QR text. The comment above each vector lists its inputs, so the file can be used without reading Kotlin. It covers the derived keys and nodeIds, whole frames (with and without recipient, and three fragments), HELLO, SUMMARY, REQUEST, two report bodies, the cancel hash, a sealed report, the signed ACK bytes and the ACK, the cancel, the seven inner types of 1.0, a boxed PRIVATE payload, and both QR formats.

1.1 added vectors and changed none. Its encoders write `hello.v11`, `summary2`, `request2`, `frame.opaque`, `frame.opaque.relayed`, `incident.ack.assigned`, an ACKNOWLEDGED with the ASSIGNED flag (section 6.4), and `hello.prefix`, a HELLO with `PREFIX_IN_HELLO` and the advertised prefix after the nickname (section 6.1); then, for the field states and the epoch, `incident.ack.on_scene`, `.handed_over`, `.closed`, `.unfounded`, `.reopened` (epoch 1) and `.assigned.epoch` (ASSIGNED and epoch 1), `summary2.epochs`, and the bodies `incident.body.triage`, `incident.body.staff`, `incident.body.lost_friend` and `incident.body.location` (triage plus accuracy 12 m and landmark 4); and for the control channel (section 6.9), `frame.control` (a PRIVATE to the channel id, with a stand-in for the sealed payload), `control.heartbeat` and `control.heartbeat.unknown`; and for responses and updates (sections 6.10 and 6.11), `incident.body.reply` (`incident.body.location` with the reply key), `response.body`, `incident.response` (sealed to the reply key with the ephemeral pair from `seed(0xd0)`, signed with the staff key), `cert.control` and `incident.response.cert` (the same under a control shift), `update.body`, `incident.update`, `frame.update`, `summary2.responses`, `summary2.updates`, `request2.responses` and `request2.updates`. The others carry what a newer version may send and are built by hand: `hello.extended`, `summary2.unknown`, `incident.body.extended`, `incident.ack.unknown`, a signed ACK with status 0x7e and two bytes of extensions, and `incident.ack.future`, written the same way with status 7 before 1.1 gave 7 and the epoch a meaning: 1.1 now reads it as CLOSED_BY_CONTROL with the flags ASSIGNED and EPOCH and epoch 205, and still skips the flag bits it doesn't know; and `control.heartbeat.extended`, a heartbeat with an unknown flag bit and two bytes after the zone. 1.1 must read them all; 1.0 rejects every one of them.

The event pack (section 16) added `event.pack.hash` and `event.pack.signature`, for the body built in `GoldenVectorsTest` (`Golden.PACK_BODY`), and `qr.event`, the gate code with that hash; `eventPackVectorsVerify` checks the whole pack against `key.root.sign`. The signing was also checked through `tools/gen_staff_keys.py pack` and PyNaCl 1.6.2.

Notices (section 6.8) added `announcement.notice`, a pause under `cert.control`, `announcement.notice.cancel`, its withdrawal, `summary2.notices` and `request2.notices`, and changed none. They are checked by `GoldenVectorsTest` only.

The inner kinds of leaving a group and of the last zone (section 6.6) added `inner.left_group`, `inner.zone_request` and `inner.zone_update`, and changed none.

`HELLO_AUTH` added, and changed none: `key.root.sign` and the certificates `cert.organiser`, `cert.event`, `cert.staff` (with `cert.staff.id`) and `cert.report_key`; `hello.auth.nonce`, a HELLO with the nonce, and `hello.auth`, with the certificate and the proof over bob's nonce; `incident.ack.cert`, an ACK under `cert.staff`; `announcement.revocation`; and the codes `qr.shift` and `qr.revocation`. The certificates, the proof, the ACK, the revocation and `qr.shift` were also checked against PyNaCl 1.6.2 through `tools/gen_staff_keys.py`. 1.0 rejects the two HELLOs and the ACK.

Inputs are deterministic. Seeds are 32 consecutive byte values (`seed(0x10)` = `10 11 … 2f`): the staff keys come from `seed(0x10)` and `seed(0x30)`, alice from `seed(0x50)` and `seed(0x70)`, bob from `seed(0x90)` and `seed(0xb0)`. Ed25519 signatures are deterministic. A real sealed box uses a random ephemeral key, so the vector builds `crypto_box_seal` step by step with the ephemeral pair from `seed(0xd0)`. Implementations should check that they open it, not that they produce it. The `crypto_box` nonce is fixed to `e0 e1 … f7` by overriding lazysodium's `randomBytesBuf`, which is the only randomness `SodiumCrypto.box` takes from Java. When the file was created, the key, nodeId, hash, dedup, sealing, signature, box and QR vectors were also checked against PyNaCl 1.6.2 and Python's `hashlib`.

`GoldenVectorsTest` checks every vector both ways: the encoder must produce the stored bytes, and decoding the stored bytes (opening, verifying) must give back the inputs. It also checks that the file holds exactly the vectors the test knows.

**1.0 next to 1.1.** `Frozen10`, in the test sources, keeps the 1.0 decoders and encoders as they shipped. `GoldenVectorsTest` checks that its encoders still write the 1.0 vectors, that its decoders read them and the 1.1 HELLO, and that they reject what only 1.1 reads. `V10Node` is a test phone that reads and writes only through `Frozen10`, relays, stores and answers summaries as 1.0 did, and counts every packet 1.0 would have counted as malformed. `Protocol11Test` puts it next to 1.1 phones: reports, ACKs and chat in both directions, store-and-forward through SUMMARY and REQUEST, two 1.0 phones talking through a 1.1 relay, and packets only 1.1 reads crossing 1.1 phones without reaching a 1.0 one, all without a single malformed packet on either side. `IncidentStatesTest` does the same for the incident flow: a 1.0 phone next to a 1.1 console shows the closest status it knows and keeps a reopened incident closed, and a 1.0 relay between a 1.1 reporter and a 1.1 console carries a lost friend's report and the 1.0 status of an UNFOUNDED.

**Changing a vector.** Any change to these bytes, other than `dedup.private`, is a wire change. It needs a new protocol version or a compatibility plan (section 15) and a note with the reason in the project log. To regenerate after an intended change, run

```
.\gradlew.bat :core:test --tests ro.safetyplease.core.protocol.GoldenVectorsTest
```

`encodersProduceTheStoredBytes` then fails and lists the current value of every changed vector, already in the file's format; copy those lines into the file and update the comments if an input changed.

**Fuzzing.** `DecoderFuzzTest` uses `kotlin.random.Random` with a fixed seed and no extra library, and runs in under 3 seconds. Every test has a 10-second timeout. For `PacketCodec.decode`, all payload decoders, the `Reassembler` and both QR decoders, it feeds valid encodings of random objects and mutations of them: random bytes, bit flips, truncation, trailing bytes, boundary values in single bytes (most often a length or a count), cut and inserted ranges, and fragments forged with arbitrary counters and lengths. It checks that:

- nothing throws, except `IncidentReportCodec.incidentId` and `sealed` on payloads shorter than 16 bytes, which `isWellFormed` rejects (section 6.3);
- every call allocates less than 32 KiB, which catches a buffer sized from an untrusted length; the test fails if the JVM can't measure per-thread allocation, rather than passing without the check;
- a successful decode re-encodes to the same bytes, unless a string came back with U+FFFD (open question 4); the decoders that skip what a newer version may add (HELLO, SUMMARY2, REQUEST2, the report body, the cancel) must instead give the same bytes when their own encoding is decoded and encoded again;
- the `Reassembler` never holds more than 8 sets or returns more than 480 bytes, and rebuilds any packet from its frames in any order, with repeats;
- any change to a sealed report, a boxed PRIVATE payload or a signed ACK makes opening or verifying fail.

## 14. Known limits and open questions

1. **Forward compatibility starts with 1.1.** 1.0 counts an unknown version, type or header flag, or an extra byte in a payload that relays parse, as malformed and bans for it (section 10). 1.1 doesn't (section 15), and sends a 1.0 peer nothing 1.0 would reject; but as long as 1.0 phones are around, nothing new reaches them. What 1.0 phones accept from a newer version: new `Inner` types, which only the recipient sees; NodeFlags bits `0x20`..`0x80`, which 1.0 ignores; and a PRIVATE addressed to a "channel" id instead of a nodeId, which 1.0 relays without store-and-forward. An unknown inner type or inner flag still makes the recipient drop the message silently, in 1.1 too.
2. **The header is not authenticated.** `sender`, `timestamp`, `recipient`, `ttl` and the E flag can be changed by anyone on the path. A PRIVATE is still checked end to end by the box, and an ACK by its signature, but neither covers the header.
3. **No freshness.** Timestamps aren't checked against the receiver's clock. A node remembers an incident for 6 hours after it last saw it (`IncidentCache`); a report or ACK replayed after that is accepted as new. The ACK order prefers the older timestamp, so at equal status a phone whose clock runs behind wins. `u32` seconds overflow in 2106.
4. **Strings are not validated as UTF-8.** A malformed sequence decodes to U+FFFD, so a decoded string can re-encode to more bytes than its limit and make the encoder throw. No receiver re-encodes a received string today, so this is latent. Should decoders reject invalid UTF-8?
5. **Coordinates are not range-checked** on decode; a body can carry a latitude of ±2147°.
6. **The `incidentId` is not bound to the sealed body.** Anyone can wrap a captured sealed body under a new `incidentId`, and staff would see it as a new report with the same content and the same cancel hash.
7. **The shared staff key is still there.** SPS2 codes, the key chain and revocations (section 9) limit a stolen phone to its shift and its role, but only for phones that set `HELLO_AUTH`, and only once nobody uses SPS1 codes: every holder of an SPS1 code can still read every report sealed to the build's key, past ones included, and sign any ACK for any team, the console's statuses included, and phones from before `HELLO_AUTH` trust only that key. The build drops it once the fleet runs SPS2. Reports themselves are not signed: the staff fields and the team in a staff report say what the author claims.
8. **Reports and cancels can't be checked by relays**, and relays don't enforce `sender = 0` on them. Any node can fill a neighbour's report cache (50) with sealed garbage and push real reports out.
9. **Anonymity is partial.** The first neighbour receives `ttl 7` and knows the sender is the origin. Since 1.1 the advertised prefix is random and changes about every 15 minutes with the address (section 2), so a passive scanner can follow a phone only between two changes; an anchor keeps a static one; a phone still on 1.0 advertises its nodeId prefix all day; and anyone who links to a phone reads its nodeId in HELLO, so an observer that connects again after each change can still follow it; older builds also broadcast PENDING_INCIDENT while a report waited for an ACK, which showed who had just reported.
10. **HELLO authenticates roles, not identities.** The proof (section 6.1) shows that a peer holds a shift certificate; it doesn't bind a nodeId to anything else. A node can claim another's nodeId. In 1.0 it could get that phone banned by sending five bad frames ("ban framing"); 1.1 bans only the address of the link (section 10). It can still make a neighbour close the real phone's link, since two links with one nodeId count as duplicates, and it can claim another phone's advertised prefix, so that a neighbour linked to it doesn't connect to the real one. STAFF counts only once proven; a peer from before `HELLO_AUTH` can't prove one, so its staff count as participants on links with current phones (section 2). Sessions go by the claimed role: a phone that claims ANCHOR gets sessions, one at a time on each phone and 60 s at most, and can send heartbeats in a real anchor's name, which the console shows as indicative (section 6.9).
11. **Fragment sets are easy to disturb** for a peer on the same link: it can evict the 8 sets or reuse a `fragId` with a different header and invalidate one. It only affects that link.
12. **480 bytes is the ceiling.** Photos and event packs need a new transfer mechanism. The report has 20 bytes to spare and TEXT 22, so a 64-byte signature on a chat message doesn't fit without shortening the text.
13. **The codec allows R on every type.** Only PRIVATE uses it. A report with R set would be stored and served by SUMMARY/REQUEST but delivered only to the named node. 1.1 still allows it; should a later version reject it?
14. **The dedup key is truncated to 64 bits.** Collisions are negligible at 4,096 entries, but a key is not a secure identifier.
15. **Chat messages carry no send time.** The displayed time is the arrival time, and a replay within the history is answered with a fresh DELIVERED.
16. **Prefix collisions.** Two phones with the same 32-bit prefix look like one peer to the connection policy until one of them changes it. About 0.3 % at 5,000 phones. A ban no longer follows the prefix.
17. **1.0 phones and the field states.** A 1.0 phone gets the closest status it knows only from the live flood and from 1.0 relays (section 8): a 1.0 phone that joins later, through 1.1 phones only, keeps the status it had. A reopened incident stays closed on 1.0 phones, and a 1.0 staff phone still closes an urgent report on the reporter's cancel. Both go away only when the staff fleet runs 1.1. And a 1.0 staff phone never shows a report with triage answers, location fields, a reply key, a staff report or a lost friend (section 6.3): it drops the body silently, and since current reporters always add the reply key, it shows none of theirs. Responses and updates never reach a 1.0 phone at all. So staff phones and the console move to 1.1 before reporters do.

## 15. Version 1.1

What changed from 1.0:

| | 1.0 | 1.1 |
|---|---|---|
| Header | version 1 | version 1, unchanged; the node announces 1.1 with `PROTOCOL_1_1` (`0x10`) in HELLO and advertising |
| Another header version or flag | malformed | dropped as unsupported |
| Unknown type | malformed | relayed as it is, at most 3 hops, 256 B, 12 a minute per link, never to a 1.0 peer (section 7.1) |
| HELLO | nothing after the nickname | the advertised prefix after the nickname, to peers that set `PREFIX_IN_HELLO`; with `HELLO_AUTH`, a nonce and a certificate proof after it; what follows skipped |
| Advertising | the nodeId prefix | a random prefix, new every 12 to 18 minutes with a new address; anchors keep the nodeId prefix (section 2) |
| Who connects | the lower nodeId prefix | the lower advertised prefix; to a peer without `PREFIX_IN_HELLO`, only from the higher nodeId |
| Bans | the claimed nodeId and the address | the address of the link only |
| ACK | status 1..4, nothing between team name and signature | statuses 1..9 (field states, closing by the console, reopening), a newer one relayed but not stored; extensions before the signature, signed and kept, starting with the ACK flags byte (ASSIGNED, EPOCH and the epoch) |
| Supersede order | status, then oldest, then team | epoch, then rank (section 8), then a take over an assignment, then oldest, then team |
| Report body | flags `0x01`..`0x04`, categories 1..7, nothing after | triage (`0x08`), staff fields (`0x10`), location fields (`0x20`) and the reply key (`0x40`) after the cancel hash, category 8 (lost friend); unknown flags and the fields after those skipped; unknown category kept |
| Responses and updates | | INCIDENT_RESPONSE (`0x13`) from the console to the reporter and INCIDENT_UPDATE (`0x14`) from the reporter to staff, stored and forwarded as SUMMARY2 kinds 2 and 3, never to a 1.0 peer |
| Cancel by the reporter | closes any open incident | closes it by itself only where a wrong cancel costs little; elsewhere staff checks it |
| CANCEL | 32 bytes | at least 32 |
| Store-and-forward | SUMMARY, REQUEST | SUMMARY2, REQUEST2, with a kind and the epochs, between 1.1 peers; SUMMARY and REQUEST with 1.0 peers |
| Origin TTL | 7 for every flooded type | per type: 7 for incidents and TEST, 6 for PRIVATE (section 5) |
| Chat relay | flood | flood; a relay can pass chat on ⌈log₂ n⌉ of n links instead, off by default (section 7) |
| New reports from one link | no limit | 6 a minute, and no more requested than that |
| ANNOUNCEMENT | | `0x30`, at report priority, 4 a minute per link; with `HELLO_AUTH`, signed revocations relayed like reports, and the console's notices, the latest per topic stored and forwarded as SUMMARY2 kind 4 |
| Keys | the build's shared pair | with `HELLO_AUTH`: a chain from Ping Up's root, shift certificates, ACKs signed under them (only to `HELLO_AUTH` peers), a report key per day, revocations; the shared pair stays for SPS1 |
| Links with anchors | held like any other | short sessions that the anchor starts, round the phones it sees; a slot each phone keeps for them, `ANCHOR_SLOT` (`0x80`) in advertising (section 2) |
| Control channel | | a PRIVATE to the id `SP-CTRL1`, sealed to the staff key: anchor heartbeats (section 6.9) |
| What can't be used | malformed, five in a minute ban | dropped; more than 10 a minute close the link, without a ban |

What a newer version can do without 1.1 phones dropping or banning it:

- Keep the header as it is: version 1, the same flags. A frame with another version or a new header flag is dropped by 1.1 and malformed to 1.0.
- A new flooded type takes a code from `0x10` up and must make sense within 3 hops of a 1.1 relay, in 256 bytes and at 12 a minute per link; a new type that stays on one link takes `0x06`..`0x0F`. Organiser announcements take `0x30`, with a kind of their own from 3 up.
- New fields go after the auth fields in HELLO; in an ACK, after the fields of the flags 1.1 knows, behind a new flag bit (`0x08` and up); in the report body after the reply key, behind flag bit `0x80`; in a response, between the certificate and the signature, behind a new flag bit (`0x02` and up); in an update body after the location fields, behind a new flag bit (`0x08` and up); in a notice, between the certificate and the signature, behind a new flag bit (`0x02` and up); after the token in a CANCEL; in a SUMMARY2 of kind INCIDENTS after the epochs, behind a new bit of its flags byte (`0x02` and up); and after the entries in a REQUEST2 of kind INCIDENTS. Other things to store and forward go in a new SUMMARY2 kind (1 to 4 are taken).
- ACK statuses above 9 cross 1.1 phones but aren't stored or shown there: a status that has to stick needs relays that know it. A new status needs a place in the rank table that keeps the order of the old ones.
- A peer that doesn't announce 1.1 gets only what 1.0 accepts. A newer version announces itself with a bit of its own and sends its own additions only to peers that set it, as `PREFIX_IN_HELLO` (`0x20`) does for the prefix in HELLO and `HELLO_AUTH` (`0x40`) for the nonce, the proof and ACKs under a certificate. `0x80` went to `ANCHOR_SLOT`, which is only advertised, so no bit is left: the next version needs a field of its own, after the auth fields in HELLO.

### Pilot metrics

The pilot metrics (ARCHITECTURE.md, "Metrici de pilot") add nothing to the wire: reporter timings, mesh snapshots and the mobile network log are measured and kept on the phone, and the console joins them with its log by incidentId only when a person exports. The golden vectors are unchanged.

## 16. Event pack and exercises (`event/`)

Nothing here travels on the mesh: the pack is a file a phone loads before the festival, and the wire, the golden vectors of sections 3 to 9 and every 1.0 and 1.1 rule stay as they are.

**The file.** UTF-8 JSON, at most 4 MiB: `{"format": "pingup-event", "version": 1, "body": "<text>", "signature": "<hex>"}`. `body` is itself JSON, kept as text so the signed bytes are exactly the ones read; the signature is the organiser's Ed25519 key over `SP-PACK-v1` (10 bytes) ‖ the body's UTF-8 bytes. The pack's hash, which the gate code carries, is SHA-256 over the same bytes. Body fields:

| Field | Rule |
|---|---|
| `id`, `version` | 1 to 64 characters, and an integer from 1. A phone keeps one pack per `id` and kind, and replaces it only with a higher version |
| `kind` | `real` or `exercise` |
| `name` | what phones show |
| `validFrom`, `validUntil` | seconds: when phones may load and use it |
| `organiser`, `event` | the organiser's certificate (signed by the root) and the event's (signed by the organiser), hex, section 9 |
| `reportKeys` | the days' report key certificates, hex |
| `box`, `sign` | optional 32-byte public keys that replace the build's shared pair (section 9); an exercise must carry its own |
| `venue` | the venue as in `app/src/main/assets/venue.json`: zones, numbered POIs, DEA, the meeting point |
| `grid` | optional `{"cellMeters": n}`, 10 to 500, the site grid (50 by default) |
| `firstAid` | optional `{"file name.md": "sheet text"}`, the first-aid sheets in the format of `app/src/main/assets/firstaid` |
| `notices` | optional, the notice templates (section 6.8) in the format of `app/src/main/assets/notices.json`, which the app uses without them: a list of `{"id": 1..255, "topic": "alert" \| "heat" \| "schedule" \| "transport", "level": n, "text": {"ro": ..., "en": ...}, "params": [{"ro": ..., "en": ...}]}`, `{1}` to `{4}` in the text for the parameters. A template whose id repeats, whose level its topic doesn't have, without Romanian or English, over 400 characters, with more than 4 parameters or a placeholder past them makes the pack malformed |
| `pages` | optional `{"<language>": {"<file name>.md": "page text"}}`, the static information pages (beach, transport, wristband and money, harm reduction, safe spaces), in the format below |

Unknown fields are ignored.

**Information pages** (`pages`, `InfoPages`). Nothing new on the wire and the golden vectors are untouched: an older phone ignores the field. The first key is a two-letter lowercase language code. A phone reads its own language, else `en`, else whatever language the pack has; a pack with no `pages` makes it show the pages the app ships (`app/src/main/assets/pages` in Romanian, `pages-en` in English). A file name is at most 64 characters of letters, digits, `.`, `_` and `-`, ending in `.md`; pages show in file-name order. The text is a small Markdown subset: `# ` the title (required), `## ` a heading, `- ` a bullet, `! ` a warning, `Aprobat: ` (`Approved: `) the approval line and any other line a paragraph; a line right under a block continues it and an empty line ends it. The first paragraph is the summary shown in the list. Until a page has an approval line it shows as a draft, and the facts only the organiser knows are marked `(sursa: organizatorul)` (`(source: organiser)`) in the text. A pack is MALFORMED if `pages` has another shape, a bad language code or file name, a page without a title or over 16 KiB, more than 24 files in a language, or more than 200 KiB of text in all (`MAX_TOTAL_BYTES`).

**Checking it** (`EventPacks.check`). With the root key the app ships: the organiser's certificate holds now under the root (`KeyChain.status`); the signature verifies with its key; the event's certificate was signed by the organiser and every report key whose day isn't over by the event, each checked at the moment of its window nearest to now, since a pack is loaded before its event; and now is inside the pack's window, and not past the event's, give or take the 10 minutes of section 9. The revocations the phone kept (section 6.8) count: a revoked organiser or event makes the pack REVOKED, a revoked report key is only left out. Otherwise the pack is MALFORMED, TAMPERED (the signature fails), WRONG_CHAIN, REVOKED, NOT_YET or EXPIRED, and the phone keeps nothing. A pack is checked again at every start; once its event is over or revoked, the phone goes back to what the app ships.

**Using it.** Phones load packs from a file or an https link (Eu › Eveniment); several can be kept, and one is in use. Switching restarts the app, which then builds everything from the pack in use: the venue and grid, the first-aid sheets, a `KeyChain` that trusts its organiser and event and its report keys, and its shared keys if it has them, in place of `venue.json` and the `chain` and `reportKeys` of `staff_public.json`. The root stays in the app. The phone leaves its role on a switch; a shift code it holds for the event switched to comes back on start. The gate code (`SPE1`, section 11) only switches to a pack the phone holds and that still checks; the gate code of the pack in use changes nothing, so a staff phone keeps its role. Before the restart every store is written out, so a report or message from just before the switch stays.

**Exercises** (`kind: exercise`). An exercise has an event key and a shared pair of its own: `EventShelf` refuses an exercise whose event key a real pack on the phone uses, and the other way round. Since the chain and the keys come from the pack in use, nothing crosses: a real phone refuses an exercise ACK (its certificate leads to another event, so it is REFUSED and dropped without a mark, section 6.4) and can't open an exercise report, and the other way round; relays carry both as before. While an exercise is in use, every screen shows the band "EXERCIȚIU" above it, in place of the status bar's space, the titles of incident, report and mesh notifications start with it, and the insistent alert only vibrates and never takes over the screen; the incidents, the store-and-forward cache, the console log and the staff keys are kept under names of their own (`incidents_exercise.json`, `console_log_exercise.json`...), so a staff phone holds both the real and the exercise keys and the pilot's log never mixes the two. An exercise is announced to everyone taking part; a phone in an exercise doesn't open real reports until it goes back to the real event, so exercises don't run during a show.
