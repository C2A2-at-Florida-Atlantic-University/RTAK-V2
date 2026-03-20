"""
rtak_bridge.py — Core Reticulum ↔ TAK Bridge (Python side)

This module runs inside Chaquopy on Android. It initialises Reticulum Network
Stack, creates a destination for TAK traffic, and exposes functions that the
Java layer calls to send/receive CoT XML over Reticulum.

Architecture
────────────
  ATAK ⇄ (TCP :8087) ⇄ Java TAK Server ⇄ (Chaquopy) ⇄ rtak_bridge.py ⇄ RNS

Fragmentation Protocol
──────────────────────
CoT XML is zlib-compressed then split into chunks that fit within the RNS
packet MTU. Each fragment carries a 4-byte header:

    [msg_id: 2 bytes][index: 1 byte][total: 1 byte][payload ...]

  msg_id  — random 16-bit ID correlating fragments of one message
  index   — fragment number (0-based)
  total   — total number of fragments

The receiver buffers fragments keyed by (link_hash, msg_id). Once all
fragments arrive, the payload is reassembled and decoded to CoT XML.
Single-fragment messages (total == 1) are delivered immediately with
no buffering.
"""

import os
import sys
import io
import time
import json
import zlib
import struct
import threading
import traceback
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# Reticulum will be imported after init() sets up the config directory.
# ---------------------------------------------------------------------------
RNS = None

# Module-level state
_reticulum   = None
_identity    = None
_destination = None
_links       = {}          # link_hash_hex → RNS.Link
_pending_links = {}        # id(link) → RNS.Link  (keeps outbound links alive until established)
_inbound_peer_map = {}     # link_hash_hex → remote_dest_hash_hex  (inbound links only)
_known_peers = {}          # dest_hash_hex → {"identity": ..., "app_data": str}
_own_hash    = None        # Our own destination hash as hex (for self-filter)
_callbacks   = None        # Java-side callback object
_running     = False
_config_path = None
_announce_app_data = b"RTAK Bridge"

# ── Dynamic interface management ──────────────────────────────────────────
# Interfaces added at runtime (not from the static RNS config file).
# name → {"interface": RNS.Interface, "config": dict, "enabled": bool,
#          "vid": int|None, "pid": int|None}
_managed_interfaces     = {}
_interface_registry_path = None   # path to rtak_interfaces.json
_announce_handler        = None   # current RTAKAnnounceHandler (tracked for clean deregistration)
_interfaces_locked       = False  # True once RNS starts; prevents all CRUD until app restart

# ── Fragmentation constants ───────────────────────────────────────────────
_HEADER_FMT  = ">HBB"            # msg_id(u16), index(u8), total(u8)
_HEADER_SIZE = struct.calcsize(_HEADER_FMT)   # 4 bytes
_DEFAULT_CHUNK_SIZE = 410         # fallback if link.mdu unavailable
_LINK_KEEPALIVE = 120             # seconds between link keepalive packets
_FRAG_TIMEOUT = 15.0              # seconds before incomplete message is discarded

# Per-peer send counters:  link_hash_hex → int (0–65535)
_send_counters = {}
_send_counters_lock = threading.Lock()

# Reassembly buffer:  (link_hash, msg_id) → { "frags": {index: bytes}, "total": int, "ts": float }
_reassembly  = {}
_reassembly_lock = threading.Lock()

# Compression markers
_COMPRESS_FLAG = b"\x01"
_RAW_FLAG      = b"\x00"

APP_NAME      = "rtak"
ASPECT_COT    = "cot"
ASPECT_SERVER = "server"


# ── Logging ───────────────────────────────────────────────────────────────
def _log(msg, level="INFO"):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[RTAK {level} {ts}] {msg}", flush=True)


# ── Initialisation ────────────────────────────────────────────────────────
def init(config_dir, callback_obj=None):
    """
    Start the bridge.  On the first call this also initialises Reticulum.
    On subsequent calls (after stop_bridge()) it reuses the running RNS/Transport
    instance and only recreates the bridge layer — avoiding the unsafe reinit path.

    IMPORTANT: Call generate_rns_config() from Java BEFORE calling init() so
    that the config file contains the correct interfaces.  init() does NOT
    dynamically add interfaces — they come from the generated config file.
    """
    global RNS, _reticulum, _identity
    global _callbacks, _config_path, _interfaces_locked, _interface_registry_path

    _callbacks   = callback_obj
    _config_path = config_dir
    _interface_registry_path = os.path.join(config_dir, "rtak_interfaces.json")

    if _reticulum is not None:
        # RNS already running — restart only the bridge layer on top of it.
        _log("RNS already active — restarting bridge layer only")
        _interfaces_locked = True
        return _start_bridge_layer()

    _log(f"Initialising Reticulum (config: {config_dir})")
    _notify_status("STARTING")

    try:
        os.makedirs(config_dir, exist_ok=True)

        # Migrate old interface registry schema if needed
        _migrate_interface_registry(config_dir)

        # Ensure a config file exists (generate_rns_config should have been
        # called already by Java, but fall back to a default if missing)
        if not os.path.isfile(os.path.join(config_dir, "config")):
            _ensure_default_config(config_dir)

        # Android workaround: signal.signal() only works on main thread
        import signal
        _original_signal = signal.signal
        signal.signal = lambda signum, handler: None

        import RNS as _rns
        RNS = _rns
        _reticulum = RNS.Reticulum(config_dir)
        signal.signal = _original_signal

        # Persistent identity
        identity_path = os.path.join(config_dir, "rtak_identity")
        if os.path.isfile(identity_path):
            _identity = RNS.Identity.from_file(identity_path)
            _log(f"Loaded identity: {_identity}")
        else:
            _identity = RNS.Identity()
            _identity.to_file(identity_path)
            _log(f"Generated new identity: {_identity}")

        # Lock interfaces — they are now baked into the RNS config file.
        # No dynamic additions/removals allowed until app restart.
        _interfaces_locked = True

        return _start_bridge_layer()

    except Exception as e:
        _log(f"Init failed: {e}\n{traceback.format_exc()}", "ERROR")
        _notify_status("ERROR")
        return None


