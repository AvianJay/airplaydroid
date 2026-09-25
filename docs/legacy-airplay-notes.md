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
| Status | ??implemented | ? this work |

`MirrorController.refusalFor` already refuses a receiver without
`supportsHapPairing` with the message that it "needs Apple's FairPlay". That
message is the thing this work makes obsolete.

## Hardware target

**AS-2112123AG** ??a MiraScreen-class dongle that reports `model=AppleTV3,2`,
`sourceVersion=220.68`, RTSP on **port 5000** (not 7000).
Reachable only through the phone (`adb connect 100.112.173.34:41393`), on
`10.124.0.197`. It answered `/fp-setup` with a real 142-byte FairPlay m2.

Captured ground truth (fixtures in `protocol/src/test/resources/fairplay/`):

| Fixture | Size | What it is |
|---|---|---|
| `appletv32_fpsetup_m2_body.bin` | 142 | the m2 the dongle returns |
| `appletv32_m3_rejection_frame.bin` | 12 | `1e1e1e1e 03 01 04 9c 00 00 00 00` |
| `appletv32_info_body.bin` | 2235 | the `/info` XML plist |
| `sender_fpsetup_m1_request.bin` | 168 | a real m1 request |

**The dongle's m2 challenge is static** ??three consecutive sessions returned an
identical 142-byte body (md5 `d0e6e9db402b52d2f36cb95192a1f316`), byte-identical
to the long-public RPiPlay/shairport-sync table. Only the `Date` header differed.
Real Apple hardware is documented to send a *fresh* challenge per session, so this
dongle is replaying a capture. Do not generalise from it.

## Implemented so far

| File | State |
|---|---|
| `fairplay/FairPlayRecords.kt` | ??FPLY framing, m1/m2/m3/m4 + refusal frame |
| `fairplay/FairPlaySapSession.kt` | ??sender handshake, fresh local SAP per session |
| `fairplay/FairPlayMessageCipher.kt` | ??m3 body cipher (AES-128, baked mode keys) |
| `fairplay/FairPlayWhiteBox.kt` | ??Phase 1 white-box AES ??GP buffer |
| `fairplay/FairPlayWhiteBoxTables.kt` | ??GENERATED from `_research/gen_wbaes_tables.py` |
| `fairplay/FairPlaySapCore.kt` | ??SAP hash + descriptor + bridge (40/40 + 30/30 vectors) |
| `fairplay/FairPlayBridge.kt` | ??MD5 family (transcribed) |
| `fairplay/FairPlayPhase2.kt` | ??**NOT IMPLEMENTED** ??throws |
| `fairplay/FairPlayResponderImpl.kt` | ?? wired, but cannot succeed until Phase 2 lands |
| `fairplay/FairPlayKeyWrap.kt` | ??72-byte `FPLY` ekey for `param1` / `a=fpaeskey` |
| `mirror/LegacyStreamPackets.kt` | ??128-byte header packetisation + `NtpClock` |
| `mirror/LegacyVideoStream.kt` | ??AES-CTR frame encryption + packet writer |
| `mirror/LegacyMirrorSession.kt` | ??`/stream.xml` + `POST /stream` |
| `mirror/VideoStreamSink.kt` | ??shared sink so `ScreenEncoder` serves both protocols |
| `protocol/MirrorTransport.kt` | ??the one place the HAP-vs-legacy decision is made |
| `devtools/MockLegacyReceiver.kt` | ??offline fixture, two session policies |
| app: `LegacyMirrorSessionFactory` | ??moved to `protocol/mirror/` (it is pure protocol logic) |
| app: `MirrorController` / `MirrorService` | ??routes legacy devices to the legacy path |
| app: picker | ??shows a **Legacy** badge; no longer says "Can't mirror" |

**The `:protocol` layer is complete for legacy mirroring, and the app routes to it.**
An APK builds.

