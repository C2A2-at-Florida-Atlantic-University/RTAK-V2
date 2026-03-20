package com.caai.rtak.rnode;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Probes connected USB serial devices using the RNode KISS protocol to confirm
 * which ones are genuine RNodes before handing their port paths to RNS.
 *
 * Must be called from a background thread — all operations are blocking I/O.
 */
public class RNodeIdentifier {

    private static final String TAG = "RNodeIdentifier";

    // KISS framing bytes
    private static final byte FEND  = (byte) 0xC0;
    private static final byte FESC  = (byte) 0xDB;
    private static final byte TFEND = (byte) 0xDC;
    private static final byte TFESC = (byte) 0xDD;

    // RNode commands
    private static final byte CMD_DETECT   = (byte) 0x08;
    private static final byte CMD_MCU      = (byte) 0x49;
    private static final byte CMD_DEV_HASH = (byte) 0x56;
    private static final byte CMD_ROM_READ = (byte) 0x51;
    private static final byte[] VALID_CMD = { CMD_DETECT, CMD_MCU, CMD_DEV_HASH, CMD_ROM_READ };


    // CMD_DETECT handshake bytes
    private static final byte DETECT_REQ  = (byte) 0x73;
    private static final byte DETECT_RESP = (byte) 0x46;

    // Known MCU variants
    public static final byte MCU_ESP32 = (byte) 0x81;
    public static final byte MCU_NRF52 = (byte) 0x71;

    // ROM offset of the AVR serial bytes
    private static final int ADDR_SERIAL = 0x03;
    private static final int SERIAL_LEN  = 4;

    // How long to discard incoming bytes after opening the port.
    // ESP32-based LilyGO boards (T3S3, T-Beam Supreme) emit boot log garbage
    // over UART before RNode firmware starts responding to commands.
    private static final int DRAIN_MS = 800;

    // ── Result type ───────────────────────────────────────────────────────

    public static class RNodeInfo {
        /** Android USB device path, e.g. /dev/bus/usb/001/002 */
        public final String devicePath;
        /** Hex string — device hash (ESP32/NRF52) or ROM serial (AVR) */
        public final String deviceId;
        /** Raw MCU variant byte reported by the firmware */
        public final byte mcuVariant;

        RNodeInfo(String devicePath, String deviceId, byte mcuVariant) {
            this.devicePath = devicePath;
            this.deviceId   = deviceId;
            this.mcuVariant = mcuVariant;
        }

        public String mcuName() {
            if (mcuVariant == MCU_ESP32) return "ESP32";
            if (mcuVariant == MCU_NRF52) return "NRF52";
            return String.format("MCU_%02X", mcuVariant & 0xFF);
        }

