package com.sakuhome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Standalone UI for Android's root-controlled overlay display setting. */
public class VirtualDisplayManagerActivity extends Activity {
    private EditText width, height, dpi, displayId;
    private TextView status;
    private SharedPreferences prefs;
    private static final String PREFS="saku_virtual_display_manager", ORIGINAL="original_overlay", SAVED="has_original";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(20),dp(20),dp(20),dp(20)); root.setBackgroundColor(0xff101827);
        TextView title=new TextView(this); title.setText("Virtual Display Manager"); title.setTextColor(-1); title.setTextSize(24); title.setTypeface(null,1); root.addView(title);
        TextView sub=new TextView(this); sub.setText("Kelola overlay display Android. Memerlukan Magisk/root."); sub.setTextColor(0xffc8d3e3); sub.setPadding(0,dp(6),0,dp(10)); root.addView(sub);
        width=field("Lebar (px)","900"); height=field("Tinggi (px)","900"); dpi=field("DPI","240");
        displayId=field("ID display virtual (lihat status display)","2");
        root.addView(width); root.addView(height); root.addView(dpi); root.addView(displayId);
        LinearLayout presets=new LinearLayout(this); presets.setOrientation(0);
        presets.addView(button("1280×720",v->setPreset(1280,720,240)),new LinearLayout.LayoutParams(0,-2,1));
        presets.addView(button("1600×900",v->setPreset(1600,900,240)),new LinearLayout.LayoutParams(0,-2,1));
        presets.addView(button("1920×1080",v->setPreset(1920,1080,240)),new LinearLayout.LayoutParams(0,-2,1)); root.addView(presets);
        LinearLayout squarePresets=new LinearLayout(this); squarePresets.setOrientation(0);
        squarePresets.addView(button("800×800",v->setPreset(800,800,240)),new LinearLayout.LayoutParams(0,-2,1));
        squarePresets.addView(button("900×900",v->setPreset(900,900,240)),new LinearLayout.LayoutParams(0,-2,1));
        root.addView(squarePresets);
        root.addView(button("Terapkan overlay",v->applyOverlay()));
        root.addView(button("Baca status display",v->runAsync("settings get global overlay_display_devices; dumpsys display")));
        root.addView(button("Aktifkan Aksesibilitas Trackpad",v->openAccessibilitySettings()));
        root.addView(button("Buka Saku Launcher di Display Virtual",v->openLauncherOnVirtualDisplay()));
        root.addView(button("Buka Trackpad Fullscreen di Display Virtual",v->openTrackpad()));
        root.addView(button("Pulihkan konfigurasi sebelumnya",v->restoreOverlay()));
        root.addView(button("Hapus overlay display",v->confirmRemove()));
        status=new TextView(this); status.setTextColor(0xffe4ebf5); status.setTextIsSelectable(true); status.setPadding(0,dp(12),0,0); root.addView(status,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this); scroll.addView(root); setContentView(scroll);
        runAsync("settings get global overlay_display_devices");
    }
    private EditText field(String hint,String value){EditText e=new EditText(this);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setHint(hint);e.setText(value);e.setTextColor(-1);e.setHintTextColor(0xffaab6c8);return e;}
    private Button button(String label,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setOnClickListener(listener);return b;}
    private void setPreset(int w,int h,int d){width.setText(""+w);height.setText(""+h);dpi.setText(""+d);}
    private void applyOverlay(){
        final int w,h,d;try{w=Integer.parseInt(width.getText().toString());h=Integer.parseInt(height.getText().toString());d=Integer.parseInt(dpi.getText().toString());}catch(Exception e){error("Isi lebar, tinggi, dan DPI dengan angka.");return;}
        if(w<320||w>3840||h<320||h>2160||d<120||d>640){error("Batas nilai: lebar 320–3840, tinggi 320–2160, DPI 120–640.");return;}
        new AlertDialog.Builder(this).setTitle("Terapkan overlay?").setMessage(w+"×"+h+" / "+d+" DPI. Resolusi layar fisik tidak diubah.")
          .setNegativeButton("Batal",null).setPositiveButton("Terapkan",(dialog,which)->new Thread(()->{
            Result old=exec("settings get global overlay_display_devices");
            if(old.code!=0){showStatus("Gagal membaca setting. Pastikan Magisk/root tersedia.");return;}
            if(!prefs.getBoolean(SAVED,false))prefs.edit().putString(ORIGINAL,old.out.equals("null")?"":old.out).putBoolean(SAVED,true).apply();
            Result result=exec("settings put global overlay_display_devices "+quote(w+"x"+h+"/"+d)+" && settings get global overlay_display_devices");
            showStatus(result.code==0?"Setting overlay diterapkan: "+result.out:"Gagal menerapkan (kode "+result.code+"): "+result.out);
          },"apply-overlay").start()).show();
    }
    private void openAccessibilitySettings(){
        try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch(Exception e){ error("Pengaturan aksesibilitas tidak dapat dibuka: "+e.getMessage()); }
    }
    private int selectedDisplayId() {
        try { int id=Integer.parseInt(displayId.getText().toString().trim()); if(id<1||id>99) throw new Exception(); return id; }
        catch(Exception e){ throw new IllegalArgumentException("Masukkan ID display virtual yang valid (1–99)."); }
    }
    private void openLauncherOnVirtualDisplay() {
        final int id;
        try { id=selectedDisplayId(); } catch(Exception e){ error(e.getMessage()); return; }
        new Thread(() -> {
            Result result=exec("am start --display "+id+" -n com.sakuhome.launcher/.MainActivity --ez saku_virtual_display_mode true");
            showStatus(result.code==0 ? "Permintaan membuka Saku Launcher di display "+id+": "+result.out :
                "Gagal membuka launcher di display "+id+". Pastikan ID display benar. "+result.out);
        },"launch-saku-on-display").start();
    }
    private void openTrackpad(){
        final int id;
        try { id=selectedDisplayId(); } catch(Exception e){ error(e.getMessage()); return; }
        new Thread(() -> {
            Result result=exec("am start --display "+id+" -n com.sakuhome.launcher/com.katsuyamaki.DroidOSFOSSKeyboardTrackpad.MainActivity --ei displayId "+id+" --ez show_trackpad true");
            showStatus(result.code==0 ? "Permintaan membuka trackpad fullscreen pada display "+id+": "+result.out :
                "Trackpad gagal dibuka pada display "+id+". Pastikan layanan aksesibilitas aktif dan ID display benar. "+result.out);
        },"launch-trackpad-on-display").start();
    }
    private void restoreOverlay(){
        if(!prefs.getBoolean(SAVED,false)){error("Belum ada konfigurasi sebelumnya yang dicadangkan.");return;}
        String old=prefs.getString(ORIGINAL,"");
        new Thread(()->{Result result=exec(old.isEmpty()?"settings delete global overlay_display_devices":"settings put global overlay_display_devices "+quote(old));
            if(result.code==0)prefs.edit().putBoolean(SAVED,false).apply();
            showStatus(result.code==0?"Konfigurasi sebelumnya dipulihkan.":"Pemulihan gagal: "+result.out);
        },"restore-overlay").start();
    }
    private void confirmRemove(){new AlertDialog.Builder(this).setTitle("Hapus overlay display?").setMessage("Konfigurasi overlay virtual dihapus. Cadangan untuk pemulihan tetap disimpan.")
        .setNegativeButton("Batal",null).setPositiveButton("Hapus",(dialog,which)->runAsync("settings delete global overlay_display_devices && settings get global overlay_display_devices")).show();}
    private void runAsync(String command){showStatus("Memeriksa status…");new Thread(()->{Result result=exec(command);showStatus(result.code==0?result.out:"Perintah gagal (kode "+result.code+"): "+result.out);},"display-status").start();}
    private static final class Result{final String out;final int code;Result(String output,int exit){out=output;code=exit;}}
    private Result exec(String command){StringBuilder output=new StringBuilder();Process process=null;try{
        process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){String line;while((line=reader.readLine())!=null&&output.length()<8000)output.append(line).append('\n');}
        if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly();return new Result("Perintah timeout",124);}
        return new Result(output.toString().trim(),process.exitValue());
      }catch(Exception e){return new Result(e.getClass().getSimpleName()+": "+e.getMessage(),1);}finally{if(process!=null)process.destroy();}
    }
    private String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private void showStatus(String value){runOnUiThread(()->status.setText(value));}
    private void error(String message){new AlertDialog.Builder(this).setTitle("Tidak dapat menerapkan").setMessage(message).setPositiveButton("OK",null).show();}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
}
