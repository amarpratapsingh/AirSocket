# AirSocket

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://jdk.java.net/21/)
[![Build Tool](https://img.shields.io/badge/Build-Maven-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

AirSocket is a high-performance, zero-dependency, peer-to-peer (P2P) local file sharing CLI built on raw Java socket architecture. It allows secure, authenticated file transfers between systems on the same local area network (LAN) without relying on external cloud servers, configuration files, or third-party libraries.

---

## Architectural Highlights

AirSocket is designed to demonstrate core low-level networking, cryptography, and systems engineering concepts directly using the Java Standard Library (`java.base`).

* **Zero-Configuration Discovery:** Utilizes UDP broadcast packets (`42069/UDP`) for local peer discovery, measuring round-trip time (RTT) and identifying hostnames automatically.
* **Optimized TCP Data Streaming:** Implements a direct TCP stream over standard sockets, leveraging custom chunk-based framing rather than message-oriented parsing.
* **Authenticated Encryption (AEAD):** Encrypts files end-to-end using **AES-256-GCM** with key derivation via **PBKDF2WithHmacSHA256**. Decryption automatically validates data integrity via GCM's 128-bit authentication tag, throwing cryptographic exceptions if the payload is tampered with in transit.
* **Resilient State Management:** Features a stateful chunk-tracking resume engine that maintains local transfer state using `.part` and `.meta` offset files, enabling interrupted transfers to resume precisely from the last transmitted byte.
* **Dynamic Performance Profiling:** Includes a built-in TCP socket buffer tuning benchmark that profiles throughput against varying socket buffer sizes (`SO_SNDBUF`), demonstrating the throughput optimization of aligning block writes with TCP windows.

---

## Technical Performance & Benchmarks

During local network testing, scaling the application-level buffer size bypassed TCP delayed acknowledgment and Nagle's algorithm latency, yielding a **5.41x improvement** in transfer speeds:

* **Default 4 KB Buffer:** `163.6 Mbps`
* **Optimized 512 KB Buffer:** `886.0 Mbps` (approaching physical Gigabit/Wi-Fi connection limits)
* **Real-World File Transfer:** Transferred a 25 MB payload in approximately `0.3 seconds` at an average rate of **89.3 MB/s (714.4 Mbps)**.
* **Loopback Throughput:** Achieved memory-to-memory speeds up to **16.3 Gbps** on loopback interfaces.

---

## CLI Interface & Usage

### Global Options

| Option | Description | Default |
|---|---|---|
| `--port <n>` | Port used for TCP transfers and binding | `9000` |
| `--to <ip>` | Target peer IP address (for sending or benchmarking) | *None* |
| `--encrypt` | Encrypts transfer using AES-256-GCM (prompts for passphrase) | *Disabled* |
| `--resume` | Resumes a previously interrupted transfer | *Disabled* |
| `--no-progress` | Disables the real-time progress bar output | *Disabled* |
| `--benchmark` | Runs throughput performance tests | *Disabled* |
| `--buf-sizes <list>` | Comma-separated list of socket write buffer sizes to test | `8192,16384,32768,65536,131072,262144` |

---

### Command Overview

#### 1. Peer Discovery
Broadcasts discovery pings to scan the local network for other running AirSocket instances:
```bash
java -jar target/airsocket-1.0.0.jar discover
```

#### 2. Start Receiver Mode
Binds a listening socket to accept incoming files:
```bash
java -jar target/airsocket-1.0.0.jar receive
```
To enable resumable transfer support for interrupted downloads:
```bash
java -jar target/airsocket-1.0.0.jar receive --resume
```

#### 3. Send File (Direct IP)
Sends a file directly to a target peer:
```bash
java -jar target/airsocket-1.0.0.jar send archive.zip --to 192.168.1.42
```

#### 4. Send File (Auto-Discovery)
Scans the LAN and automatically targets the first discovered peer on the network:
```bash
java -jar target/airsocket-1.0.0.jar send archive.zip
```

#### 5. Encrypted Transmission
Encrypts the payload in transit. Prompts the sender and receiver for a matching passphrase:
```bash
java -jar target/airsocket-1.0.0.jar send confidential.pdf --to 192.168.1.42 --encrypt
```

#### 6. Resuming Interrupted Send
Resumes a file transfer from the exact byte offset recorded in the receiver's local metadata:
```bash
java -jar target/airsocket-1.0.0.jar send largefile.iso --to 192.168.1.42 --resume
```

#### 7. Connection Profiling & Benchmarking
Runs a diagnostic performance sweep to profile raw throughput:
```bash
java -jar target/airsocket-1.0.0.jar send dummy --to 192.168.1.42 --benchmark --buf-sizes 8192,16384,65536,262144,524288
```

---

## Getting Started

### Prerequisites
* **Java Development Kit (JDK) 21** or higher.
* **Maven** (for compiling and packaging).

### Build Instructions
Compile the project and package it into a single, executable fat JAR:
```bash
mvn clean package
```
The output executable will be created at `target/airsocket-1.0.0.jar`.

### Running Tests
Execute the automated test suite verifying connection state, packet transmission, cryptography, and resume state:
```bash
mvn test
```

---

## License
This project is licensed under the MIT License.
