package com.unique.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Started only by an *implicit* intent: a custom action, or the app's own URI scheme.
 *
 * There is no way to reach this class by naming it. Every start has to be resolved by
 * matching an intent against this app's own manifest filters — which is what a deep link
 * coming back from a browser is, and what an app's internal implicit navigation is.
 */
public class ProbeDeepLinkActivity extends Activity {
    private static final String TAG = ProbeApplication.TAG;

    public static final String ACTION = "com.unique.probe.CUSTOM_OPEN";
    public static final String SCHEME = "unique-probe";
    public static final String RESULT_FILE = "probe-deeplink.properties";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("action", String.valueOf(intent == null ? null : intent.getAction()));
        out.put("data", String.valueOf(intent == null ? null : intent.getDataString()));
        out.put("scheme", String.valueOf(
                intent == null || intent.getData() == null ? null : intent.getData().getScheme()));
        out.put("extra", String.valueOf(
                intent == null ? null : intent.getStringExtra("probe.deeplink.extra")));
        out.put("packageName", getPackageName());
        out.put("componentName", getComponentName().flattenToString());
        out.put("pid", String.valueOf(android.os.Process.myPid()));
        out.put("filesDir", getFilesDir().getAbsolutePath());

        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.BLACK);
        view.setText("deep link: " + out.get("action") + "\n" + out.get("data"));
        setContentView(view);

        try {
            File f = new File(getFilesDir(), RESULT_FILE);
            FileOutputStream fos = new FileOutputStream(f, false);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : out.entrySet()) {
                Log.i(TAG, "deeplink " + e.getKey() + "=" + e.getValue());
                body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "could not write deep link result", t);
        }
    }
}