def _start_bridge_layer():
    """
    Create (or recreate) the RNS destination and wire up callbacks.
    Requires RNS/_reticulum/_identity to already be initialised.
    Safe to call repeatedly after stop_bridge().
    """
    global _destination, _own_hash, _running, _announce_handler

    _running = True
    _notify_status("STARTING")

    try:
        # Deregister any previous announce handler to avoid duplicates
        if _announce_handler is not None:
            try:
                RNS.Transport.announce_handlers.remove(_announce_handler)
            except (ValueError, AttributeError):
                pass

        # Fresh inbound destination
        _destination = RNS.Destination(
            _identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME, ASPECT_COT, ASPECT_SERVER,
        )
        _destination.set_proof_strategy(RNS.Destination.PROVE_ALL)
        _destination.set_link_established_callback(_on_link_established)

        _own_hash = _destination.hash.hex()

        # Register announce handler for peer discovery
        _announce_handler = RTAKAnnounceHandler()
        RNS.Transport.register_announce_handler(_announce_handler)

        # Reassembly cleanup (daemon thread exits when _running goes False)
        threading.Thread(target=_reassembly_cleanup_loop, daemon=True).start()

        _log(f"Bridge layer ready. Dest: {RNS.prettyhexrep(_destination.hash)}")
        _notify_status("RUNNING")
        return RNS.prettyhexrep(_destination.hash)

    except Exception as e:
        _log(f"Failed to start bridge layer: {e}\n{traceback.format_exc()}", "ERROR")
        _notify_status("ERROR")
        _running = False
        return None


def stop_bridge():
    """
    Stop the TAK bridge layer (links, destination, TCP state) without
    touching RNS or Transport.  Call init() again to restart.
    """
    global _running, _destination, _announce_handler

    _running = False

    # Tear down all active links
    for lhex, link in list(_links.items()):
        try:
            link.teardown()
        except:
            pass
    _links.clear()
    _known_peers.clear()
    with _send_counters_lock:
        _send_counters.clear()
    with _reassembly_lock:
        _reassembly.clear()

    # Deregister announce handler so no new peer callbacks fire
    if _announce_handler is not None and RNS is not None:
        try:
            RNS.Transport.announce_handlers.remove(_announce_handler)
        except (ValueError, AttributeError):
            pass
        _announce_handler = None

    # Remove destination from Transport so no new inbound links arrive
    if _destination is not None:
        try:
            _destination.set_link_established_callback(None)
        except:
            pass
        try:
            RNS.Transport.destinations.remove(_destination)
        except (ValueError, AttributeError):
            pass
        _destination = None

    _notify_status("STOPPED")
    _log("Bridge stopped (RNS/Transport remain active)")


def shutdown():
    """
    Full teardown: stop the bridge layer then shut down RNS entirely.
    Called only when the Android service is destroyed (app close).
    """
    global _reticulum, _identity, _own_hash, RNS

    stop_bridge()
    _managed_interfaces.clear()

    if _reticulum is not None:
        try:
            _reticulum.exit_handler()
        except Exception as e:
            _log(f"RNS exit_handler error: {e}", "WARN")

    _reticulum = None
    _identity  = None
    _own_hash  = None
    RNS        = None

    _log("Bridge shut down")


# ── Config ────────────────────────────────────────────────────────────────
def _ensure_default_config(config_dir):
    config_file = os.path.join(config_dir, "config")
    if os.path.isfile(config_file):
        with open(config_file, "r") as f:
            existing = f.read()
        if "AutoInterface" in existing:
            _log("Migrating config: replacing AutoInterface with UDPInterface")
            os.remove(config_file)
        else:
            return

    default_config = """# RTAK Bridge — Reticulum Config

[reticulum]
  enable_transport = True
  share_instance   = No
  shared_instance_port = 37428
  instance_control_port = 37429
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]
  [[Default UDP Interface]]
    type = UDPInterface
    enabled = Yes
    listen_ip = 0.0.0.0
    listen_port = 4242
    forward_ip = 255.255.255.255
    forward_port = 4242

  # [[TCP Client]]
  #   type = TCPClientInterface
  #   enabled = no
  #   target_host = 127.0.0.1
  #   target_port = 4242

  # [[RNode LoRa]]
  #   type = RNodeInterface
  #   enabled = no
  #   port = /dev/ttyUSB0
  #   frequency = 915000000
  #   bandwidth = 125000
  #   txpower = 7
  #   spreadingfactor = 8
  #   codingrate = 5
"""
    with open(config_file, "w") as f:
        f.write(default_config)
    _log("Wrote default Reticulum config")


# ══════════════════════════════════════════════════════════════════════════
#  DYNAMIC INTERFACE MANAGEMENT
# ══════════════════════════════════════════════════════════════════════════

# Supported interface types and their module locations.
# Android-specific variants are preferred where available.
_INTERFACE_TYPES = {
    "UDPInterface":       ("RNS.Interfaces.UDPInterface",         "UDPInterface"),
    "TCPClientInterface": ("RNS.Interfaces.TCPInterface",         "TCPClientInterface"),
    "TCPServerInterface": ("RNS.Interfaces.TCPInterface",         "TCPServerInterface"),
    "RNodeInterface":     ("RNS.Interfaces.Android.RNodeInterface","RNodeInterface"),
    "SerialInterface":    ("RNS.Interfaces.Android.SerialInterface","SerialInterface"),
    "KISSInterface":      ("RNS.Interfaces.Android.KISSInterface", "KISSInterface"),
}
# Desktop fallbacks for types that have Android variants
_INTERFACE_FALLBACKS = {
    "RNodeInterface":  ("RNS.Interfaces.RNodeInterface",  "RNodeInterface"),
    "SerialInterface": ("RNS.Interfaces.SerialInterface", "SerialInterface"),
    "KISSInterface":   ("RNS.Interfaces.KISSInterface",   "KISSInterface"),
}


