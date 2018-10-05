package com.docsinclouds.glucose;

import static com.docsinclouds.glucose.Constants.SECOND_IN_MS;
import static com.docsinclouds.glucose.G5CollectionService.STATE.CLOSE;
import static com.docsinclouds.glucose.G5CollectionService.STATE.CLOSED;
import static com.docsinclouds.glucose.G5CollectionService.STATE.INIT;
import static com.docsinclouds.glucose.G5Model.BluetoothServices.getUUIDName;
import static com.docsinclouds.glucose.WebsocketService.ACTION_NEW_GLUC_DATA;
import static com.docsinclouds.glucose.WebsocketService.EXTRA_GLUCVALUE;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.docsinclouds.glucose.G5Model.BluetoothServices;
import com.docsinclouds.glucose.G5Model.CalibrationState;
import com.docsinclouds.glucose.G5Model.Extensions;
import com.docsinclouds.glucose.G5Model.G5StateMachine;
import com.docsinclouds.glucose.GlucoseDataBase.GlucoEntity;
import com.google.common.collect.Sets;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleCustomOperation;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback;
import com.polidea.rxandroidble.scan.ScanResult;
import com.polidea.rxandroidble.scan.ScanSettings;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import rx.Observable;
import rx.Scheduler;
import rx.Subscription;
import rx.schedulers.Schedulers;


/**
 * OB1 G5 collector Created by jamorham on 16/09/2017. Modified by Docs in Clouds
 * Former name: Ob1G5CollectionService
 */


public class G5CollectionService extends G5BaseService {

  public static final String TAG = G5CollectionService.class.getSimpleName();
  private static final String OB1G5_MACSTORE = "G5-mac-for-txid-";
  private static final String BUGGY_SAMSUNG_ENABLED = "buggy-samsung-enabled";
  private static volatile STATE state = INIT;
  private static volatile STATE last_automata_state = CLOSED;

  private static RxBleClient rxBleClient;
  private static volatile PendingIntent pendingIntent;

  private static String transmitterID;
  private static String transmitterMAC;
  private static String transmitterIDmatchingMAC;

  private static String lastScanError = null;
  public static String lastSensorStatus = null;
  public static CalibrationState lastSensorState = null;
  public static volatile long lastUsableGlucosePacketTime = 0;
  private static volatile String static_connection_state = null;
  private static long static_last_connected = 0;
  private static long last_transmitter_timestamp = 0;
  private static long lastStateUpdated = 0;
  private static long wakeup_time = 0;
  private static long wakeup_jitter = 0;
  private static long max_wakeup_jitter = 0;
  private static volatile long connecting_time = 0;

  public static boolean keep_running = true;

  public static boolean android_wear = false;

  private Subscription scanSubscription;
  private Subscription connectionSubscription;
  private static volatile Subscription stateSubscription;
  private Subscription discoverSubscription;
  private RxBleDevice bleDevice;
  private RxBleConnection connection;

  private PowerManager.WakeLock connection_linger;
  private PowerManager.WakeLock scanWakeLock;
  private volatile PowerManager.WakeLock floatingWakeLock;
  private PowerManager.WakeLock fullWakeLock;

  private boolean background_launch_waiting = false;
  private static long last_scan_started = -1;
  private static int error_count = 0;
  private static int connectNowFailures = 0;
  private static int connectFailures = 0;
  private static boolean auth_succeeded = false;
  private int error_backoff_ms = 1000;
  private static final int max_error_backoff_ms = 10000;
  private static final long TOLERABLE_JITTER = 10000;

  private static final boolean d = false;

  private static boolean always_scan = false;
  private static boolean always_discover = false;
  private static boolean always_connect = false;
  private static boolean do_discovery = true;
  private static final boolean do_auth = true;
  private static boolean initiate_bonding = false;

  private static final Set<String> alwaysScanModels = Sets.newHashSet("SM-N910V", "G Watch");
  private static final List<String> alwaysScanModelFamilies = Arrays.asList("SM-N910");
  private static final Set<String> alwaysConnectModels = Sets.newHashSet("G Watch");
  private static final Set<String> alwaysBuggyWakeupModels = Sets
      .newHashSet("Jelly-Pro", "SmartWatch 3");


  private static SharedPreferences prefs;

  // Internal process state tracking
  public enum STATE {
    INIT("Initializing"),
    SCAN("Scanning"),
    CONNECT("Waiting connect"),
    CONNECT_NOW("Power connect"),
    DISCOVER("Examining"),
    CHECK_AUTH("Checking Auth"),
    PREBOND("Bond Prepare"),
    BOND("Bonding"),
    RESET("Reseting"),
    GET_DATA("Getting Data"),
    CLOSE("Sleeping"),
    CLOSED("Deep Sleeping");


    private String str;

    STATE(String custom) {
      this.str = custom;
    }

    STATE() {
      this.str = toString();
    }

    public String getString() {
      return str;
    }
  }

  public void authResult(boolean good) {
    auth_succeeded = good;
  }