`LegacyMirrorSessionFactory` lives in the protocol module, not `app/`, because it
has no Android dependency ??which is what lets `LegacySessionEndToEndTest` drive
the whole handshake over real sockets against the mock receiver.

## The crypto chain, as implemented

```
m2 challenge (128 B)
  ?? FairPlayWhiteBox.phase1(challenge)                     ??gp (128 B)      ??       ?? FairPlaySapCore.bridgeX9HeadForSap(localSap, gp)  ??x9Data (20 B)   ??            ?? FairPlayPhase2.exchangeFromX9(x9Data)        ??response (20 B) ??MISSING
                 ?? FairPlayRecords.m3(mode, localSap, response)
                      ?? FairPlayMessageCipher.encryptBody(...)  ??the 128-byte body

stream AES key (16 B)
  ?? FairPlayKeyWrap.wrap(receiverSap, localSap, key)       ??72-byte param1  ??       ?? LegacyMirrorCipher(key, iv).apply(frame)          ??AES-CTR frame   ??```

`FairPlayPhase2` throws rather than returning the bridge output. That is
deliberate: the previous version returned x9Data as if it were the response,
which produces 20 well-formed bytes that every receiver rejects ??and a rejected
m3 is indistinguishable from an unevaluated one, so nothing looked wrong.

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

**The response is a function of BOTH the challenge and the local SAP.** `gp`
comes from the challenge; the local SAP is the sender's own per-session value and
the receiver folds it into its check. Passing the challenge in both slots returns
20 perfectly plausible bytes that no receiver accepts. Upstream:

```go
gp := wbaesFullPhase1(payload)                   // payload = m2 challenge
x9 := bridgeX9DataClosedForSAP(s.localSAP, gp)   // localSAP = this session's
```

`FairPlaySapSession` therefore generates the local SAP *before* the exchange and
calls `respondWithSap`. `FairPlayResponderImplTest` pins this.

## Key correction to the published literature

The response core is **not** "~500 KB of white-box tables". Two measurements:

- `fairplay_message.go` shows the **m3 body** is ordinary AES-128 round
  operations with baked, per-mode round keys ??~1.5 KB of constants for all four
  modes. Implemented and verified: decrypting the captured frozen m3 body and
  re-encrypting reproduces it byte for byte, and the recovered plaintext has the
  `00 01 ...` local-SAP layout Apple's sender uses.
- The **Phase 1** tables are carried as small base tables plus a
  `(base, inXor, outXor)` spec. `_research/probe_phase1.py` confirms the 160
  Type-I tables are **not** XOR-affine images of the AES S-box (0/160), so Phase 1
  genuinely is irreducible ??but the *source literals* are only **2,624 bytes**;
  the ~86 KB is derived at runtime. The generated Kotlin is ~21 KB.

## Two mirroring key paths ??do not mix them up

There are two ways an AirPlay 1 receiver can be handed a mirroring key, and they
derive the cipher **differently**. This project implements the first.

| | port-7100 `/stream` (**implemented**) | RTSP `SETUP` type 110 |
|---|---|---|
| key | FairPlay-unwrapped key, used **raw** | `SHA-512("AirPlayStreamKey"+id?eed)[0:16]` |
| IV | `param2`, sent in the request | `SHA-512("AirPlayStreamIV"+id?eed)[0:16]` |
| needs `streamConnectionID` | **no** | yes |

