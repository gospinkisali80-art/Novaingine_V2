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
    private final Meshy3DCore document = new Meshy3DCore();
    private float[][] vertices = document.copyVertices();
    private int[][] faces = document.copyFaces();
    private float[] vertexMask = new float[vertices.length];
    private boolean meshEditMode;
    private Meshy3DCore.SelectMode selectionMode = Meshy3DCore.SelectMode.FACE;
    private boolean meshVisible = true;
    private boolean showFloorGrid = true;
    private boolean showTopology = true;
    private int meshTint = 0xFF9DAAB7;
    private float metallic = 0.0f;
    private float roughness = 0.65f;
    private final float[][] originalVertices = document.copyVertices();
    private final int[][] originalFaces = document.copyFaces();
    private float[][] uvCoordinates;
    private final java.util.HashMap<Integer, float[][]> meshKeyframes = new java.util.HashMap<>();


    public Meshy3DCore getDocumentCore(){return document;}
    public android.graphics.Bitmap renderFinalImage(int width,int height){
        syncCoreFromViewport();
        return Meshy3DSoftwareRenderer.render(vertices,faces,width,height,yaw,pitch,meshTint,metallic,roughness,
                document.getTexturePixels(),document.getTextureWidth(),document.getTextureHeight(),document.copyFaceUvs());
    }
    public void refreshFromDocument(){syncViewportFromCore();}
    private void syncCoreFromViewport(){document.replaceGeometry(vertices,faces);document.setSculptMask(vertexMask);}
    private void syncViewportFromCore(){
        vertices=document.copyVertices();faces=document.copyFaces();vertexMask=document.getSculptMask();if(vertexMask.length!=vertices.length)vertexMask=new float[vertices.length];
        uvCoordinates=null;invalidate();
    }
    public void setMeshEditMode(boolean enabled){meshEditMode=enabled;if(enabled)showTopology=true;invalidate();}
    public boolean isMeshEditMode(){return meshEditMode;}
    public void setSelectionMode(String mode){
        if("Vertex".equalsIgnoreCase(mode))selectionMode=Meshy3DCore.SelectMode.VERTEX;
        else if("Edge".equalsIgnoreCase(mode))selectionMode=Meshy3DCore.SelectMode.EDGE;
        else selectionMode=Meshy3DCore.SelectMode.FACE;
        document.setSelectMode(selectionMode);invalidate();
    }
    public String getSelectionMode(){return selectionMode.name();}
    public int getSelectedElementCount(){return document.getSelectedCount();}
    public void selectAllElements(){document.selectAll();invalidate();}
    public void selectAt(float x,float y,boolean additive){
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.min(getWidth(),getHeight())*.235f*zoom;
        if(selectionMode==Meshy3DCore.SelectMode.VERTEX)document.selectNearestVertex(x,y,cx,cy,unit,yaw,pitch,additive);
        else if(selectionMode==Meshy3DCore.SelectMode.EDGE)document.selectNearestEdge(x,y,cx,cy,unit,yaw,pitch,additive);
        else document.selectNearestFace(x,y,cx,cy,unit,yaw,pitch,additive);
        invalidate();
    }
    public void insetSelected(float amount){document.insetSelected(amount);syncViewportFromCore();}
    public void bevelSelected(float amount){document.bevelSelectedEdges(amount);syncViewportFromCore();}
    public void loopCutSelected(float factor){document.loopCutSelected(factor);syncViewportFromCore();}
    public void subdivideSelected(){document.subdivide();syncViewportFromCore();}
    public void extrudeSelected(float amount){document.extrudeSelected(amount);syncViewportFromCore();}
    public void extrudeSelectedAlongNormals(float amount,boolean individual){document.extrudeSelectedAlongNormals(amount,individual);syncViewportFromCore();}
    public void mergeSelected(float distance){document.mergeSelectedByDistance(distance);syncViewportFromCore();}
    public void dissolveSelectedEdges(){document.dissolveSelectedEdges();syncViewportFromCore();}
    public void triangulateSelectedFaces(){document.triangulateSelectedFaces();syncViewportFromCore();}
    public void recalculateNormals(){document.recalculateNormals();syncViewportFromCore();}
    public void selectLoops(){document.selectEdgeLoops();invalidate();}
    public void selectSimilar(){document.selectSimilar();invalidate();}
    public void mirrorSelected(int axis){document.mirrorSelected(axis);syncViewportFromCore();}
    public void shrinkFattenSelected(float amount){document.shrinkFattenSelected(amount);syncViewportFromCore();}
    public void makePlanarSelected(){document.makePlanarSelected();syncViewportFromCore();}
    public void randomizeSelected(float amount){document.randomizeSelected(amount);syncViewportFromCore();}
    public void toSphereSelected(float amount){document.toSphereSelected(amount);syncViewportFromCore();}
    public void shearSelected(float amount){document.shearSelected(amount);syncViewportFromCore();}
    public void flipSelectedNormals(){document.flipSelectedNormals();syncViewportFromCore();}
    public void pokeSelectedFaces(float amount){document.pokeSelectedFaces(amount);syncViewportFromCore();}
    public void fillSelectedBoundary(){document.fillSelectedBoundary();syncViewportFromCore();}
    public void splitSelectedFaces(){document.splitSelectedFaces();syncViewportFromCore();}
    public void bisectMesh(float nx,float ny,float nz,float offset){document.bisect(nx,ny,nz,offset);syncViewportFromCore();}
    public void bridgeSelectedLoops(){document.bridgeSelectedBoundaryLoops();syncViewportFromCore();}
    public void solidifySelected(float thickness){document.solidifySelected(thickness);syncViewportFromCore();}
    public void spinSelectedFaces(float angle,int steps,float screwDistance){document.spinSelectedFaces(angle,steps,screwDistance);syncViewportFromCore();}

    public void slideSelected(float factor,boolean edgeMode){document.slideSelected(factor,edgeMode);syncViewportFromCore();}

    public void deleteSelectedFaces(){document.deleteSelectedFaces();syncViewportFromCore();}
    public void deleteSelectedVertices(){document.deleteSelectedVertices();syncViewportFromCore();}
    public void smoothTopology(float amount,int iterations){document.smooth(amount,iterations);syncViewportFromCore();}
    public void markSeams(boolean clear){document.markSelectedSeams(clear);invalidate();}
    public void pinSelectedUvs(boolean pin){document.pinSelectedUvs(pin);invalidate();}
    public void packUvIslands(){document.packUvIslands();invalidate();}
    public void selectLinkedUvIsland(){document.selectLinkedFromCurrent();invalidate();}
    public void unwrapMesh(){syncCoreFromViewport();document.unwrapPlanar();generatePlanarUv();invalidate();}
    public void createPaintTexture(int width,int height,int color){document.createTexture(width,height,color);}
    public void setPaintBrush(int color,float radius,float opacity){document.setBrush(color,radius,opacity);}
    public void paintTexturePixel(int x,int y,int color,float radius,float opacity){
        document.setBrush(color,radius,opacity);document.paintPixel(x,y);
    }
    public int getUvIslandCount(){return document.getUvIslandCount();}
    public void addGeometryNode(String type,float x,float y,float z,float value){document.addGeometryNode(type,x,y,z,value);}
    public void evaluateGeometryNodes(){document.evaluateGeometryNodes();syncViewportFromCore();}
    public void addCompositorNode(String type,float value){document.addCompositorNode(type,value);}
    public int getCompositorNodeCount(){return document.getCompositorNodeCount();}
    public void addShaderNode(String type){document.addShaderNode(type);}
    public int getShaderNodeCount(){return document.getShaderNodeCount();}
    public void insertAnimationKey(String property,int frame,float value){document.insertKeyframe(property,frame,value);}
    public float evaluateAnimation(String property,float frame,float fallback){return document.evaluateAnimation(property,frame,fallback);}
    public int getAnimationKeyCount(){return document.getAnimationKeyCount();}
    public org.json.JSONObject exportCoreState(){syncCoreFromViewport();return document.exportState();}
    public boolean importCoreState(org.json.JSONObject state){
        if(!document.importState(state))return false;syncViewportFromCore();return true;
    }

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
    private float[] strokePlanePoint=new float[3],strokePlaneNormal=new float[]{0,0,1};
    private float currentStrokePressure=1f;
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
        if (showFloorGrid) {
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
        }

        if (!meshVisible) {
            drawNavigationGizmo(canvas, width, 18f*getResources().getDisplayMetrics().density);
            return;
        }
        float[][] p=new float[vertices.length][3];
        for(int i=0;i<vertices.length;i++) p[i]=project(vertices[i][0],vertices[i][1],vertices[i][2],cx,cy,unit);

        float[] viewportLight=cameraPoint(-.45f,.82f,.65f);
        boolean hasActiveTexture=document.hasTexture()&&document.hasFaceUvs();
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
            float centerZ=0f;for(int id:face)centerZ+=cameraPoint(vertices[id][0],vertices[id][1],vertices[id][2])[2];centerZ/=face.length;
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
            float[] ca=cameraPoint(vertices[face[0]][0],vertices[face[0]][1],vertices[face[0]][2]);
            float[] cb=cameraPoint(vertices[face[1]][0],vertices[face[1]][1],vertices[face[1]][2]);
            float[] cc=cameraPoint(vertices[face[2]][0],vertices[face[2]][1],vertices[face[2]][2]);
            float abx=cb[0]-ca[0],aby=cb[1]-ca[1],abz=cb[2]-ca[2];
            float acx=cc[0]-ca[0],acy=cc[1]-ca[1],acz=cc[2]-ca[2];
            float nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
            Path path=new Path();
            path.moveTo(p[face[0]][0],p[face[0]][1]);
            for(int k=1;k<face.length;k++) path.lineTo(p[face[k]][0],p[face[k]][1]);
            path.close();
            int side = Math.min(FACE_COLORS.length-1,f / (MESH_SUBDIVISIONS * MESH_SUBDIVISIONS));
            int base = FACE_COLORS[side];
            int shaded = document.isFaceSelected(f) ? 0xFFFFA52B : blendColor(base, meshTint, 0.48f);
            if(hasActiveTexture)shaded=blendColor(shaded,document.sampleFaceTextureColor(f),0.72f);
            if(!document.isFaceSelected(f))shaded=shadeViewportColor(shaded,nx,ny,nz,viewportLight);
            paint.setStyle(Paint.Style.FILL); paint.setColor(shaded); canvas.drawPath(path,paint);
            if (showTopology) {
                paint.setStyle(Paint.Style.STROKE); paint.setColor(0xAA303840);
                paint.setStrokeWidth(0.65f); canvas.drawPath(path,paint);
            }
        }
        if(meshEditMode){
            paint.setStyle(Paint.Style.STROKE);
            for(int i=0;i<document.getEdges().size();i++)if(document.isEdgeSelected(i)||document.isSeam(i)){
                Meshy3DCore.Edge e=document.getEdges().get(i);float[] a=p[e.a],b=p[e.b];
                boolean selected=document.isEdgeSelected(i);paint.setStrokeWidth(selected?2.4f:1.5f);
                paint.setColor(selected?0xFFFFA52B:0xFFE05252);canvas.drawLine(a[0],a[1],b[0],b[1],paint);
            }
            if(selectionMode==Meshy3DCore.SelectMode.VERTEX){
                paint.setStyle(Paint.Style.FILL);float density=getResources().getDisplayMetrics().density;
                for(int i=0;i<vertices.length;i++){
                    paint.setColor(document.isVertexSelected(i)?0xFFFFA52B:0xFFDBE2E9);
                    canvas.drawCircle(p[i][0],p[i][1],(document.isVertexSelected(i)?4.5f:2.1f)*density,paint);
                }
            }
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

    private int shadeViewportColor(int color,float nx,float ny,float nz,float[] light){
        float nl=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(nl<1e-6f)return color;
        nx/=nl;ny/=nl;nz/=nl;
        float ll=(float)Math.sqrt(light[0]*light[0]+light[1]*light[1]+light[2]*light[2]);if(ll<1e-6f)ll=1;
        float lx=light[0]/ll,ly=light[1]/ll,lz=light[2]/ll;
        float ndl=Math.max(0,nx*lx+ny*ly+nz*lz);
        float hx=lx,hy=ly,hz=lz+1f;float hl=(float)Math.sqrt(hx*hx+hy*hy+hz*hz);hx/=hl;hy/=hl;hz/=hl;
        float ndh=Math.max(0,nx*hx+ny*hy+nz*hz);
        float r=Math.max(.03f,roughness),a=r*r,a2=a*a;
        float den=ndh*ndh*(a2-1f)+1f;
        float spec=a2/(float)(Math.PI*den*den+1e-5f);
        float baseR=((color>>16)&255)/255f,baseG=((color>>8)&255)/255f,baseB=(color&255)/255f;
        float metal=Math.max(0,Math.min(1,metallic));
        float diffuse=.12f+(1f-metal)*ndl*.88f;
        float shine=spec*(.08f+.35f*(1f-r));
        int rr=(int)(Math.min(1f,baseR*diffuse+shine*(.04f*(1-metal)+baseR*metal))*255);
        int gg=(int)(Math.min(1f,baseG*diffuse+shine*(.04f*(1-metal)+baseG*metal))*255);
        int bb=(int)(Math.min(1f,baseB*diffuse+shine*(.04f*(1-metal)+baseB*metal))*255);
        return 0xFF000000|(rr<<16)|(gg<<8)|bb;
    }

    private static int blendColor(int a, int b, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int ar=(a>>16)&255, ag=(a>>8)&255, ab=a&255;
        int br=(b>>16)&255, bg=(b>>8)&255, bb=b&255;
        return 0xFF000000 | ((int)(ar+(br-ar)*t)<<16)
                | ((int)(ag+(bg-ag)*t)<<8) | (int)(ab+(bb-ab)*t);
    }

    public void resetView(){yaw=-0.62f;pitch=0.42f;zoom=1.0f;invalidate();}

    public void generatePlanarUv(){
        float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
        float minZ=Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(float[] v:vertices){minX=Math.min(minX,v[0]);maxX=Math.max(maxX,v[0]);
            minY=Math.min(minY,v[1]);maxY=Math.max(maxY,v[1]);minZ=Math.min(minZ,v[2]);maxZ=Math.max(maxZ,v[2]);}
        uvCoordinates=new float[vertices.length][2];
        float sx=Math.max(0.0001f,maxX-minX),sy=Math.max(0.0001f,maxY-minY),sz=Math.max(0.0001f,maxZ-minZ);
        for(int i=0;i<vertices.length;i++){
            float[] v=vertices[i];
            // Dominant extent chooses the projection plane for a stable initial UV layout.
            if(sx>=sy && sx>=sz){uvCoordinates[i][0]=(v[2]-minZ)/sz;uvCoordinates[i][1]=(v[1]-minY)/sy;}
            else if(sy>=sx && sy>=sz){uvCoordinates[i][0]=(v[0]-minX)/sx;uvCoordinates[i][1]=(v[2]-minZ)/sz;}
            else {uvCoordinates[i][0]=(v[0]-minX)/sx;uvCoordinates[i][1]=(v[1]-minY)/sy;}
        }
    }

    public String exportUvCsv(){
        float[][][] uv=document.copyFaceUvs();if(uv==null){document.unwrapPlanar();uv=document.copyFaceUvs();}
        StringBuilder out=new StringBuilder("face,corner,vertex,u,v\n");
        for(int fi=0;fi<uv.length;fi++){int[] ids=document.getFaceVertices(fi);
            for(int j=0;j<uv[fi].length;j++)out.append(fi).append(',').append(j).append(',')
                    .append(ids[j]).append(',').append(uv[fi][j][0]).append(',').append(uv[fi][j][1]).append('\n');
        }return out.toString();
    }

    public void storeMeshKeyframe(int frame){
        float[][] snapshot=new float[vertices.length][3];
        for(int i=0;i<vertices.length;i++)System.arraycopy(vertices[i],0,snapshot[i],0,3);
        meshKeyframes.put(Math.max(0,frame),snapshot);
    }
    public void deleteMeshKeyframe(int frame){meshKeyframes.remove(Math.max(0,frame));}
    public int getMeshKeyframeCount(){return meshKeyframes.size();}
    public java.util.ArrayList<Integer> getMeshKeyframeFrames(){
        java.util.ArrayList<Integer> frames=new java.util.ArrayList<>(meshKeyframes.keySet());
        java.util.Collections.sort(frames);return frames;
    }
    public void applyAnimationFrame(float frame){
        if(meshKeyframes.isEmpty())return;
        java.util.ArrayList<Integer> keys=getMeshKeyframeFrames();
        int before=keys.get(0),after=keys.get(keys.size()-1);
        for(int k:keys){if(k<=frame)before=k;if(k>=frame){after=k;break;}}
        float[][] a=meshKeyframes.get(before),b=meshKeyframes.get(after);
        if(a==null||b==null||a.length!=vertices.length||b.length!=vertices.length)return;
        float t=after==before?0f:Math.max(0f,Math.min(1f,(frame-before)/(float)(after-before)));
        t=t*t*(3f-2f*t);
        for(int i=0;i<vertices.length;i++)for(int axis=0;axis<3;axis++)
            vertices[i][axis]=a[i][axis]+(b[i][axis]-a[i][axis])*t;
        syncCoreFromViewport();invalidate();
    }


    /** Apply a geometric transform to every vertex of the active Meshy3D object. */
    public void transformMesh(int operation,float x,float y,float z){
        document.transformSelected(x,y,z,operation);syncViewportFromCore();
    }

    /** Laplacian relaxation over each cube-grid surface patch. */
    public void smoothMesh(float amount, int iterations) {
        float t=Math.max(0f,Math.min(1f,amount)); int n=MESH_SUBDIVISIONS+1;
        float[][] next=new float[vertices.length][3];
        for(int pass=0;pass<Math.max(1,Math.min(20,iterations));pass++){
            for(int side=0;side<6;side++) for(int v=0;v<n;v++) for(int u=0;u<n;u++){
                int index=side*n*n+v*n+u; float[] point=vertices[index];
                float sx=0,sy=0,sz=0; int count=0;
                int[] du={-1,1,0,0}, dv={0,0,-1,1};
                for(int k=0;k<4;k++){int uu=u+du[k],vv=v+dv[k];if(uu>=0&&uu<n&&vv>=0&&vv<n){
                    float[] q=vertices[side*n*n+vv*n+uu];sx+=q[0];sy+=q[1];sz+=q[2];count++;}}
                next[index][0]=count==0?point[0]:point[0]+(sx/count-point[0])*t;
                next[index][1]=count==0?point[1]:point[1]+(sy/count-point[1])*t;
                next[index][2]=count==0?point[2]:point[2]+(sz/count-point[2])*t;
            }
            for(int i=0;i<vertices.length;i++) System.arraycopy(next[i],0,vertices[i],0,3);
        }
        syncCoreFromViewport();
        invalidate();
    }

    public void extrudeMesh(float distance) {
        for(float[] v:vertices){float length=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
            if(length>0.0001f){v[0]+=v[0]/length*distance;v[1]+=v[1]/length*distance;v[2]+=v[2]/length*distance;}}
        syncCoreFromViewport();
        invalidate();
    }
    public void setMeshVisible(boolean visible){meshVisible=visible;invalidate();}
    public void toggleMeshVisible(){meshVisible=!meshVisible;invalidate();}
    public void setShowFloorGrid(boolean visible){showFloorGrid=visible;invalidate();}
    public void toggleFloorGrid(){showFloorGrid=!showFloorGrid;invalidate();}
    public void setShowTopology(boolean visible){showTopology=visible;invalidate();}
    public void toggleTopology(){showTopology=!showTopology;invalidate();}
    public void setMeshTint(int color){meshTint=color|0xFF000000;document.setMaterial(meshTint,metallic,roughness);invalidate();}
    public void setMaterialParameters(float metallicValue,float roughnessValue){
        metallic=Math.max(0f,Math.min(1f,metallicValue));roughness=Math.max(0.02f,Math.min(1f,roughnessValue));document.setMaterial(meshTint,metallic,roughness);invalidate();
    }
    public float getMetallic(){return metallic;}
    public float getRoughness(){return roughness;}
    public int getVertexCount(){return vertices.length;}
    public int getEdgeCount(){return document.getEdgeCount();}
    public int getFaceCount(){return faces.length;}
    public void resetMesh(){
        vertices=new float[originalVertices.length][3];
        for(int i=0;i<vertices.length;i++)System.arraycopy(originalVertices[i],0,vertices[i],0,3);
        faces=new int[originalFaces.length][];for(int i=0;i<faces.length;i++)faces[i]=originalFaces[i].clone();
        vertexMask=new float[vertices.length];meshVisible=true;showTopology=true;
        syncCoreFromViewport();invalidate();
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

    private void beginSculptStroke(float x,float y){
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.max(1,Math.min(getWidth(),getHeight())*.235f*zoom);
        float[][] normals=calculateVertexNormals();int best=-1;float bestDistance=Float.MAX_VALUE;
        for(int i=0;i<vertices.length;i++){
            float[] q=project(vertices[i][0],vertices[i][1],vertices[i][2],cx,cy,unit);
            float[] cn=cameraPoint(normals[i][0],normals[i][1],normals[i][2]);
            if(cn[2]<=0.015f)continue;
            float dx=q[0]-x,dy=q[1]-y,d=dx*dx+dy*dy;
            if(d<bestDistance){bestDistance=d;best=i;}
        }
        if(best>=0){
            System.arraycopy(vertices[best],0,strokePlanePoint,0,3);
            System.arraycopy(normals[best],0,strokePlaneNormal,0,3);
        }else{
            strokePlanePoint=new float[]{0,0,0};strokePlaneNormal=new float[]{0,0,1};
        }
    }
    private float[][] calculateVertexNormals(){
        float[][] normals=new float[vertices.length][3];
        for(int[] face:faces){
            if(face.length<3)continue;float[] a=vertices[face[0]];
            for(int j=1;j<face.length-1;j++){
                float[] b=vertices[face[j]],cc=vertices[face[j+1]];
                float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2];
                float vx=cc[0]-a[0],vy=cc[1]-a[1],vz=cc[2]-a[2];
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                for(int id:face){normals[id][0]+=nx;normals[id][1]+=ny;normals[id][2]+=nz;}
            }
        }
        for(float[] n:normals){float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(len>1e-8f){n[0]/=len;n[1]/=len;n[2]/=len;}}
        return normals;
    }
    private float[] cameraVectorToWorld(float x,float y,float z){
        double cp=Math.cos(pitch),sp=Math.sin(pitch),cy=Math.cos(yaw),sy=Math.sin(yaw);
        float ry=(float)(y*cp+z*sp),rz=(float)(-y*sp+z*cp);
        return new float[]{(float)(x*cy+rz*sy),ry,(float)(-x*sy+rz*cy)};
    }
    private void applySculptSegment(float x,float y,float dx,float dy,float pressure){
        float step=Math.max(2f,Math.min(getWidth(),getHeight())*sculptRadius*.55f*.22f);
        int count=Math.max(1,(int)Math.ceil(Math.hypot(dx,dy)/step));
        for(int i=1;i<=count;i++)applySculptDab(x-dx+dx*i/count,y-dy+dy*i/count,dx/count,dy/count,pressure);
    }
    private void applySculptDab(float x,float y,float dragX,float dragY,float eventPressure){
        if(vertices.length==0||getWidth()<=0||getHeight()<=0)return;
        float cx=getWidth()*.5f,cy=getHeight()*.49f;
        float unit=Math.max(1,Math.min(getWidth(),getHeight())*.235f*zoom);
        float brushPx=Math.max(4f,Math.min(getWidth(),getHeight())*sculptRadius*.55f);
        float worldRadius=brushPx/unit;
        float amount=sculptStrength*sculptPressure*Math.max(.05f,eventPressure)*worldRadius*.32f;
        float[][] normals=calculateVertexNormals();
        java.util.ArrayList<java.util.HashSet<Integer>> neighbors=new java.util.ArrayList<>();
        for(int i=0;i<vertices.length;i++)neighbors.add(new java.util.HashSet<>());
        for(int[] face:faces)for(int j=0;j<face.length;j++){
            int a=face[j],b=face[(j+1)%face.length];if(a>=0&&b>=0&&a<vertices.length&&b<vertices.length){neighbors.get(a).add(b);neighbors.get(b).add(a);}
        }
        float[] dragWorld=cameraVectorToWorld(dragX/unit,-dragY/unit,0);
        float sign=sculptSubtract?-1f:1f;
        for(int i=0;i<vertices.length;i++){
            float[] v=vertices[i],normal=normals[i];
            float[] screen=project(v[0],v[1],v[2],cx,cy,unit);
            float sx=screen[0],sy=screen[1];
            float dx=sx-x,dy=sy-y;
            if(sculptSymmetry==1){float[] m=project(-v[0],v[1],v[2],cx,cy,unit);dx=Math.min(Math.abs(dx),Math.abs(m[0]-x));dy=Math.min(Math.abs(dy),Math.abs(m[1]-y));}
            else if(sculptSymmetry==2){float[] m=project(v[0],-v[1],v[2],cx,cy,unit);dx=Math.min(Math.abs(dx),Math.abs(m[0]-x));dy=Math.min(Math.abs(dy),Math.abs(m[1]-y));}
            else if(sculptSymmetry==3){float[] m=project(v[0],v[1],-v[2],cx,cy,unit);dx=Math.min(Math.abs(dx),Math.abs(m[0]-x));dy=Math.min(Math.abs(dy),Math.abs(m[1]-y));}
            float distance=(float)Math.hypot(dx,dy);
            if(distance>brushPx)continue;
            float[] cameraNormal=cameraPoint(normal[0],normal[1],normal[2]);
            if(cameraNormal[2]<0.015f)continue;
            float t=Math.max(0,1-distance/brushPx);
            float falloff=(float)Math.pow(t,0.5f+sculptFalloff*2.5f);
            float mask=i<vertexMask.length?Math.max(0,Math.min(1,vertexMask[i])):0;
            float weight=falloff*(1-mask);
            if(weight<=0.0001f)continue;
            float strength=amount*weight;
            float nx=normal[0],ny=normal[1],nz=normal[2];
            float planeDistance=(v[0]-strokePlanePoint[0])*strokePlaneNormal[0]
                    +(v[1]-strokePlanePoint[1])*strokePlaneNormal[1]
                    +(v[2]-strokePlanePoint[2])*strokePlaneNormal[2];
            java.util.HashSet<Integer> adjacent=neighbors.get(i);
            float ax=0,ay=0,az=0;for(int id:adjacent){float[] q=vertices[id];ax+=q[0];ay+=q[1];az+=q[2];}
            if(!adjacent.isEmpty()){ax/=adjacent.size();ay/=adjacent.size();az/=adjacent.size();}
            switch(sculptBrush){
                case 0: // Draw: displacement along the averaged surface normal.
                    v[0]+=nx*strength*sign;v[1]+=ny*strength*sign;v[2]+=nz*strength*sign;break;
                case 1: // Smooth: local one-ring Laplacian relaxation.
                case 19: // Slide Relax: relax while preserving most normal displacement.
                case 24: // Simplify: stronger local relaxation.
                    if(!adjacent.isEmpty()){
                        float blend=(sculptBrush==24?.42f:sculptBrush==19?.18f:.30f)*weight*sculptStrength;
                        float ox=ax-v[0],oy=ay-v[1],oz=az-v[2];
                        if(sculptBrush==19){float dot=ox*nx+oy*ny+oz*nz;ox-=nx*dot;oy-=ny*dot;oz-=nz*dot;}
                        v[0]+=ox*blend;v[1]+=oy*blend;v[2]+=oz*blend;
                    }break;
                case 2: // Grab: drag the patch in the view plane.
                case 14: // Elastic Deform: softer, spring-like grab.
                case 15: // Snake Hook: drag plus normal extension.
                case 16: // Thumb: directional push and smear.
                case 17: // Pose: rotate the local patch around the stroke center.
                case 23: // Nudge: stroke-direction translation.
                    float factor=sculptBrush==14?.55f:sculptBrush==16?.65f:sculptBrush==23?.8f:1f;
                    if(sculptBrush==17){
                        float angle=(dragX-dragY)*.003f*sculptStrength*weight;
                        float ox=v[0]-strokePlanePoint[0],oy=v[1]-strokePlanePoint[1],oz=v[2]-strokePlanePoint[2];
                        float co=(float)Math.cos(angle),si=(float)Math.sin(angle);
                        v[0]=strokePlanePoint[0]+ox*co-oy*si;v[1]=strokePlanePoint[1]+ox*si+oy*co;
                    }else{
                        v[0]+=dragWorld[0]*factor*weight*sculptStrength;
                        v[1]+=dragWorld[1]*factor*weight*sculptStrength;
                        v[2]+=dragWorld[2]*factor*weight*sculptStrength;
                        if(sculptBrush==15){v[0]+=nx*strength*.9f;v[1]+=ny*strength*.9f;v[2]+=nz*strength*.9f;}
                        if(sculptBrush==16&& !adjacent.isEmpty()){v[0]+=(ax-v[0])*.08f*weight;v[1]+=(ay-v[1])*.08f*weight;v[2]+=(az-v[2])*.08f*weight;}
                    }break;
                case 3: // Inflate: normal displacement with a slightly expanded response.
                    v[0]+=nx*strength*1.25f*sign;v[1]+=ny*strength*1.25f*sign;v[2]+=nz*strength*1.25f*sign;break;
                case 4: // Pinch: move toward the projected stroke center.
                    float[] toward=cameraVectorToWorld(-dx/unit,dy/unit,0);
                    v[0]+=toward[0]*strength*1.8f;v[1]+=toward[1]*strength*1.8f;v[2]+=toward[2]*strength*1.8f;break;
                case 5: // Flatten: converge to the stroke-start plane from either side.
                case 9: // Scrape: only remove material above the plane.
                case 20: // Multiplane scrape: stronger plane projection.
                    if(sculptBrush==5||planeDistance>0){
                        float f=(sculptBrush==20?.85f:.55f)*weight*sculptStrength;
                        v[0]-=strokePlaneNormal[0]*planeDistance*f;
                        v[1]-=strokePlaneNormal[1]*planeDistance*f;
                        v[2]-=strokePlaneNormal[2]*planeDistance*f;
                    }break;
                case 6: // Crease: narrow negative groove plus pinching.
                case 22: // Deepen: stronger groove.
                    float crease=(sculptBrush==22?1.7f:1f)*strength*sign;
                    v[0]-=nx*crease;v[1]-=ny*crease;v[2]-=nz*crease;
                    float pinch=(float)Math.hypot(dx,dy)/brushPx;
                    float pin=(1f-pinch)*strength*.35f;
                    v[0]+=(strokePlanePoint[0]-v[0])*pin;v[1]+=(strokePlanePoint[1]-v[1])*pin;v[2]+=(strokePlanePoint[2]-v[2])*pin;break;
                case 7: // Mask.
                    vertexMask[i]=Math.min(1f,mask+weight*sculptStrength*.75f);break;
                case 8: // Clay: build volume while gently flattening toward plane.
                    v[0]+=nx*strength*.78f*sign-strokePlaneNormal[0]*planeDistance*weight*.10f;
                    v[1]+=ny*strength*.78f*sign-strokePlaneNormal[1]*planeDistance*weight*.10f;
                    v[2]+=nz*strength*.78f*sign-strokePlaneNormal[2]*planeDistance*weight*.10f;break;
                case 10: // Layer: fixed plane-height accumulation.
                    float layer=Math.max(-worldRadius*.45f,Math.min(worldRadius*.45f,planeDistance));
                    float layerMove=(worldRadius*.18f-layer)*weight*sculptStrength*sign;
                    v[0]+=nx*layerMove;v[1]+=ny*layerMove;v[2]+=nz*layerMove;break;
                case 11: // Unmask.
                    vertexMask[i]=Math.max(0,mask-weight*sculptStrength*.85f);break;
                case 12: // Blob: rounded radial volume.
                    float blob=(float)Math.sqrt(Math.max(0,1-(distance/brushPx)*(distance/brushPx)));
                    v[0]+=nx*strength*blob*1.65f*sign;v[1]+=ny*strength*blob*1.65f*sign;v[2]+=nz*strength*blob*1.65f*sign;break;
                case 13: // Clay Strips: square footprint with broad buildup.
                    float square=Math.max(Math.abs(dx),Math.abs(dy))/brushPx;
                    float stripWeight=(float)Math.pow(Math.max(0,1-square),1.2f)*(1-mask);
                    v[0]+=nx*amount*stripWeight*1.15f*sign;v[1]+=ny*amount*stripWeight*1.15f*sign;v[2]+=nz*amount*stripWeight*1.15f*sign;break;
                case 18: // Boundary: emphasize the visible silhouette/edge region.
                    float silhouette=1f-Math.min(1f,Math.abs(cameraNormal[2]));
                    v[0]+=nx*strength*silhouette*.7f*sign;v[1]+=ny*strength*silhouette*.7f*sign;v[2]+=nz*strength*silhouette*.7f*sign;break;
                case 21: // Fill: push only vertices below the stroke plane toward it.
                    if(planeDistance<0){float f=Math.min(-planeDistance,strength)*weight;v[0]+=strokePlaneNormal[0]*f;v[1]+=strokePlaneNormal[1]*f;v[2]+=strokePlaneNormal[2]*f;}break;
                default: break;
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
                    beginSculptStroke(strokeX,strokeY);
                    applySculptDab(strokeX,strokeY,0f,0f,event.getPressure());
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastPinchDistance=pointerDistance(event); moved=true; return true;
            case MotionEvent.ACTION_MOVE:
                if(sculpting && strokeActive && event.getPointerCount()==1){
                    float sx=event.getX(), sy=event.getY();
                    applySculptSegment(sx,sy,sx-previousStrokeX,sy-previousStrokeY,event.getPressure());
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
                if(sculpting){syncCoreFromViewport();}
                if(!moved && !sculpting){
                    float gx=getWidth()-190*d, gy=150*d, radius=35*d;
                    float dx=event.getX()-gx,dy=event.getY()-gy;
                    if(dx*dx+dy*dy<=radius*radius){
                        snapToNearestAxis(dx,dy);
                    } else if(meshEditMode){selectAt(event.getX(),event.getY(),(event.getMetaState()&android.view.KeyEvent.META_SHIFT_ON)!=0);}
                    else if(onViewportTap!=null) onViewportTap.run();
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
            syncCoreFromViewport();
            org.json.JSONObject state = new org.json.JSONObject();
            state.put("core",document.exportState());
            int[] texture=document.getTexturePixels();
            if(texture!=null){
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(texture,
                        document.getTextureWidth(),document.getTextureHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);image.recycle();
                state.put("texturePng",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));
                state.put("textureWidth",document.getTextureWidth());state.put("textureHeight",document.getTextureHeight());
            }
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
            state.put("meshTint", meshTint); state.put("metallic", metallic); state.put("roughness", roughness);
            state.put("meshVisible", meshVisible); state.put("showFloorGrid", showFloorGrid);
            state.put("showTopology", showTopology);
            if (uvCoordinates != null) {
                org.json.JSONArray uv = new org.json.JSONArray();
                for (float[] pair : uvCoordinates) {
                    org.json.JSONArray p = new org.json.JSONArray(); p.put(pair[0]); p.put(pair[1]); uv.put(p);
                }
                state.put("uv", uv);
            }
            org.json.JSONArray keyframes = new org.json.JSONArray();
            for (java.util.Map.Entry<Integer,float[][]> entry : meshKeyframes.entrySet()) {
                org.json.JSONObject frame = new org.json.JSONObject();
                frame.put("frame", entry.getKey());
                org.json.JSONArray points = new org.json.JSONArray();
                for (float[] point : entry.getValue()) {
                    org.json.JSONArray p = new org.json.JSONArray();
                    p.put(point[0]); p.put(point[1]); p.put(point[2]); points.put(p);
                }
                frame.put("vertices", points); keyframes.put(frame);
            }
            state.put("keyframes", keyframes);
            return state;
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not serialize Meshy3D mesh", error);
        }
    }

    /** Restore mesh coordinates only when the saved topology matches this editor build. */
    public boolean importDocumentState(org.json.JSONObject state) {
        if (state == null) return false;
        try {
            org.json.JSONObject coreState=state.optJSONObject("core");
            if(coreState!=null){
                if(!document.importState(coreState))return false;
                vertices=document.copyVertices();faces=document.copyFaces();vertexMask=new float[vertices.length];
                String png=state.optString("texturePng","");
                if(!png.isEmpty()){
                    byte[] data=android.util.Base64.decode(png,android.util.Base64.DEFAULT);
                    android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeByteArray(data,0,data.length);
                    if(image!=null){
                        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];
                        image.getPixels(pixels,0,w,0,0,w,h);image.recycle();document.setTexturePixels(pixels,w,h);
                    }
                }
            }
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
            meshTint = state.optInt("meshTint", meshTint);
            metallic = (float) state.optDouble("metallic", metallic);
            roughness = (float) state.optDouble("roughness", roughness);
            meshVisible = state.optBoolean("meshVisible", meshVisible);
            showFloorGrid = state.optBoolean("showFloorGrid", showFloorGrid);
            showTopology = state.optBoolean("showTopology", showTopology);
            org.json.JSONArray uv = state.optJSONArray("uv");
            if (uv != null && uv.length() == vertices.length) {
                float[][] restored = new float[vertices.length][2];
                for (int i=0;i<uv.length();i++) {
                    org.json.JSONArray pair=uv.getJSONArray(i);
                    restored[i][0]=(float)pair.getDouble(0); restored[i][1]=(float)pair.getDouble(1);
                }
                uvCoordinates=restored;
            }
            meshKeyframes.clear();
            org.json.JSONArray frames=state.optJSONArray("keyframes");
            if(frames!=null)for(int i=0;i<frames.length();i++){
                org.json.JSONObject frame=frames.getJSONObject(i);
                org.json.JSONArray points=frame.getJSONArray("vertices");
                if(points.length()!=vertices.length)continue;
                float[][] snapshot=new float[vertices.length][3];
                for(int j=0;j<points.length();j++){
                    org.json.JSONArray point=points.getJSONArray(j);
                    for(int axis=0;axis<3;axis++)snapshot[j][axis]=(float)point.getDouble(axis);
                }
                meshKeyframes.put(frame.optInt("frame",i),snapshot);
            }
            if(coreState==null)syncCoreFromViewport();
            invalidate();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override public boolean performClick(){super.performClick();return true;}
}