  private synchronized void backoff_automata() {
    background_automata(error_backoff_ms);
    if (error_backoff_ms < max_error_backoff_ms) {
      error_backoff_ms += 100;
    }
  }

  public void background_automata() {
    background_automata(100);
  }

  public synchronized void background_automata(final int timeout) {
    if (background_launch_waiting) {
      Log.d(TAG, "Blocked by existing background automata pending");
      return;
    }
    final PowerManager.WakeLock wl = HelperClass.getWakeLock("jam-g5-background", timeout + 1000);
    background_launch_waiting = true;
    new Thread(() -> {
      HelperClass.threadSleep(timeout);
      background_launch_waiting = false;
      HelperClass.releaseWakeLock(wl);
      automata();
    }).start();
  }

  /**
   * This automata switchtes through the states to find and connect to the transmitter and transfer data from the transmitter.
   */
  private synchronized void automata() {

    if ((last_automata_state != state) || (HelperClass.ratelimit("jam-g5-dupe-auto", 2))) {
      last_automata_state = state;
      final PowerManager.WakeLock wl = HelperClass.getWakeLock("jam-g5-automata", 60000);
      try {
        switch (state) {

          case INIT:
            initialize();
            break;
          case SCAN:
            scan_for_device();
            break;
          case CONNECT_NOW:
            connect_to_device(false);
            break;
          case CONNECT:
            connect_to_device(true);
            break;
          case DISCOVER:
            if (do_discovery) {
              discover_services();
            } else {
              Log.d(TAG, "Skipping discovery");
              changeState(STATE.CHECK_AUTH);
            }
            break;
          case CHECK_AUTH:
            if (do_auth) {
              final PowerManager.WakeLock linger_wl_connect = HelperClass
                  .getWakeLock("jam-g5-check-linger", 6000);
              if (!G5StateMachine.doCheckAuth(this, connection)) {
                resetState();
              }
            } else {
              Log.d(TAG, "Skipping authentication");
              changeState(STATE.GET_DATA);
            }
            break;
          case PREBOND:
            final PowerManager.WakeLock linger_wl_prebond = HelperClass
                .getWakeLock("jam-g5-prebond-linger", 16000);
            if (!G5StateMachine.doKeepAliveAndBondRequest(this, connection)) {
              resetState();
            }
            break;
          case BOND:
            //create_bond();
            Log.d(TAG, "State bond currently does nothing");
            break;
          case RESET:
            Log.d(TAG, "Entering hard reset state");
            G5StateMachine.doReset(this, connection);
            break;
          case GET_DATA:
            if (hardResetTransmitterNow) {
              Log.e(TAG, "calling GET_DATA_if1");
              send_reset_command();
            } else {
              Log.e(TAG, "calling GET_DATA_if2");
              final PowerManager.WakeLock linger_wl_get_data = HelperClass
                  .getWakeLock("jam-g5-get-linger", 6000);
              if (!G5StateMachine.doGetData(this, connection)) {
                resetState();
              }
            }
            break;
          case CLOSE:
            prepareToWakeup();
            break;
          case CLOSED:
            handleWakeup();
            break;
        }
      } finally {
        HelperClass.releaseWakeLock(wl);
      }
    } else {
      Log.d(TAG, "Ignoring duplicate automata state within 2 seconds: " + state);
    }
  }

  private void resetState() {
    Log.e(TAG, "Resetting sequence state to INIT");
    changeState(INIT);
  }

  public STATE getState() {
    return state;
  }

  public void changeState(STATE new_state) {
    if ((state == CLOSED || state == CLOSE) && new_state == CLOSE) {
      Log.d(TAG, "Not closing as already closed");
    } else {
      Log.d(TAG, "Changing state from: " + state + " to " + new_state);
      state = new_state;
      background_automata();
    }
  }

  private synchronized void initialize() {
    if (state == INIT) {
      msg("Initializing");
      static_connection_state = null;
      if (rxBleClient == null) {
        rxBleClient = RxBleClient.create(BackgroundClass.getAppContext());
      }
      init_tx_id();
      // load prefs etc
      changeState(STATE.SCAN);
    } else {
      Log.wtf(TAG, "Attempt to initialize when not in INIT state");
    }
  }

  private static void init_tx_id() {
    transmitterID = prefs.getString(MainActivity.contextOfApplication.getResources()
        .getString(R.string.prefs_dexcomTransmitterId), "");
    //transmitterID =  "4G0W4A";
  }

