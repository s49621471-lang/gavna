package com.unique.probe;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A JobService that records what it saw when the system ran it.
 *
 * Jobs matter for virtualization beyond the API itself: the job fires long after the
 * process that scheduled it has gone, so this is the first path where the guest is
 * started by the *system* rather than by UNIQUE.
 */
public class ProbeJobService extends JobService {
    private static final String TAG = ProbeApplication.TAG;
    public static final String RESULT_FILE = "probe-job.properties";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Job.onStartJob id=" + params.getJobId());
        String body = "ran=true\n"
                + "jobId=" + params.getJobId() + "\n"
                + "packageName=" + getPackageName() + "\n"
                + "className=" + getClass().getName() + "\n"
                + "filesDir=" + getFilesDir().getAbsolutePath() + "\n"
                + "pid=" + android.os.Process.myPid() + "\n";
        try {
            File f = new File(getFilesDir(), RESULT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            Log.i(TAG, "wrote " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "job could not write its result", t);
        }
        // Reporting completion synchronously exercises jobFinished(), which goes through
        // the engine the platform installs at bind time - the part a guest instance does
        // not have of its own.
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "Job.onStopJob id=" + params.getJobId());
        return false;
    }
}
