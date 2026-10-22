package com.sakuhome.launcher;

import android.app.Application;

public final class SakuApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLogger.install(this);
    }
}
