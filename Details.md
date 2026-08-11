# AirSocket — P2P Local File Sharing Tool

## Elevator Pitch

A desktop CLI tool that lets two computers on the same Wi-Fi discover each other and transfer files securely using raw Java sockets. No server, no cloud, no configuration. Teaches how data actually travels across a network bit by bit.

---

## Guiding Philosophy

The file transfer is the excuse. The learning is the point.

Most tutorials abstract networking away. AirSocket exposes it:
- UDP for discovery (you decide the protocol)
- TCP for transfer (you tune the buffer)
- AES-256-GCM for encryption (you derive the key)
- Chunking for resume (you track the offset)

Every feature doubles as a teaching moment.

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Language | Java 21 | Record patterns, text blocks, sealed types |
| Build | Maven + Shade | Fat JAR, one-file distribution |
| Discovery | UDP `DatagramSocket` | Broadcast on port 42069 |
| Transfer | TCP `ServerSocket` / `Socket` | Reliable stream |
| Encryption | `javax.crypto` (AES-256-GCM) | Authenticated, standard library |
| Key derivation | `PBKDF2WithHmacSHA256` | Salt + iterations → strong key |
| Serialization | DataInputStream/DataOutputStream | No JSON library needed |
| Tests | JUnit 5 | |

---

## Project Structure

```
airsocket/
├── Details.md                     # This file
├── pom.xml
├── .gitignore
├── README.md
├── src/
│   ├── main/java/com/airsocket/
│   │   ├── AirSocket.java          # CLI entry point
│   │   ├── discovery/
│   │   │   ├── Discoverer.java     # UDP broadcast sender
│   │   │   └── Responder.java      # UDP broadcast listener
│   │   ├── transfer/
│   │   │   ├── Sender.java         # TCP file sender
│   │   │   ├── Receiver.java       # TCP file receiver
│   │   │   └── Chunk.java          # Chunk metadata + byte tracking
│   │   ├── crypto/
│   │   │   └── Crypto.java         # AES-256-GCM encrypt/decrypt
│   │   └── model/
│   │       └── Peer.java           # Peer (hostname, IP, port, RTT)
│   └── test/java/com/airsocket/
│       ├── DiscovererTest.java
│       ├── SenderReceiverTest.java
│       └── CryptoTest.java
```

---

## Features — Ordered by Implementation

### Phase 1: Discovery (`--discover`)

**Goal:** Two instances find each other on the same LAN with no config.

**Protocol (UDP):**
1. Peer A broadcasts to `255.255.255.255:42069`:
   ```json
   {"type":"ping","hostname":"amar-pc","port":9000}
   ```
2. Peer B responds directly to Peer A's IP:
   ```json
   {"type":"pong","hostname":"nas-server","port":9000}
   ```
3. Peer A prints:
   ```
   Scanning LAN...
     192.168.1.42  amar-pc        (RTT: 1.2ms)
     192.168.1.73  nas-server     (RTT: 2.1ms)
   ```

**Key decisions:**
- Timeout after 2 seconds
- Use `System.currentTimeMillis()` embedded in ping to calculate RTT
- `DatagramSocket` with `setBroadcast(true)` and `setSoTimeout(2000)`

**Learning moment:** UDP is fire-and-forget. No guarantee a peer responds. The 2-second timeout shows exactly how unreliable UDP feels in practice.

---

### Phase 2: Send/Receive (`task send` / `task receive`)

**Goal:** Transfer a file between two peers over TCP.

**Protocol (TCP):**
1. Sender connects to Receiver's IP:port
2. Handshake (plaintext):
   - Sender writes: `[file_name_length:4B][file_name][file_size:8B]`
   - Receiver writes: `[ack:1B = 0x06]`
3. Transfer loop:
   - Sender reads chunk from file, writes to socket
   - Receiver reads from socket, writes to file
4. Teardown: both close

**Sender:**
```java
try (Socket s = new Socket(peerIp, port);
     OutputStream out = s.getOutputStream();
     FileInputStream fis = new FileInputStream(file)) {
    writeString(out, file.getName());
    writeLong(out, file.length());
    waitForAck(s.getInputStream());        // 0x06
    byte[] buf = new byte[8192];
    int read;
    while ((read = fis.read(buf)) != -1)
        out.write(buf, 0, read);
}
```

