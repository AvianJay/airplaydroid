# Legacy (AirPlay 1) support - progress notes

> ## Legacy mirroring WORKS - picture verified on two real receivers (2026-09-25)
>
> The RTSP type-110 path (below) mirrors to **LonelyScreen** and **iPhoneMirror**:
> pair-verify, FairPlay, the `ekey` wrap, AES-CTR video, all accepted, and the
> picture shown and seen by eye. The app itself, running in an Android emulator,
> mirrored its own live screen to LonelyScreen through `MirrorService`.
>
> The FairPlay SAP core is the one described in
> **[fairplay-status.md](fairplay-status.md)**. The port-7100 `/stream` path is
> still verified only against the mock: no receiver here really serves it.

Working notes for the legacy-AirPlay sender work. The research report is
[fairplay-research.md](fairplay-research.md); this file tracks **implementation**
state so it survives a context reset.

## Why legacy is a separate path

A receiver's model name does not decide which protocol it wants. What decides is
whether it advertises **HAP pairing**:

| | AirPlay 2 / HAP | Legacy (AirPlay 1) |
|---|---|---|
| Auth | HomeKit pair-setup / pair-verify | **FairPlay SAP** (`/fp-setup`) |
| Control channel | HAP-encrypted | plaintext |
| Video key | HKDF from pair-verify secret | FairPlay `ekey` (72-byte `FPLY`) |
| Video crypto | HAP frames | **AES-CTR** |
| Mirror endpoint | RTSP `SETUP`, stream type 110 | RTSP `SETUP` type 110 (iOS 9+), or port 7100 `/stream` (iOS 6-8) |
| Clock | PTP (or NTP) | NTP, receiver-initiated |
| Status | works, verified on one Apple TV | **type 110 works on two receivers**; port 7100 mock-only |

`MirrorController.refusalFor` used to refuse a receiver without `supportsHapPairing`
with a message saying it "needs Apple's FairPlay". That message is now obsolete:
such a receiver takes the legacy path instead.

## Hardware targets

| Receiver | Where | m2 challenge | m3 behaviour |
|---|---|---|---|
| **LonelyScreen** (Windows) | `192.168.31.51:7000` | **fresh, random per session** | **accepts our m3 (m4)** |
| **iPhoneMirror 1.8.3** (Windows, `airplay2dll`) | `192.168.31.51:5001` | fresh | **accepts our m3 (m4)** |
| AirScreen 2.15.1 (Android) | `192.168.31.141:5000` | static | 12-byte refusal frame |
| AS-2112123AG dongle | (via phone tunnel) | static | 12-byte refusal frame |

**LonelyScreen is the best target**: directly reachable, and it issues a *fresh*
challenge per session, so it cannot be fooled by a hard-coded or replayed
response. The other two replay a canned challenge, which means they cannot
distinguish a correct response from a memorised one.

AirScreen is reachable over `adb forward tcp:15000 tcp:5000` when its phone is
attached.

Captured ground truth (fixtures in `protocol/src/test/resources/fairplay/`):

| Fixture | Size | What it is |
|---|---|---|
| `appletv32_fpsetup_m2_body.bin` | 142 | a real m2 |
| `appletv32_m3_rejection_frame.bin` | 12 | `1e1e1e1e 03 01 04 9c 00 00 00 00` |
| `appletv32_info_body.bin` | 2235 | the `/info` XML plist |
| `sender_fpsetup_m1_request.bin` | 168 | a real m1 request |
| `golden_vectors.csv` | 47 KB | 142 full-chain vectors (challenge to response) |
| `hardware_attested.csv` | 7 KB | 12 accepted challenge + localSAP + response triples |
| `bridge_x9head.csv`, `sap_hash.csv` | 23 KB | 70 component vectors |

## The RTSP type-110 legacy path (iOS 9+) -- what receivers actually speak

