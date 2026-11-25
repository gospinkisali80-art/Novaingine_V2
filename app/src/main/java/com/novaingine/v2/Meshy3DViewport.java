package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Standalone Meshy3D viewport with an opaque solid cube and Blender-style navigation gizmo. */
public final class Meshy3DViewport extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable onViewportTap;
    private float yaw = -0.62f;
    private float pitch = 0.42f;
    private float lastX, lastY, lastPinchDistance;
    private float zoom = 1.0f;
    private boolean moved;
    private float downX, downY;

    private static final float[][] V = {
        {-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},
        {-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}
    };
    private static final int[][] FACES = {
        {0,3,2,1},{4,5,6,7},{0,1,5,4},
        {3,7,6,2},{0,4,7,3},{1,2,6,5}
    };
    private static final int[] FACE_COLORS = {
        0xFF9DAAB7,0xFF66798C,0xFF7D8FA1,
        0xFFB7C2CC,0xFF596D80,0xFF8999A8
    };

    public Meshy3DViewport(Context context) { this(context, null); }

    public Meshy3DViewport(Context context, Runnable onViewportTap) {
        super(context);
        this.onViewportTap = onViewportTap;
        setBackgroundColor(0xFF30343B);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
    }

    private float[] cameraPoint(float x, float y, float z) {
        double cy=Math.cos(yaw), sy=Math.sin(yaw);
        double cp=Math.cos(pitch), sp=Math.sin(pitch);
        double rx=x*cy-z*sy;
        double rz=x*sy+z*cy;
        double ry=y*cp-rz*sp;
        double rz2=y*sp+rz*cp;
        return new float[]{(float)rx,(float)ry,(float)rz2};
    }

    private float[] project(float x, float y, float z, float cx, float cy, float unit) {
        float[] q=cameraPoint(x,y,z);
        float depth=5.2f-q[2];
        float perspective=5.2f/Math.max(2.0f,depth);
        return new float[]{cx+q[0]*unit*perspective,cy-q[1]*unit*perspective,q[2]};
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width=getWidth(), height=getHeight();
        final float cx=width*0.5f, cy=height*0.49f;
        final float unit=Math.min(width,height)*0.235f*zoom;

        // Perspective floor grid with Blender-like red X and green Y ground axes.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        for(int i=-12;i<=12;i++){
            int grid=i==0?0xFF6B747D:0xFF3B424A;
            paint.setColor(grid);
            float[] a=project(i*0.5f,-1.12f,-6,cx,cy,unit);
            float[] b=project(i*0.5f,-1.12f,6,cx,cy,unit);
            canvas.drawLine(a[0],a[1],b[0],b[1],paint);
            float[] c=project(-6,-1.12f,i*0.5f,cx,cy,unit);
            float[] d=project(6,-1.12f,i*0.5f,cx,cy,unit);
            canvas.drawLine(c[0],c[1],d[0],d[1],paint);
        }
        paint.setStrokeWidth(2.0f);
        paint.setColor(0xFFB34E50);
        float[] x1=project(-6,-1.115f,0,cx,cy,unit), x2=project(6,-1.115f,0,cx,cy,unit);
        canvas.drawLine(x1[0],x1[1],x2[0],x2[1],paint);
        paint.setColor(0xFF55A879);
        float[] y1=project(0,-1.11f,-6,cx,cy,unit), y2=project(0,-1.11f,6,cx,cy,unit);
        canvas.drawLine(y1[0],y1[1],y2[0],y2[1],paint);

        float[][] p=new float[8][3];
        for(int i=0;i<8;i++) p[i]=project(V[i][0],V[i][1],V[i][2],cx,cy,unit);

        // Draw only outward-facing faces, sorted far-to-near. This prevents the
        // previous see-through/inverted-looking cube caused by drawing all six sides.
        int[] order=new int[FACES.length];
        float[] depth=new float[FACES.length];
        int count=0;
        for(int f=0;f<FACES.length;f++){
            int[] face=FACES[f];
            float[] a=cameraPoint(V[face[0]][0],V[face[0]][1],V[face[0]][2]);
            float[] b=cameraPoint(V[face[1]][0],V[face[1]][1],V[face[1]][2]);
            float[] c=cameraPoint(V[face[2]][0],V[face[2]][1],V[face[2]][2]);
            float abx=b[0]-a[0], aby=b[1]-a[1], abz=b[2]-a[2];
            float acx=c[0]-a[0], acy=c[1]-a[1], acz=c[2]-a[2];
            float nx=aby*acz-abz*acy, ny=abz*acx-abx*acz, nz=abx*acy-aby*acx;
            float centerZ=(a[2]+b[2]+c[2]+cameraPoint(V[face[3]][0],V[face[3]][1],V[face[3]][2])[2])*0.25f;
            // Camera looks toward negative camera-Z; visible outward normals face camera.
            if(nx*0f+ny*0f+nz > 0.0001f) {
                order[count]=f; depth[f]=centerZ; count++;
            }
        }
        for(int i=0;i<count;i++) for(int j=i+1;j<count;j++) if(depth[order[i]]>depth[order[j]]){
            int t=order[i]; order[i]=order[j]; order[j]=t;
        }
        for(int n=0;n<count;n++){
            int f=order[n]; int[] face=FACES[f];
            Path path=new Path();
            path.moveTo(p[face[0]][0],p[face[0]][1]);
            for(int k=1;k<4;k++) path.lineTo(p[face[k]][0],p[face[k]][1]);
            path.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(FACE_COLORS[f]); canvas.drawPath(path,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(0xFFFFA33A);
            paint.setStrokeWidth(2.4f); canvas.drawPath(path,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawNavigationGizmo(canvas, width, 18f*getResources().getDisplayMetrics().density);
    }

    private void drawNavigationGizmo(Canvas canvas,float width,float margin) {
        float d=getResources().getDisplayMetrics().density;
        float gx=width-47*d, gy=150*d, r=23*d;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF20252C);
        canvas.drawCircle(gx,gy,r+8*d,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1*d);
        paint.setColor(0xFF65717D); canvas.drawCircle(gx,gy,r,paint);
        float[][] axes={{1,0,0},{0,1,0},{0,0,1}};
        int[] colors={0xFFE35D5D,0xFF59B77C,0xFF669BE8};
        String[] labels={"X","Y","Z"};
        for(int i=0;i<3;i++){
            float[] q=cameraPoint(axes[i][0],axes[i][1],axes[i][2]);
            float ex=gx+q[0]*r*0.88f, ey=gy-q[1]*r*0.88f;
            paint.setColor(colors[i]); paint.setStrokeWidth(3*d);
            canvas.drawLine(gx,gy,ex,ey,paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(ex,ey,4.5f*d,paint);
            paint.setTextSize(10*d); canvas.drawText(labels[i],ex+4*d,ey-3*d,paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private float pointerDistance(MotionEvent e) {
        if(e.getPointerCount()<2) return 0;
        float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float d=getResources().getDisplayMetrics().density;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=lastX=event.getX(); downY=lastY=event.getY();
                moved=false; lastPinchDistance=0; return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastPinchDistance=pointerDistance(event); moved=true; return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()>=2){
                    float distance=pointerDistance(event);
                    if(lastPinchDistance>0 && distance>0){
                        zoom=Math.max(0.38f,Math.min(3.4f,zoom*distance/lastPinchDistance));
                        invalidate();
                    }
                    lastPinchDistance=distance;
                } else {
                    float dx=event.getX()-lastX, dy=event.getY()-lastY;
                    if(Math.abs(event.getX()-downX)+Math.abs(event.getY()-downY)>4*d) moved=true;
                    yaw-=dx*0.008f;
                    pitch=Math.max(-1.35f,Math.min(1.35f,pitch+dy*0.008f));
                    invalidate();
                }
                lastX=event.getX(); lastY=event.getY(); return true;
            case MotionEvent.ACTION_POINTER_UP:
                lastPinchDistance=0;
                if(event.getPointerCount()>1){
                    int index=event.getActionIndex()==0?1:0;
                    lastX=event.getX(index); lastY=event.getY(index);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(!moved){
                    float gx=getWidth()-47*d, gy=150*d, radius=35*d;
                    float dx=event.getX()-gx,dy=event.getY()-gy;
                    if(dx*dx+dy*dy<=radius*radius){
                        snapToNearestAxis(dx,dy);
                    } else if(onViewportTap!=null) onViewportTap.run();
                }
                lastPinchDistance=0; performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                lastPinchDistance=0; return true;
        }
        return true;
    }

    private void snapToNearestAxis(float dx,float dy) {
        // Tapping the gizmo snaps to a clean front/top/side view.
        if(Math.abs(dx)>Math.abs(dy)) yaw=dx>=0?0f:(float)Math.PI;
        else pitch=dy<0?1.25f:-1.25f;
        invalidate();
    }

    @Override public boolean performClick(){super.performClick();return true;}
}
