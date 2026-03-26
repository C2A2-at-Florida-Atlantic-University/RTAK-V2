# RTAK V2

**Android TAK ↔ Reticulum Network Stack Bridge**

RTAK is an Android application that serves as a *thin TAK server*, bridging Cursor on Target (CoT) messages between ATAK clients and the [Reticulum Network Stack](https://github.com/markqvist/Reticulum/tree/1.1.3) v1.1.3. This enables TAK-based situational awareness over Reticulum's encrypted, infrastructure-independent mesh networks — including LoRa, packet radio, serial links, WiFi, and TCP/IP.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android Device                        │
│                                                         │
│  ┌──────────┐    TCP :8087     ┌──────────────────────┐ │
│  │   ATAK   │◄───────────────►│    CotTcpServer      │ │
│  │  Client   │  CoT XML        │    (Java)            │ │
│  └──────────┘                  └──────┬───────────────┘ │
│                                       │                  │
│  ┌──────────┐                  ┌──────▼───────────────┐ │
│  │  WinTAK  │◄──── TCP ──────►│  TakBridgeService    │ │
│  │ (remote) │                  │  (Foreground Service) │ │
│  └──────────┘                  └──────┬───────────────┘ │
│                                       │ Chaquopy JNI    │
│                                ┌──────▼───────────────┐ │
│                                │   rtak_bridge.py     │ │
│                                │   (Python / RNS)     │ │
│                                └──────┬───────────────┘ │
│                                       │                  │
│                                ┌──────▼───────────────┐ │
│                                │   Reticulum 1.1.3    │ │
│                                │   Network Stack      │ │
│                                └──────┬───────────────┘ │
└───────────────────────────────────────┼─────────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    │                   │                    │
              ┌─────▼─────┐     ┌──────▼──────┐    ┌───────▼───────┐
              │  RNode    │     │  WiFi/LAN   │    │   TCP Link    │
              │  (LoRa)   │     │ AutoIface   │    │   to Remote   │
              └───────────┘     └─────────────┘    └───────────────┘
```

## How It Works

1. **TAK TCP Server** (`CotTcpServer.java`): Listens on port 8087 for TCP connections from ATAK. Speaks TAK Protocol Version 0 (plain CoT XML delimited by `</event>` tags).

2. **Bridge Service** (`TakBridgeService.java`): A foreground service that orchestrates the TCP server and the Reticulum bridge. It routes CoT events bidirectionally:
   - **TAK → Reticulum**: CoT XML from any connected TAK client is forwarded to all Reticulum peers.
   - **Reticulum → TAK**: CoT XML received from any Reticulum peer is pushed to all connected TAK clients.

3. **Reticulum Bridge** (`ReticulumBridge.java` + `rtak_bridge.py`): The Java wrapper calls into Python via Chaquopy. The Python module initialises the Reticulum Network Stack, creates encrypted destinations, manages links, and handles announce discovery.

4. **CoT Helpers** (`cot_helper.py`): Python utilities for building and parsing CoT XML messages.

## Project Structure

```
rtak-bridge/
├── build.gradle                     # Root Gradle (Chaquopy plugin)
├── settings.gradle
├── gradle.properties
├── app/
│   ├── build.gradle                 # App Gradle (Chaquopy config + RNS pip)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/rtak/bridge/
│       │   ├── RTAKApplication.java     # App init, Chaquopy Python.start()
│       │   ├── RTAKCallback.java        # Java interface for Python callbacks
│       │   ├── ReticulumBridge.java     # Java↔Python Chaquopy wrapper
│       │   ├── model/
│       │   │   └── BridgeStatus.java    # Observable status model
│       │   ├── service/
│       │   │   ├── TakBridgeService.java    # Foreground service
│       │   │   └── CotTcpServer.java        # TAK TCP server
│       │   └── ui/
│       │       ├── MainActivity.java        # Dashboard UI
│       │       └── SettingsActivity.java    # Configuration
│       ├── python/
│       │   ├── rtak_bridge.py           # Core Reticulum↔TAK bridge (Python)
│       │   └── cot_helper.py            # CoT XML builder/parser
│       └── res/
│           ├── layout/
│           ├── values/
│           ├── menu/
│           ├── xml/
│           └── drawable/
```

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Python 3.11 on the build machine (for Chaquopy)
- Android SDK 34

### Steps

1. Clone or extract this project.
2. Open in Android Studio.
3. Sync Gradle — Chaquopy will download CPython and install `rns` via pip.
4. Build and run on a device or emulator (min API 24 / Android 7.0).

> **Note:** The first build may take several minutes as Chaquopy downloads and compiles Python packages for each target ABI.

## Usage

### Starting the Bridge

1. Launch RTAK on your Android device.
2. Tap **Start Bridge** — this launches the foreground service which:
   - Initialises Reticulum (creates/loads identity, starts interfaces)
   - Starts the TAK TCP server on port 8087
3. The dashboard will show `RUNNING` once ready.

### Connecting ATAK

In ATAK, configure a TAK Server connection:
- **Protocol**: TCP
- **Address**: The Android device's IP address
- **Port**: 8087

ATAK will connect and begin exchanging CoT events through the bridge.

### Connecting Reticulum Peers

- Tap **Announce** to broadcast this node's existence on the Reticulum network.
- Tap **Connect** and enter a remote RTAK node's destination hash to establish an encrypted link.
- Peers discovered via AutoInterface (same WiFi/LAN) will connect automatically.

### Reticulum Interfaces

The default config enables `AutoInterface` for local peer discovery. To add additional interfaces (RNode LoRa, TCP, etc.), edit the Reticulum config file at:

```
/data/data/com.caai.rtak/files/reticulum/config
```

Or modify the default config template in `rtak_bridge.py`.

## Chaquopy Integration Details

Chaquopy bridges Java and Python through JNI. Key integration points:

| Java Side | Python Side | Purpose |
|-----------|-------------|---------|
| `RTAKApplication.onCreate()` | `Python.start(AndroidPlatform)` | Bootstrap CPython |
| `ReticulumBridge.init()` | `rtak_bridge.init()` | Start Reticulum |
| `ReticulumBridge.sendCot()` | `rtak_bridge.send_cot()` | Send CoT over RNS |
| `RTAKCallback` interface | `_notify_*()` functions | Python→Java callbacks |

The `RTAKCallback` interface is passed to Python as a Chaquopy `PyObject`, allowing the Python code to invoke Java methods directly when events occur (CoT received, peer connected, etc.).

## CoT Protocol Support

- **TAK Protocol Version 0**: Plain XML CoT over TCP (default for Mesh SA)
- Events are delimited by `</event>` tags
- Full SA/PLI event construction with MIL-STD-2525 type codes
- Ping/pong support for server health checks

## Security

- All Reticulum traffic is **end-to-end encrypted** using Curve25519 + AES
- Each bridge node has a persistent Ed25519/X25519 identity
- Links provide forward secrecy via ephemeral key exchange
- TAK client ↔ bridge TCP connection is **cleartext** (standard TAK protocol); for encrypted TAK connections, use TAK Server with TLS certificates
