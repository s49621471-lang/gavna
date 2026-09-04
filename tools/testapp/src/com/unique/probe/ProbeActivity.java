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
            startService(new Intent(this, ProbeService.class)
                    .putExtra("probe.foreground",
                            request.getBooleanExtra("probe.foreground", false)));
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
        if (request != null && request.getBooleanExtra("probe.native", false)) {
            Log.i(TAG, "loading own native library");
            exerciseNative();
        }
        if (request != null && request.getBooleanExtra("probe.job", false)) {
            Log.i(TAG, "scheduling own job");
            scheduleJob();
        }
        if (request != null && request.getBooleanExtra("probe.alarmClipboard", false)) {
            Log.i(TAG, "exercising alarms and the clipboard");
            exerciseAlarmAndClipboard();
        }
        if (request != null && request.getBooleanExtra("probe.graphics", false)) {
            Log.i(TAG, "exercising graphics");
            exerciseGraphics();
        }
        if (request != null && request.getBooleanExtra("probe.identity", false)) {
            Log.i(TAG, "checking own signature and the Google stack");
            exercisePackageIdentity();
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

    /**
     * Loads the app's own JNI library and calls into it.
     *
     * `System.loadLibrary` resolves through the ClassLoader's library search path, which
     * the platform builds from ApplicationInfo.nativeLibraryDir - so this fails unless
     * the graft got that directory right *and* the importer extracted an ABI this device
     * can execute.
     */
    private void exerciseNative() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            System.loadLibrary("probenative");
            out.put("loaded", "true");
            out.put("arch", ProbeNative.arch());
            out.put("pageSize", String.valueOf(ProbeNative.pageSize()));
            out.put("nativePid", String.valueOf(ProbeNative.pid()));
            out.put("javaPid", String.valueOf(android.os.Process.myPid()));
            out.put("echo", ProbeNative.echo("hello"));
            out.put("nativeLibraryDir", getApplicationInfo().nativeLibraryDir);
            // Through the *redirected* path the Context gives us. This works with or
            // without native interception, and is the common case.
            java.io.File target = new java.io.File(getFilesDir(), "probe-libc.txt");
            out.put("libcWrite", ProbeNative.writeThroughLibc(target.getAbsolutePath()));
            out.put("libcFileExists", String.valueOf(target.isFile()));

            // Through the path an app *hard-codes* in native code: the canonical location
            // the platform would give an installed app. Nothing in Java rewrites this, so
            // it lands outside the instance unless libc itself is intercepted.
            String canonical = "/data/data/" + getPackageName() + "/files/probe-libc-raw.txt";
            out.put("libcRawWrite", ProbeNative.writeThroughLibc(canonical));
            out.put("libcRawPath", canonical);
            out.put("libcRawLandedInInstance",
                    String.valueOf(new java.io.File(getFilesDir(), "probe-libc-raw.txt").isFile()));

            // A second library, loaded here rather than in a static initializer - so it
            // arrives after UNIQUE installed its interception, which is the case the
            // initial scan cannot cover.
            ProbeLateNative.load();
            String lateCanonical =
                    "/data/data/" + getPackageName() + "/files/probe-libc-late.txt";
            out.put("lateLoaded", "true");
            out.put("lateWrite", ProbeLateNative.writeThroughLibc(lateCanonical));
            out.put("lateLandedInInstance",
                    String.valueOf(new java.io.File(getFilesDir(), "probe-libc-late.txt").isFile()));
        } catch (Throwable t) {
            out.put("loaded", "false");
            out.put("error", t.toString());
            Log.e(TAG, "native library failed", t);
        }
        writeMap("probe-native.properties", out);
    }

    /**
     * Schedules a job against the app's own JobService.
     *
     * An override deadline rather than a real constraint: the point is to observe the
     * system starting the app's job, not to test JobScheduler's own policy engine.
     */
    private void scheduleJob() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(
                    31, new ComponentName(this, ProbeJobService.class))
                    .setMinimumLatency(0)
                    .setOverrideDeadline(1000)
                    .build();
            int result = js.schedule(job);
            out.put("scheduleResult", String.valueOf(result));
            out.put("requestedJobId", "31");

            // Read back through the same API an app would use. It must see the id it
            // chose and its own class, not whatever UNIQUE scheduled underneath.
            android.app.job.JobInfo pending = js.getPendingJob(31);
            out.put("pendingFound", String.valueOf(pending != null));
            if (pending != null) {
                out.put("pendingJobId", String.valueOf(pending.getId()));
                out.put("pendingService", pending.getService().flattenToShortString());
            }
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "job scheduling failed", t);
        }
        writeMap("probe-job-schedule.properties", out);
    }

    /**
     * Sets an alarm and uses the clipboard, the way an ordinary app does.
     *
     * Both reach system services that check the calling package against the uid, so an
     * unhandled identity layer shows up as a SecurityException from an API that has
     * nothing obviously to do with package names.
     *
     * The clipboard *read* is attempted but not relied on: Android 10 restricted
     * getPrimaryClip to the app holding input focus, and a headless emulator never gives
     * an activity focus at all. Whatever comes back is recorded rather than asserted, and
     * hadFocus says which case this was.
     */
    private void exerciseAlarmAndClipboard() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent fire = android.app.PendingIntent.getBroadcast(
                    this, 0, new Intent("com.unique.probe.PING"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            // Inexact and far out: the point is that scheduling is accepted, not that it
            // fires during the test.
            am.set(android.app.AlarmManager.RTC,
                    System.currentTimeMillis() + 3600_000L, fire);
            out.put("alarmSet", "true");
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                out.put("canScheduleExact", String.valueOf(am.canScheduleExactAlarms()));
            }
            am.cancel(fire);
            out.put("alarmCancelled", "true");
        } catch (Throwable t) {
            out.put("alarmError", t.toString());
            Log.e(TAG, "alarm failed", t);
        }

        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "probe", "clip-from-" + getPackageName()));
            out.put("clipSet", "true");
            android.content.ClipData back = cm.getPrimaryClip();
            out.put("clipRead", back == null ? "null"
                    : String.valueOf(back.getItemAt(0).getText()));
            out.put("hadFocus", String.valueOf(hasWindowFocus()));
        } catch (Throwable t) {
            out.put("clipError", t.toString());
            Log.e(TAG, "clipboard failed", t);
        }
        out.put("packageName", getPackageName());
        writeMap("probe-alarm-clip.properties", out);
    }

    /**
     * Brings up EGL, renders one frame, and reads the pixel back.
     *
     * Self-verifying on purpose: clearing to a known colour and reading it with
     * glReadPixels proves the whole path executed rather than merely that no call threw.
     * A graphics stack that reports success and draws nothing is the usual failure, and
     * an assertion on GL_VENDOR would not catch it.
     *
     * A pbuffer rather than the window surface, so the result does not depend on the
     * window being visible - which on a headless emulator it never becomes.
     */
    private void exerciseGraphics() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        android.opengl.EGLDisplay display = android.opengl.EGL14.EGL_NO_DISPLAY;
        android.opengl.EGLContext context = android.opengl.EGL14.EGL_NO_CONTEXT;
        android.opengl.EGLSurface surface = android.opengl.EGL14.EGL_NO_SURFACE;
        try {
            display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            out.put("eglInitialised",
                    String.valueOf(android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)));
            out.put("eglVersion", version[0] + "." + version[1]);

            int[] attributes = {
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                    android.opengl.EGL14.EGL_RED_SIZE, 8,
                    android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                    android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                    android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
                    android.opengl.EGL14.EGL_NONE,
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] found = new int[1];
            android.opengl.EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0);
            out.put("eglConfigs", String.valueOf(found[0]));
            if (found[0] <= 0) {
                out.put("error", "no EGL config");
                writeMap("probe-graphics.properties", out);
                return;
            }

            context = android.opengl.EGL14.eglCreateContext(display, configs[0],
                    android.opengl.EGL14.EGL_NO_CONTEXT,
                    new int[]{android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                            android.opengl.EGL14.EGL_NONE}, 0);
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{android.opengl.EGL14.EGL_WIDTH, 16,
                            android.opengl.EGL14.EGL_HEIGHT, 16,
                            android.opengl.EGL14.EGL_NONE}, 0);
            out.put("eglMadeCurrent", String.valueOf(
                    android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)));

            out.put("glVendor", String.valueOf(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR)));
            out.put("glRenderer", String.valueOf(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)));
            out.put("glVersion", String.valueOf(android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION)));

            // Clear to a colour nothing else would produce, then read it back.
            android.opengl.GLES20.glClearColor(0.25f, 0.5f, 0.75f, 1f);
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);
            android.opengl.GLES20.glFinish();

            java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4);
            pixel.order(java.nio.ByteOrder.nativeOrder());
            android.opengl.GLES20.glReadPixels(8, 8, 1, 1,
                    android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, pixel);
            out.put("glError", String.valueOf(android.opengl.GLES20.glGetError()));
            out.put("pixelR", String.valueOf(pixel.get(0) & 0xFF));
            out.put("pixelG", String.valueOf(pixel.get(1) & 0xFF));
            out.put("pixelB", String.valueOf(pixel.get(2) & 0xFF));
            out.put("pixelA", String.valueOf(pixel.get(3) & 0xFF));
        } catch (Throwable t) {
            out.put("error", t.toString());
            Log.e(TAG, "graphics failed", t);
        } finally {
            try {
                if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                    android.opengl.EGL14.eglMakeCurrent(display,
                            android.opengl.EGL14.EGL_NO_SURFACE,
                            android.opengl.EGL14.EGL_NO_SURFACE,
                            android.opengl.EGL14.EGL_NO_CONTEXT);
                    if (surface != android.opengl.EGL14.EGL_NO_SURFACE) {
                        android.opengl.EGL14.eglDestroySurface(display, surface);
                    }
                    if (context != android.opengl.EGL14.EGL_NO_CONTEXT) {
                        android.opengl.EGL14.eglDestroyContext(display, context);
                    }
                    android.opengl.EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
                // Teardown failure must not mask the result being reported.
            }
        }
        out.put("packageName", getPackageName());
        writeMap("probe-graphics.properties", out);
    }

    /**
     * Reads the app's own signature and asks whether Google Play services exists.
     *
     * Both are things apps do constantly and both break silently under a naive
     * virtualization layer: an app that cannot read its own certificate concludes it has
     * been tampered with, and an app told Play services is present when it is not fails
     * later, somewhere less obvious.
     */
    private void exercisePackageIdentity() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                    ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    : android.content.pm.PackageManager.GET_SIGNATURES;
            android.content.pm.PackageInfo self = pm.getPackageInfo(getPackageName(), flags);
            // The deprecated array first, because that is what most apps and libraries
            // still read, and it must not be null just because the platform is API 28+.
            android.content.pm.Signature[] sigs = self.signatures;
            out.put("legacyArrayCount", String.valueOf(sigs == null ? 0 : sigs.length));
            if (sigs == null && android.os.Build.VERSION.SDK_INT >= 28
                    && self.signingInfo != null) {
                sigs = self.signingInfo.getApkContentsSigners();
            }
            out.put("signatureCount", String.valueOf(sigs == null ? 0 : sigs.length));
            if (sigs != null && sigs.length > 0) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(sigs[0].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format("%02x", b));
                out.put("signatureSha256", hex.toString());
            }
            out.put("hasSigningInfo", String.valueOf(
                    android.os.Build.VERSION.SDK_INT >= 28 && self.signingInfo != null));
        } catch (Throwable t) {
            out.put("signatureError", t.toString());
            Log.e(TAG, "signature check failed", t);
        }

        // What the app believes about the Google stack. The answer must be the truth
        // about this device, not a convenient fiction.
        for (String pkg : new String[]{
                "com.google.android.gms", "com.android.vending", "com.google.android.gsf"}) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                out.put("present." + pkg, "true");
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                out.put("present." + pkg, "false");
            } catch (Throwable t) {
                out.put("present." + pkg, "error:" + t.getClass().getSimpleName());
            }
        }
        // Device identity. Two instances of one app must not look like one installation
        // to anything that fingerprints, which is most of the interesting apps.
        try {
            out.put("androidId", android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
        } catch (Throwable t) {
            out.put("androidIdError", t.toString());
        }
        out.put("buildModel", String.valueOf(android.os.Build.MODEL));
        out.put("buildManufacturer", String.valueOf(android.os.Build.MANUFACTURER));
        out.put("buildFingerprint", String.valueOf(android.os.Build.FINGERPRINT));
        out.put("buildSerial", String.valueOf(android.os.Build.SERIAL));

        out.put("packageName", getPackageName());
        writeMap("probe-identity.properties", out);
    }
}