  private synchronized void scan_for_device() {
    if (state == STATE.SCAN) {
      msg("Scanning");
      stopScan();
      tryLoadingSavedMAC(); // did we already find it?
      if (always_scan || (transmitterMAC == null) || (!transmitterID
          .equals(transmitterIDmatchingMAC)) || (static_last_timestamp < 1)) {
        transmitterMAC = null; // reset if set
        last_scan_started = HelperClass.tsl();
        scanWakeLock = HelperClass
            .getWakeLock("xdrip-jam-g5-scan", (int) Constants.MINUTE_IN_MS * 6);

        scanSubscription = rxBleClient.scanBleDevices(
            new ScanSettings.Builder()
                .setScanMode(static_last_timestamp < 1 ? ScanSettings.SCAN_MODE_LOW_LATENCY
                    : ScanSettings.SCAN_MODE_BALANCED)
                //.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()//,

        )
            .subscribeOn(Schedulers.io())
            .subscribe(this::onScanResult, this::onScanFailure);
        Log.d(TAG, "Scanning for: " + getTransmitterBluetoothName());
      } else {
        Log.d(TAG, "Transmitter mac already known: " + transmitterMAC);
        changeState(STATE.CONNECT);

      }
    } else {
      Log.wtf(TAG, "Attempt to scan when not in SCAN state");
    }
  }

  private synchronized void connect_to_device(boolean auto) {
    if ((state == STATE.CONNECT) || (state == STATE.CONNECT_NOW)) {

      if (transmitterMAC != null) {
        msg("Connect request");
        if (state == STATE.CONNECT_NOW) {
          if (connection_linger != null) {
            HelperClass.releaseWakeLock(connection_linger);
          }
          connection_linger = HelperClass.getWakeLock("jam-g5-pconnect", 60000);
        }
        if (d) {
          Log.d(TAG, "Local bonding state: " + (isDeviceLocallyBonded() ? "BONDED" : "NOT Bonded"));
        }
        stopConnect();

        bleDevice = rxBleClient.getBleDevice(transmitterMAC);

        /// / Listen for connection state changes
        stateSubscription = bleDevice.observeConnectionStateChanges()
            // .observeOn(AndroidSchedulers.mainThread())
            .subscribeOn(Schedulers.io())
            .subscribe(this::onConnectionStateChange, throwable -> {
              Log.wtf(TAG, "Got Error from state subscription: " + throwable);
            });

        // Attempt to establish a connection
        connectionSubscription = bleDevice.establishConnection(auto)
            .timeout(7, TimeUnit.MINUTES)
            .subscribeOn(Schedulers.io())

            .subscribe(this::onConnectionReceived, this::onConnectionFailure);

      } else {
        Log.wtf(TAG, "No transmitter mac address!");

        state = STATE.SCAN;
        backoff_automata(); // note backoff
      }

    } else {
      Log.wtf(TAG, "Attempt to connect when not in CONNECT state");
    }
  }

  private synchronized void discover_services() {
    if (state == STATE.DISCOVER) {
      if (connection != null) {
        if (d) {
          Log.d(TAG, "Local bonding state: " + (isDeviceLocallyBonded() ? "BONDED" : "NOT Bonded"));
        }
        stopDiscover();
        discoverSubscription = connection.discoverServices(10, TimeUnit.SECONDS)
            .subscribe(this::onServicesDiscovered, this::onDiscoverFailed);
      } else {
        Log.e(TAG, "No connection when in DISCOVER state - reset");
        state = INIT;
        background_automata();
      }
    } else {
      Log.wtf(TAG, "Attempt to discover when not in DISCOVER state");
    }
  }

  @TargetApi(Build.VERSION_CODES.KITKAT)
  private synchronized void create_bond() {
    if (state == STATE.BOND) {
      try {
        msg("Bonding");
        do_create_bond();
        //state = STATE.CONNECT_NOW;
        //background_automata(15000);
      } catch (Exception e) {
        Log.wtf(TAG, "Exception creating bond: " + e);
      }
    } else {
      Log.wtf(TAG, "Attempt to bond when not in BOND state");
    }
  }


  private synchronized void do_create_bond() {
    Log.d(TAG, "Attempting to create bond, device is : " + (isDeviceLocallyBonded() ? "BONDED"
        : "NOT Bonded"));
    try {
      unBond();
      instantCreateBond();
    } catch (Exception e) {
      Log.wtf(TAG, "Got exception in do_create_bond() " + e);
    }
  }

  private synchronized void send_reset_command() {
    hardResetTransmitterNow = false;
    getBatteryStatusNow = true;
    if (HelperClass.ratelimit("reset-command", 1200)) {
      Log.e(TAG, "Issuing reset command!");
      changeState(STATE.RESET);
    } else {
      Log.e(TAG, "Reset command blocked by 20 minute timer");
    }
  }

  private String getTransmitterBluetoothName() {
    final String transmitterIdLastTwo = Extensions.lastTwoCharactersOfString(transmitterID);

    return "Dexcom" + transmitterIdLastTwo;
  }