def _instantiate_interface(config_dict):
    """Instantiate an RNS interface object from a config dict.

    Values in config_dict may be Python native types (int, bool, str);
    Interface.get_config_obj() wraps them in ConfigObj which handles
    as_bool() / as_int() for both string and native-type values.
    """
    import importlib
    itype = config_dict.get("type", "")
    if itype not in _INTERFACE_TYPES:
        raise ValueError(f"Unsupported interface type: {itype!r}. "
                         f"Supported: {list(_INTERFACE_TYPES)}")

    module_path, class_name = _INTERFACE_TYPES[itype]
    try:
        mod = importlib.import_module(module_path)
    except ImportError:
        if itype in _INTERFACE_FALLBACKS:
            fb_module, fb_class = _INTERFACE_FALLBACKS[itype]
            _log(f"Android interface module {module_path!r} unavailable, "
                 f"falling back to {fb_module!r}", "WARN")
            mod = importlib.import_module(fb_module)
            class_name = fb_class
        else:
            raise

    cls = getattr(mod, class_name)
    return cls(RNS.Transport, config_dict)


def add_interface(config_json_str):
    """Add an RNS interface at runtime.

    Args:
        config_json_str: JSON string containing at minimum "name" and "type",
                         plus any type-specific fields (see plan for schemas).
    Returns:
        The interface name on success, or None on failure.
    """
    if _interfaces_locked:
        _log("add_interface: interfaces are locked while RTAK is running", "WARN")
        return None
    if _reticulum is None or not _running:
        _log("add_interface: bridge not running", "WARN")
        return None
    try:
        config = json.loads(config_json_str)
    except Exception as e:
        _log(f"add_interface: invalid JSON: {e}", "ERROR")
        return None
    # Pop vid/pid before passing to _add_interface_dict — they are metadata injected
    # by the Java layer for USB device tracking and must not reach the interface constructor.
    vid = config.pop("vid", None)
    pid = config.pop("pid", None)
    return _add_interface_dict(config, vid=vid, pid=pid)


def _add_interface_dict(config, vid=None, pid=None, save=True):
    """Internal: add an interface from a plain dict. Returns name or None."""
    name  = config.get("name", "").strip()
    itype = config.get("type", "").strip()

    if not name or not itype:
        _log("add_interface: 'name' and 'type' are required", "ERROR")
        return None

    if name in _managed_interfaces:
        _log(f"add_interface: interface '{name}' already exists", "WARN")
        return None

    try:
        iface = _instantiate_interface(config)
        _reticulum._add_interface(iface)

        _managed_interfaces[name] = {
            "interface": iface,
            "config":    config,
            "enabled":   True,
            "vid":       vid,
            "pid":       pid,
        }

        if save:
            _save_interface_registry()

        _log(f"Interface added: {name} ({itype})")
        _notify_interface_changed(name, "ADDED")
        return name

    except Exception as e:
        _log(f"add_interface '{name}' failed: {e}\n{traceback.format_exc()}", "ERROR")
        return None


def remove_interface(name):
    """Remove a dynamically-managed RNS interface by name.

    Returns True on success, False if the interface was not found or removal
    failed.
    """
    if _interfaces_locked:
        _log("remove_interface: interfaces are locked while RTAK is running", "WARN")
        return False
    entry = _managed_interfaces.get(name)
    if entry is None:
        _log(f"remove_interface: '{name}' not found in managed interfaces", "WARN")
        return False

    iface = entry["interface"]
    try:
        iface.detach()
    except Exception as e:
        _log(f"remove_interface: detach error for '{name}': {e}", "WARN")

    try:
        RNS.Transport.interfaces.remove(iface)
    except ValueError:
        pass  # Already removed from Transport (e.g. by detach itself)

    # Mark disabled in registry (preserve config for future re-attach recognition)
    entry["enabled"] = False
    _managed_interfaces.pop(name)
    _save_interface_registry()

    _log(f"Interface removed: {name}")
    _notify_interface_changed(name, "REMOVED")
    return True


def update_interface(config_json_str):
    """Update config of an existing managed interface.

    Tears down the old interface instance, merges new config values,
    re-instantiates, and re-adds to RNS Transport.

    Args:
        config_json_str: JSON string with "name" (required) and any fields to update.
    Returns:
        The interface name on success, or None on failure.
    """
    if _interfaces_locked:
        _log("update_interface: interfaces are locked while RTAK is running", "WARN")
        return None
    if _reticulum is None or not _running:
        _log("update_interface: bridge not running", "WARN")
        return None
    try:
        new_config = json.loads(config_json_str)
    except Exception as e:
        _log(f"update_interface: invalid JSON: {e}", "ERROR")
        return None

    name = new_config.get("name", "").strip()
    if name not in _managed_interfaces:
        _log(f"update_interface: '{name}' not found", "WARN")
        return None

    entry = _managed_interfaces[name]

    if not entry.get("enabled", True):
        # Interface is disabled — just update stored config, don't instantiate
        entry["config"].update(new_config)
        _save_interface_registry()
        _log(f"Interface config updated (disabled): {name}")
        _notify_interface_changed(name, "UPDATED")
        return name

    old_iface = entry.get("interface")

    # Tear down old instance
    if old_iface is not None:
        try:
            old_iface.detach()
        except Exception:
            pass
        try:
            RNS.Transport.interfaces.remove(old_iface)
        except ValueError:
            pass

    # Merge new config over old
    merged = dict(entry["config"])
    merged.update(new_config)

    try:
        new_iface = _instantiate_interface(merged)
        _reticulum._add_interface(new_iface)
        entry["interface"] = new_iface
        entry["config"] = merged
        _save_interface_registry()
        _log(f"Interface updated: {name}")
        _notify_interface_changed(name, "UPDATED")
        return name
    except Exception as e:
        _log(f"update_interface '{name}' failed: {e}\n{traceback.format_exc()}", "ERROR")
        return None


def disable_interface(name):
    """Disable a managed interface (detach from Transport but keep in registry).

    Returns True on success.
    """
    if _interfaces_locked:
        _log("disable_interface: interfaces are locked while RTAK is running", "WARN")
        return False
    entry = _managed_interfaces.get(name)
    if entry is None:
        _log(f"disable_interface: '{name}' not found", "WARN")
        return False

    if not entry.get("enabled", True):
        _log(f"disable_interface: '{name}' already disabled")
        return True

    iface = entry.get("interface")
    if iface is not None:
        try:
            iface.detach()
        except Exception as e:
            _log(f"disable_interface: detach error for '{name}': {e}", "WARN")
        try:
            RNS.Transport.interfaces.remove(iface)
        except ValueError:
            pass

    entry["enabled"] = False
    entry["interface"] = None
    _save_interface_registry()

    _log(f"Interface disabled: {name}")
    _notify_interface_changed(name, "DISABLED")
    return True


