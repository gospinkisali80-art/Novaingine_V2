package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * Standalone Meshy3D preview viewport. It owns its camera orientation and
 * renders its own starter cube; it does not read or mutate Novaingine scene state.
 */
public final class Meshy3DViewport extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float yaw = -0.55f;
    private float pitch = 0.48f;
    private float lastX, lastY;
    private float zoom = 1.0f;

    public Meshy3DViewport(Context context) {
        super(context);
        setBackgroundColor(0xFF30343B);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.48f;
        float size = Math.min(getWidth(), getHeight()) * 0.19f * zoom;
        float[][] v = {
            {-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},
            {-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}
        };
        float[][] p = new float[8][2];
        double cyaw=Math.cos(yaw), syaw=Math.sin(yaw);
        double cp=Math.cos(pitch), sp=Math.sin(pitch);
        for(int i=0;i<8;i++){
            double x=v[i][0], y=v[i][1], z=v[i][2];
            double rx=x*cyaw-z*syaw;
            double rz=x*syaw+z*cyaw;
            double ry=y*cp-rz*sp;
            double rz2=y*sp+rz*cp;
            double perspective=3.8/(3.8-rz2*0.18);
            p[i][0]=(float)(cx+rx*size*perspective);
            p[i][1]=(float)(cy-ry*size*perspective);
        }
        int[][] faces={{0,1,2,3},{4,5,6,7},{0,1,5,4},{3,2,6,7},{0,3,7,4},{1,2,6,5}};
        int[] colors={0xFF8C9BAA,0xFF64788D,0xFF75899D,0xFF9BAABA,0xFF596B7D,0xFFB3BFCA};
        for(int f=0;f<faces.length;f++){
            Path path=new Path();
            path.moveTo(p[faces[f][0]][0],p[faces[f][0]][1]);
            for(int j=1;j<4;j++)path.lineTo(p[faces[f][j]][0],p[faces[f][j]][1]);
            path.close();
            paint.setColor(colors[f]); paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path,paint);
            paint.setColor(0xFF202832); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f); canvas.drawPath(path,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE5EAF0); paint.setTextSize(13);
        canvas.drawText("Meshy3D Viewport", 16, 24, paint);
        paint.setColor(0xFF9EAAB7); paint.setTextSize(11);
        canvas.drawText("Cube", 16, 42, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastX=event.getX(); lastY=event.getY(); return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()==1){
                    yaw+=(event.getX()-lastX)*0.009f;
                    pitch=Math.max(-1.25f,Math.min(1.25f,pitch+(event.getY()-lastY)*0.009f));
                    lastX=event.getX(); lastY=event.getY(); invalidate();
                } else if(event.getPointerCount()>=2){
                    float dx=event.getX(0)-event.getX(1),dy=event.getY(0)-event.getY(1);
                    float d=(float)Math.sqrt(dx*dx+dy*dy);
                    if(lastX>0)zoom=Math.max(0.55f,Math.min(2.2f,zoom*d/lastX));
                    lastX=d;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastX=0; return true;
        }
        return true;
    }
}