  private void tryLoadingSavedMAC() {
    if ((transmitterMAC == null) || (!transmitterIDmatchingMAC.equals(transmitterID))) {
      if (transmitterID != null) {
        final String this_mac = PersistentStore.getString(OB1G5_MACSTORE + transmitterID);
        if (this_mac.length() == 17) {
          Log.d(TAG, "Loaded stored MAC for: " + transmitterID + " " + this_mac);
          transmitterMAC = this_mac;
          transmitterIDmatchingMAC = transmitterID;
        } else {
          Log.d(TAG, "Did not find any saved MAC for: " + transmitterID);
        }
      } else {
        Log.e(TAG, "Could not load saved mac as transmitter id isn't set!");
      }
    } else {
      Log.d(TAG,
          "MAC for transmitter id already populated: " + transmitterID + " " + transmitterMAC);
    }
  }

  // should this service be running? Used to decide when to shut down
  private static boolean shouldServiceRun() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
      return false;
    }
    return true;

  }

  private static synchronized boolean isDeviceLocallyBonded() {
    if (transmitterMAC == null) {
      return false;
    }
    final Set<RxBleDevice> pairedDevices = rxBleClient.getBondedDevices();
    if ((pairedDevices != null) && (pairedDevices.size() > 0)) {
      for (RxBleDevice device : pairedDevices) {
        if ((device.getMacAddress() != null) && (device.getMacAddress().equals(transmitterMAC))) {
          return true;
        }
      }
    }
    return false;
  }

  private synchronized void checkAndEnableBT() {
    try {
      final BluetoothAdapter mBluetoothAdapter = ((BluetoothManager) getSystemService(
          Context.BLUETOOTH_SERVICE)).getAdapter();
      if (!mBluetoothAdapter.isEnabled()) {
        if (HelperClass.ratelimit("g5-enabling-bluetooth", 30)) {
          HelperClass.setBluetoothEnabled(this, true);
          Log.e(TAG, "Enabling bluetooth");
        }
      }

    } catch (Exception e) {
      Log.e(TAG, "Got exception checking BT: " + e);
    }
  }

  public synchronized void unBond() {

    Log.d(TAG, "unBond() start");
    if (transmitterMAC == null) {
      return;
    }

    final BluetoothAdapter mBluetoothAdapter = ((BluetoothManager) getSystemService(
        Context.BLUETOOTH_SERVICE)).getAdapter();

    final Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
    if (pairedDevices.size() > 0) {
      for (BluetoothDevice device : pairedDevices) {
        if (device.getAddress() != null) {
          if (device.getAddress().equals(transmitterMAC)) {
            try {
              Log.e(TAG, "removingBond: " + transmitterMAC);
              Method m = device.getClass().getMethod("removeBond", (Class[]) null);
              m.invoke(device, (Object[]) null);

            } catch (Exception e) {
              Log.e(TAG, e.getMessage(), e);
            }
          }

        }
      }
    }
    Log.d(TAG, "unBond() finished");
  }


  public static String getTransmitterID() {
    if (transmitterID == null) {
      init_tx_id();
    }
    return transmitterID;
  }

  private void handleWakeup() {
    if (always_scan) {
      Log.d(TAG, "Always scan mode");
      changeState(STATE.SCAN);
    } else {
      if (connectFailures > 0) {
        always_scan = true;
        Log.e(TAG,
            "Switching to scan always mode due to connect failures metric: " + connectFailures);
        changeState(STATE.SCAN);
      } else if ((connectNowFailures > 1) && (connectFailures < 0)) {
        Log.d(TAG, "Avoiding power connect due to failure metric: " + connectNowFailures + " "
            + connectFailures);
        changeState(STATE.CONNECT);
      } else {
        changeState(STATE.CONNECT_NOW);
      }
    }
  }


  private synchronized void prepareToWakeup() {
    if (HelperClass.ratelimit("g5-wakeup-timer", 5)) {
      scheduleWakeUp(SECOND_IN_MS * 285, "anticipate");
    }

    if ((android_wear && wakeup_jitter > TOLERABLE_JITTER) || always_connect) {

      Log.d(TAG, "Not stopping connect due to " + (always_connect ? "always_connect flag"
          : "unreliable wake up"));
      state = STATE.CONNECT;
      background_automata(6000);
    } else {
      state = CLOSED; // Don't poll automata as we want to do this on waking
      stopConnect();
    }
  }

  private void scheduleWakeUp(long future, final String info) {
    if (future < 0) {
      future = 5000;
    }
    Log.d(TAG,
        "Scheduling wakeup @ " + HelperClass.dateTimeText(HelperClass.tsl() + future) + " (" + info
            + ")");
    if (pendingIntent == null) {
      pendingIntent = PendingIntent.getService(this, 0, new Intent(this, this.getClass()), 0);
    }
    wakeup_time = HelperClass.tsl() + future;
    HelperClass.wakeUpIntent(this, future, pendingIntent);
  }

  public void incrementErrors() {
    error_count++;
    if (error_count > 1) {
      Log.e(TAG, "Error count reached: " + error_count);
    }
  }

  public void clearErrors() {
    error_count = 0;
  }

  private void checkAlwaysScanModels() {
    final String this_model = Build.MODEL;
    Log.d(TAG, "Checking model: " + this_model);

    always_connect = alwaysConnectModels.contains(this_model);

    if (alwaysBuggyWakeupModels.contains(this_model)) {
      Log.e(TAG, "Always buggy wakeup exact match for " + this_model);
      HelperClass.buggy_samsung = true;
    }

    if (alwaysScanModels.contains(this_model)) {
      Log.e(TAG, "Always scan model exact match for: " + this_model);
      always_scan = true;
      return;
    }

    for (String check : alwaysScanModelFamilies) {
      if (this_model.startsWith(check)) {
        Log.e(TAG, "Always scan model fuzzy match for: " + this_model);
        always_scan = true;
        return;
      }
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();

    prefs = PreferenceManager
        .getDefaultSharedPreferences(MainActivity.contextOfApplication);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
      Log.wtf(TAG, "Not high enough Android version to run: " + Build.VERSION.SDK_INT);
    } else {

      registerReceiver(mBondStateReceiver,
          new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

      final IntentFilter pairingRequestFilter = new IntentFilter(
          BluetoothDevice.ACTION_PAIRING_REQUEST);
      pairingRequestFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
      registerReceiver(mPairingRequestRecevier, pairingRequestFilter);

      checkAlwaysScanModels();

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
        android_wear = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
            == Configuration.UI_MODE_TYPE_WATCH;
        if (android_wear) {
          Log.d(TAG, "We are running on Android Wear");
        }
      }
    }
    if (d) {
      RxBleClient.setLogLevel(RxBleLog.DEBUG);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    BackgroundClass.checkAppContext(getApplicationContext());
    final PowerManager.WakeLock wl = HelperClass.getWakeLock("g5-start-service", 310000);
    try {
      Log.d(TAG,
          "WAKE UP WAKE UP WAKE UP WAKE UP @ " + HelperClass.dateTimeText(HelperClass.tsl()));
      msg("Wake up");
      if (wakeup_time > 0) {
        wakeup_jitter = HelperClass.msSince(wakeup_time);
        if (wakeup_jitter < 0) {
          Log.d(TAG, "Woke up Early..");
        } else {
          if (wakeup_jitter > 1000) {
            Log.d(TAG, "Wake up, time jitter: " + HelperClass.niceTimeScalar(wakeup_jitter));
            if ((wakeup_jitter > TOLERABLE_JITTER) && (!HelperClass.buggy_samsung) && HelperClass
                .isSamsung()) {
              Log.wtf(TAG, "Enabled Buggy Samsung workaround due to jitter of: " + HelperClass
                  .niceTimeScalar(wakeup_jitter));
              HelperClass.buggy_samsung = true;
              PersistentStore.incrementLong(BUGGY_SAMSUNG_ENABLED);
              max_wakeup_jitter = 0;
            } else {
              max_wakeup_jitter = Math.max(max_wakeup_jitter, wakeup_jitter);
            }
          }
        }
      }

      scheduleWakeUp(Constants.MINUTE_IN_MS * 6, "fail-over");
      if ((state == STATE.BOND) || (state == STATE.PREBOND)) {
        state = STATE.SCAN;
      }

      checkAndEnableBT();

      G5StateMachine.restoreQueue();
      automata(); // sequence logic

      Log.d(TAG, "Releasing service start");
      return START_STICKY;
    } finally {
      HelperClass.releaseWakeLock(wl);
    }
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "Service destroyed");
    msg("Shutting down");
    stopScan();
    stopDiscover();
    stopConnect();
    scanSubscription = null;
    connectionSubscription = null;
    stateSubscription = null;
    discoverSubscription = null;

    unregisterPairingReceiver();

    try {
      unregisterReceiver(mBondStateReceiver);
    } catch (Exception e) {
      Log.e(TAG, "Got exception unregistering pairing receiver: " + e);
    }

    state = INIT; // Should be STATE.END ?
    msg("Service Stopped");
    super.onDestroy();
  }

  public void unregisterPairingReceiver() {
    try {
      unregisterReceiver(mPairingRequestRecevier);
    } catch (Exception e) {
      Log.e(TAG, "Got exception unregistering pairing receiver: " + e);
    }
  }

  private synchronized void stopScan() {
    if (scanSubscription != null) {
      scanSubscription.unsubscribe();
    }
    if (scanWakeLock != null) {
      HelperClass.releaseWakeLock(scanWakeLock);
    }
  }

  private synchronized void stopConnect() {
    if (connectionSubscription != null) {
      connectionSubscription.unsubscribe();
    }
    if (stateSubscription != null) {
      stateSubscription.unsubscribe();
    }
  }

  private synchronized void stopDiscover() {
    if (discoverSubscription != null) {
      discoverSubscription.unsubscribe();
    }
  }

  // Successful result from our bluetooth scan
  private synchronized void onScanResult(ScanResult bleScanResult) {
    final String this_name = bleScanResult.getBleDevice().getName();
    final String search_name = getTransmitterBluetoothName();
    if ((this_name != null) && (this_name.equalsIgnoreCase(search_name))) {
      stopScan(); // we got one!
      last_scan_started = 0; // clear scanning for time
      lastScanError = null; // error should be cleared
      Log.d(TAG,
          "Got scan result match: " + bleScanResult.getBleDevice().getName() + " " + bleScanResult
              .getBleDevice().getMacAddress() + " rssi: " + bleScanResult.getRssi());
      transmitterMAC = bleScanResult.getBleDevice().getMacAddress();
      transmitterIDmatchingMAC = transmitterID;
      PersistentStore.setString(OB1G5_MACSTORE + transmitterID, transmitterMAC);
      //if (HelperClass.ratelimit("ob1-g5-scan-to-connect-transition", 3)) {
      if (state == STATE.SCAN) {
        //  if (always_scan) {
        changeState(STATE.CONNECT_NOW);
        //   } else {
        //       changeState(STATE.CONNECT);
        //  }
      } else {
        Log.e(TAG, "Skipping apparent duplicate connect transition, current state = " + state);
      }
    } else {
      String this_mac = bleScanResult.getBleDevice().getMacAddress();
      if (this_mac == null) {
        this_mac = "NULL";
      }
      if (HelperClass.quietratelimit("bt-obi1-null-match" + this_mac, 15)) {
        Log.d(TAG,
            "Bluetooth scanned device doesn't match (" + search_name + ") found: " + this_name + " "
                + bleScanResult.getBleDevice().getMacAddress());
      }
    }
  }

  // Failed result from our bluetooth scan
  private synchronized void onScanFailure(Throwable throwable) {

    if (throwable instanceof BleScanException) {
      final String info = handleBleScanException((BleScanException) throwable);
      lastScanError = info;
      Log.d(TAG, info);
      if (((BleScanException) throwable).getReason() == BleScanException.BLUETOOTH_DISABLED) {
        // Attempt to turn bluetooth on
        if (HelperClass.ratelimit("bluetooth_toggle_on", 30)) {
          Log.d(TAG, "Pause before Turn Bluetooth on");
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            //
          }
          Log.e(TAG, "Trying to Turn Bluetooth on");
          HelperClass.setBluetoothEnabled(BackgroundClass.getAppContext(), true);
        }
      }

      stopScan();
      backoff_automata();
    }
  }


  // Connection has been terminated or failed
  // - quite normal when device switches to sleep between readings
  private void onConnectionFailure(Throwable throwable) {
    // msg("Connection failure");

    Log.d(TAG, "Connection Disconnected/Failed: " + throwable);

    if (state == STATE.DISCOVER) {
      Log.e(TAG, "Would have tried to unpair but preference setting prevents it.");
    }

    if (state == STATE.CONNECT_NOW) {
      connectNowFailures++;
      Log.d(TAG, "Connect Now failures incremented to: " + connectNowFailures);
      changeState(STATE.CONNECT);
    }

    if (state == STATE.CONNECT) {
      connectFailures++;

      if (HelperClass.ratelimit("ob1-restart-scan-on-connect-failure", 10)) {
        Log.d(TAG, "Restarting scan due to connect failure");
        tryGattRefresh();
        changeState(STATE.SCAN);
      }
    }

  }

  public void tryGattRefresh() {
    if (HelperClass.ratelimit("ob1-gatt-refresh", 60)) {
      try {
        if (connection != null) {
          Log.d(TAG, "Trying gatt refresh queue");
        }
        connection.queue((new GattRefreshOperation(0))).timeout(2, TimeUnit.SECONDS).subscribe(
            readValue -> {
              Log.d(TAG, "Refresh OK: " + readValue);
            }, throwable -> {
              Log.d(TAG, "Refresh exception: " + throwable);
            });
      } catch (NullPointerException e) {
        Log.d(TAG, "Probably harmless gatt refresh exception: " + e);
      } catch (Exception e) {
        Log.d(TAG, "Got exception trying gatt refresh: " + e);
      }
    } else {
      Log.d(TAG, "Gatt refresh rate limited");
    }
  }

  // We have connected to the device!
  private void onConnectionReceived(RxBleConnection this_connection) {
    msg("Connected");
    static_last_connected = HelperClass.tsl();

    if (connection_linger != null) {
      HelperClass.releaseWakeLock(connection_linger);
    }
    connection = this_connection;

    if (state == STATE.CONNECT_NOW) {
      connectNowFailures = -3; // mark good
    }
    if (state == STATE.CONNECT) {
      connectFailures = -1; // mark good
    }

    if (HelperClass.ratelimit("g5-to-discover", 1)) {
      changeState(STATE.DISCOVER);
    }
  }

  private synchronized void onConnectionStateChange(RxBleConnection.RxBleConnectionState newState) {
    String connection_state = "Unknown";
    switch (newState) {
      case CONNECTING:
        connection_state = "Connecting";
        connecting_time = HelperClass.tsl();
        break;
      case CONNECTED:
        connection_state = "Connected";
        HelperClass.releaseWakeLock(floatingWakeLock);
        floatingWakeLock = HelperClass.getWakeLock("floating-connected", 40000);
        final long since_connecting = HelperClass.msSince(connecting_time);
        if ((connecting_time > static_last_timestamp) && (since_connecting > SECOND_IN_MS * 310)
            && (since_connecting < SECOND_IN_MS * 620)) {
          if (!always_scan) {
            Log.e(TAG, "Connection time shows missed reading, switching to always scan, metric: "
                + HelperClass.niceTimeScalar(since_connecting));
            always_scan = true;
          } else {
            Log.e(TAG,
                "Connection time shows missed reading, despite always scan, metric: " + HelperClass
                    .niceTimeScalar(since_connecting));
          }
        }
        break;
      case DISCONNECTING:
        connection_state = "Disconnecting";
        break;
      case DISCONNECTED:
        connection_state = "Disconnected";
        HelperClass.releaseWakeLock(floatingWakeLock);
        break;
    }
    static_connection_state = connection_state;
    Log.d(TAG, "Bluetooth connection: " + static_connection_state);
    if (connection_state.equals("Disconnecting")) {
      //tryGattRefresh();
    }
  }

  public static void connectionStateChange(String connection_state) {
    static_connection_state = connection_state;
  }


  private void onServicesDiscovered(RxBleDeviceServices services) {
    for (BluetoothGattService service : services.getBluetoothGattServices()) {
      if (d) {
        Log.d(TAG, "Service: " + getUUIDName(service.getUuid()));
      }
      if (service.getUuid().equals(BluetoothServices.CGMService)) {
        if (d) {
          Log.i(TAG, "Found CGM Service!");
        }
        if (!always_discover) {
          do_discovery = false;
        }
        changeState(STATE.CHECK_AUTH);
        return;
      }
    }
    Log.e(TAG, "Could not locate CGM service during discovery");
    incrementErrors();
  }

  private void onDiscoverFailed(Throwable throwable) {
    Log.e(TAG, "Discover failure: " + throwable.toString());
    incrementErrors();
  }


  public static void updateLast(long timestamp) {
    if ((static_last_timestamp == 0) && (transmitterID != null)) {
      final String ref = "last-ob1-data-" + transmitterID;
      if (PersistentStore.getLong(ref) == 0) {
        PersistentStore.setLong(ref, timestamp);
        //if (!android_wear) HelperClass.playResourceAudio(R.raw.labbed_musical_chime);   //NEU geändert
      }
    }
    static_last_timestamp = timestamp;
  }

  private String handleBleScanException(BleScanException bleScanException) {
    final String text;

    switch (bleScanException.getReason()) {
      case BleScanException.BLUETOOTH_NOT_AVAILABLE:
        text = "Bluetooth is not available";
        break;
      case BleScanException.BLUETOOTH_DISABLED:
        text = "Enable bluetooth and try again";
        break;
      case BleScanException.LOCATION_PERMISSION_MISSING:
        text = "On Android 6.0+ location permission is required. Implement Runtime Permissions";
        break;
      case BleScanException.LOCATION_SERVICES_DISABLED:
        text = "Location services needs to be enabled on Android 6.0+";
        break;
      case BleScanException.SCAN_FAILED_ALREADY_STARTED:
        text = "Scan with the same filters is already started";
        break;
      case BleScanException.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
        text = "Failed to register application for bluetooth scan";
        break;
      case BleScanException.SCAN_FAILED_FEATURE_UNSUPPORTED:
        text = "Scan with specified parameters is not supported";
        break;
      case BleScanException.SCAN_FAILED_INTERNAL_ERROR:
        text = "Scan failed due to internal error";
        break;
      case BleScanException.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
        text = "Scan cannot start due to limited hardware resources";
        break;
      case BleScanException.UNDOCUMENTED_SCAN_THROTTLE:
        text = String.format(
            Locale.getDefault(),
            "Android 7+ does not allow more scans. Try in %d seconds",
            secondsTill(bleScanException.getRetryDateSuggestion())
        );
        break;
      case BleScanException.UNKNOWN_ERROR_CODE:
      case BleScanException.BLUETOOTH_CANNOT_START:
      default:
        text = "Unable to start scanning";
        break;
    }
    Log.w(TAG, text + " " + bleScanException);
    return text;
  }

  private static class GattRefreshOperation implements RxBleCustomOperation<Void> {

    private long delay_ms = 500;

    GattRefreshOperation(long delay_ms) {
      this.delay_ms = delay_ms;
    }

    @NonNull
    @Override
    public Observable<Void> asObservable(BluetoothGatt bluetoothGatt,
        RxBleGattCallback rxBleGattCallback,
        Scheduler scheduler) throws Throwable {

      return Observable.fromCallable(() -> refreshDeviceCache(bluetoothGatt))
          .delay(delay_ms, TimeUnit.MILLISECONDS, Schedulers.computation())
          .subscribeOn(scheduler);
    }

    private Void refreshDeviceCache(final BluetoothGatt gatt) {
      Log.d(TAG,
          "Gatt Refresh " + (HelperClass.refreshDeviceCache(TAG, gatt) ? "succeeded" : "failed"));
      return null;
    }
  }

  private int currentBondState = 0;
  public int waitingBondConfirmation = 0; // 0 = not waiting, 1 = waiting, 2 = received
  final BroadcastReceiver mBondStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!keep_running) {
        try {
          Log.e(TAG, "Rogue bond state receiver still active - unregistering");
          unregisterReceiver(mBondStateReceiver);
        } catch (Exception e) {
          //
        }
        return;
      }
      final String action = intent.getAction();
      Log.d(TAG, "BondState: onReceive ACTION: " + action);
      if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
        final BluetoothDevice parcel_device = intent
            .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        currentBondState = parcel_device.getBondState();
        final int bond_state_extra = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
        final int previous_bond_state_extra = intent
            .getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

        Log.e(TAG, "onReceive UPDATE Name " + parcel_device.getName() + " Value " + parcel_device
            .getAddress()
            + " Bond state " + parcel_device.getBondState() + bondState(
            parcel_device.getBondState()) + " "
            + "bs: " + bondState(bond_state_extra) + " was " + bondState(
            previous_bond_state_extra));
        try {
          if (parcel_device.getAddress().equals(transmitterMAC)) {
            msg(bondState(bond_state_extra).replace(" ", ""));
            if (parcel_device.getBondState() == BluetoothDevice.BOND_BONDED) {

              if (waitingBondConfirmation == 1) {
                waitingBondConfirmation = 2; // received
                Log.e(TAG, "Bond confirmation received!");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                  Log.d(TAG, "Sleeping before create bond");
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                    //
                  }
                  instantCreateBond();
                }
              }
            }
          }
        } catch (Exception e) {
          Log.e(TAG, "Got exception trying to process bonded confirmation: ", e);
        }
      }
    }
  };

  public void instantCreateBond() {
    if (initiate_bonding) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          Log.d(TAG, "instantCreateBond() called");
          bleDevice.getBluetoothDevice().createBond();
        }
      } catch (Exception e) {
        Log.e(TAG, "Got exception in instantCreateBond() " + e);
      }
    } else {
      Log.e(TAG, "instantCreateBond blocked by initiate_bonding flag");
    }
  }


  private final BroadcastReceiver mPairingRequestRecevier = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!keep_running) {
        try {
          Log.e(TAG, "Rogue pairing request receiver still active - unregistering");
          unregisterReceiver(mPairingRequestRecevier);
        } catch (Exception e) {
          //
        }
        return;
      }
      if ((bleDevice != null) && (bleDevice.getBluetoothDevice().getAddress() != null)) {
        Log.e(TAG, "Processing mPairingRequestReceiver !!!");
        HelperClass.releaseWakeLock(fullWakeLock);
        fullWakeLock = HelperClass.fullWakeLock("pairing-screen-wake", 30 * SECOND_IN_MS);
        if (!HelperClass
            .doPairingRequest(context, this, intent, bleDevice.getBluetoothDevice().getAddress())) {
          if (!android_wear) {
            unregisterPairingReceiver();
            Log.e(TAG, "Pairing failed so removing pairing automation");
          }
        }
      } else {
        Log.e(TAG, "Received pairing request but device was null !!!");
      }
    }
  };

  private static long secondsTill(Date retryDateSuggestion) {
    return TimeUnit.MILLISECONDS
        .toSeconds(retryDateSuggestion.getTime() - System.currentTimeMillis());
  }

  @Override
  public IBinder onBind(Intent intent) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

   public static void msg(String msg) {
    lastState = msg + " " + HelperClass.hourMinuteString();
    Log.d(TAG, "Status: " + lastState);
    lastStateUpdated = HelperClass.tsl();
  }


  public void showGlucoseNotification(int value, String timestamp) {
    NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(MainActivity.contextOfApplication,
        MainActivity.GLUC_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_speak_reading_grey600_48dp)
        .setContentTitle(Integer.toString(value) + " mg/dL")
        .setContentText("Time: " + timestamp)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true);

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);

    // notificationId is a unique int for each notification that you must define.
    notificationManager.notify(1, mBuilder.build());

  }

  public void saveInRoom(int value, long timestamp) {
    GlucoEntity glucoEntity = new GlucoEntity(0, HelperClass.ISO8601DATEFORMAT.format(timestamp),
        value);

    new Thread(() -> {
      MainActivity.glucoseDatabase.glucoDao().insertGlucovalue(glucoEntity);
    }).start();
  }

  public void transferGlucose(int value) {
    // Hier Broadcast an WebsocketService
    Intent intent = new Intent();
    intent.setAction(ACTION_NEW_GLUC_DATA);
    intent.putExtra(EXTRA_GLUCVALUE, value);
    LocalBroadcastManager.getInstance(MainActivity.contextOfApplication).sendBroadcast(intent);
  }
}
