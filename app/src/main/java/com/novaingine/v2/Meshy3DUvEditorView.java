package com.novaingine.v2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Interactive UV and texture-image canvas backed by the active Meshy3D document. */
public final class Meshy3DUvEditorView extends View {
    private final Meshy3DCore core;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable onEditStart;
    private boolean paintMode;
    private String paintTool="Draw";
    private float lastX,lastY,downX,downY;
    private int lastPaintX,lastPaintY;
    private int lineEndX,lineEndY;
    private int cloneSourceX,cloneSourceY;
    private boolean cloneSourceSet;
    private boolean linePreview;
    private boolean editStarted;
    private Bitmap textureBitmap;
    private int brushColor=0xFFFF6B35;
    private float brushRadius=18f;
    private float opacity=0.9f;

    public Meshy3DUvEditorView(Context context,Meshy3DCore core,boolean paintMode){
        this(context,core,paintMode,null);
    }

    public Meshy3DUvEditorView(Context context,Meshy3DCore core,boolean paintMode,Runnable onEditStart){
        super(context);
        this.core=core;
        this.paintMode=paintMode;
        this.onEditStart=onEditStart;
        brushColor=core.getBrushColor();
        brushRadius=core.getBrushRadius();
        opacity=core.getBrushOpacity();
        setBackgroundColor(0xFF252B33);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setClickable(true);
    }

    public void setPaintMode(boolean enabled){paintMode=enabled;invalidate();}
    public void setPaintTool(String tool){
        if(tool!=null&&!tool.trim().isEmpty())paintTool=tool.trim();
        invalidate();
    }
    public String getPaintTool(){return paintTool;}

    public void setBrush(int color,float radius,float alpha){
        brushColor=color;
        brushRadius=Math.max(1f,Math.min(256f,radius));
        opacity=Math.max(0f,Math.min(1f,alpha));
        core.setBrush(color,brushRadius,opacity);
        invalidate();
    }

