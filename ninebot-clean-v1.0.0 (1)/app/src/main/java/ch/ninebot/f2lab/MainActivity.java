package ch.ninebot.f2lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ninebot F2 Lab v1.0.0
 *
 * Narrow-purpose owner tool: connect to a Segway/Ninebot F2-family scooter,
 * authenticate using the physical power button, read the stock max-speed
 * register, and test values from 22 through 25 km/h. It does not flash
 * firmware, alter motor current/braking, modify serial/region, or expose a
 * raw command console.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 9;
    private static final int MAX_TEST_KMH = 25;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final UUID SVC_NINEBOT = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    private static final UUID WR_NINEBOT  = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    private static final UUID NT_NINEBOT  = UUID.fromString("6e400004-0000-0000-006e-696e65626f74");

    private static final UUID SVC_NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID WR_NUS  = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NT_NUS  = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    private enum Mode { UNKNOWN, ENC2, PLAIN }
    private enum AuthState { IDLE, PRECOMM, SETPWD, AUTH, READY }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, FoundDevice> found = new LinkedHashMap<>();
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    private final NinebotCrypto crypto = new NinebotCrypto();
    private final SecureRandom secureRandom = new SecureRandom();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private BluetoothDevice device;
    private String bleName = "";
    private Mode mode = Mode.UNKNOWN;
    private AuthState authState = AuthState.IDLE;
    private int bleTarget = NinebotProtocol.BLE_LEGACY;
    private int attemptSerial = 0;
    private byte[] authParam;
    private byte[] scooterSerial;
    private byte[] sessionPassword;
    private boolean triedStoredPassword;
    private int currentRaw = -1;
    private int speedScale = 0;
    private boolean controllerVerified = false;
    private int pendingTarget = -1;
    private int attPayload = 20;
    private boolean pendingReadAfterWrite = false;

    private TextView status;
    private TextView limit;
    private TextView log;
    private Button scanButton;
    private Button readButton;
    private final List<Button> speedButtons = new ArrayList<>();
    private CheckBox privateGround;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            setStatus("Bluetooth LE ist auf diesem Gerät nicht verfügbar.");
            scanButton.setEnabled(false);
        }
        requestBlePermissionsIfNeeded();
        append("Ninebot F2 Lab 1.0.0 gestartet.");
        append("Grenze der App: 25 km/h. Kein Firmware-Flash, keine Leistungs-/Bremsparameter.");
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroller = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroller.addView(root);

        TextView title = new TextView(this);
        title.setText("Ninebot F2 Lab");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("F2 Pro D II · Sicherer Stock-BLE-Test bis 25 km/h");
        subtitle.setTextSize(16);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        status = new TextView(this);
        status.setText("Nicht verbunden");
        status.setTextSize(16);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, matchWrap());

        scanButton = button("Scooter suchen & verbinden");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, matchWrap());

        readButton = button("Aktuelle Grenze auslesen");
        readButton.setEnabled(false);
        readButton.setOnClickListener(v -> readMaxSpeed());
        root.addView(readButton, matchWrap());

        limit = new TextView(this);
        limit.setText("Aktuelle Grenze: —");
        limit.setTextSize(21);
        limit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        limit.setPadding(0, dp(14), 0, dp(10));
        root.addView(limit);

        privateGround = new CheckBox(this);
        privateGround.setText("Ich teste nur auf Privatgelände / Teststand");
        privateGround.setOnCheckedChangeListener((b, checked) -> updateSpeedButtons());
        root.addView(privateGround);

        TextView hint = new TextView(this);
        hint.setText("Die App schreibt nur das bekannte F2-Max-Speed-Register und liest es sofort zurück. Wird 23/24/25 von Firmware 1.8.7 ignoriert, wird nichts weiter umgangen.");
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int kmh : new int[]{20, 22, 23, 24, 25}) {
            Button b = button(kmh + "");
            b.setTag(kmh);
            b.setEnabled(false);
            b.setOnClickListener(v -> testSpeed((Integer)v.getTag()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(b, lp);
            speedButtons.add(b);
        }
        root.addView(row, matchWrap());

        TextView unit = new TextView(this);
        unit.setText("km/h");
        unit.setGravity(Gravity.CENTER_HORIZONTAL);
        unit.setPadding(0, dp(2), 0, dp(14));
        root.addView(unit);

        Button copy = button("Testprotokoll kopieren");
        copy.setOnClickListener(v -> copyLog());
        root.addView(copy, matchWrap());

        log = new TextView(this);
        log.setTextSize(12);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(10), dp(10), dp(10), dp(30));
        root.addView(log, matchWrap());

        setContentView(scroller);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestBlePermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            Toast.makeText(this, "Bluetooth-Berechtigung erlauben und nochmals tippen.", Toast.LENGTH_LONG).show();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Bitte Bluetooth einschalten.", Toast.LENGTH_LONG).show();
            return;
        }
        disconnect();
        found.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        setStatus("Suche 7 Sekunden … Scooter einschalten.");
        append("SCAN start");
        scanner.startScan(scanCallback);
        ui.postDelayed(() -> {
            try { if (scanner != null) scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
            showDevices();
        }, 7000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = null;
            try { name = d.getName(); } catch (SecurityException ignored) {}
            if ((name == null || name.trim().isEmpty()) && result.getScanRecord() != null)
                name = result.getScanRecord().getDeviceName();
            if (name == null) name = "(ohne Namen)";
            String address = d.getAddress();
            found.put(address, new FoundDevice(d, name, result.getRssi()));
        }
    };

    private void showDevices() {
        if (found.isEmpty()) {
            setStatus("Kein BLE-Gerät gefunden.");
            append("SCAN: nichts gefunden");
            return;
        }
        List<FoundDevice> all = new ArrayList<>(found.values());
        all.sort((a,b) -> {
            int pa = likelyNinebot(a.name) ? 0 : 1;
            int pb = likelyNinebot(b.name) ? 0 : 1;
            if (pa != pb) return pa - pb;
            return b.rssi - a.rssi;
        });
        String[] labels = new String[all.size()];
        for (int i=0;i<all.size();i++) {
            FoundDevice f = all.get(i);
            labels[i] = f.name + "   " + f.address + "   " + f.rssi + " dBm";
        }
        new AlertDialog.Builder(this)
                .setTitle("Scooter auswählen")
                .setItems(labels, (dlg, which) -> connect(all.get(which)))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private boolean likelyNinebot(String n) {
        if (n == null) return false;
        String s = n.toUpperCase(Locale.ROOT);
        return s.startsWith("NAH") || s.startsWith("NAG") || s.startsWith("N2") ||
                s.contains("NINEBOT") || s.contains("SEGWAY") || s.contains("F2");
    }

    private void connect(FoundDevice f) {
        device = f.device;
        bleName = f.name.equals("(ohne Namen)") ? "" : f.name;
        setStatus("Verbinde mit " + f.name + " …");
        append("CONNECT " + f.name + " " + f.address);
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendUi("GATT verbunden; Services werden gelesen …");
                try { g.requestMtu(247); } catch (Exception ignored) {}
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendUi("GATT getrennt (status " + st + ")");
                ui.post(() -> {
                    setStatus("Nicht verbunden");
                    authState = AuthState.IDLE;
                    updateSpeedButtons();
                    readButton.setEnabled(false);
                });
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(SVC_NINEBOT);
            if (svc != null) {
                writeChar = svc.getCharacteristic(WR_NINEBOT);
                notifyChar = svc.getCharacteristic(NT_NINEBOT);
                appendUi("Ninebot-GATT-Service gefunden.");
            }
            if (writeChar == null || notifyChar == null) {
                svc = g.getService(SVC_NUS);
                if (svc != null) {
                    writeChar = svc.getCharacteristic(WR_NUS);
                    notifyChar = svc.getCharacteristic(NT_NUS);
                    appendUi("Nordic-UART-Fallback gefunden.");
                }
            }
            if (writeChar == null || notifyChar == null) {
                appendUi("FEHLER: passender Ninebot BLE Service nicht gefunden.");
                ui.post(() -> setStatus("Ninebot-GATT-Service fehlt"));
                return;
            }
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
            ui.postDelayed(() -> beginProtocolProbe(), 700);
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                attPayload = Math.max(20, mtu - 3);
                appendUi("MTU=" + mtu + " · ATT payload=" + attPayload);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            onNotify(value == null ? new byte[0] : value);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) appendUi("GATT write status=" + status);
        }
    };

    private void beginProtocolProbe() {
        if (bleName == null || bleName.isEmpty()) {
            setStatus("Verbunden, aber BLE-Name fehlt");
            append("Für Encryption2 wird der BLE-Gerätename benötigt. Nochmals scannen und Gerät mit Namen wählen.");
            return;
        }
        currentRaw = -1;
        speedScale = 0;
        controllerVerified = false;
        pendingTarget = -1;
        pendingReadAfterWrite = false;
        mode = Mode.ENC2;
        authState = AuthState.PRECOMM;
        attemptSerial++;
        int token = attemptSerial;
        bleTarget = NinebotProtocol.BLE_MODERN;
        resetRx();
        try {
            crypto.reset();
            crypto.setPreCommKey(bleName);
            setStatus("Verbunden · Authentifizierung …");
            append("ENC2 PRE_COMM über BLE target 0x04");
            sendEncrypted(bleTarget, 0x5B, 0x00, new byte[0]);
        } catch (Exception e) {
            append("Crypto init: " + e.getMessage());
        }
        ui.postDelayed(() -> {
            if (token == attemptSerial && authState == AuthState.PRECOMM) {
                bleTarget = NinebotProtocol.BLE_LEGACY;
                try {
                    crypto.reset();
                    crypto.setPreCommKey(bleName);
                    resetRx();
                    append("Keine Antwort; ENC2 PRE_COMM über Legacy-target 0x21");
                    sendEncrypted(bleTarget, 0x5B, 0x00, new byte[0]);
                } catch (Exception e) { append("Retry: " + e.getMessage()); }
            }
        }, 1800);
        ui.postDelayed(() -> {
            if (token == attemptSerial && authState == AuthState.PRECOMM) {
                append("Keine ENC2-Antwort; teste plain Protocol-2.");
                mode = Mode.PLAIN;
                resetRx();
                bleTarget = NinebotProtocol.BLE_MODERN;
                sendPlain(bleTarget, 0x5B, 0x00, new byte[0]);
                ui.postDelayed(() -> {
                    if (token == attemptSerial && authState == AuthState.PRECOMM) {
                        bleTarget = NinebotProtocol.BLE_LEGACY;
                        sendPlain(bleTarget, 0x5B, 0x00, new byte[0]);
                    }
                }, 1200);
                ui.postDelayed(() -> {
                    if (token == attemptSerial && authState == AuthState.PRECOMM) {
                        setStatus("Verbunden, aber Protokoll-Antwort fehlt");
                        append("STOP: kein unterstützter PRE_COMM-Reply. Bitte Protokoll kopieren; v1.0 kann anhand des Protokolls angepasst werden.");
                    }
                }, 2800);
            }
        }, 3800);
    }

    private synchronized void onNotify(byte[] chunk) {
        if (chunk.length == 0) return;
        appendUi("RX chunk: " + NinebotProtocol.hex(chunk));
        try {
            rx.write(chunk);
            parseRxFrames();
        } catch (Exception e) {
            appendUi("RX parse error: " + e.getMessage());
            resetRx();
        }
    }

    private void parseRxFrames() throws Exception {
        while (true) {
            byte[] a = rx.toByteArray();
            int start = findHeader(a);
            if (start < 0) {
                if (a.length > 2) resetRx();
                return;
            }
            if (start > 0) {
                replaceRx(Arrays.copyOfRange(a, start, a.length));
                a = rx.toByteArray();
            }
            if (a.length < 3) return;
            int len = a[2] & 0xFF;
            int total = mode == Mode.PLAIN ? len + 9 : len + 13;
            if (a.length < total) return;
            byte[] frame = Arrays.copyOfRange(a, 0, total);
            byte[] remain = Arrays.copyOfRange(a, total, a.length);
            replaceRx(remain);
            processFrame(frame);
        }
    }

    private int findHeader(byte[] a) {
        for (int i=0;i+1<a.length;i++) if (a[i] == 0x5A && (a[i+1] & 0xFF) == 0xA5) return i;
        return -1;
    }

    private void processFrame(byte[] wire) {
        try {
            byte[] plain;
            if (mode == Mode.ENC2) {
                plain = crypto.decrypt(wire);
            } else {
                if (wire.length < 9) return;
                plain = Arrays.copyOf(wire, wire.length - 2);
                verifyPlainChecksum(wire);
            }
            append("RX frame: " + NinebotProtocol.hex(plain));
            handlePlaintext(plain);
        } catch (Exception e) {
            append("Decrypt/frame reject: " + e.getMessage());
        }
    }

    private void verifyPlainChecksum(byte[] wire) {
        int sum = 0;
        for (int i=2;i<wire.length-2;i++) sum += wire[i] & 0xFF;
        int calc = (~sum) & 0x7FFF;
        int got = (wire[wire.length-2] & 0xFF) | ((wire[wire.length-1] & 0xFF) << 8);
        if (calc != got) append("WARN: Plain checksum erwartet " + calc + ", erhalten " + got);
    }

    private void handlePlaintext(byte[] p) throws Exception {
        if (!NinebotProtocol.looksLikeFrame(p)) return;
        int cmd = NinebotProtocol.cmd(p);
        int idx = NinebotProtocol.index(p);
        byte[] data = NinebotProtocol.data(p);

        if (authState == AuthState.PRECOMM && cmd == 0x5B) {
            attemptSerial++;
            if (mode == Mode.PLAIN) {
                authState = AuthState.READY;
                append("Plain Protocol-2 antwortet; keine zusätzliche Crypto-Paarung nötig.");
                ready();
                return;
            }
            if (data.length < 30) {
                append("PRE_COMM payload zu kurz: " + data.length);
                return;
            }
            authParam = Arrays.copyOfRange(data, 0, 16);
            scooterSerial = Arrays.copyOfRange(data, 16, 30);
            append("PRE_COMM ok · Scooter SN: " + safeAscii(scooterSerial) + " · storedFlag=" + idx);
            crypto.setAuth(authParam);
            crypto.setHandshakeKey(bleName);

            byte[] stored = loadPassword();
            if (stored != null && stored.length == 16) {
                triedStoredPassword = true;
                sessionPassword = stored;
                crypto.setSessionKey(sessionPassword);
                authState = AuthState.AUTH;
                append("Gespeicherten Ninebot-F2-Lab-Schlüssel teste ich zuerst.");
                sendEncrypted(bleTarget, 0x5D, 0x00, scooterSerial);
            } else {
                triedStoredPassword = false;
                startSetPassword();
            }
            return;
        }

        if (authState == AuthState.SETPWD && cmd == 0x5C) {
            if (idx == 1) {
                append("Power-Taste bestätigt; Session-Key akzeptiert.");
                crypto.setSessionKey(sessionPassword);
                authState = AuthState.AUTH;
                sendEncrypted(bleTarget, 0x5D, 0x00, scooterSerial);
            } else {
                append("SET_PWD pending (index=" + idx + ") · Power-Taste am Scooter kurz drücken.");
            }
            return;
        }

        if (authState == AuthState.AUTH && cmd == 0x5D) {
            if (idx == 1) {
                append("AUTH erfolgreich.");
                savePassword(sessionPassword);
                authState = AuthState.READY;
                ready();
            } else if (triedStoredPassword) {
                append("Gespeicherter Schlüssel ist nicht mehr gültig; neue physische Paarung nötig.");
                clearPassword();
                triedStoredPassword = false;
                crypto.setAuth(authParam);
                crypto.setHandshakeKey(bleName);
                startSetPassword();
            } else {
                append("AUTH abgelehnt (index=" + idx + ").");
            }
            return;
        }

        if (authState == AuthState.READY && (cmd == NinebotProtocol.CMD_READ_ACK || cmd == NinebotProtocol.CMD_READ)
                && idx == 0x10 && data.length >= 14 && NinebotProtocol.source(p) == NinebotProtocol.ESC) {
            byte[] gotSn = Arrays.copyOfRange(data, 0, 14);
            String got = safeAscii(gotSn);
            String expected = scooterSerial == null ? "" : safeAscii(scooterSerial);
            if (!expected.isEmpty() && Arrays.equals(gotSn, scooterSerial)) {
                controllerVerified = true;
                append("CTRL 0x20 verifiziert · SN=" + got);
                setStatus("Verbunden & Controller verifiziert");
                updateSpeedButtons();
                ui.postDelayed(this::readMaxSpeed, 250);
            } else {
                append("CTRL 0x20 rSN=" + got + " · erwartet=" + expected + " · Schreiben bleibt gesperrt.");
            }
            return;
        }

        if (authState == AuthState.READY && (cmd == NinebotProtocol.CMD_READ_ACK || cmd == NinebotProtocol.CMD_READ)
                && idx == NinebotProtocol.IDX_SPEED_LIMIT && data.length >= 2) {
            int raw = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
            onMaxSpeed(raw);
        }
    }

    private void startSetPassword() throws Exception {
        sessionPassword = new byte[16];
        secureRandom.nextBytes(sessionPassword);
        authState = AuthState.SETPWD;
        setStatus("Power-Taste am Scooter kurz drücken");
        append("Neue lokale BLE-Session wird angefragt. Jetzt Power-Taste am Scooter kurz drücken.");
        int token = ++attemptSerial;
        Runnable retry = new Runnable() {
            int count = 0;
            @Override public void run() {
                if (authState != AuthState.SETPWD || token != attemptSerial) return;
                if (count++ >= 20) {
                    append("SET_PWD Timeout. Nochmals verbinden und Power-Taste früher drücken.");
                    setStatus("Authentifizierung Timeout");
                    return;
                }
                try { sendEncrypted(bleTarget, 0x5C, 0x00, sessionPassword); }
                catch (Exception e) { append("SET_PWD send: " + e.getMessage()); }
                ui.postDelayed(this, 2000);
            }
        };
        retry.run();
    }

    private void ready() {
        ui.post(() -> {
            setStatus("Verbunden & authentifiziert");
            readButton.setEnabled(true);
            updateSpeedButtons();
        });
        ui.postDelayed(this::verifyController, 400);
    }

    private void verifyController() {
        if (authState != AuthState.READY) return;
        append("VERIFY CTRL 0x20 via rSN 0x10");
        sendCommand(NinebotProtocol.ESC, NinebotProtocol.CMD_READ, 0x10, new byte[]{0x0E});
        ui.postDelayed(() -> {
            if (!controllerVerified && authState == AuthState.READY) {
                append("STOP: CTRL 0x20 konnte nicht anhand der Seriennummer bestätigt werden. Schreiben bleibt gesperrt.");
                setStatus("Controller nicht verifiziert");
                updateSpeedButtons();
            }
        }, 1800);
    }

    private void readMaxSpeed() {
        if (authState != AuthState.READY) {
            Toast.makeText(this, "Noch nicht authentifiziert.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!controllerVerified) {
            Toast.makeText(this, "Controller 0x20 ist noch nicht verifiziert.", Toast.LENGTH_SHORT).show();
            verifyController();
            return;
        }
        append("READ speed_limit ESC:0x93");
        sendCommand(NinebotProtocol.ESC, NinebotProtocol.CMD_READ, NinebotProtocol.IDX_SPEED_LIMIT,
                new byte[]{0x02});
    }

    private void onMaxSpeed(int raw) {
        currentRaw = raw;
        speedScale = inferScale(raw);
        if (speedScale == 0) {
            append("speed_limit raw=" + raw + " · Einheit unbekannt. Schreiben bleibt gesperrt.");
            ui.post(() -> {
                limit.setText("Aktuelle Grenze: raw " + raw + " (Einheit unbekannt)");
                updateSpeedButtons();
            });
            return;
        }
        double kmh = raw / (double)speedScale;
        append(String.format(Locale.US, "speed_limit raw=%d => %.1f km/h (scale=%d)", raw, kmh, speedScale));
        ui.post(() -> {
            limit.setText(String.format(Locale.getDefault(), "Aktuelle Grenze: %.1f km/h", kmh));
            updateSpeedButtons();
        });

        if (pendingReadAfterWrite) {
            pendingReadAfterWrite = false;
            double actual = raw / (double)speedScale;
            if (Math.abs(actual - pendingTarget) < 0.11) {
                append("ERGEBNIS: " + pendingTarget + " km/h wurde vom Register angenommen.");
                toast("Angenommen: " + pendingTarget + " km/h");
            } else {
                append(String.format(Locale.US, "ERGEBNIS: %d wurde NICHT gehalten; Readback %.1f km/h. Firmware ignoriert/begrenzt den Wert.", pendingTarget, actual));
                toast("Nicht angenommen – bleibt bei " + String.format(Locale.getDefault(), "%.1f", actual));
            }
            pendingTarget = -1;
        }
    }

    private int inferScale(int raw) {
        if (raw >= 15 && raw <= 60) return 1;
        if (raw >= 150 && raw <= 600) return 10;
        if (raw >= 1500 && raw <= 6000) return 100;
        return 0;
    }

    private void testSpeed(int kmh) {
        if (kmh < 20 || kmh > MAX_TEST_KMH) return;
        if (!privateGround.isChecked()) {
            Toast.makeText(this, "Privatgelände/Teststand bestätigen.", Toast.LENGTH_LONG).show();
            return;
        }
        if (authState != AuthState.READY || !controllerVerified || speedScale == 0) {
            Toast.makeText(this, "Zuerst Controller verifizieren und aktuelle Grenze auslesen.", Toast.LENGTH_LONG).show();
            return;
        }
        int raw = kmh * speedScale;
        pendingTarget = kmh;
        pendingReadAfterWrite = true;
        append("TEST speed_limit ESC:0x93 -> " + kmh + " km/h (raw " + raw + ")");
        sendCommand(NinebotProtocol.ESC, NinebotProtocol.CMD_WRITE, NinebotProtocol.IDX_SPEED_LIMIT,
                new byte[]{(byte)(raw & 0xFF), (byte)((raw >>> 8) & 0xFF)});
        ui.postDelayed(this::readMaxSpeed, 900);
    }

    private void sendCommand(int target, int cmd, int idx, byte[] data) {
        try {
            if (mode == Mode.ENC2) sendEncrypted(target, cmd, idx, data);
            else if (mode == Mode.PLAIN) sendPlain(target, cmd, idx, data);
        } catch (Exception e) {
            append("SEND error: " + e.getMessage());
        }
    }

    private void sendEncrypted(int target, int cmd, int idx, byte[] data) throws Exception {
        byte[] inner = NinebotProtocol.inner(target, cmd, idx, data);
        byte[] wire = crypto.encrypt(inner);
        writeWire(wire, "ENC");
    }

    private void sendPlain(int target, int cmd, int idx, byte[] data) {
        byte[] wire = NinebotProtocol.plain(target, cmd, idx, data);
        writeWire(wire, "PLAIN");
    }

    private synchronized void writeWire(byte[] bytes, String kind) {
        if (gatt == null || writeChar == null) {
            append("WRITE ohne GATT");
            return;
        }
        append("TX " + kind + ": " + NinebotProtocol.hex(bytes));
        int props = writeChar.getProperties();
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        else
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

        if (bytes.length <= attPayload) {
            writeChar.setValue(bytes);
            gatt.writeCharacteristic(writeChar);
        } else {
            // Pairing frames can exceed the default 20-byte ATT payload. Send
            // fragments in order with a small gap; Ninebot reassembles them.
            List<byte[]> parts = new ArrayList<>();
            for (int off = 0; off < bytes.length; off += attPayload) {
                int n = Math.min(attPayload, bytes.length - off);
                parts.add(Arrays.copyOfRange(bytes, off, off + n));
            }
            writePart(parts, 0);
        }
    }

    private void writePart(List<byte[]> parts, int index) {
        if (index >= parts.size() || gatt == null || writeChar == null) return;
        byte[] part = parts.get(index);
        writeChar.setValue(part);
        boolean queued = gatt.writeCharacteristic(writeChar);
        if (!queued) {
            append("GATT Fragment " + (index + 1) + " konnte nicht eingereiht werden");
            return;
        }
        if (index + 1 < parts.size()) {
            ui.postDelayed(() -> writePart(parts, index + 1), 35);
        }
    }

    private byte[] loadPassword() {
        if (device == null) return null;
        SharedPreferences p = getSharedPreferences("keys", MODE_PRIVATE);
        String s = p.getString(device.getAddress(), null);
        if (s == null) return null;
        try { return Base64.decode(s, Base64.NO_WRAP); } catch (Exception e) { return null; }
    }

    private void savePassword(byte[] pwd) {
        if (device == null || pwd == null) return;
        getSharedPreferences("keys", MODE_PRIVATE).edit()
                .putString(device.getAddress(), Base64.encodeToString(pwd, Base64.NO_WRAP)).apply();
    }

    private void clearPassword() {
        if (device == null) return;
        getSharedPreferences("keys", MODE_PRIVATE).edit().remove(device.getAddress()).apply();
    }

    private void updateSpeedButtons() {
        boolean ok = privateGround != null && privateGround.isChecked() && authState == AuthState.READY && controllerVerified && speedScale > 0;
        for (Button b : speedButtons) b.setEnabled(ok);
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void append(String s) {
        appendUi(s);
    }

    private void appendUi(String s) {
        ui.post(() -> {
            if (log == null) return;
            String old = log.getText().toString();
            if (old.length() > 18000) old = old.substring(old.length() - 14000);
            log.setText(old + (old.isEmpty() ? "" : "\n") + s);
        });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private String safeAscii(byte[] b) {
        try { return new String(b, "US-ASCII").replaceAll("[^ -~]", "?"); }
        catch (Exception e) { return NinebotProtocol.hex(b); }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Ninebot F2 Lab Log", log.getText()));
        Toast.makeText(this, "Protokoll kopiert.", Toast.LENGTH_SHORT).show();
    }

    private synchronized void resetRx() { rx.reset(); }
    private synchronized void replaceRx(byte[] b) {
        rx.reset();
        try { rx.write(b); } catch (Exception ignored) {}
    }

    private void disconnect() {
        attemptSerial++;
        authState = AuthState.IDLE;
        mode = Mode.UNKNOWN;
        resetRx();
        currentRaw = -1;
        speedScale = 0;
        controllerVerified = false;
        attPayload = 20;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        writeChar = null;
        notifyChar = null;
        updateSpeedButtons();
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private static final class FoundDevice {
        final BluetoothDevice device;
        final String name;
        final String address;
        final int rssi;
        FoundDevice(BluetoothDevice d, String n, int r) {
            device=d; name=n; address=d.getAddress(); rssi=r;
        }
    }
}