The nto spec's `POST /stream` body carries `param1` (FairPlay-wrapped key) and
`param2` (the IV) and has **no** `streamConnectionID` field. `doubletake` agrees ??its `deriveStreamMasterKey` returns the raw key for a legacy receiver ("using raw
fpAesKey (legacy receiver or no SharedSecret)"). RPiPlay/UxPlay's
`mirror_buffer_init_aes` does the SHA-512 derivation, but it is reached only from
`raop_rtp_init_mirror_aes(streamConnectionID)` ??the SETUP path.

An earlier draft of this work applied the SHA-512 derivation to the legacy path,
which would have been wrong twice over: it hashes with an id this path never
sends, and the receiver would never match it. Corrected.

## The video keystream is continuous, not per-packet

The receiver (`mirror_buffer_decrypt`) carries `nextDecryptCount` and a saved
keystream tail between payloads, so the effect is **one contiguous CTR keystream
over the concatenated payloads**. A per-packet counter produces a first frame
that decrypts and every later frame that does not ??recorded upstream as a
"counter desync issue (first packet works, subsequent packets fail)".

Two consequences that are easy to get wrong:

- The 128-byte packet header is **not** encrypted and does **not** advance the
  keystream.
- The codec-data packet is **not** encrypted and must **not** advance the
  keystream either ??a receiver does not decrypt it, so advancing there would
  desynchronise every frame after it.

Both are pinned by tests (`LegacyVideoStreamTest`).

## Verification status

- `:protocol:test` ??**249 tests, 0 failures** (3 skipped: the hardware tests).
- `:app:assembleDebug` ??builds; APK produced.
- **70/70 component vectors pass**: 40 `sap_hash` + 30 `bridge_x9head`, from CSVs
  produced by a *separate* implementation (`fpsapcore`), never recomputed locally.
  ?? **These are components, not the response.** They passed 100% while the
  responder as a whole was wrong ??see
  [fairplay-status.md](fairplay-status.md).
- **Full-chain corpus: 0/142.** `testdata/golden_vectors.csv` (challenge ??  response) fails every row because Phase 2 is missing. `FairPlayFullChainGoldenTest`
  reports this honestly and will start passing when Phase 2 lands.
- **End-to-end against the mock receiver** (`LegacySessionEndToEndTest`, 6 tests):
  the real `LegacyMirrorSessionFactory.open` runs over real sockets ??FairPlay
  SAP, `/stream.xml`, the key wrap, `POST /stream` ??and the frames are walked
  back out as a receiver would. These use a **stub responder**, because the real
  one cannot complete until Phase 2 exists; they exercise the session, not the
  crypto.
- Verified against real captures: framing, the refusal frame, the m3 body cipher
  (round-trip on captured ciphertext), the recovered local-SAP layout, the
  72-byte ekey layout (two captures), stream packet layout.
- The video cipher is pinned against a plain `AES/CTR/NoPadding` reference, and
  the continuous-keystream property is asserted across packet boundaries.
- The transport rule is pinned against **real** feature words observed on the
  network, and a test guards the fixtures themselves so a "fixed" feature word
  cannot make the routing test pass while the rule is wrong.

### Hardware results (real receivers, 2026-09-25)

Three receivers were probed. All three answered `/fp-setup` m1 with a real
142-byte m2, and **none** accepted our m3 ??as expected, given the Phase 2 gap.

| Receiver | Where | m1 ??m2 | m3 | Challenge |
|---|---|---|---|---|
| LonelyScreen (Windows) | `192.168.31.51:7000` | ??142 B | **no reply at all** | **fresh/random each run** |
| AirScreen 2.15.1 (Android) | `192.168.31.141:5000` | ??142 B | 12-byte refusal `1e1e1e1e 03 01 04 9c` | static |
| AS-2112123AG dongle | (earlier, phone tunnel) | ??142 B | 12-byte refusal | static |

**LonelyScreen is the best test target** available: it is directly reachable from
this host, and it issues a *fresh* challenge per session, so it cannot be fooled
by a hard-coded or replayed response. The other two replay a canned challenge,
which means they cannot distinguish a correct response from a memorised one.

Note the difference in failure mode: the dongle and AirScreen *refuse* (a 12-byte
error frame under HTTP 200), while LonelyScreen **closes the connection** without
answering at all (`EOFException: connection closed before a status line arrived`).

The hardware test is `FairPlayHardwareTest`, enabled with
`-Dairplay.host=... -Dairplay.port=...`; `protocol/build.gradle.kts` forwards
those properties to the test JVM. It also includes a **control** test that sends a
deliberately wrong response. The control distinguishes three outcomes and reports
them separately, because conflating them is how the Phase 2 gap stayed hidden:

- **refused** (error frame / bad m4) ??the receiver evaluated and said no; the
  control is good.
- **no reply** (timeout or EOF) ??the receiver disengaged. This does **not**
  establish that it validates anything, so the control is reported
  *INCONCLUSIVE* rather than being counted as a pass.
- **completed** ??it accepted an all-zero response, which would make an
  acceptance meaningless. A real failure.

Against LonelyScreen the control is **INCONCLUSIVE**: it drops the connection on a
wrong m3. So even once Phase 2 lands, an acceptance from LonelyScreen must be read
with that in mind ??a receiver that closes on *everything* would look the same.
AirScreen and the dongle, which do answer a refusal frame, are the better
controls.

### A flakiness bug worth recording

The end-to-end tests were flaky (3 of 5 runs failed) and the first fix ??waiting
for the stream to close instead of for non-empty bytes ??did not help. The real
cause was structural: `MockLegacyReceiver` recorded into **process-wide atomics**,
while it serves each connection on a daemon thread. Those threads outlive the test
that starts them, so a stale thread flipped the shared "closed" flag and a later
test's wait returned immediately with empty or partial bytes. Fixed by giving each
connection its own `StreamRecorder` ??which is also the honest model, since a
receiver consumes one stream per session.

Lesson: a race fix that passes once proves nothing. This one only surfaced under
8 consecutive runs.

### NOT verified

- **No receiver has accepted a full legacy session from this code.** The dongle
  went offline before it could be tried, and the mock deliberately refuses every
  m3, so the success path has never run against real hardware.
- Whether a receiver unwraps the `param1` record this code produces.
- Whether the dongle enforces AES-CTR on video frames, or accepts them in the
  clear. The captures do not settle it.
- Audio: the legacy path stops audio capture. AirPlay 1 mirroring audio is the
  RAOP RTP path, which is not implemented, so **legacy mirroring is currently
  video-only**.
- **Which of the two key derivations this receiver wants.** The port-7100
  `/stream` path is implemented because that is the endpoint this code calls and
  the spec defines `param2` as the IV there. If the dongle turns out to want the
  RTSP `SETUP` type-110 path instead, both the endpoint *and* the derivation
  change together. See the table above.

## Known gaps, in priority order

1. **Hardware verification.** Everything above is offline evidence. The first
   real run is the actual test.
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

1. **Port FairPlay Phase 2.** This is the blocker; nothing else matters until it
   lands. ~60 KB of Go across ~15 files in upstream `fairplayhash/`, verified
   offline against the 142 golden vectors already wired into
   `FairPlayFullChainGoldenTest`. Flip `phase2Implemented` there to `true` when it
   passes. See [fairplay-status.md](fairplay-status.md).
2. **Re-verify on hardware**, preferring **AirScreen** (`192.168.31.141:5000`) or
   the dongle over LonelyScreen: they answer a refusal frame, so a rejection is
   distinguishable from silence, and LonelyScreen's control is inconclusive.
   AirScreen is also reachable over `adb forward tcp:15000 tcp:5000`.
3. Wire legacy SRP pairing in, so password-protected receivers work.
4. Implement the RAOP RTP audio path for legacy mirroring audio.
5. If hardware shows the receiver wants the RTSP SETUP type-110 path instead of
   port-7100 `/stream`, implement that alongside and select by what the receiver
   answers.

## Licensing

LGPL-3.0-or-later, compatible with this project's GPLv3. The white-box tables are
Apple-derived *data* already public upstream (RPiPlay/doubletake, ~2013); this
vendoring adds no new exposure, but a NOTICE entry is owed.
