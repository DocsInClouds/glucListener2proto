package com.docsinclouds.glucose;

import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by jamorham on 07/03/2018.
 *
 * Tasks which are fired from events can be scheduled here and only execute when they become idle
 * and are not being rescheduled within their wait window.
 *
 */

public class Inevitable {

    private static final String TAG = Inevitable.class.getSimpleName();
    private static final int MAX_QUEUE_TIME = (int) Constants.MINUTE_IN_MS * 5;
    private static final boolean d = true;

    private static final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    public static synchronized void task(final String id, long idle_for, Runnable runnable) {
        final Task task = tasks.get(id);
        if (task != null) {
            // if it already exists then extend the time
            task.extendTime(idle_for);

            if (d)
                Log.d(TAG, "Extending time for: " + id + " to " + HelperClass.dateTimeText(task.when));
        } else {
            // otherwise create new task
            if (runnable == null) return; // extension only if already exists
            tasks.put(id, new Task(id, idle_for, runnable));

            if (d)
                Log.d(TAG, "Creating task: " + id + " due: " + HelperClass.dateTimeText(tasks.get(id).when));

            // create a thread to wait and execute in background
            final Thread t = new Thread(() -> {
                final PowerManager.WakeLock wl = HelperClass.getWakeLock(id, MAX_QUEUE_TIME + 5000);
                try {
                    boolean running = true;
                    // wait for task to be due or killed
                    while (running) {
                        HelperClass.threadSleep(500);
                        final Task thisTask = tasks.get(id);
                        running = thisTask != null && !thisTask.poll();
                    }
                } finally {
                    HelperClass.releaseWakeLock(wl);
                }
            });
            t.setPriority(Thread.MIN_PRIORITY);
            //t.setDaemon(true);
            t.start();
        }
    }

    public void kill(final String id) {
        tasks.remove(id);
    }


    private static class Task {
        private long when;
        private final Runnable what;
        private final String id;

        Task(String id, long offset, Runnable what) {
            this.what = what;
            this.id = id;
            extendTime(offset);
        }

        public void extendTime(long offset) {
            this.when = HelperClass.tsl() + offset;
        }

        public boolean poll() {
            final long till = HelperClass.msTill(when);
            if (till < 1) {
                if (d) Log.d(TAG, "Executing task!");
                tasks.remove(this.id); // early remove to allow overlapping scheduling
                what.run();
                return true;
            } else if (till > MAX_QUEUE_TIME) {
                Log.wtf(TAG, "In queue too long: " + till);
                tasks.remove(this.id);
                return true;
            }
            return false;
        }

    }

}
