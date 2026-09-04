package com.unique.app

import android.os.Bundle
import com.unique.app.bridge.UniqueBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * The UNIQUE UI.
 *
 * Flutter hosts the entire interface and nothing else: no virtualization code runs in
 * this process. See [UniqueApplication] for why.
 */
class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash theme covers only the window before Flutter's first frame; swapping
        // it here stops the mark showing through the UI afterwards.
        setTheme(R.style.Theme_Unique)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        UniqueBridge.attach(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        UniqueBridge.detach()
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
