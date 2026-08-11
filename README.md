# AirSocket — P2P Local File Sharing Tool

AirSocket is a desktop CLI tool that lets two computers on the same Wi-Fi discover each other and transfer files securely using raw Java sockets. No server, no cloud, no configuration.

---

## Guiding Philosophy

The file transfer is the excuse. The learning is the point.

Most tutorials abstract networking away. AirSocket exposes it:
- **UDP for discovery** — Broadcasts on LAN to find other peers.
- **TCP for transfer** — High-throughput transfer with adjustable buffer sizes.
- **AES-256-GCM for encryption** — Secure, authenticated encryption using the Java Standard Library.
- **Chunking for resume** — Resumable file transfers using `.part` and `.part.meta` offset files.

---

## Tech Stack

- **Language:** Java 21 (uses Record patterns, text blocks, sealed types)
- **Build System:** Maven + Shade (produces a single runnable fat JAR)
- **Discovery:** UDP `DatagramSocket` on port `42069`
- **Transfer:** TCP `ServerSocket` / `Socket` (port `9000` default)
- **Encryption:** `javax.crypto` (AES-256-GCM) with `PBKDF2WithHmacSHA256` key derivation
- **Serialization:** Plain `DataInputStream`/`DataOutputStream`
- **Testing:** JUnit 5

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
  --no-progress         Disable progress bar
  --benchmark           Run throughput benchmark
  --buf-sizes <list>    Comma-separated buffer sizes for benchmark
  --help                Print this help
```

### Examples

#### 1. Peer Discovery
Scan the LAN to see other active AirSocket instances:
```bash
java -jar target/airsocket-1.0.0.jar discover
```

#### 2. Start Receiver
Start AirSocket in receive mode on the default port (9000):
```bash
java -jar target/airsocket-1.0.0.jar receive
```

#### 3. Send a File (Plaintext)
Send a file to a target IP. If `--to` is omitted, the CLI automatically scans the LAN and targets the first discovered peer:
```bash
java -jar target/airsocket-1.0.0.jar send report.pdf --to 192.168.1.42
```

#### 4. Send a File (Encrypted)
Sends a file encrypted with AES-256-GCM. The tool prompts for a passphrase on both the sender and receiver side:
```bash
java -jar target/airsocket-1.0.0.jar send secret.zip --to 192.168.1.42 --encrypt
```

#### 5. Resuming a Transfer
If a transfer is interrupted, you can resume it using the `--resume` flag on both sender and receiver commands:
```bash
# On receiver side:
java -jar target/airsocket-1.0.0.jar receive --resume

# On sender side:
java -jar target/airsocket-1.0.0.jar send largefile.iso --to 192.168.1.42 --resume
```

#### 6. Run Benchmark
Measure the raw TCP throughput with different buffer sizes to understand the performance impact of socket buffer tuning:
```bash
java -jar target/airsocket-1.0.0.jar send dummy --to 192.168.1.42 --benchmark --buf-sizes 8192,16384,65536,262144
```

---

## How to Build

AirSocket has zero external dependencies (aside from JUnit for testing). To compile and build the fat executable JAR, run:

```bash
mvn clean package
```

The compiled fat JAR will be located at `target/airsocket-1.0.0.jar`.

---

## Running Tests

To run the suite of automated tests verifying discovery, encryption, transmission, and resumable state:

```bash
mvn test
```
