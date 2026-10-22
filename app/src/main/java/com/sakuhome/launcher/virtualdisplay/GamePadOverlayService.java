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
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Gamepad uses two small, bounded overlay windows. The empty middle of the
 * screen is never covered by a full-screen touchable window.
 */
public final class GamePadOverlayService extends Service {
    private WindowManager wm;
    private SharedPreferences prefs;
    private PanelView leftPanel, rightPanel;
    private WindowManager.LayoutParams leftParams, rightParams;
    private boolean leftAttached, rightAttached;
    private int targetDisplayId;
    private int screenWidth=1, screenHeight=1;
    private float leftWidth=0.24f, leftHeight=0.72f, rightWidth=0.25f, rightHeight=0.78f;
    private int alpha=185;
    private boolean editing;
    private volatile Process bridgeProcess;
    private volatile BufferedWriter bridgeWriter;
    private volatile boolean bridgeReady;
    private final String[] actions={"SERANG","SKILL 1","SKILL 2","SKILL 3","FLICKER","REGEN","RECALL"};
    private final float[][] rightPoints={{.50f,.25f},{.50f,.37f},{.50f,.49f},{.50f,.61f},{.28f,.78f},{.50f,.82f},{.72f,.78f}};
    private float analogX=.5f, analogY=.62f;
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("gamepad_layouts",MODE_PRIVATE);
        leftWidth=prefs.getFloat("left_width",.24f); leftHeight=prefs.getFloat("left_height",.72f);
        rightWidth=prefs.getFloat("right_width",.25f); rightHeight=prefs.getFloat("right_height",.78f);
        alpha=prefs.getInt("alpha",185); analogX=prefs.getFloat("analog_x",.5f); analogY=prefs.getFloat("analog_y",.62f);
        for(int i=0;i<actions.length;i++){rightPoints[i][0]=prefs.getFloat("button_x"+i,rightPoints[i][0]);rightPoints[i][1]=prefs.getFloat("button_y"+i,rightPoints[i][1]);}
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("saku_gamepad","Saku Game Pad",NotificationManager.IMPORTANCE_LOW));
            Notification n=new Notification.Builder(this,"saku_gamepad").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Saku Game Pad aktif").setContentText("Panel kiri dan kanan • area tengah bebas").setOngoing(true).build();
            startForeground(731,n);
        }
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics(); wm.getDefaultDisplay().getRealMetrics(dm);
        screenWidth=dm.widthPixels; screenHeight=dm.heightPixels;
        targetDisplayId=getSharedPreferences("gamepad_runtime",MODE_PRIVATE).getInt("display_id",0);
        startRootBridge();
        leftPanel=new PanelView(this,true); rightPanel=new PanelView(this,false);
        updatePanels();
    }
    private void startRootBridge(){
        try{
            String shell="APK=$(pm path "+getPackageName()+" | head -n 1 | cut -d: -f2); if [ -z \"$APK\" ]; then echo NO_APK_PATH; exit 20; fi; CLASSPATH=\"$APK\" app_process /system/bin com.sakuhome.launcher.virtualdisplay.RootInputBridge";
            Process process=new ProcessBuilder("su","-c",shell).redirectErrorStream(true).start(); bridgeProcess=process;
            bridgeWriter=new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            Thread reader=new Thread(()->{try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream()))){String line;while((line=br.readLine())!=null){if("READY".equals(line))bridgeReady=true;else if(line.startsWith("BRIDGE_ERROR"))bridgeReady=false;android.util.Log.d("SakuGamePadBridge",line);}}catch(Exception e){android.util.Log.e("SakuGamePadBridge","reader",e);}finally{bridgeReady=false;}},"saku-gamepad-bridge-reader");
            reader.setDaemon(true);reader.start();
        }catch(Throwable e){android.util.Log.e("SakuGamePadBridge","Cannot start root bridge",e);}
    }
    private void sendTouch(int id,String phase,float nx,float ny){
        BufferedWriter w=bridgeWriter;if(!bridgeReady||w==null){android.util.Log.w("SakuGamePad","Input bridge not ready; no touch sent");return;}
        float x=Math.max(0,Math.min(screenWidth-1,nx*screenWidth)),y=Math.max(0,Math.min(screenHeight-1,ny*screenHeight));
        try{synchronized(w){w.write("TOUCH "+targetDisplayId+" "+id+" "+phase+" "+x+" "+y);w.newLine();w.flush();}}catch(Exception e){android.util.Log.e("SakuGamePad","touch",e);}
    }
    private void save(){
        SharedPreferences.Editor e=prefs.edit().putFloat("left_width",leftWidth).putFloat("left_height",leftHeight)
            .putFloat("right_width",rightWidth).putFloat("right_height",rightHeight).putInt("alpha",alpha)
            .putFloat("analog_x",analogX).putFloat("analog_y",analogY);
        for(int i=0;i<actions.length;i++)e.putFloat("button_x"+i,rightPoints[i][0]).putFloat("button_y"+i,rightPoints[i][1]);
        e.apply();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&intent.hasExtra("display_id")){targetDisplayId=intent.getIntExtra("display_id",0);getSharedPreferences("gamepad_runtime",MODE_PRIVATE).edit().putInt("display_id",targetDisplayId).apply();}
        android.util.DisplayMetrics currentMetrics=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(currentMetrics);screenWidth=currentMetrics.widthPixels;screenHeight=currentMetrics.heightPixels;
        updatePanels(); return START_STICKY;
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);android.util.DisplayMetrics dm=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);screenWidth=dm.widthPixels;screenHeight=dm.heightPixels;updatePanels();}
    private void updatePanels(){
        if(wm==null||leftPanel==null||rightPanel==null)return;
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        if(!landscape){removePanels();return;}
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        int lw=Math.max(dp(92),Math.min((int)(screenWidth*.36f),(int)(screenWidth*leftWidth)));
        int lh=Math.max(dp(150),Math.min((int)(screenHeight*.95f),(int)(screenHeight*leftHeight)));
        int rw=Math.max(dp(100),Math.min((int)(screenWidth*.38f),(int)(screenWidth*rightWidth)));
        int rh=Math.max(dp(180),Math.min((int)(screenHeight*.95f),(int)(screenHeight*rightHeight)));
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        leftParams=new WindowManager.LayoutParams(lw,lh,type,flags,android.graphics.PixelFormat.TRANSLUCENT);
        leftParams.gravity=Gravity.TOP|Gravity.LEFT;leftParams.y=Math.max(0,(screenHeight-lh)/2);leftParams.x=0;leftParams.setTitle("Saku GamePad left panel");
        rightParams=new WindowManager.LayoutParams(rw,rh,type,flags,android.graphics.PixelFormat.TRANSLUCENT);
        rightParams.gravity=Gravity.TOP|Gravity.RIGHT;rightParams.y=Math.max(0,(screenHeight-rh)/2);rightParams.x=0;rightParams.setTitle("Saku GamePad right panel");
        try{
            if(!leftAttached){wm.addView(leftPanel,leftParams);leftAttached=true;}else wm.updateViewLayout(leftPanel,leftParams);
            if(!rightAttached){wm.addView(rightPanel,rightParams);rightAttached=true;}else wm.updateViewLayout(rightPanel,rightParams);
        }catch(Throwable e){android.util.Log.e("SakuGamePad","Panel attach/update failed",e);Toast.makeText(this,"Game Pad gagal ditampilkan: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        leftPanel.invalidate();rightPanel.invalidate();
    }
    private void removePanels(){
        if(wm==null)return;
        if(leftAttached){try{wm.removeView(leftPanel);}catch(Throwable ignored){}leftAttached=false;}
        if(rightAttached){try{wm.removeView(rightPanel);}catch(Throwable ignored){}rightAttached=false;}
    }
    @Override public void onDestroy(){bridgeReady=false;removePanels();if(bridgeProcess!=null)bridgeProcess.destroy();try{if(bridgeWriter!=null)bridgeWriter.close();}catch(Exception ignored){}super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    private final class PanelView extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean left;
        private float downX,downY;private int selected=-1;private boolean drag;
        PanelView(Context c,boolean isLeft){super(c);left=isLeft;setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.TRANSPARENT);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();
            p.setStyle(Paint.Style.FILL);p.setColor(0x55202E40);c.drawRoundRect(new RectF(dp(3),dp(3),w-dp(3),h-dp(3)),dp(14),dp(14),p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(editing?Color.YELLOW:0xBBDAE9F8);c.drawRoundRect(new RectF(dp(3),dp(3),w-dp(3),h-dp(3)),dp(14),dp(14),p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));p.setColor(Color.WHITE);
            c.drawText(left?"ANALOG":"GAME PAD",w/2,dp(17),p);
            float top=dp(26), cell=Math.max(dp(20),(w-dp(12))/5f);
            drawChip(c,dp(6),top,cell-dp(2),dp(22),editing?"DONE":"EDIT");
            drawChip(c,dp(6)+cell,top,cell-dp(2),dp(22),"W-");
            drawChip(c,dp(6)+2*cell,top,cell-dp(2),dp(22),"W+");
            drawChip(c,dp(6)+3*cell,top,cell-dp(2),dp(22),"H-");
            drawChip(c,dp(6)+4*cell,top,Math.max(dp(18),w-dp(6)-(dp(6)+4*cell)),dp(22),"H+");
            if(left){
                float cx=analogX*w,cy=analogY*h,r=Math.min(w*.30f,h*.20f);
                p.setColor(0x8845B9D5);c.drawCircle(cx,cy,r,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(0xFFECF7FF);c.drawCircle(cx,cy,r,p);
                p.setStyle(Paint.Style.FILL);p.setColor(0xCC142536);c.drawCircle(cx,cy,r*.36f,p);p.setColor(Color.WHITE);p.setTextSize(dp(9));c.drawText("ANALOG",cx,cy+r+dp(13),p);
            }else{
                float r=Math.max(dp(13),Math.min(w*.20f,h*.065f));
                for(int i=0;i<actions.length;i++){float x=rightPoints[i][0]*w,y=rightPoints[i][1]*h;p.setStyle(Paint.Style.FILL);p.setColor(0xCC17617A);c.drawCircle(x,y,r,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(Color.WHITE);c.drawCircle(x,y,r,p);p.setStyle(Paint.Style.FILL);p.setTextSize(dp(8));p.setColor(Color.WHITE);c.drawText(actions[i],x,y+dp(3),p);}
            }
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTextSize(dp(8));c.drawText(editing?"SERET TOMBOL • TAP W/H":"TAP W/H UNTUK UKURAN",w/2,h-dp(8),p);
        }
        private void drawChip(Canvas c,float x,float y,float w,float h,String s){p.setStyle(Paint.Style.FILL);p.setColor(0xFF25384B);c.drawRoundRect(new RectF(x,y,x+w,y+h),dp(5),dp(5),p);p.setColor(Color.WHITE);p.setTextSize(dp(9));p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x+w/2,y+h/2+dp(3),p);}
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY(),w=Math.max(1,getWidth()),h=Math.max(1,getHeight());
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                downX=x;downY=y;selected=-1;drag=false;
                if(y<dp(52)){
                    float cell=(w-dp(12))/5f;int ix=Math.min(4,Math.max(0,(int)((x-dp(6))/Math.max(1,cell))));
                    if(ix==0){editing=!editing;leftPanel.invalidate();rightPanel.invalidate();Toast.makeText(GamePadOverlayService.this,editing?"Mode edit aktif":"Mode edit selesai",Toast.LENGTH_SHORT).show();return true;}
                    if(ix==1){if(left)leftWidth=Math.max(.14f,leftWidth-.03f);else rightWidth=Math.max(.14f,rightWidth-.03f);}
                    if(ix==2){if(left)leftWidth=Math.min(.36f,leftWidth+.03f);else rightWidth=Math.min(.38f,rightWidth+.03f);}
                    if(ix==3){if(left)leftHeight=Math.max(.35f,leftHeight-.08f);else rightHeight=Math.max(.35f,rightHeight-.08f);}
                    if(ix==4){if(left)leftHeight=Math.min(.95f,leftHeight+.08f);else rightHeight=Math.min(.95f,rightHeight+.08f);}
                    save();post(()->updatePanels());return true;
                }
                if(left){float dx=x-analogX*w,dy=y-analogY*h;float r=Math.min(w*.34f,h*.24f);if(dx*dx+dy*dy<=r*r||editing){selected=0;drag=editing;if(!editing)sendTouch(0,"DOWN",panelNormX(true,x,w),panelNormY(y,h));return true;}}
                else{float r=Math.max(dp(13),Math.min(w*.20f,h*.065f));for(int i=0;i<actions.length;i++){float dx=x-rightPoints[i][0]*w,dy=y-rightPoints[i][1]*h;if(dx*dx+dy*dy<=r*r||editing&&Math.abs(dx)<r*1.4f&&Math.abs(dy)<r*1.4f){selected=i;drag=editing;if(!editing)sendTouch(i+1,"DOWN",panelNormX(false,x,w),panelNormY(y,h));return true;}}}
                // Empty portions of the small panel do not consume the rest of the phone screen.
                return false;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&selected>=0&&drag){
                if(left){analogX=clamp(x/w,.15f,.85f);analogY=clamp(y/h,.20f,.85f);}else{rightPoints[selected][0]=clamp(x/w,.15f,.85f);rightPoints[selected][1]=clamp(y/h,.16f,.90f);}invalidate();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&selected>=0&&!editing){sendTouch(left?0:selected+1,"MOVE",panelNormX(left,x,w),panelNormY(y,h));return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP&&selected>=0){
                if(drag){save();invalidate();}else sendTouch(left?0:selected+1,"UP",panelNormX(left,x,w),panelNormY(y,h));
                selected=-1;return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(selected>=0&&!drag)sendTouch(left?0:selected+1,"UP",panelNormX(left,x,w),panelNormY(y,h));selected=-1;return true;}
            return selected>=0;
        }
        private float panelNormX(boolean isLeft,float x,float w){float screenX=isLeft?x:screenWidth-w+x;return clamp(screenX/screenWidth,.001f,.999f);}
        private float panelNormY(float y,float h){float screenY=(screenHeight-h)/2f+y;return clamp(screenY/screenHeight,.001f,.999f);}
    }
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
