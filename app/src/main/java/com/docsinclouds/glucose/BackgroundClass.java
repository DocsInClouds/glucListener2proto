package com.docsinclouds.glucose;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.Locale;

//import com.crashlytics.android.Crashlytics;
//import com.crashlytics.android.core.CrashlyticsCore;
//import com.docsinclouds.glucosense.webservices.XdripWebService;

//import com.bugfender.sdk.Bugfender;

/**
 * Created by Emma Black on 3/21/15.
 */

public class BackgroundClass extends Application {

    private static final String TAG = "xdrip.java";
    @SuppressLint("StaticFieldLeak")
    private static Context context;
    //   private static boolean fabricInited = false;   übersprungen
    private static boolean bfInited = false;
    private static Locale LOCALE;

    public static boolean useBF = false;
    private static Boolean isRunningTestCache;


    @Override
    public void onCreate() {
        BackgroundClass.context = getApplicationContext();
        super.onCreate();





        HelperClass.ratelimit("policy-never", 3600); // don't on first load


        if (!isRunningTest()) {
            Log.d(TAG, "In xdrip.java werden viele Funktionen nicht gestartet");
            //MissedReadingService.delayedLaunch();

            //new CollectionServiceStarter(getApplicationContext()).start(getApplicationContext());
            new CollectionServiceStarter();

            //PlusSyncService.startSyncService(context, "xdrip.java");
            //BluetoothGlucoseMeter.startIfEnabled();
            //ÜbersprungenXdripWebService.immortality();

        } else {
            Log.d(TAG, "Detected running test mode, holding back on background processes");
        }

    }
//übersprungen
    /*
    public synchronized static void initCrashlytics(Context context) {
        if ((!fabricInited) && !isRunningTest()) {
            try {
                Crashlytics crashlyticsKit = new Crashlytics.Builder()
                        .core(new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
                        .build();
                Fabric.with(context, crashlyticsKit);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
            fabricInited = true;
        }
    }*/

    public static synchronized boolean isRunningTest() {
        if (null == isRunningTestCache) {
            boolean test_framework;
            try {
                Class.forName("android.support.test.espresso.Espresso");
                test_framework = true;
            } catch (ClassNotFoundException e) {
                test_framework = false;
            }
            isRunningTestCache = test_framework;
        }
        return isRunningTestCache;
    }

    public synchronized static void initBF() {
        try {
            if (PreferenceManager.getDefaultSharedPreferences(BackgroundClass.context).getBoolean("enable_bugfender", false)) {
                new Thread() {
                    @Override
                    public void run() {
                        String app_id = PreferenceManager.getDefaultSharedPreferences(BackgroundClass.context).getString("bugfender_appid", "").trim();
                        if (!useBF && (app_id.length() > 10)) {
                            if (!bfInited) {
                                //Bugfender.init(xdrip.context, app_id, BuildConfig.DEBUG);
                                bfInited = true;
                            }
                            useBF = true;
                        }
                    }
                }.start();
            } else {
                useBF = false;
            }
        } catch (Exception e) {
            //
        }
    }


    public static Context getAppContext() {
        return BackgroundClass.context;
    }

    public static boolean checkAppContext(Context context) {
        if (getAppContext() == null) {
            BackgroundClass.context = context;
            return false;
        } else {
            return true;
        }
    }


    //}
}