def enable_interface(name):
    """Re-enable a disabled managed interface.

    Re-instantiates from stored config and adds to Transport.
    Returns True on success.
    """
    if _interfaces_locked:
        _log("enable_interface: interfaces are locked while RTAK is running", "WARN")
        return False
    entry = _managed_interfaces.get(name)
    if entry is None:
        _log(f"enable_interface: '{name}' not found", "WARN")
        return False

    if entry.get("enabled", True) and entry.get("interface") is not None:
        _log(f"enable_interface: '{name}' already enabled")
        return True

    config = entry["config"]
    try:
        iface = _instantiate_interface(config)
        _reticulum._add_interface(iface)
        entry["interface"]    = iface
        entry["enabled"]      = True
        entry["disconnected"] = False
        _save_interface_registry()
        _log(f"Interface enabled: {name}")
        _notify_interface_changed(name, "ENABLED")
        return True
    except Exception as e:
        _log(f"enable_interface '{name}' failed: {e}\n{traceback.format_exc()}", "ERROR")
        return False


def disconnect_interface(name):
    """Mark a managed interface as physically disconnected (e.g. USB unplugged).

    Detaches from RNS Transport and keeps the entry in the registry so the UI
    can show it as disconnected (red indicator) rather than removing it.
    Returns True on success.
    """
    if _interfaces_locked:
        _log("disconnect_interface: interfaces are locked while RTAK is running", "WARN")
        return False
    entry = _managed_interfaces.get(name)
    if entry is None:
        _log(f"disconnect_interface: '{name}' not found", "WARN")
        return False

    iface = entry.get("interface")
    if iface is not None:
        try:
            iface.detach()
        except Exception:
            pass  # Device is already physically gone; ignore errors
        try:
            RNS.Transport.interfaces.remove(iface)
        except ValueError:
            pass

    entry["enabled"]      = False
    entry["disconnected"] = True
    entry["interface"]    = None
    _save_interface_registry()

    _log(f"Interface disconnected (physically unplugged): {name}")
    _notify_interface_changed(name, "DISCONNECTED")
    return True


def list_interfaces():
    """Return a JSON array describing all active RNS Transport interfaces.

    Each entry includes: name, type, online, rx_bytes, tx_bytes, managed.
    Managed interfaces also include: config, vid, pid, enabled.
    Disabled managed interfaces (not in Transport) are appended at the end.
    """
    if RNS is None:
        return "[]"

    result = []
    managed_names = set(_managed_interfaces.keys())
    seen_managed = set()

    for iface in list(RNS.Transport.interfaces):
        try:
            iname = getattr(iface, "name", str(iface))
            itype = type(iface).__name__
            if itype == "TCPServerInterfaceClient":
                continue
            online = bool(getattr(iface, "online", False))
            rx = int(getattr(iface, "rxb", 0) or 0)
            tx = int(getattr(iface, "txb", 0) or 0)
            entry = {
                "name":    iname,
                "type":    itype,
                "online":  online,
                "rx_bytes": rx,
                "tx_bytes": tx,
                "managed": iname in managed_names,
            }
            if iname in managed_names:
                seen_managed.add(iname)
                m = _managed_interfaces[iname]
                entry["enabled"]      = m.get("enabled", True)
                entry["disconnected"] = m.get("disconnected", False)
                entry["vid"] = m.get("vid")
                entry["pid"] = m.get("pid")
                cfg = dict(m.get("config", {}))
                cfg.pop("name", None)
                cfg.pop("type", None)
                entry["config"] = cfg
            result.append(entry)
        except Exception:
            pass

    # Append disabled/disconnected managed interfaces not in Transport
    for mname, m in _managed_interfaces.items():
        if mname not in seen_managed:
            cfg = dict(m.get("config", {}))
            cfg.pop("name", None)
            cfg.pop("type", None)
            result.append({
                "name":         mname,
                "type":         m.get("config", {}).get("type", "?"),
                "online":       False,
                "rx_bytes":     0,
                "tx_bytes":     0,
                "managed":      True,
                "enabled":      m.get("enabled", False),
                "disconnected": m.get("disconnected", False),
                "vid":          m.get("vid"),
                "pid":          m.get("pid"),
                "config":       cfg,
            })

    return json.dumps(result)


# ── Interface registry persistence ────────────────────────────────────────

def _load_interface_registry(config_dir):
    """Load rtak_interfaces.json and re-add all enabled interfaces."""
    global _interface_registry_path
    _interface_registry_path = os.path.join(config_dir, "rtak_interfaces.json")

    if not os.path.isfile(_interface_registry_path):
        return

    try:
        with open(_interface_registry_path, "r") as f:
            registry = json.load(f)
    except Exception as e:
        _log(f"Failed to read interface registry: {e}", "ERROR")
        return

    loaded = 0
    for entry in registry:
        if not entry.get("enabled", False):
            continue
        config = dict(entry.get("config", {}))
        config["name"] = entry["name"]
        config["type"] = entry["type"]
        vid = entry.get("vid")
        pid = entry.get("pid")
        result = _add_interface_dict(config, vid=vid, pid=pid, save=False)
        if result:
            loaded += 1

    if loaded:
        _log(f"Restored {loaded} managed interface(s) from registry")


def _save_interface_registry():
    """Persist current + previously-disabled interface records to JSON."""
    if _interface_registry_path is None:
        return

    # Read any existing records for disabled interfaces (to preserve their configs)
    existing_disabled = {}
    if os.path.isfile(_interface_registry_path):
        try:
            with open(_interface_registry_path, "r") as f:
                for rec in json.load(f):
                    if not rec.get("enabled", True):
                        existing_disabled[rec["name"]] = rec
        except Exception:
            pass

    registry = list(existing_disabled.values())
    for name, entry in _managed_interfaces.items():
        cfg = dict(entry["config"])
        cfg.pop("name", None)
        cfg.pop("type", None)
        record = {
            "name":    name,
            "type":    entry["config"].get("type", ""),
            "enabled": entry["enabled"],
            "config":  cfg,
        }
        if entry.get("vid") is not None:
            record["vid"] = entry["vid"]
        if entry.get("pid") is not None:
            record["pid"] = entry["pid"]
        registry.append(record)

    try:
        with open(_interface_registry_path, "w") as f:
            json.dump(registry, f, indent=2)
    except Exception as e:
        _log(f"Failed to save interface registry: {e}", "ERROR")