Every third-party receiver checked answers this and not port 7100: LonelyScreen
listens only on 7000, and iPhoneMirror's 7100 answers every request -- even
`GET /nonexistent` -- with an empty `200`. Implemented in
`mirror/LegacyRtspMirrorSession.kt`, all on **one** control connection (the
receiver keeps the FairPlay state per connection, and unwraps `ekey` with the m3
that connection carried):

```
GET /info                                   display size (binary or XML plist)
POST /pair-setup     our Ed25519 pk (32)    -> theirs (32)
POST /pair-verify    01000000|X25519|Ed     -> their X25519 | AES-CTR(their sig)
POST /pair-verify    00000000|AES-CTR(sig)  -> 200
POST /fp-setup x2                           FairPlay SAP (m1..m4)
SETUP  {ekey, eiv, timingPort, et=32, ...}  -> maybe {eventPort, timingPort}
SETUP  {streams:[{type:110, streamConnectionID, timestampInfo}]} -> dataPort
RECORD
TCP dataPort: 128-byte iOS 9 headers + AES-CTR video; POST /feedback every 2 s;
the receiver queries our clock over UDP (AirTunes 80 d2 or NTP mode 3)
```

The pair-verify keystream is **one** AES-CTR stream: the receiver's signature
takes bytes 0..63 and ours 64..127 (`LegacyPairVerify.kt`).

### The video key: two seeds, and receivers disagree

```
seed = RAW:   the FairPlay key itself
seed = MIXED: SHA-512(fairplayKey || pair-verify X25519 secret)[0:16]
key  = SHA-512("AirPlayStreamKey" + streamConnectionID(decimal, unsigned) || seed)[0:16]
iv   = SHA-512("AirPlayStreamIV"  + streamConnectionID || seed)[0:16]
```

Measured on 2026-09-25, each receiver showing a picture with one seed and nothing
with the other:

| Receiver | Wants | SETUP replies carry a `timingPort` |
|---|---|---|
| LonelyScreen | **RAW** | no |
| iPhoneMirror (`airplay2dll`, RPiPlay lineage) | **MIXED** | yes |

`X-Apple-PD: 1` on the pairing requests (doubletake's signal for mixing) did not
move LonelyScreen to MIXED. `KeySeed.AUTO` therefore mixes when a SETUP reply
advertised a `timingPort` and uses RAW otherwise. That is a heuristic from two
receivers, not a rule -- doubletake reports a real Apple TV 3 that queries timing
yet wants RAW -- so the app lets the user override it per receiver (row menu,
"Video key", `LegacyVideoKeyStore`).

### The iOS 9 packet header

The type-110 data channel uses the header current senders send (the one the
AirPlay 2 path already sends an Apple TV), not the nto one: type is byte 4 alone,
byte 5 is `0x10` on a keyframe, bytes 6-7 are `16 01` on a codec packet (UxPlay
tells H.264 from HEVC by byte 6) and `1e 00` on a heartbeat; the codec packet
carries the picture size as floats at 16, 40 and 56. Timestamps count from 1970
(RPiPlay reads them without the NTP epoch shift). LonelyScreen displayed both
header styles; iPhoneMirror was only tried with this one.

### Things only hardware showed

- **iPhoneMirror renders nothing until a clock query is answered.** From the
  Android emulator, behind QEMU's NAT, it accepted the whole session and sat in
  "Handshaking" -- yet the very same encoded stream, recorded by the mock and
  replayed from the PC, displayed. Forwarding UDP 7010 into the emulator
  delivered the queries, but QEMU rewrites the reply's source port, which a
  connected socket drops. An emulator limitation, not the app's; a phone on the
  LAN answers from the port it was asked on.
- **A decoder that attaches late needs SPS/PPS again.** iPhoneMirror opens its
  pipeline seconds into the stream; with the codec packet sent once it decoded
  nothing, however many IDRs followed. `LegacyVideoStream` now sends the codec
  packet before every keyframe.
- **A new sender id triggers a first-connection dialog** on iPhoneMirror. The app
  presents one stable id per install (`LegacyVideoKeyStore.deviceId`).
- **The emulator's H.264 encoder ignores a 1 s keyframe interval** (IDR every
  ~5 s) and emits ~7 fps from a still screen.
