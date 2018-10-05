package com.docsinclouds.glucose;

import static com.docsinclouds.glucose.WebsocketService.ACTION_NEW_GLUC_DATA;
import static com.docsinclouds.glucose.WebsocketService.EXTRA_GLUCVALUE;

import android.Manifest;
import android.Manifest.permission;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.arch.persistence.room.Room;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import com.docsinclouds.glucose.GlucoseDataBase.AppDatabase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides the UI, starts WebsocketService, G5CollectionService and the room-database
 * that is used to save Glucosevalues. Additionally, it provides a framework for the demomode.
 */
public class MainActivity extends AppCompatActivity {

  private static final String TAG = "MainActivity";
  private static final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 1;
  public static Context contextOfApplication;
  public static AppDatabase glucoseDatabase;
  public static final String GLUC_NOTIFICATION_CHANNEL_ID = "glucNotification1";
  private static boolean demomode_active = false;
  private DemoValues demovalues;

  /**
   * This method loads the UI, starts WebsocketService, G5CollectionService and the room-database
   * that is used to save Glucosevalues. Additionally, it provides a framework for the demomode.
   */
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    contextOfApplication = getApplicationContext();

    getWindow().addFlags(LayoutParams.FLAG_TURN_SCREEN_ON | LayoutParams.FLAG_KEEP_SCREEN_ON);
    // set orientation fixed
    setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

    final SharedPreferences prefs = PreferenceManager
        .getDefaultSharedPreferences(getApplicationContext());

