# AirPlayDroid

An **AirPlay sender** for Android. The phone is the *source*: it mirrors its screen,
with sound, **to** an Apple TV, and hands video URLs to AirPlay receivers. Audio-only
streaming to HomePods and AirPlay speakers is planned but not built.

Package id: `tw.avianjay.airplaydroid`

## What this is, and what it is not

| | |
|---|---|
| ✅ **Sender** | Android → Apple TV. HomePod / AirPlay speakers: not yet (no audio-only path). |
| ❌ **Receiver** | This app does *not* make your phone an AirPlay target. |
| ✅ **Screen mirroring** | Picture **and** sound, to an Apple TV on tvOS 26.6. **No FairPlay.** Verified on one unit. |
| ⚠️ **Video-URL handoff** | `POST /play` with Digest. Accepted (200) by an AirPlay 2 Apple TV, but playback does not start there; not yet tested on an AirPlay 1 receiver. |

## Screen mirroring works, and needs no FairPlay

An earlier version of this file said mirroring was blocked on the sender half of
the FairPlay SAP handshake (`/fp-setup`). **That is wrong, at least for the Apple
TV tested** (Apple TV 4K, AppleTV6,2, tvOS 26.6, password mode). Other models and
tvOS versions are untested.
On tvOS 26.6 the whole session rests on HomeKit pairing. The phone's screen and
its audio play on an Apple TV 4K without `/fp-setup` ever being sent, and no
FairPlay code exists anywhere in this project.

Verified against that Apple TV ("Require Password" on) from an OPPO Reno6 5G
(CPH2251) on Android 13, and confirmed by eye and ear on the TV: the picture
shows, rotation works, and the sound plays.

### The session, end to end

| Step | What happens |
|---|---|
| 1. pair-setup (first time only) | Persistent HomeKit pairing, M1–M6, with the AirPlay **password as the SRP PIN**. Both sides then hold each other's Ed25519 long-term key. |
| 2. pair-verify | X25519 exchange, signed by both long-term keys. The shared secret keys the control, event and video channels. The audio key is a random `shk` sent inside the encrypted channel. |
| 3. Encrypted control channel | The same socket switches to HAP framing: `[uint16-LE len][ChaCha20-Poly1305][tag]`, 1024-byte frames, length as AAD. |
| 4. HTTP Digest | A password-protected receiver *also* demands Digest inside the encrypted channel: the control `SETUP` is answered `401` until it carries Digest. An encrypted `GET /info` was answered without it. Pairing does not replace Digest. Once challenged, the sender adds Digest to every later request. |
| 5. Control `SETUP` | `timingProtocol: PTP`. The reply carries the receiver's ClockID and an `eventPort`. |
| 6. Event channel | HAP-encrypted (`Events-Salt`). The receiver pushes `updateInfo`, which includes its display size. |
| 7. `RECORD`, then audio `SETUP` | Stream type 96, ALAC 44.1k/16/2, a random 32-byte `shk`. Reply gives UDP data and control ports. |
| 8. Video `SETUP` | Stream type 110. The reply gives the TCP `dataPort`. |
| 9. Streaming | Video frames sealed with HKDF(pair-verify secret, `DataStream-Salt<id>`, `DataStream-Output-Encryption-Key`). Audio as RTP, sealed with `shk`, plus `0xD7` PTP TimeAnnounce packets. `/feedback` every 2 s. |

No PTP stack runs on the phone. Every RTSP reply carries
`X-Apple-RequestReceivedTimestamp`, which is the receiver's own clock, so the
sender maps its monotonic clock onto the receiver's (`ReceiverClock`). Video and
audio are both stamped *capture time + 250 ms* on that clock, so they should line
up. Video falls back to send time if the encoder's timestamps are unusable. A/V
sync has not been measured.

How we know each piece is right:

- **Video key:** frames sealed with a random key make the Apple TV drop the data
  connection within about 20 ms. With the derived key it accepts the stream: all
  600 frames of a 20 s probe, and phone sessions of several minutes. Some early
  phone sessions still ended on their own; the cause is not yet known.
- **Audio:** withholding one packet makes the Apple TV send one retransmit request
  (`0xD5`), so it is receiving and parsing the RTP stream. Separately, an offline
  harness decrypted 39/39 of our packets. It used airplay2-receiver's and
  shairport-sync's packet layouts, re-implemented in Python, and FFmpeg's ALAC
  decoder decoded them bit-exact.
