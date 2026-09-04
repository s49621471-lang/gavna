package com.unique.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Records that a broadcast reached the guest, and with which identity.
 *
 * Declared in the manifest with an explicit custom action so the test can send one
 * without relying on any implicit broadcast the platform restricts.
 */
public class ProbeReceiver extends BroadcastReceiver {
    private static final String TAG = ProbeApplication.TAG;
    public static final String ACTION = "com.unique.probe.PING";
    public static final String RESULT_FILE = "probe-receiver.properties";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Receiver.onReceive action=" + intent.getAction()
                + " package=" + context.getPackageName());
        try {
            File f = new File(context.getFilesDir(), RESULT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            String body = "action=" + intent.getAction() + "\n"
                    + "packageName=" + context.getPackageName() + "\n"
                    + "extra=" + intent.getStringExtra("probe.extra") + "\n"
                    + "filesDir=" + context.getFilesDir().getAbsolutePath() + "\n"
                    + "pid=" + android.os.Process.myPid() + "\n";
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable t) {
            Log.e(TAG, "receiver could not write its result", t);
        }
    }
}