**Receiver:**
```java
try (ServerSocket server = new ServerSocket(port);
     Socket s = server.accept();
     InputStream in = s.getInputStream()) {
    String name = readString(in);
    long size = readLong(in);
    sendAck(s.getOutputStream());           // 0x06
    try (FileOutputStream fos = new FileOutputStream(name)) {
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1)
            fos.write(buf, 0, read);
    }
}
```

**Learning moment:** TCP is a stream, not a message boundary. The 4-byte length prefix for the filename is necessary because `readLine()` doesn't work on binary streams. This is why HTTP has `Content-Length`.

---

### Phase 3: Progress (`--progress`)

**Goal:** Show a real-time progress bar during transfer.

Wrap the output stream in a custom `ProgressOutputStream` that counts bytes written and prints a progress bar every 100ms:

```
  ████████████████████████████░░░  87%  (347/398 MB)  28.1 MB/s
```

**Learning moment:** Progress tracking requires the total size upfront. Without `file.length()`, you can't calculate percentage. This is why HTTP responses include `Content-Length`.

---

### Phase 4: Encryption (`--encrypt`)

**Goal:** Encrypt the file before sending so it can't be read mid-transit.

**Key derivation (on both sides):**
```java
SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 65536, 256);
SecretKey key = factory.generateSecret(spec);
SecretKeySpec aesKey = new SecretKeySpec(key.getEncoded(), "AES");
```

**Encrypt (sender):**
1. Generate random 16-byte salt
2. Generate random 12-byte IV (GCM nonce)
3. Derive key via PBKDF2 (salt, 65536 iterations)
4. Write salt + IV to output (plaintext)
5. Wrap output stream in `CipherOutputStream` with AES/GCM/NoPadding
6. Write file data

**Decrypt (receiver):**
1. Read salt + IV from input
2. Derive same key via PBKDF2 (same salt)
3. Wrap input stream in `CipherInputStream` with AES/GCM/NoPadding
4. Read decrypted data

**File format:**
```
┌──────────────────────────────────────────────┐
│ Salt (16 bytes)                              │
├──────────────────────────────────────────────┤
│ IV / Nonce (12 bytes)                        │
├──────────────────────────────────────────────┤
│ AES-256-GCM encrypted data (rest of file)    │
└──────────────────────────────────────────────┘
```

The file is self-contained. Receiver only needs the passphrase — everything else is in the file.

**Learning moment:** AES-GCM is authenticated — it detects tampering. If someone modifies the encrypted file mid-transfer, decryption fails with `AEADBadTagException`. Compare this to AES-CBC (unauthenticated) where corruption goes undetected.

---

### Phase 5: Resume (`--resume`)

**Goal:** If the connection drops, retry without re-sending the whole file.

**How:**
1. Receiver saves to `<filename>.part`
2. Receiver tracks bytes received in `<filename>.part.meta` (just the byte offset as text)
3. On resume: Receiver sends `RESUME:<offset>` to Sender
4. Sender seeks to `<offset>` in the source file and starts sending from there
5. Receiver appends to `.part` file
6. On completion, receiver renames `.part` → original name, deletes `.meta`

**Learning moment:** Resumable transfers require both sides to agree on a byte offset. This is exactly what HTTP `Range` headers do. The `.part` / `.meta` pattern is how browsers handle interrupted downloads.

---

### Phase 6: Benchmark (`--benchmark`)

**Goal:** Measure raw TCP throughput between two peers.

```
$ java -jar airsocket.jar --benchmark --to 192.168.1.42

Benchmarking TCP throughput to 192.168.1.42:9000...
  Buffer:   8 KB    12.4 Mbps
  Buffer:  16 KB    24.1 Mbps
  Buffer:  32 KB    45.8 Mbps
  Buffer:  64 KB    87.2 Mbps  ← sweet spot
  Buffer: 128 KB    89.1 Mbps
  Buffer: 256 KB    88.5 Mbps
```

Sends a fixed payload (100MB of random bytes) multiple times with different `SO_SNDBUF` sizes.

**Learning moment:** Buffer size directly impacts throughput up to a point, then plateaus. Users see for themselves why `BufferedInputStream` exists and why the default 8KB TCP buffer is often too small.

