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
    // Each cube side is subdivided into a 24 x 24 quad grid.
    // Six independent side grids give 3,750 editable vertices and 3,456 quads.
    // Matching border vertices receive the same screen-space brush dabs.
    private static final int MESH_SUBDIVISIONS = 24;
    private final float[][] vertices = createDenseCubeVertices();
    private final int[][] faces = createDenseCubeFaces();
    private final float[] vertexMask = new float[vertices.length];

    private static float[][] createDenseCubeVertices() {
        int n = MESH_SUBDIVISIONS + 1;
        float[][] result = new float[6 * n * n][3];
        int index = 0;
        // For each side, uAxis x vAxis points outwards.
        int[][] side = {
            {0, 1, 2,  1}, // +X: y x z
            {0, 2, 1, -1}, // -X: z x y
            {1, 2, 0,  1}, // +Y: z x x
            {1, 0, 2, -1}, // -Y: x x z
            {2, 0, 1,  1}, // +Z: x x y
            {2, 1, 0, -1}  // -Z: y x x
        };
        for (int s = 0; s < 6; s++) {
            int fixedAxis = side[s][0];
            int uAxis = side[s][1];
            int vAxis = side[s][2];
            int sign = side[s][3];
            for (int v = 0; v <= MESH_SUBDIVISIONS; v++) {
                for (int u = 0; u <= MESH_SUBDIVISIONS; u++) {
                    float fu = -1f + 2f * u / MESH_SUBDIVISIONS;
                    float fv = -1f + 2f * v / MESH_SUBDIVISIONS;
                    result[index][fixedAxis] = (s % 2 == 0) ? 1f : -1f;
                    result[index][uAxis] = fu;
                    result[index][vAxis] = fv;
                    index++;
                }
            }
        }
        return result;
    }

    private static int[][] createDenseCubeFaces() {
        int n = MESH_SUBDIVISIONS + 1;
        int[][] result = new int[6 * MESH_SUBDIVISIONS * MESH_SUBDIVISIONS][4];
        int face = 0;
        for (int side = 0; side < 6; side++) {
            int base = side * n * n;
            for (int v = 0; v < MESH_SUBDIVISIONS; v++) {
                for (int u = 0; u < MESH_SUBDIVISIONS; u++) {
                    int a = base + v * n + u;
                    int b = a + 1;
                    int d = a + n;
                    int cc = d + 1;
                    result[face++] = new int[]{a, b, cc, d};
                }
            }
        }
        return result;
    }
    private boolean sculpting;
    private int sculptBrush;
    private float sculptRadius = 0.18f, sculptStrength = 0.40f, sculptFalloff = 0.5f, sculptPressure = 1.0f;
    private int sculptSymmetry;
    private boolean sculptSubtract;
    private float strokeX, strokeY, previousStrokeX, previousStrokeY;
    private boolean strokeActive;
    private boolean moved;
    private float downX, downY;

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

        float[][] p=new float[vertices.length][3];
        for(int i=0;i<vertices.length;i++) p[i]=project(vertices[i][0],vertices[i][1],vertices[i][2],cx,cy,unit);

        // Draw only outward-facing faces, sorted far-to-near. This prevents the
        // previous see-through/inverted-looking cube caused by drawing all six sides.
        int[] order=new int[faces.length];
        float[] depth=new float[faces.length];
        int count=0;
        for(int f=0;f<faces.length;f++){
            int[] face=faces[f];
            float[] a=cameraPoint(vertices[face[0]][0],vertices[face[0]][1],vertices[face[0]][2]);
            float[] b=cameraPoint(vertices[face[1]][0],vertices[face[1]][1],vertices[face[1]][2]);
            float[] c=cameraPoint(vertices[face[2]][0],vertices[face[2]][1],vertices[face[2]][2]);
            float abx=b[0]-a[0], aby=b[1]-a[1], abz=b[2]-a[2];
            float acx=c[0]-a[0], acy=c[1]-a[1], acz=c[2]-a[2];
            float nx=aby*acz-abz*acy, ny=abz*acx-abx*acz, nz=abx*acy-aby*acx;
            float centerZ=(a[2]+b[2]+c[2]+cameraPoint(vertices[face[3]][0],vertices[face[3]][1],vertices[face[3]][2])[2])*0.25f;
            // Camera looks toward negative camera-Z; visible outward normals face camera.
            if(nx*0f+ny*0f+nz > 0.0001f) {
                order[count]=f; depth[f]=centerZ; count++;
            }
        }
        for(int i=0;i<count;i++) for(int j=i+1;j<count;j++) if(depth[order[i]]>depth[order[j]]){
            int t=order[i]; order[i]=order[j]; order[j]=t;
        }
        for(int n=0;n<count;n++){
            int f=order[n]; int[] face=faces[f];
            Path path=new Path();
            path.moveTo(p[face[0]][0],p[face[0]][1]);
            for(int k=1;k<4;k++) path.lineTo(p[face[k]][0],p[face[k]][1]);
            path.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(FACE_COLORS[f / (MESH_SUBDIVISIONS * MESH_SUBDIVISIONS)]); canvas.drawPath(path,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(0xAA303840);
            paint.setStrokeWidth(0.65f); canvas.drawPath(path,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawNavigationGizmo(canvas, width, 18f*getResources().getDisplayMetrics().density);
    }

    private void drawNavigationGizmo(Canvas canvas,float width,float margin) {
        float d=getResources().getDisplayMetrics().density;
        float gx=width-190*d, gy=150*d, r=23*d;
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

    public void setSculptSettings(boolean enabled, int brush, float radius, float strength,
                                   float falloff, int symmetry, boolean subtract, float pressure) {
        sculpting = enabled;
        sculptBrush = Math.max(0, Math.min(24, brush));
        sculptRadius = Math.max(0.02f, Math.min(1.0f, radius));
        sculptStrength = Math.max(0f, Math.min(1f, strength));
        sculptFalloff = Math.max(0f, Math.min(1f, falloff));
        sculptSymmetry = Math.max(0, Math.min(3, symmetry));
        sculptSubtract = subtract;
        sculptPressure = Math.max(0.05f, Math.min(1f, pressure));
        invalidate();
    }

    private void applySculptDab(float x, float y, float dragX, float dragY) {
        float cx=getWidth()*0.5f, cy=getHeight()*0.49f;
        float unit=Math.min(getWidth(),getHeight())*0.235f*zoom;
        float brushPx=Math.max(5f, Math.min(getWidth(),getHeight())*sculptRadius*0.55f);
        float amount=sculptStrength*sculptPressure*(sculptSubtract?-1f:1f)*0.075f;
        float meanY=0f; for(float[] v:vertices) meanY+=v[1]; meanY/=vertices.length;
        float meanX=0f, meanZ=0f;
        for(float[] v:vertices){meanX+=v[0];meanZ+=v[2];}
        meanX/=vertices.length; meanZ/=vertices.length;
        for(int i=0;i<vertices.length;i++){
            float[] q=project(vertices[i][0],vertices[i][1],vertices[i][2],cx,cy,unit);
            float dx=q[0]-x, dy=q[1]-y, dist=(float)Math.sqrt(dx*dx+dy*dy);
            if (sculptSymmetry == 1) {
                float[] mirror=project(-vertices[i][0],vertices[i][1],vertices[i][2],cx,cy,unit);
                float mdx=mirror[0]-x, mdy=mirror[1]-y;
                dist=Math.min(dist,(float)Math.sqrt(mdx*mdx+mdy*mdy));
            } else if (sculptSymmetry == 2) {
                float[] mirror=project(vertices[i][0],-vertices[i][1],vertices[i][2],cx,cy,unit);
                float mdx=mirror[0]-x, mdy=mirror[1]-y;
                dist=Math.min(dist,(float)Math.sqrt(mdx*mdx+mdy*mdy));
            } else if (sculptSymmetry == 3) {
                float[] mirror=project(vertices[i][0],vertices[i][1],-vertices[i][2],cx,cy,unit);
                float mdx=mirror[0]-x, mdy=mirror[1]-y;
                dist=Math.min(dist,(float)Math.sqrt(mdx*mdx+mdy*mdy));
            }
            if(dist>brushPx || vertexMask[i]>0.95f) continue;
            float t=1f-dist/brushPx;
            float weight=(float)Math.pow(Math.max(0f,t),0.5f+sculptFalloff*3f)*(1f-vertexMask[i]);
            float a=amount*weight;
            float nx=vertices[i][0], ny=vertices[i][1], nz=vertices[i][2];
            float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz); if(len<0.001f) len=1f;
            switch(sculptBrush){
                case 0: case 8: case 10: case 12: case 13: case 21:
                    vertices[i][0]+=nx/len*a*(sculptBrush==12?1.8f:1f);
                    vertices[i][1]+=ny/len*a*(sculptBrush==8?1.35f:1f);
                    vertices[i][2]+=nz/len*a; break;
                case 1: case 19: case 24:
                    vertices[i][0]+=(meanX-nx)*Math.abs(a)*0.45f;
                    vertices[i][1]+=(meanY-ny)*Math.abs(a)*0.45f;
                    vertices[i][2]+=(meanZ-nz)*Math.abs(a)*0.45f; break;
                case 2: case 14: case 15: case 16: case 17: case 23:
                    vertices[i][0]+=dragX/Math.max(1f,unit)*a*4f;
                    vertices[i][1]-=dragY/Math.max(1f,unit)*a*4f;
                    vertices[i][2]+=a*0.35f; break;
                case 3:
                    vertices[i][0]+=nx/len*a*1.2f; vertices[i][1]+=ny/len*a*1.2f;
                    vertices[i][2]+=nz/len*a*1.2f; break;
                case 11:
                    vertexMask[i]=Math.max(0f,vertexMask[i]-weight*0.75f); break;
                case 4:
                    vertices[i][0]+=(meanX-nx)*a*1.8f;
                    vertices[i][2]+=(meanZ-nz)*a*1.8f; break;
                case 5: case 9: case 20:
                    vertices[i][1]+=(meanY-ny)*Math.abs(a)*1.5f; break;
                case 6: case 22:
                    vertices[i][0]-=nx/len*a; vertices[i][1]-=ny/len*a;
                    vertices[i][2]-=nz/len*a; break;
                case 7: vertexMask[i]=Math.min(1f,vertexMask[i]+weight*0.65f); break;
                case 18:
                    if(Math.abs(nx)>0.85f || Math.abs(ny)>0.85f || Math.abs(nz)>0.85f)
                        vertices[i][1]+=a; break;
                default: vertices[i][0]+=a*0.25f; break;
            }
        }
        invalidate();
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
                moved=false; lastPinchDistance=0;
                if(sculpting){
                    strokeActive=true;
                    strokeX=previousStrokeX=event.getX();
                    strokeY=previousStrokeY=event.getY();
                    applySculptDab(strokeX,strokeY,0f,0f);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastPinchDistance=pointerDistance(event); moved=true; return true;
            case MotionEvent.ACTION_MOVE:
                if(sculpting && strokeActive && event.getPointerCount()==1){
                    float sx=event.getX(), sy=event.getY();
                    applySculptDab(sx,sy,sx-previousStrokeX,sy-previousStrokeY);
                    previousStrokeX=sx; previousStrokeY=sy;
                    moved=true;
                    return true;
                }
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
                strokeActive=false;
                if(!moved && !sculpting){
                    float gx=getWidth()-190*d, gy=150*d, radius=35*d;
                    float dx=event.getX()-gx,dy=event.getY()-gy;
                    if(dx*dx+dy*dy<=radius*radius){
                        snapToNearestAxis(dx,dy);
                    } else if(onViewportTap!=null) onViewportTap.run();
                }
                lastPinchDistance=0; performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                strokeActive=false; lastPinchDistance=0; return true;
        }
        return true;
    }

    private void snapToNearestAxis(float dx,float dy) {
        // Tapping the gizmo snaps to a clean front/top/side view.
        if(Math.abs(dx)>Math.abs(dy)) yaw=dx>=0?0f:(float)Math.PI;
        else pitch=dy<0?1.25f:-1.25f;
        invalidate();
    }

    /** Serialize the actual editable Meshy3D mesh and viewport state. */
    public org.json.JSONObject exportDocumentState() {
        try {
            org.json.JSONObject state = new org.json.JSONObject();
            state.put("meshSubdivisions", MESH_SUBDIVISIONS);
            state.put("vertexCount", vertices.length);
            org.json.JSONArray vertexArray = new org.json.JSONArray();
            org.json.JSONArray maskArray = new org.json.JSONArray();
            for (int i = 0; i < vertices.length; i++) {
                org.json.JSONArray point = new org.json.JSONArray();
                point.put(vertices[i][0]); point.put(vertices[i][1]); point.put(vertices[i][2]);
                vertexArray.put(point);
                maskArray.put(vertexMask[i]);
            }
            state.put("vertices", vertexArray);
            state.put("mask", maskArray);
            state.put("yaw", yaw); state.put("pitch", pitch); state.put("zoom", zoom);
            return state;
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not serialize Meshy3D mesh", error);
        }
    }

    /** Restore mesh coordinates only when the saved topology matches this editor build. */
    public boolean importDocumentState(org.json.JSONObject state) {
        if (state == null) return false;
        try {
            org.json.JSONArray savedVertices = state.getJSONArray("vertices");
            if (savedVertices.length() != vertices.length) return false;
            org.json.JSONArray savedMask = state.optJSONArray("mask");
            for (int i = 0; i < vertices.length; i++) {
                org.json.JSONArray point = savedVertices.getJSONArray(i);
                if (point.length() != 3) return false;
                for (int axis = 0; axis < 3; axis++) {
                    double value = point.getDouble(axis);
                    if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) > 100000.0) return false;
                }
            }
            for (int i = 0; i < vertices.length; i++) {
                org.json.JSONArray point = savedVertices.getJSONArray(i);
                for (int axis = 0; axis < 3; axis++) vertices[i][axis] = (float) point.getDouble(axis);
                vertexMask[i] = savedMask != null && i < savedMask.length()
                        ? Math.max(0f, Math.min(1f, (float) savedMask.optDouble(i, 0.0))) : 0f;
            }
            yaw = (float) state.optDouble("yaw", yaw);
            pitch = Math.max(-1.35f, Math.min(1.35f, (float) state.optDouble("pitch", pitch)));
            zoom = Math.max(0.35f, Math.min(3.4f, (float) state.optDouble("zoom", zoom)));
            invalidate();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override public boolean performClick(){super.performClick();return true;}
}
