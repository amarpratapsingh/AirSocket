# AirSocket Wire Protocol Specification (v4.0)

## 1. Overview

The AirSocket Wire Protocol defines a binary framing and session exchange format for high-throughput, encrypted peer-to-peer file transfers over TCP. It operates with zero third-party dependencies, adhering strictly to network byte order (Big-Endian).

---

## 2. Fixed Binary Frame Header (28 Bytes)

Every protocol unit transmitted across the wire begins with a fixed 28-byte header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Magic: 0x41525354 ("ARST")              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Protocol Version        |          Frame Type           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Sequence Number (64-bit)                   +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                     Payload Length (64-bit)                   +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Flags (32-bit)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Payload Data (Variable)                 |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Header Field Specification
| Offset | Field | Type | Description |
|---|---|---|---|
| `0..3` | `Magic` | `uint32` | Constant `0x41525354` (`ASCII "ARST"`). Rejects malformed streams immediately. |
| `4..5` | `Version` | `uint16` | Major/minor protocol version identifier (e.g., `4` for V4). |
| `6..7` | `Frame Type`| `uint16` | Type code identifying the semantic payload purpose. |
| `8..15`| `Sequence` | `uint64` | Monotonically increasing chunk or frame sequence counter. |
| `16..23`| `Length` | `uint64` | Byte count of the immediately following payload (`0` to `16,777,216`). |
| `24..27`| `Flags` | `uint32` | Bitmask for frame attributes (`0x01` = Encrypted, `0x02` = Final Chunk, `0x04` = Compressed). |

---

## 3. Frame Types

| ID | Name | Direction | Description |
|---|---|---|---|
| `0x0001` | `HANDSHAKE_INIT` | Client &rarr; Server | Initial handshake with transfer ID, filename, file size, salt, and auth token. |
| `0x0002` | `HANDSHAKE_ACK` | Server &rarr; Client | Response confirming version negotiation and starting byte offset (`0` for new file). |
| `0x0003` | `RESUME_REQ` | Client &rarr; Server | Request to resume an interrupted transfer by transfer ID. |
| `0x0004` | `RESUME_ACK` | Server &rarr; Client | Confirms accepted resume offset from the server's crash journal. |
| `0x0005` | `CHUNK_DATA` | Client &rarr; Server | Binary payload slice (plaintext or AES-256-GCM ciphertext). |
| `0x0006` | `CHUNK_ACK` | Server &rarr; Client | Acknowledges receipt and persistence of sequence number. |
| `0x0007` | `TRANSFER_DONE` | Client &rarr; Server | Indicates end of file stream; contains 32-byte SHA-256 checksum. |
| `0x0008` | `TRANSFER_ACK` | Server &rarr; Client | Confirms checksum match and final file flush. |
| `0x0009` | `ERROR` | Bidirectional | Signals typed fatal or non-fatal protocol error. |
| `0x000A` | `PING` | Bidirectional | Keepalive heartbeat probe. |
| `0x000B` | `PONG` | Bidirectional | Keepalive heartbeat response. |

---

## 4. Authentication & Key Derivation

When `--encrypt` is active:
1. **Salt Generation**: Client generates a cryptographically secure 16-byte random salt (`java.security.SecureRandom`).
2. **Key Derivation (KDF)**:
   - Algorithm: `PBKDF2WithHmacSHA256`
   - Iterations: `100,000`
   - Key Length: `256 bits`
3. **Handshake Token**:
   - Format: `[4 bytes Magic: 0x41555448] [16 bytes TransferID] [12 bytes Salt] [16 bytes GCM Auth Tag]`
   - Tag verification proves knowledge of the passphrase before disk space allocation or file writing begins.
4. **Memory Hygiene**: Secret keys and sensitive byte buffers are zeroized (`Arrays.fill(bytes, (byte) 0)`) immediately upon session teardown.

---

## 5. Error Codes

When an unexpected error or security violation occurs, the discovering peer issues an `ERROR` (`0x0009`) frame containing a 1-byte error code followed by a UTF-8 diagnostic description:

| Code | Constant | Meaning | Retryable? |
|---|---|---|---|
| `0x01` | `AUTH_FAILED` | Passphrase mismatch or corrupt auth token. | No |
| `0x02` | `PROTOCOL_MISMATCH` | Incompatible client/server protocol version. | No |
| `0x03` | `ILLEGAL_STATE` | Frame received out of state machine sequence. | No |
| `0x04` | `CHECKSUM_MISMATCH` | SHA-256 digest failed verification upon transfer completion. | Yes (resume) |
| `0x05` | `INSUFFICIENT_SPACE`| Target volume lacks required free disk space. | No |
| `0x06` | `PATH_TRAVERSAL` | Filename contains illegal `..`, `/`, `\` characters. | No |
| `0x07` | `CORRUPTED_FRAME` | Header magic mismatch, invalid flags, or truncated payload. | Yes |
| `0x08` | `TRANSFER_REJECTED`| Unencrypted transfer rejected by encrypted receiver, or file collision. | No |
| `0x09` | `TIMEOUT` | Socket read/write or handshake deadline exceeded. | Yes |
| `0x0A` | `CONNECTION_LOST` | TCP pipe broken or peer reset. | Yes |
| `0xFF` | `INTERNAL_ERROR` | Unexpected runtime exception or OS error. | No |

---

## 6. Resumption & Retries

### Retries with Exponential Backoff
- AirSocket utilizes exponential backoff for retryable errors (`TIMEOUT`, `CONNECTION_LOST`):
  $$\text{delay} = \min(200 \text{ ms} \times 2^{\text{attempt} - 1}, 5000 \text{ ms})$$
- Default retry threshold: `3` attempts before terminating the transfer.

### Crash Journaling
- Receiver writes progress to `.airsocket_journal_<UUID>.bin` periodically during chunk reception.
- On reconnect:
  1. Client sends `RESUME_REQ` with `TransferID`.
  2. Receiver verifies journal presence, validates SHA-256 chunk sequence, and replies with `RESUME_ACK` containing the exact resume byte offset.
  3. Client seeks `RandomAccessFile` to `offset` and continues without re-transferring previously persisted bytes.
