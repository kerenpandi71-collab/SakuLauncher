package com.sakuhome.launcher.virtualdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configurable on-screen Mobile Legends layout. Coordinates are stored as normalized
 * fractions, so the portrait and landscape layouts remain independent.
 */
public final class GamePadActivity extends Activity {
    private FrameLayout root;
    private PadView pad;
    private SharedPreferences prefs;
    private boolean editing = false;
    private int alpha = 190;
    private float scale = 1f;
    private int selected = 0;
    private int targetDisplay = -1;
    private final List<PadButton> buttons = new ArrayList<>();

    private static final String[] LABELS = {
        "MOVE", "ATTACK", "SKILL 1", "SKILL 2", "SKILL 3", "ULT", "SPELL",
        "RECALL", "REGEN", "SHOP", "TARGET", "EXTRA"
    };
    private static final float[][] DEFAULTS = {
        {.14f,.72f},{.88f,.78f},{.76f,.55f},{.83f,.43f},{.90f,.34f},{.94f,.23f},
        {.69f,.78f},{.58f,.90f},{.68f,.90f},{.08f,.20f},{.94f,.62f},{.50f,.50f}
    };

    private boolean landscape() { return getResources().getConfiguration().orientation == 2; }
    private String profile() { return landscape() ? "landscape" : "portrait"; }
    private String key(String suffix) { return profile() + "." + suffix; }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        targetDisplay = getIntent().getIntExtra("display_id", -1);
        prefs = getSharedPreferences("gamepad_layouts", MODE_PRIVATE);
        alpha = prefs.getInt("alpha", 190);
        scale = prefs.getFloat("scale", 1f);
        for (int i = 0; i < LABELS.length; i++) {
            buttons.add(new PadButton(LABELS[i], prefs.getFloat(key("x"+i), DEFAULTS[i][0]),
                prefs.getFloat(key("y"+i), DEFAULTS[i][1])));
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        pad = new PadView();
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showTools();
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("Izin overlay diperlukan")
                .setMessage("Izinkan SakuLauncher tampil di atas aplikasi lain agar game pad dapat digunakan.")
                .setPositiveButton("Pengaturan", (d,w) -> startActivity(new android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Tutup", (d,w) -> finish()).show();
        }
    }

    private void showTools() {
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(6), dp(4), dp(6), dp(4));
        tools.setBackgroundColor(0xB8141B28);
        TextView title = new TextView(this);
        title.setText("SAKU GAME PAD • " + profile().toUpperCase(Locale.ROOT));
        title.setTextColor(Color.WHITE); title.setTextSize(11);
        tools.addView(title);
        LinearLayout row = new LinearLayout(this);
        row.addView(tool("Atur posisi", v -> { editing = !editing; pad.invalidate(); toast(editing ? "Pilih tombol lalu geser" : "Mode main"); }), new LinearLayout.LayoutParams(0,dp(38),1));
        row.addView(tool("Ukuran −", v -> { scale = Math.max(.55f, scale-.1f); savePrefs(); pad.invalidate(); }), new LinearLayout.LayoutParams(0,dp(38),1));
        row.addView(tool("Ukuran +", v -> { scale = Math.min(2.8f, scale+.1f); savePrefs(); pad.invalidate(); }), new LinearLayout.LayoutParams(0,dp(38),1));
        row.addView(tool("Transparansi", v -> {
            alpha = alpha >= 230 ? 90 : alpha + 35; savePrefs(); pad.invalidate();
        }), new LinearLayout.LayoutParams(0,dp(38),1));
        row.addView(tool("Simpan", v -> { savePrefs(); toast("Layout " + profile() + " disimpan"); }), new LinearLayout.LayoutParams(0,dp(38),1));
        row.addView(tool("Reset", v -> resetLayout()), new LinearLayout.LayoutParams(0,dp(38),1));
        tools.addView(row);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(tools,p);
    }

    private Button tool(String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setTextSize(10); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setOnClickListener(click); return b;
    }
    private void savePrefs() {
        SharedPreferences.Editor e = prefs.edit().putInt("alpha",alpha).putFloat("scale",scale);
        for (int i=0;i<buttons.size();i++) e.putFloat(key("x"+i),buttons.get(i).x).putFloat(key("y"+i),buttons.get(i).y);
        e.apply();
    }
    private void resetLayout() {
        for(int i=0;i<buttons.size();i++){ buttons.get(i).x=DEFAULTS[i][0]; buttons.get(i).y=DEFAULTS[i][1]; }
        scale=1f; alpha=190; savePrefs(); pad.invalidate(); toast("Layout dikembalikan");
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }

    private final class PadButton {
        String label; float x,y;
        PadButton(String label,float x,float y){this.label=label;this.x=x;this.y=y;}
    }
    private final class PadView extends View {
        private final Paint paint = new Paint(3);
        private float downX,downY;
        private boolean dragging;
        PadView(){super(GamePadActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(),h=getHeight();
            for(int i=0;i<buttons.size();i++){
                PadButton b=buttons.get(i); float cx=b.x*w,cy=b.y*h;
                float r=dp((b.label.equals("MOVE")?46:29)*scale);
                paint.setColor((alpha<<24)|0x00A9DFFF); paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx,cy,r,paint);
                paint.setColor(editing && i==selected ? Color.YELLOW : 0xDDEAF5FF);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
                c.drawCircle(cx,cy,r,paint);
                paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(dp(b.label.length()>7?8:10)); paint.setColor(Color.WHITE);
                c.drawText(b.label,cx,cy+dp(4),paint);
                if(editing && i==selected){ paint.setColor(Color.YELLOW); c.drawCircle(cx,cy,r+dp(5),paint); }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getX(),y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                downX=x;downY=y;selected=nearest(x,y);dragging=editing;
                if(dragging){PadButton b=buttons.get(selected);b.x=clamp(x/getWidth());b.y=clamp(y/getHeight());invalidate();}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE && dragging){
                PadButton b=buttons.get(selected);b.x=clamp(x/getWidth());b.y=clamp(y/getHeight());invalidate();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                if(dragging){savePrefs();invalidate();}
                else if(selected>=0){ activate(buttons.get(selected)); }
                return true;
            }
            return true;
        }
        private int nearest(float x,float y){
            int best=0;float dist=Float.MAX_VALUE;
            for(int i=0;i<buttons.size();i++){PadButton b=buttons.get(i);float dx=x-b.x*getWidth(),dy=y-b.y*getHeight();float d=dx*dx+dy*dy;if(d<dist){dist=d;best=i;}}
            return best;
        }
        private float clamp(float v){return Math.max(.03f,Math.min(.97f,v));}
    }
    private void activate(PadButton b) {
        if (b.label.equals("MOVE")) {
            toast("Analog visual tersedia; input multi-touch ke game belum diaktifkan.");
        } else {
            toast(b.label + ": pemetaan input game belum terhubung.");
        }
    }
    @Override protected void onPause(){ super.onPause(); savePrefs(); }
}