# AirPlayDroid

An **AirPlay sender** for Android. The phone is the *source*: it streams its content
**to** Apple TVs, HomePods, AirPlay 2 speakers and AirPlay-capable TVs.

Package id: `tw.avianjay.airplaydroid`

## What this is, and what it is not

| | |
|---|---|
| ✅ **Sender** | Android → Apple TV / HomePod / AirPlay speaker |
| ❌ **Receiver** | This app does *not* make your phone an AirPlay target. |
| ⚠️ **Screen mirroring** | Blocked on FairPlay as far as anyone has shown. See below. |

### Why screen mirroring is not on the roadmap

Mirroring requires the **sender half** of the FairPlay SAP handshake (`/fp-setup`).
Receiver-side "playfair" is not a FairPlay implementation — it is a handful of
hardcoded Apple response blobs plus a key derivation that *consumes* the sender's
164-byte message 3. No receiver ever **generates** that message, so there is nothing
to learn from them here.

**FairPlay blocks nothing else on this roadmap.** The wall is real but it stands
somewhere we never walk: DRM-protected video, negotiated at `/fp-setup2`. Audio and
video-URL handoff need no FairPlay at all — every receiver ever dumped advertises
`et` containing `0` (unencrypted), and none of pyatv, owntone or RAOP-Player
implements FairPlay for any path.

**What the wall actually is, per receiver.** An earlier version of this file claimed
every AirPlay 2 receiver demands HomeKit pairing before any session. Hardware
disproved it: an Apple TV 4K on tvOS 26.6, with feature bits 38 and 48 set, accepts
`POST /play` after nothing more than HTTP Digest. Read the receiver's `flags`
instead of its feature bits — bit 7 means a Digest password, bit 9 means real
pairing, bit 3 means a PIN, and `acl`/`act` mean you can never pair at all.

Most of the protocol was derived by **inverting receiver source**: what a receiver
parses is what a sender must send, and the code where a receiver *constructs* its own
mDNS TXT record is an authoritative declaration of what each bit means. That settles
the whole TXT layer, the complete RAOP RTSP conversation, and the HAP pairing
protocol. What it cannot give is sender-side *ordering* and *hardware quirks* — those
live only in the comments of real senders.

## Current status — milestone 3 (video-URL handoff), verified on hardware

The app builds, installs, launches, lists real AirPlay receivers, and can **play a
video URL on an AirPlay 1 receiver**.

- Discovers `_airplay._tcp` and `_raop._tcp` via `NsdManager`
- Merges both advertisements into one device per physical receiver
- Renders name, model, `host:port` (always from SRV — never hardcoded) and
  capability badges
- Tapping a supported device asks for a URL, then drives `POST /play`,
  `POST /rate`, `GET /playback-info` and `POST /stop` with play/pause/stop controls
- Unsupported devices are refused up front with the actual reason (needs pairing,
  access-control restricted, password, audio-only) rather than a failed connection

The sender never carries pixels: the receiver fetches the URL itself.

Verified against an Apple TV 4K (AppleTV6,2, tvOS 26.6) with "Require Password" on:
discovery, Digest authentication, `POST /play` and `POST /stop` all return 200.

### The pairing wall is not where the research said it was

A receiver advertising `flags` bit 7 answers an unauthenticated request with
`401` + `WWW-Authenticate: Digest realm="airplay"`, and accepts the retry with an
ordinary RFC 2617 Digest header (username `AirPlay`). **No HomeKit pairing is
involved** — not even on an AirPlay 2 receiver with feature bits 38 and 48 set.
An earlier conclusion that AirPlay 2 devices demand pairing even for `/play` was
wrong, and the `isAirPlay2` refusal it justified has been removed.

### Unresolved: the receiver accepts commands but starts no session

Against that Apple TV, `POST /play` returns `200` and the TV shows a spinner for a
few seconds, then gives up. `GET /playback-info` answers `500` in ~2 ms — the
signature of "no session by that id", not of a playback failure. The same happens
from a raw `nc` probe, with both a progressive MP4 and an HLS `.m3u8`, and the
phone can reach both origin hosts, so it is neither a client bug nor the network.

What has been ruled out:

- **Content format** — MP4 and HLS behave identically.
- **Network reachability** — verified from the phone with `nc` to both hosts.
- **Authentication** — Digest succeeds; a wrong password gives 401, not 200.
- **The reverse-HTTP event channel** — `POST /reverse` with `Upgrade: PTTH/1.0`
  now runs before `/play` and the receiver answers `101`, but it then sends **no
  events at all**, which confirms it never creates a session.

Probed directly against the device (2026-09-17):

| Request | Response |
|---|---|
| `POST /fp-setup` (FairPlay message 1) | **403 Forbidden** |
| `POST /pair-setup` (M1) | **200 OK**, TLV8 with 16-byte salt + 384-byte `B` |
| `POST /pair-setup` (M3), transient pw `3939` | **470 Connection Authorization Required** |
| `POST /pair-setup` (M3), device password | **470 Connection Authorization Required** |
| `POST /pair-pin-start` | 200 OK, but **no PIN appears on screen** |