---

## CLI Interface

```
Usage: airsocket [command] [options]

Commands:
  discover              Scan LAN for other AirSocket instances
  send <file>           Send a file to a peer
  receive               Listen for incoming files

Options:
  --to <ip>             Target peer IP (for send/benchmark)
  --port <n>            Port (default: 9000)
  --encrypt             Encrypt with AES-256-GCM (prompts for passphrase)
  --resume              Resume interrupted transfer
  --progress            Show progress bar (default: on)
  --benchmark           Run throughput benchmark
  --buf-sizes <list>    Comma-separated buffer sizes for benchmark
  --help                Print this help
```

### Examples

```bash
# Discover peers on the LAN
java -jar airsocket.jar discover

# Send a file (will prompt for peer if --to omitted)
java -jar airsocket.jar send report.pdf --to 192.168.1.42

# Receive mode
java -jar airsocket.jar receive

# Encrypted transfer (both sides need the passphrase)
java -jar airsocket.jar send confidential.zip --to 192.168.1.42 --encrypt

# Resume interrupted transfer
java -jar airsocket.jar send largefile.iso --to 192.168.1.42 --resume

# Benchmark TCP throughput
java -jar airsocket.jar --benchmark --to 192.168.1.42 --buf-sizes 8192,16384,65536
```

---

## Testing Strategy

| Test | What it validates |
|---|---|
| `DiscovererTest` | Sends ping, verifies pong response with correct hostname |
| `SenderReceiverTest` | Starts receiver thread, sender connects, 100MB file transfers byte-identical |
| `SenderReceiverTest` with encryption | Same but encrypted → decrypted on the other side |
| `CryptoTest` | Encrypt → decrypt yields identical plaintext |
| `CryptoTest` (tamper) | Modify one byte of ciphertext → `AEADBadTagException` |
| `ResumeTest` | Send 50%, disconnect, resume → file matches original |

---

## What Makes This Unique (Not Just Another File Sender)

1. **`--benchmark` with `--buf-sizes`** — a file transfer tool that doubles as a TCP learning lab. No other tool does this.

2. **UDP discovery from scratch** — no mDNS, no Zeroconf library. Three classes and you understand how LAN discovery works at the wire level.

3. **Self-contained encrypted files** — salt + IV prepended to the file. No sidecar files, no config. Just a passphrase.

4. **`--resume` with visible `.part` files** — shows the exact mechanism browsers use for download resume.

5. **Everything in `java.base`** — zero dependencies. The fat JAR is tiny (~50KB). Contrast with WebRTC solutions that need Node.js, signaling servers, and STUN/TURN.

---

## Implementation Order

| # | Feature | Files | Est. Time |
|---|---|---|---|
| 1 | Project scaffold (pom.xml, entry point, CLI) | `AirSocket.java`, `pom.xml` | 30 min |
| 2 | Discovery | `Discoverer.java`, `Responder.java`, `Peer.java` | 1 hr |
| 3 | Send + Receive | `Sender.java`, `Receiver.java` | 1.5 hr |
| 4 | Progress bar | (inline in Sender/Receiver) | 30 min |
| 5 | Encryption | `Crypto.java` | 1 hr |
| 6 | Resume | Chunk tracking in Receiver | 1 hr |
| 7 | Benchmark | Benchmark mode | 1 hr |
| 8 | Tests | All test files | 1 hr |
| 9 | README + polish | `README.md`, `.gitignore` | 30 min |

**Total: ~8 hours**

---

## Comparison to ThreadTrace

| | ThreadTrace | AirSocket |
|---|---|---|
| Paradigm | Multi-threaded file reading | P2P file transfer |
| Network | Reads local files | Sends/receives over network |
| Concurrency | Thread pool | Single socket per transfer |
| Crypto | None | AES-256-GCM |
| Discovery | N/A | UDP broadcast |
| CLI | `--by-component`, `--by-time` | `discover`, `send`, `receive` |
| Unique angle | Multi-dimensional log analysis | TCP education through benchmarking |

AirSocket is threadtrace's sibling — same low-level Java mindset, same zero-dependency philosophy, applied to a completely different problem.
