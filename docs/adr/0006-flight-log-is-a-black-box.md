# 0006. Record every MAVLink 2 frame as received; validate the log when it is read

- Status: Accepted
- Date: 2026-10-19
- Issue: #67

## Context

From v0.3 every flight leaves a .tlog (REQ-036): an 8-byte big-endian timestamp in µs, then the frame, as
QGroundControl writes it. The log is the black box of the flight: it is replayed by the GCS, opened in
QGroundControl and Mission Planner, and read with pymavlink. It is only useful if it holds what the link
carried.

The parser cannot give that today. It validates a frame with its checksum and the message's CRC_EXTRA, and the
codec knows CRC_EXTRA only for the messages each phase needs (ADR 0001, rule 4 of `CLAUDE.md`). Every other
message ID is skipped by its LEN and dropped. In the captured SITL session (`heartbeat/session.tlog`) that is
3,609 of its 5,522 MAVLink 2 frames, from 28 of its 39 message IDs. `MavlinkFrame` keeps no checksum either, so the bytes
of an accepted frame cannot be written back exactly.

What other implementations do:

- The reference C library (`mavlink_helpers.h`,
  [`c_library_v2@8c63a49`](https://github.com/mavlink/c_library_v2/blob/8c63a49fe35aead7f9ef14031381c5e076ff743a/mavlink_helpers.h))
  also reads an unknown message ID to the end of its LEN and reports it as `MAVLINK_FRAMING_BAD_CRC`, keeping
  the checksum from the wire "so that if the caller decides to forward the message anyway" it is unchanged.
- QGroundControl
  ([`MAVLinkProtocol.cc@8ca8598`](https://github.com/mavlink/qgroundcontrol/blob/8ca8598c43b2dca812c72e03549bb3090c0e7b59/src/Comms/MAVLinkProtocol.cc))
  logs only frames its parser returns as `MAVLINK_FRAMING_OK`, so messages outside its dialect never reach its
  .tlog.
- pymavlink 2.4.50 (`MAVLink.decode` in its generated dialects) returns a message ID it does not know as
  `MAVLink_unknown` without a checksum check, and checks the checksum of every message it knows.
- The signing guide
  ([Logging](https://mavlink.io/en/guide/message_signing.html#logging),
  [`mavlink-devguide@9d859af`](https://github.com/mavlink/mavlink-devguide/blob/9d859af40c12b724d2db51bf6027b957a2d86ff5/en/guide/message_signing.md))
  says systems "should omit SETUP_SIGNING messages from logs (or replace the secret with 32 0xFF bytes)", and
  signed packets "should have the signature incompatibility bit cleared and the signature block removed before
  being put into telemetry log files". QGroundControl does both for the frames it receives (`_logData`), but
  writes the bytes it sends as they are (`logSentBytes`).

## Decision

The .tlog is a black box: complete, every MAVLink 2 frame as it arrived. Validation happens when the log is
read, not when it is written: QGroundControl and pymavlink check every frame with their full dialect, and the
GCS replays it through the same parser as the live link, which still validates and drops what it cannot check.

- The parser hands out the exact bytes of every frame it delimits, in arrival order (#67). A valid frame comes
  with its decoded header and payload. A frame with an unknown message ID is delimited by its LEN, is not checked
  and is only bytes: it is never decoded and never changes vehicle state. It still counts in
  `ParserStats.unknownMessageId`.
- A frame whose message ID is known but whose checksum is wrong is not "unknown": it is corrupted. It is dropped
  and counted in `ParserStats.badChecksum` as before, and never logged.
- MAVLink 1 frames (`0xFE`) stay out of the log. The parser does not delimit MAVLink 1: their bytes are skipped
  while searching for a `0xFD` and counted in `ParserStats.skippedBytes`. ArduPilot talks MAVLink 2 to the GCS;
  a MAVLink 1 frame on the link comes from another station (in the SITL session, 2 broadcast COMMAND_LONGs from
  Mission Planner).
- Signing follows the specification, for frames received and sent alike. The parser keeps the signature, which
  the GCS needs to verify signed frames from v0.6. The recorder (#70) clears the signed flag, removes the
  signature block and recomputes the checksum of every signed frame it writes, and never writes SETUP_SIGNING
  (it omits it rather than blanking the secret). A signed frame with an unknown message ID cannot be rewritten
  without its CRC_EXTRA, so it is left out of the log and counted.

## Alternatives considered

### Log only validated frames, as QGroundControl does
- Pros: no unchecked bytes in the log; no parser change.
- Cons: with the codec's small message table most of the link is lost (3,609 of 5,522 frames in the SITL
  session), so the log is useless to other tools and to debugging.
- Why not: a black box that misses most of the flight is not a black box.

### Give the codec the CRC_EXTRA of the whole dialect
- Pros: every frame validated before it is logged.
- Cons: CRC_EXTRA and length for hundreds of messages that are never decoded; against rule 4 (only the messages
  each phase needs) and the line-by-line review of the codec.
- Why not: the review cost is out of proportion to the gain, and other tools validate the log anyway.

### Log raw datagrams instead of frames
- Pros: truly everything, MAVLink 1 and noise included; no parser change.
- Cons: not the .tlog format (a timestamp before each frame), so QGroundControl and Mission Planner could not
  replay it; noise would be written as if it were frames.
- Why not: the log must open in the tools the sector uses.

### Keep signatures in the log
- Pros: byte-exact for signed frames too.
- Cons: against the specification's "should"; a log collects signatures an attacker could use.
- Why not: the specification gives a security reason, and the signature is already verified live from v0.6.

### Keep signed frames with an unknown message ID signed, as an exception
- Pros: the log stays complete for them.
- Cons: the exception is the very case the specification's rule is about; the log would mix stripped and signed
  frames.
- Why not: no frame is worth a collected signature; leaving them out is counted, so the gap is visible.

## Consequences

- Positive: the log holds every MAVLink 2 message, including the ones the GCS does not decode, as other tools
  expect; the parser's validation is unchanged for the live link, and an unknown-ID frame cannot reach the
  decoder because it is never a `MavlinkFrame`.
- Negative / costs we accept: noise that starts with `0xFD`, has a plausible header and an unknown message ID
  is written to the log as a frame, and can hide up to one maximum frame (280 bytes) of real data behind it, as
  it already does on the live link. Readers drop it when they validate. MAVLink 1 frames and signed frames with an
  unknown message ID are not logged.
- Follow-ups: the recorder (#70) applies the signing rules to received and sent frames and counts what it leaves
  out. SETUP_SIGNING (256) is not in the codec's table, so a received one comes out as an unknown-ID frame: the
  recorder recognises it by its message ID, not by decoding it. These rules handle the signing key, so #70 needs
  `safety-reviewer`. The replay (#71) reads the log with the same parser, so unknown-ID frames in a log are only
  bytes there too.
- Open (from #70): what to log of a signed frame that fails verification once signing exists (v0.6); it is what
  arrived, but it also carries a signature someone may be probing. Durability as built: records reach the disk when
  the writer's queue empties and a log is synced when it closes, so a crash loses at most the queued records.