# ══════════════════════════════════════════════════════════════════════════
#  INTERFACE CONFIG — PRE-START OPERATIONS (no RNS required)
# ══════════════════════════════════════════════════════════════════════════

def _registry_path(config_dir):
    """Return the path to rtak_interfaces.json for a given config directory."""
    return os.path.join(config_dir, "rtak_interfaces.json")


def _read_registry(config_dir):
    """Read the interface registry JSON. Returns a list of dicts, or [] on error."""
    path = _registry_path(config_dir)
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r") as f:
            return json.load(f)
    except Exception as e:
        _log(f"Failed to read interface registry: {e}", "ERROR")
        return []


def _write_registry(config_dir, registry):
    """Write the interface registry list back to JSON."""
    path = _registry_path(config_dir)
    os.makedirs(config_dir, exist_ok=True)
    try:
        with open(path, "w") as f:
            json.dump(registry, f, indent=2)
        return True
    except Exception as e:
        _log(f"Failed to write interface registry: {e}", "ERROR")
        return False


# ── Schema migration ────────────────────────────────────────────────────

def _migrate_interface_registry(config_dir):
    """Migrate rtak_interfaces.json from old flat vid/pid schema to new
    identifier-based schema.  Idempotent — entries that already have an
    'identifier' key are left untouched.

    Old format:  { ..., "vid": 4292, "pid": 60000 }
    New format:  { ..., "identifier": { "method": "usb", "vid": 4292, "pid": 60000 } }
    """
    registry = _read_registry(config_dir)
    if not registry:
        return

    changed = False
    for entry in registry:
        if "identifier" in entry:
            continue  # already migrated

        itype = entry.get("type", "")
        vid = entry.pop("vid", None)
        pid = entry.pop("pid", None)
        config = entry.get("config", {})

        if vid and pid:
            entry["identifier"] = {"method": "usb", "vid": vid, "pid": pid}
            # Remove port from config — it's resolved at detection time
            config.pop("port", None)
            changed = True
        elif itype in ("UDPInterface", "TCPClientInterface", "TCPServerInterface"):
            device = config.pop("device", None)
            if device:
                entry["identifier"] = {"method": "network_device", "device": device}
            else:
                entry["identifier"] = {"method": "always"}
            changed = True
        else:
            # Serial/KISS without vid/pid, or unknown type — default to always
            entry["identifier"] = {"method": "always"}
            changed = True

    if changed:
        # Back up old file
        old_path = _registry_path(config_dir)
        bak_path = old_path + ".bak"
        try:
            import shutil
            shutil.copy2(old_path, bak_path)
        except Exception:
            pass
        _write_registry(config_dir, registry)
        _log("Migrated interface registry to new identifier schema")


# ── Pre-start CRUD (no RNS required) ────────────────────────────────────

def read_interface_configs(config_dir):
    """Return interface configs as a JSON string.  No RNS required.
    Java calls this for the detection loop and UI display.
    """
    return json.dumps(_read_registry(config_dir))


def save_interface_config(config_dir, config_json_str):
    """Add or update an interface config in rtak_interfaces.json.

    Pure file I/O — no RNS interaction.

    Args:
        config_dir:       Reticulum config directory path.
        config_json_str:  JSON string with name, type, enabled, config, identifier.
    Returns:
        The interface name on success, None on failure.
    """
    try:
        new_entry = json.loads(config_json_str)
    except Exception as e:
        _log(f"save_interface_config: invalid JSON: {e}", "ERROR")
        return None

    name = new_entry.get("name", "").strip()
    if not name:
        _log("save_interface_config: 'name' is required", "ERROR")
        return None

    registry = _read_registry(config_dir)

    # Update existing entry with the same name, or append
    found = False
    for i, entry in enumerate(registry):
        if entry.get("name") == name:
            registry[i] = new_entry
            found = True
            break

    if not found:
        registry.append(new_entry)

    if _write_registry(config_dir, registry):
        _log(f"Interface config {'updated' if found else 'saved'}: {name}")
        return name
    return None


def remove_interface_config(config_dir, name):
    """Remove an interface config from rtak_interfaces.json by name.

    Pure file I/O — no RNS interaction.
    Returns True on success, False if not found or error.
    """
    registry = _read_registry(config_dir)
    original_len = len(registry)
    registry = [e for e in registry if e.get("name") != name]

    if len(registry) == original_len:
        _log(f"remove_interface_config: '{name}' not found", "WARN")
        return False

    if _write_registry(config_dir, registry):
        _log(f"Interface config removed: {name}")
        return True
    return False


# ── RNS config file generation ──────────────────────────────────────────

