# NOTICE

AirPlayDroid is licensed under GPLv3 (see [LICENSE](LICENSE)).

This file records third-party code and data that have been **incorporated into**
this project, as distinct from the projects listed in the README as protocol
references. It exists because those are two different things, and the README's
"no code copied" note applies only to the latter.

## FairPlay SAP implementation — incorporated

`protocol/src/main/kotlin/tw/avianjay/airplaydroid/protocol/fairplay/` contains a
transcription of the following, adapted to this project's package and API:

| File here | Origin |
|---|---|
| `FairPlaySapCore.kt` | `ports/kotlin/FairPlaySapCore.kt` |
| `FairPlayBridge.kt` | `ports/kotlin/FairPlayBridge.kt` |
| `FairPlayWhiteBoxTables.kt` | generated from `fairplayhash/wbaes_tables.go`, `fpbridge/wbaes_output_dec_gen.go`, `fpbridge/wbaes_consts.go`, `fpbridge/wbaes_xor_consts_gen.go` |
| `FairPlayMessageCipher.kt` | constants from `internal/airplay/fairplay_message.go` |
| `FairPlayPhase2.kt` | `fairplayhash/analytical.go`, `fpbridge/fp_exchange_native.go` |
| `FairPlayPhase2Tables.kt` | generated from 13 files in `fairplayhash/` and `fpbridge/` (see the generator's header) |
| `FairPlayPhase2Rounds.kt` | `fairplayhash/prologue.go`, `roundc_plain.go`, `roundc_unrolled.go`, `spn1_r8r19.go` |
| `FairPlayPhase2Spn.kt` | `fairplayhash/spn1.go`, `spn1_trailing.go`, `tail_spn.go`, `spn1_ground.go`, `fpbridge/neon_state.go` |

The Phase 2 tables are generated, not hand-copied, by
`_research/gen_phase2_tables.py` — which decodes the upstream Go string-escape
table literals, because hand-transcribing those corrupts them silently. That
generator is the provenance record for the tables.

Upstream projects:

- **[objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake)**
  — LGPL-3.0-or-later (some files BlueOak-1.0.0). The direct source of the
  transcribed files.
- **[omarroth/doubletake](https://github.com/omarroth/doubletake)**
  — LGPL-3.0-or-later. The origin of the FairPlay round keys and of the algorithm
  that `fpsapcore`/`fpbridge` were derived from.

**Licence compatibility.** LGPL-3.0-or-later may be combined with GPLv3; LGPLv3 is
GPLv3 plus additional permissions. The combined work is distributed under GPLv3 as
the stronger copyleft, and the LGPL terms continue to apply to the incorporated
portions.

### The tables are Apple-derived data

The FairPlay white-box AES tables and the per-mode message round keys are
**data recovered by reverse engineering Apple's FairPlay SAP**, not code written
by the upstream authors. They are transcribed values, not an independent
derivation. The same tables have been public in the shairplay / RPiPlay /
doubletake lineage since around 2013.

This project adds no new exposure of that data. It is included here because
legacy AirPlay interoperability requires it, and the upstream authors' own
framing is worth repeating: this is an **authentication handshake, not DRM**. It
decrypts no content and extracts no content keys.

Anyone redistributing this project inherits both the GPLv3 obligations and
whatever risk attaches to the Apple-derived data. See
[docs/fairplay-research.md](docs/fairplay-research.md) §10.

## Test fixtures — captured from hardware

`protocol/src/test/resources/fairplay/` holds byte captures taken from a
third-party AirPlay receiver (a MiraScreen-class dongle reporting
`AppleTV3,2` / `AirTunes/220.68`) on a private network:

| File | What it is |
|---|---|
| `appletv32_fpsetup_m2_body.bin` | the 142-byte FairPlay m2 the device returned |
| `appletv32_m3_rejection_frame.bin` | the 12-byte refusal frame |
| `appletv32_info_body.bin` | the `/info` XML plist |
| `sender_fpsetup_m1_request.bin` | an m1 request |

These are protocol messages, not creative works, and they are held in
`src/test/resources` so they are not packaged into the application.

## Golden vectors

`protocol/src/test/resources/fairplay/sap_hash.csv` and `bridge_x9head.csv` are
copied from the `objevovat` project's `conformance/` directory
(LGPL-3.0-or-later). They are the conformance corpus for the FairPlay SAP
bridge.

## Protocol references (no code incorporated)

The projects listed under "Protocol references" in the README — pyatv, owntone,
RPiPlay, airplay2-receiver, shairport-sync, UxPlay, airplay-spec — were consulted
as documentation. No code from them is incorporated, with the one exception
noted above: the FairPlay tables reach this project through doubletake, which is
itself in the shairplay/RPiPlay lineage.
