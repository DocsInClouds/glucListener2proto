package com.docsinclouds.glucose;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;


/**
 * Created by Emma Black on 12/22/14.
 */
public class CollectionServiceStarter {
    private Context mContext;

    private final static String TAG = CollectionServiceStarter.class.getSimpleName();
    final public static String pref_run_wear_collector = "run_wear_collector"; // only used on wear but here for code compatibility

    public static void restartCollectionService(Context context) {
        if (context == null) context = BackgroundClass.getAppContext();
        CollectionServiceStarter collectionServiceStarter = new CollectionServiceStarter();
        collectionServiceStarter.stopG5ShareService();
        collectionServiceStarter.start(context);
    }

    private void stopG5ShareService() {
        //Übersprungen
        Log.d(TAG, "stopping G5  service got replaced");
        //Ob1G5CollectionService.keep_running = false; // ensure zombie stays down
        //this.mContext.stopService(new Intent(this.mContext, Ob1G5CollectionService.class));
        //Ob1G5CollectionService.resetSomeInternalState();
    }

    public void start(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String collection_method = prefs.getString("dex_collection_method", "BluetoothWixel");

        start(context, collection_method);
    }

    public void start(Context context, String collection_method) {
        Log.d(TAG, "Aufruf: CollectionServiceStarter.start, dort viel gelöscht");
    }


}