- **Android's XML parser threw on `isXIncludeAware = false`**, so every XML plist
  -- including an AppleTV3's `/info` -- failed to parse on a phone while passing
  every JVM test. Fixed in `XmlPlist`.
- LonelyScreen crashed (access violation) once on an early emulator stream; not
  reproduced after the codec-resend and header changes, cause unknown.

### How the stream was checked when a receiver showed nothing

`MockLegacyReceiver --rtsp <dir>` records each session's m3, `ekey`, `eiv`,
stream id and data channel. Because the mock's m2 is the static capture, the key
can then be unwrapped offline with any `playfair_decrypt` (airplay2-receiver's
Python port was used) and the app's real frames decrypted and fed to ffmpeg. The
same oracle unwrapped 8/8 `ekey` records this code wrapped; they are pinned in
`keywrap_playfair_verified.csv` (`FairPlayKeyWrapPlayfairTest`).

### Adding a receiver by address

mDNS does not cross a VPN or reach the host from an emulator, so the picker has
**Add by address**: `AddressLookup` asks `/info` (with the `txtAirPlay`
qualifier an Apple TV honours) and maps a plain `/info` dictionary onto the same
TXT keys, so the transport rule applies unchanged. From the emulator the host is
`10.0.2.2`.

## Implemented

| File | State |
|---|---|
| `fairplay/FairPlayRecords.kt` | FPLY framing, m1/m2/m3/m4 + refusal frame |
| `fairplay/FairPlaySapSession.kt` | sender handshake, fresh local SAP per session |
| `fairplay/FairPlayMessageCipher.kt` | m3 body cipher (AES-128, baked mode keys) |
| `fairplay/FairPlayWhiteBox.kt` | Phase 1 white-box AES -> GP buffer |
| `fairplay/FairPlayWhiteBoxTables.kt` | GENERATED from `_research/gen_wbaes_tables.py` |
| `fairplay/FairPlaySapCore.kt` | SAP hash + descriptor + bridge (40/40 + 30/30 vectors) |
| `fairplay/FairPlayBridge.kt` | MD5 family (transcribed) |
| `fairplay/FairPlayPhase2.kt` | Phase 2 driver (the analytical pipeline) |
| `fairplay/FairPlayPhase2Tables.kt` | every Phase 2 constant table (generated) |
| `fairplay/FairPlayPhase2Rounds.kt` | NEON prologue + WB-MD5 rounds + staging |
| `fairplay/FairPlayPhase2Spn.kt` | the two white-box AES SPN passes |
| `fairplay/FairPlayResponderImpl.kt` | the assembled responder |
| `fairplay/FairPlayKeyWrap.kt` | 72-byte `FPLY` ekey for `param1` / `a=fpaeskey` |
| `mirror/LegacyStreamPackets.kt` | 128-byte header packetisation + `NtpClock` |
| `mirror/LegacyVideoStream.kt` | AES-CTR frame encryption + packet writer |
| `mirror/LegacyMirrorSession.kt` | `/stream.xml` + `POST /stream` |
| `mirror/LegacyMirrorSessionFactory.kt` | `connect`: RTSP type 110 first, port 7100 if refused |
| `mirror/LegacyRtspMirrorSession.kt` | the RTSP type-110 session; **hardware-verified** |
| `mirror/LegacyTimingServer.kt` | answers the receiver's clock queries; UDP 7010 if free |
| `pairing/LegacyPairVerify.kt` | Ed25519 `/pair-setup` + two-round `/pair-verify` |
| `AddressLookup.kt` | a device from `/info`, for receivers mDNS cannot see |
| `devtools/LegacyMirrorProbe.kt` | streams a file over either legacy path from the PC |
| app: `LegacyVideoKeyStore` | per-receiver key seed override, stable sender id |
| `mirror/VideoStreamSink.kt` | shared sink so `ScreenEncoder` serves both protocols |
| `protocol/MirrorTransport.kt` | the one place the HAP-vs-legacy decision is made |
| `devtools/MockLegacyReceiver.kt` | offline fixture receiver, two session policies |
| app: `MirrorController` / `MirrorService` | routes legacy devices to the legacy path |
| app: picker | shows a **Legacy** badge; no longer says "Can't mirror" |