def generate_rns_config(config_dir, detected_interfaces_json=None):
    """Generate the RNS config file from rtak_interfaces.json.

    Called by Java BEFORE init() so that RNS.Reticulum() reads the correct
    interfaces.

    Args:
        config_dir:  Reticulum config directory path.
        detected_interfaces_json:  Optional JSON string — a dict mapping
            interface names to their resolved device paths.
            e.g. {"RNode 900MHz": "/dev/bus/usb/001/002", "WiFi UDP": null}
            If provided, only interfaces present in this dict (and enabled)
            are included.  If None, all enabled interfaces are included.
    Returns:
        True on success, False on failure.
    """
    try:
        detected = None
        if detected_interfaces_json:
            detected = json.loads(detected_interfaces_json)

        registry = _read_registry(config_dir)

        # ── Preserve existing [reticulum] and [logging] from current config ──
        config_file = os.path.join(config_dir, "config")
        reticulum_section = """\
[reticulum]
  enable_transport = True
  share_instance   = No
  shared_instance_port = 37428
  instance_control_port = 37429
  panic_on_interface_error = No
"""
        logging_section = """\
[logging]
  loglevel = 4
"""
        if os.path.isfile(config_file):
            try:
                with open(config_file, "r") as f:
                    existing = f.read()
                # Extract [reticulum] section
                ret_match = _extract_section(existing, "reticulum")
                if ret_match:
                    reticulum_section = ret_match
                log_match = _extract_section(existing, "logging")
                if log_match:
                    logging_section = log_match
            except Exception:
                pass  # Use defaults

        # ── Build [interfaces] section ──
        interfaces_lines = ["[interfaces]"]

        for entry in registry:
            if not entry.get("enabled", True):
                continue

            name = entry.get("name", "").strip()
            itype = entry.get("type", "").strip()
            config = dict(entry.get("config", {}))

            if not name or not itype:
                continue

            # If we have a detected map, only include detected interfaces
            if detected is not None and name not in detected:
                continue

            # For USB interfaces, inject the resolved device path
            ident = entry.get("identifier", {})
            if ident.get("method") == "usb" and detected and name in detected:
                resolved_path = detected[name]
                if resolved_path:
                    config["port"] = resolved_path
                elif "port" not in config:
                    # USB device not detected / no path available — skip
                    _log(f"Skipping '{name}': USB device not detected", "WARN")
                    continue

            interfaces_lines.append(f"  [[{name}]]")
            interfaces_lines.append(f"    type = {itype}")
            interfaces_lines.append(f"    enabled = Yes")

            for key, value in config.items():
                # Write each config value
                interfaces_lines.append(f"    {key} = {value}")

            interfaces_lines.append("")  # blank line between interfaces

        # ── Write the config file ──
        config_content = (
            "# RTAK Bridge — Reticulum Config (auto-generated)\n\n"
            + reticulum_section + "\n"
            + logging_section + "\n"
            + "\n".join(interfaces_lines) + "\n"
        )

        os.makedirs(config_dir, exist_ok=True)
        with open(config_file, "w") as f:
            f.write(config_content)

        iface_count = sum(1 for e in registry
                          if e.get("enabled", True)
                          and (detected is None or e.get("name") in detected))
        _log(f"Generated RNS config with {iface_count} interface(s)")
        return True

    except Exception as e:
        _log(f"generate_rns_config failed: {e}\n{traceback.format_exc()}", "ERROR")
        return False


def _extract_section(text, section_name):
    """Extract a [section] block from an RNS config file (TOML-like).
    Returns the section text including the header, or None if not found.
    """
    lines = text.splitlines(True)
    result = []
    in_section = False
    for line in lines:
        stripped = line.strip()
        if stripped == f"[{section_name}]":
            in_section = True
            result.append(line)
        elif in_section:
            # Next top-level section starts
            if stripped.startswith("[") and not stripped.startswith("[["):
                break
            result.append(line)
    return "".join(result) if result else None


# ── Lock state ──────────────────────────────────────────────────────────

def is_interfaces_locked():
    """Return True if interfaces are locked (bridge is running)."""
    return _interfaces_locked


# ── Announce ──────────────────────────────────────────────────────────────
def announce(app_data_str=None):
    if _destination is None:
        return False
    data = app_data_str.encode("utf-8") if app_data_str else _announce_app_data
    _destination.announce(app_data=data)
    _log(f"Announced: {RNS.prettyhexrep(_destination.hash)}")
    return True


# ── Compression ───────────────────────────────────────────────────────────
def _encode_cot(cot_xml):
    """Compress CoT XML. Returns bytes: 1-byte flag + payload."""
    raw = cot_xml.encode("utf-8")
    compressed = zlib.compress(raw, level=9)
    if len(compressed) < len(raw):
        return _COMPRESS_FLAG + compressed
    return _RAW_FLAG + raw


def _decode_cot(data):
    """Decode bytes back to CoT XML string."""
    if len(data) < 2:
        return data.decode("utf-8", errors="replace")
    flag = data[0:1]
    payload = data[1:]
    if flag == _COMPRESS_FLAG:
        return zlib.decompress(payload).decode("utf-8")
    elif flag == _RAW_FLAG:
        return payload.decode("utf-8")
    else:
        return data.decode("utf-8", errors="replace")


# ══════════════════════════════════════════════════════════════════════════
#  FRAGMENTATION — SEND SIDE
# ══════════════════════════════════════════════════════════════════════════

def _next_msg_id(link_hash_hex):
    """Return the next message ID (0–65535) for this peer, wrapping atomically."""
    with _send_counters_lock:
        mid = _send_counters.get(link_hash_hex, 0) & 0xFFFF
        _send_counters[link_hash_hex] = (mid + 1) & 0xFFFF
        return mid


def _fragment_and_send(link, data):
    """
    Split *data* into chunks, prepend a 4-byte header to each, and send
    as individual RNS.Packets over *link*.

    Returns True if all fragments were dispatched.
    """
    lhex = link.hash.hex() if hasattr(link, "hash") else str(id(link))
    msg_id = _next_msg_id(lhex)

    # Use the link's actual MDU, minus our fragment header
    try:
        chunk_size = link.mdu - _HEADER_SIZE
    except Exception:
        chunk_size = _DEFAULT_CHUNK_SIZE

    if chunk_size <= 0:
        _log(f"Invalid chunk_size {chunk_size} (link.mdu={getattr(link, 'mdu', '?')}), using default", "WARN")
        chunk_size = _DEFAULT_CHUNK_SIZE

    total  = (len(data) + chunk_size - 1) // chunk_size  # ceil division

    if total > 255:
        _log(f"Message too large ({len(data)}B, {total} frags), dropping", "ERROR")
        return False

    for i in range(total):
        offset = i * chunk_size
        chunk  = data[offset : offset + chunk_size]
        header = struct.pack(_HEADER_FMT, msg_id, i, total)
        pkt    = RNS.Packet(link, header + chunk)
        pkt.send()

    return True


# ══════════════════════════════════════════════════════════════════════════
#  FRAGMENTATION — RECEIVE SIDE
# ══════════════════════════════════════════════════════════════════════════