- **Audio codec:** "ALAC verbatim" is raw PCM inside an ALAC escape frame, so no
  audio encoder is needed.

### Why the pairing used to fail with 470

The old blocker was `470 Connection Authorization Required` at pair-setup M3. It
was two bugs at once:

1. **Wrong mode.** A receiver with `flags` bit 7 (password) must be paired in
   **persistent** mode (`X-Apple-HKP: 3`, no transient flag), with the device
   password as the PIN. Transient mode is refused with 470 before the proof is
   checked. owntone has the right mapping.
2. **Wrong SRP proof.** M1 hashed a 384-byte-padded `g`. The srptools convention
   that pyatv pairs Apple TVs with pads only the inputs of `k` and `u`. Our own
   unit test shared the same mistake, so it passed.

The earlier `/fp-setup → 403` probe was sent unauthenticated. It proved nothing
either way.

### Rotation

The encoder canvas is a fixed landscape box: the receiver's display size from
`updateInfo`, capped at 1920×1080 (default 1280×720). Android fits the mirrored
screen into it keeping its aspect ratio, so a portrait phone arrives pillarboxed and a
landscape one fills the width. A 20:9 phone gets thin bars top and bottom. Rotating needs no encoder restart. This also
matters because a MediaProjection may create only one VirtualDisplay.

### Current limitations

- **Receivers:** only receivers in password mode (`flags` bit 7) can be mirrored
  to. A receiver that shows a PIN (bit 9), or one with no access control
  (transient pairing), needs a pairing flow that is not built yet.
- **Sound source:** needs Android 10+. Only media, game and unknown-usage audio is
  sent, and only from apps that allow capture: apps targeting Android 10+ that
  don't opt out, or older apps that opt in. Apps that opt out, typically DRM
  streaming apps, stay silent on the TV.
- **Phone speaker:** the phone keeps playing the sound too. No public API mutes
  it; turn the phone's volume down.
- **Password storage:** the password is stored alongside the pairing in app-private,
  no-backup storage, because Digest needs it on every session. It is not
  encrypted beyond what the OS provides.

### Diagnostics

Some OEM builds (ColorOS) drop a third-party app's logcat output. Session starts
and end reasons are therefore also written to a file:

```
adb shell run-as tw.avianjay.airplaydroid cat files/mirror-log.txt
```

`MirrorProbe` (in `:protocol`, `devtools`) runs the same session from a desktop
JVM, streaming an H.264 file and a test tone. Use it to check a protocol change
against real hardware without a phone.

## Video-URL handoff (AirPlay 1)

Tapping a device and entering a URL drives `POST /play`, `POST /rate`,
`GET /playback-info` and `POST /stop`. The receiver fetches the URL itself; the
phone never carries pixels. A receiver with `flags` bit 7 answers `401` with
`WWW-Authenticate: Digest realm="airplay"`, and an ordinary RFC 2617 Digest
retry (username `AirPlay`) is accepted.

**Unresolved on the AirPlay 2 Apple TV:** `/play` returns `200`, the TV shows a
spinner and gives up, and `GET /playback-info` answers `500` within a few ms, which looks like "no
session by that id" rather than a playback failure. Content format, reachability and Digest are ruled out. Now that pairing
works, the likely next step is to send `/play` inside the pair-verify-encrypted
channel, the way mirroring does. Not yet tried.

## What a real iOS sender does, from a packet capture

Captured with `rvictl` + `tcpdump` on an iPhone. The session runs over **AWDL**
(IPv6 link-local), not the infrastructure network. It uses transient pair-setup
(`X-Apple-HKP: 4`, Flags `0x10`), and right after M4 the same socket switches to
`[uint16-LE length][ciphertext][16-byte tag]` frames. Over AWDL no password
challenge appears. On the infrastructure network with "Require Password" on,
persistent pairing plus Digest is what works (see above).

Confirmed SRP parameters, read off the wire: RFC 5054 **3072-bit** group,
16-byte salt, SHA-512, TLV8 with 255-byte fragments that must be reassembled.
The pairing responses claim `Content-Type: application/x-apple-binary-plist`,
but the body is **TLV8**.