`LegacyMirrorSessionFactory` lives in the protocol module, not `app/`, because it
has no Android dependency - which is what lets `LegacySessionEndToEndTest` drive
the whole handshake over real sockets against the mock receiver.

## The crypto chain, as implemented

```
m2 challenge (128 B)
  -> FairPlayWhiteBox.phase1(challenge)                     => gp (128 B)
       -> FairPlaySapCore.bridgeX9HeadForSap(localSap, gp)  => x9Data (20 B)
            -> FairPlayPhase2.exchangeFromX9(x9Data)        => response (20 B)
                 -> FairPlayRecords.m3(mode, localSap, response)
                      -> FairPlayMessageCipher.encryptBody(...) => the 128-byte body

stream AES key (16 B)
  -> FairPlayKeyWrap.wrap(receiverSap, localSap, key)       => 72-byte param1
       -> LegacyMirrorCipher(key, iv).apply(frame)          => AES-CTR frame
```

**The response is a function of BOTH the challenge and the local SAP.** `gp`
comes from the challenge; the local SAP is the sender's own per-session value and
the receiver folds it into its check. Passing the challenge in both slots returns
20 perfectly plausible bytes that no receiver accepts. Upstream:

```go
gp := wbaesFullPhase1(payload)                   // payload = m2 challenge
x9 := bridgeX9DataClosedForSAP(s.localSAP, gp)   // localSAP = this session's
```

`FairPlaySapSession` therefore generates the local SAP *before* the exchange and
calls `respondWithSap`.

## The m3 body is encrypted

Bytes 16..144 of the m3 carry the local SAP **through the mode's message cipher**,
not in the clear:

```go
body := fpsapcore.EncryptMessageBodyMode3(s.localSAP)
copy(m3[16:144], body[:])
```

This was a real bug here: `FairPlayMessageCipher.encryptBody` existed and was
unit-tested but was never called on this path, so every m3 went out with a raw
body and every receiver rejected it. The frame looked perfect - correct framing,
correct label, correct response - which is why only hardware caught it.

## The transport rule

`MirrorTransport.forDevice` is the **single** decision point. It reads the
device's feature bits, not its model name:

| Receiver | `features` | Screen | HAP pairing | Transport |
|---|---|---|---|---|
| AS-2112123AG dongle | `0x5A7FFFF7,0xE` | set | **clear** | LEGACY |
| Apple TV 4K (tvOS 26.6) | `0x4A7FDFD5,0x3C177FDE` | set | set | HAP |
| AirPort Express | `0x200` | clear | clear | UNSUPPORTED |

Both the picker's badge and the service's choice of session call this, so they
cannot disagree. An earlier draft duplicated the condition in two files, which
would have let the badge say "Legacy" while the service took the HAP path.

## Key correction to the published literature

The response core is **not** "~500 KB of white-box tables". Two measurements:

- The **m3 body** is ordinary AES-128 round operations with baked, per-mode round
  keys - about 1.5 KB of constants for all four modes. Verified: decrypting a
  captured frozen m3 body and re-encrypting reproduces it byte for byte.
- The **Phase 1** tables are carried as small base tables plus a
  `(base, inXor, outXor)` spec. `_research/probe_phase1.py` confirms the 160
  Type-I tables are **not** XOR-affine images of the AES S-box (0/160), so Phase 1
  genuinely is irreducible - but the *source literals* are only **2,624 bytes**;
  the rest is derived at runtime.

