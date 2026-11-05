package de.multiapplications.multiampel;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BluetoothLink {

    public static class Candidate {
        public final BluetoothDevice device;
        public final String name;

        Candidate(BluetoothDevice device, String name) {
            this.device = device;
            this.name = name;
        }
    }

    public interface Listener {
        void onScanStarted();
        void onScanFinished();
        void onCandidates(List<Candidate> candidates);
        void onPinChallenge(String deviceName, String pin);
        void onPinRequest(Candidate candidate);
        void onPinResult(boolean success, int attemptsLeft);
        void onPinFinished(String deviceName, boolean success);
        void onPeersChanged(List<String> peers);
        void onMessage(String line);
        void onError(String reason);
    }

    private static final UUID SERVICE_UUID = UUID.fromString("b1a3c9d2-7c4e-4f6a-9b8d-2e5f7a1c3d90");
    private static final String SERVICE_NAME = "MultiAmpel";
    private static final int MAX_ATTEMPTS = 3;
    private static final long PIN_TIMEOUT = 90000L;
    private static final long PROBE_TIMEOUT = 4000L;
    private static final long PROBE_RETRY = 5000L;
    private static final long PEER_TIMEOUT = 6000L;

    private class Connection {
        final BluetoothSocket socket;
        final boolean host;
        String name = "Android";
        boolean authenticated;
        boolean challengeShown;
        String pin;
        long pinCreatedAt;
        int attempts;
        int index;

        Connection(BluetoothSocket socket, boolean host) {
            this.socket = socket;
            this.host = host;
        }
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final List<Connection> connections = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final Set<String> probed = new LinkedHashSet<>();
    private final List<BluetoothDevice> probeQueue = new ArrayList<>();
    private final Map<String, Long> probeAttempts = new HashMap<>();

    private Listener listener;
    private BluetoothServerSocket serverSocket;
    private Thread serverThread;
    private boolean receiverRegistered;
    private boolean closed;
    private boolean visible;
    private boolean scanning;
    private boolean connecting;
    private boolean probeRunning;
    private BluetoothLeScanner leScanner;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private Connection pendingClient;
    private String ownAddress;
    private boolean pinRequired = true;
    private int lastPeerCount;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    enqueueProbe(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                handleDiscoveryFinished();
            }
        }
    };

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result != null && result.getDevice() != null) {
                enqueueProbe(result.getDevice());
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            advertiser = null;
            mainHandler.post(BluetoothLink.this::resumeDiscovery);
        }
    };

    public BluetoothLink(Context context) {
        this.context = context.getApplicationContext();
        this.adapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isAvailable() {
        return adapter != null;
    }

    public boolean isEnabled() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException exception) {
            return false;
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public String ownName() {
        try {
            String name = adapter == null ? null : adapter.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (SecurityException ignored) {
        }
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    public List<Candidate> candidates() {
        return new ArrayList<>(candidates);
    }

    public List<String> peerNames() {
        List<String> names = new ArrayList<>();
        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.authenticated) {
                    names.add(connection.name);
                }
            }
        }
        return names;
    }

    public int peerCount() {
        return peerNames().size();
    }

    public void setVisible(boolean enable) {
        visible = enable;
        if (enable) {
            startServer();
        } else {
            stopServer();
        }
    }

    public void startScan() {
        if (!hasPermissions() || !isEnabled()) {
            onError("permission");
            return;
        }
        registerReceiver();
        synchronized (candidates) {
            candidates.clear();
            probed.clear();
        }
        synchronized (probeQueue) {
            probeQueue.clear();
        }
        synchronized (probeAttempts) {
            probeAttempts.clear();
        }
        scanning = true;
        startServer();
        startLeScan();
        startAdvertise();
        if (advertising) {
            notifyScanStarted();
            mainHandler.postDelayed(this::fallbackDiscovery, 6000L);
        } else {
            beginDiscovery();
        }
    }

    private void fallbackDiscovery() {
        if (!scanning) {
            return;
        }
        synchronized (candidates) {
            if (!candidates.isEmpty()) {
                return;
            }
        }
        try {
            if (!adapter.isDiscovering()) {
                adapter.startDiscovery();
            }
        } catch (SecurityException ignored) {
        }
        mainHandler.postDelayed(this::fallbackDiscovery, 15000L);
    }

    public void stopScan() {
        scanning = false;
        stopLeScan();
        cancelDiscovery();
        synchronized (probeQueue) {
            probeQueue.clear();
        }
        if (!visible) {
            stopServer();
        }
    }

    private void beginDiscovery() {
        if (!scanning || connecting || advertising || !hasPermissions() || !isEnabled()) {
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            boolean started = adapter.startDiscovery();
            notifyScanStarted();
            if (!started) {
                mainHandler.postDelayed(this::resumeDiscovery, 1500L);
            }
        } catch (SecurityException exception) {
            onError("scan");
        }
    }

    private void resumeDiscovery() {
        if (scanning && !connecting && !advertising) {
            beginDiscovery();
        }
    }

    private void cancelDiscovery() {
        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
        }
    }

    public void pair(Candidate candidate) {
        if (candidate == null || !hasPermissions()) {
            return;
        }
        new Thread(() -> {
            BluetoothSocket socket = null;
            connecting = true;
            try {
                cancelDiscovery();
                for (int attempt = 0; attempt < 2 && socket == null; attempt++) {
                    BluetoothSocket attemptSocket = null;
                    try {
                        attemptSocket = candidate.device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                        attemptSocket.connect();
                        socket = attemptSocket;
                    } catch (IOException | SecurityException exception) {
                        closeQuietly(attemptSocket);
                        if (attempt == 1) {
                            throw exception;
                        }
                        SystemClock.sleep(200L);
                    }
                }
                Connection connection = new Connection(socket, false);
                connection.name = candidate.name;
                synchronized (connections) {
                    connections.add(connection);
                    pendingClient = connection;
                }
                startReader(connection);
                write(connection, "HELLO|1|" + ownName() + "|" + addressOf(socket));
                mainHandler.postDelayed(() -> verifyPeer(connection), PEER_TIMEOUT);
            } catch (IOException | SecurityException exception) {
                closeQuietly(socket);
                onError("pair");
            } finally {
                connecting = false;
                mainHandler.postDelayed(this::resumeDiscovery, 800L);
            }
        }, "multiampel-pair").start();
    }

    public void setPinRequired(boolean required) {
        pinRequired = required;
    }

    public void submitPin(String code) {
        Connection connection;
        synchronized (connections) {
            connection = pendingClient;
        }
        if (connection != null && code != null && !code.isEmpty()) {
            write(connection, "PIN|" + code);
        }
    }

    public List<Candidate> bondedDevices() {
        List<Candidate> devices = new ArrayList<>();
        if (!hasPermissions() || !isEnabled()) {
            return devices;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return devices;
            }
            for (BluetoothDevice device : bonded) {
                devices.add(new Candidate(device, displayName(device)));
            }
        } catch (SecurityException ignored) {
        }
        return devices;
    }

    private void verifyPeer(Connection connection) {
        boolean answered;
        synchronized (connections) {
            answered = !connections.contains(connection) || connection.challengeShown || connection.authenticated;
        }
        if (!answered) {
            closeQuietly(connection.socket);
            onError("peer");
        }
    }

    public int ownIndex() {
        synchronized (connections) {
            for (Connection connection : connections) {
                if (!connection.host && connection.authenticated) {
                    return connection.index;
                }
            }
        }
        return 0;
    }

    public int indexOf(String name) {
        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.authenticated && connection.name.equals(name)) {
                    return connection.index;
                }
            }
        }
        return 0;
    }

    public String addressOf(String name) {
        if (ownName().equals(name)) {
            if (ownAddress != null && !ownAddress.isEmpty()) {
                return ownAddress;
            }
            try {
                String address = adapter == null ? null : adapter.getAddress();
                return address == null ? "" : address;
            } catch (SecurityException ignored) {
                return "";
            }
        }
        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.authenticated && connection.name.equals(name)) {
                    try {
                        return connection.socket.getRemoteDevice().getAddress();
                    } catch (SecurityException ignored) {
                        return "";
                    }
                }
            }
        }
        for (Candidate candidate : bondedDevices()) {
            if (candidate.name.equals(name)) {
                try {
                    return candidate.device.getAddress();
                } catch (SecurityException ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private String addressOf(BluetoothSocket socket) {
        try {
            return socket.getRemoteDevice().getAddress();
        } catch (SecurityException exception) {
            return "";
        }
    }

    public void sendToHost(String line) {
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>();
            for (Connection connection : connections) {
                if (connection.authenticated) {
                    targets.add(connection);
                }
            }
        }
        for (Connection connection : targets) {
            write(connection, line);
        }
    }

    public void declineChallenges() {
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>();
            for (Connection connection : connections) {
                if (connection.host && !connection.authenticated) {
                    targets.add(connection);
                }
            }
        }
        for (Connection connection : targets) {
            closeQuietly(connection.socket);
        }
    }

    public void cancelPending() {
        Connection connection;
        synchronized (connections) {
            connection = pendingClient;
            pendingClient = null;
        }
        if (connection != null) {
            closeQuietly(connection.socket);
        }
    }

    public void broadcast(String line) {
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>();
            for (Connection connection : connections) {
                if (connection.authenticated) {
                    targets.add(connection);
                }
            }
        }
        for (Connection connection : targets) {
            write(connection, line);
        }
    }

    public void disconnectAll() {
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>(connections);
            connections.clear();
            pendingClient = null;
        }
        for (Connection connection : targets) {
            closeQuietly(connection.socket);
        }
        notifyPeers();
    }

    public void close() {
        closed = true;
        stopScan();
        stopServer();
        unregisterReceiver();
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>(connections);
            connections.clear();
            pendingClient = null;
        }
        for (Connection connection : targets) {
            closeQuietly(connection.socket);
        }
    }

    private void startServer() {
        if (serverThread != null || !hasPermissions() || !isEnabled()) {
            return;
        }
        mainHandler.post(this::startAdvertise);
        serverThread = new Thread(() -> {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                while (!closed) {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        Connection connection = new Connection(socket, true);
                        synchronized (connections) {
                            connections.add(connection);
                        }
                        startReader(connection);
                    }
                }
            } catch (IOException | SecurityException ignored) {
            }
        }, "multiampel-server");
        serverThread.start();
    }

    private void stopServer() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
        serverThread = null;
        stopAdvertise();
    }

    private void startLeScan() {
        if (leScanner != null || !hasPermissions() || !isEnabled()) {
            return;
        }
        try {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                return;
            }
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, leScanCallback);
            leScanner = scanner;
        } catch (RuntimeException ignored) {
        }
    }

    private void stopLeScan() {
        BluetoothLeScanner scanner = leScanner;
        leScanner = null;
        if (scanner == null) {
            return;
        }
        try {
            scanner.stopScan(leScanCallback);
        } catch (RuntimeException ignored) {
        }
    }

    private void startAdvertise() {
        if (advertising || !isEnabled() || !hasPermissions() || !hasAdvertisePermission()) {
            return;
        }
        try {
            if (!adapter.isMultipleAdvertisementSupported()) {
                return;
            }
            BluetoothLeAdvertiser leAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (leAdvertiser == null) {
                return;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build();
            leAdvertiser.startAdvertising(settings, data, advertiseCallback);
            advertiser = leAdvertiser;
            advertising = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void stopAdvertise() {
        BluetoothLeAdvertiser leAdvertiser = advertiser;
        advertiser = null;
        advertising = false;
        if (leAdvertiser == null) {
            return;
        }
        try {
            leAdvertiser.stopAdvertising(advertiseCallback);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isPeerAddress(String address) {
        synchronized (connections) {
            for (Connection connection : connections) {
                if (!connection.authenticated) {
                    continue;
                }
                try {
                    if (address.equals(connection.socket.getRemoteDevice().getAddress())) {
                        return true;
                    }
                } catch (SecurityException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private void enqueueProbe(BluetoothDevice device) {
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException exception) {
            return;
        }
        synchronized (probeQueue) {
            for (BluetoothDevice queued : probeQueue) {
                try {
                    if (address.equals(queued.getAddress())) {
                        return;
                    }
                } catch (SecurityException ignored) {
                    return;
                }
            }
            probeQueue.add(device);
        }
        startProbeDrain();
    }

    private void handleDiscoveryFinished() {
        if (!scanning) {
            notifyScanFinished();
            return;
        }
        startProbeDrain();
    }

    private void startProbeDrain() {
        synchronized (probeQueue) {
            if (probeRunning) {
                return;
            }
            probeRunning = true;
        }
        new Thread(this::runProbes, "multiampel-probe").start();
    }

    private void runProbes() {
        cancelDiscovery();
        while (true) {
            BluetoothDevice device;
            synchronized (probeQueue) {
                if (!scanning || probeQueue.isEmpty()) {
                    break;
                }
                device = probeQueue.remove(0);
            }
            probe(device);
        }
        synchronized (probeQueue) {
            probeRunning = false;
        }
        if (!advertising) {
            notifyScanFinished();
        }
        mainHandler.postDelayed(this::resumeDiscovery, 500L);
    }

    private void probe(BluetoothDevice device) {
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException exception) {
            return;
        }
        synchronized (candidates) {
            if (probed.contains(address)) {
                return;
            }
        }
        long now = SystemClock.uptimeMillis();
        synchronized (probeAttempts) {
            Long last = probeAttempts.get(address);
            if (last != null && now - last < PROBE_RETRY) {
                return;
            }
            probeAttempts.put(address, now);
        }
        if (isPeerAddress(address)) {
            return;
        }
        BluetoothSocket socket = null;
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
            final BluetoothSocket probeSocket = socket;
            mainHandler.postDelayed(() -> closeQuietly(probeSocket), PROBE_TIMEOUT);
            socket.connect();
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write("HELLO|0\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            String line = readLine(socket.getInputStream());
            if (line != null && line.startsWith("AMPEL|")) {
                String name = line.substring(6).trim();
                Candidate candidate = new Candidate(device, name.isEmpty() ? displayName(device) : name);
                boolean fresh;
                synchronized (candidates) {
                    fresh = probed.add(address);
                    if (fresh) {
                        candidates.add(candidate);
                    }
                }
                if (fresh) {
                    notifyCandidates();
                }
            }
        } catch (IOException | SecurityException ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    private void startReader(Connection connection) {
        new Thread(() -> {
            try {
                InputStream inputStream = connection.socket.getInputStream();
                String line;
                while ((line = readLine(inputStream)) != null) {
                    handleLine(connection, line.trim());
                }
            } catch (IOException ignored) {
            } finally {
                synchronized (connections) {
                    connections.remove(connection);
                    if (pendingClient == connection) {
                        pendingClient = null;
                    }
                }
                closeQuietly(connection.socket);
                notifyPeers();
                if (connection.challengeShown) {
                    notifyPinFinished(connection.name, connection.authenticated);
                }
            }
        }, "multiampel-reader").start();
    }

    private void handleLine(Connection connection, String line) {
        String[] parts = line.split("\\|", -1);
        String command = parts[0];
        switch (command) {
            case "HELLO":
                if (connection.host) {
                    if (parts.length >= 2 && "0".equals(parts[1])) {
                        write(connection, "AMPEL|" + ownName());
                        closeQuietly(connection.socket);
                    } else if (parts.length >= 3) {
                        connection.name = parts[2];
                        if (parts.length >= 4 && !parts[3].isEmpty()) {
                            ownAddress = parts[3];
                        }
                        if (pinRequired) {
                            connection.pin = newPin();
                            connection.pinCreatedAt = SystemClock.uptimeMillis();
                            connection.attempts = 0;
                            connection.challengeShown = true;
                            write(connection, "PINREQ");
                            notifyPinChallenge(connection.name, connection.pin);
                        } else {
                            connection.challengeShown = true;
                            connection.authenticated = true;
                            connection.index = nextIndex();
                            write(connection, "OK|" + connection.index + "|" + addressOf(connection.socket));
                            notifyPeers();
                            notifyPinFinished(connection.name, true);
                        }
                    }
                }
                break;
            case "PINREQ":
                if (!connection.host) {
                    connection.challengeShown = true;
                    synchronized (connections) {
                        pendingClient = connection;
                    }
                    notifyPinRequest(new Candidate(null, connection.name));
                }
                break;
            case "PIN":
                if (connection.host) {
                    verifyPin(connection, parts.length >= 2 ? parts[1] : "");
                }
                break;
            case "OK":
                if (!connection.host) {
                    connection.authenticated = true;
                    connection.index = parts.length >= 2 ? parseInt(parts[1]) : 1;
                    if (parts.length >= 3 && !parts[2].isEmpty()) {
                        ownAddress = parts[2];
                    }
                    notifyPeers();
                    notifyPinResult(true, 0);
                    notifyPinFinished(connection.name, true);
                }
                break;
            case "RETRY":
                notifyPinResult(false, parts.length >= 2 ? parseInt(parts[1]) : 0);
                break;
            case "DENY":
                notifyPinResult(false, 0);
                notifyPinFinished(connection.name, false);
                closeQuietly(connection.socket);
                break;
            case "REQUEST":
                if (connection.authenticated) {
                    if (connection.host) {
                        write(connection, "PEERS|" + peerCount());
                        relay(connection, line);
                    }
                    notifyMessage(line);
                }
                break;
            default:
                if (connection.authenticated) {
                    if (connection.host) {
                        relay(connection, line);
                    }
                    notifyMessage(line);
                }
                break;
        }
    }

    private void relay(Connection source, String line) {
        List<Connection> targets;
        synchronized (connections) {
            targets = new ArrayList<>();
            for (Connection connection : connections) {
                if (connection.authenticated && connection != source) {
                    targets.add(connection);
                }
            }
        }
        for (Connection connection : targets) {
            write(connection, line);
        }
    }

    private void verifyPin(Connection connection, String code) {
        if (connection.authenticated || connection.pin == null) {
            return;
        }
        if (SystemClock.uptimeMillis() - connection.pinCreatedAt > PIN_TIMEOUT) {
            write(connection, "DENY");
            notifyPinFinished(connection.name, false);
            closeQuietly(connection.socket);
            return;
        }
        boolean match = MessageDigest.isEqual(
                connection.pin.getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (match) {
            connection.authenticated = true;
            connection.index = nextIndex();
            write(connection, "OK|" + connection.index + "|" + addressOf(connection.socket));
            notifyPeers();
            notifyPinFinished(connection.name, true);
        } else {
            connection.attempts++;
            if (connection.attempts >= MAX_ATTEMPTS) {
                write(connection, "DENY");
                notifyPinFinished(connection.name, false);
                closeQuietly(connection.socket);
            } else {
                write(connection, "RETRY|" + (MAX_ATTEMPTS - connection.attempts));
            }
        }
    }

    private int nextIndex() {
        int index = 1;
        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.authenticated && connection.index >= index) {
                    index = connection.index + 1;
                }
            }
        }
        return index;
    }

    private String newPin() {
        return String.format("%04d", random.nextInt(10000));
    }

    private void write(Connection connection, String line) {
        try {
            OutputStream outputStream = connection.socket.getOutputStream();
            outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException ignored) {
        }
    }

    private String readLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = inputStream.read()) != -1) {
            if (value == '\n') {
                String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
                return line.isEmpty() ? readLine(inputStream) : line;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        return null;
    }

    private String displayName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException exception) {
            return "Android";
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private boolean hasAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(discoveryReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }

    private void onError(String reason) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(reason);
            }
        });
    }

    private void notifyScanStarted() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onScanStarted();
            }
        });
    }

    private void notifyScanFinished() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onScanFinished();
            }
        });
    }

    private void notifyCandidates() {
        List<Candidate> snapshot;
        synchronized (candidates) {
            snapshot = new ArrayList<>(candidates);
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onCandidates(snapshot);
            }
        });
    }

    private void notifyPinChallenge(String deviceName, String pin) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPinChallenge(deviceName, pin);
            }
        });
    }

    private void notifyPinRequest(Candidate candidate) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPinRequest(candidate);
            }
        });
    }

    private void notifyPinResult(boolean success, int attemptsLeft) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPinResult(success, attemptsLeft);
            }
        });
    }

    private void notifyPinFinished(String deviceName, boolean success) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPinFinished(deviceName, success);
            }
        });
    }

    private void notifyPeers() {
        List<String> names = peerNames();
        boolean changed = names.size() != lastPeerCount;
        lastPeerCount = names.size();
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPeersChanged(names);
                if (changed && names.isEmpty()) {
                    listener.onError("empty");
                }
            }
        });
    }

    private void notifyMessage(String line) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onMessage(line);
            }
        });
    }
}