    private float side(){return Math.max(40f,Math.min(getWidth(),getHeight())-48f);}
    private float left(){return (getWidth()-side())*.5f;}
    private float top(){return (getHeight()-side())*.5f;}
    private int imageX(float x){return Math.max(0,Math.min(core.getTextureWidth()-1,
            Math.round((x-left())/side()*(core.getTextureWidth()-1))));}
    private int imageY(float y){return Math.max(0,Math.min(core.getTextureHeight()-1,
            Math.round((1f-(y-top())/side())*(core.getTextureHeight()-1))));}
    private void beginEdit(){
        if(!editStarted){
            editStarted=true;
            if(onEditStart!=null)onEditStart.run();
        }
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float s=side(),l=left(),t=top();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF171B20);
        c.drawRect(l,t,l+s,t+s,paint);
        if(paintMode)drawTexture(c,l,t,s);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0xFF414B56);
        for(int i=1;i<10;i++){
            float p=l+s*i/10f;
            c.drawLine(p,t,p,t+s,paint);
            c.drawLine(l,p,l+s,p,paint);
        }
        paint.setColor(0xFF8794A1);
        paint.setStrokeWidth(1.5f);
        c.drawRect(l,t,l+s,t+s,paint);

        float[][][] uv=core.copyFaceUvs();
        if(!paintMode&&uv!=null){
            java.util.List<Meshy3DCore.UvIsland> islands=core.getUvIslands();
            int selected=core.getSelectedUvIsland();
            for(int fi=0;fi<uv.length;fi++){
                boolean active=false;
                for(int k=0;k<islands.size();k++)
                    if(k==selected&&islands.get(k).faces.contains(fi)){active=true;break;}
                Path path=new Path();
                for(int j=0;j<uv[fi].length;j++){
                    float x=l+uv[fi][j][0]*s,y=t+(1f-uv[fi][j][1])*s;
                    if(j==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                path.close();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(active?2.5f:1f);
                paint.setColor(active?0xFFFFA52B:0xFFB9C5D0);
                c.drawPath(path,paint);
            }
        }
        if(paintMode&&linePreview){
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f,brushRadius*.08f));
            paint.setColor(brushColor);
            c.drawLine(downX,downY,lineEndX,lineEndY,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(13f);
        paint.setColor(0xFFE0E6EC);
        c.drawText(paintMode?"TEXTURE PAINT  •  "+paintTool:"UV EDITOR  •  drag to move selected island",12,22,paint);
        if(paintMode&&paintTool.equals("Clone")&&!cloneSourceSet){
            paint.setTextSize(10f);paint.setColor(0xFFFFC36B);
            c.drawText("Choose Set Clone first",12,getHeight()-12,paint);
        }
    }

    private void drawTexture(Canvas c,float l,float t,float s){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||w<=0||h<=0){
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF777777);
            c.drawRect(l,t,l+s,t+s,paint);return;
        }
        try{
            if(textureBitmap==null||textureBitmap.getWidth()!=w||textureBitmap.getHeight()!=h)
                textureBitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
            else textureBitmap.setPixels(pixels,0,w,0,0,w,h);
            paint.setStyle(Paint.Style.FILL);
            c.drawBitmap(textureBitmap,null,new android.graphics.RectF(l,t,l+s,t+s),paint);
        }catch(Exception ignored){}
    }

    private static int blend(int dst,int src,float amount){
        float a=Math.max(0f,Math.min(1f,amount));
        int da=(dst>>>24)&255,dr=(dst>>>16)&255,dg=(dst>>>8)&255,db=dst&255;
        int sa=(src>>>24)&255,sr=(src>>>16)&255,sg=(src>>>8)&255,sb=src&255;
        return (Math.round(da+(sa-da)*a)<<24)
                |(Math.round(dr+(sr-dr)*a)<<16)
                |(Math.round(dg+(sg-dg)*a)<<8)
                |Math.round(db+(sb-db)*a);
    }

    private void fillTexture(int color){
        int[] pixels=core.getTexturePixelsUnsafe();
        if(pixels==null)return;
        java.util.Arrays.fill(pixels,color);
        core.markTextureChanged();
        textureBitmap=null;
        invalidate();
    }

    private void applyGradient(){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||w<1||h<1)return;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            float t=x/(float)Math.max(1,w-1);
            int index=y*w+x;
            pixels[index]=blend(0xFFFFFFFF,brushColor,t);
        }
        core.markTextureChanged();
        textureBitmap=null;
        invalidate();
    }

    private void softenAt(int x,int y){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||w<1||h<1)return;
        int radius=Math.max(1,Math.min(20,Math.round(brushRadius*.45f)));
        int[] source=pixels.clone();
        int left=Math.max(0,x-radius),right=Math.min(w-1,x+radius);
        int top=Math.max(0,y-radius),bottom=Math.min(h-1,y+radius);
        for(int py=top;py<=bottom;py++)for(int px=left;px<=right;px++){
            int r=0,g=0,b=0,a=0,count=0;
            for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++){
                int xx=px+ox,yy=py+oy;
                if(xx<0||yy<0||xx>=w||yy>=h)continue;
                int color=source[yy*w+xx];
                a+=(color>>>24)&255;r+=(color>>>16)&255;g+=(color>>>8)&255;b+=color&255;count++;
            }
            if(count>0){
                int average=((a/count)<<24)|((r/count)<<16)|((g/count)<<8)|(b/count);
                float dx=(px-x)/(float)radius,dy=(py-y)/(float)radius;
                float falloff=Math.max(0f,1f-(float)Math.sqrt(dx*dx+dy*dy));
                pixels[py*w+px]=blend(source[py*w+px],average,opacity*falloff);
            }
        }
        core.markTextureChanged();
        textureBitmap=null;
    }

    private void smearAt(int x,int y){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||w<1||h<1)return;
        int sx=Math.max(0,Math.min(w-1,lastPaintX)),sy=Math.max(0,Math.min(h-1,lastPaintY));
        int sample=pixels[sy*w+sx];
        int radius=Math.max(1,Math.min(48,Math.round(brushRadius)));
        for(int py=Math.max(0,y-radius);py<Math.min(h,y+radius);py++)
            for(int px=Math.max(0,x-radius);px<Math.min(w,x+radius);px++){
                float d=(float)Math.hypot(px-x,py-y)/radius;
                if(d<=1f)pixels[py*w+px]=blend(pixels[py*w+px],sample,opacity*(1f-d));
            }
        core.markTextureChanged();
        textureBitmap=null;
    }

    private void cloneAt(int x,int y){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||!cloneSourceSet)return;
        int offsetX=x-imageX(downX),offsetY=y-imageY(downY);
        int sx0=cloneSourceX+offsetX,sy0=cloneSourceY+offsetY;
        int radius=Math.max(1,Math.min(64,Math.round(brushRadius)));
        int[] source=pixels.clone();
        for(int py=Math.max(0,y-radius);py<Math.min(h,y+radius);py++)
            for(int px=Math.max(0,x-radius);px<Math.min(w,x+radius);px++){
                int sx=sx0+(px-x),sy=sy0+(py-y);
                if(sx<0||sy<0||sx>=w||sy>=h)continue;
                float d=(float)Math.hypot(px-x,py-y)/radius;
                if(d<=1f)pixels[py*w+px]=blend(pixels[py*w+px],source[sy*w+sx],opacity*(1f-d));
            }
        core.markTextureChanged();
        textureBitmap=null;
    }

    private void paintDab(int x,int y){
        if(paintTool.equals("Soften")){softenAt(x,y);return;}
        if(paintTool.equals("Smear")){smearAt(x,y);return;}
        if(paintTool.equals("Clone")){cloneAt(x,y);return;}
        if(paintTool.equals("Mask")){
            core.setBrush(0xFF6A7890,brushRadius,Math.min(.55f,opacity));
            core.paintPixel(x,y);return;
        }
        if(paintTool.equals("Erase Alpha")){
            core.setBrush(0x00000000,brushRadius,opacity);
            core.paintPixel(x,y);return;
        }
        if(paintTool.equals("Airbrush")){
            core.setBrush(brushColor,brushRadius,opacity*.22f);
            core.paintPixel(x,y);return;
        }
        core.setBrush(brushColor,brushRadius,opacity);
        core.paintPixel(x,y);
    }

    private void paintSegment(int x0,int y0,int x1,int y1){
        float distance=(float)Math.hypot(x1-x0,y1-y0);
        int steps=Math.max(1,(int)Math.ceil(distance/Math.max(1f,brushRadius*.22f)));
        for(int i=1;i<=steps;i++){
            int x=Math.round(x0+(x1-x0)*(i/(float)steps));
            int y=Math.round(y0+(y1-y0)*(i/(float)steps));
            paintDab(x,y);
            lastPaintX=x;lastPaintY=y;
        }
    }

    private void sampleColorAt(int x,int y){
        int[] pixels=core.getTexturePixelsUnsafe();
        int w=core.getTextureWidth(),h=core.getTextureHeight();
        if(pixels==null||x<0||y<0||x>=w||y>=h)return;
        brushColor=pixels[y*w+x];
        core.setBrush(brushColor,brushRadius,opacity);
        paintTool="Draw";
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        float s=side(),l=left(),t=top();
        float u=Math.max(0,Math.min(1,(e.getX()-l)/s));
        float v=Math.max(0,Math.min(1,1f-(e.getY()-t)/s));
        int px=imageX(e.getX()),py=imageY(e.getY());
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            downX=e.getX();lastX=e.getX();lastY=e.getY();
            lastPaintX=px;lastPaintY=py;editStarted=false;
            if(paintMode){
                if(paintTool.equals("Sample Color")){sampleColorAt(px,py);return true;}
                if(paintTool.equals("Set Clone")){
                    cloneSourceX=px;cloneSourceY=py;cloneSourceSet=true;
                    paintTool="Clone";invalidate();return true;
                }
                if(paintTool.equals("Fill")){beginEdit();fillTexture(brushColor);return true;}
                if(paintTool.equals("Gradient")){beginEdit();applyGradient();return true;}
                if(!paintTool.equals("Line")&&!paintTool.equals("Polyline")){
                    beginEdit();paintDab(px,py);
                }else{
                    linePreview=true;lineEndX=px;lineEndY=py;
                }
            }else{
                core.selectUvIsland(core.findUvIsland(u,v));
            }
            downY=e.getY();
            invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(paintMode){
                if(paintTool.equals("Line")||paintTool.equals("Polyline")){
                    lineEndX=px;lineEndY=py;invalidate();return true;
                }
                beginEdit();
                paintSegment(lastPaintX,lastPaintY,px,py);
            }else{
                float du=(e.getX()-lastX)/s,dv=-(e.getY()-lastY)/s;
                if(Math.abs(du)+Math.abs(dv)>.0001f){
                    beginEdit();
                    core.transformSelectedUv(du,dv,1f,0f);
                }
            }
            lastX=e.getX();lastY=e.getY();invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP){
            if(paintMode&&(paintTool.equals("Line")||paintTool.equals("Polyline"))){
                beginEdit();
                paintSegment(imageX(downX),imageY(downY),px,py);
                linePreview=false;
            }
            editStarted=false;
            invalidate();return true;
        }
        return e.getActionMasked()==MotionEvent.ACTION_CANCEL;
    }
}
