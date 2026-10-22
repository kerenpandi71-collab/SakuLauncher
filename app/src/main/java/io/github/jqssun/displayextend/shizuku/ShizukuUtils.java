package io.github.jqssun.displayextend.shizuku;

import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

public class ShizukuUtils {
  public static boolean hasShizukuStarted() {
    try { return Shizuku.pingBinder(); }
    catch (Throwable e) { android.util.Log.w("DisplayExtendShizuku", "binder unavailable", e); return false; }
  }

  public static boolean hasPermission() {
    if (!hasShizukuStarted()) return false;
    try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
    catch (Throwable e) { android.util.Log.w("DisplayExtendShizuku", "permission check failed", e); return false; }
  }

  public static int getServerUid() {
    if (!hasShizukuStarted()) return -1;
    try { return Shizuku.getUid(); }
    catch (Throwable e) { android.util.Log.w("DisplayExtendShizuku", "UID lookup failed", e); return -1; }
  }
}