⚠️ Repeated failed pair-setup attempts trigger a HomeKit anti-brute-force
**backoff** (TLV `Error=3`), and further attempts extend it. Test offline first:
pair-setup M5/M6 and pair-verify were checked against a local srptools-based fake
accessory before being pointed at the TV.

## Building

```
gradlew.bat :protocol:test     # fast JVM unit tests, no emulator
gradlew.bat :app:assembleDebug
gradlew.bat :app:installDebug
```

`local.properties` is machine-local and gitignored; it must point at your Android SDK.

> On this development machine `ANDROID_ADB_SERVER_PORT=5137` is set, so `adb` must be
> invoked on that port or it will not see any devices.

### Toolchain

Versions are pinned deliberately and should not be bumped to "newest stable" without
checking AAR metadata first:

| | |
|---|---|
| AGP | 9.1.1 |
| Gradle | 9.7.1 |
| Kotlin | 2.2.10 (the KGP that AGP 9.1.1 pins) |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Compose BOM | 2026.06.01 |

Compose **1.12.0+**, `androidx.core` **1.18.0+** and `lifecycle` **2.11.0+** all raise
their `minCompileSdk` above 36 and will hard-fail `checkDebugAarMetadata`.

AGP 9 ships **built-in Kotlin**: `org.jetbrains.kotlin.android` must not be applied to
`:app`. App sources therefore live in `app/src/main/java/` (a guaranteed source root);
`src/main/kotlin` is not, and the failure mode is a silent zero-source compile.

## Modules

- **`:protocol`**: pure Kotlin/JVM, zero `android.*` imports, unit-testable in
  milliseconds.
  - TXT-record and bitmask parsing, and the device model.
  - `pairing/`: HomeKit pair-setup/verify, SRP-6a, TLV8, and HAP crypto via the
    BouncyCastle lightweight API.
  - `http/`: RTSP/HTTP codec, HAP-encrypted framing, and Digest.
  - `mirror/`: `MirrorSession`, `ScreenAudioStream`, `ReceiverClock`, `AlacVerbatim`
    and the H.264 helpers.
- **`:app`**: Compose UI, `NsdManager` discovery, and the mirroring pipeline. The
  pipeline is `MirrorService`, a mediaProjection foreground service, which runs
  `ScreenEncoder` (VirtualDisplay → MediaCodec) and `AudioCapture`
  (AudioPlaybackCapture). Pairings are kept in `PairingStore`.

The split exists because the `features` bitmask is the easiest thing in the whole
protocol to get wrong: it is serialised `0x<low32>,0x<high32>` with the **low word
first**, and reading it backwards silently misreads every capability.

## Roadmap

- ✅ Discovery, HTTP Digest.
- ⚠️ Video-URL handoff: `/play` is accepted but does not start playback on the
  AirPlay 2 Apple TV; untested on AirPlay 1.
- ✅ HomeKit pairing (persistent), pair-verify, encrypted control channel.
- ✅ Screen mirroring with sound, and rotation, to a password-protected Apple TV
  (one unit tested).
- ⬜ Mirroring to receivers that use an on-screen PIN (`flags` bit 9) or transient
  pairing.
- ⬜ `/play` inside the encrypted channel for AirPlay 2 receivers.
- ⬜ RAOP / AirPlay 2 audio-only streaming to speakers. The realtime audio path
  already exists in `ScreenAudioStream`.

## Protocol references

- [pyatv](https://github.com/postlund/pyatv) (MIT) — AirPlay 2 / RAOP sender, HAP pairing
- [owntone](https://github.com/owntone/owntone-server) — AirPlay 2 output; the pairing-mode decision table
- [doubletake](https://github.com/omarroth/doubletake) (GPL) — mirroring sender tested against Apple TVs; used as a protocol reference only, no code copied
- [airplay2-receiver](https://github.com/openairplay/airplay2-receiver), [shairport-sync](https://github.com/mikebrady/shairport-sync), [UxPlay](https://github.com/FDH2/UxPlay) — receivers; what they parse is what a sender must send
- [airplay-spec](https://github.com/openairplay/airplay-spec) — the unofficial protocol notes

## Licence

GPLv3. See [LICENSE](LICENSE).
