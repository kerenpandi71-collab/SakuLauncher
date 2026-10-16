package com.sakuhome.launcher;

import android.content.ComponentName;
import android.content.ServiceConnection;
import rikka.shizuku.Shizuku;

public final class ShizukuBinder {
    private ShizukuBinder() {}

    public static void bind(ComponentName component, ServiceConnection connection, boolean debug, int version) {
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(component)
                .processNameSuffix("saku_input")
                .daemon(false)
                .debuggable(debug)
                .version(version);
        Shizuku.bindUserService(args, connection);
    }
}