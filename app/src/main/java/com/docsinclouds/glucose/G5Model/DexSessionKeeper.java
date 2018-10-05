package com.docsinclouds.glucose.G5Model;



// jamorham

// track active session time

import com.docsinclouds.glucose.Constants;
import com.docsinclouds.glucose.HelperClass;
import com.docsinclouds.glucose.PersistentStore;

public class DexSessionKeeper {

    private static final String PREF_SESSION_START = "OB1-SESSION-START";
    private static final long WARMUP_PERIOD = Constants.HOUR_IN_MS * 2;

    public static void clearStart() {
        PersistentStore.setLong(PREF_SESSION_START, 0);
    }

    public static void setStart(long when) {
        // TODO sanity check
        PersistentStore.setLong(PREF_SESSION_START, when);
    }

    public static long getStart() {
        // value 0 == not started
        return PersistentStore.getLong(PREF_SESSION_START);
    }

    public static boolean isStarted() {
        return getStart() != 0;
    }

    public static String prettyTime() {
        if (isStarted()) {
            final long elapsed = HelperClass.msSince(getStart());
            if (elapsed < WARMUP_PERIOD) {
                return HelperClass.niceTimeScalar((double) WARMUP_PERIOD - elapsed, 1);
            } else {
                return HelperClass.niceTimeScalar((double) elapsed, 1);
            }
        } else {
            return "";
        }
    }
}