    // Check for permissions
    List<String> permissionsNeeded = new ArrayList<String>();
    final List<String> permissionsList = new ArrayList<String>();
    if (!addPermission(permissionsList, permission.ACCESS_COARSE_LOCATION)) {
      permissionsNeeded.add("Location");
    }
    if (!addPermission(permissionsList, permission.WRITE_EXTERNAL_STORAGE)) {
      permissionsNeeded.add("ext storage");
    }
    if (permissionsList.size() > 0) {
      ActivityCompat.requestPermissions(MainActivity.this,
          permissionsList.toArray(new String[permissionsList.size()]),
          REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
      return;
    }
    Log.d(TAG, "Permissions already present");

    // get handles of all used UI elements
    final EditText transmitterId = findViewById(R.id.transmitterId);
    final EditText websocketUri = findViewById(R.id.websocketUri);
    final Switch switchG5Service = findViewById(R.id.switchG5Service);
    final Switch switchDemomode = findViewById(R.id.switchDemomode);
    final EditText numberInterval = findViewById(R.id.interval);
    final EditText singleValue = findViewById(R.id.singleValue);
    final Switch switchSingleValue = findViewById(R.id.switchSingleValue);
    final Button buttonSingleValue = findViewById(R.id.buttonSendSingleValue);

    buttonSingleValue.setEnabled(false);

    // Use last entry for the wsURI and transmitter ID
    websocketUri.setText(prefs.getString(getString(R.string.prefs_websocketUriString),"ws://172.20.10.2:4568"));
    transmitterId.setText(prefs.getString(getString(R.string.prefs_dexcomTransmitterId),"4G0W4A"));

    Handler handler = new Handler();
    //For demomode
    final Intent WSServiceIntent = new Intent(this, WebsocketService.class);

    // Switch for Singlevalue
    switchSingleValue.setOnClickListener(v -> {
      if (switchSingleValue.isChecked()) {

        // Start websocketService
        startService(WSServiceIntent);

        transmitterId.setEnabled(false);
        websocketUri.setEnabled(false);
        numberInterval.setEnabled(false);
        switchDemomode.setEnabled(false);
        switchG5Service.setEnabled(false);
        buttonSingleValue.setEnabled(true);

        Log.d(TAG, "Switch: singlevalue mode started");
        final String interval = numberInterval.getText().toString();
        final String transId = transmitterId.getText().toString().toUpperCase();
        final String socketUri = websocketUri.getText().toString();
        final String singleValueInt = singleValue.getText().toString();

        // Applying the current variables
        prefs.edit().putString(getString(R.string.prefs_dexcomTransmitterId), transId)
            .putString(getString(R.string.prefs_websocketUriString), socketUri)
            .putString(getString(R.string.prefs_demomodeinterval), interval)
            .putString(getString(R.string.prefs_singlevalue), singleValueInt)
            .apply(); //  set transmitter id from edit field

        // Activate button for singleValue
        buttonSingleValue.setOnClickListener(new View.OnClickListener() {
          public void onClick(View v) {
            final String singleValueInt = singleValue.getText().toString();
            prefs.edit().putString(getString(R.string.prefs_singlevalue), singleValueInt)
                .apply();
            Intent intent = new Intent();
            intent.setAction(ACTION_NEW_GLUC_DATA);
            intent.putExtra(EXTRA_GLUCVALUE, Integer.parseInt(singleValueInt));
            LocalBroadcastManager.getInstance(MainActivity.contextOfApplication).sendBroadcast(intent);

          }
        });
      }
      else {
        //Stop the websocketService
        stopService(WSServiceIntent);

        transmitterId.setEnabled(true);
        websocketUri.setEnabled(true);
        numberInterval.setEnabled(true);
        switchG5Service.setEnabled(true);
        switchDemomode.setEnabled(true);
        buttonSingleValue.setEnabled(false);

        Log.d(TAG, "Switch: singlevalue mode stopped");
      }

    });


    switchDemomode.setOnClickListener(v -> {
      if (switchDemomode.isChecked()) {
        Log.d(TAG, "Switch: Demomode started");
        //Get settings from textfield
        final String interval = numberInterval.getText().toString();
        final String transId = transmitterId.getText().toString().toUpperCase();
        final String socketUri = websocketUri.getText().toString();


        // Check if transmitterID is valid
        if (transId.length()!=6) {
          Toast.makeText(this, "Wrong format of Transmitter ID", Toast.LENGTH_LONG).show();
          switchDemomode.setChecked(false);
          return;
        }
        // Check if socketURI is valid
        try {
          Uri.parse(socketUri);
        } catch (Exception e) {
          Toast.makeText(this, "Wrong format of URI", Toast.LENGTH_LONG).show();
          switchDemomode.setChecked(false);
          return;
        }

        // Applying the current variables
        prefs.edit().putString(getString(R.string.prefs_dexcomTransmitterId), transId)
            .putString(getString(R.string.prefs_websocketUriString), socketUri)
            .putString(getString(R.string.prefs_demomodeinterval), interval)
            .apply(); //  set transmitter id from edit field

        // Mark textfields as unusable
        transmitterId.setEnabled(false);
        websocketUri.setEnabled(false);
        numberInterval.setEnabled(false);
        switchG5Service.setEnabled(false);
        singleValue.setEnabled(false);
        buttonSingleValue.setEnabled(false);
        switchSingleValue.setEnabled(false);

        // Create notification channel
        createNotificationChannel();

        // Start websocketService
        startService(WSServiceIntent);

        // Start demomode
        demomode_active = true;
        demovalues = new DemoValues();
        demomode(Integer.parseInt(interval), handler);
      } else { // switch state is unchecked
        demomode_active = false;
        demomodeStop(handler);

        //Stop the websocketService
        stopService(WSServiceIntent);

        // Enable textfields
        transmitterId.setEnabled(true);
        websocketUri.setEnabled(true);
        numberInterval.setEnabled(true);
        switchG5Service.setEnabled(true);
        singleValue.setEnabled(true);
        buttonSingleValue.setEnabled(false);
        switchSingleValue.setEnabled(true);

        Log.d(TAG, "Switch: Demomode stopped");
      }
    });



    //For G5 mode
    switchG5Service.setOnClickListener(v -> {
      if (switchG5Service.isChecked()) {
        Log.d(TAG, "Switch: G5Mode started");
        //Get settings from textfield
        final String transId = transmitterId.getText().toString().toUpperCase();
        final String socketUri = websocketUri.getText().toString();

        // Check if transmitterID is valid
        if (transId.length()!=6) {
          Toast.makeText(this, "Wrong format of Transmitter ID", Toast.LENGTH_LONG).show();
          switchG5Service.setChecked(false);
          return;
        }
        // Check if socketURI is valid
        try {
          Uri.parse(socketUri);
        } catch (Exception e) {
          Toast.makeText(this, "Wrong format of URI", Toast.LENGTH_LONG).show();
          switchG5Service.setChecked(false);
          return;
        }

        // Applying the current variables
        prefs.edit().putString(getString(R.string.prefs_dexcomTransmitterId), transId)
            .putString(getString(R.string.prefs_websocketUriString), socketUri)
            .apply(); //  set transmitter id from edit field

        // Mark textfields as unusable
        transmitterId.setEnabled(false);
        websocketUri.setEnabled(false);
        numberInterval.setEnabled(false);
        switchDemomode.setEnabled(false);
        singleValue.setEnabled(false);
        buttonSingleValue.setEnabled(false);
        switchSingleValue.setEnabled(false);

        // Start websocketService
        startService(WSServiceIntent);

        // Start G5Service
        Intent G5ServiceIntent = new Intent(this, G5CollectionService.class);
        G5ServiceIntent.putExtra("IntentStarter",
            "CustomSensorDataListener.SensorMessageInterface"); // Use putExtra for callback
        startService(G5ServiceIntent);
      } else { // G5 switch not checked
        //Stop the websocketService
        stopService(WSServiceIntent);

        //Stop the G5Service
        Intent G5ServiceIntent = new Intent(this, G5CollectionService.class);
        PendingIntent pendingIntent = PendingIntent
            .getService(this, 0, G5ServiceIntent, 0);
        final AlarmManager alarm = (AlarmManager) this.getSystemService(ALARM_SERVICE);
        try {
          alarm.cancel(pendingIntent);
        } catch (Exception e) {
          Log.e(TAG, "Exception cancelling alarm while stopping G5 mode " + e);
        }
        stopService(G5ServiceIntent);

        stopNotificationChannel();

        // Enable textfields
        transmitterId.setEnabled(true);
        websocketUri.setEnabled(true);
        numberInterval.setEnabled(true);
        switchDemomode.setEnabled(true);
        singleValue.setEnabled(true);
        buttonSingleValue.setEnabled(false);
        switchSingleValue.setEnabled(true);

        Log.d(TAG, "Switch: G5Mode stopped");
      }
    });

    // Initialize Database
    glucoseDatabase = Room.databaseBuilder(getApplicationContext(),
        AppDatabase.class, "glucoseDatabase").build();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    getWindow().clearFlags(LayoutParams.FLAG_TURN_SCREEN_ON | LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private boolean addPermission(List<String> permissionsList, String permission) {
    if (ContextCompat.checkSelfPermission(MainActivity.this, permission)
        != PackageManager.PERMISSION_GRANTED) {
      permissionsList.add(permission);
      // Check for Rationale Option
      return ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, permission);
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
      @NonNull String permissions[], @NonNull int[] grantResults) {
    switch (requestCode) {
      case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS: {
        Map<String, Integer> perms = new HashMap<String, Integer>();
        // Initial
        perms.put(Manifest.permission.ACCESS_COARSE_LOCATION, PackageManager.PERMISSION_GRANTED);
        perms.put(permission.WRITE_EXTERNAL_STORAGE, PackageManager.PERMISSION_GRANTED);
        // Fill with results
        for (int i = 0; i < permissions.length; i++) {
          perms.put(permissions[i], grantResults[i]);
        }
        if (perms.get(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED &&
            perms.get(permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
          // All Permissions Granted
          Log.d(TAG, "all permissions present");
        } else {
          // Permission Denied
          Toast.makeText(this, "Some Permission is denied - app might not work correctly",
              Toast.LENGTH_SHORT).show();
        }
        break;
      }
    }
  }

  /**
   * used to show notifications on the smartphone
   */
  private void createNotificationChannel() {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = getString(R.string.channel_name);
      String description = getString(R.string.channel_description);
      int importance = NotificationManager.IMPORTANCE_DEFAULT;
      NotificationChannel channel = new NotificationChannel(GLUC_NOTIFICATION_CHANNEL_ID, name, importance);
      channel.setDescription(description);
      // Register the channel with the system; you can't change the importance
      // or other notification behaviors after this
      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  /**
   * stops the notification channel
   */
  private void stopNotificationChannel() {
    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      NotificationManager notificationManager =
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.deleteNotificationChannel(GLUC_NOTIFICATION_CHANNEL_ID);
    }
  }

  /**
   * Starts the demomodus that skips the interaction with the transmitter and generates
   * glucosedata.
   *
   * @param seconds interval in which glucosevalues are sent
   */
  public void demomode(int seconds, Handler handler) {
    if (demomode_active) {
      // send the testvalue
      final int nextValue = demovalues.getNextDemoValueAndAdvance();
      Log.d(TAG, "Sending testvalue " + Integer.toString(nextValue) + " at time "
          + HelperClass.ISO8601DATEFORMAT.format(HelperClass.tsl()).substring(11, 19));
      Intent intent = new Intent();
      intent.setAction(ACTION_NEW_GLUC_DATA);
      intent.putExtra(EXTRA_GLUCVALUE, nextValue);
      LocalBroadcastManager.getInstance(MainActivity.contextOfApplication).sendBroadcast(intent);
      Runnable periodicMessage = () -> demomode(seconds, handler);
      handler.postDelayed(periodicMessage, seconds * 1000);
    }
  }

  /**
   * Stops the demomode
   */
  public void demomodeStop(Handler handler) {
    handler.removeCallbacksAndMessages(null);
  }

  public static void showToast(String msg) {
    Toast.makeText(contextOfApplication, msg, Toast.LENGTH_SHORT).show();
  }

}