def _on_fragment_received(message, packet):
    """
    Called for every incoming packet.  Parses the fragment header,
    buffers the payload, and delivers the complete message once all
    fragments have arrived.
    """
    if len(message) < _HEADER_SIZE:
        _log("Runt packet, ignoring", "WARN")
        return

    msg_id, index, total = struct.unpack(_HEADER_FMT, message[:_HEADER_SIZE])
    payload = message[_HEADER_SIZE:]

    # Identify the sender by link hash
    sender = ""
    if hasattr(packet, "link") and packet.link:
        sender = packet.link.hash.hex() if hasattr(packet.link, "hash") else ""

    # ── Fast path: single-fragment message (most common case) ──
    if total == 1:
        _deliver_complete_message(payload, sender)
        return

    # ── Multi-fragment: buffer until complete ──
    key = (sender, msg_id)
    now = time.time()

    with _reassembly_lock:
        if key not in _reassembly:
            _reassembly[key] = {"frags": {}, "total": total, "ts": now}

        entry = _reassembly[key]
        entry["frags"][index] = payload
        entry["ts"] = now

        if len(entry["frags"]) == total:
            # All fragments received — reassemble
            parts = _reassembly.pop(key)
            assembled = b""
            for idx in range(total):
                assembled += parts["frags"][idx]
            # Deliver outside the lock
            _deliver_complete_message(assembled, sender)
        else:
            _log(f"Fragment {index+1}/{total} (msg {msg_id:#06x}) from {sender[:12]}")


def _deliver_complete_message(data, sender):
    """Decode compressed CoT and forward to Java callback."""
    try:
        cot_xml = _decode_cot(data)
        _log(f"Received CoT ({len(cot_xml)}ch) from {sender[:12] if sender else 'direct'}")
        _notify_cot_received(cot_xml, sender)
    except Exception as e:
        _log(f"Decode error: {e}", "ERROR")


def _reassembly_cleanup_loop():
    """Periodically purge stale incomplete messages."""
    while _running:
        time.sleep(5.0)
        now = time.time()
        with _reassembly_lock:
            stale = [k for k, v in _reassembly.items()
                     if now - v["ts"] > _FRAG_TIMEOUT]
            for k in stale:
                entry = _reassembly.pop(k)
                got = len(entry["frags"])
                _log(f"Dropped incomplete msg {k[1]:#06x} ({got}/{entry['total']} frags)", "WARN")


# ══════════════════════════════════════════════════════════════════════════
#  SEND COT
# ══════════════════════════════════════════════════════════════════════════

def send_cot(cot_xml, dest_hash_hex=None):
    """
    Send a CoT XML message over Reticulum.

    If dest_hash_hex is given, send to that specific peer's link.
    If None, send to all active links.

    Returns True if sent to at least one peer.
    """
    if not _running or RNS is None:
        return False

    try:
        data = _encode_cot(cot_xml)

        if dest_hash_hex:
            # Find a link to this destination
            for lhex, link in list(_links.items()):
                if link.status == RNS.Link.ACTIVE:
                    if _link_matches_dest(link, dest_hash_hex):
                        return _fragment_and_send(link, data)
            _log(f"No active link to {dest_hash_hex[:12]}", "WARN")
            return False
        else:
            # Broadcast to all active links
            sent = 0
            for lhex, link in list(_links.items()):
                try:
                    if link.status == RNS.Link.ACTIVE:
                        if _fragment_and_send(link, data):
                            sent += 1
                except Exception as ex:
                    _log(f"Send on link {lhex[:12]} failed: {ex}", "WARN")

            if sent > 0:
                _log(f"Sent CoT to {sent} link(s) ({len(data)}B compressed)")
            else:
                _log(f"CoT dropped: {len(_links)} link(s), 0 successful ({len(data)}B)", "WARN")
            return sent > 0

    except Exception as e:
        _log(f"send_cot error: {e}", "ERROR")
        return False


def _link_matches_dest(link, dest_hash_hex):
    """Check if a link connects to the given destination hash."""
    try:
        if hasattr(link, "destination") and link.destination:
            return link.destination.hash.hex() == dest_hash_hex
    except:
        pass
    return False


def _has_active_link_to(dest_hash_hex):
    """Return True if we already have a link (active or pending) to this destination."""
    if dest_hash_hex in _inbound_peer_map.values():
        return True
    for link in list(_links.values()):
        if _link_matches_dest(link, dest_hash_hex):
            return True
    for link in list(_pending_links.values()):
        if _link_matches_dest(link, dest_hash_hex):
            return True
    return False


# ══════════════════════════════════════════════════════════════════════════
#  PEER / LINK MANAGEMENT
# ══════════════════════════════════════════════════════════════════════════

def connect_to_peer(dest_hash_hex):
    """Establish an encrypted link to a remote RTAK node."""
    if RNS is None:
        return False
    if dest_hash_hex == _own_hash:
        return False

    try:
        dest_bytes = bytes.fromhex(dest_hash_hex)

        if not RNS.Transport.has_path(dest_bytes):
            _log(f"Requesting path to {dest_hash_hex[:12]}…")
            RNS.Transport.request_path(dest_bytes)
            timeout = time.time() + 15
            while not RNS.Transport.has_path(dest_bytes):
                time.sleep(0.25)
                if time.time() > timeout:
                    _log(f"Path timeout for {dest_hash_hex[:12]}", "WARN")
                    return False

        remote_id = RNS.Identity.recall(dest_bytes)
        if not remote_id:
            _log(f"No identity for {dest_hash_hex[:12]}", "WARN")
            return False

        remote_dest = RNS.Destination(
            remote_id, RNS.Destination.OUT, RNS.Destination.SINGLE,
            APP_NAME, ASPECT_COT, ASPECT_SERVER,
        )

        link = RNS.Link(remote_dest)
        link.keepalive = _LINK_KEEPALIVE
        link.set_link_established_callback(_on_outbound_link_established)
        link.set_link_closed_callback(_on_link_closed)
        link.set_packet_callback(_on_fragment_received)
        _pending_links[id(link)] = link   # prevent GC until link establishes or closes
        _log(f"Establishing link to {dest_hash_hex[:12]}…")
        return True

    except Exception as e:
        _log(f"connect_to_peer error: {e}", "ERROR")
        return False


# ── Getters ───────────────────────────────────────────────────────────────
def get_destination_hash():
    if _destination:
        return RNS.prettyhexrep(_destination.hash)
    return ""

def get_identity_hash():
    if _identity:
        return RNS.prettyhexrep(RNS.Identity.truncated_hash(
            _identity.get_public_key()))
    return ""

