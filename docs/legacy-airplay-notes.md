# Legacy (AirPlay 1) support - progress notes

> ## FairPlay is DONE - verified on real hardware
>
> The SAP response core is complete and a live receiver accepts it (**5/5 runs**).
> Two real bugs were found by hardware testing and fixed: Phase 2 was entirely
> missing, and the m3 body was not encrypted. See
> **[fairplay-status.md](fairplay-status.md)**.
>
> What remains is verifying the **session** that follows the handshake
> (`/stream.xml`, `POST /stream`, the ekey wrap) against a real receiver - it has
> only ever run against the mock.

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
| Mirror endpoint | RTSP `SETUP`, stream type 110 | port 7100 `/stream.xml` + `POST /stream` |
| Clock | PTP (or NTP) | NTP |
| Status | works, verified on one Apple TV | handshake verified; session not |

`MirrorController.refusalFor` used to refuse a receiver without `supportsHapPairing`
with a message saying it "needs Apple's FairPlay". That message is now obsolete:
such a receiver takes the legacy path instead.

## Hardware targets

| Receiver | Where | m2 challenge | m3 behaviour |
|---|---|---|---|
| **LonelyScreen** (Windows) | `192.168.31.51:7000` | **fresh, random per session** | **accepts our m3 (m4)** |
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
| `mirror/LegacyMirrorSessionFactory.kt` | the whole legacy handshake in one call |
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

- `:protocol:test` - **266 tests, 0 failures** (3 skipped: the live-hardware tests).
- `:app:assembleDebug` - builds; APK produced.
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
  as a receiver would.
- The video cipher is pinned against a plain `AES/CTR/NoPadding` reference, and
  the continuous-keystream property is asserted across packet boundaries.
- The transport rule is pinned against **real** feature words observed on the
  network, and a test guards the fixtures themselves so a "fixed" feature word
  cannot make the routing test pass while the rule is wrong.

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

- **No receiver has accepted a full legacy mirroring session.** The FairPlay
  handshake is verified on hardware, but everything after it - `/stream.xml`,
  `POST /stream`, the packetised video - has only run against the mock.
- Whether a receiver unwraps the `param1` record this code produces. Its *layout*
  is verified against two real captures; its *values* are not.
- Whether a receiver enforces AES-CTR on video frames, or accepts them in the
  clear. The captures do not settle it.
- Audio: the legacy path stops audio capture. AirPlay 1 mirroring audio is the
  RAOP RTP path, which is not implemented, so **legacy mirroring is video-only**.
- **Which of the two key derivations a given receiver wants.** The port-7100
  `/stream` path is implemented because that is the endpoint this code calls and
  the spec defines `param2` as the IV there. If a receiver turns out to want the
  RTSP `SETUP` type-110 path instead, both the endpoint *and* the derivation
  change together. See the table above.

## Known gaps, in priority order

1. **Verify the legacy session on hardware.** The handshake works; the session
   that follows has never been exercised against a real receiver.
2. **Legacy password pairing.** `LegacyMirrorSessionFactory.open` *refuses* a
   non-empty password rather than ignoring it: legacy SRP pairing exists
   (`LegacyPairing`) but is not wired in. Refusing keeps a password-protected
   receiver from failing at `/fp-setup` and sending the next person to debug the
   wrong layer.
3. **Legacy audio.** Video-only today.
4. **The `/stream` port is assumed to be 7100** unless overridden. The dongle
   answered `/stream.xml` on 7100 but its RTSP is on 5000; a device that puts both
   on one port needs the caller to pass `streamPort`.
5. **The `POST /stream` body is minimal.** The spec's example also carries
   `fpsInfo` and `timestampInfo` arrays, and UxPlay notes a `streams[]` variant
   with a `streamConnectionID`. Only the fields the nto spec marks as meaningful
   are sent; a strict receiver may want more.

## Next steps

1. **Verify the session on hardware**, preferring LonelyScreen (already accepts
   our handshake) then AirScreen.
2. Wire legacy SRP pairing in, so password-protected receivers work.
3. Implement the RAOP RTP audio path for legacy mirroring audio.
4. If hardware shows a receiver wants the RTSP SETUP type-110 path instead of
   port-7100 `/stream`, implement that alongside and select by what the receiver
   answers.

## Licensing

LGPL-3.0-or-later, compatible with this project's GPLv3. The white-box tables and
the Phase 2 tables are Apple-derived *data* already public upstream
(RPiPlay/doubletake, ~2013); this vendoring adds no new exposure. See
[../NOTICE.md](../NOTICE.md).
