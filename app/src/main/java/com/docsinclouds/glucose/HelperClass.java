package com.docsinclouds.glucose;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static android.bluetooth.BluetoothDevice.PAIRING_VARIANT_PIN;
import static android.content.Context.ALARM_SERVICE;


/**
 * Created by jamorham on 06/01/16.
 * Modified by Docs in Clouds
 * lazy helper class for utilities
 * Former name: JoH
 */
public class HelperClass {
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private final static String TAG = "Helperclass";
    private final static boolean debug_wakelocks = false;

    private static double benchmark_time = 0;
    private static Map<String, Double> benchmarks = new HashMap<String, Double>();
    private static final Map<String, Long> rateLimits = new HashMap<>();

    public static boolean buggy_samsung = false; // flag set when we detect samsung devices which do not perform to android specifications

    public static SimpleDateFormat ISO8601DATEFORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.GERMANY);


    // singletons to avoid repeated allocation
    private static DecimalFormatSymbols dfs;
    private static DecimalFormat df;
    public static String qs(double x, int digits) {

        if (digits == -1) {
            digits = 0;
            if (((int) x != x)) {
                digits++;
                if ((((int) x * 10) / 10 != x)) {
                    digits++;
                    if ((((int) x * 100) / 100 != x)) digits++;
                }
            }
        }

        if (dfs == null) {
            final DecimalFormatSymbols local_dfs = new DecimalFormatSymbols();
            local_dfs.setDecimalSeparator('.');
            dfs = local_dfs; // avoid race condition
        }

        final DecimalFormat this_df;
        // use singleton if on ui thread otherwise allocate new as DecimalFormat is not thread safe
        if (Thread.currentThread().getId() == 1) {
            if (df == null) {
                final DecimalFormat local_df = new DecimalFormat("#", dfs);
                local_df.setMinimumIntegerDigits(1);
                df = local_df; // avoid race condition
            }
            this_df = df;
        } else {
            this_df = new DecimalFormat("#", dfs);
        }

        this_df.setMaximumFractionDigits(digits);
        return this_df.format(x);
    }

    public static double ts() {
        return new Date().getTime();
    }

    public static long tsl() {
        return System.currentTimeMillis();
    }

    public static long uptime() {
        return SystemClock.uptimeMillis();
    }

    public static long msSince(long when) {
        return (tsl() - when);
    }

    public static long msTill(long when) {
        return (when - tsl());
    }


    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "<empty>";
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }



    public static String base64encodeBytes(byte[] input) {
        try {
            return new String(Base64.encode(input, Base64.NO_WRAP), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return "encode-error";
        }
    }

    public static byte[] base64decodeBytes(String input) {
        try {
            return Base64.decode(input.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return new byte[0];
        }
    }

    public static boolean isSamsung() {
        return Build.MANUFACTURER.toLowerCase().contains("samsung");
    }

    private static final String BUGGY_SAMSUNG_ENABLED = "buggy-samsung-enabled";

    public static String backTrace(int depth) {
        try {
            StackTraceElement stack = new Exception().getStackTrace()[2 + depth];
            StackTraceElement stackb = new Exception().getStackTrace()[3 + depth];
            String[] stackclassa = stack.getClassName().split("\\.");
            String[] stackbclassa = stackb.getClassName().split("\\.");

            return stackbclassa[stackbclassa.length - 1] + "::" + stackb.getMethodName()
                    + " -> " + stackclassa[stackclassa.length - 1] + "::" + stack.getMethodName();
        } catch (Exception e) {
            return "unknown backtrace: " + e.toString();
        }
    }


    // return true if below rate limit (persistent version)
    public static synchronized boolean pratelimit(String name, int seconds) {
        // check if over limit
        final long time_now = HelperClass.tsl();
        final long rate_time;
        if (!rateLimits.containsKey(name)) {
            rate_time = PersistentStore.getLong(name); // 0 if undef
        } else {
            rate_time = rateLimits.get(name);
        }
        if ((rate_time > 0) && (time_now - rate_time) < (seconds * 1000L)) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, time_now);
        PersistentStore.setLong(name, time_now);
        return true;
    }

    // return true if below rate limit
    public static synchronized boolean ratelimit(String name, int seconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && HelperClass.tsl() - rateLimits.get(name) < (seconds * 1000L)) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, HelperClass.tsl());
        return true;
    }

    // return true if below rate limit
    public static synchronized boolean quietratelimit(String name, int seconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && (HelperClass.tsl() - rateLimits.get(name) < (seconds * 1000))) {
            return false;
        }
        // not over limit
        rateLimits.put(name, HelperClass.tsl());
        return true;
    }


    private static Gson gson_instance;
    public static Gson defaultGsonInstance() {
        if (gson_instance == null) {
            gson_instance = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    //.registerTypeAdapter(Date.class, new DateTypeAdapter())
                    // .serializeSpecialFloatingPointValues()
                    .create();
        }
        return gson_instance;
    }

    public static String hourMinuteString() {
        // Date date = new Date();
        // SimpleDateFormat sd = new SimpleDateFormat("HH:mm");
        //  return sd.format(date);
        return hourMinuteString(HelperClass.tsl());
    }

    public static String hourMinuteString(long timestamp) {
        return android.text.format.DateFormat.format("kk:mm", timestamp).toString();
    }

    public static String dateTimeText(long timestamp) {
        return android.text.format.DateFormat.format("yyyy-MM-dd kk:mm:ss", timestamp).toString();
    }


    // temporary
    public static String niceTimeScalar(long t) {
        String unit = "second";
        t = t / 1000;
        if (t > 59) {
            unit = "minute";
            t = t / 60;
            if (t > 59) {
                unit = "hour";
                t = t / 60;
                if (t > 24) {
                    unit = "day";
                    t = t / 24;
                    if (t > 28) {
                        unit = "week";
                        t = t / 7;
                    }
                }
            }
        }
        if (t != 1) unit = unit + "s";
        return qs((double) t, 0) + " " + unit;
    }

    public static String niceTimeScalar(double t, int digits) {
        String unit = "second";
        t = t / 1000;
        if (t > 59) {
            unit = "minute";
            t = t / 60;
            if (t > 59) {
                unit = "hour";
                t = t / 60;
                if (t > 24) {
                    unit = "day";
                    t = t / 24;
                    if (t > 28) {
                        unit = "week";
                        t = t / 7;
                    }
                }
            }
        }
        if (t != 1) unit = unit + "s";
        return qs( t, digits) + " " + unit;
    }


    public static PowerManager.WakeLock getWakeLock(final String name, int millis) {
        final PowerManager pm = (PowerManager) BackgroundClass.getAppContext().getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
        wl.acquire(millis);
        if (debug_wakelocks) Log.d(TAG, "getWakeLock: " + name + " " + wl.toString());
        return wl;
    }

    public static void releaseWakeLock(PowerManager.WakeLock wl) {
        if (debug_wakelocks) Log.d(TAG, "releaseWakeLock: " + wl.toString());
        if (wl == null) return;
        if (wl.isHeld()) {
            try {
                wl.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wakelock: " + e);
            }
        }
    }

    public static PowerManager.WakeLock fullWakeLock(final String name, long millis) {
        final PowerManager pm = (PowerManager) BackgroundClass.getAppContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, name);
        wl.acquire(millis);
        if (debug_wakelocks) Log.d(TAG, "fullWakeLock: " + name + " " + wl.toString());
        return wl;
    }




    public static boolean isOngoingCall() {
        try {
            AudioManager manager = (AudioManager) BackgroundClass.getAppContext().getSystemService(Context.AUDIO_SERVICE);
            return (manager.getMode() == AudioManager.MODE_IN_CALL);
            // possibly should have MODE_IN_COMMUNICATION as well
        } catch (Exception e) {
            return false;
        }
    }


    public static boolean runOnUiThread(Runnable theRunnable) {
        final Handler mainHandler = new Handler(BackgroundClass.getAppContext().getMainLooper());
        return mainHandler.post(theRunnable);
    }


    public static void static_toast(final Context context, final String msg, final int length) {
        try {
            if (!runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, msg, length).show();
                        Log.i(TAG, "Displaying toast using fallback");
                    } catch (Exception e) {
                        Log.e(TAG, "Exception processing runnable toast ui thread: " + e);
                    }
                }
            })) {
                Log.e(TAG, "Couldn't display toast via ui thread: " + msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't display toast due to exception: " + msg + " e: " + e.toString());
        }
    }



    public static long wakeUpIntent(Context context, long delayMs, PendingIntent pendingIntent) {
        final long wakeTime = HelperClass.tsl() + delayMs;
        if (pendingIntent != null) {
            Log.d(TAG, "Scheduling wakeup intent: " + dateTimeText(wakeTime));
            final AlarmManager alarm = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            try {
                alarm.cancel(pendingIntent);
            } catch (Exception e) {
                Log.e(TAG, "Exception cancelling alarm in wakeUpIntent: " + e);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (buggy_samsung) {
                    alarm.setAlarmClock(new AlarmManager.AlarmClockInfo(wakeTime, null), pendingIntent);
                } else {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
            } else
                alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else {
            Log.e(TAG, "wakeUpIntent - pending intent was null!");
        }
        return wakeTime;
    }


    public static void cancelNotification(int notificationId) {
        final NotificationManager mNotifyMgr = (NotificationManager) BackgroundClass.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.cancel(notificationId);
    }




    public static boolean areWeRunningOnAndroidWear() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                && (BackgroundClass.getAppContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH;
    }

    public static boolean isAirplaneModeEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }


    public static byte[] convertPinToBytes(String pin) {
        if (pin == null) {
            return null;
        }
        byte[] pinBytes;
        try {
            pinBytes = pin.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF-8 not supported?!?");  // this should not happen
            return null;
        }
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    public static boolean doPairingRequest(Context context, BroadcastReceiver broadcastReceiver, Intent intent, String mBluetoothDeviceAddress) {
        return doPairingRequest(context, broadcastReceiver, intent, mBluetoothDeviceAddress, null);
    }

    @TargetApi(19)
    public static boolean doPairingRequest(Context context, BroadcastReceiver broadcastReceiver, Intent intent, String mBluetoothDeviceAddress, String pinHint) {
        if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) {
                int type = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
                if ((mBluetoothDeviceAddress != null) && (device.getAddress().equals(mBluetoothDeviceAddress))) {
                    if ((type == PAIRING_VARIANT_PIN) && (pinHint != null)) {
                        device.setPin(convertPinToBytes(pinHint));
                        Log.d(TAG, "Setting pairing pin to " + pinHint);
                        broadcastReceiver.abortBroadcast();
                    }
                    //Übersprungen
                    /*
                    try {
                        Log.e(TAG, "Pairing type: " + type);
                        if (type != PAIRING_VARIANT_PIN) {
                            device.setPairingConfirmation(true);
                            JoH.static_toast_short("xDrip Pairing");
                            broadcastReceiver.abortBroadcast();
                        } else {
                            Log.d(TAG,"Attempting to passthrough PIN pairing");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Could not set pairing confirmation due to exception: " + e);
                        if (JoH.ratelimit("failed pair confirmation", 200)) {
                            // BluetoothDevice.PAIRING_VARIANT_CONSENT)
                            if (type == 3) {
                                JoH.static_toast_long("Please confirm the bluetooth pairing request");
                                return false;
                            } else {
                                JoH.static_toast_long("Failed to pair, may need to do it via Android Settings");
                                device.createBond(); // for what it is worth
                                return false;
                            }
                        }
                    }*/
                } else {
                    Log.e(TAG, "Received pairing request for not our device: " + device.getAddress());
                }
            } else {
                Log.e(TAG, "Device was null in pairing receiver");
            }
        }
        return true;
    }


    public static boolean refreshDeviceCache(String thisTAG, BluetoothGatt gatt){
        try {
            final Method method = gatt.getClass().getMethod("refresh", new Class[0]);
            if (method != null) {
                return (Boolean) method.invoke(gatt, new Object[0]);
            }
        }
        catch (Exception e) {
            Log.e(thisTAG, "An exception occured while refreshing gatt device cache: "+e);
        }
        return false;
    }

    public synchronized static void setBluetoothEnabled(Context context, boolean state) {
        try {

            if (isAirplaneModeEnabled(context)) {
                Log.e(TAG, "Not setting bluetooth to state: " + state + " due to airplane mode being enabled");
                return;
            }

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {

                final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bluetoothManager == null) {
                    Log.e(TAG, "Couldn't get bluetooth in setBluetoothEnabled");
                    return;
                }
                BluetoothAdapter mBluetoothAdapter = bluetoothManager.getAdapter(); // local scope only
                if (mBluetoothAdapter == null) {
                    Log.e(TAG, "Couldn't get bluetooth adapter in setBluetoothEnabled");
                    return;
                }
                try {
                    if (state) {
                        Log.i(TAG, "Setting bluetooth enabled");
                        mBluetoothAdapter.enable();
                    } else {
                        Log.i(TAG, "Setting bluetooth disabled");
                        mBluetoothAdapter.disable();

                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception when enabling/disabling bluetooth: " + e);
                }
            } else {
                Log.e(TAG, "Bluetooth low energy not supported");
            }
        } finally {
            //
        }
    }


    public synchronized static void restartBluetooth(final Context context, final int startInMs) {
        new Thread() {
            @Override
            public void run() {
                final PowerManager.WakeLock wl = getWakeLock("restart-bluetooth", 60000);
                Log.d(TAG, "Restarting bluetooth");
                try {
                    if (startInMs > 0) {
                        try {
                            Thread.sleep(startInMs);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Got interrupted waiting to start resetBluetooth");
                        }
                    }
                    setBluetoothEnabled(context, false);
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Got interrupted in resetBluetooth");
                    }
                    setBluetoothEnabled(context, true);
                } finally {
                    releaseWakeLock(wl);
                }
            }
        }.start();
    }

    public static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            //
        }
    }

    //Neu, von ChipherUtils
    public static byte[] getRandomKey() {
        byte[] keybytes = new byte[16];
        SecureRandom sr = new SecureRandom();
        sr.nextBytes(keybytes);
        return keybytes;
    }

    public static byte[] concatenateByteArrays(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }



}
