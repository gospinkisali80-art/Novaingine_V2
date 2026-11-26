package com.novaingine.v2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Interactive 2D UV/image editor backed by the active Meshy3D document. */
public final class Meshy3DUvEditorView extends View {
    private final Meshy3DCore core;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean paintMode;
    private float lastX,lastY;
    private Bitmap textureBitmap;
    private int brushColor=0xFFFF6B35;
    private float brushRadius=18f;
    private float opacity=0.9f;

    public Meshy3DUvEditorView(Context context,Meshy3DCore core,boolean paintMode){
        super(context);this.core=core;this.paintMode=paintMode;brushColor=core.getBrushColor();brushRadius=core.getBrushRadius();opacity=core.getBrushOpacity();setBackgroundColor(0xFF252B33);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    public void setPaintMode(boolean enabled){paintMode=enabled;invalidate();}
    public void setBrush(int color,float radius,float alpha){brushColor=color;brushRadius=radius;opacity=alpha;core.setBrush(color,radius,alpha);invalidate();}
    private float side(){return Math.max(40f,Math.min(getWidth(),getHeight())-48f);}
    private float left(){return (getWidth()-side())*.5f;}
    private float top(){return (getHeight()-side())*.5f;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float s=side(),l=left(),t=top();
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF171B20);c.drawRect(l,t,l+s,t+s,paint);
        if(paintMode)drawTexture(c,l,t,s);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1f);paint.setColor(0xFF414B56);
        for(int i=1;i<10;i++){float p=l+s*i/10f;c.drawLine(p,t,p,t+s,paint);c.drawLine(l,p,l+s,p,paint);}
        paint.setColor(0xFF8794A1);paint.setStrokeWidth(1.5f);c.drawRect(l,t,l+s,t+s,paint);
        float[][][] uv=core.copyFaceUvs();if(uv==null)return;
        java.util.List<Meshy3DCore.UvIsland> islands=core.getUvIslands();int selected=core.getSelectedUvIsland();
        for(int fi=0;fi<uv.length;fi++){
            boolean active=false;for(int k=0;k<islands.size();k++)if(k==selected&&islands.get(k).faces.contains(fi)){active=true;break;}
            Path path=new Path();for(int j=0;j<uv[fi].length;j++){
                float x=l+uv[fi][j][0]*s,y=t+(1f-uv[fi][j][1])*s;
                if(j==0)path.moveTo(x,y);else path.lineTo(x,y);
            }path.close();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(active?2.5f:1f);
            paint.setColor(active?0xFFFFA52B:0xFFB9C5D0);c.drawPath(path,paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(13f);paint.setColor(0xFFE0E6EC);
        c.drawText(paintMode?"TEXTURE IMAGE • pixel painting":"UV EDITOR • drag islands to move",12,22,paint);
    }
    private void drawTexture(Canvas c,float l,float t,float s){
        int[] pixels=core.getTexturePixelsUnsafe();int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null){paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF777777);c.drawRect(l,t,l+s,t+s,paint);return;}
        try{if(textureBitmap==null||textureBitmap.getWidth()!=w||textureBitmap.getHeight()!=h)
            textureBitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
            else textureBitmap.setPixels(pixels,0,w,0,0,w,h);
            paint.setStyle(Paint.Style.FILL);c.drawBitmap(textureBitmap,null,new android.graphics.RectF(l,t,l+s,t+s),paint);
        }catch(Exception ignored){}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float s=side(),l=left(),t=top();
        float u=Math.max(0,Math.min(1,(e.getX()-l)/s));
        float v=Math.max(0,Math.min(1,1f-(e.getY()-t)/s));
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            lastX=e.getX();lastY=e.getY();
            if(paintMode){core.setBrush(brushColor,brushRadius,opacity);core.paintPixel((int)(u*core.getTextureWidth()),(int)(v*core.getTextureHeight()));}
            else core.selectUvIsland(core.findUvIsland(u,v));
            invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(paintMode){core.setBrush(brushColor,brushRadius,opacity);core.paintPixel((int)(u*core.getTextureWidth()),(int)(v*core.getTextureHeight()));}
            else {float du=(e.getX()-lastX)/s,dv=-(e.getY()-lastY)/s;core.transformSelectedUv(du,dv,1f,0f);}
            lastX=e.getX();lastY=e.getY();invalidate();return true;
        }
        return e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL;
    }
}