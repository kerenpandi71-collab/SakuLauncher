package com.sakuhome.launcher.virtualdisplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/** Persistent configurable gamepad overlay. Mapping is saved per orientation. */
public final class GamePadOverlayService extends Service {
    private WindowManager wm;
    private PadView pad;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private boolean editing;
    private boolean toolbar = true;
    private int alpha = 185, selected = -1;
    private float scale = 1f;
    private final String[] labels = {"MOVE","ATTACK","SKILL 1","SKILL 2","SKILL 3","ULT","SPELL","RECALL","REGEN","SHOP","TARGET","EXTRA"};
    private final float[][] defaults = {{.14f,.72f},{.88f,.78f},{.76f,.55f},{.83f,.43f},{.90f,.34f},{.94f,.23f},{.69f,.78f},{.58f,.90f},{.68f,.90f},{.08f,.20f},{.94f,.62f},{.50f,.50f}};
    private float[] xs = new float[12], ys = new float[12];
    private volatile Process bridgeProcess;
    private volatile BufferedWriter bridgeWriter;
    private volatile boolean bridgeReady;
    private int targetDisplayId = 0;
    private int screenWidth = 1, screenHeight = 1;
    private String profile() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait"; }
    private String key(String suffix) { return profile()+"."+suffix; }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }

    @Override public void onCreate() {
        super.onCreate();
        prefs=getSharedPreferences("gamepad_layouts",MODE_PRIVATE);
        alpha=prefs.getInt("alpha",185); scale=prefs.getFloat("scale",1f);
        loadLayout();
        if(Build.VERSION.SDK_INT>=26) {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("saku_gamepad","Saku Game Pad",NotificationManager.IMPORTANCE_LOW));
            Notification n=new Notification.Builder(this,"saku_gamepad").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Saku Game Pad aktif").setContentText("Overlay mapping sedang berjalan")
                .setOngoing(true).build();
            startForeground(731,n);
        }
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        targetDisplayId = getIntentDisplayId();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
        startRootBridge();
        pad=new PadView(this);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        params=new WindowManager.LayoutParams(-1,-1,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        try { wm.addView(pad,params); } catch(Throwable e) { stopSelf(); }
    }
    private int getIntentDisplayId() {
        // The launch activity is normally on the display containing the game.
        return getSharedPreferences("gamepad_runtime", MODE_PRIVATE).getInt("display_id", 0);
    }
    private void startRootBridge() {
        try {
            String shell = "APK=$(pm path " + getPackageName() + " | head -n 1 | cut -d: -f2); "
                + "if [ -z \\"$APK\\" ]; then echo NO_APK_PATH; exit 20; fi; "
                + "CLASSPATH=\\"$APK\\" app_process /system/bin com.sakuhome.launcher.virtualdisplay.RootInputBridge";
            Process process = new ProcessBuilder("su","-c",shell).redirectErrorStream(true).start();
            bridgeProcess=process;
            bridgeWriter=new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            Thread reader=new Thread(() -> {
                try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line; while((line=br.readLine())!=null) {
                        if("READY".equals(line)) bridgeReady=true;
                        else if(line.startsWith("BRIDGE_ERROR")) bridgeReady=false;
                        android.util.Log.d("SakuGamePadBridge",line);
                    }
                } catch(Exception e) { android.util.Log.e("SakuGamePadBridge","reader",e); }
                finally { bridgeReady=false; }
            },"saku-gamepad-bridge-reader");
            reader.setDaemon(true); reader.start();
        } catch(Throwable e) { android.util.Log.e("SakuGamePadBridge","Cannot start root bridge",e); }
    }
    private boolean sendTouch(int pointerId,String phase,float nx,float ny) {
        BufferedWriter writer=bridgeWriter;
        if(!bridgeReady || writer==null) return false;
        float x=Math.max(0,Math.min(screenWidth-1,nx*screenWidth));
        float y=Math.max(0,Math.min(screenHeight-1,ny*screenHeight));
        try { synchronized(writer) { writer.write("TOUCH "+targetDisplayId+" "+pointerId+" "+phase+" "+x+" "+y);writer.newLine();writer.flush(); } return true; }
        catch(Exception e) { android.util.Log.e("SakuGamePadBridge","send touch",e);return false; }
    }
    private void loadLayout() {
        for(int i=0;i<labels.length;i++){xs[i]=prefs.getFloat(key("x"+i),defaults[i][0]);ys[i]=prefs.getFloat(key("y"+i),defaults[i][1]);}
    }
    private void saveLayout() {
        SharedPreferences.Editor e=prefs.edit().putInt("alpha",alpha).putFloat("scale",scale);
        for(int i=0;i<labels.length;i++)e.putFloat(key("x"+i),xs[i]).putFloat(key("y"+i),ys[i]);
        e.apply();
    }
    private void reset() { for(int i=0;i<labels.length;i++){xs[i]=defaults[i][0];ys[i]=defaults[i][1];}scale=1f;alpha=185;saveLayout();pad.invalidate(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);loadLayout();if(pad!=null)pad.invalidate();}
    @Override public void onDestroy(){bridgeReady=false;if(bridgeProcess!=null)bridgeProcess.destroy();if(wm!=null&&pad!=null)try{wm.removeView(pad);}catch(Throwable ignored){};super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    private final class PadView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX,downY; private boolean moving; private long lastTap;
        PadView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float w=getWidth(),h=getHeight();
            for(int i=0;i<labels.length;i++){
                float r=dp((i==0?44:29)*scale),cx=xs[i]*w,cy=ys[i]*h;
                p.setStyle(Paint.Style.FILL);p.setColor((alpha<<24)|0x00A6DFFF);c.drawCircle(cx,cy,r,p);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(editing&&i==selected?Color.YELLOW:0xDDEAF5FF);c.drawCircle(cx,cy,r,p);
                p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(labels[i].length()>7?8:10));p.setColor(Color.WHITE);c.drawText(labels[i],cx,cy+dp(4),p);
                if(editing&&i==selected){p.setStyle(Paint.Style.STROKE);p.setColor(Color.YELLOW);c.drawCircle(cx,cy,r+dp(5),p);}
            }
            if(toolbar) drawToolbar(c);
        }
        private void drawToolbar(Canvas c){
            p.setStyle(Paint.Style.FILL);p.setColor(0xD91A2230);c.drawRoundRect(new RectF(dp(5),dp(5),getWidth()-dp(5),dp(43)),dp(8),dp(8),p);
            p.setTextSize(dp(11));p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.LEFT);
            c.drawText("SAKU PAD • "+profile().toUpperCase(Locale.ROOT)+(editing?" • ATUR POSISI":" • MAIN"),dp(12),dp(29),p);
            String[] opts={"EDIT","SIZE−","SIZE+","ALPHA","SAVE","RESET","HIDE","×"};
            float cell=(getWidth()-dp(10))/8f;
            for(int i=0;i<opts.length;i++){float x=dp(5)+cell*i;p.setColor(0xFF26364A);c.drawRoundRect(new RectF(x+dp(1),dp(48),x+cell-dp(2),dp(78)),dp(5),dp(5),p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));c.drawText(opts[i],x+cell/2,dp(67),p);}
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;moving=false;
                if(toolbar&&y<dp(84)){int action=toolbarAction(x);if(action>=0){performToolbar(action);return true;}}
                selected=nearest(x,y);moving=editing;if(moving){xs[selected]=clamp(x/getWidth());ys[selected]=clamp(y/getHeight());invalidate();}else if(selected==0){sendTouch(0,"DOWN",xs[selected],ys[selected]);}else{sendTouch(selected+1,"DOWN",xs[selected],ys[selected]);}return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE&&moving){xs[selected]=clamp(x/getWidth());ys[selected]=clamp(y/getHeight());invalidate();return true;}\n            if(e.getAction()==MotionEvent.ACTION_MOVE && selected==0 && !editing){float radius=dp(44)*scale;float dx=Math.max(-radius,Math.min(radius,x-downX));float dy=Math.max(-radius,Math.min(radius,y-downY));sendTouch(0,"MOVE",clamp(xs[0]+dx/getWidth()),clamp(ys[0]+dy/getHeight()));return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){if(moving){saveLayout();invalidate();}else if(selected>=0)trigger(selected);return true;}return true;
        }
        private int toolbarAction(float x){float cell=(getWidth()-dp(10))/8f;int i=(int)((x-dp(5))/cell);return i>=0&&i<8?i:-1;}
        private void performToolbar(int a){
            switch(a){case 0:editing=!editing;break;case 1:scale=Math.max(.55f,scale-.1f);break;case 2:scale=Math.min(2.8f,scale+.1f);break;case 3:alpha=alpha>=230?80:alpha+30;break;case 4:saveLayout();Toast.makeText(GamePadOverlayService.this,"Layout "+profile()+" disimpan",Toast.LENGTH_SHORT).show();break;case 5:reset();return;case 6:toolbar=false;break;case 7:stopSelf();return;}
            saveLayout();invalidate();
        }
        private int nearest(float x,float y){int best=0;float dist=Float.MAX_VALUE;for(int i=0;i<labels.length;i++){float dx=x-xs[i]*getWidth(),dy=y-ys[i]*getHeight(),d=dx*dx+dy*dy;if(d<dist){dist=d;best=i;}}return best;}
        private float clamp(float v){return Math.max(.03f,Math.min(.97f,v));}
        private void trigger(int i){
            // UI is intentionally honest: input injection is not yet wired to the target game.
            Toast.makeText(GamePadOverlayService.this,labels[i]+" dipilih — jalur input game belum tersambung",Toast.LENGTH_SHORT).show();
        }
    }
}