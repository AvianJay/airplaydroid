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
| ✅ **Screen mirroring, AirPlay 2** | Picture **and** sound, to an Apple TV on tvOS 26.6. **No FairPlay** — this path derives the video key from the pair-verify secret. Verified on one unit. |
| ✅ **Screen mirroring, legacy (AirPlay 1)** | Picture, no sound, via FairPlay + RTSP type 110. Seen on **LonelyScreen** (from the app in an Android emulator, and from a PC probe) and **iPhoneMirror** (PC probe). Not yet run from a real phone or on a hardware dongle. See [docs/legacy-airplay-notes.md](docs/legacy-airplay-notes.md). |
| ⚠️ **Video-URL handoff** | `POST /play` with Digest. Accepted (200) by an AirPlay 2 Apple TV, but playback does not start there; not yet tested on an AirPlay 1 receiver. |

## Screen mirroring: two protocols, not one

**Which protocol a receiver wants is not decided by its model name.** It is
decided by whether it advertises HAP pairing. Both paths exist in this project:

| | AirPlay 2 (HAP) | Legacy (AirPlay 1) |
|---|---|---|
| Auth | HomeKit pair-setup / pair-verify | **FairPlay SAP** (`/fp-setup`) |
| Video key | HKDF from the pair-verify secret | FairPlay `ekey` (72-byte `FPLY`) |
| Video crypto | HAP frames | AES-CTR |
| Endpoint | RTSP `SETUP`, stream type 110 | RTSP `SETUP` type 110; port 7100 `/stream` as a fallback |
| Status | ✅ works, verified on one Apple TV | ✅ type 110 shows a picture on two Windows receivers; port 7100 mock-only |

The AirPlay 2 path needs **no FairPlay at all** — on tvOS 26.6 the whole session
rests on HomeKit pairing. The phone's screen and its audio play on an Apple TV 4K
without `/fp-setup` ever being sent.

The legacy path **does** need FairPlay, and this project now implements it: the
SAP framing, the m3 body cipher, the white-box Phase 1, the SAP-hash and bridge
components, and Phase 2 (the analytical WB-MD5) — plus legacy pair-verify, the
RTSP type-110 session, a clock responder, the 128-byte packet format and the
continuous AES-CTR video keystream. The older port-7100 `/stream` session is
there too, as a fallback.

The FairPlay half is verified three ways: 142/142 full-chain vectors, 12/12
hardware-attested vectors, and **a live receiver accepting our m3 in 5/5 runs**.
The session after it now shows a picture on LonelyScreen and iPhoneMirror. The
two receivers want the video key derived differently, and nothing they advertise
says which; the app guesses from the SETUP reply and lets the user override it
per receiver.

> Two earlier versions of this file were wrong in opposite directions. The first
> said mirroring "needs no FairPlay" without qualification — true only of the
> AirPlay 2 path. The second said the legacy crypto was "done and verified
> offline", which was true of two *components* (70/70 vectors) and false of the
> whole chain. Hardware testing found the difference both times: it caught a
> missing Phase 2 and an unencrypted m3 body, neither of which any component test
> could see.

Verified against that Apple TV ("Require Password" on) from an OPPO Reno6 5G
(CPH2251) on Android 13, and confirmed by eye and ear on the TV: the picture
shows, rotation works, and the sound plays.

In the app, tapping a device starts mirroring. A password-protected receiver asks
for its AirPlay password once, the first time.

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
  encrypted beyond what the OS provides. A newly typed password is saved only
  after the receiver accepts it. If the receiver later rejects the saved one, the
  app drops it but keeps the pairing, and the next tap asks again.
