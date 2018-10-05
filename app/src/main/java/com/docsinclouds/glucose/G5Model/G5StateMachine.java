package com.docsinclouds.glucose.G5Model;

import static com.docsinclouds.glucose.Constants.HOUR_IN_MS;
import static com.docsinclouds.glucose.Constants.MINUTE_IN_MS;
import static com.docsinclouds.glucose.G5BaseService.G5_BATTERY_FROM_MARKER;
import static com.docsinclouds.glucose.G5BaseService.G5_BATTERY_LEVEL_MARKER;
import static com.docsinclouds.glucose.G5BaseService.G5_BATTERY_MARKER;
import static com.docsinclouds.glucose.G5BaseService.G5_BATTERY_WEARABLE_SEND;
import static com.docsinclouds.glucose.G5BaseService.G5_FIRMWARE_MARKER;
import static com.docsinclouds.glucose.G5CollectionService.android_wear;
import static com.docsinclouds.glucose.G5CollectionService.getTransmitterID;
import static com.docsinclouds.glucose.G5Model.BluetoothServices.Authentication;
import static com.docsinclouds.glucose.G5Model.BluetoothServices.Control;
import static com.docsinclouds.glucose.HelperClass.msSince;
import static com.docsinclouds.glucose.HelperClass.pratelimit;

import android.bluetooth.BluetoothGatt;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import com.docsinclouds.glucose.G5CollectionService;
import com.docsinclouds.glucose.HelperClass;
import com.docsinclouds.glucose.Inevitable;
import com.docsinclouds.glucose.PersistentStore;
import com.docsinclouds.glucose.PowerStateReceiver;
import com.docsinclouds.glucose.ProtoMessageBuilder;
import com.docsinclouds.glucose.WebsocketService;
import com.google.gson.reflect.TypeToken;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.exceptions.BleCannotSetCharacteristicNotificationException;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble.exceptions.BleGattCharacteristicException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import rx.schedulers.Schedulers;


/**
 * Created by jamorham on 17/09/2017. <p> Handles OB1 G5 communication logic. Modified by Docs in Clouds
 * Former name: Ob1G5StateMachine
 */

@SuppressWarnings("AccessStaticViaInstance")
public class G5StateMachine {

  private static final String TAG = "Ob1G5StateMachine";
  private static final String PREF_SAVED_QUEUE = "Ob1-saved-queue";

  public static final String PREF_QUEUE_DRAINED = "OB1-QUEUE-DRAINED";

  private static final int LOW_BATTERY_WARNING_LEVEL = 300; // voltage a < this value raises warnings;
  private static final long BATTERY_READ_PERIOD_MS =
      HOUR_IN_MS * 12; // how often to poll battery data (12 hours)

  private static final boolean getVersionDetails = true; // try to load firmware version details
  private static final boolean getBatteryDetails = true; // try to load battery info details

  private static final LinkedBlockingDeque<Ob1Work> commandQueue = new LinkedBlockingDeque<>();

  private static boolean speakSlowly = false; // slow down bluetooth comms for android wear etc
  private static final boolean d = false;
  private static volatile boolean backup_loaded = false;