        @Override
        public String toString() {
            return devicePath + "  [" + mcuName() + "]  " + deviceId;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Iterates every connected USB serial device, sends CMD_DETECT, and returns
     * only those that respond with DETECT_RESP — confirming genuine RNode firmware.
     * Also signals CABLE_STATE_CONNECTED to the RNode as a side effect.
     *
     * @param context Application or activity context.
     * @return List of confirmed RNodes (may be empty; never null).
     */
    public List<RNodeInfo> discoverRNodes(Context context) {
        List<RNodeInfo> result = new ArrayList<>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        ProbeTable probeTable = UsbSerialProber.getDefaultProbeTable();
        probeTable.addProduct(0x239A, 0x8029, CdcAcmSerialDriver.class); // RAK4630 nRF52840
        List<UsbSerialDriver> drivers = new UsbSerialProber(probeTable).findAllDrivers(manager);

        if (drivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices connected.");
            return result;
        }

        for (UsbSerialDriver driver : drivers) {
            for (UsbSerialPort port : driver.getPorts()) {
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection == null) {
                    Log.w(TAG, "Cannot open — no permission for "
                            + driver.getDevice().getDeviceName());
                    continue;
                }

                try {
                    port.open(connection);
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    port.setDTR(true);

                    // Step 1: confirm RNode firmware via CMD_DETECT handshake
                    writeKissFrame(port, CMD_DETECT, DETECT_REQ);
                    KissFrame detectFrame = readKissFrame(port, 1500);
                    if (detectFrame == null) { // Call again if CMD_DETECT is drowned out by ESP32 boot spam.
                        writeKissFrame(port, CMD_DETECT, DETECT_REQ);
                        detectFrame = readKissFrame(port, 1500);
                    }

                    if (detectFrame == null
                            || detectFrame.command != CMD_DETECT
                            || detectFrame.data.length == 0
                            || detectFrame.data[0] != DETECT_RESP) {
                        Log.d(TAG, driver.getDevice().getDeviceName()
                                + " port " + port.getPortNumber() + " — not an RNode");
                        continue;
                    }

                    // Step 2: query MCU variant (needed to pick the right device-ID command)
                    writeKissFrame(port, CMD_MCU);
                    KissFrame mcuFrame = readKissFrame(port, 1500);
                    byte mcu = (mcuFrame != null
                            && mcuFrame.command == CMD_MCU
                            && mcuFrame.data.length > 0)
                            ? mcuFrame.data[0] : (byte) 0x00;

                    // Step 3: fetch device ID
                    String deviceId = fetchDeviceId(port, mcu);
                    String path = driver.getDevice().getDeviceName();

                    result.add(new RNodeInfo(path, deviceId, mcu));
                    Log.i(TAG, "RNode confirmed: " + path
                            + "  mcu=0x" + String.format("%02X", mcu & 0xFF)
                            + "  id=" + deviceId);

                } catch (IOException e) {
                    Log.e(TAG, "I/O error probing " + driver.getDevice().getDeviceName(), e);
                } finally {
                    try { port.close(); } catch (IOException ignored) {}
                }
            }
        }

        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String fetchDeviceId(UsbSerialPort port, byte mcu) throws IOException {
        if (mcu == MCU_ESP32 || mcu == MCU_NRF52) {
            // ESP32 / NRF52: request the 32-byte device hash
            writeKissFrame(port, CMD_DEV_HASH, (byte) 0x01);
            KissFrame frame = readKissFrame(port, 2000);
            if (frame != null && frame.command == CMD_DEV_HASH) {
                return bytesToHex(frame.data);
            }
        } else {
            // Older AVR platforms: read 4-byte ROM serial
            writeKissFrame(port, CMD_ROM_READ);
            KissFrame frame = readKissFrame(port, 2000);
            if (frame != null && frame.command == CMD_ROM_READ
                    && frame.data.length > ADDR_SERIAL + SERIAL_LEN) {
                return bytesToHex(Arrays.copyOfRange(
                        frame.data, ADDR_SERIAL, ADDR_SERIAL + SERIAL_LEN));
            }
        }
        return "unknown";
    }

    /**
     * Reads and discards all incoming bytes for DRAIN_MS milliseconds.
     * Silences ESP32 boot log output so the subsequent KISS exchange is clean.
     */
    private void drainPort(UsbSerialPort port) {
        byte[] buf = new byte[256];
        long deadline = System.currentTimeMillis() + DRAIN_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                port.read(buf, 50);
            } catch (IOException ignored) {
                break;
            }
        }
    }

    /** Builds and writes an escaped KISS frame. */
    private void writeKissFrame(UsbSerialPort port, byte command, byte... payload)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(FEND);
        bos.write(command);
        for (byte b : payload) {
            if      (b == FEND) { bos.write(FESC); bos.write(TFEND); }
            else if (b == FESC) { bos.write(FESC); bos.write(TFESC); }
            else                { bos.write(b); }
        }
        bos.write(FEND);
        port.write(bos.toByteArray(), 500);
    }

    /** Reads and un-escapes one KISS frame within the given timeout, or returns null. */
    private KissFrame readKissFrame(UsbSerialPort port, int timeoutMs) throws IOException {
        boolean inFrame = false, escape = false;
        byte command = (byte) 0xFE;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] buf = new byte[256];

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int len = port.read(buf, 50);
            for (int i = 0; i < len; i++) {
                byte b = buf[i];
                if (inFrame && b == FEND) {
                    if (Arrays.binarySearch(VALID_CMD, command) >= 0)
                        return new KissFrame(command, payload.toByteArray());
                } else if (b == FEND) {
                    inFrame = true;
                    command = (byte) 0xFE;
                    payload.reset();
                } else if (inFrame) {
                    if (payload.size() == 0 && command == (byte) 0xFE) {
                        command = b; // first byte after FEND is the command
                    } else {
                        if (b == FESC) {
                            escape = true;
                        } else {
                            if (escape) {
                                if (b == TFEND) b = FEND;
                                if (b == TFESC) b = FESC;
                                escape = false;
                            }
                            payload.write(b);
                        }
                    }
                }
            }
        }
        return null; // timeout
    }

    /** API-24-safe hex encoding (HexFormat requires API 33). */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    // ── Internal frame holder ─────────────────────────────────────────────

    private static class KissFrame {
        final byte   command;
        final byte[] data;
        KissFrame(byte command, byte[] data) {
            this.command = command;
            this.data    = data;
        }
    }
}
