package com.docsinclouds.glucose;

import android.app.IntentService;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.docsinclouds.glucose.G5Model.GlucoseRxMessage;
import com.docsinclouds.glucose.GlucoseDataBase.GlucoEntity;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * This Service connects to the server and is used to transmit the glucosedata
 */
public class WebsocketService extends Service {

  public static final String TAG = WebsocketService.class.getSimpleName();
  public final static String ACTION_NEW_GLUC_DATA = "com.docsinclouds.glucose.actionNewData";
  public final static String EXTRA_PROTOMESSAGE = "protobuf_gluc";
  public final static String EXTRA_GLUCVALUE = "value_gluc";
  public final static String EXTRA_TIMESTAMP_STRING = "timestamp_gluc";

  private URI ws_uri;
  private WsClient wsClient;
  private BroadcastReceiver sensorReceiver;

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /**
   * This method sets up the Broadcastreceiver for the glucosevalues and opens a connection to the
   * wsClient
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Receives Glucosevalues from the Collectionservice or demomode
    sensorReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(ACTION_NEW_GLUC_DATA)) {
          final int val = intent.getIntExtra(EXTRA_GLUCVALUE, -1);
          byte[] messagePM_byte = ProtoMessageBuilder.buildSingleRawValueBytes(val,
              HelperClass.ISO8601DATEFORMAT.format(HelperClass.tsl()));
          if (wsClient.isOpen()) {
            wsClient.send(messagePM_byte);
            Log.d(TAG, "Sent Glucosemessage via wsClient at time " +
                HelperClass.ISO8601DATEFORMAT.format(HelperClass.tsl()).substring(11, 19));
          } else {
            Log.e(TAG, "Connection to websocket is not open at time " +
                HelperClass.ISO8601DATEFORMAT.format(HelperClass.tsl()).substring(11, 19));
                MainActivity.showToast("Connection to websocket is not open");
          }

        }
      }
    };

    LocalBroadcastManager.getInstance(MainActivity.contextOfApplication).registerReceiver(
        sensorReceiver,
        new IntentFilter(ACTION_NEW_GLUC_DATA));

    SharedPreferences prefs = PreferenceManager
        .getDefaultSharedPreferences(MainActivity.contextOfApplication);

    try {
      String socketUri = prefs.getString(MainActivity.contextOfApplication.getResources().
          getString(R.string.prefs_websocketUriString),"");
      ws_uri = new URI(socketUri);
      Log.d(TAG,"Connecting to websocket" + ws_uri.toASCIIString());
    } catch (URISyntaxException e) {
      e.printStackTrace();
      MainActivity.showToast("WebsocketService: Problem with URI");
    }
    Log.d(TAG, "Creating new wsClient");
    wsClient = new WsClient(ws_uri);
    if (!wsClient.isOpen()) {
      wsClient.connect();
      Log.d(TAG, "Started connection to wsClient");
    }
    return START_STICKY;
  }

  /**
   * This method is used to close wsClient
   */
  @Override
  public void onDestroy() {
    Log.d(TAG, "Websocket service closing.");
    LocalBroadcastManager.getInstance(MainActivity.contextOfApplication)
        .unregisterReceiver(sensorReceiver);
    wsClient.close();
    wsClient = null;

    super.onDestroy();
  }
}