def get_connected_peers():
    peers = []
    for lhex, link in list(_links.items()):
        try:
            status = "ACTIVE" if link.status == RNS.Link.ACTIVE else "PENDING"
            peers.append({"hash": lhex, "status": status})
        except:
            pass
    return json.dumps(peers)

def get_status():
    return json.dumps({
        "running": _running,
        "destination": get_destination_hash(),
        "peers": len(_known_peers),
        "links": len(_links),
        "active_links": sum(1 for l in _links.values()
                            if l.status == RNS.Link.ACTIVE) if RNS else 0,
        "transport_enabled": RNS.Reticulum.transport_enabled() if RNS else False,
    })


# ══════════════════════════════════════════════════════════════════════════
#  RNS CALLBACKS
# ══════════════════════════════════════════════════════════════════════════

def _on_link_established(link):
    """Inbound link from a remote peer."""
    lhex = link.hash.hex() if hasattr(link, "hash") else str(id(link))
    _links[lhex] = link
    # Register a callback so that when the link initiator identifies themselves
    # (via link.identify on their end), we can map this inbound link to their
    # destination hash and detect it in _has_active_link_to().
    link.set_remote_identified_callback(_on_remote_identified)
    link.set_packet_callback(_on_fragment_received)
    link.set_link_closed_callback(_on_link_closed)
    link.keepalive = _LINK_KEEPALIVE
    _log(f"Inbound link from {lhex[:12]}")
    _notify_peer_connected(lhex)


def _on_remote_identified(link, identity):
    """Called when an inbound link's initiator reveals their identity."""
    lhex = link.hash.hex() if hasattr(link, "hash") else str(id(link))
    if identity is not None:
        try:
            remote_dest = RNS.Destination(
                identity,
                RNS.Destination.OUT, RNS.Destination.SINGLE,
                APP_NAME, ASPECT_COT, ASPECT_SERVER,
            )
            _inbound_peer_map[lhex] = remote_dest.hash.hex()
            _log(f"Inbound link {lhex[:12]} identified as {remote_dest.hash.hex()[:12]}")
        except Exception:
            pass


def _on_outbound_link_established(link):
    """Our outgoing link is now active."""
    _pending_links.pop(id(link), None)
    lhex = link.hash.hex() if hasattr(link, "hash") else str(id(link))
    _links[lhex] = link
    _log(f"Outbound link established: {lhex[:12]}")
    _notify_peer_connected(lhex)
    # Reveal our identity to the remote end so they can map this as an inbound
    # link to us — used by _has_active_link_to() on their side.
    try:
        link.identify(_identity)
    except Exception:
        pass


def _on_link_closed(link):
    _pending_links.pop(id(link), None)
    lhex = link.hash.hex() if hasattr(link, "hash") else str(id(link))
    _links.pop(lhex, None)
    _inbound_peer_map.pop(lhex, None)
    with _send_counters_lock:
        _send_counters.pop(lhex, None)
    try:
        reason = "timeout" if link.teardown_reason == RNS.Link.TIMEOUT else "closed"
    except:
        reason = "unknown"
    _log(f"Link closed ({reason}): {lhex[:12]}")
    _notify_peer_disconnected(lhex)

    # Auto-reconnect: find which known peer this link belonged to
    reconnect_hash = None
    for dest_hash_hex in list(_known_peers):
        if _link_matches_dest(link, dest_hash_hex):
            reconnect_hash = dest_hash_hex
            break

    if reconnect_hash and _running:
        def _delayed_reconnect(dhash):
            time.sleep(5)
            if not _running:
                return
            if not _has_active_link_to(dhash):
                _log(f"Auto-reconnecting to {dhash[:12]}...")
                connect_to_peer(dhash)

        threading.Thread(
            target=_delayed_reconnect,
            args=(reconnect_hash,),
            daemon=True,
        ).start()


class RTAKAnnounceHandler:
    """Discovers other RTAK nodes and auto-connects on first sight."""

    def __init__(self):
        self.aspect_filter = f"{APP_NAME}.{ASPECT_COT}.{ASPECT_SERVER}"

    def received_announce(self, destination_hash, announced_identity, app_data):
        hash_hex = destination_hash.hex()
        app_str = app_data.decode("utf-8") if app_data else ""

        if hash_hex == _own_hash:
            return

        is_new = hash_hex not in _known_peers
        _log(f"{'New peer' if is_new else 'Re-announce'}: {hash_hex[:12]} ({app_str})")

        _known_peers[hash_hex] = {
            "identity": announced_identity,
            "app_data": app_str,
        }

        if _callbacks:
            try:
                _callbacks.onPeerAnnounced(hash_hex, app_str)
            except:
                pass

        if is_new or not _has_active_link_to(hash_hex):
            threading.Thread(
                target=connect_to_peer,
                args=(hash_hex,),
                daemon=True,
            ).start()


# ── Java Callback Helpers ─────────────────────────────────────────────────
def _notify_cot_received(cot_xml, sender_hash):
    if _callbacks:
        try:
            _callbacks.onCotReceived(cot_xml, sender_hash)
        except Exception as e:
            _log(f"Callback error (onCotReceived): {e}", "ERROR")

def _notify_peer_connected(peer_hash):
    if _callbacks:
        try:
            _callbacks.onPeerConnected(peer_hash)
        except Exception as e:
            _log(f"Callback error (onPeerConnected): {e}", "ERROR")

def _notify_peer_disconnected(peer_hash):
    if _callbacks:
        try:
            _callbacks.onPeerDisconnected(peer_hash)
        except Exception as e:
            _log(f"Callback error (onPeerDisconnected): {e}", "ERROR")

def _notify_status(status):
    if _callbacks:
        try:
            _callbacks.onStatusChanged(status)
        except Exception as e:
            _log(f"Callback error (onStatusChanged): {e}", "ERROR")

def _notify_interface_changed(name, event):
    """event: "ADDED" | "REMOVED" | "UPDATED" | "ENABLED" | "DISABLED" | "ONLINE" | "OFFLINE" """
    if _callbacks:
        try:
            _callbacks.onInterfaceStateChanged(name, event)
        except Exception as e:
            _log(f"Callback error (onInterfaceStateChanged): {e}", "ERROR")
