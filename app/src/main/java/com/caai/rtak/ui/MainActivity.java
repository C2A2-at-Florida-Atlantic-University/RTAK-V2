package com.caai.rtak.ui;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caai.rtak.R;
import com.caai.rtak.model.BridgeStatus;
import com.caai.rtak.service.InterfaceDetector;
import com.caai.rtak.service.TakBridgeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main dashboard for the RTAK Bridge application.
 * <p>
 * Displays bridge status, connected peers, message statistics, and a
 * scrollable log. Provides controls for starting/stopping the service,
 * sending announces, and connecting to remote peers.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ── Views ──────────────────────────────────────────────────────────
    private TextView tvBridgeState, tvDestHash, tvTakStatus, tvTakConnectAddr;
    private TextView tvTakClients, tvRnsPeers;
    private TextView tvCotFromTak, tvCotFromRns, tvCotToTak, tvCotToRns;
    private TextView tvLog;
    private ScrollView scrollLog;
    private Button btnStartStop, btnAnnounce, btnConnect;

    private String lastBridgeState = "";

    // ── Interface chip row ─────────────────────────────────────────────
    private RecyclerView rvInterfaceChips;
    private TextView tvManageInterfaces;
    private InterfaceChipAdapter chipAdapter;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private TakBridgeService boundService;
    private boolean isBound = false;

    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat logTimeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ── Permission launcher ────────────────────────────────────────────
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* Proceed regardless */ }
            );

    // ── Service connection ─────────────────────────────────────────────
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TakBridgeService.LocalBinder binder =
                    (TakBridgeService.LocalBinder) service;
            boundService = binder.getService();
            isBound = true;
            updateStartStopButton();
            refreshInterfaceChips();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            isBound = false;
            btnStartStop.setText("Start Bridge");
            chipAdapter.setItems(new ArrayList<>());
        }
    };

    // ── Activity Lifecycle ──────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        requestPermissions();
        observeState();

        // Start the foreground service immediately (detection mode).
        // The bridge itself only starts when the user clicks "Start Bridge".
        Intent intent = new Intent(this, TakBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    // ── View init ──────────────────────────────────────────────────────

    private void initViews() {
        tvBridgeState    = findViewById(R.id.tv_bridge_state);
        tvDestHash       = findViewById(R.id.tv_dest_hash);
        tvTakStatus      = findViewById(R.id.tv_tak_status);
        tvTakConnectAddr = findViewById(R.id.tv_tak_connect_addr);
        tvTakClients     = findViewById(R.id.tv_tak_clients);
        tvRnsPeers       = findViewById(R.id.tv_rns_peers);
        tvCotFromTak     = findViewById(R.id.tv_cot_from_tak);
        tvCotFromRns     = findViewById(R.id.tv_cot_from_rns);
        tvCotToTak       = findViewById(R.id.tv_cot_to_tak);
        tvCotToRns       = findViewById(R.id.tv_cot_to_rns);
        tvLog            = findViewById(R.id.tv_log);
        scrollLog        = findViewById(R.id.scroll_log);
        btnStartStop     = findViewById(R.id.btn_start_stop);
        btnAnnounce      = findViewById(R.id.btn_announce);
        btnConnect       = findViewById(R.id.btn_connect);

        tvLog.setMovementMethod(new ScrollingMovementMethod());

        btnStartStop.setOnClickListener(v -> toggleService());
        btnAnnounce.setOnClickListener(v -> sendAnnounce());
        btnConnect.setOnClickListener(v -> showConnectDialog());

        // ── Interface chip row ─────────────────────────────────────────
        tvManageInterfaces = findViewById(R.id.tv_manage_interfaces);
        tvManageInterfaces.setOnClickListener(v ->
                startActivity(new Intent(this, InterfacesActivity.class)));

        rvInterfaceChips = findViewById(R.id.rv_interface_chips);
        chipAdapter = new InterfaceChipAdapter();
        rvInterfaceChips.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvInterfaceChips.setAdapter(chipAdapter);
    }

    // ── Permissions ────────────────────────────────────────────────────

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    // ── Observe LiveData ───────────────────────────────────────────────

    private void observeState() {
        TakBridgeService.statusLive.observe(this, this::updateUi);
        TakBridgeService.logLive.observe(this, this::appendLog);
        TakBridgeService.interfaceEventLive.observe(this, event -> {
            if (event != null) refreshInterfaceChips();
        });
        TakBridgeService.interfacesLockedLive.observe(this, locked -> {
            updateStartStopButton();
            refreshInterfaceChips();
        });
        // Also refresh chips when detection results change
        TakBridgeService.detectedInterfacesLive.observe(this, detected -> {
            if (detected != null) {
                Boolean locked = TakBridgeService.interfacesLockedLive.getValue();
                if (locked == null || !locked) {
                    // Pre-start phase: show detection status in chips
                    List<InterfaceChipItem> chips = new ArrayList<>();
                    for (InterfaceDetector.DetectedInterface di : detected) {
                        InterfaceChipItem chip = new InterfaceChipItem();
                        chip.name = di.name;
                        chip.type = stripInterfaceSuffix(di.type);
                        chip.online = false;
                        chip.detected = di.detected;
                        chip.enabled = di.enabled;
                        chips.add(chip);
                    }
                    chipAdapter.setItems(chips);
                }
            }
        });
    }

    private void updateUi(BridgeStatus s) {
        tvBridgeState.setText(s.bridgeState);

        int stateColor;
        switch (s.bridgeState) {
            case "RUNNING": stateColor = 0xFFFB8016; break;  // orange
            case "ERROR":   stateColor = 0xFFF44336; break;  // red
            default:        stateColor = 0xFFFF9800; break;  // orange
        }
        tvBridgeState.setTextColor(stateColor);

        tvDestHash.setText(s.destinationHash.isEmpty() ? "\u2014" : s.destinationHash);

        tvTakStatus.setText(s.takServerRunning
                ? "Listening on port " + s.takServerPort
                : "Stopped");

        if (s.takServerRunning) {
            tvTakConnectAddr.setText("localhost:" + s.takServerPort);
        } else {
            tvTakConnectAddr.setText("\u2014");
        }

        tvTakClients.setText(s.takClients > 0 ? "YES" : "NO");
        tvRnsPeers.setText(String.valueOf(s.rnsPeers));

        tvCotFromTak.setText(String.valueOf(s.cotFromTak));
        tvCotFromRns.setText(String.valueOf(s.cotFromRns));
        tvCotToTak.setText(String.valueOf(s.cotToTak));
        tvCotToRns.setText(String.valueOf(s.cotToRns));

        // Refresh interface chips once RNS finishes initialising
        if ("RUNNING".equals(s.bridgeState) && !lastBridgeState.equals("RUNNING")) {
            refreshInterfaceChips();
        }
        lastBridgeState = s.bridgeState;

        updateStartStopButton();
    }

    private void updateStartStopButton() {
        Boolean locked = TakBridgeService.interfacesLockedLive.getValue();
        boolean isLocked = locked != null && locked;

        if (isLocked) {
            if ("RUNNING".equals(lastBridgeState) || "STARTING".equals(lastBridgeState)) {
                btnStartStop.setText("Stop Bridge");
                btnStartStop.setEnabled(true);
            } else {
                // Locked but stopped — interfaces remain locked but bridge can restart
                btnStartStop.setText("Start Bridge");
                btnStartStop.setEnabled(true);
            }
        } else {
            btnStartStop.setText("Start Bridge");
            btnStartStop.setEnabled(true);
        }
    }

    private void appendLog(String msg) {
        if (msg == null) return;
        String time = logTimeFmt.format(new Date());
        logBuffer.append(time).append("  ").append(msg).append("\n");

        // Trim log buffer to last 200 lines
        String full = logBuffer.toString();
        String[] lines = full.split("\n");
        if (lines.length > 200) {
            logBuffer.setLength(0);
            for (int i = lines.length - 200; i < lines.length; i++) {
                logBuffer.append(lines[i]).append("\n");
            }
        }

        tvLog.setText(logBuffer.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    // ── Interface chips ────────────────────────────────────────────────

    private void refreshInterfaceChips() {
        if (!isBound || boundService == null) return;
        Boolean locked = TakBridgeService.interfacesLockedLive.getValue();
        if (locked != null && locked) {
            // Bridge running: show live status
            bgExecutor.submit(() -> {
                String json = boundService.listInterfacesJson();
                List<InterfaceChipItem> items = parseChipItems(json);
                runOnUiThread(() -> chipAdapter.setItems(items));
            });
        }
        // When unlocked, detectedInterfacesLive observer handles chips
    }

    private List<InterfaceChipItem> parseChipItems(String json) {
        List<InterfaceChipItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                InterfaceChipItem item = new InterfaceChipItem();
                item.name   = obj.optString("name", "?");
                item.type   = stripInterfaceSuffix(obj.optString("type", "?"));
                item.online = obj.optBoolean("online", false);
                item.detected = true;
                item.enabled = obj.optBoolean("enabled", true);
                items.add(item);
            }
        } catch (Exception ignored) {}
        return items;
    }

    /** Strips the "Interface" suffix for compact display: "UDPInterface" -> "UDP" */
    private static String stripInterfaceSuffix(String type) {
        if (type.endsWith("Interface")) {
            return type.substring(0, type.length() - "Interface".length());
        }
        return type;
    }

    // ── Service Control ────────────────────────────────────────────────

    private void toggleService() {
        if (!isBound || boundService == null) return;

        Boolean locked = TakBridgeService.interfacesLockedLive.getValue();
        boolean isLocked = locked != null && locked;

        if (isLocked && ("RUNNING".equals(lastBridgeState) || "STARTING".equals(lastBridgeState))) {
            boundService.stopBridgeAsync();
        } else {
            // Bridge not yet started — start it
            Intent startIntent = new Intent(this, TakBridgeService.class);
            startIntent.setAction(TakBridgeService.ACTION_START_BRIDGE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent);
            } else {
                startService(startIntent);
            }
            btnStartStop.setText("Starting...");
            btnStartStop.setEnabled(false);
        }
    }

    private void sendAnnounce() {
        if (boundService != null) {
            boundService.sendAnnounce();
        } else {
            appendLog("Bridge not running \u2014 start it first.");
        }
    }

    /** Stops the service and kills the process entirely. */
    private void killApp() {
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        stopService(new Intent(this, TakBridgeService.class));
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Schedules a relaunch via AlarmManager (500 ms delay), then kills the process. */
    private void restartApp() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager mgr = (AlarmManager) getSystemService(ALARM_SERVICE);
            mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
        }
        killApp();
    }

    private void showConnectDialog() {
        if (boundService == null) {
            appendLog("Bridge not running \u2014 start it first.");
            return;
        }

        final EditText input = new EditText(this);
        input.setHint("Destination hash (hex)");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Connect to RTAK Peer")
                .setMessage("Enter the destination hash of a remote RTAK node:")
                .setView(input)
                .setPositiveButton("Connect", (d, w) -> {
                    String hash = input.getText().toString().trim();
                    if (!hash.isEmpty()) {
                        boundService.connectPeer(hash);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Options Menu ───────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_licenses) {
            startActivity(new Intent(this, LicensesActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_clear_log) {
            logBuffer.setLength(0);
            tvLog.setText("");
            return true;
//        } else if (item.getItemId() == R.id.action_restart_app) {
//            restartApp();
//            return true;
        } else if (item.getItemId() == R.id.action_close_app) {
            killApp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Interface chip adapter ─────────────────────────────────────────

    private static class InterfaceChipItem {
        String name, type;
        boolean online;
        boolean detected;
        boolean enabled;
    }

    private static class InterfaceChipAdapter
            extends RecyclerView.Adapter<InterfaceChipAdapter.VH> {

        private final List<InterfaceChipItem> items = new ArrayList<>();

        void setItems(List<InterfaceChipItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_interface_chip, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InterfaceChipItem item = items.get(pos);
            h.tvName.setText(item.name);
            h.tvType.setText(item.type);

            // Color: green = online/detected+enabled, amber = enabled+not detected, gray = disabled
            int dotColor;
            if (item.online) {
                dotColor = Color.parseColor("#4CAF50"); // green (running)
            } else if (!item.enabled) {
                dotColor = Color.GRAY; // disabled
            } else if (item.detected) {
                dotColor = Color.parseColor("#4CAF50"); // green (detected, ready)
            } else {
                dotColor = Color.parseColor("#FFC107"); // amber (not detected)
            }
            h.dot.setBackgroundColor(dotColor);

            h.itemView.setOnClickListener(v ->
                    v.getContext().startActivity(
                            new Intent(v.getContext(), InterfacesActivity.class)));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            View dot;
            TextView tvName, tvType;

            VH(View v) {
                super(v);
                dot    = v.findViewById(R.id.view_chip_dot);
                tvName = v.findViewById(R.id.tv_chip_name);
                tvType = v.findViewById(R.id.tv_chip_type);
            }
        }
    }
}