- **Forget pairing** (in a device's ⋮ menu) deletes only this phone's copy. No
  pair-remove is sent, so the Apple TV keeps this phone in its paired list.

### Diagnostics

Some OEM builds (ColorOS) drop a third-party app's logcat output. Session starts
and end reasons are therefore also written to a file:

```
adb shell run-as tw.avianjay.airplaydroid cat files/mirror-log.txt
```

`MirrorProbe` (in `:protocol`, `devtools`) runs the same session from a desktop
JVM, streaming an H.264 file and a test tone. Use it to check a protocol change
against real hardware without a phone.

## Settings

The picker's ⋮ menu holds **Add by address** and **Settings**.

| Setting | Effect |
|---|---|
| **Client name** | The name the receiver lists and pairs this phone under. Defaults to `Build.MODEL`, and is sent both as `X-Apple-Client-Name` during pairing and as `name` in the control `SETUP`. One name for both, so a receiver cannot list one name and have paired another. Changing it does not touch existing pairings. |
| **Keep the screen awake while mirroring** | On by default. `MediaProjection` keeps capturing while the screen sleeps, but the encoder's surface stops producing frames and the receiver freezes on the last one. Sets `FLAG_KEEP_SCREEN_ON` for the length of a session only. |
| **Default legacy video key** | The app-wide fallback for the per-receiver `KeySeed`. A receiver with its own choice, set from its row menu, still wins. |
| **Update channel** | Off by default. *Stable* offers tagged releases, *Nightly* also offers pre-releases. See [App updates](#app-updates). |
| **Diagnostics** | Where the session log is, and the `run-as` command that reads it. |

## App updates

The settings screen can check this project's GitHub releases for a newer build,
download the APK and hand it to Android's installer. It is **off by default**,
and nothing is ever installed without the system installer's own confirmation.

| | |
|---|---|
| **Off** | No network request is made at all. |
| **Stable releases** | Reads `releases/latest/download/update.json`. GitHub resolves `latest` itself, and it **excludes pre-releases**, so this channel never sees a nightly. |
| **Nightly builds** | Reads `releases/download/nightly/update.json`, the rolling pre-release tag. |

The two channels use different URL shapes on purpose. There is no
`releases/download/latest/...` -- that path is a 404 -- and a single
`releases/latest` URL cannot serve the nightly channel, because GitHub skips
pre-releases when resolving it. Verified against this repository, whose only
release is a pre-release.

### What is checked before an install

- The manifest is **https only**; a cleartext `apkUrl` is dropped at parse time,
  so a network attacker cannot redirect the download.
- The downloaded file is **SHA-256 checked** against the manifest, and a mismatch
  deletes it rather than handing it to the installer.
- The APK is written to a `.part` file and renamed only once complete, so an
  interrupted download is never mistaken for a good one.
- The download is exposed through a `FileProvider` with a read-only grant; a
  `file://` URI would throw `FileUriExposedException`.
- The **"install unknown apps"** grant is checked *before* the download starts,
  and the row offers the Settings page instead. Downloading ~10 MB and then
  discovering the grant is missing would waste the user's data.

### Version codes

`versionCode` is the only number Android will accept an upgrade on, so it must
increase across **every** published build, from either channel. Both workflows
therefore derive it from **one shared sequence, the git commit count**:

| | `versionName` | `versionCode` |
|---|---|---|
| Nightly | `0.1.0-nightly.<commits>.<sha>` | `<commits>` |
| Release (`v0.2.0`) | `0.2.0` | `<commits at the tag>` |
| Local build | `app.version.base` | `major*10000 + minor*100 + patch` |

This is what lets a user move between channels in either direction and always be
offered an upgrade. Deriving a release's code from its *name* (`0.2.0` -> `10200`)
would break exactly that: someone on nightly build 20000 would be told that
`0.2.0` is older than what they already have, and Android would refuse the
install. `WorkflowManifestContractTest` pins this.

Both workflows check out with `fetch-depth: 0`; a shallow clone reports a commit
count of 1 and would hand every nightly the same code.

### Releasing

- **Nightly** is automatic: every push to `main` moves the rolling `nightly` tag
  and replaces the APK and `update.json` on that pre-release.
- **Release** is `git tag v0.2.0 && git push origin v0.2.0`, or the *Release*
  workflow with a tag. It builds a signed APK and opens a **draft** release.
  Nothing reaches the stable channel until a human presses *Publish release*,
  because GitHub's `latest` ignores drafts. The workflow fails rather than
  publishing an unsigned APK, which could not be installed over an existing one.

## Video-URL handoff (AirPlay 1)

**Play video URL…** in a device's ⋮ menu drives `POST /play`, `POST /rate`,
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

A local build takes its version from `app.version.base` in `gradle.properties`
and is never published. CI overrides both the name and the code through
`APP_VERSION_NAME` / `APP_VERSION_CODE` -- see [Version codes](#version-codes).

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
  - `update/`: version comparison, the `update.json` reader (a small strict JSON
    parser, since the module has no third-party runtime dependency) and the rule
    that decides which release is offered. No `android.*`, so the decision that
    matters -- *is this an upgrade?* -- is tested in milliseconds rather than on a
    device.
- **`:app`**: Compose UI, `NsdManager` discovery (only while the device list is on
  screen; there is no background discovery service), and the mirroring pipeline. The
  pipeline is `MirrorService`, a mediaProjection foreground service, which runs
  `ScreenEncoder` (VirtualDisplay → MediaCodec) and `AudioCapture`
  (AudioPlaybackCapture). Pairings are kept in `PairingStore`. `update/` is the
  network half of the updater: fetch, verify, hand to the installer.

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
- ✅ **Legacy (AirPlay 1) mirroring**, video only, to third-party receivers:
  picture seen on LonelyScreen (from the app in an emulator) and iPhoneMirror (PC
  probe). Not yet from a real phone, not yet on a hardware dongle, and the
  port-7100 fallback has only met the mock. See
  [docs/legacy-airplay-notes.md](docs/legacy-airplay-notes.md).
- ✅ **Add a receiver by address**, for one mDNS cannot reach (a VPN, or the host
  seen from an emulator as `10.0.2.2`).
- ✅ **In-app updater**, off by default: stable and nightly channels, SHA-256
  checked downloads, handed to Android's installer. Tagged releases open a
  **draft** GitHub release. See [App updates](#app-updates).
- ⬜ Mirroring to receivers that use an on-screen PIN (`flags` bit 9) or transient
  pairing.
- ⬜ `/play` inside the encrypted channel for AirPlay 2 receivers.
- ⬜ RAOP / AirPlay 2 audio-only streaming to speakers. The realtime audio path
  already exists in `ScreenAudioStream`.

## Protocol references

- [pyatv](https://github.com/postlund/pyatv) (MIT) — AirPlay 2 / RAOP sender, HAP pairing
- [owntone](https://github.com/owntone/owntone-server) — AirPlay 2 output; the pairing-mode decision table
- [doubletake](https://github.com/omarroth/doubletake) (LGPL-3.0-or-later) — mirroring sender tested against
  Apple TVs. **Code incorporated** for the legacy FairPlay path: its message round
  keys are transcribed here, and it is the origin of the tables this project's
  `fairplay/` package carries.
- [objevovat/fairplay-sap-core](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake)
  (LGPL-3.0-or-later / BlueOak-1.0.0) — **code incorporated**: the FairPlay SAP
  hash, bridge, white-box AES tables and the whole of Phase 2 under `fairplay/` are
  transcriptions of its Kotlin and Go ports.
- [airplay2-receiver](https://github.com/openairplay/airplay2-receiver), [shairport-sync](https://github.com/mikebrady/shairport-sync), [UxPlay](https://github.com/FDH2/UxPlay) — receivers; what they parse is what a sender must send
- [airplay-spec](https://github.com/openairplay/airplay-spec) — the unofficial protocol notes

## Licence

GPLv3. See [LICENSE](LICENSE). Third-party code and data incorporated into this
project are recorded in [NOTICE.md](NOTICE.md) — note that the FairPlay tables are
Apple-derived data, not merely third-party source.