## Two mirroring key paths - do not mix them up

There are two ways an AirPlay 1 receiver can be handed a mirroring key, and they
derive the cipher **differently**. This project implements the first.

| | port-7100 `/stream` (**implemented**) | RTSP `SETUP` type 110 |
|---|---|---|
| key | FairPlay-unwrapped key, used **raw** | `SHA-512("AirPlayStreamKey"+id+seed)[0:16]` |
| IV | `param2`, sent in the request | `SHA-512("AirPlayStreamIV"+id+seed)[0:16]` |
| needs `streamConnectionID` | **no** | yes |

The nto spec's `POST /stream` body carries `param1` (FairPlay-wrapped key) and
`param2` (the IV) and has **no** `streamConnectionID` field. `doubletake` agrees:
its `deriveStreamMasterKey` returns the raw key for a legacy receiver ("using raw
fpAesKey (legacy receiver or no SharedSecret)"). RPiPlay/UxPlay's
`mirror_buffer_init_aes` does the SHA-512 derivation, but it is reached only from
`raop_rtp_init_mirror_aes(streamConnectionID)` - the SETUP path.

An earlier draft applied the SHA-512 derivation to the legacy path, which would
have been wrong twice over: it hashes with an id this path never sends, and the
receiver would never match it. Corrected.

## The video keystream is continuous, not per-packet

The receiver (`mirror_buffer_decrypt`) carries `nextDecryptCount` and a saved
keystream tail between payloads, so the effect is **one contiguous CTR keystream
over the concatenated payloads**. A per-packet counter produces a first frame
that decrypts and every later frame that does not - recorded upstream as a
"counter desync issue (first packet works, subsequent packets fail)".

Two consequences that are easy to get wrong:

- The 128-byte packet header is **not** encrypted and does **not** advance the
  keystream.
- The codec-data packet is **not** encrypted and must **not** advance the
  keystream either - a receiver does not decrypt it, so advancing there would
  desynchronise every frame after it.

Both are pinned by tests (`LegacyVideoStreamTest`).

## Verification status

- **Live picture, RTSP type 110, 2026-09-25** (all seen by eye):
  - PC probe -> LonelyScreen: labelled test clip displayed (RAW seed).
  - PC probe -> iPhoneMirror: displayed, "Streaming 1280x720 30 fps", clock
    queries answered (MIXED seed, chosen by `AUTO`).
  - **App in an Android 12 emulator -> LonelyScreen**: the emulator's live screen
    displayed for over a minute, stopped cleanly from the app's Stop button.
  - App in the emulator -> iPhoneMirror: session accepted, no picture (the NAT
    issue above); the recorded stream replayed from the PC displayed.
- `:protocol:test` - **299 tests, 0 failures** (4 skipped: the live-hardware tests).
- `:app:assembleDebug` builds; `:app:lintDebug` reports no issues.
- **Live hardware: a receiver accepts our m3, and distinguishes it from a wrong
  one.** 5/5 consecutive runs against LonelyScreen. This is the decisive evidence:
  `FairPlaySapSession.handshake()` returns only when the receiver answers our m3
  with a valid m4 it validated against that m3.

  The control is **differential**, because LonelyScreen does not send a refusal
  frame - it closes the connection:

  | m3 sent | outcome |
  |---|---|
  | correct response | **accepted** (valid m4) |
  | all-zero response | `EOFException: connection closed` |

  A receiver that answered m4 to anything, or closed on everything, would fail
  that comparison. It is the evidence that the acceptance means something.
- **Hardware-attested corpus: 12/12.** Challenge + real per-session local SAP to a
  response a receiver accepted. This exercises the session-aware path
  (`bridgeX9DataClosedForSAP`), which is the one a real sender uses.
- **Full-chain golden corpus: 142/142.** Challenge to response, from upstream
  `FPExchangeBlobless` (a separate implementation).
- **Component corpora: 40/40 `sap_hash`, 30/30 `bridge_x9head`.**
  These are components, not the response: they passed 100% while the responder as
  a whole was wrong. Kept, but not sufficient.
- **End-to-end against the mock receiver** (`LegacySessionEndToEndTest`): the real
  `LegacyMirrorSessionFactory.open` runs over real sockets - FairPlay SAP,
  `/stream.xml`, the key wrap, `POST /stream` - and the frames are walked back out
  as a receiver would. These use a stub responder on purpose, so a crypto
  regression cannot be mistaken for a session one.
- **Whole path with the real crypto** (`LegacyRealResponderSessionTest`): the same
  session driven by `FairPlayResponderImpl` against the mock's
  `ACCEPT_DECRYPTABLE_BODY` policy, which decrypts the m3 body and refuses
  anything that is not a well-formed local SAP. It also checks that two
  consecutive sessions present *distinct* local SAPs. This was impossible before
  Phase 2 landed, which is why the m3-body bug survived as long as it did.
- The video cipher is pinned against a plain `AES/CTR/NoPadding` reference, and
  the continuous-keystream property is asserted across packet boundaries.
- The transport rule is pinned against **real** feature words observed on the
  network, and a test guards the fixtures themselves so a "fixed" feature word
  cannot make the routing test pass while the rule is wrong.
- **Digest auth on the legacy path** (`LegacyDigestAuthTest`): the retry rules,
  plus a check that recomputes the digest the way a receiver would and requires
  our header to match. Untested against a real password-protected receiver.

### LonelyScreen cannot test the mirroring session

LonelyScreen accepts the FairPlay handshake but is **not** a usable legacy
mirroring target. Measured (`FairPlaySessionProbeTest`):

| Probe | Result |
|---|---|
| `POST /fp-setup` m1 -> m2 | 142-byte m2, fresh challenge each run |
| m3 with our computed response | **accepted** (valid m4) |
| `OPTIONS` on the same connection afterwards | **times out** |
| port 7100 | closed |
| `GET /stream.xml` | no reply |

So after the m4 it neither keeps the control connection open nor serves the
port-7100 endpoint. It validates FairPlay and nothing else — which makes it an
excellent *FairPlay* oracle (that is what the 5/5 acceptance result rests on) and
useless for the mirroring session.

Testing the session needs a receiver that serves port 7100: **AirScreen**
(`192.168.31.141:5000` when its phone is attached) or the **AS-2112123AG dongle**.

### Two bugs hardware found that the test suite did not

**1. Phase 2 was entirely missing.** The bridge output is an *input* to Phase 2,
not the answer. Returning it directly produced 20 well-formed bytes that every
receiver rejected. The suite missed it because it checked two *components*
(70/70 vectors) rather than the whole chain.

**2. The m3 body was not encrypted.** `encryptBody` existed and was unit-tested
but was never called. This is the bug that actually blocked hardware acceptance;
fixing it turned 5/5 rejections into 5/5 acceptances.

Both produce output of the right *shape*. Only an oracle spanning the whole chain
- a full-chain vector, or a receiver - can see them. **"N/N vectors pass" is a
statement about what those vectors cover, and nothing more.**

Both now have offline guards, so neither can regress silently:

- `FairPlayFullChainGoldenTest` (142 vectors) covers the response end to end.
- `MockLegacyReceiverBodyPolicyTest` covers the body: the mock's
  `ACCEPT_DECRYPTABLE_BODY` policy decrypts the m3 body and requires a well-formed
  local SAP, and the test asserts a hand-built **raw** body is refused. That is the
  regression guard for bug 2, which nothing offline caught at the time.

### A flakiness bug worth recording

The end-to-end tests were flaky (3 of 5 runs failed) and the first fix - waiting
for the stream to close instead of for non-empty bytes - did not help. The real
cause was structural: `MockLegacyReceiver` recorded into **process-wide atomics**,
while it serves each connection on a daemon thread. Those threads outlive the test
that starts them, so a stale thread flipped the shared "closed" flag and a later
test's wait returned immediately with empty or partial bytes. Fixed by giving each
connection its own `StreamRecorder` - which is also the honest model, since a
receiver consumes one stream per session.

Lesson: a race fix that passes once proves nothing. This one only surfaced under
8 consecutive runs.

## NOT verified

- **The port-7100 path on hardware.** `/stream.xml` + `POST /stream` has only run
  against the mock; no receiver available serves it.
- **A real phone.** Everything app-side ran in the emulator. A phone on the LAN
  should also reach iPhoneMirror (its clock replies are not NAT-rewritten), but
  that has not been seen.
- **The AS-2112123AG dongle and AirServer** were not reachable. The dongle
  answered `/stream.xml` on 7100 in an old capture, so it may be the first real
  port-7100 target.
- Which seed receivers other than the two above want; `AUTO` is a heuristic.
- Password-protected legacy receivers (Digest) on hardware.
- Audio: the legacy path stops audio capture. AirPlay 1 mirroring audio is the
  RAOP RTP path, which is not implemented, so **legacy mirroring is video-only**.
- **Which of the two key derivations a given receiver wants.** The port-7100
  `/stream` path is implemented because that is the endpoint this code calls and
  the spec defines `param2` as the IV there. If a receiver turns out to want the
  RTSP `SETUP` type-110 path instead, both the endpoint *and* the derivation
  change together. See the table above.

## Known gaps, in priority order

1. **Run on a real phone**, against iPhoneMirror (needs the clock replies) and
   LonelyScreen.
2. **Legacy audio.** Video-only today: the AirPlay 1 mirroring audio channel is the
   RAOP RTP path, which is not implemented.
3. **The `/stream` port is assumed to be 7100** unless overridden. The dongle
   answered `/stream.xml` on 7100 but its RTSP is on 5000; a device that puts both
   on one port needs the caller to pass `streamPort`.
4. **The `POST /stream` body is minimal.** The spec's example also carries
   `fpsInfo` and `timestampInfo` arrays, and UxPlay notes a `streams[]` variant
   with a `streamConnectionID`. Only the fields the nto spec marks as meaningful
   are sent; a strict receiver may want more.
5. **The Digest `uri` for `/stream` is the path only.** Digest is computed over
   the request URI as sent; a receiver that expects an absolute `rtsp://` URI in
   the digest would reject it. Untested against a password-protected receiver.

## Password-protected receivers: Digest, not SRP

An earlier version of this file said a password-protected legacy receiver "wants
legacy SRP pairing". **That was wrong.** The nto spec's "Password Protection"
section is explicit:

> An AirPlay server can require a password ... This is implemented using standard
> HTTP Digest Authentication (RFC 2617), over RTSP for AirTunes, and HTTP for
> everything else.

with realm `AirPlay` and username `AirPlay` for the AirPlay service (the AirTunes
service uses realm `raop`, username `iTunes`).

So the legacy path answers the receiver's `401` challenge with a Digest header on
the same connection -- no pairing step, and the FairPlay handshake itself is
unauthenticated. `LegacyPairing` (the `/pair-setup-pin` SRP flow) is a *different*
mechanism, used by receivers that show an on-screen PIN, and is not what this path
needs.

## Next steps

1. Mirror from a real phone to iPhoneMirror and LonelyScreen.
2. Try the dongle when it is reachable -- the likeliest real port-7100 receiver.
3. Implement the RAOP RTP audio path for legacy mirroring audio.

## Licensing

LGPL-3.0-or-later, compatible with this project's GPLv3. The white-box tables and
the Phase 2 tables are Apple-derived *data* already public upstream
(RPiPlay/doubletake, ~2013); this vendoring adds no new exposure. See
[../NOTICE.md](../NOTICE.md).
