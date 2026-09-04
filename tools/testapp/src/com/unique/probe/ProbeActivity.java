package com.unique.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProbeActivity extends Activity {
    private static final String TAG = ProbeApplication.TAG;
    public static final String RESULT_FILE = "probe-result.properties";

    /** Permission answers seen before the request and in the callback, written once. */
    private final Map<String, String> mPermissionObservations = new LinkedHashMap<String, String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.BLACK);
        view.setPadding(32, 96, 32, 32);
        setContentView(view);

        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            collect(out);
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "probe failed", t);
        }
        writeResult(out);

        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : out.entrySet()) {
            Log.i(TAG, e.getKey() + "=" + e.getValue());
            text.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        view.setText(text.toString());

        Intent request = getIntent();
        if (request != null && request.getBooleanExtra("probe.startService", false)) {
            Log.i(TAG, "starting own service");
            startService(new Intent(this, ProbeService.class));
        }
        if (request != null && request.getBooleanExtra("probe.bindService", false)) {
            Log.i(TAG, "binding own service");
            bindService(new Intent(this, ProbeService.class), new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    Log.i(TAG, "onServiceConnected " + name.flattenToShortString());
                    writeConnection(name, binder);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    Log.i(TAG, "onServiceDisconnected");
                }
            }, BIND_AUTO_CREATE);
        }
        if (request != null && request.getBooleanExtra("probe.queryProvider", false)) {
            Log.i(TAG, "querying own provider");
            queryOwnProvider();
        }
        if (request != null && request.getBooleanExtra("probe.startSecond", false)) {
            Log.i(TAG, "starting own second activity");
            startActivity(new Intent(this, ProbeSecondActivity.class)
                    .putExtra("probe.second.extra", "carried-through"));
        }
        if (request != null && request.getBooleanExtra("probe.pendingIntent", false)) {
            Log.i(TAG, "firing own PendingIntent");
            firePendingIntent();
        }
        if (request != null && request.getBooleanExtra("probe.permissions", false)) {
            Log.i(TAG, "exercising runtime permissions");
            exercisePermissions();
        }
        if (request != null && request.getBooleanExtra("probe.appops", false)) {
            Log.i(TAG, "exercising app ops");
            exerciseAppOps();
        }
        if (request != null && request.getBooleanExtra("probe.notify", false)) {
            Log.i(TAG, "posting own notification");
            postNotification();
        }
        if (request != null && request.getBooleanExtra("probe.crash", false)) {
            Log.w(TAG, "crashing on request");
            throw new IllegalStateException("Deliberate probe crash");
        }
    }

    private void collect(Map<String, String> out) throws Exception {
        out.put("packageName", getPackageName());
        out.put("applicationClass", getApplication().getClass().getName());
        out.put("applicationOnCreateRan",
                String.valueOf(ProbeApplication.applicationOnCreateAt != 0L));
        out.put("applicationBeforeActivity",
                String.valueOf(ProbeApplication.applicationOnCreateAt != 0L
                        && ProbeApplication.applicationOnCreateAt < System.nanoTime()));
        out.put("activityClass", getClass().getName());
        out.put("componentName", getComponentName().flattenToString());
        out.put("pid", String.valueOf(android.os.Process.myPid()));
        out.put("taskId", String.valueOf(getTaskId()));
        out.put("uid", String.valueOf(android.os.Process.myUid()));
        out.put("dataDir", getDataDir().getAbsolutePath());
        out.put("filesDir", getFilesDir().getAbsolutePath());
        out.put("cacheDir", getCacheDir().getAbsolutePath());
        out.put("codeCacheDir", getCodeCacheDir().getAbsolutePath());
        out.put("packageCodePath", getPackageCodePath());
        out.put("packageResourcePath", getPackageResourcePath());
        out.put("appInfoDataDir", getApplicationInfo().dataDir);
        out.put("appInfoSourceDir", getApplicationInfo().sourceDir);
        out.put("appInfoNativeLibraryDir", String.valueOf(getApplicationInfo().nativeLibraryDir));
        out.put("targetSdk", String.valueOf(getApplicationInfo().targetSdkVersion));

        // SharedPreferences: the counter is the persistence proof across restarts.
        SharedPreferences prefs = getSharedPreferences("probe", MODE_PRIVATE);
        int launchCount = prefs.getInt("launchCount", 0) + 1;
        prefs.edit().putInt("launchCount", launchCount).commit();
        out.put("launchCount", String.valueOf(launchCount));
        out.put("sharedPrefsFile",
                new File(new File(getDataDir(), "shared_prefs"), "probe.xml").getAbsolutePath());

        // A plain file, appended once per launch.
        File log = new File(getFilesDir(), "launches.log");
        FileOutputStream fos = new FileOutputStream(log, true);
        fos.write((System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
        fos.close();
        out.put("fileLineCount", String.valueOf(countLines(log)));
        out.put("filePath", log.getAbsolutePath());

        // SQLite, through the platform's own path resolution.
        SQLiteDatabase db = openOrCreateDatabase("probe.db", MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS launches (id INTEGER PRIMARY KEY, at INTEGER)");
        db.execSQL("INSERT INTO launches (at) VALUES (" + System.currentTimeMillis() + ")");
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM launches", null);
        c.moveToFirst();
        out.put("dbRowCount", String.valueOf(c.getInt(0)));
        c.close();
        out.put("dbPath", getDatabasePath("probe.db").getAbsolutePath());
        db.close();
    }

    private int countLines(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int read = in.read(data);
        in.close();
        int lines = 0;
        for (int i = 0; i < read; i++) if (data[i] == '\n') lines++;
        return lines;
    }

    /**
     * Writes the observations where the verification suite can read them.
     *
     * The suite runs in UNIQUE's own process and reads this file from the instance's
     * directory - which also proves the file landed where UNIQUE intended it to.
     */
    private void writeResult(Map<String, String> out) {
        try {
            File f = new File(getFilesDir(), RESULT_FILE);
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, false), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> e : out.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
            w.flush();
            w.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "could not write result file", t);
        }
    }

    /**
     * Records what the *client* side of the bind observed.
     *
     * Written separately from the service's own file because the two can disagree: the
     * ComponentName the framework hands back is the one AMS knows about, which under
     * virtualization is the stub's.
     */
    private void writeConnection(ComponentName name, IBinder binder) {
        try {
            File f = new File(getFilesDir(), "probe-connection.properties");
            FileOutputStream out = new FileOutputStream(f, false);
            String body = "connected=true\n"
                    + "connectedComponent=" + name.flattenToShortString() + "\n"
                    + "binderClass=" + binder.getClass().getName() + "\n"
                    + "binderIsLocal=" + (binder instanceof ProbeService.LocalBinder) + "\n"
                    + "pid=" + android.os.Process.myPid() + "\n";
            out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "could not write connection result", t);
        }
    }

    /**
     * Queries the app's own ContentProvider through the ContentResolver.
     *
     * Deliberately through the resolver and not by calling the provider class directly:
     * the resolver goes to ActivityManagerService to acquire the authority, which is the
     * step that fails for a package the system has never installed.
     */
    private void queryOwnProvider() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Cursor cursor = null;
        try {
            android.net.Uri uri = android.net.Uri.parse("content://" + ProbeProvider.AUTHORITY + "/rows");
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor == null) {
                out.put("error", "resolver returned no cursor");
            } else {
                out.put("rowCount", String.valueOf(cursor.getCount()));
                while (cursor.moveToNext()) {
                    out.put("provider." + cursor.getString(0), cursor.getString(1));
                }
                out.put("type", String.valueOf(getContentResolver().getType(uri)));
            }
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "provider query failed", t);
        } finally {
            if (cursor != null) cursor.close();
        }
        out.put("callerPid", String.valueOf(android.os.Process.myPid()));
        try {
            File f = new File(getFilesDir(), "probe-provider.properties");
            FileOutputStream fos = new FileOutputStream(f, false);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : out.entrySet()) {
                Log.i(TAG, e.getKey() + "=" + e.getValue());
                body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "could not write provider result", t);
        }
    }

    /**
     * Builds a PendingIntent for the app's own second Activity and fires it.
     *
     * A PendingIntent is assembled by system_server at *creation* time and fired later by
     * whoever holds it, so the component inside has to survive the round trip through the
     * system with nothing of the app's still running to fix it up.
     */
    private void firePendingIntent() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            Intent target = new Intent(this, ProbeSecondActivity.class)
                    .putExtra("probe.second.extra", "via-pending-intent")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, target,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            out.put("created", String.valueOf(pi != null));
            if (pi != null) {
                out.put("creatorPackage", String.valueOf(pi.getCreatorPackage()));
                pi.send();
                out.put("sent", "true");
            }
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "pending intent failed", t);
        }
        try {
            File f = new File(getFilesDir(), "probe-pending.properties");
            FileOutputStream fos = new FileOutputStream(f, false);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : out.entrySet()) {
                Log.i(TAG, e.getKey() + "=" + e.getValue());
                body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable t) {
            Log.e(TAG, "could not write pending intent result", t);
        }
    }

    /**
     * Checks, requests and re-checks a runtime permission, recording every answer.
     *
     * Written the way an ordinary app writes it. Nothing here knows about UNIQUE; the
     * suite compares these answers against what the instance was configured to allow.
     */
    private void exercisePermissions() {
        final String camera = android.Manifest.permission.CAMERA;
        final String mic = android.Manifest.permission.RECORD_AUDIO;
        try {
            // Observations accumulate across the callback, which arrives later on the
            // main thread: writing them in two passes would leave the file holding only
            // whichever pass ran last.
            mPermissionObservations.put("cameraBefore", named(checkSelfPermission(camera)));
            mPermissionObservations.put("micBefore", named(checkSelfPermission(mic)));
            mPermissionObservations.put("cameraViaPm", named(getPackageManager()
                    .checkPermission(camera, getPackageName())));
            mPermissionObservations.put("cameraRationaleBefore",
                    String.valueOf(shouldShowRequestPermissionRationale(camera)));

            requestPermissions(new String[]{camera}, 4242);
        } catch (Throwable t) {
            mPermissionObservations.put("error", t.toString());
            Log.e(TAG, "permission exercise failed", t);
            writeMap("probe-permissions.properties", mPermissionObservations);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mPermissionObservations.put("requestCode", String.valueOf(requestCode));
        for (int i = 0; i < permissions.length; i++) {
            mPermissionObservations.put("result." + permissions[i], named(grantResults[i]));
        }
        // The re-check is the point: a grant recorded for this instance has to be visible
        // through the ordinary API immediately afterwards.
        mPermissionObservations.put("cameraAfter",
                named(checkSelfPermission(android.Manifest.permission.CAMERA)));
        mPermissionObservations.put("micAfter",
                named(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)));
        // Through PackageManager as well, which reaches a different system service. A
        // guest that gets GRANTED from one route and DENIED from the other is worse than
        // one that is consistently denied, so both are recorded before and after.
        mPermissionObservations.put("cameraViaPmAfter", named(getPackageManager()
                .checkPermission(android.Manifest.permission.CAMERA, getPackageName())));
        mPermissionObservations.put("cameraRationaleAfter", String.valueOf(
                shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)));
        writeMap("probe-permissions.properties", mPermissionObservations);
    }

    private static String named(int result) {
        return result == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "GRANTED" : "DENIED";
    }

    private void writeMap(String fileName, Map<String, String> values) {
        try {
            File f = new File(getFilesDir(), fileName);
            FileOutputStream fos = new FileOutputStream(f, false);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : values.entrySet()) {
                Log.i(TAG, e.getKey() + "=" + e.getValue());
                body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "could not write " + fileName, t);
        }
    }

    /**
     * Asks AppOpsManager about this app, the way the framework does on the way into the
     * camera, the microphone and the clipboard.
     *
     * `checkPackage` throws when the package name does not belong to the calling uid,
     * which under virtualization it does not, so an unhandled app-op layer shows up as a
     * SecurityException from an API that has nothing obviously to do with app ops.
     */
    private void exerciseAppOps() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        android.app.AppOpsManager ops =
                (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
        try {
            ops.checkPackage(android.os.Process.myUid(), getPackageName());
            out.put("checkPackage", "ok");
        } catch (Throwable t) {
            out.put("checkPackage", t.getClass().getSimpleName());
            Log.e(TAG, "checkPackage failed", t);
        }
        try {
            int mode = ops.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_CAMERA,
                    android.os.Process.myUid(), getPackageName());
            out.put("cameraOp", String.valueOf(mode));
        } catch (Throwable t) {
            out.put("cameraOp", t.getClass().getSimpleName());
        }
        out.put("packageName", getPackageName());
        writeMap("probe-appops.properties", out);
    }

    /**
     * Creates a channel and posts a notification, the way any app does.
     *
     * The content PendingIntent points at this app's own second Activity, so a tap has to
     * come back into this instance and not into UNIQUE or into a sibling.
     */
    private void postNotification() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "probe-channel", "Probe", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Posted by the UNIQUE probe");
            nm.createNotificationChannel(channel);
            out.put("channelCreated", "probe-channel");

            android.app.PendingIntent tap = android.app.PendingIntent.getActivity(
                    this, 0,
                    new Intent(this, ProbeSecondActivity.class)
                            .putExtra("probe.second.extra", "via-notification")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification n = new android.app.Notification.Builder(this, "probe-channel")
                    .setContentTitle("probe-notification")
                    .setContentText("posted by " + getPackageName())
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            nm.notify(4711, n);
            out.put("posted", "4711");
            out.put("packageName", getPackageName());
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "notification failed", t);
        }
        writeMap("probe-notification.properties", out);
    }
}