  // Auth Check + Request
  public static boolean doCheckAuth(G5CollectionService parent, RxBleConnection connection) {

    if (connection == null) {
      return false;
    }
    parent.msg("Authorizing");

    if (android_wear) {
      speakSlowly = true;
      Log.d(TAG, "Setting speak slowly to true"); // WARN should be reactive or on named devices
    }

    final AuthRequestTxMessage authRequest = new AuthRequestTxMessage(getTokenSize(),
        HelperClass.areWeRunningOnAndroidWear()
            && true); // unsicher ob "!Pref.getBooleanDefaultFalse("only_ever_use_wear_collector"" true or false sein sollte
    Log.i(TAG, "AuthRequestTX: " + HelperClass.bytesToHex(authRequest.byteSequence));

    connection.setupNotification(Authentication)
        // .timeout(10, TimeUnit.SECONDS)
        .timeout(15, TimeUnit.SECONDS) // WARN
        // .observeOn(Schedulers.newThread()) // needed?
        .doOnNext(notificationObservable -> {
          connection.writeCharacteristic(Authentication, authRequest.byteSequence)
              .subscribe(
                  characteristicValue -> {
                    // Characteristic value confirmed.
                    if (d) {
                      Log.d(TAG,
                          "Wrote authrequest, got: " + HelperClass.bytesToHex(characteristicValue));
                    }
                    speakSlowly();
                    connection.readCharacteristic(Authentication).subscribe(
                        readValue -> {
                          PacketShop pkt = classifyPacket(readValue);
                          Log.d(TAG, "Read from auth request: " + pkt.type + " " + HelperClass
                              .bytesToHex(readValue));

                          switch (pkt.type) {
                            case AuthChallengeRxMessage:
                              // Respond to the challenge request
                              byte[] challengeHash = calculateHash(
                                  ((AuthChallengeRxMessage) pkt.msg).challenge);
                              if (d) {
                                Log.d(TAG, "challenge hash" + Arrays.toString(challengeHash));
                              }
                              if (challengeHash != null) {
                                if (d) {
                                  Log.d(TAG, "Transmitter trying auth challenge");
                                }

                                connection.writeCharacteristic(Authentication,
                                    new AuthChallengeTxMessage(challengeHash).byteSequence)
                                    .subscribe(
                                        challenge_value -> {

                                          speakSlowly();

                                          connection.readCharacteristic(Authentication)
                                              //.observeOn(Schedulers.io())
                                              .subscribe(
                                                  status_value -> {
                                                    // interpret authentication response
                                                    final PacketShop status_packet = classifyPacket(
                                                        status_value);
                                                    Log.d(TAG,
                                                        status_packet.type + " " + HelperClass
                                                            .bytesToHex(status_value));
                                                    if (status_packet.type
                                                        == PACKET.AuthStatusRxMessage) {
                                                      final AuthStatusRxMessage status = (AuthStatusRxMessage) status_packet.msg;
                                                      if (d) {
                                                        Log.d(TAG, ("Authenticated: " + status
                                                            .isAuthenticated() + " " + status
                                                            .isBonded()));
                                                      }
                                                      if (status.isAuthenticated()) {
                                                        if (status.isBonded()) {
                                                          parent.msg("Authenticated");
                                                          parent.authResult(true);
                                                          parent.changeState(
                                                              G5CollectionService.STATE.GET_DATA);
                                                          throw new OperationSuccess(
                                                              "Authenticated");
                                                        } else {
                                                          //parent.unBond(); // bond must be invalid or not existing // WARN
                                                          parent.changeState(
                                                              G5CollectionService.STATE.PREBOND);

                                                        }
                                                      } else {
                                                        parent.msg("Not Authorized! (Wrong TxID?)");
                                                        Log.e(TAG, "Authentication failed!!!!");
                                                        parent.incrementErrors();

                                                      }
                                                    } else {
                                                      Log.e(TAG,
                                                          "Got unexpected packet when looking for auth status: "
                                                              + status_packet.type + " "
                                                              + HelperClass
                                                              .bytesToHex(status_value));
                                                      parent.incrementErrors();

                                                    }

                                                  }, throwable -> {
                                                    if (throwable instanceof OperationSuccess) {
                                                      Log.d(TAG,
                                                          "Stopping auth challenge listener due to success");
                                                    } else {
                                                      Log.e(TAG,
                                                          "Could not read reply to auth challenge: "
                                                              + throwable);
                                                      parent.incrementErrors();
                                                      speakSlowly = true;
                                                    }
                                                  });
                                        }, throwable -> {
                                          Log.e(TAG,
                                              "Could not write auth challenge reply: " + throwable);
                                          parent.incrementErrors();
                                        });

                              } else {
                                Log.e(TAG, "Could not generate challenge hash! - resetting");
                                parent.changeState(G5CollectionService.STATE.INIT);
                                parent.incrementErrors();
                                return;
                              }

                              break;

                            default:
                              Log.e(TAG,
                                  "Unhandled packet type in reply: " + pkt.type + " " + HelperClass
                                      .bytesToHex(readValue));
                              parent.incrementErrors();

                              break;
                          }

                        }, throwable -> {
                          Log.e(TAG, "Could not read after AuthRequestTX: " + throwable);
                        });
                    //parent.background_automata();
                  },
                  throwable -> {
                    Log.e(TAG, "Could not write AuthRequestTX: " + throwable);
                    parent.incrementErrors();
                  }

              );
        }).flatMap(notificationObservable -> notificationObservable)
        //.timeout(5, TimeUnit.SECONDS)
        //.observeOn(Schedulers.newThread())
        .subscribe(bytes -> {
          // incoming notifications
          Log.e(TAG,
              "Received Authentication notification bytes: " + HelperClass.bytesToHex(bytes));

        }, throwable -> {
          if (!(throwable instanceof OperationSuccess)) {
            if (((parent.getState() == G5CollectionService.STATE.CLOSED)
                || (parent.getState() == G5CollectionService.STATE.CLOSE))
                && (throwable instanceof BleDisconnectedException)) {
              Log.d(TAG,
                  "normal authentication notification throwable: (" + parent.getState() + ") "
                      + throwable + " " + HelperClass.dateTimeText(HelperClass.tsl()));
              parent.connectionStateChange("Closed OK");
            } else if ((parent.getState() == G5CollectionService.STATE.BOND)
                && (throwable instanceof TimeoutException)) {

              // Log.e(TAG,"Attempting to reset/create bond due to: "+throwable);
              // parent.reset_bond(true);
              // parent.unBond(); // WARN
            } else {
              Log.e(TAG,
                  "authentication notification  throwable: (" + parent.getState() + ") " + throwable
                      + " " + HelperClass.dateTimeText(HelperClass.tsl()));
              parent.incrementErrors();
              if (throwable instanceof BleCannotSetCharacteristicNotificationException
                  || throwable instanceof BleGattCharacteristicException) {
                parent.tryGattRefresh();
                parent.changeState(G5CollectionService.STATE.SCAN);
              }
            }
            if ((throwable instanceof BleDisconnectedException)
                || (throwable instanceof TimeoutException)) {
              if ((parent.getState() == G5CollectionService.STATE.BOND) || (parent.getState()
                  == G5CollectionService.STATE.CHECK_AUTH)) {

                if (parent.getState() == G5CollectionService.STATE.BOND) {
                  Log.d(TAG, "SLEEPING BEFORE RECONNECT");
                  threadSleep(15000);
                }
                Log.d(TAG, "REQUESTING RECONNECT");
                parent.changeState(G5CollectionService.STATE.SCAN);
              }
            }
          }
        });
    return true;
  }

