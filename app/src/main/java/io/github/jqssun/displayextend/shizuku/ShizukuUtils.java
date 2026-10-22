package io.github.jqssun.displayextend.shizuku;

import android.content.pm.PackageManager;
import android.util.Log;
import rikka.shizuku.Shizuku;

public final class ShizukuUtils {
  private static final String TAG = "DisplayExtendShizuku";

  private ShizukuUtils() {}

  public static boolean hasShizukuStarted() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable e) {
      Log.w(TAG, "Shizuku binder is unavailable", e);
      return false;
    }
  }

  public static boolean hasPermission() {
    if (!hasShizukuStarted()) return false;
    try {
      return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable e) {
      Log.w(TAG, "Unable to check Shizuku permission", e);
      return false;
    }
  }

  public static int getServerUid() {
    if (!hasShizukuStarted()) return -1;
    try {
      return Shizuku.getUid();
    } catch (Throwable e) {
      Log.w(TAG, "Unable to read Shizuku server UID", e);
      return -1;
    }
  }
}