So **FairPlay is not the blocker** — that endpoint is refused outright while HAP
pair-setup answers normally. Session establishment goes through pair-setup, and
that is what is missing. Note this device advertises `flags` bit 7 (password) but
NOT bit 3 (PIN), which is consistent with `/pair-pin-start` doing nothing.

Still unknown: why M3 is answered `470` rather than a TLV `Error` code. A wrong
SRP proof should produce `Error=Authentication (2)`; an HTTP-level 470 suggests
the request is rejected before the proof is even checked.

Confirmed SRP parameters, read off the wire: RFC 5054 **3072-bit** group (the
384-byte `B` proves it), 16-byte salt, SHA-512, TLV8 transport with 255-byte
fragmentation that must be reassembled. Beware: the response carries
`Content-Type: application/x-apple-binary-plist` but the body is **TLV8**, so a
parser that trusts the header will break.

⚠️ Repeated failed pair-setup attempts trigger a HomeKit anti-brute-force
**backoff** (TLV `Error=3`). Further attempts extend it.

### What a real iOS sender does, from a packet capture

Captured with `rvictl` + `tcpdump` on the iPhone itself. **The session runs over
AWDL, i.e. IPv6 link-local — not the infrastructure IPv4 network.** A capture
filtered to IPv4 shows only two `GET /info` probes and misses everything else.

The control connection, verbatim:

```
POST /pair-setup RTSP/1.0            <- RTSP/1.0, not HTTP/1.1
X-Apple-HKP: 4
X-Apple-Client-ID: <uuid>
X-Apple-Client-Name: <device name>
X-Apple-AbsoluteTime: <n>
Content-Type: application/x-apple-binary-plist   <- lies; the body is TLV8
CSeq: 0
    TLV: Method=0, State=M1, Flags=0x10          <- 0x10 = transient
 -> 200  TLV: State=M2, Salt[16B], PublicKey[384B]

CSeq: 1
    TLV: State=M3, PublicKey[384B], Proof[64B]
 -> 200  TLV: State=M4, Proof[64B]               <- success, no Error TLV
```

Note there is **no `Authorization` header** — over AWDL the password challenge
does not appear.

Immediately after M4 the SAME socket stops speaking RTSP: the remaining 9630
bytes parse as `[uint16-LE length][ciphertext][16-byte tag]` frames (first frame
declares 158 bytes, and 2+158+16 lines up exactly). So `/setup` and `/play` are
carried inside the ChaCha20-Poly1305 channel and cannot be read from a capture.

**Current blocker.** Replaying that exact handshake over infrastructure IPv4 gets
`200` for M1/M2 but **`470 Connection Authorization Required`** at M3, with or
without the Flags TLV, with the transient password `3939` or the device password.
A wrong SRP proof would produce TLV `Error=2 (Authentication)`, not an HTTP-level
470, so the request is being refused before the proof is checked. The working
capture differs in exactly one respect we have not reproduced: it ran over AWDL
with no password challenge, while this receiver has "Require Password" enabled on
the infrastructure interface.

Not yet implemented: audio (RAOP), HomeKit pairing (only needed for receivers that
actually use it), persistent credentials.

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

- **`:protocol`** — pure Kotlin/JVM, zero `android.*` imports. TXT-record and bitmask
  parsing, the device model, and the sender seam. Unit-testable in milliseconds.
- **`:app`** — Compose UI, `NsdManager` discovery, foreground service.

The split exists because the `features` bitmask is the easiest thing in the whole
protocol to get wrong: it is serialised `0x<low32>,0x<high32>` with the **low word
first**, and reading it backwards silently misreads every capability.

## Roadmap

2. Verify discovery against real hardware.
3. **AirPlay 1 video/photo URL handoff** — `POST /play` over **HTTP/1.1** (not
   RTSP/1.0) on the `_airplay._tcp` port, then `/rate`, `/stop`, `/playback-info`.
   Plus HTTP Digest when the receiver sets `flags` bit 7. DONE and hardware-verified,
   including against an AirPlay 2 Apple TV 4K.
4. **HomeKit pairing** (`/pair-setup`, `/pair-verify`) — SRP-6a 3072/SHA-512,
   username `Pair-Setup`, then ChaCha20-Poly1305 framing as
   `[uint16-LE len][ciphertext][16-byte tag]` with the length as AAD. Now known to be
   needed only for receivers that set `flags` bit 9, not for every AirPlay 2 device.
5. **RAOP audio streaming** — RTSP + three UDP channels. The unencrypted path is
   reached simply by *omitting* `a=rsaaeskey` and `a=aesiv` from the ANNOUNCE SDP.
   Bind the timing socket *before* SETUP. `POST /auth-setup` first when `et`
   contains 4.
6. **AirPlay 2 realtime audio** — the audio key is a 32-byte value the *sender*
   chooses and hands over as `shk` in the pair-verify-encrypted SETUP plist.

## Protocol references

- [pyatv](https://github.com/postlund/pyatv) (MIT) — the best AirPlay 2 / RAOP sender reference
- [owntone](https://github.com/owntone/owntone-server) — AirPlay 2 output implementation
- [shairport-sync](https://github.com/mikebrady/shairport-sync) — receiver, useful for crypto details

## Licence

GPLv3. See [LICENSE](LICENSE).
