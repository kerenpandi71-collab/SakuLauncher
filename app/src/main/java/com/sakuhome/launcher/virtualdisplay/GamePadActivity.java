package com.sakuhome.launcher.virtualdisplay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Requests overlay permission and starts the persistent gamepad overlay. */
public final class GamePadActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())), 731);
        } else startOverlay();
    }
    private int getDisplayIdCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 30 && getDisplay() != null) return getDisplay().getDisplayId();
        return 0;
    }
    private void startOverlay() {
        try {
            Intent service = new Intent(this, GamePadOverlayService.class).putExtra("display_id", getDisplayIdCompat());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "Saku Game Pad diaktifkan", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "Game Pad gagal: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 731) {
            if (Settings.canDrawOverlays(this)) startOverlay();
            else { Toast.makeText(this, "Izin overlay belum diberikan", Toast.LENGTH_SHORT).show(); finish(); }
        }
    }
}