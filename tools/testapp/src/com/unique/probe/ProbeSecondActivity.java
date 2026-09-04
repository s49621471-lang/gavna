package com.unique.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A second Activity, started by the first through an ordinary startActivity().
 *
 * The first Activity of an instance is launched by UNIQUE and proves nothing about what
 * an app does for itself: every screen after the first is the app's own call, naming a
 * component the system has never installed.
 */
public class ProbeSecondActivity extends Activity {
    private static final String TAG = ProbeApplication.TAG;
    public static final String RESULT_FILE = "probe-second.properties";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.DKGRAY);
        view.setPadding(32, 96, 32, 32);
        view.setText("second activity");
        setContentView(view);

        String body = "activityClass=" + getClass().getName() + "\n"
                + "packageName=" + getPackageName() + "\n"
                + "componentName=" + getComponentName().flattenToString() + "\n"
                + "callingPackage=" + String.valueOf(getCallingPackage()) + "\n"
                + "extra=" + String.valueOf(getIntent().getStringExtra("probe.second.extra")) + "\n"
                + "filesDir=" + getFilesDir().getAbsolutePath() + "\n"
                + "taskId=" + getTaskId() + "\n"
                + "pid=" + android.os.Process.myPid() + "\n";
        try {
            File f = new File(getFilesDir(), RESULT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "second activity could not write its result", t);
        }
        for (String line : body.split("\n")) Log.i(TAG, line);
    }
}
