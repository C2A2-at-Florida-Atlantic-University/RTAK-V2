package com.caai.rtak.ui;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.caai.rtak.R;
import com.caai.rtak.rnode.RNodeIdentifier;
import com.caai.rtak.service.InterfaceDetector;
import com.caai.rtak.service.TakBridgeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays all configured RNS interfaces with their detection status and allows
 * the user to add, edit, or remove interface configurations.
 * <p>
 * When the bridge is running, interfaces are locked and cannot be modified.
 */
public class InterfacesActivity extends AppCompatActivity {

    private static final String TAG = "InterfacesActivity";
    private static final String ACTION_USB_PERMISSION = "com.caai.rtak.USB_PERMISSION_INTERFACES";

    private RecyclerView rvInterfaces;
    private FloatingActionButton fabAdd;
    private TextView tvLockedBanner;

    private InterfaceAdapter adapter;
    private TakBridgeService boundService;
    private boolean isBound = false;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            boundService = ((TakBridgeService.LocalBinder) service).getService();
            isBound = true;
            refreshInterfaces();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_interfaces);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("RNS Interfaces");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvInterfaces = findViewById(R.id.rv_interfaces);
        fabAdd = findViewById(R.id.fab_add_interface);
        tvLockedBanner = findViewById(R.id.tv_locked_banner);

        adapter = new InterfaceAdapter();
        rvInterfaces.setLayoutManager(new LinearLayoutManager(this));
        rvInterfaces.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showAddInterfaceDialog());

        bindService(new Intent(this, TakBridgeService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);

        // Observe interface events from the service — refresh list on any change
        TakBridgeService.interfaceEventLive.observe(this, event -> {
            if (event == null) return;
            refreshInterfaces();
        });

        // Observe locked state
        TakBridgeService.interfacesLockedLive.observe(this, locked -> {
            boolean isLocked = locked != null && locked;
            tvLockedBanner.setVisibility(isLocked ? View.VISIBLE : View.GONE);
            fabAdd.setVisibility(isLocked ? View.GONE : View.VISIBLE);
            // Refresh to update UI state (remove buttons, etc.)
            refreshInterfaces();
        });

        // Observe detection status (pre-start phase)
        TakBridgeService.detectedInterfacesLive.observe(this, detected -> {
            if (detected != null && !isLocked()) {
                adapter.setItems(convertDetectedToItems(detected));
            }
        });
    }

    private boolean isLocked() {
        Boolean locked = TakBridgeService.interfacesLockedLive.getValue();
        return locked != null && locked;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        bgExecutor.shutdown();
    }

    // ── Interface list ───────────────────────────────────────────────────

    private void refreshInterfaces() {
        if (!isBound || boundService == null) return;

        if (isLocked()) {
            // Bridge running: show live interface status from RNS
            bgExecutor.submit(() -> {
                String json = boundService.listInterfacesJson();
                List<InterfaceItem> items = parseInterfaceJson(json);
                runOnUiThread(() -> adapter.setItems(items));
            });
        }
        // When unlocked, detectedInterfacesLive observer handles the UI
    }

    private List<InterfaceItem> convertDetectedToItems(
            List<InterfaceDetector.DetectedInterface> detected) {
        List<InterfaceItem> items = new ArrayList<>();
        for (InterfaceDetector.DetectedInterface di : detected) {
            InterfaceItem item = new InterfaceItem();
            item.name = di.name;
            item.type = di.type;
            item.enabled = di.enabled;
            item.detected = di.detected;
            item.managed = true;
            item.online = false;
            if (di.config != null) {
                item.port            = di.config.optString("port", "");
                item.frequency       = di.config.optLong("frequency", 0);
                item.bandwidth       = di.config.optLong("bandwidth", 0);
                item.txpower         = di.config.optInt("txpower", 0);
                item.spreadingfactor = di.config.optInt("spreadingfactor", 0);
                item.codingrate      = di.config.optInt("codingrate", 0);
                item.listenIp        = di.config.optString("listen_ip",    "");
                item.listenPort      = di.config.optString("listen_port",  "");
                item.forwardIp       = di.config.optString("forward_ip",   "");
                item.forwardPort     = di.config.optString("forward_port", "");
                item.targetHost      = di.config.optString("target_host",  "");
                item.targetPort      = di.config.optString("target_port",  "");
            }
            if (di.identifier != null) {
                item.identifierMethod = di.identifier.optString("method", "always");
                item.vid = di.identifier.optInt("vid", 0);
                item.pid = di.identifier.optInt("pid", 0);
            }
            items.add(item);
        }
        return items;
    }

    private List<InterfaceItem> parseInterfaceJson(String json) {
        List<InterfaceItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                InterfaceItem item = new InterfaceItem();
                item.name     = obj.optString("name", "?");
                item.type     = obj.optString("type", "?");
                item.online   = obj.optBoolean("online", false);
                item.rxBytes  = obj.optLong("rx_bytes", 0);
                item.txBytes  = obj.optLong("tx_bytes", 0);
                item.managed      = obj.optBoolean("managed", true);
                item.enabled      = obj.optBoolean("enabled", true);
                item.disconnected = obj.optBoolean("disconnected", false);
                item.detected     = true; // If running, assume detected
                JSONObject cfg = obj.optJSONObject("config");
                if (cfg != null) {
                    item.port            = cfg.optString("port", "");
                    item.frequency       = cfg.optLong("frequency", 0);
                    item.bandwidth       = cfg.optLong("bandwidth", 0);
                    item.txpower         = cfg.optInt("txpower", 0);
                    item.spreadingfactor = cfg.optInt("spreadingfactor", 0);
                    item.codingrate      = cfg.optInt("codingrate", 0);
                    item.listenIp        = cfg.optString("listen_ip",    "");
                    item.listenPort      = cfg.optString("listen_port",  "");
                    item.forwardIp       = cfg.optString("forward_ip",   "");
                    item.forwardPort     = cfg.optString("forward_port", "");
                    item.targetHost      = cfg.optString("target_host",  "");
                    item.targetPort      = cfg.optString("target_port",  "");
                }
                items.add(item);
            }
        } catch (Exception ignored) {}
        return items;
    }

    // ── Add interface dialog ─────────────────────────────────────────────

    private void showAddInterfaceDialog() {
        if (isLocked()) {
            Toast.makeText(this, "Interfaces locked while RTAK is running",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String[] types = {
                "UDPInterface",
                "TCPClientInterface",
                "TCPServerInterface",
                "RNodeInterface",
                "SerialInterface",
                "KISSInterface",
        };

        Spinner spinnerType = new Spinner(this);
        spinnerType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types));

        EditText etName = new EditText(this);
        etName.setHint("Interface name (e.g. \"My RNode\")");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 16, 48, 0);
        root.addView(etName);
        root.addView(spinnerType);

        new AlertDialog.Builder(this)
                .setTitle("Select Interface Type")
                .setView(root)
                .setPositiveButton("Next", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String type = types[spinnerType.getSelectedItemPosition()];
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showTypeConfigDialog(name, type);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTypeConfigDialog(String name, String type) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);

        List<String> fieldKeys = new ArrayList<>();
        List<EditText> fieldViews = new ArrayList<>();

        switch (type) {
            case "UDPInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",    "0.0.0.0");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port",  "4242");
                addField(form, fieldKeys, fieldViews, "forward_ip",  "Forward IP",   "255.255.255.255");
                addField(form, fieldKeys, fieldViews, "forward_port","Forward Port", "4242");
                addField(form, fieldKeys, fieldViews, "device",      "Bind to network device (optional, e.g. wlan0)", "");
                break;
            case "TCPClientInterface":
                addField(form, fieldKeys, fieldViews, "target_host", "Host / IP",  "");
                addField(form, fieldKeys, fieldViews, "target_port", "Port",       "4242");
                addField(form, fieldKeys, fieldViews, "device",      "Bind to network device (optional)", "");
                break;
            case "TCPServerInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",  "0.0.0.0");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port","4242");
                addField(form, fieldKeys, fieldViews, "device",      "Bind to network device (optional)", "");
                break;
            case "RNodeInterface":
                addRNodePortField(form, fieldKeys, fieldViews, "");
                addField(form, fieldKeys, fieldViews, "frequency",      "Frequency (Hz)",  "915000000", InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "bandwidth",      "Bandwidth (Hz)",  "125000",    InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "txpower",        "TX Power (dBm)",  "7",         InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "spreadingfactor","Spreading Factor","8",         InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "codingrate",     "Coding Rate",     "5",         InputType.TYPE_CLASS_NUMBER);
                break;
            case "SerialInterface":
                addField(form, fieldKeys, fieldViews, "port",  "Serial Port", "/dev/ttyUSB0");
                addField(form, fieldKeys, fieldViews, "speed", "Baud Rate",   "115200");
                break;
            case "KISSInterface":
                addField(form, fieldKeys, fieldViews, "port",  "Serial Port", "");
                addField(form, fieldKeys, fieldViews, "speed", "Baud Rate",   "115200");
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle("Configure " + type)
                .setView(form)
                .setPositiveButton("Add", (d, w) -> {
                    try {
                        // Build the config sub-object
                        JSONObject config = new JSONObject();
                        String device = ""; // for identifier
                        for (int i = 0; i < fieldKeys.size(); i++) {
                            String key = fieldKeys.get(i);
                            String val = fieldViews.get(i).getText().toString().trim();
                            if (!val.isEmpty()) {
                                if ("device".equals(key)) {
                                    device = val; // handled separately in identifier
                                } else {
                                    config.put(key, val);
                                }
                            }
                        }

                        // Build identifier
                        JSONObject identifier = new JSONObject();
                        if (type.equals("RNodeInterface") || type.equals("SerialInterface")
                                || type.equals("KISSInterface")) {
                            // For USB devices — scan current USB bus for VID/PID
                            int[] vidPid = detectUsbVidPid();
                            if (vidPid[0] != 0 && vidPid[1] != 0) {
                                identifier.put("method", "usb");
                                identifier.put("vid", vidPid[0]);
                                identifier.put("pid", vidPid[1]);
                                // Don't store port in config (resolved at detection time)
                                config.remove("port");
                            } else {
                                identifier.put("method", "always");
                            }
                        } else if (!device.isEmpty()) {
                            identifier.put("method", "network_device");
                            identifier.put("device", device);
                        } else {
                            identifier.put("method", "always");
                        }

                        // Build the full registry entry
                        JSONObject entry = new JSONObject();
                        entry.put("name", name);
                        entry.put("type", type);
                        entry.put("enabled", true);
                        entry.put("config", config);
                        entry.put("identifier", identifier);

                        if (boundService != null) {
                            boundService.addInterface(entry.toString());
                            Toast.makeText(this, "Adding " + name + "...",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private UsbSerialProber buildProber() {
        ProbeTable table = UsbSerialProber.getDefaultProbeTable();
        table.addProduct(0x239A, 0x8029, CdcAcmSerialDriver.class); // RAK4630 nRF52840
        return new UsbSerialProber(table);
    }

    /**
     * Detect VID/PID of the first connected USB serial device.
     * Returns {vid, pid} or {0, 0} if none found.
     */
    private int[] detectUsbVidPid() {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = buildProber().findAllDrivers(usbManager);
            if (!drivers.isEmpty()) {
                android.hardware.usb.UsbDevice dev = drivers.get(0).getDevice();
                return new int[]{dev.getVendorId(), dev.getProductId()};
            }
        } catch (Exception ignored) {}
        return new int[]{0, 0};
    }

    private void addField(LinearLayout parent, List<String> keys, List<EditText> views,
                           String key, String label, String defaultValue) {
        addField(parent, keys, views, key, label, defaultValue, InputType.TYPE_CLASS_TEXT);
    }

    private void addField(LinearLayout parent, List<String> keys, List<EditText> views,
                           String key, String label, String defaultValue, int inputType) {
        int dpTopMargin = (int) (8 * getResources().getDisplayMetrics().density);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dpTopMargin;
        tv.setLayoutParams(labelLp);
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setHint(label);
        et.setText(defaultValue);
        et.setInputType(inputType);
        parent.addView(et);
        keys.add(key);
        views.add(et);
    }

    /**
     * Adds the "Port / BLE name" row for RNodeInterface, including a "Scan USB" button
     * that enumerates connected USB serial devices and auto-fills the port path.
     */
    private void addRNodePortField(LinearLayout parent, List<String> keys, List<EditText> views,
                                    String defaultPort) {
        int dpTopMargin = (int) (8 * getResources().getDisplayMetrics().density);

        TextView tv = new TextView(this);
        tv.setText("Port / BLE name");
        tv.setTextSize(12f);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dpTopMargin;
        tv.setLayoutParams(labelLp);
        parent.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        EditText et = new EditText(this);
        et.setHint("/dev/bus/usb/... or BLE name");
        et.setText(defaultPort);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        row.addView(et);

        Button scanBtn = new Button(this);
        scanBtn.setText("Scan USB");
        scanBtn.setOnClickListener(v -> scanForRNodes(et, scanBtn));
        row.addView(scanBtn);

        parent.addView(row);
        keys.add("port");
        views.add(et);
    }

    private void scanForRNodes(EditText portField, Button scanBtn) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = buildProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            Toast.makeText(this, "No USB serial devices connected", Toast.LENGTH_SHORT).show();
            return;
        }

        // Request permission for any device that doesn't have it yet
        boolean permissionRequested = false;
        for (UsbSerialDriver driver : drivers) {
            if (!usbManager.hasPermission(driver.getDevice())) {
                PendingIntent pi = PendingIntent.getBroadcast(
                        this, 0,
                        new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                        PendingIntent.FLAG_MUTABLE);
                usbManager.requestPermission(driver.getDevice(), pi);
                permissionRequested = true;
            }
        }

        if (permissionRequested) {
            Toast.makeText(this,
                    "USB permission needed — grant it in the dialog, then tap Scan again",
                    Toast.LENGTH_LONG).show();
            return;
        }

        scanBtn.setEnabled(false);
        Toast.makeText(this, "Scanning for RNodes...", Toast.LENGTH_SHORT).show();

        bgExecutor.submit(() -> {
            List<RNodeIdentifier.RNodeInfo> found =
                    new RNodeIdentifier().discoverRNodes(InterfacesActivity.this);
            runOnUiThread(() -> {
                scanBtn.setEnabled(true);
                if (found.isEmpty()) {
                    Toast.makeText(this, "No RNodes found", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (found.size() == 1) {
                    portField.setText(found.get(0).devicePath);
                    Toast.makeText(this,
                            "RNode confirmed: " + found.get(0).devicePath
                                    + "  [" + found.get(0).mcuName() + "]",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    labels[i] = found.get(i).toString();
                }
                new AlertDialog.Builder(this)
                        .setTitle("Select RNode")
                        .setItems(labels, (dialog, which) ->
                                portField.setText(found.get(which).devicePath))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    // ── Edit Interface Dialog ───────────────────────────────────────────

    private void showEditInterfaceDialog(InterfaceItem item) {
        if (isLocked()) {
            Toast.makeText(this, "Interfaces locked while RTAK is running",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);

        // Enable/Disable toggle
        androidx.appcompat.widget.SwitchCompat switchEnabled =
                new androidx.appcompat.widget.SwitchCompat(this);
        switchEnabled.setText("Enabled");
        switchEnabled.setChecked(item.enabled);
        form.addView(switchEnabled);

        // Type-specific editable fields
        List<String> fieldKeys = new ArrayList<>();
        List<EditText> fieldViews = new ArrayList<>();

        switch (item.type) {
            case "RNodeInterface":
                addField(form, fieldKeys, fieldViews, "frequency",
                        "Frequency (Hz)", String.valueOf(item.frequency), InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "bandwidth",
                        "Bandwidth (Hz)", String.valueOf(item.bandwidth), InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "txpower",
                        "TX Power (dBm)", String.valueOf(item.txpower), InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "spreadingfactor",
                        "Spreading Factor", String.valueOf(item.spreadingfactor), InputType.TYPE_CLASS_NUMBER);
                addField(form, fieldKeys, fieldViews, "codingrate",
                        "Coding Rate", String.valueOf(item.codingrate), InputType.TYPE_CLASS_NUMBER);
                break;
            case "UDPInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",    "Listen IP",    item.listenIp);
                addField(form, fieldKeys, fieldViews, "listen_port",  "Listen Port",  item.listenPort);
                addField(form, fieldKeys, fieldViews, "forward_ip",   "Forward IP",   item.forwardIp);
                addField(form, fieldKeys, fieldViews, "forward_port", "Forward Port", item.forwardPort);
                break;
            case "TCPClientInterface":
                addField(form, fieldKeys, fieldViews, "target_host", "Host / IP", item.targetHost);
                addField(form, fieldKeys, fieldViews, "target_port", "Port",      item.targetPort);
                break;
            case "TCPServerInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",   item.listenIp);
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port", item.listenPort);
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle("Edit: " + item.name)
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    boolean wantEnabled = switchEnabled.isChecked();

                    if (wantEnabled != item.enabled && boundService != null) {
                        boundService.setInterfaceEnabled(item.name, wantEnabled);
                    }

                    if (wantEnabled && boundService != null) {
                        try {
                            JSONObject config = new JSONObject();
                            for (int i = 0; i < fieldKeys.size(); i++) {
                                String val = fieldViews.get(i).getText().toString().trim();
                                if (!val.isEmpty()) {
                                    config.put(fieldKeys.get(i), val);
                                }
                            }

                            // Build the full entry for save_interface_config
                            JSONObject entry = new JSONObject();
                            entry.put("name", item.name);
                            entry.put("type", item.type);
                            entry.put("enabled", wantEnabled);
                            entry.put("config", config);

                            // Preserve existing identifier
                            JSONObject identifier = new JSONObject();
                            if (item.identifierMethod != null) {
                                identifier.put("method", item.identifierMethod);
                                if ("usb".equals(item.identifierMethod)) {
                                    identifier.put("vid", item.vid);
                                    identifier.put("pid", item.pid);
                                }
                            } else {
                                identifier.put("method", "always");
                            }
                            entry.put("identifier", identifier);

                            boundService.updateInterface(entry.toString());
                        } catch (Exception e) {
                            Toast.makeText(this, "Error: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("Remove", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Remove Interface")
                            .setMessage("Remove \"" + item.name + "\"?")
                            .setPositiveButton("Remove", (d2, w2) -> {
                                if (boundService != null) {
                                    boundService.removeInterface(item.name);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    static class InterfaceItem {
        String name, type;
        boolean online, managed, enabled, disconnected;
        boolean detected;
        long rxBytes, txBytes;
        int vid, pid;
        String identifierMethod;
        // Config fields
        String port;
        long frequency, bandwidth;
        int txpower, spreadingfactor, codingrate;
        // UDP / TCP config fields
        String listenIp, listenPort, forwardIp, forwardPort;
        String targetHost, targetPort;
    }

    private class InterfaceAdapter extends RecyclerView.Adapter<InterfaceAdapter.VH> {

        private final List<InterfaceItem> items = new ArrayList<>();

        void setItems(List<InterfaceItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_interface, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InterfaceItem item = items.get(pos);
            h.tvName.setText(item.name);
            h.tvType.setText(item.type);
            h.tvTraffic.setText(String.format("\u2193%s \u2191%s",
                    formatBytes(item.rxBytes), formatBytes(item.txBytes)));

            boolean locked = isLocked();

            if (locked) {
                // When locked (running): show online/offline status
                h.itemView.setAlpha(item.disconnected ? 0.5f : 1.0f);
                h.statusDot.setBackgroundColor(
                        item.disconnected ? Color.RED     :
                        item.online       ? Color.GREEN   : Color.GRAY);
            } else {
                // When unlocked (pre-start): show detection status
                h.itemView.setAlpha(!item.enabled ? 0.5f : 1.0f);
                h.statusDot.setBackgroundColor(
                        !item.enabled     ? Color.DKGRAY  :
                        item.detected     ? Color.GREEN   :
                                            Color.parseColor("#FFC107")); // amber
            }

            // Remove button: only visible when unlocked
            h.btnRemove.setVisibility(locked ? View.GONE : View.VISIBLE);
            h.btnRemove.setOnClickListener(v -> {
                if (locked) {
                    Toast.makeText(InterfacesActivity.this,
                            "Interfaces locked while RTAK is running",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(InterfacesActivity.this)
                        .setTitle("Remove Interface")
                        .setMessage("Remove \"" + item.name + "\"?")
                        .setPositiveButton("Remove", (d, w) -> {
                            if (boundService != null) {
                                boundService.removeInterface(item.name);
                                Toast.makeText(InterfacesActivity.this,
                                        "Removing " + item.name + "...",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            // Tap to edit (only when unlocked)
            if (locked) {
                h.itemView.setOnClickListener(v ->
                        Toast.makeText(InterfacesActivity.this,
                                "Interfaces locked while RTAK is running",
                                Toast.LENGTH_SHORT).show());
            } else {
                h.itemView.setOnClickListener(v -> showEditInterfaceDialog(item));
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            View statusDot;
            TextView tvName, tvType, tvTraffic;
            ImageButton btnRemove;

            VH(View v) {
                super(v);
                statusDot  = v.findViewById(R.id.view_status_dot);
                tvName     = v.findViewById(R.id.tv_iface_name);
                tvType     = v.findViewById(R.id.tv_iface_type);
                tvTraffic  = v.findViewById(R.id.tv_iface_traffic);
                btnRemove  = v.findViewById(R.id.btn_remove);
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }
}
