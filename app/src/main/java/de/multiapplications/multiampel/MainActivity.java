package de.multiapplications.multiampel;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String PHASE_RED = "red";
    private static final String PHASE_RED_YELLOW = "red+yellow";
    private static final String PHASE_GREEN = "green";
    private static final String PHASE_YELLOW = "yellow";
    private static final long TRANSITION_DURATION = 1500L;
    private static final long OVERLAY_DURATION = 700L;
    private static final long DOUBLE_TAP_TIMEOUT = 300L;
    private static final float SWIPE_THRESHOLD_DP = 48f;
    private static final long SHEET_ENTER_DURATION = 400L;
    private static final long SHEET_EXIT_DURATION = 200L;
    private static final int REQUEST_BLUETOOTH = 41;
    private static final int REQUEST_VISIBILITY = 42;
    private static final float PHASE_DEFAULT_SECONDS = 12.8f;
    private static final float CIRCUIT_MIN_SECONDS = 2f;
    private static final float CIRCUIT_MAX_SECONDS = 30f;

    private final PathInterpolator emphasizedDecelerate = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private final PathInterpolator emphasizedAccelerate = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private final Handler phaseHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom secureRandom = new SecureRandom();
    private final List<BluetoothLink.Candidate> scanResults = new ArrayList<>();
    private final List<String> peerNames = new ArrayList<>();

    private View root;
    private View lightScaler;
    private View lightHousing;
    private View dimView;
    private View lockOverlay;
    private ImageView lockIcon;
    private ImageView circuitBadge;
    private View scrim;
    private View bottomSheet;
    private TextView phaseValue;
    private TextView languageValue;
    private MaterialButton circuitButton;
    private MaterialButton circuitInfoButton;
    private MaterialSwitch visibilitySwitch;
    private MaterialSwitch pinSecuritySwitch;
    private boolean pinSecurityUpdating;
    private TrafficLightView redLight;
    private TrafficLightView yellowLight;
    private TrafficLightView greenLight;
    private BluetoothLink bluetoothLink;
    private AlertDialog flowDialog;
    private LinearLayout flowContainer;
    private boolean flowRefreshPosted;
    private long flowShownAt;
    private AlertDialog pinInputDialog;
    private TextInputLayout pinInputLayout;
    private AlertDialog pinHostDialog;
    private AlertDialog requestDialog;
    private AlertDialog waitDialog;
    private AlertDialog languageDialog;
    private CircuitPreset circuitPreset = CircuitPreset.CROSSING;
    private long[] circuitDurations = circuitPreset.defaultDurations();
    private float circuitGreenSeconds = 8f;
    private int circuitPhaseIndex;
    private int deviceIndex;
    private boolean circuitRunning;
    private boolean remoteMode;
    private boolean scanning;
    private CircularProgressIndicator scanProgress;
    private MaterialTextView scanHint;
    private int flowStep = 1;
    private int acceptedPeers;
    private int expectedPeers;
    private String sessionId;
    private String requestSession;
    private String pinHostDevice;
    private String hostName;
    private String renderedFlowState;
    private boolean isLocked;
    private boolean isSheetOpen;
    private boolean overlayActive;
    private boolean visibilityUpdating;
    private boolean visibilityPending;
    private String currentPhase = PHASE_RED;
    private long phaseDuration = (long) (PHASE_DEFAULT_SECONDS * 1000f);
    private long nextSwitchAt;
    private float swipeThreshold;
    private float touchX;
    private float touchY;
    private boolean swipeDone;
    private long lastTapAt;
    private float sheetDragFrom;
    private boolean sheetDragging;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);

        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = 1.0f;
        getWindow().setAttributes(layoutParams);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat windowInsetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        lightScaler = findViewById(R.id.light_scaler);
        lightHousing = findViewById(R.id.light_housing);
        dimView = findViewById(R.id.dim_view);
        lockOverlay = findViewById(R.id.lock_overlay);
        lockIcon = findViewById(R.id.lock_overlay);
        circuitBadge = findViewById(R.id.circuit_paired);
        scrim = findViewById(R.id.sheet_scrim);
        bottomSheet = findViewById(R.id.bottom_sheet);
        phaseValue = findViewById(R.id.phase_value);
        languageValue = findViewById(R.id.language_value);
        circuitButton = findViewById(R.id.circuit_bluetooth);
        circuitInfoButton = findViewById(R.id.circuit_info);
        visibilitySwitch = findViewById(R.id.bluetooth_visibility);
        pinSecuritySwitch = findViewById(R.id.pin_security);

        redLight = findViewById(R.id.light_red);
        yellowLight = findViewById(R.id.light_yellow);
        greenLight = findViewById(R.id.light_green);
        redLight.setLightColor(ContextCompat.getColor(this, R.color.light_red));
        yellowLight.setLightColor(ContextCompat.getColor(this, R.color.light_yellow));
        greenLight.setLightColor(ContextCompat.getColor(this, R.color.light_green));

        phaseValue.setText(formatSeconds(PHASE_DEFAULT_SECONDS));
        swipeThreshold = SWIPE_THRESHOLD_DP * getResources().getDisplayMetrics().density;
        languageValue.setText(getString("en".equals(AppLocale.language(this))
                ? R.string.language_english
                : R.string.language_german));

        bluetoothLink = new BluetoothLink(this);
        bluetoothLink.setListener(new BluetoothLink.Listener() {
            @Override
            public void onScanStarted() {
                scanning = true;
                updateScanHint();
                refreshFlow();
            }

            @Override
            public void onScanFinished() {
                scanning = false;
                updateScanHint();
                refreshFlow();
            }

            @Override
            public void onCandidates(List<BluetoothLink.Candidate> candidates) {
                scanResults.clear();
                scanResults.addAll(candidates);
                refreshFlow();
            }

            @Override
            public void onPinChallenge(String deviceName, String pin) {
                showPinChallenge(deviceName, pin);
            }

            @Override
            public void onPinRequest(BluetoothLink.Candidate candidate) {
                showPinInput(candidate);
            }

            @Override
            public void onPinResult(boolean success, int attemptsLeft) {
                if (success) {
                    if (pinInputDialog != null && pinInputDialog.isShowing()) {
                        pinInputDialog.dismiss();
                    }
                } else if (pinInputLayout != null) {
                    pinInputLayout.setError(getString(R.string.pin_wrong, attemptsLeft));
                }
            }

            @Override
            public void onPinFinished(String deviceName, boolean success) {
                if (pinHostDialog != null && pinHostDialog.isShowing()) {
                    pinHostDialog.dismiss();
                }
                if (pinInputDialog != null && pinInputDialog.isShowing()) {
                    pinInputDialog.dismiss();
                }
                if (success && !pinRequired()) {
                    showMessage(getString(R.string.connected_to, deviceName));
                }
                if (success && pinHostDevice != null) {
                    pinHostDevice = null;
                    if (flowDialog != null && flowDialog.isShowing()) {
                        flowDialog.dismiss();
                    }
                }
                if (!success) {
                    pinHostDevice = null;
                    showMessage(getString(R.string.pin_denied));
                }
            }

            @Override
            public void onPeersChanged(List<String> peers) {
                List<String> gone = new ArrayList<>(peerNames);
                gone.removeAll(peers);
                peerNames.clear();
                peerNames.addAll(peers);
                updateCircuitButton();
                if (!gone.isEmpty()) {
                    handleMemberLeft(gone.get(0));
                }
                refreshFlow();
            }

            @Override
            public void onMessage(String line) {
                handleCircuitMessage(line);
            }

            @Override
            public void onError(String reason) {
                if (pinInputDialog != null && pinInputDialog.isShowing()) {
                    pinInputDialog.dismiss();
                }
                if ("empty".equals(reason)) {
                    return;
                }
                if ("peer".equals(reason)) {
                    showMessage(getString(R.string.not_multiampel));
                    return;
                }
                showMessage(getString(R.string.connection_failed));
            }
        });

        scrim.setOnClickListener(v -> closeSheet());

        bottomSheet.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sheetDragFrom = event.getRawY();
                    sheetDragging = true;
                    bottomSheet.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (sheetDragging) {
                        bottomSheet.setTranslationY(Math.max(0f, event.getRawY() - sheetDragFrom));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (sheetDragging) {
                        sheetDragging = false;
                        if (bottomSheet.getTranslationY() > sheetHeight() * 0.3f) {
                            closeSheet();
                        } else {
                            bottomSheet.animate()
                                    .translationY(0f)
                                    .setDuration(SHEET_ENTER_DURATION)
                                    .setInterpolator(emphasizedDecelerate)
                                    .start();
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });

        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            applyFitScale();
            if (!isSheetOpen) {
                bottomSheet.setTranslationY(sheetHeight());
            }
        });

        root.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = event.getX();
                    touchY = event.getY();
                    swipeDone = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swipeDone) {
                        float dx = event.getX() - touchX;
                        float dy = event.getY() - touchY;
                        if (Math.abs(dy) > swipeThreshold && Math.abs(dy) > Math.abs(dx)) {
                            swipeDone = true;
                            if (dy < 0f) {
                                openSheet();
                            } else {
                                closeSheet();
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!swipeDone) {
                        handleTap();
                    }
                    return true;
                default:
                    return true;
            }
        });

        Slider phaseSlider = findViewById(R.id.phase_slider);
        phaseSlider.addOnChangeListener((slider, value, fromUser) -> {
            phaseValue.setText(formatSeconds(value));
            phaseDuration = (long) (value * 1000f);
            if (!circuitRunning && !remoteMode) {
                run();
            }
        });
        findViewById(R.id.phase_reset).setOnClickListener(v -> phaseSlider.setValue(PHASE_DEFAULT_SECONDS));

        findViewById(R.id.language_row).setOnClickListener(v -> showLanguageDialog());
        circuitInfoButton.setOnClickListener(v -> showCircuitInfo());
        circuitButton.setOnClickListener(v -> onCircuitPressed());
        visibilitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onVisibilityChanged(isChecked));
        pinSecuritySwitch.setChecked(pinRequired());
        bluetoothLink.setPinRequired(pinRequired());
        pinSecuritySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onPinSecurityChanged(isChecked));
        updateCircuitButton();

        bottomSheet.post(this::limitSheetHeight);
        run();
    }

    private void onCircuitPressed() {
        if (circuitRunning && !peerNames.isEmpty()) {
            if (remoteMode) {
                ask(R.string.circuit_leave_title, R.string.circuit_leave_question, R.string.circuit_leave, this::leaveCircuit);
            } else {
                ask(R.string.circuit_dissolve_title, R.string.circuit_dissolve_question, R.string.circuit_dissolve, this::dissolveCircuit);
            }
            return;
        }
        if (circuitRunning) {
            ask(R.string.circuit_running_title, R.string.circuit_stop_question, R.string.circuit_end, this::stopCircuit);
            return;
        }
        if (!bluetoothLink.isAvailable()) {
            showMessage(getString(R.string.bluetooth_unavailable));
            return;
        }
        if (!ensureBluetooth(REQUEST_BLUETOOTH, false)) {
            return;
        }
        enableVisibilityForCircuit();
        openFlow();
    }

    private void enableVisibilityForCircuit() {
        if (bluetoothLink == null || bluetoothLink.isVisible()) {
            return;
        }
        if (!hasBluetoothPermissions()) {
            visibilityPending = true;
            ensureBluetooth(REQUEST_VISIBILITY, true);
            return;
        }
        visibilityPending = false;
        setVisibilityChecked(true);
        bluetoothLink.setVisible(true);
    }

    private void dissolveCircuit() {
        stopCircuit();
        bluetoothLink.disconnectAll();
        onVisibilityChanged(false);
        setVisibilityChecked(false);
        closeSheet();
    }

    private void leaveCircuit() {
        bluetoothLink.broadcast("QUIT|" + bluetoothLink.ownName());
        stopRemoteCircuit();
        closeSheet();
        uiHandler.postDelayed(() -> {
            bluetoothLink.disconnectAll();
            onVisibilityChanged(false);
            setVisibilityChecked(false);
        }, 300L);
    }

    private void handleMemberLeft(String name) {
        if (!circuitRunning) {
            return;
        }
        if (remoteMode) {
            stopRemoteCircuit();
        } else {
            stopCircuit();
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.circuit_left_title)
                .setMessage(getString(R.string.circuit_left_message, name))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showCircuitInfo() {
        String host = hostName == null || hostName.isEmpty() ? bluetoothLink.ownName() : hostName;
        List<String> members = new ArrayList<>();
        members.add(host);
        for (String peer : peerNames) {
            if (!members.contains(peer)) {
                members.add(peer);
            }
        }
        if (!members.contains(bluetoothLink.ownName())) {
            members.add(bluetoothLink.ownName());
        }
        SpannableStringBuilder info = new SpannableStringBuilder()
                .append(getString(R.string.info_type, presetName(circuitPreset)))
                .append('\n')
                .append(getString(R.string.info_devices, members.size()));
        for (String name : members) {
            info.append('\n');
            if (name.equals(host)) {
                int start = info.length();
                info.append(getString(R.string.info_host_label));
                info.setSpan(new ForegroundColorSpan(MaterialColors.getColor(circuitInfoButton,
                                com.google.android.material.R.attr.colorError)),
                        start, info.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                info.append(' ');
            }
            String address = bluetoothLink.addressOf(name);
            info.append(address.isEmpty() ? name : getString(R.string.info_member, name, address));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.circuit_info)
                .setMessage(info)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void ask(int titleRes, int messageRes, int positiveRes, Runnable action) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(positiveRes, (dialog, which) -> action.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateCircuitButton() {
        boolean dissolve = circuitRunning && !peerNames.isEmpty();
        circuitInfoButton.setVisibility(circuitRunning ? View.VISIBLE : View.GONE);
        if (dissolve) {
            circuitButton.setText(remoteMode ? R.string.circuit_leave : R.string.circuit_dissolve);
        } else {
            circuitButton.setText(R.string.circuit_via_bluetooth);
        }
        circuitButton.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(circuitButton,
                dissolve ? com.google.android.material.R.attr.colorError : com.google.android.material.R.attr.colorPrimary)));
        circuitButton.setTextColor(MaterialColors.getColor(circuitButton,
                dissolve ? com.google.android.material.R.attr.colorOnError : com.google.android.material.R.attr.colorOnPrimary));
    }

    private void onVisibilityChanged(boolean enable) {
        if (visibilityUpdating) {
            return;
        }
        if (!enable) {
            visibilityPending = false;
            if (bluetoothLink.isVisible()) {
                bluetoothLink.setVisible(false);
                showMessage(getString(R.string.visibility_disabled));
            }
            return;
        }
        if (!bluetoothLink.isAvailable()) {
            visibilityPending = false;
            setVisibilityChecked(false);
            showMessage(getString(R.string.bluetooth_unavailable));
            return;
        }
        if (!ensureBluetooth(REQUEST_VISIBILITY, true)) {
            visibilityPending = true;
            setVisibilityChecked(false);
            return;
        }
        visibilityPending = false;
        setVisibilityChecked(true);
        bluetoothLink.setVisible(true);
        showMessage(getString(R.string.visibility_enabled));
    }

    private void setVisibilityChecked(boolean checked) {
        visibilityUpdating = true;
        visibilitySwitch.setChecked(checked);
        visibilityUpdating = false;
    }

    private boolean pinRequired() {
        return getSharedPreferences("multiampel_settings", MODE_PRIVATE).getBoolean("pin_required", true);
    }

    private void savePinRequired(boolean required) {
        getSharedPreferences("multiampel_settings", MODE_PRIVATE)
                .edit()
                .putBoolean("pin_required", required)
                .apply();
        bluetoothLink.setPinRequired(required);
    }

    private void setPinSecurityChecked(boolean checked) {
        pinSecurityUpdating = true;
        pinSecuritySwitch.setChecked(checked);
        pinSecurityUpdating = false;
    }

    private void onPinSecurityChanged(boolean enabled) {
        if (pinSecurityUpdating) {
            return;
        }
        if (enabled) {
            savePinRequired(true);
            showMessage(getString(R.string.pin_security_on));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pin_security_off_title)
                .setMessage(R.string.pin_security_off_message)
                .setPositiveButton(R.string.pin_security_disable, (dialog, which) -> savePinRequired(false))
                .setNegativeButton(R.string.cancel, (dialog, which) -> setPinSecurityChecked(true))
                .show();
    }

    private boolean hasBluetoothPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
            addMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addMissing(missing, Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            addMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing.isEmpty();
    }

    private boolean ensureBluetooth(int requestCode, boolean advertise) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
            addMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            if (advertise) {
                addMissing(missing, Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            addMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), requestCode);
            return false;
        }
        if (!bluetoothLink.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return false;
        }
        return true;
    }

    private void addMissing(List<String> missing, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    private void openFlow() {
        if (flowDialog != null && flowDialog.isShowing()) {
            return;
        }
        flowStep = 1;
        flowContainer = new LinearLayout(this);
        flowContainer.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        flowContainer.setPadding(padding, padding, padding, padding);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.addView(flowContainer);

        flowDialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_label, null)
                .create();
        flowDialog.setOnDismissListener(dialog -> {
            bluetoothLink.stopScan();
            scanning = false;
            bluetoothLink.disconnectAll();
        });
        buildFlowContent();
        flowShownAt = SystemClock.uptimeMillis();
        flowDialog.show();
        renderFlow();
        bluetoothLink.startScan();
    }

    private void renderFlow() {
        if (flowDialog == null || !flowDialog.isShowing() || flowContainer == null) {
            return;
        }
        buildFlowContent();
        Button positive = flowDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (flowStep == 3) {
            positive.setText(R.string.start_circuit);
        } else if (flowStep == 1 && !peerNames.isEmpty()) {
            positive.setText(getResources().getQuantityString(R.plurals.continue_count, peerNames.size(), peerNames.size()));
        } else {
            positive.setText(R.string.continue_label);
        }
        positive.setOnClickListener(v -> flowNext());
        positive.setEnabled(flowStepComplete());

        Button negative = flowDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negative.setText(flowStep == 1 ? R.string.cancel : R.string.back);
        negative.setOnClickListener(v -> flowBack());
        renderedFlowState = flowState();
    }

    private void buildFlowContent() {
        if (flowContainer == null) {
            return;
        }
        flowContainer.removeAllViews();
        if (flowStep == 1) {
            buildConnectStep();
        } else if (flowStep == 2) {
            buildTypeStep();
        } else {
            buildTimesStep();
        }
    }

    private void refreshFlow() {
        if (flowDialog == null || !flowDialog.isShowing() || flowContainer == null || flowRefreshPosted) {
            return;
        }
        flowRefreshPosted = true;
        long settle = Math.max(0L, 400L - (SystemClock.uptimeMillis() - flowShownAt));
        uiHandler.postDelayed(() -> {
            flowRefreshPosted = false;
            if (flowDialog == null || !flowDialog.isShowing() || flowContainer == null) {
                return;
            }
            if (flowState().equals(renderedFlowState)) {
                return;
            }
            renderFlow();
        }, 200L + settle);
    }

    private String flowState() {
        StringBuilder state = new StringBuilder().append(flowStep).append('|').append(peerNames);
        if (flowStep == 1) {
            List<String> nearby = new ArrayList<>();
            for (BluetoothLink.Candidate candidate : scanResults) {
                if (!peerNames.contains(candidate.name)) {
                    nearby.add(candidate.name);
                }
            }
            Collections.sort(nearby);
            state.append('|').append(nearby);
        }
        return state.toString();
    }

    private boolean flowStepComplete() {
        if (flowStep == 1) {
            return !peerNames.isEmpty();
        }
        int devices = peerNames.size() + 1;
        return devices >= circuitPreset.minDevices && devices <= circuitPreset.maxDevices;
    }

    private void flowBack() {
        if (flowStep > 1) {
            flowStep--;
            renderFlow();
        } else if (flowDialog != null && flowDialog.isShowing()) {
            flowDialog.dismiss();
        }
    }

    private void flowNext() {
        if (flowStep == 1) {
            flowStep = 2;
            renderFlow();
        } else if (flowStep == 2) {
            flowStep = 3;
            renderFlow();
        } else {
            sendCircuitRequest();
        }
    }

    private void updateScanHint() {
        if (scanHint == null) {
            return;
        }
        scanHint.setText(getString(scanning ? R.string.connect_scanning : R.string.connect_scan_empty));
        if (scanProgress != null) {
            scanProgress.setVisibility(scanning ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void buildConnectStep() {
        flowContainer.addView(label(getString(R.string.step_indicator, 1, 3),
                R.style.TextAppearance_Material3_LabelLarge, com.google.android.material.R.attr.colorPrimary, 0, 4));
        flowContainer.addView(label(getString(R.string.connect_title),
                R.style.TextAppearance_Material3_HeadlineSmall, com.google.android.material.R.attr.colorOnSurface, 0, 12));
        flowContainer.addView(label(getString(R.string.connect_nearby),
                R.style.TextAppearance_Material3_TitleSmall, com.google.android.material.R.attr.colorOnSurfaceVariant, 8, 4));

        List<BluetoothLink.Candidate> nearby = new ArrayList<>();
        for (BluetoothLink.Candidate candidate : scanResults) {
            if (!peerNames.contains(candidate.name)) {
                nearby.add(candidate);
            }
        }

        scanProgress = null;
        scanHint = null;
        if (nearby.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            CircularProgressIndicator progress = new CircularProgressIndicator(this);
            progress.setIndeterminate(true);
            progress.setVisibility(scanning ? View.VISIBLE : View.INVISIBLE);
            row.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
            MaterialTextView hint = label(getString(scanning ? R.string.connect_scanning : R.string.connect_scan_empty),
                    R.style.TextAppearance_Material3_BodyMedium, com.google.android.material.R.attr.colorOnSurfaceVariant, 0, 0);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.leftMargin = dp(12);
            hint.setLayoutParams(hintParams);
            row.addView(hint);
            scanProgress = progress;
            scanHint = hint;
            flowContainer.addView(row);
        } else {
            for (BluetoothLink.Candidate candidate : nearby) {
                addRow(candidate.name, getString(R.string.connect_tap), () -> bluetoothLink.pair(candidate));
            }
        }

        MaterialDivider divider = new MaterialDivider(this);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dividerParams.topMargin = dp(16);
        dividerParams.bottomMargin = dp(8);
        divider.setLayoutParams(dividerParams);
        flowContainer.addView(divider);

        List<BluetoothLink.Candidate> bonded = bluetoothLink.bondedDevices();
        int knownCount = peerNames.size();
        for (BluetoothLink.Candidate device : bonded) {
            if (!peerNames.contains(device.name)) {
                knownCount++;
            }
        }
        flowContainer.addView(label(getString(R.string.connect_known, knownCount),
                R.style.TextAppearance_Material3_TitleSmall, com.google.android.material.R.attr.colorOnSurfaceVariant, 8, 4));

        if (knownCount == 0) {
            flowContainer.addView(label(getString(R.string.connect_known_empty),
                    R.style.TextAppearance_Material3_BodyMedium, com.google.android.material.R.attr.colorOnSurfaceVariant, 0, 0));
            return;
        }
        for (String peer : peerNames) {
            addRow(peer, getString(R.string.connect_device_connected), null, true);
        }
        for (BluetoothLink.Candidate device : bonded) {
            if (!peerNames.contains(device.name)) {
                addRow(device.name, getString(R.string.connect_tap), () -> bluetoothLink.pair(device));
            }
        }
    }

    private void buildTypeStep() {
        flowContainer.addView(label(getString(R.string.step_indicator, 2, 3),
                R.style.TextAppearance_Material3_LabelLarge, com.google.android.material.R.attr.colorPrimary, 0, 4));
        flowContainer.addView(label(getString(R.string.type_title),
                R.style.TextAppearance_Material3_HeadlineSmall, com.google.android.material.R.attr.colorOnSurface, 0, 12));

        addTypeOption(CircuitPreset.CROSSING, R.string.type_bau);
        addTypeOption(CircuitPreset.T_JUNCTION, R.string.type_t);
        addTypeOption(CircuitPreset.FOUR_WAY, R.string.type_plus);
    }

    private void addTypeOption(CircuitPreset preset, int nameRes) {
        int available = peerNames.size() + 1;
        boolean fits = available >= preset.minDevices && available <= preset.maxDevices;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        MaterialRadioButton button = radioOption(getString(R.string.type_option, getString(nameRes), preset.minDevices),
                circuitPreset == preset);
        if (fits) {
            TypedValue value = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            row.setBackgroundResource(value.resourceId);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (circuitPreset != preset) {
                    circuitPreset = preset;
                    applyPhaseDuration(circuitGreenSeconds);
                    renderFlow();
                }
            });
            button.setOnClickListener(v -> row.performClick());
        } else {
            button.setEnabled(false);
            button.setAlpha(0.38f);
        }
        row.addView(button);
        if (available < preset.minDevices) {
            row.addView(label(getString(R.string.type_more_devices, preset.minDevices - available),
                    R.style.TextAppearance_Material3_BodySmall, com.google.android.material.R.attr.colorError, 0, 6));
        } else if (available > preset.maxDevices) {
            row.addView(label(getString(R.string.type_max_devices, preset.maxDevices),
                    R.style.TextAppearance_Material3_BodySmall, com.google.android.material.R.attr.colorError, 0, 6));
        }
        flowContainer.addView(row);
    }

    private void buildTimesStep() {
        flowContainer.addView(label(getString(R.string.step_indicator, 3, 3),
                R.style.TextAppearance_Material3_LabelLarge, com.google.android.material.R.attr.colorPrimary, 0, 4));
        final MaterialTextView phaseLabel = label(getString(R.string.times_phase_value, formatSeconds(circuitGreenSeconds)),
                R.style.TextAppearance_Material3_BodyLarge, com.google.android.material.R.attr.colorOnSurface, 0, 12);
        flowContainer.addView(phaseLabel);

        if (peerNames.size() + 1 < circuitPreset.minDevices) {
            flowContainer.addView(label(getString(R.string.devices_needed, circuitPreset.minDevices, peerNames.size() + 1),
                    R.style.TextAppearance_Material3_BodyMedium, com.google.android.material.R.attr.colorError, 0, 12));
        }

        Slider slider = new Slider(this);
        slider.setValueFrom(CIRCUIT_MIN_SECONDS);
        slider.setValueTo(CIRCUIT_MAX_SECONDS);
        slider.setStepSize(0.5f);
        slider.setValue(Math.min(CIRCUIT_MAX_SECONDS, Math.max(CIRCUIT_MIN_SECONDS, circuitGreenSeconds)));
        slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        slider.setTickVisible(false);
        slider.addOnChangeListener((s, seconds, fromUser) -> {
            applyPhaseDuration(seconds);
            phaseLabel.setText(getString(R.string.times_phase_value, formatSeconds(seconds)));
        });
        flowContainer.addView(slider);
    }

    private void applyPhaseDuration(float seconds) {
        circuitGreenSeconds = seconds;
        long green = (long) (seconds * 1000f);
        long transition = Math.max(1000L, Math.round(green * 0.25f));
        int devices = Math.max(1, peerNames.size() + 1);
        circuitDurations = new long[devices * 3];
        for (int i = 0; i < circuitDurations.length; i++) {
            circuitDurations[i] = i % 3 == 1 ? green : transition;
        }
    }

    private int circuitColorFor(int phase) {
        int devices = Math.max(1, circuitDurations.length / 3);
        int slot = ((phase / 3) % devices + devices) % devices;
        if (deviceIndex != slot) {
            return CircuitPreset.RED;
        }
        int step = ((phase % 3) + 3) % 3;
        if (step == 1) {
            return CircuitPreset.GREEN;
        }
        return step == 0 ? CircuitPreset.RED_YELLOW : CircuitPreset.YELLOW;
    }

    private MaterialRadioButton radioOption(String label, boolean checked) {
        MaterialRadioButton button = new MaterialRadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setChecked(checked);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setTextAppearance(R.style.TextAppearance_Material3_BodyLarge);
        button.setButtonTintList(accentTint());
        return button;
    }

    private ColorStateList accentTint() {
        int accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xff007aff);
        int idle = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff44474e);
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, idle});
    }

    private void sendCircuitRequest() {
        int devices = peerNames.size() + 1;
        if (devices < circuitPreset.minDevices) {
            showMessage(getString(R.string.devices_needed, circuitPreset.minDevices, devices));
            return;
        }
        if (devices > circuitPreset.maxDevices) {
            showMessage(getString(R.string.type_max_devices, circuitPreset.maxDevices));
            return;
        }
        applyPhaseDuration(circuitGreenSeconds);
        expectedPeers = peerNames.size();
        acceptedPeers = 0;
        sessionId = String.format(Locale.US, "%06d", secureRandom.nextInt(1000000));
        bluetoothLink.broadcast("REQUEST|" + circuitPreset.ordinal() + "|" + durationsCsv() + "|" + bluetoothLink.ownName() + "|" + sessionId);
        bluetoothLink.stopScan();
        scanning = false;
        if (flowDialog != null && flowDialog.isShowing()) {
            flowDialog.setOnDismissListener(null);
            flowDialog.dismiss();
        }
        waitDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wait_title)
                .setMessage(getString(R.string.wait_message, acceptedPeers, expectedPeers))
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    bluetoothLink.broadcast("CANCEL|" + sessionId);
                    sessionId = null;
                })
                .create();
        waitDialog.show();
    }

    private void handleCircuitMessage(String line) {
        String[] parts = line.split("\\|", -1);
        try {
            switch (parts[0]) {
                case "REQUEST":
                    if (parts.length >= 5) {
                        requestSession = parts[4];
                        showRequestDialog(CircuitPreset.values()[Integer.parseInt(parts[1])],
                                parseDurations(parts[2]), parts[3], parts[4]);
                    }
                    break;
                case "ACCEPT":
                    if (parts.length >= 2 && parts[1].equals(sessionId)) {
                        acceptedPeers++;
                        if (waitDialog != null && waitDialog.isShowing()) {
                            waitDialog.setMessage(getString(R.string.wait_message, acceptedPeers, expectedPeers));
                        }
                        if (acceptedPeers >= expectedPeers) {
                            if (waitDialog != null && waitDialog.isShowing()) {
                                waitDialog.dismiss();
                            }
                            beginCircuit(true);
                        }
                    }
                    break;
                case "REJECT":
                    if (parts.length >= 2 && parts[1].equals(sessionId)) {
                        if (waitDialog != null && waitDialog.isShowing()) {
                            waitDialog.dismiss();
                        }
                        sessionId = null;
                        showMessage(getString(R.string.circuit_rejected));
                    }
                    break;
                case "CANCEL":
                    if (requestDialog != null && requestDialog.isShowing()) {
                        requestDialog.dismiss();
                    }
                    break;
                case "PEERS":
                    if (parts.length >= 2) {
                        expectedPeers = Math.max(expectedPeers, Integer.parseInt(parts[1]));
                        if (waitDialog != null && waitDialog.isShowing()) {
                            waitDialog.setMessage(getString(R.string.wait_message, acceptedPeers, expectedPeers));
                        }
                    }
                    break;
                case "RUN":
                    if (parts.length >= 3) {
                        circuitPreset = CircuitPreset.values()[Integer.parseInt(parts[1])];
                        circuitDurations = parseDurations(parts[2]);
                        deviceIndex = bluetoothLink.ownIndex();
                        beginCircuit(false);
                    }
                    break;
                case "PHASE":
                    if (parts.length >= 2 && circuitRunning) {
                        circuitPhaseIndex = Integer.parseInt(parts[1]);
                        applyColorCode(circuitColorFor(circuitPhaseIndex));
                    }
                    break;
                case "STOP":
                    stopRemoteCircuit();
                    break;
                case "QUIT":
                    if (parts.length >= 2) {
                        handleMemberLeft(parts[1]);
                    }
                    break;
                default:
                    break;
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
        }
    }

    private void showRequestDialog(CircuitPreset preset, long[] durations, String master, String session) {
        if (requestDialog != null && requestDialog.isShowing()) {
            requestDialog.dismiss();
        }
        hostName = master;
        long phase = 0;
        for (long duration : durations) {
            phase = Math.max(phase, duration);
        }
        String message = getString(R.string.request_message, master, presetName(preset), preset.minDevices)
                + "\n\n" + getString(R.string.request_times, formatSeconds(phase / 1000f));
        requestDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.request_title)
                .setMessage(message)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    circuitPreset = preset;
                    circuitDurations = durations;
                    bluetoothLink.sendToHost("ACCEPT|" + session);
                })
                .setNegativeButton(R.string.reject, (dialog, which) -> bluetoothLink.sendToHost("REJECT|" + session))
                .create();
        requestDialog.show();
    }

    private void beginCircuit(boolean master) {
        remoteMode = !master;
        circuitRunning = true;
        circuitPhaseIndex = 0;
        deviceIndex = bluetoothLink.ownIndex();
        circuitBadge.setVisibility(View.VISIBLE);
        updateCircuitButton();
        phaseHandler.removeCallbacksAndMessages(null);
        if (master) {
            hostName = bluetoothLink.ownName();
            bluetoothLink.broadcast("RUN|" + circuitPreset.ordinal() + "|" + durationsCsv());
            runCircuitPhase();
        } else {
            applyColorCode(circuitColorFor(0));
        }
    }

    private void runCircuitPhase() {
        int phases = Math.max(1, circuitDurations.length);
        circuitPhaseIndex = ((circuitPhaseIndex % phases) + phases) % phases;
        applyColorCode(circuitColorFor(circuitPhaseIndex));
        long duration = circuitDurations[circuitPhaseIndex];
        bluetoothLink.broadcast("PHASE|" + circuitPhaseIndex + "|" + duration);
        nextSwitchAt = SystemClock.uptimeMillis() + duration;
        phaseHandler.removeCallbacksAndMessages(null);
        phaseHandler.postDelayed(() -> {
            circuitPhaseIndex = (circuitPhaseIndex + 1) % phases;
            runCircuitPhase();
        }, duration);
    }

    private void stopCircuit() {
        circuitRunning = false;
        remoteMode = false;
        circuitBadge.setVisibility(View.GONE);
        updateCircuitButton();
        phaseHandler.removeCallbacksAndMessages(null);
        bluetoothLink.broadcast("STOP|" + (sessionId == null ? "0" : sessionId));
        sessionId = null;
        currentPhase = PHASE_RED;
        run();
    }

    private void stopRemoteCircuit() {
        circuitRunning = false;
        remoteMode = false;
        circuitBadge.setVisibility(View.GONE);
        updateCircuitButton();
        phaseHandler.removeCallbacksAndMessages(null);
        currentPhase = PHASE_RED;
        run();
    }

    private String durationsCsv() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < circuitDurations.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(circuitDurations[i]);
        }
        return builder.toString();
    }

    private long[] parseDurations(String value) {
        String[] parts = value.split(",");
        long[] durations = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            durations[i] = Long.parseLong(parts[i].trim());
        }
        return durations;
    }

    private String formatDurations(long[] durations) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < durations.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(formatSeconds(durations[i] / 1000f));
        }
        return builder.toString();
    }

    private String presetName(CircuitPreset preset) {
        switch (preset) {
            case T_JUNCTION:
                return getString(R.string.type_t);
            case FOUR_WAY:
                return getString(R.string.type_plus);
            default:
                return getString(R.string.type_bau);
        }
    }

    private void showPinChallenge(String deviceName, String pin) {
        pinHostDevice = deviceName;
        if (pinHostDialog != null && pinHostDialog.isShowing()) {
            pinHostDialog.dismiss();
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        container.setPadding(padding, dp(8), padding, dp(8));
        container.addView(label(getString(R.string.pin_host_message),
                R.style.TextAppearance_Material3_BodyMedium, com.google.android.material.R.attr.colorOnSurfaceVariant, 0, 12));
        MaterialTextView pinView = label(pin, R.style.TextAppearance_Material3_DisplayMedium,
                com.google.android.material.R.attr.colorPrimary, 0, 0);
        pinView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        container.addView(pinView);

        pinHostDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pin_host_title, deviceName))
                .setView(container)
                .setNegativeButton(R.string.cancel, (dialog, which) -> bluetoothLink.declineChallenges())
                .create();
        pinHostDialog.show();
    }

    private void showPinInput(BluetoothLink.Candidate candidate) {
        if (pinInputDialog != null && pinInputDialog.isShowing()) {
            pinInputDialog.dismiss();
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        container.setPadding(padding, dp(8), padding, dp(8));
        container.addView(label(getString(R.string.pin_message, candidate.name),
                R.style.TextAppearance_Material3_BodyMedium, com.google.android.material.R.attr.colorOnSurfaceVariant, 0, 12));

        pinInputLayout = new TextInputLayout(this);
        pinInputLayout.setHint(getString(R.string.pin_hint));
        pinInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(pinInputLayout.getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setMaxLines(1);
        pinInputLayout.addView(input);
        container.addView(pinInputLayout);

        pinInputDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pin_title)
                .setView(container)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    CharSequence code = input.getText();
                    bluetoothLink.submitPin(code == null ? "" : code.toString().trim());
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> bluetoothLink.cancelPending())
                .create();
        pinInputDialog.show();
        if (pinInputDialog.getWindow() != null) {
            pinInputDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.requestFocus();
        input.post(() -> {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) {
                keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private MaterialTextView label(CharSequence text, int styleRes, int colorAttr, int topDp, int bottomDp) {
        MaterialTextView view = new MaterialTextView(this);
        view.setText(text);
        view.setTextAppearance(styleRes);
        view.setTextColor(MaterialColors.getColor(view, colorAttr));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        view.setLayoutParams(params);
        return view;
    }

    private void addRow(String title, String subtitle, Runnable action) {
        addRow(title, subtitle, action, false);
    }

    private void addRow(String title, String subtitle, Runnable action, boolean highlight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        MaterialTextView titleView = new MaterialTextView(this);
        titleView.setText(title);
        titleView.setTextAppearance(R.style.TextAppearance_Material3_BodyLarge);
        titleView.setTextColor(MaterialColors.getColor(titleView, com.google.android.material.R.attr.colorOnSurface));
        row.addView(titleView);

        if (subtitle != null) {
            MaterialTextView subtitleView = new MaterialTextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextAppearance(R.style.TextAppearance_Material3_BodySmall);
            subtitleView.setTextColor(highlight
                    ? ContextCompat.getColor(this, R.color.light_green)
                    : MaterialColors.getColor(subtitleView, com.google.android.material.R.attr.colorOnSurfaceVariant));
            row.addView(subtitleView);
        }

        if (action != null) {
            TypedValue value = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            row.setBackgroundResource(value.resourceId);
            row.setClickable(true);
            row.setOnClickListener(v -> action.run());
        }
        flowContainer.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String message) {
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show();
    }

    private void showLanguageDialog() {
        String[] codes = {"de", "en"};
        String[] labels = {getString(R.string.language_german), getString(R.string.language_english)};
        String current = AppLocale.language(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), dp(8));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < codes.length; i++) {
            group.addView(radioOption(labels[i], codes[i].equals(current)));
        }
        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            int index = radioGroup.indexOfChild(radioGroup.findViewById(checkedId));
            if (index < 0) {
                return;
            }
            if (languageDialog != null && languageDialog.isShowing()) {
                languageDialog.dismiss();
            }
            if (!codes[index].equals(AppLocale.language(MainActivity.this))) {
                AppLocale.setLanguage(MainActivity.this, codes[index]);
                recreate();
            }
        });
        container.addView(group);

        languageDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_language)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .create();
        languageDialog.show();
    }

    private void applyColorCode(int code) {
        switch (code) {
            case CircuitPreset.RED_YELLOW:
                applyLights(true, true, false);
                currentPhase = PHASE_RED_YELLOW;
                break;
            case CircuitPreset.GREEN:
                applyLights(false, false, true);
                currentPhase = PHASE_GREEN;
                break;
            case CircuitPreset.YELLOW:
                applyLights(false, true, false);
                currentPhase = PHASE_YELLOW;
                break;
            default:
                applyLights(true, false, false);
                currentPhase = PHASE_RED;
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
            }
        }
        if (!granted) {
            if (requestCode == REQUEST_VISIBILITY) {
                visibilityPending = false;
                setVisibilityChecked(false);
            }
            showMessage(getString(R.string.bluetooth_permission));
            return;
        }
        if (requestCode == REQUEST_BLUETOOTH) {
            openFlow();
        } else if (requestCode == REQUEST_VISIBILITY && visibilityPending) {
            onVisibilityChanged(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (visibilityPending && bluetoothLink != null && bluetoothLink.isEnabled() && hasBluetoothPermissions()) {
            onVisibilityChanged(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        phaseHandler.removeCallbacksAndMessages(null);
        uiHandler.removeCallbacksAndMessages(null);
        if (bluetoothLink != null) {
            bluetoothLink.close();
        }
    }

    private void limitSheetHeight() {
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.9f);
        if (bottomSheet.getHeight() <= maxHeight) {
            return;
        }
        ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
        params.height = maxHeight;
        bottomSheet.setLayoutParams(params);
        if (!isSheetOpen) {
            bottomSheet.setTranslationY(maxHeight);
        }
    }

    private void handleTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTapAt < DOUBLE_TAP_TIMEOUT) {
            lastTapAt = 0L;
            toggleLock();
        } else {
            lastTapAt = now;
        }
    }

    private String formatSeconds(float seconds) {
        return String.format(Locale.US, "%.1fs", seconds);
    }

    private void applyFitScale() {
        if (root.getWidth() == 0 || root.getHeight() == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float scale = Math.min(
                root.getWidth() * 0.9f / (120f * density),
                (root.getHeight() - 40f * density) / (360f * density));
        float fitScale = scale * 0.94f;
        lightScaler.setScaleX(fitScale);
        lightScaler.setScaleY(fitScale);
    }

    private void applyLights(boolean red, boolean yellow, boolean green) {
        redLight.setOn(red);
        yellowLight.setOn(yellow);
        greenLight.setOn(green);
    }

    private void schedulePhase(Runnable runnable, long delay) {
        phaseHandler.removeCallbacksAndMessages(null);
        nextSwitchAt = SystemClock.uptimeMillis() + delay;
        phaseHandler.postDelayed(runnable, delay);
    }

    private void run() {
        if (isLocked || circuitRunning || remoteMode) {
            return;
        }
        if (PHASE_RED.equals(currentPhase)) {
            applyLights(true, false, false);
            schedulePhase(() -> {
                applyLights(true, true, false);
                schedulePhase(() -> {
                    currentPhase = PHASE_GREEN;
                    run();
                }, TRANSITION_DURATION);
            }, phaseDuration);
        } else {
            applyLights(false, false, true);
            schedulePhase(() -> {
                applyLights(false, true, false);
                schedulePhase(() -> {
                    currentPhase = PHASE_RED;
                    run();
                }, TRANSITION_DURATION);
            }, phaseDuration);
        }
    }

    private void toggleLock() {
        if (overlayActive || isSheetOpen) {
            return;
        }
        isLocked = !isLocked;
        setUiActive(true);
        lockIcon.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
        showOverlay();
        if (isLocked) {
            phaseHandler.removeCallbacksAndMessages(null);
            nextSwitchAt = SystemClock.uptimeMillis();
            currentPhase = PHASE_RED;
            applyLights(true, false, false);
        }
        uiHandler.postDelayed(() -> {
            hideOverlay();
            overlayActive = false;
            if (!isSheetOpen) {
                setUiActive(false);
            }
            if (!isLocked) {
                uiHandler.postDelayed(() -> {
                    applyLights(true, true, false);
                    schedulePhase(() -> {
                        currentPhase = PHASE_GREEN;
                        run();
                    }, TRANSITION_DURATION);
                }, 300L);
            }
        }, OVERLAY_DURATION);
    }

    private void setUiActive(boolean active) {
        lightHousing.animate().cancel();
        dimView.animate().cancel();
        lightHousing.animate()
                .scaleX(active ? 0.98f : 1f)
                .scaleY(active ? 0.98f : 1f)
                .setDuration(250L)
                .start();
        dimView.animate()
                .alpha(active ? 0.4f : 0f)
                .setDuration(250L)
                .start();
    }

    private void showOverlay() {
        overlayActive = true;
        lockOverlay.setVisibility(View.VISIBLE);
        lockOverlay.animate().cancel();
        lockIcon.animate().cancel();
        lockOverlay.animate().alpha(1f).setDuration(200L).start();
        lockIcon.setScaleX(0.7f);
        lockIcon.setScaleY(0.7f);
        lockIcon.animate()
                .scaleX(1.5f)
                .scaleY(1.5f)
                .setDuration(300L)
                .setInterpolator(new PathInterpolator(0.2f, 1f, 0.3f, 1f))
                .start();
    }

    private void hideOverlay() {
        lockOverlay.animate().cancel();
        lockIcon.animate().cancel();
        lockOverlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction(() -> lockOverlay.setVisibility(View.INVISIBLE))
                .start();
        lockIcon.animate()
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(300L)
                .setInterpolator(new PathInterpolator(0.2f, 1f, 0.3f, 1f))
                .start();
    }

    private float sheetHeight() {
        return Math.max(bottomSheet.getHeight(), bottomSheet.getMeasuredHeight());
    }

    private void openSheet() {
        if (isSheetOpen) {
            return;
        }
        isSheetOpen = true;
        bottomSheet.animate().cancel();
        scrim.animate().cancel();
        bottomSheet.setTranslationY(sheetHeight());
        bottomSheet.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        bottomSheet.animate()
                .translationY(0f)
                .setDuration(SHEET_ENTER_DURATION)
                .setInterpolator(emphasizedDecelerate)
                .start();
        scrim.animate()
                .alpha(1f)
                .setDuration(SHEET_ENTER_DURATION)
                .setInterpolator(emphasizedDecelerate)
                .start();
        setUiActive(true);
    }

    private void closeSheet() {
        if (!isSheetOpen) {
            return;
        }
        isSheetOpen = false;
        bottomSheet.animate().cancel();
        scrim.animate().cancel();
        bottomSheet.animate()
                .translationY(sheetHeight())
                .setDuration(SHEET_EXIT_DURATION)
                .setInterpolator(emphasizedAccelerate)
                .withEndAction(() -> bottomSheet.setVisibility(View.INVISIBLE))
                .start();
        scrim.animate()
                .alpha(0f)
                .setDuration(SHEET_EXIT_DURATION)
                .setInterpolator(emphasizedAccelerate)
                .withEndAction(() -> scrim.setVisibility(View.GONE))
                .start();
        setUiActive(false);
    }

    @Override
    public void onBackPressed() {
        if (isSheetOpen) {
            closeSheet();
        } else {
            super.onBackPressed();
        }
    }
}