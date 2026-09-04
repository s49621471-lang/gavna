package com.unique.probe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A service that records what it observed, the way the Activity does.
 *
 * Started and bound modes are both exercised: they take different paths through
 * ActivityThread (SERVICE_ARGS vs BIND_SERVICE) and a virtualization layer can easily get
 * one right and the other wrong.
 */
public class ProbeService extends Service {
    private static final String TAG = ProbeApplication.TAG;
    public static final String RESULT_FILE = "probe-service.properties";

    public class LocalBinder extends Binder {
        public String packageNameFromService() {
            return getPackageName();
        }
    }

    private final IBinder mBinder = new LocalBinder();
    private int mStartCount = 0;
    private int mBindCount = 0;
    /** The component the bind Intent named, as the service itself saw it. */
    private String mBindComponent = "-";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service.onCreate package=" + getPackageName()
                + " process=" + android.os.Process.myPid());
        write("created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mStartCount++;
        Log.i(TAG, "Service.onStartCommand startId=" + startId + " count=" + mStartCount);
        write("started");
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        mBindCount++;
        mBindComponent = intent == null || intent.getComponent() == null
                ? "-" : intent.getComponent().flattenToShortString();
        Log.i(TAG, "Service.onBind count=" + mBindCount + " component=" + mBindComponent);
        write("bound");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Service.onUnbind");
        return false;
    }

    private void write(String stage) {
        try {
            File f = new File(getFilesDir(), RESULT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            String body = "stage=" + stage + "\n"
                    + "packageName=" + getPackageName() + "\n"
                    + "className=" + getClass().getName() + "\n"
                    + "filesDir=" + getFilesDir().getAbsolutePath() + "\n"
                    + "pid=" + android.os.Process.myPid() + "\n"
                    + "startCount=" + mStartCount + "\n"
                    + "bindCount=" + mBindCount + "\n"
                    + "bindComponent=" + mBindComponent + "\n";
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable t) {
            Log.e(TAG, "service could not write its result", t);
        }
    }
}