  private static final int SPEAK_SLOWLY_DELAY = 300;

  private static int speakSlowlyDelay() {
    return speakSlowly ? SPEAK_SLOWLY_DELAY : 0;
  }

  private static void speakSlowly() {
    if (speakSlowly) {
      Log.d(TAG, "Speaking slowly");
      threadSleep(SPEAK_SLOWLY_DELAY);
    }
  }

  private static void threadSleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (Exception e) {
      Log.e(TAG, "Failed to sleep for " + ms + " due to: " + e);
    }
  }

  // Handle bonding
  public synchronized static boolean doKeepAliveAndBondRequest(G5CollectionService parent,
      RxBleConnection connection) {

    if (connection == null) {
      return false;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      Log.d(TAG, "Requesting high priority");
      connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH, 500,
          TimeUnit.MILLISECONDS);
    }
    Log.e(TAG, "Sending keepalive..");
    connection.writeCharacteristic(Authentication, new KeepAliveTxMessage(25).byteSequence)
        .subscribe(
            characteristicValue -> {
              Log.d(TAG, "Wrote keep-alive request successfully");
              speakSlowly(); // is this really needed here?
              parent.unBond();
              parent.instantCreateBond();
              speakSlowly();
              connection
                  .writeCharacteristic(Authentication, new BondRequestTxMessage().byteSequence)
                  .subscribe(
                      bondRequestValue -> {
                        Log.d(TAG, "Wrote bond request value: " + HelperClass
                            .bytesToHex(bondRequestValue));
                        speakSlowly();
                        connection.readCharacteristic(Authentication)
                            .observeOn(Schedulers.io())
                            .timeout(10, TimeUnit.SECONDS)
                            .subscribe(
                                status_value -> {
                                  Log.d(TAG, "Got status read after keepalive " + HelperClass
                                      .bytesToHex(status_value));

                                  Log.d(TAG, "Wrote bond request successfully");
                                  parent.waitingBondConfirmation = 1; // waiting

                                  parent.instantCreateBond();
                                  Log.d(TAG, "Sleeping for bond");
                                  for (int i = 0; i < 9; i++) {
                                    if (parent.waitingBondConfirmation == 2) {
                                      Log.d(TAG, "Bond confirmation received - continuing!");
                                      break;
                                    }
                                    threadSleep(1000);
                                  }
                                  parent.changeState(G5CollectionService.STATE.BOND);
                                  throw new OperationSuccess("Bond requested");

//
                                }, throwable -> {
                                  Log.e(TAG,
                                      "Throwable when reading characteristic after keepalive: "
                                          + throwable);
                                });

                        // Wrote bond request successfully was here moved above - is this right?
                      }, throwable -> {
                        // failed to write bond request retry?
                        if (!(throwable instanceof OperationSuccess)) {
                          Log.e(TAG, "Failed to write bond request! " + throwable);
                        }
                      });

            }, throwable -> {
              // Could not write keep alive ? retry?
              Log.e(TAG, "Failed writing keep-alive request! " + throwable);
            });
    Log.d(TAG, "Exiting doKeepAliveBondRequest");
    final PowerManager.WakeLock linger = HelperClass.getWakeLock("jam-g5-bond-linger", 30000);
    return true;
  }

  public static boolean doReset(G5CollectionService parent, RxBleConnection connection) {
    if (connection == null) {
      return false;
    }
    parent.msg("Hard Resetting Transmitter");
    connection.writeCharacteristic(Control, new ResetTxMessage().byteSequence)
        .subscribe(characteristicValue -> {
          if (d) {
            Log.d(TAG, "Wrote ResetTxMessage request!!");
          }
          parent.msg("Hard Reset Sent");
        }, throwable -> {
          parent.msg("Hard Reset maybe Failed");
          Log.e(TAG, "Failed to write ResetTxMessage: " + throwable);
          if (throwable instanceof BleGattCharacteristicException) {
            final int status = ((BleGattCharacteristicException) throwable).getStatus();
            Log.e(TAG, "Got status message: " + BluetoothServices.getStatusName(status));
          }
        });
    return true;
  }

  /**
   * This method handles the data received from the transmitter
   */
  public static boolean doGetData(G5CollectionService parent, RxBleConnection connection) {
    Log.e(TAG, "MOSTIMPORTANTCALL");
    if (connection == null) {
      return false;
    }

    final boolean use_g5_internal_alg = true;
    Log.d(TAG, use_g5_internal_alg ? "Requesting Glucose Data" : "Requesting Sensor Data");

    if (!use_g5_internal_alg) {
      parent.lastSensorStatus = null; // not applicable
      parent.lastUsableGlucosePacketTime = 0;
    }

    connection.setupIndication(Control)

        .doOnNext(notificationObservable -> {

          if (d) {
            Log.d(TAG, "Notifications enabled");
          }
          speakSlowly();

          connection.writeCharacteristic(Control,
              use_g5_internal_alg ? new GlucoseTxMessage().byteSequence
                  : new SensorTxMessage().byteSequence)
              .subscribe(
                  characteristicValue -> {
                    if (d) {
                      Log.d(TAG, "Wrote SensorTxMessage request");
                    }
                  }, throwable -> {
                    Log.e(TAG, "Failed to write SensorTxMessage: " + throwable);
                    if (throwable instanceof BleGattCharacteristicException) {
                      final int status = ((BleGattCharacteristicException) throwable).getStatus();
                      Log.e(TAG, "Got status message: " + BluetoothServices.getStatusName(status));
                      if (status == 8) {
                        Log.e(TAG, "Request rejected due to Insufficient Authorization failure!");
                        parent.authResult(false);
                      }
                    }
                  });

        })
        .flatMap(notificationObservable -> notificationObservable)
        .timeout(6, TimeUnit.SECONDS)
        .subscribe(bytes -> {
          // incoming data notifications
          Log.d(TAG, "Received indication bytes: " + HelperClass.bytesToHex(bytes));
          final PacketShop data_packet = classifyPacket(bytes);
          switch (data_packet.type) {
            case SensorRxMessage:
              try {
                if ((getVersionDetails) && (!haveFirmwareDetails())) {
                  connection
                      .writeCharacteristic(Control, new VersionRequestTxMessage().byteSequence)
                      .subscribe(versionValue -> {
                        Log.d(TAG, "Wrote version request");
                      }, throwable -> {
                        Log.e(TAG, "Failed to write VersionRequestTxMessage: " + throwable);
                      });
                } else if ((getBatteryDetails) && (parent.getBatteryStatusNow
                    || !haveCurrentBatteryStatus())) {

                  enqueueUniqueCommand(new BatteryInfoTxMessage(), "Query battery");
                  parent.getBatteryStatusNow = false;
                }
              } finally {
                processSensorRxMessage((SensorRxMessage) data_packet.msg);
                parent.msg("Got data");
                parent.updateLast(HelperClass.tsl());
                parent.clearErrors();
              }
              break;

            case VersionRequestRxMessage:
              if (!setStoredFirmwareBytes(getTransmitterID(), bytes, true)) {
                Log.e(TAG, "Could not save out firmware version!");
              }
              break;

            case BatteryInfoRxMessage:
              if (!setStoredBatteryBytes(getTransmitterID(), bytes)) {
                Log.e(TAG, "Could not save out battery data!");
              } else {
                if (android_wear) {
                  PersistentStore.setBoolean(G5_BATTERY_WEARABLE_SEND, true);
                }
              }
              break;
            case SessionStopRxMessage:
              final SessionStopRxMessage session_stop = (SessionStopRxMessage) data_packet.msg;
              if (session_stop.isOkay()) {

                parent.msg("Session Stopped Successfully: " + HelperClass
                    .dateTimeText(session_stop.getSessionStart()) + " " + HelperClass
                    .dateTimeText(session_stop.getSessionStop()));
                enqueueUniqueCommand(new GlucoseTxMessage(), "Re-read glucose");
                enqueueUniqueCommand(new TransmitterTimeTxMessage(), "Query time after stop");
              } else {
                Log.e(TAG, "Session Stop Error!");
              }
              break;

            case GlucoseRxMessage:
              final GlucoseRxMessage glucose = (GlucoseRxMessage) data_packet.msg;

              if (glucose.usable()) {
                Log.d(TAG,
                    "Received Glucosevalue " + glucose.filtered + " at " + HelperClass
                        .hourMinuteString()
                        + ".");
              } else {
                parent.msg("Got data from G5");
              }

              if (HelperClass.ratelimit("ob1-g5-also-read-raw", 20)) {
                enqueueUniqueCommand(new SensorTxMessage(), "Also read raw");
              }

              if (pratelimit("g5-tx-time-since", 7200)
                  || glucose.calibrationState().warmingUp()) {
                if (HelperClass.ratelimit("g5-tx-time-governer", 30)) {
                  enqueueUniqueCommand(new TransmitterTimeTxMessage(), "Periodic Query Time");
                }
              }

              parent.updateLast(HelperClass.tsl());
              parent.clearErrors();
              parent.saveInRoom(glucose.filtered / 1000, HelperClass.tsl());
              // Show in notificationbar
              parent.showGlucoseNotification(glucose.filtered / 1000,
                  HelperClass.ISO8601DATEFORMAT.format(HelperClass.tsl()).substring(11, 19));

              // Transfer data to websocket
              parent.transferGlucose(
                  glucose.filtered / 1000); // Glucosevalue needs to be scaled by 1/1000
              break;

            case TransmitterTimeRxMessage:
              final TransmitterTimeRxMessage txtime = (TransmitterTimeRxMessage) data_packet.msg;
              if (txtime.sessionInProgress()) {
                Log.d(TAG, "Session start time reports: "
                    + HelperClass.dateTimeText(txtime.getRealSessionStartTime()) + " Duration: "
                    + HelperClass.niceTimeScalar(txtime.getSessionDuration()));
                DexSessionKeeper.setStart(txtime.getRealSessionStartTime());
              } else {
                Log.e(TAG, "Session start time reports: No session in progress");
                DexSessionKeeper.clearStart();
              }
              break;

            default:
              Log.e(TAG, "Got unknown packet rx: " + HelperClass.bytesToHex(bytes));
              break;
          }
          if (!queued(parent, connection)) {
            inevitableDisconnect(parent, connection);
          }

        }, throwable -> {
          if (!(throwable instanceof OperationSuccess)) {
            if (throwable instanceof BleDisconnectedException) {
              Log.d(TAG, "Disconnected when waiting to receive indication: " + throwable);
              parent.changeState(G5CollectionService.STATE.CLOSE);
            } else {
              Log.e(TAG, "Error receiving indication: " + throwable);
              throwable.printStackTrace();
              disconnectNow(parent, connection);
            }
          }
        });

    return true;
  }


  private static void inevitableDisconnect(G5CollectionService parent, RxBleConnection connection) {
    inevitableDisconnect(parent, connection, speakSlowlyDelay());
  }

  private static void inevitableDisconnect(G5CollectionService parent, RxBleConnection connection,
      long guardTime) {
    Inevitable.task("Ob1G5 disconnect", 500 + guardTime + speakSlowlyDelay(),
        () -> disconnectNow(parent, connection));
  }

  private static void disconnectNow(G5CollectionService parent, RxBleConnection connection) {
    // tell device to disconnect now
    Log.d(TAG, "Disconnect NOW: " + HelperClass.dateTimeText(HelperClass.tsl()));
    speakSlowly();
    connection.writeCharacteristic(Control, new DisconnectTxMessage().byteSequence)
        .timeout(2, TimeUnit.SECONDS)
        //  .observeOn(Schedulers.newThread())
        //  .subscribeOn(Schedulers.newThread())
        .subscribe(disconnectValue -> {
          if (d) {
            Log.d(TAG, "Wrote disconnect request");
          }
          parent.changeState(G5CollectionService.STATE.CLOSE);
          throw new OperationSuccess("Requested Disconnect");
        }, throwable -> {
          if (!(throwable instanceof OperationSuccess)) {
            Log.d(TAG, "Disconnect NOW failure: " + HelperClass.dateTimeText(HelperClass.tsl()));
            if (throwable instanceof BleDisconnectedException) {
              Log.d(TAG,
                  "Failed to write DisconnectTxMessage as already disconnected: " + throwable);

            } else {
              Log.e(TAG, "Failed to write DisconnectTxMessage: " + throwable);

            }
            parent.changeState(G5CollectionService.STATE.CLOSE);
          }
        });
    Log.d(TAG, "Disconnect NOW exit: " + HelperClass.dateTimeText(HelperClass.tsl()));
  }


  private static void backupCheck(Ob1Work item) {
    if (item.streamable()) {
      saveQueue();
    }
  }

  private static void enqueueUniqueCommand(TransmitterMessage tm, String msg) {
    if (tm != null) {
      final Class searchClass = tm.getClass();
      Ob1Work item;
      synchronized (commandQueue) {
        if (searchQueue(searchClass)) {
          Log.d(TAG, "Not adding duplicate: " + searchClass.getSimpleName());
          return;
        }
        item = new Ob1Work(tm, msg);
        commandQueue.add(item);
      }
      backupCheck(item);
    }
  }

  // note not synchronized here
  private static boolean searchQueue(Class searchClass) {
    for (Ob1Work item : commandQueue) {
      if (item.msg.getClass() == searchClass) {
        return true;
      }
    }
    return false;
  }

  public static void restoreQueue() {
    if (!backup_loaded) {
      loadQueue();
    }
  }

  private synchronized static void loadQueue() {
    if (commandQueue.size() == 0) {
      injectQueueJson(PersistentStore.getString(PREF_SAVED_QUEUE));
      Log.d(TAG, "Loaded queue stream backup.");
    }
    backup_loaded = true;
  }


  private static void saveQueue() {
    final String queue_json = extractQueueJson();
    if (!(queue_json == null ? "" : queue_json)
        .equals(PersistentStore.getString(PREF_SAVED_QUEUE))) {
      PersistentStore.setString(PREF_SAVED_QUEUE, queue_json);
      Log.d(TAG, "Saved queue stream backup: " + queue_json);
    }
  }

  public static String extractQueueJson() {
    synchronized (commandQueue) {
      final List<Ob1Work> queue = new ArrayList<>(commandQueue.size());
      for (Ob1Work item : commandQueue) {
        if (item.streamable()) {
          queue.add(item);
        }
      }
      return HelperClass.defaultGsonInstance().toJson(queue);
    }
  }

  // used in backup restore and wear
  @SuppressWarnings("WeakerAccess")
  public static void injectQueueJson(String json) {
    if (json == null || json.length() == 0) {
      return;
    }
    final Type queueType = new TypeToken<ArrayList<Ob1Work>>() {
    }.getType();
    final List<Ob1Work> queue = HelperClass.defaultGsonInstance().fromJson(json, queueType);
    synchronized (commandQueue) {
      commandQueue.clear();
      commandQueue.addAll(queue);
    }
    Log.d(TAG, "Replaced queue with stream: " + json);
  }


  @SuppressWarnings("unused")
  public static void injectDexTime(String stream) {
    DexTimeKeeper.injectFromStream(stream);
  }

  private static void reprocessTxMessage(TransmitterMessage tm) {
  }


  private static boolean queued(G5CollectionService parent, RxBleConnection connection) {
    if (!commandQueue.isEmpty()) {
      processQueueCommand(parent, connection);
      return true;
    }
    return false;
  }

  private static void processQueueCommand(G5CollectionService parent, RxBleConnection connection) {
    boolean changed = false;
    synchronized (commandQueue) {
      if (!commandQueue.isEmpty()) {
        final Ob1Work unit = commandQueue.poll();
        if (unit != null) {
          changed = true;
          reprocessTxMessage(unit.msg);
          if (unit.retry < 5 && msSince(unit.timestamp) < HOUR_IN_MS * 8) {
            connection.writeCharacteristic(Control, unit.msg.byteSequence)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(value -> {
                  Log.d(TAG, "Wrote Queue Message: " + unit.text);
                  final long guardTime = unit.msg.guardTime();
                  inevitableDisconnect(parent, connection, guardTime);
                  if (guardTime > 0) {
                    Log.d(TAG, "Sleeping post execute: " + unit.text + " " + guardTime + "ms");
                    HelperClass.threadSleep(guardTime);
                  }
                  throw new OperationSuccess("Completed: " + unit.text);

                }, throwable -> {
                  if (!(throwable instanceof OperationSuccess)) {
                    unit.retry++;
                    Log.d(TAG, "Re-adding: " + unit.text);
                    synchronized (commandQueue) {
                      commandQueue.push(unit);
                    }
                    Log.d(TAG, "Failure: " + unit.text + " " + HelperClass
                        .dateTimeText(HelperClass.tsl()));
                    if (throwable instanceof BleDisconnectedException) {
                      Log.d(TAG, "Disconnected: " + unit.text + " " + throwable);
                      parent.changeState(G5CollectionService.STATE.CLOSE);
                    } else {
                      Log.e(TAG, "Failed to write: " + unit.text + " " + throwable);
                    }
                    parent.changeState(G5CollectionService.STATE.CLOSE);
                  } else {
                    queued(parent, connection); // turtles all the way down
                  }
                });
          } else {
            Log.e(TAG,
                "Ejected command from queue due to being too old: " + unit.text + " " + HelperClass
                    .dateTimeText(unit.timestamp));
          }
        }
        if (commandQueue.isEmpty()) {
          if (d) {
            Log.d(TAG, "Command Queue Drained");
          }
          if (android_wear) {
            PersistentStore.setBoolean(PREF_QUEUE_DRAINED, true);
          }
        }
      } else {
        Log.d(TAG, "Command Queue is Empty");
      }
    }
    if (changed) {
      saveQueue();
    }
  }


  private static void processSensorRxMessage(SensorRxMessage sensorRx) {
    if (sensorRx == null) {
      return;
    }

    int sensor_battery_level = 0;
    if (sensorRx.status == TransmitterStatus.BRICKED) {
      sensor_battery_level = 206; //will give message "EMPTY"
    } else if (sensorRx.status == TransmitterStatus.LOW) {
      sensor_battery_level = 209; //will give message "LOW"
    } else {
      sensor_battery_level = 216; //no message, just system status "OK"
    }

    Log.d(TAG,
        "SUCCESS!! unfiltered: " + sensorRx.unfiltered + " timestamp: " + sensorRx.timestamp + " "
            + HelperClass.qs((double) sensorRx.timestamp / 86400, 1) + " days");
    DexTimeKeeper.updateAge(getTransmitterID(), sensorRx.timestamp);
  }


  private static boolean haveFirmwareDetails() {
    return getTransmitterID().length() == 6
        && getStoredFirmwareBytes(getTransmitterID()).length >= 10;
  }


  private static boolean haveCurrentBatteryStatus() {
    return getTransmitterID().length() == 6 && (
        msSince(PersistentStore.getLong(G5_BATTERY_FROM_MARKER + getTransmitterID()))
            < BATTERY_READ_PERIOD_MS);
  }

  private static byte[] getStoredFirmwareBytes(String transmitterId) {
    if (transmitterId.length() != 6) {
      return new byte[0];
    }
    return PersistentStore.getBytes(G5_FIRMWARE_MARKER + transmitterId);
  }

  public static boolean setStoredFirmwareBytes(String transmitterId, byte[] data,
      boolean from_bluetooth) {
    if (from_bluetooth) {
      Log.d(TAG, "Store: VersionRX dbg: " + HelperClass.bytesToHex(data));
    }
    if (transmitterId.length() != 6) {
      return false;
    }
    if (data.length < 10) {
      return false;
    }
    if (HelperClass.ratelimit("store-firmware-bytes", 60)) {
      PersistentStore.setBytes(G5_FIRMWARE_MARKER + transmitterId, data);
    }
    return true;
  }


  public synchronized static boolean setStoredBatteryBytes(String transmitterId, byte[] data) {
    Log.e(TAG, "Store: BatteryRX dbg: " + HelperClass.bytesToHex(data));
    if (transmitterId.length() != 6) {
      return false;
    }
    if (data.length < 10) {
      return false;
    }
    final BatteryInfoRxMessage batteryInfoRxMessage = new BatteryInfoRxMessage(data);
    Log.e(TAG, "Saving battery data: " + batteryInfoRxMessage.toString());
    PersistentStore.setBytes(G5_BATTERY_MARKER + transmitterId, data);
    PersistentStore.setLong(G5_BATTERY_FROM_MARKER + transmitterId, HelperClass.tsl());

    final long old_level = PersistentStore.getLong(G5_BATTERY_LEVEL_MARKER + transmitterId);
    if ((batteryInfoRxMessage.voltagea < old_level) || (old_level == 0)) {
      if (batteryInfoRxMessage.voltagea < LOW_BATTERY_WARNING_LEVEL) {
        if (pratelimit("g5-low-battery-warning", 40000)) {
          final boolean loud = !PowerStateReceiver.is_power_connected();
          //HelperClass.showNotification("G5 Battery Low", "G5 Transmitter battery has dropped to: " + batteryInfoRxMessage.voltagea + " it may fail soon",
          //        null, 770, NotificationChannels.LOW_TRANSMITTER_BATTERY_CHANNEL, loud, loud, null, null, null);
        }
      }
      PersistentStore
          .setLong(G5_BATTERY_LEVEL_MARKER + transmitterId, batteryInfoRxMessage.voltagea);
    }
    return true;
  }

  private static synchronized byte[] calculateHash(byte[] data) {
    if (data.length != 8) {
      Log.e(TAG, "Data length should be exactly 8.");
      return null;
    }

    final byte[] key = cryptKey();
    if (key == null) {
      return null;
    }

    ByteBuffer bb = ByteBuffer.allocate(16);
    bb.put(data);
    bb.put(data);

    final byte[] doubleData = bb.array();

    final Cipher aesCipher;
    try {
      aesCipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
      final SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
      aesCipher.init(Cipher.ENCRYPT_MODE, skeySpec);
      byte[] aesBytes = aesCipher.doFinal(doubleData, 0, doubleData.length);

      bb = ByteBuffer.allocate(8);
      bb.put(aesBytes, 0, 8);

      return bb.array();
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException | InvalidKeyException e) {
      e.printStackTrace();
    }

    return null;
  }

  private static byte[] cryptKey() {
    final String transmitterId = getTransmitterID();
    if (transmitterId.length() != 6) {
      Log.e(TAG, "cryptKey: Wrong transmitter id length!: " + transmitterId.length());
    }
    try {
      return ("00" + transmitterId + "00" + transmitterId).getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return null;
  }

  // types of packet we receive
  private enum PACKET {
    NULL,
    UNKNOWN,
    AuthChallengeRxMessage,
    AuthStatusRxMessage,
    SensorRxMessage,
    VersionRequestRxMessage,
    BatteryInfoRxMessage,
    SessionStartRxMessage,
    SessionStopRxMessage,
    GlucoseRxMessage,
    CalibrateRxMessage,
    TransmitterTimeRxMessage,
    InvalidRxMessage,

  }

  // unified data structure
  private static class PacketShop {

    private PACKET type;
    private TransmitterMessage msg;

    PacketShop(PACKET type, TransmitterMessage msg) {
      this.type = type;
      this.msg = msg;
    }
  }

  // work out what type of packet we received and wrap it up nicely
  private static PacketShop classifyPacket(byte[] packet) {
    if ((packet == null) || (packet.length == 0)) {
      return new PacketShop(PACKET.NULL, null);
    }
    switch ((int) packet[0]) {
      case AuthChallengeRxMessage.opcode:
        return new PacketShop(PACKET.AuthChallengeRxMessage, new AuthChallengeRxMessage(packet));
      case AuthStatusRxMessage.opcode:
        return new PacketShop(PACKET.AuthStatusRxMessage, new AuthStatusRxMessage(packet));
      case SensorRxMessage.opcode:
        return new PacketShop(PACKET.SensorRxMessage, new SensorRxMessage(packet));
      case VersionRequestRxMessage.opcode:
        return new PacketShop(PACKET.VersionRequestRxMessage, new VersionRequestRxMessage(packet));
      case BatteryInfoRxMessage.opcode:
        return new PacketShop(PACKET.BatteryInfoRxMessage, new BatteryInfoRxMessage(packet));
      case SessionStartRxMessage.opcode:
        return new PacketShop(PACKET.SessionStartRxMessage,
            new SessionStartRxMessage(packet, getTransmitterID()));
      case SessionStopRxMessage.opcode:
        return new PacketShop(PACKET.SessionStopRxMessage,
            new SessionStopRxMessage(packet, getTransmitterID()));
      case GlucoseRxMessage.opcode:
        return new PacketShop(PACKET.GlucoseRxMessage, new GlucoseRxMessage(packet));
      case CalibrateRxMessage.opcode:
        return new PacketShop(PACKET.CalibrateRxMessage, new CalibrateRxMessage(packet));
      case TransmitterTimeRxMessage.opcode:
        return new PacketShop(PACKET.TransmitterTimeRxMessage,
            new TransmitterTimeRxMessage(packet));
      case InvalidRxMessage.opcode:
        return new PacketShop(PACKET.InvalidRxMessage, new InvalidRxMessage(packet));
    }
    return new PacketShop(PACKET.UNKNOWN, null);
  }

  private static int getTokenSize() {
    return 8;
  }


  private static class OperationSuccess extends RuntimeException {

    private OperationSuccess(String message) {
      super(message);
      Log.d(TAG, "Operation Success: " + message);
    }
  }
}
