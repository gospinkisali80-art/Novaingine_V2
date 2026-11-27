package com.novaingine.v2;

import java.util.ArrayList;

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
    private float viewRoll;
    private float lastX, lastY, lastPinchDistance, lastPinchCenterX, lastPinchCenterY;
    private float zoom = 1.0f;
    // Adaptive viewport-camera distance prevents large scaled meshes from crossing the near plane.
    private float projectionDistance = 5.2f;
    private float viewTargetX,viewTargetY,viewTargetZ;
    private boolean cameraViewActive;
    private int viewportNavigationMode = 0; // 0 = orbit, 1 = pan
    private boolean measureMode;
    private boolean measurementHasStart;
    private boolean measurementHasEnd;
    private float measurementStartX, measurementStartY, measurementEndX, measurementEndY;
    private float measurementDistance;
    private float savedViewYaw,savedViewPitch,savedViewRoll,savedViewZoom,savedTargetX,savedTargetY,savedTargetZ;
    // Each cube side is subdivided into a 24 x 24 quad grid.
    // Six independent side grids give 3,750 editable vertices and 3,456 quads.
    // Matching border vertices receive the same screen-space brush dabs.
    private static final int MESH_SUBDIVISIONS = 24;
    private Meshy3DCore document = new Meshy3DCore();
    private final Meshy3DObjectScene objectScene = new Meshy3DObjectScene();
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
    private final java.util.TreeMap<Integer, java.util.HashMap<String, Meshy3DObjectScene.Transform>>
            sceneTransformKeyframes = new java.util.TreeMap<>();
    private int animationInterpolationMode = 0; // 0 smooth, 1 linear, 2 constant


    private String objectSceneActiveId;
    private boolean objectSelectionEnabled;
    private boolean localViewActive;
    private final java.util.HashSet<String> localViewObjectIds=new java.util.HashSet<>();
    private Runnable onObjectSelectionChanged;
    private String modifierEvaluationWarning;
    private Runnable onObjectTransformStart;
    private Runnable onSculptStrokeStart;
    private int objectTransformTool=-1;
    private int objectPivotMode=0;
    private boolean objectTransformLocalOrientation;
    private boolean objectGizmoDragging;
    private boolean objectGizmoRotationRing;
    private boolean objectGizmoUniformScale;
    private boolean objectBoxSelectEnabled;
    private boolean objectBoxSelecting;
    private float objectBoxEndX,objectBoxEndY;
    private int objectGizmoAxis=-1;
    private float gizmoStartX,gizmoStartY,gizmoAxisScreenX,gizmoAxisScreenY,gizmoStartWorldX;
    private final java.util.ArrayList<String> gizmoObjectIds=new java.util.ArrayList<>();
    private final java.util.ArrayList<Meshy3DObjectScene.Transform> gizmoInitialTransforms=new java.util.ArrayList<>();
    private final java.util.ArrayList<float[]> gizmoInitialWorldLocations=new java.util.ArrayList<>();
    private final java.util.ArrayList<double[]> gizmoInitialWorldMatrices=new java.util.ArrayList<>();

    private static final class RenderData {
        float[][] vertices;
        int[][] faces;
        boolean[] activeFaces;
        boolean[] wireFaces;
        int[] displayColors;
    }

    private static final class SceneRenderData {
        float[][] vertices;
        int[][] faces;
        int[] faceMaterialIds;
        int[] materialColors;
        float[] materialMetallic;
        float[] materialRoughness;
        int[][] materialTextures;
        int[] textureWidths;
        int[] textureHeights;
        float[][] faceLightDirections;
        float[][] faceLightTints;
        float[][][] faceUvs;
    }

    public Meshy3DObjectScene getObjectScene(){return objectScene;}
    public Meshy3DCore getDocumentCore(){return document;}
    public void setObjectSelectionListener(Runnable listener){onObjectSelectionChanged=listener;}
    public void setObjectTransformStartListener(Runnable listener){onObjectTransformStart=listener;}
    public void setSculptStrokeStartListener(Runnable listener){onSculptStrokeStart=listener;}
    public void setObjectTransformTool(int mode){
        objectTransformTool=mode<0||mode>2?-1:mode;
        objectGizmoDragging=false;objectGizmoAxis=-1;invalidate();
    }
    public int getObjectTransformTool(){return objectTransformTool;}
    public void setObjectPivotMode(int mode){objectPivotMode=Math.max(0,Math.min(3,mode));invalidate();}
    public int getObjectPivotMode(){return objectPivotMode;}
    public void setObjectTransformLocalOrientation(boolean local){objectTransformLocalOrientation=local;invalidate();}
    public boolean isObjectTransformLocalOrientation(){return objectTransformLocalOrientation;}

    private float[] gizmoAxisDirection(Meshy3DObjectScene.Object3D active,int axis) {
        float[][] axes={{1,0,0},{0,1,0},{0,0,1}};
        if(!objectTransformLocalOrientation)return axes[axis].clone();
        float[] origin=objectScene.transformPoint(active.id,0,0,0);
        float[] endpoint=objectScene.transformPoint(active.id,axes[axis][0],axes[axis][1],axes[axis][2]);
        float dx=endpoint[0]-origin[0],dy=endpoint[1]-origin[1],dz=endpoint[2]-origin[2];
        float length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(length<1e-6f)return axes[axis].clone();
        return new float[]{dx/length,dy/length,dz/length};
    }

    private float[] getObjectTransformPivot() {
        if(objectPivotMode==2)return objectScene.getCursorLocation();
        Meshy3DObjectScene.Object3D active=objectScene.getActiveObject();
        if(objectPivotMode==1&&active!=null)return objectScene.worldLocation(active.id);
        java.util.List<Meshy3DObjectScene.Object3D> selected=objectScene.getSelectedObjects();
        if(selected.isEmpty()&&active!=null)return objectScene.worldLocation(active.id);
        float x=0,y=0,z=0;int count=0;
        for(Meshy3DObjectScene.Object3D object:selected){
            float[] p=objectScene.worldLocation(object.id);x+=p[0];y+=p[1];z+=p[2];count++;
        }
        if(count==0)return new float[]{0,0,0};
        return new float[]{x/count,y/count,z/count};
    }

    private static double[] identityMatrix4() {
        return new double[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }
    private static double[] multiplyMatrix4(double[] a,double[] b) {
        double[] out=new double[16];
        for(int row=0;row<4;row++)for(int col=0;col<4;col++)
            for(int k=0;k<4;k++)out[row*4+col]+=a[row*4+k]*b[k*4+col];
        return out;
    }
    private static double[] translationMatrix4(double x,double y,double z) {
        double[] m=identityMatrix4();m[3]=x;m[7]=y;m[11]=z;return m;
    }
    private static double[] rotationAroundAxisMatrix(float[] axis,double radians) {
        double x=axis[0],y=axis[1],z=axis[2],c=Math.cos(radians),s=Math.sin(radians),t=1-c;
        double[] m=identityMatrix4();
        m[0]=t*x*x+c;m[1]=t*x*y-s*z;m[2]=t*x*z+s*y;
        m[4]=t*x*y+s*z;m[5]=t*y*y+c;m[6]=t*y*z-s*x;
        m[8]=t*x*z-s*y;m[9]=t*y*z+s*x;m[10]=t*z*z+c;
        return m;
    }
    private static double[] uniformScaleMatrix(double factor) {
        double[] matrix=identityMatrix4();
        matrix[0]=factor;matrix[5]=factor;matrix[10]=factor;
        return matrix;
    }
    private static double[] scaleAlongAxisMatrix(float[] axis,double factor) {
        double[] m=identityMatrix4();double f=factor-1.0;
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)m[row*4+col]+=
                f*axis[row]*axis[col];
        return m;
    }
    private double[] pivotedWorldDelta(double[] delta) {
        float[] p=getObjectTransformPivot();
        return multiplyMatrix4(translationMatrix4(p[0],p[1],p[2]),
                multiplyMatrix4(delta,translationMatrix4(-p[0],-p[1],-p[2])));
    }

    public void setObjectBoxSelectEnabled(boolean enabled){
        objectBoxSelectEnabled=enabled;objectBoxSelecting=false;
        if(enabled)objectTransformTool=-1;invalidate();
    }

    private Meshy3DCore copyMeshCore(Meshy3DCore source) {
        Meshy3DCore copy=new Meshy3DCore();
        if(!copy.importState(source.exportState()))throw new IllegalStateException("Could not copy object mesh");
        return copy;
    }

    private void activateSceneObject(String id) { activateSceneObject(id,true); }

    private void activateSceneObject(String id,boolean exclusiveSelection) {
        Meshy3DObjectScene.Object3D target=objectScene.getObject(id);
        if(target==null)return;
        if(target.mesh!=null){
            // The viewport edits the object's actual mesh datablock. Keeping this
            // reference intact is what makes linked duplicates share real geometry.
            document=target.mesh;
            syncViewportFromCore();
        } else {
            // Non-mesh objects have no mesh datablock; retain the last mesh datablock
            // for other objects in the scene without assigning it to this object.
            meshVisible=false;
        }
        if(exclusiveSelection)objectScene.selectOnly(id);else objectScene.setActiveObject(id);
        objectSceneActiveId=id;
        meshVisible=target.mesh!=null&&target.visible;
        invalidate();
    }


    /** Add AI-generated indexed geometry to the Meshy3D document, not the Nova Engine runtime scene. */
    public String addAIMeshObject(String name,float[] positions,int[] triangles,
                                  float x,float y,float z,int color,float metallic,float roughness) {
        if(positions==null||triangles==null||positions.length<9||positions.length>9000
                ||positions.length%3!=0||triangles.length<3||triangles.length>18000||triangles.length%3!=0)
            throw new IllegalArgumentException("AI mesh exceeds the supported vertex/triangle limits.");
        int vertexCount=positions.length/3;
        org.json.JSONObject state=new org.json.JSONObject();
        org.json.JSONArray verts=new org.json.JSONArray(),polys=new org.json.JSONArray();
        try {
            for(int i=0;i<vertexCount;i++){
                org.json.JSONArray point=new org.json.JSONArray();
                point.put(positions[i*3]);point.put(positions[i*3+1]);point.put(positions[i*3+2]);verts.put(point);
            }
            for(int i=0;i<triangles.length;i+=3){
                org.json.JSONArray face=new org.json.JSONArray();
                for(int j=0;j<3;j++){
                    int index=triangles[i+j];
                    if(index<0||index>=vertexCount)throw new IllegalArgumentException("AI mesh triangle index is invalid.");
                    face.put(index);
                }
                polys.put(face);
            }
            state.put("vertices",verts);state.put("faces",polys);
        } catch(org.json.JSONException error) {
            throw new IllegalStateException("Could not assemble AI mesh data.",error);
        }
        Meshy3DCore mesh=new Meshy3DCore();
        if(!mesh.importState(state))throw new IllegalArgumentException("Meshy3D rejected the generated mesh topology.");
        mesh.setMaterial(color,metallic,roughness);
        Meshy3DObjectScene.Object3D created=objectScene.createMeshObject(
                name==null||name.trim().isEmpty()?"AI Mesh":name.trim(),mesh);
        objectScene.setWorldLocation(created.id,x,y,z);
        activateSceneObject(created.id);
        meshEditMode=false;
        objectSelectionEnabled=true;
        invalidate();
        return created.id;
    }

    public String addCubeObject() {
        return addPrimitiveObject("Cube");
    }

    /**
     * Add a primitive at the 3D Cursor. In Object Mode it becomes a new object;
     * in Edit Mode it is appended to the active mesh datablock.
     */
    public String addPrimitiveObject(String primitive) {
        syncCoreFromViewport();
        Meshy3DCore primitiveMesh=Meshy3DPrimitiveFactory.create(primitive);
        float[] cursor=objectScene.getCursorLocation();
        if(meshEditMode&&objectSceneActiveId!=null) {
            Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
            if(active!=null&&active.mesh!=null&&"MESH".equals(active.type)) {
                float[] localCursor=objectScene.inverseTransformPoint(
                        active.id,cursor[0],cursor[1],cursor[2]);
                document.appendGeometry(primitiveMesh.copyVertices(),primitiveMesh.copyFaces(),
                        localCursor[0],localCursor[1],localCursor[2]);
                syncViewportFromCore();
                meshEditMode=true;
                objectSelectionEnabled=false;
                invalidate();
                return active.id;
            }
        }
        Meshy3DObjectScene.Object3D created=objectScene.createMeshObject(primitive,primitiveMesh);
        objectScene.setWorldLocation(created.id,cursor[0],cursor[1],cursor[2]);
        activateSceneObject(created.id);
        meshEditMode=false;objectSelectionEnabled=true;
        return created.id;
    }

    public int importMeshyMeshes(java.util.List<Meshy3DGlbImporter.ImportedMesh> imported) {
        if (imported == null || imported.isEmpty()) return 0;
        syncCoreFromViewport();
        String lastId = null;
        int count = 0;
        for (Meshy3DGlbImporter.ImportedMesh item : imported) {
            if (item == null || item.mesh == null) continue;
            Meshy3DObjectScene.Object3D created = objectScene.createMeshObject(item.name, item.mesh);
            lastId = created.id;
            count++;
        }
        if (lastId != null) {
            activateSceneObject(lastId);
            meshEditMode = false;
            objectSelectionEnabled = true;
            invalidate();
        }
        return count;
    }

    public String duplicateActiveObject(boolean linked) {
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active==null)return null;
        syncCoreFromViewport();
        java.util.List<Meshy3DObjectScene.Object3D> copies=objectScene.duplicateSelected(linked);
        if(copies.isEmpty())return null;
        Meshy3DObjectScene.Object3D copy=copies.get(copies.size()-1);
        activateSceneObject(copy.id);
        return copy.id;
    }

    public String addCameraObject() {
        Meshy3DObjectScene.Object3D camera=objectScene.createTypedObject("Camera","CAMERA",null);
        camera.displaySize=.9f;
        camera.transform.x=7f;camera.transform.y=5f;camera.transform.z=7f;
        camera.transform.rotationX=(float)Math.toRadians(22.0);
        camera.transform.rotationY=(float)Math.toRadians(45.0);
        objectScene.setActiveCamera(camera.id);activateSceneObject(camera.id);return camera.id;
    }

    public String addLightObject() {
        Meshy3DObjectScene.Object3D light=objectScene.createTypedObject("Point Light","LIGHT",null);
        light.displaySize=.7f;light.transform.x=-3.5f;light.transform.y=5f;light.transform.z=3f;
        light.lightType="POINT";light.lightPower=1000f;light.lightColor=0xFFFFFFFF;
        activateSceneObject(light.id);return light.id;
    }

    public String addEmptyObject() {
        Meshy3DObjectScene.Object3D empty=objectScene.createTypedObject("Empty","EMPTY",null);
        empty.displaySize=.65f;activateSceneObject(empty.id);return empty.id;
    }

    public void makeActiveCamera() {
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active==null)throw new IllegalStateException("Select a Camera object first");
        objectScene.setActiveCamera(active.id);invalidate();
    }
    public void setActiveCameraSettings(float lens,float clipStart,float clipEnd){
        if(objectSceneActiveId==null)throw new IllegalStateException("Select a Camera object first");
        objectScene.setCameraSettings(objectSceneActiveId,lens,clipStart,clipEnd);invalidate();
    }
    public void setActiveLightSettings(String type,float power,float angle,int color){
        if(objectSceneActiveId==null)throw new IllegalStateException("Select a Light object first");
        objectScene.setLightSettings(objectSceneActiveId,type,power,angle,color);invalidate();
    }

    public void toggleLocalView() {
        if(localViewActive){localViewActive=false;localViewObjectIds.clear();invalidate();return;}
        localViewObjectIds.clear();
        for(Meshy3DObjectScene.Object3D o:objectScene.getSelectedObjects())localViewObjectIds.add(o.id);
        if(localViewObjectIds.isEmpty()&&objectSceneActiveId!=null)localViewObjectIds.add(objectSceneActiveId);
        if(localViewObjectIds.isEmpty())return;
        localViewActive=true;invalidate();
    }

    public boolean isLocalViewActive(){return localViewActive;}

    public void hideSelectedObjects(){objectScene.hideSelected();invalidate();}
    public void hideUnselectedObjects(){objectScene.hideUnselected();invalidate();}
    public void showAllObjects(){objectScene.showAllObjects();localViewActive=false;localViewObjectIds.clear();invalidate();}
    public void invertHiddenObjects(){objectScene.invertHiddenObjects();invalidate();}
    public void setSelectedObjectsSelectable(boolean selectable){objectScene.setSelectedSelectable(selectable);invalidate();}
    public void setSelectedObjectsRenderVisible(boolean visible){objectScene.setSelectedRenderVisible(visible);invalidate();}

    public void selectObjectsByType(String type,boolean extend){
        enterObjectSelectionMode();
        objectScene.selectByType(type,extend);activateCurrentObjectWithoutClearing();
    }
    public void selectActiveCameraObject(boolean extend){
        enterObjectSelectionMode();
        objectScene.selectActiveCamera(extend);activateCurrentObjectWithoutClearing();
    }
    public void selectLinkedDataObjects(boolean extend){
        objectScene.selectLinkedData(extend);activateCurrentObjectWithoutClearing();
    }
    public void selectChildren(boolean recursive,boolean extend){
        objectScene.selectChildren(recursive,extend);activateCurrentObjectWithoutClearing();
    }
    public void selectParent(boolean extend){
        objectScene.selectParent(extend);activateCurrentObjectWithoutClearing();
    }
    public void selectSiblings(boolean extend){
        objectScene.selectSiblings(extend);activateCurrentObjectWithoutClearing();
    }
    public void selectSameCollection(boolean extend){
        objectScene.selectSameCollection(extend);activateCurrentObjectWithoutClearing();
    }
    public void selectObjectPattern(String pattern,boolean extend,boolean caseSensitive){
        objectScene.selectPattern(pattern,extend,caseSensitive);activateCurrentObjectWithoutClearing();
    }
    private void activateCurrentObjectWithoutClearing(){
        String id=objectScene.getActiveObjectId();
        if(id!=null)activateSceneObject(id,false);else {objectSceneActiveId=null;invalidate();}
    }

    public void parentSelectedToActive(){
        objectScene.parentSelectedToActive();invalidate();
    }
    public void alignSelectedObjectsToActive(int axis){
        objectScene.alignSelectedLocationsToActive(axis);invalidate();
    }

    public void mirrorSelectedObjects(int axis,boolean localOrientation) {
        if(axis<0||axis>2)throw new IllegalArgumentException("Mirror axis must be X, Y or Z");
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=
                new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty()&&objectSceneActiveId!=null)targets.add(objectScene.getObject(objectSceneActiveId));
        if(targets.isEmpty())throw new IllegalStateException("Select at least one object to mirror");
        if(localOrientation) {
            targets.sort((a,b)->Integer.compare(objectScene.getObjectHierarchyDepth(a.id),objectScene.getObjectHierarchyDepth(b.id)));
            for(Meshy3DObjectScene.Object3D object:targets) {
                Meshy3DObjectScene.Transform transform=object.transform.copy();
                if(axis==0)transform.scaleX=-transform.scaleX;
                else if(axis==1)transform.scaleY=-transform.scaleY;
                else transform.scaleZ=-transform.scaleZ;
                objectScene.setTransform(object.id,transform);
            }
        } else {
            float[] median=getObjectTransformPivot();
            java.util.HashMap<String,double[]> matrices=new java.util.HashMap<>();
            for(Meshy3DObjectScene.Object3D object:targets)
                matrices.put(object.id,objectScene.getWorldMatrixCopy(object.id));
            targets.sort((a,b)->Integer.compare(objectScene.getObjectHierarchyDepth(a.id),objectScene.getObjectHierarchyDepth(b.id)));
            for(Meshy3DObjectScene.Object3D object:targets) {
                float[] pivot=objectPivotMode==3?objectScene.worldLocation(object.id):median;
                double[] mirror=identityMatrix4();
                mirror[axis*4+axis]=-1.0;
                mirror[axis*4+3]=2.0*pivot[axis];
                double[] original=matrices.get(object.id);
                objectScene.setWorldMatrix(object.id,multiplyMatrix4(mirror,original));
            }
        }
        invalidate();
    }
    public void clearSelectedParents(){
        objectScene.clearSelectedParents();invalidate();
    }
    public void joinSelectedObjects(){
        syncCoreFromViewport();
        Meshy3DObjectScene.Object3D joined=objectScene.joinSelectedMeshes();
        objectSceneActiveId=joined.id;
        activateSceneObject(joined.id);
    }
    public void moveSelectedToCollection(String collectionId){
        for(Meshy3DObjectScene.Object3D o:objectScene.getSelectedObjects())objectScene.moveToCollection(o.id,collectionId);
        invalidate();
    }
    public void linkSelectedToCollection(String collectionId){
        for(Meshy3DObjectScene.Object3D o:objectScene.getSelectedObjects())objectScene.linkToCollection(o.id,collectionId);
        invalidate();
    }
    public void linkSelectedObjectsToActiveMeshData(){
        syncCoreFromViewport();objectScene.linkSelectedToActiveMeshData();
        invalidate();
    }
    public void selectCollectionObjects(String collectionId,boolean extend){
        enterObjectSelectionMode();
        objectScene.selectCollection(collectionId,extend);activateCurrentObjectWithoutClearing();
    }
    public void setCollectionViewportVisibility(String collectionId,boolean visible){
        objectScene.setCollectionVisibility(collectionId,visible);invalidate();
    }
    public void setCollectionRenderVisibility(String collectionId,boolean visible){
        objectScene.setCollectionRenderVisibility(collectionId,visible);invalidate();
    }
    public void deleteSceneCollection(String collectionId){
        objectScene.deleteCollection(collectionId);invalidate();
    }
    public void makeActiveObjectSingleUser(){
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active==null||active.mesh==null)return;
        syncCoreFromViewport();
        Meshy3DCore copy=copyMeshCore(active.mesh);
        active.mesh=copy;
        document=copy;
        syncViewportFromCore();
        invalidate();
    }

    private void enterObjectSelectionMode() {
        if(meshEditMode)syncCoreFromViewport();
        meshEditMode=false;sculpting=false;strokeActive=false;
        objectSelectionEnabled=true;objectTransformTool=-1;objectBoxSelectEnabled=false;
    }
    public void selectSceneObject(String id) { enterObjectSelectionMode();activateSceneObject(id); }
    public void selectSceneObject(String id,boolean additive) {
        enterObjectSelectionMode();activateSceneObject(id,!additive);
    }
    public void setObjectSelectionEnabled(boolean enabled){objectSelectionEnabled=enabled;}
    private void performObjectBoxSelection(float x0,float y0,float x1,float y1) {
        float left=Math.min(x0,x1),right=Math.max(x0,x1),top=Math.min(y0,y1),bottom=Math.max(y0,y1);
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.min(getWidth(),getHeight())*.235f*zoom;
        ArrayList<Meshy3DObjectScene.Object3D> hits=new ArrayList<>();
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()){
            if(!object.selectable||!objectScene.isVisibleInViewport(object)||!object.renderVisible
                    ||(localViewActive&&!localViewObjectIds.contains(object.id)))continue;
            boolean hit=false;
            if(object.mesh==null){
                float[] world=objectScene.transformPoint(object.id,0,0,0);
                float[] screen=project(world[0],world[1],world[2],cx,cy,unit);
                float radius=Math.max(12f,object.displaySize*12f*getResources().getDisplayMetrics().density);
                hit=screen[0]+radius>=left&&screen[0]-radius<=right&&screen[1]+radius>=top&&screen[1]-radius<=bottom;
            }else{
                float[][] mesh=object.mesh.copyVertices();
                float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
                for(float[] vertex:mesh){
                    float[] world=objectScene.transformPoint(object.id,vertex[0],vertex[1],vertex[2]);
                    float[] screen=project(world[0],world[1],world[2],cx,cy,unit);
                    minX=Math.min(minX,screen[0]);minY=Math.min(minY,screen[1]);
                    maxX=Math.max(maxX,screen[0]);maxY=Math.max(maxY,screen[1]);
                    if(screen[0]>=left&&screen[0]<=right&&screen[1]>=top&&screen[1]<=bottom)hit=true;
                }
                if(!hit)hit=maxX>=left&&minX<=right&&maxY>=top&&minY<=bottom;
            }
            if(hit)hits.add(object);
        }
        objectScene.deselectAllObjects();
        objectSceneActiveId=null;
        for(Meshy3DObjectScene.Object3D object:hits){
            if(objectSceneActiveId==null)activateSceneObject(object.id,true);
            else activateSceneObject(object.id,false);
            objectSceneActiveId=object.id;
        }
        if(onObjectSelectionChanged!=null)onObjectSelectionChanged.run();
    }

    private void selectObjectAt(float x,float y,boolean additive) {
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.min(getWidth(),getHeight())*.235f*zoom;
        Meshy3DObjectScene.Object3D best=null;
        float bestDepth=Float.MAX_VALUE,bestMarkerDistance=Float.MAX_VALUE;
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()) {
            if(!objectScene.isVisibleInViewport(object)||!object.renderVisible||!object.selectable
                    ||(localViewActive&&!localViewObjectIds.contains(object.id)))continue;
            if(object.mesh==null){
                float[] center=objectScene.transformPoint(object.id,0,0,0);
                float[] screen=project(center[0],center[1],center[2],cx,cy,unit);
                float radius=Math.max(20f,object.displaySize*15f*getResources().getDisplayMetrics().density);
                float distance=(screen[0]-x)*(screen[0]-x)+(screen[1]-y)*(screen[1]-y);
                if(distance<=radius*radius&&distance<bestMarkerDistance){best=object;bestMarkerDistance=distance;}
                continue;
            }
            Meshy3DCore renderMesh=object.mesh;
            if(!object.modifiers.isEmpty()){
                try{renderMesh=Meshy3DModifierEvaluator.evaluate(object);}
                catch(RuntimeException modifierError){
                    modifierEvaluationWarning=object.name+": "+(modifierError.getMessage()==null?"Modifier evaluation failed.":modifierError.getMessage());
                    renderMesh=object.mesh;
                }
            }
            float[][] meshVertices=renderMesh==document?vertices:renderMesh.copyVertices();
            int[][] meshFaces=renderMesh==document?faces:renderMesh.copyFaces();
            float[][] screenVertices=new float[meshVertices.length][3];
            for(int i=0;i<meshVertices.length;i++){
                float[] world=objectScene.transformPoint(object.id,meshVertices[i][0],meshVertices[i][1],meshVertices[i][2]);
                screenVertices[i]=project(world[0],world[1],world[2],cx,cy,unit);
            }
            for(int[] face:meshFaces){
                if(face.length<3)continue;
                float[] polyX=new float[face.length],polyY=new float[face.length];
                float worldX=0,worldY=0,worldZ=0;
                for(int i=0;i<face.length;i++){
                    int id=face[i];float[] p=screenVertices[id];polyX[i]=p[0];polyY[i]=p[1];
                    float[] source=meshVertices[id];float[] world=objectScene.transformPoint(object.id,source[0],source[1],source[2]);
                    worldX+=world[0]/face.length;worldY+=world[1]/face.length;worldZ+=world[2]/face.length;
                }
                if(pointInsidePolygon(x,y,polyX,polyY)){
                    float depth=projectionDistance-cameraPoint(worldX,worldY,worldZ)[2];
                    if(depth<bestDepth){best=object;bestDepth=depth;}
                }
            }
        }
        if(best==null){
            if(!additive){
                objectScene.deselectAllObjects();objectSceneActiveId=null;meshVisible=false;
                invalidate();
                if(onObjectSelectionChanged!=null)onObjectSelectionChanged.run();
            }
            return;
        }
        if(additive)objectScene.setSelected(best.id,!best.selected);
        else objectScene.selectOnly(best.id);
        String activeId=objectScene.getActiveObjectId();
        if(activeId!=null)activateSceneObject(activeId,false);
        else {objectSceneActiveId=null;meshVisible=false;invalidate();}
        if(onObjectSelectionChanged!=null)onObjectSelectionChanged.run();
    }

    private static boolean pointInsidePolygon(float x,float y,float[] px,float[] py){
        boolean inside=false;
        for(int i=0,j=px.length-1;i<px.length;j=i++){
            boolean crosses=(py[i]>y)!=(py[j]>y);
            if(crosses&&x<(px[j]-px[i])*(y-py[i])/(py[j]-py[i]+1e-8f)+px[i])inside=!inside;
        }
        return inside;
    }

    public void selectAllSceneObjects() {
        enterObjectSelectionMode();
        objectScene.selectAllObjects();
        String id=objectScene.getActiveObjectId();
        if(id!=null)activateSceneObject(id,false);
        invalidate();
    }
    public void deselectAllSceneObjects() {
        enterObjectSelectionMode();
        objectScene.deselectAllObjects();objectSceneActiveId=null;invalidate();
    }
    public void invertSceneSelection() {
        enterObjectSelectionMode();
        objectScene.invertObjectSelection();
        String id=objectScene.getActiveObjectId();
        if(id!=null)activateSceneObject(id,false);else objectSceneActiveId=null;
        invalidate();
    }
    public void deleteSelectedObjects() {
        java.util.ArrayList<Meshy3DObjectScene.Object3D> selected=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(selected.isEmpty()&&objectSceneActiveId!=null)selected.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D o:selected)if(o!=null)objectScene.delete(o.id);
        objectSceneActiveId=null;
        Meshy3DObjectScene.Object3D next=null;
        for(Meshy3DObjectScene.Object3D o:objectScene.getObjects())if(o.selected){next=o;break;}
        if(next==null)for(Meshy3DObjectScene.Object3D o:objectScene.getObjects()){next=o;break;}
        if(next==null){
            objectSceneActiveId=null;meshVisible=false;invalidate();
            if(onObjectSelectionChanged!=null)onObjectSelectionChanged.run();
            return;
        }
        activateSceneObject(next.id);
    }

    public void deleteActiveObject() {
        if(objectSceneActiveId==null)return;
        objectScene.delete(objectSceneActiveId);
        objectSceneActiveId=null;
        Meshy3DObjectScene.Object3D next=null;
        for(Meshy3DObjectScene.Object3D candidate:objectScene.getObjects()){next=candidate;break;}
        if(next==null){
            objectSceneActiveId=null;meshVisible=false;invalidate();
            if(onObjectSelectionChanged!=null)onObjectSelectionChanged.run();
            return;
        }
        activateSceneObject(next.id);
    }

    public void renameActiveObject(String name) {
        if(objectSceneActiveId==null)return;
        objectScene.rename(objectSceneActiveId,name);
        invalidate();
    }

    public void transformActiveObject(int operation,float x,float y,float z) {
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty()&&objectSceneActiveId!=null)targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets) {
            Meshy3DObjectScene.Transform t=target.transform.copy();
            if(operation==0){t.x+=x;t.y+=y;t.z+=z;}
            else if(operation==1){t.rotationX+=x;t.rotationY+=y;t.rotationZ+=z;}
            else {t.scaleX*=x;t.scaleY*=y;t.scaleZ*=z;}
            objectScene.setTransform(target.id,t);
        }
        invalidate();
    }

    public void applyActiveObjectRotationAndScale() {
        if(objectSceneActiveId==null)return;
        syncCoreFromViewport();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)objectScene.applyRotationAndScale(target.id);
        syncViewportFromCore();
    }

    public void applyActiveObjectLocation() {
        if(objectSceneActiveId==null)return;
        syncCoreFromViewport();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)objectScene.applyLocation(target.id);
        syncViewportFromCore();invalidate();
    }
    public void setActiveObjectOriginToGeometry() {
        if(objectSceneActiveId==null)return;
        syncCoreFromViewport();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)
            if(target!=null&&target.mesh!=null)objectScene.setOriginToGeometry(target.id);
        syncViewportFromCore();invalidate();
    }
    public void setActiveObjectOriginToCursor() {
        if(objectSceneActiveId==null)return;
        syncCoreFromViewport();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)
            if(target!=null&&target.mesh!=null)objectScene.setOriginToCursor(target.id);
        syncViewportFromCore();invalidate();
    }
    public void clearActiveObjectTransform(int component) {
        if(objectSceneActiveId==null)return;
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets){
            if(component==0)objectScene.clearLocation(target.id);
            else if(component==1)objectScene.clearRotation(target.id);
            else objectScene.clearScale(target.id);
        }
        invalidate();
    }

    public void setActiveObjectVisible(boolean visible) {
        if(objectSceneActiveId==null)return;
        objectScene.setVisibility(objectSceneActiveId,visible);
        meshVisible=visible;
        invalidate();
    }
    public void setActiveObjectSelectable(boolean selectable) {
        if(objectSceneActiveId==null)return;
        objectScene.setSelectable(objectSceneActiveId,selectable);invalidate();
    }
    public void setActiveObjectRenderVisible(boolean visible) {
        if(objectSceneActiveId==null)return;
        objectScene.setRenderVisibility(objectSceneActiveId,visible);invalidate();
    }
    public void setSelectedObjectsVisible(boolean visible) {
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty()&&objectSceneActiveId!=null)targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)objectScene.setVisibility(target.id,visible);
        invalidate();
    }

    public void setActiveObjectParent(String parentId) {
        if(objectSceneActiveId==null)return;
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(targets.isEmpty())targets.add(objectScene.getObject(objectSceneActiveId));
        for(Meshy3DObjectScene.Object3D target:targets)
            if(target!=null&&!target.id.equals(parentId))objectScene.setParent(target.id,parentId);
        invalidate();
    }

    public org.json.JSONObject exportObjectSceneState() {
        syncCoreFromViewport();
        return objectScene.exportState();
    }

    public boolean importObjectSceneState(org.json.JSONObject state) {
        if(state==null||!objectScene.importState(state))return false;
        objectSceneActiveId=objectScene.getActiveObjectId();
        if(objectSceneActiveId==null&&!objectScene.getObjects().isEmpty())
            objectSceneActiveId=objectScene.getObjects().get(0).id;
        if(objectSceneActiveId==null)return false;
        activateSceneObject(objectSceneActiveId);
        return true;
    }

    private RenderData buildRenderData() {
        RenderData data=new RenderData();
        if(meshEditMode) {
            data.vertices=vertices;
            data.faces=faces;
            data.activeFaces=new boolean[faces.length];
            data.wireFaces=new boolean[faces.length];
            data.displayColors=new int[faces.length];
            java.util.Arrays.fill(data.activeFaces,true);
            return data;
        }
        java.util.ArrayList<float[]> allVertices=new java.util.ArrayList<>();
        java.util.ArrayList<int[]> allFaces=new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> active=new java.util.ArrayList<>(),wire=new java.util.ArrayList<>();
        java.util.ArrayList<Integer> colors=new java.util.ArrayList<>();
        modifierEvaluationWarning=null;
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()) {
            if(!objectScene.isVisibleInViewport(object)||!object.renderVisible
                    ||(localViewActive&&!localViewObjectIds.contains(object.id))||object.mesh==null)continue;
            Meshy3DCore renderMesh=object.mesh;
            if(!object.modifiers.isEmpty()){
                try{renderMesh=Meshy3DModifierEvaluator.evaluate(object);}
                catch(RuntimeException error){
                    modifierEvaluationWarning=object.name+": "+(error.getMessage()==null?"Modifier evaluation failed.":error.getMessage());
                    renderMesh=object.mesh;
                }
            }
            float[][] meshVertices=renderMesh==document?vertices:renderMesh.copyVertices();
            int[][] meshFaces=renderMesh==document?faces:renderMesh.copyFaces();
            boolean bounds="BOUNDS".equals(object.viewportDisplayType);
            boolean wireOnly=bounds||"WIRE".equals(object.viewportDisplayType);
            if(bounds&&meshVertices.length>0){
                float[] min={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE};
                float[] max={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
                for(float[] vertex:meshVertices)for(int axis=0;axis<3;axis++){
                    min[axis]=Math.min(min[axis],vertex[axis]);max[axis]=Math.max(max[axis],vertex[axis]);
                }
                meshVertices=new float[][]{
                        {min[0],min[1],min[2]},{max[0],min[1],min[2]},
                        {max[0],max[1],min[2]},{min[0],max[1],min[2]},
                        {min[0],min[1],max[2]},{max[0],min[1],max[2]},
                        {max[0],max[1],max[2]},{min[0],max[1],max[2]}};
                meshFaces=new int[][]{{0,1,2,3},{4,7,6,5},{0,4,5,1},
                        {1,5,6,2},{2,6,7,3},{3,7,4,0}};
            }
            int offset=allVertices.size();
            for(float[] source:meshVertices)
                allVertices.add(objectScene.transformPoint(object.id,source[0],source[1],source[2]));
            for(int[] face:meshFaces) {
                int[] mapped=face.clone();
                for(int i=0;i<mapped.length;i++)mapped[i]+=offset;
                allFaces.add(mapped);
                active.add(object.id.equals(objectSceneActiveId));
                wire.add(wireOnly);
                colors.add(object.viewportDisplayColor);
            }
        }
        data.vertices=allVertices.toArray(new float[0][]);
        data.faces=allFaces.toArray(new int[0][]);
        data.activeFaces=new boolean[active.size()];
        data.wireFaces=new boolean[wire.size()];
        data.displayColors=new int[colors.size()];
        for(int i=0;i<active.size();i++){
            data.activeFaces[i]=active.get(i);
            data.wireFaces[i]=wire.get(i);
            data.displayColors[i]=colors.get(i);
        }
        return data;
    }
    private SceneRenderData buildFinalSceneRenderData() {
        ArrayList<float[]> sceneVertices=new ArrayList<>();
        ArrayList<int[]> sceneFaces=new ArrayList<>();
        ArrayList<Integer> faceMaterials=new ArrayList<>();
        ArrayList<float[][]> faceUvList=new ArrayList<>();
        ArrayList<Integer> materialColors=new ArrayList<>();
        ArrayList<Float> materialMetallic=new ArrayList<>(),materialRoughness=new ArrayList<>();
        ArrayList<int[]> materialTextures=new ArrayList<>();
        ArrayList<Integer> textureWidths=new ArrayList<>(),textureHeights=new ArrayList<>();
        ArrayList<float[]> faceLightDirections=new ArrayList<>(),faceLightTints=new ArrayList<>();
        java.util.IdentityHashMap<Meshy3DCore,Integer> materialIds=new java.util.IdentityHashMap<>();
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()) {
            Meshy3DCore mesh=object.mesh;
            if(mesh==null||!"MESH".equals(object.type)||!objectScene.isVisibleInRender(object))continue;
            if(!object.modifiers.isEmpty()){
                try{mesh=Meshy3DModifierEvaluator.evaluate(object);}
                catch(RuntimeException modifierError){
                    modifierEvaluationWarning=object.name+": "+(modifierError.getMessage()==null?"Modifier evaluation failed.":modifierError.getMessage());
                    mesh=object.mesh;
                }
            }
            Integer materialId=materialIds.get(mesh);
            if(materialId==null) {
                materialId=materialColors.size();materialIds.put(mesh,materialId);
                materialColors.add(mesh.getBaseColor());materialMetallic.add(mesh.getMetallic());
                materialRoughness.add(mesh.getRoughness());
                materialTextures.add(mesh.getTexturePixels());
                textureWidths.add(mesh.getTextureWidth());textureHeights.add(mesh.getTextureHeight());
            }
            float[][] sourceVertices=mesh.copyVertices();int[][] sourceFaces=mesh.copyFaces();
            float[][][] sourceUvs=mesh.copyFaceUvs();int offset=sceneVertices.size();
            for(float[] vertex:sourceVertices) {
                float[] world=objectScene.transformPoint(object.id,vertex[0],vertex[1],vertex[2]);
                sceneVertices.add(new float[]{world[0]-viewTargetX,world[1]-viewTargetY,world[2]-viewTargetZ});
            }
            for(int fi=0;fi<sourceFaces.length;fi++) {
                int[] face=sourceFaces[fi].clone();
                for(int i=0;i<face.length;i++)face[i]+=offset;
                sceneFaces.add(face);faceMaterials.add(materialId);
                faceUvList.add(sourceUvs!=null&&fi<sourceUvs.length?sourceUvs[fi]:null);
                float centerX=0f,centerY=0f,centerZ=0f;
                for(int vertexId:sourceFaces[fi]){
                    float[] local=sourceVertices[vertexId];
                    float[] world=objectScene.transformPoint(object.id,local[0],local[1],local[2]);
                    centerX+=world[0]/sourceFaces[fi].length;
                    centerY+=world[1]/sourceFaces[fi].length;
                    centerZ+=world[2]/sourceFaces[fi].length;
                }
                float[] light=sceneLightForPoint(centerX,centerY,centerZ);
                faceLightDirections.add(new float[]{light[0],light[1],light[2]});
                faceLightTints.add(new float[]{light[3],light[4],light[5]});
            }
        }
        SceneRenderData data=new SceneRenderData();
        data.vertices=sceneVertices.toArray(new float[0][]);
        data.faces=sceneFaces.toArray(new int[0][]);
        data.faceMaterialIds=new int[faceMaterials.size()];
        for(int i=0;i<data.faceMaterialIds.length;i++)data.faceMaterialIds[i]=faceMaterials.get(i);
        data.materialColors=new int[materialColors.size()];
        data.materialMetallic=new float[materialMetallic.size()];
        data.materialRoughness=new float[materialRoughness.size()];
        data.materialTextures=materialTextures.toArray(new int[0][]);
        data.textureWidths=new int[textureWidths.size()];data.textureHeights=new int[textureHeights.size()];
        data.faceLightDirections=faceLightDirections.toArray(new float[0][]);
        data.faceLightTints=faceLightTints.toArray(new float[0][]);
        for(int i=0;i<materialColors.size();i++){
            data.materialColors[i]=materialColors.get(i);
            data.materialMetallic[i]=materialMetallic.get(i);
            data.materialRoughness[i]=materialRoughness.get(i);
            data.textureWidths[i]=textureWidths.get(i);data.textureHeights[i]=textureHeights.get(i);
        }
        data.faceUvs=faceUvList.toArray(new float[0][][]);
        return data;
    }

    public String validateActiveObjectModifiers(){
        Meshy3DObjectScene.Object3D active=objectScene.getActiveObject();
        if(active==null||active.mesh==null)return "Select a mesh object first.";
        try{Meshy3DModifierEvaluator.evaluate(active);modifierEvaluationWarning=null;invalidate();return null;}
        catch(RuntimeException error){
            modifierEvaluationWarning=active.name+": "+(error.getMessage()==null?"Modifier evaluation failed.":error.getMessage());
            invalidate();return modifierEvaluationWarning;
        }
    }

    public void applyActiveObjectModifiersThrough(int modifierIndex){
        Meshy3DObjectScene.Object3D active=objectScene.getActiveObject();
        if(active==null||active.mesh==null)throw new IllegalStateException("Select a mesh object first.");
        if(modifierIndex<0||modifierIndex>=active.modifiers.size())
            throw new IllegalArgumentException("Modifier index is outside the stack.");
        Meshy3DCore evaluated=Meshy3DModifierEvaluator.evaluateThrough(active,modifierIndex);
        active.mesh=evaluated;
        objectScene.removeModifiersThrough(active.id,modifierIndex);
        modifierEvaluationWarning=null;
        if(active.id.equals(objectSceneActiveId)){
            document=evaluated;syncViewportFromCore();
        }
        invalidate();
    }

    public String getModifierEvaluationWarning(){return modifierEvaluationWarning;}

    public android.graphics.Bitmap renderFinalImage(int width,int height){
        syncCoreFromViewport();
        if(!meshEditMode) {
            SceneRenderData scene=buildFinalSceneRenderData();
            return Meshy3DSoftwareRenderer.renderScene(scene.vertices,scene.faces,width,height,yaw,pitch,viewRoll,zoom,
                    scene.faceMaterialIds,scene.materialColors,scene.materialMetallic,scene.materialRoughness,
                    scene.materialTextures,scene.textureWidths,scene.textureHeights,
                    scene.faceLightDirections,scene.faceLightTints,scene.faceUvs);
        }
        return Meshy3DSoftwareRenderer.render(vertices,faces,width,height,yaw,pitch,viewRoll,zoom,meshTint,metallic,roughness,
                document.getTexturePixels(),document.getTextureWidth(),document.getTextureHeight(),document.copyFaceUvs());
    }
    public void refreshFromDocument(){syncViewportFromCore();}
    private void syncCoreFromViewport(){
        document.replaceGeometry(vertices,faces);document.setSculptMask(vertexMask);
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active!=null&&active.mesh!=null)active.mesh=document;
    }
    private void syncViewportFromCore(){
        vertices=document.copyVertices();faces=document.copyFaces();vertexMask=document.getSculptMask();if(vertexMask.length!=vertices.length)vertexMask=new float[vertices.length];
        uvCoordinates=null;invalidate();
    }
    public void setMeshEditMode(boolean enabled){
        boolean entering=enabled&&!meshEditMode;
        meshEditMode=enabled;
        if(enabled){
            showTopology=true;
            if(entering&&document.getSelectedCount()==0)document.selectAll();
        }
        invalidate();
    }
    public boolean isMeshEditMode(){return meshEditMode;}
    public void setSelectionMode(String mode){
        if("Vertex".equalsIgnoreCase(mode))selectionMode=Meshy3DCore.SelectMode.VERTEX;
        else if("Edge".equalsIgnoreCase(mode))selectionMode=Meshy3DCore.SelectMode.EDGE;
        else selectionMode=Meshy3DCore.SelectMode.FACE;
        document.setSelectMode(selectionMode);invalidate();
    }
    public String getSelectionMode(){return selectionMode.name();}
    public int getSelectedElementCount(){return document.getSelectedCount();}
    public void clearElementSelection(){document.clearSelection();invalidate();}
    public void selectAllElements(){document.selectAll();invalidate();}
    /**
     * Pick mesh elements using the active object's world transform. This keeps
     * edit-mode vertex/edge/face taps aligned after moving, rotating or scaling
     * the object in Object Mode.
     */
    private static boolean pointInPolygon(float x,float y,float[][] polygon) {
        boolean inside=false;
        for(int i=0,j=polygon.length-1;i<polygon.length;j=i++){
            float xi=polygon[i][0],yi=polygon[i][1],xj=polygon[j][0],yj=polygon[j][1];
            boolean crosses=((yi>y)!=(yj>y))
                    &&(x<(xj-xi)*(y-yi)/(Math.abs(yj-yi)<1e-6f?1e-6f: yj-yi)+xi);
            if(crosses)inside=!inside;
        }
        return inside;
    }

    private float projectedDepth(float[] point) {
        return cameraPoint(point[0],point[1],point[2])[2];
    }

    /** Screen-space component picking with visible-face testing and depth-aware overlap resolution. */
    public void selectAt(float x,float y,boolean additive){
        if(getWidth()<=0||getHeight()<=0)return;
        updateProjectionCameraDistance();
        float cx=getWidth()*.5f,cy=getHeight()*.49f;
        float unit=Math.min(getWidth(),getHeight())*.235f*zoom;
        float density=getResources().getDisplayMetrics().density;
        float[][] local=document.copyVertices();
        float[][] world=new float[local.length][3];
        float[][] screen=new float[local.length][3];
        for(int i=0;i<local.length;i++){
            if(objectSceneActiveId!=null){
                world[i]=objectScene.transformPoint(objectSceneActiveId,local[i][0],local[i][1],local[i][2]);
            }else world[i]=local[i].clone();
            screen[i]=project(world[i][0],world[i][1],world[i][2],cx,cy,unit);
        }

        int best=-1;
        float bestDistance=Float.MAX_VALUE;
        float bestDepth=-Float.MAX_VALUE;
        if(selectionMode==Meshy3DCore.SelectMode.VERTEX){
            float tolerance= Math.max(12f*density,7f*density/Math.max(.25f,zoom));
            float limit=tolerance*tolerance;
            for(int i=0;i<screen.length;i++){
                float dx=screen[i][0]-x,dy=screen[i][1]-y,distance=dx*dx+dy*dy;
                float depth=screen[i][2];
                if(distance<=limit&&(distance<bestDistance-1f
                        ||(Math.abs(distance-bestDistance)<=1f&&depth>bestDepth))){
                    bestDistance=distance;bestDepth=depth;best=i;
                }
            }
            if(best>=0)document.selectVertex(best,additive);
        }else if(selectionMode==Meshy3DCore.SelectMode.EDGE){
            java.util.List<Meshy3DCore.Edge> edges=document.getEdges();
            float tolerance= Math.max(12f*density,7f*density/Math.max(.25f,zoom));
            float limit=tolerance*tolerance;
            for(int i=0;i<edges.size();i++){
                Meshy3DCore.Edge edge=edges.get(i);
                float[] a=screen[edge.a],b=screen[edge.b];
                float dx=b[0]-a[0],dy=b[1]-a[1];
                float t=((x-a[0])*dx+(y-a[1])*dy)/Math.max(.0001f,dx*dx+dy*dy);
                t=Math.max(0f,Math.min(1f,t));
                float px=a[0]+t*dx,py=a[1]+t*dy;
                float ex=px-x,ey=py-y,distance=ex*ex+ey*ey;
                float depth=a[2]+(b[2]-a[2])*t;
                if(distance<=limit&&(distance<bestDistance-1f
                        ||(Math.abs(distance-bestDistance)<=1f&&depth>bestDepth))){
                    bestDistance=distance;bestDepth=depth;best=i;
                }
            }
            if(best>=0)document.selectEdge(best,additive);
        }else{
            int[][] polygons=document.copyFaces();
            float nearestDepth=-Float.MAX_VALUE;
            int containingFace=-1;
            for(int i=0;i<polygons.length;i++){
                int[] face=polygons[i];
                if(face.length<3)continue;
                float[][] polygon=new float[face.length][2];
                float averageDepth=0f;
                for(int j=0;j<face.length;j++){
                    float[] p=screen[face[j]];
                    polygon[j][0]=p[0];polygon[j][1]=p[1];averageDepth+=p[2]/face.length;
                }
                // Ignore back-facing polygons just as the viewport renderer does.
                float[] a=cameraPoint(world[face[0]][0],world[face[0]][1],world[face[0]][2]);
                float[] b=cameraPoint(world[face[1]][0],world[face[1]][1],world[face[1]][2]);
                float[] c=cameraPoint(world[face[2]][0],world[face[2]][1],world[face[2]][2]);
                float abx=b[0]-a[0],aby=b[1]-a[1],abz=b[2]-a[2];
                float acx=c[0]-a[0],acy=c[1]-a[1],acz=c[2]-a[2];
                float nz=abx*acy-aby*acx;
                if(nz<=0.0001f)continue;
                if(pointInPolygon(x,y,polygon)&&averageDepth>nearestDepth){
                    nearestDepth=averageDepth;containingFace=i;
                }
            }
            if(containingFace>=0)document.selectFace(containingFace,additive);
        }
        invalidate();
    }
    public void insetSelected(float amount){document.insetSelected(amount);syncViewportFromCore();}
    public void bevelSelected(float amount){document.bevelSelectedEdges(amount);syncViewportFromCore();}
    public void loopCutSelected(float factor){document.loopCutSelected(factor);syncViewportFromCore();}
    public void subdivideSelected(){document.subdivide();syncViewportFromCore();}
    public void subdivideCatmullClark(int levels){document.subdivideCatmullClark(levels);syncViewportFromCore();}
    public String separateSelectedFacesToObject() {
        if(objectSceneActiveId==null)throw new IllegalStateException("Select a mesh object first.");
        syncCoreFromViewport();
        Meshy3DObjectScene.Object3D source=objectScene.getObject(objectSceneActiveId);
        if(source==null||source.mesh==null||!"MESH".equals(source.type))
            throw new IllegalStateException("Separate Selection requires an active mesh object.");
        Meshy3DCore detached=document.extractSelectedFaces();
        if(detached==null)return null;
        syncViewportFromCore();
        Meshy3DObjectScene.Object3D created=objectScene.createMeshObject(source.name+"_separated",detached);
        created.transform.x=source.transform.x;created.transform.y=source.transform.y;created.transform.z=source.transform.z;
        created.transform.rotationX=source.transform.rotationX;created.transform.rotationY=source.transform.rotationY;
        created.transform.rotationZ=source.transform.rotationZ;
        created.transform.scaleX=source.transform.scaleX;created.transform.scaleY=source.transform.scaleY;
        created.transform.scaleZ=source.transform.scaleZ;
        activateSceneObject(created.id);
        meshEditMode=false;objectSelectionEnabled=true;
        invalidate();
        return created.id;
    }
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
    public boolean gridFillSelectedBoundary(){
        boolean filled=document.gridFillSelectedBoundary();
        syncViewportFromCore();
        return filled;
    }
    public void splitSelectedFaces(){document.splitSelectedFaces();syncViewportFromCore();}
    public void bisectMesh(float nx,float ny,float nz,float offset){document.bisect(nx,ny,nz,offset);syncViewportFromCore();}
    public void bridgeSelectedLoops(){document.bridgeSelectedBoundaryLoops();syncViewportFromCore();}
    public void solidifySelected(float thickness){document.solidifySelected(thickness);syncViewportFromCore();}
    public void spinSelectedFaces(float angle,int steps,float screwDistance){document.spinSelectedFaces(angle,steps,screwDistance);syncViewportFromCore();}

    public void slideSelected(float factor,boolean edgeMode){document.slideSelected(factor,edgeMode);syncViewportFromCore();}
    public void decimatePlanar(float angle,int targetFaces){document.decimatePlanar(angle,targetFaces);syncViewportFromCore();}
    public void unSubdividePlanar(){document.decimatePlanar(.5f,4);syncViewportFromCore();}
    public void startPolyBuild(){polyBuildPoints.clear();polyBuildMode=true;meshEditMode=true;invalidate();}
    private float[] worldPointFromScreen(float x,float y){
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.max(1,Math.min(getWidth(),getHeight())*.235f*zoom);
        return cameraVectorToWorld((x-cx)/unit,(cy-y)/unit,0);
    }
    private void addPolyBuildPoint(float x,float y){
        if(polyBuildPoints.size()>=3){
            int first=polyBuildPoints.get(0);float[] p=vertices[first],screen=project(p[0],p[1],p[2],getWidth()*.5f,getHeight()*.49f,Math.min(getWidth(),getHeight())*.235f*zoom);
            float d=(screen[0]-x)*(screen[0]-x)+(screen[1]-y)*(screen[1]-y);
            if(d<400f){int[] ids=new int[polyBuildPoints.size()];for(int i=0;i<ids.length;i++)ids[i]=polyBuildPoints.get(i);
                document.addFaceFromVertices(ids);polyBuildPoints.clear();polyBuildMode=false;syncViewportFromCore();return;}
        }
        float[] p=worldPointFromScreen(x,y);polyBuildPoints.add(document.addVertex(p[0],p[1],p[2]));
        vertices=document.copyVertices();faces=document.copyFaces();vertexMask=document.getSculptMask();invalidate();
    }


    public void deleteSelectedFaces(){document.deleteSelectedFaces();syncViewportFromCore();}
    public void deleteSelectedVertices(){document.deleteSelectedVertices();syncViewportFromCore();}
    public void smoothTopology(float amount,int iterations){document.smooth(amount,iterations);syncViewportFromCore();}
    public void markSeams(boolean clear){document.markSelectedSeams(clear);invalidate();}
    public void pinSelectedUvs(boolean pin){document.pinSelectedUvs(pin);invalidate();}
    public void packUvIslands(){document.packUvIslands();invalidate();}
    public void packUvIslands(float margin){document.packUvIslands(margin);invalidate();}
    public void snapSelectedUvToPixels(int width,int height){document.snapSelectedUvToPixels(width,height);invalidate();}
    public boolean snapSelectedUvToNearestVertex(float maxDistance){
        boolean snapped=document.snapSelectedUvToNearestVertex(maxDistance);invalidate();return snapped;
    }
    public void removeUvMap(){document.clearUvMap();invalidate();}
    public boolean hasUvMap(){return document.hasUvMap();}
    public boolean selectPinnedUvIsland(){boolean found=document.selectPinnedUvIsland();invalidate();return found;}
    public boolean selectOverlappingUvIsland(){boolean found=document.selectOverlappingUvIsland();invalidate();return found;}
    public boolean selectBoundaryUvIsland(){boolean found=document.selectBoundaryUvIsland();invalidate();return found;}
    public void mirrorSelectedUv(int axis){document.mirrorSelectedUv(axis);invalidate();}
    public void alignSelectedUv(int axis){document.alignSelectedUv(axis);invalidate();}
    public void weldSelectedUv(float tolerance){document.weldSelectedUv(tolerance);invalidate();}
    public boolean splitSelectedUvIsland(){boolean split=document.splitSelectedUvIsland();invalidate();return split;}
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
    private int sculptStrokeMethod;
    private float sculptCursorX,sculptCursorY;
    private boolean sculptCursorVisible;
    private boolean sculptSubtract;
    private float strokeX, strokeY, previousStrokeX, previousStrokeY;
    private boolean strokeActive;
    private float[] strokePlanePoint=new float[3],strokePlaneNormal=new float[]{0,0,1};
    private float currentStrokePressure=1f;
    private boolean moved;
    private float downX, downY;
    private boolean polyBuildMode;
    private final java.util.ArrayList<Integer> polyBuildPoints=new java.util.ArrayList<>();

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
        // Blender-style startup scene: retain the selected default cube and add a usable camera/light.
        Meshy3DObjectScene.Object3D starter = objectScene.createMeshObject("Cube", document);
        Meshy3DObjectScene.Object3D camera = objectScene.createTypedObject("Camera", "CAMERA", null);
        camera.transform.x = 7.0f;
        camera.transform.y = 5.0f;
        camera.transform.z = 7.0f;
        camera.transform.rotationX = (float)Math.toRadians(22.0);
        camera.transform.rotationY = (float)Math.toRadians(45.0);
        objectScene.setActiveCamera(camera.id);
        Meshy3DObjectScene.Object3D light = objectScene.createTypedObject("Light", "LIGHT", null);
        light.transform.x = -3.5f;
        light.transform.y = 5.0f;
        light.transform.z = 3.0f;
        light.lightType = "SUN";
        light.lightPower = 1000.0f;
        objectScene.selectOnly(starter.id);
        objectSceneActiveId = starter.id;
        objectSelectionEnabled = true;
        objectTransformTool = -1;
    }

    private float[] cameraPoint(float x, float y, float z) {
        return cameraDirectionPoint(x-viewTargetX,y-viewTargetY,z-viewTargetZ);
    }

    private float[] cameraDirectionPoint(float x,float y,float z) {
        double cy=Math.cos(yaw), sy=Math.sin(yaw);
        double cp=Math.cos(pitch), sp=Math.sin(pitch);
        double rx=x*cy-z*sy;
        double rz=x*sy+z*cy;
        double ry=y*cp-rz*sp;
        double rz2=y*sp+rz*cp;
        double cr=Math.cos(viewRoll),sr=Math.sin(viewRoll);
        double rolledX=rx*cr+ry*sr;
        double rolledY=-rx*sr+ry*cr;
        return new float[]{(float)rolledX,(float)rolledY,(float)rz2};
    }

    private float[] project(float x, float y, float z, float cx, float cy, float unit) {
        float[] q=cameraPoint(x,y,z);
        float depth=projectionDistance-q[2];
        float nearLimit=Math.max(0.1f,projectionDistance*0.035f);
        float perspective=projectionDistance/Math.max(nearLimit,depth);
        return new float[]{cx+q[0]*unit*perspective,cy-q[1]*unit*perspective,q[2]};
    }

    /** Repositions the virtual viewport camera far enough to contain the largest visible object. */
    private void updateProjectionCameraDistance() {
        float maxRadius=0f;
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()) {
            if(object==null||!objectScene.isVisibleInViewport(object)||!object.renderVisible
                    ||(localViewActive&&!localViewObjectIds.contains(object.id))) continue;
            if(object.mesh!=null) {
                for(float[] vertex:object.mesh.copyVertices()) {
                    float[] world=objectScene.transformPoint(object.id,vertex[0],vertex[1],vertex[2]);
                    float[] q=cameraDirectionPoint(world[0]-viewTargetX,world[1]-viewTargetY,world[2]-viewTargetZ);
                    maxRadius=Math.max(maxRadius,(float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]));
                }
            } else {
                float[] world=objectScene.transformPoint(object.id,0,0,0);
                float[] q=cameraDirectionPoint(world[0]-viewTargetX,world[1]-viewTargetY,world[2]-viewTargetZ);
                maxRadius=Math.max(maxRadius,(float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2])
                        +Math.max(0.1f,object.displaySize));
            }
        }
        projectionDistance=Math.max(5.2f,maxRadius*3.0f+2.0f);
    }

    public void frameSelectedObjects(){cameraViewActive=false;frameObjects(false);}
    public void frameAllObjects(){cameraViewActive=false;frameObjects(true);}

    public void setViewportNavigationMode(int mode) {
        viewportNavigationMode = mode == 1 ? 1 : 0;
    }
    public int getViewportNavigationMode() { return viewportNavigationMode; }

    public void setMeasureMode(boolean enabled) {
        measureMode = enabled;
        measurementHasStart = false;
        measurementHasEnd = false;
        invalidate();
    }
    public boolean isMeasureMode() { return measureMode; }
    public void clearMeasurement() {
        measurementHasStart = false;
        measurementHasEnd = false;
        invalidate();
    }

    public boolean isCameraViewActive(){return cameraViewActive;}
    public void toggleActiveCameraView() {
        if(cameraViewActive) {
            yaw=savedViewYaw;pitch=savedViewPitch;viewRoll=savedViewRoll;zoom=savedViewZoom;
            viewTargetX=savedTargetX;viewTargetY=savedTargetY;viewTargetZ=savedTargetZ;
            cameraViewActive=false;invalidate();return;
        }
        Meshy3DObjectScene.Object3D camera=objectScene.getActiveCamera();
        if(camera==null)throw new IllegalStateException("Create or set an active Camera object first");
        float[] position=objectScene.transformPoint(camera.id,0,0,0);
        float[] forwardPoint=objectScene.transformPoint(camera.id,0,0,-1);
        float[] upPoint=objectScene.transformPoint(camera.id,0,1,0);
        float fx=forwardPoint[0]-position[0],fy=forwardPoint[1]-position[1],fz=forwardPoint[2]-position[2];
        float ux=upPoint[0]-position[0],uy=upPoint[1]-position[1],uz=upPoint[2]-position[2];
        float length=(float)Math.sqrt(fx*fx+fy*fy+fz*fz);
        if(length<1e-6f)throw new IllegalStateException("Camera orientation is invalid");
        fx/=length;fy/=length;fz/=length;
        savedViewYaw=yaw;savedViewPitch=pitch;savedViewRoll=viewRoll;savedViewZoom=zoom;
        savedTargetX=viewTargetX;savedTargetY=viewTargetY;savedTargetZ=viewTargetZ;
        pitch=(float)Math.asin(Math.max(-1f,Math.min(1f,-fy)));
        yaw=(float)Math.atan2(-fx,-fz);
        viewRoll=0f;
        float upDot=ux*fx+uy*fy+uz*fz;
        ux-=upDot*fx;uy-=upDot*fy;uz-=upDot*fz;
        float upLength=(float)Math.sqrt(ux*ux+uy*uy+uz*uz);
        if(upLength>1e-6f){
            ux/=upLength;uy/=upLength;uz/=upLength;
            float[] cameraUp=cameraDirectionPoint(ux,uy,uz);
            viewRoll=(float)Math.atan2(-cameraUp[0],cameraUp[1]);
        }
        viewTargetX=position[0]+fx*5.2f;
        viewTargetY=position[1]+fy*5.2f;
        viewTargetZ=position[2]+fz*5.2f;
        zoom=Math.max(.35f,Math.min(3.4f,camera.cameraLens/50f));
        cameraViewActive=true;invalidate();
    }
    private void frameObjects(boolean all) {
        java.util.ArrayList<Meshy3DObjectScene.Object3D> targets=new java.util.ArrayList<>();
        if(all) {
            for(Meshy3DObjectScene.Object3D object:objectScene.getObjects())
                if(objectScene.isVisibleInViewport(object))targets.add(object);
        } else {
            targets.addAll(objectScene.getSelectedObjects());
            if(targets.isEmpty()&&objectSceneActiveId!=null)targets.add(objectScene.getObject(objectSceneActiveId));
        }
        if(targets.isEmpty())return;
        float[] min={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE};
        float[] max={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        boolean found=false;
        for(Meshy3DObjectScene.Object3D object:targets) {
            if(object==null)continue;
            if(object.mesh!=null) {
                Meshy3DCore frameMesh=object.mesh;
                if(!object.modifiers.isEmpty()){
                    try{frameMesh=Meshy3DModifierEvaluator.evaluate(object);}
                    catch(RuntimeException error){
                        modifierEvaluationWarning=object.name+": "+(error.getMessage()==null?"Modifier evaluation failed.":error.getMessage());
                        frameMesh=object.mesh;
                    }
                }
                for(float[] vertex:frameMesh.copyVertices()) {
                    float[] world=objectScene.transformPoint(object.id,vertex[0],vertex[1],vertex[2]);
                    for(int axis=0;axis<3;axis++){min[axis]=Math.min(min[axis],world[axis]);max[axis]=Math.max(max[axis],world[axis]);}
                    found=true;
                }
            } else {
                float[] world=objectScene.transformPoint(object.id,0,0,0);
                float radius=Math.max(.1f,object.displaySize);
                for(int axis=0;axis<3;axis++){min[axis]=Math.min(min[axis],world[axis]-radius);max[axis]=Math.max(max[axis],world[axis]+radius);}
                found=true;
            }
        }
        if(!found)return;
        viewTargetX=(min[0]+max[0])*.5f;viewTargetY=(min[1]+max[1])*.5f;viewTargetZ=(min[2]+max[2])*.5f;
        float extent=Math.max(max[0]-min[0],Math.max(max[1]-min[1],max[2]-min[2]));
        // Large objects must be allowed to frame below the old 0.35 zoom floor.
        zoom=Math.max(0.015f,Math.min(3.4f,3.0f/Math.max(.75f,extent)));
        updateProjectionCameraDistance();
        invalidate();
    }

    public void setSceneCursorLocation(float x,float y,float z){objectScene.setCursorLocation(x,y,z);invalidate();}
    public void snapSelectedObjectsToGrid(float step){objectScene.snapSelectedToGrid(step);invalidate();}
    public void snapSelectedObjectsToCursor(){objectScene.snapSelectedToCursor();invalidate();}
    public float[] getSceneCursorLocation(){return objectScene.getCursorLocation();}

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width=getWidth(), height=getHeight();
        updateProjectionCameraDistance();
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

        RenderData renderData=buildRenderData();
        float[][] drawVertices=renderData.vertices;
        int[][] drawFaces=renderData.faces;
        if (meshEditMode && !meshVisible) {
            drawNavigationGizmo(canvas, width, 18f*getResources().getDisplayMetrics().density);
            return;
        }
        float[][] p=new float[drawVertices.length][3];
        for(int i=0;i<drawVertices.length;i++) p[i]=project(drawVertices[i][0],drawVertices[i][1],drawVertices[i][2],cx,cy,unit);

        float[] viewportLight=cameraDirectionPoint(-.45f,.82f,.65f);
        boolean hasActiveTexture=meshEditMode&&document.hasTexture()&&document.hasFaceUvs();
        // Draw only outward-facing faces, sorted far-to-near. This prevents the
        // previous see-through/inverted-looking cube caused by drawing all six sides.
        // Pack sortable camera depth and face index into primitive longs. Sorting
        // these keys is O(n log n), avoiding the old O(n^2) nested-loop sort on
        // every frame (which became prohibitively expensive on dense edit meshes).
        long[] depthOrder=new long[drawFaces.length];
        int count=0;
        for(int f=0;f<drawFaces.length;f++){
            int[] face=drawFaces[f];
            if(face==null||face.length<3)continue;
            boolean valid=true;
            for(int id:face)if(id<0||id>=drawVertices.length){valid=false;break;}
            if(!valid)continue;
            float[] a=cameraPoint(drawVertices[face[0]][0],drawVertices[face[0]][1],drawVertices[face[0]][2]);
            float[] b=cameraPoint(drawVertices[face[1]][0],drawVertices[face[1]][1],drawVertices[face[1]][2]);
            float[] c=cameraPoint(drawVertices[face[2]][0],drawVertices[face[2]][1],drawVertices[face[2]][2]);
            float abx=b[0]-a[0], aby=b[1]-a[1], abz=b[2]-a[2];
            float acx=c[0]-a[0], acy=c[1]-a[1], acz=c[2]-a[2];
            float nx=aby*acz-abz*acy, ny=abz*acx-abx*acz, nz=abx*acy-aby*acx;
            float centerZ=0f;
            for(int id:face)centerZ+=cameraPoint(drawVertices[id][0],drawVertices[id][1],drawVertices[id][2])[2];
            centerZ/=face.length;
            // Camera looks toward negative camera-Z; visible outward normals face camera.
            boolean wireFace=!meshEditMode&&renderData.wireFaces!=null
                    &&f<renderData.wireFaces.length&&renderData.wireFaces[f];
            if(wireFace||nx*0f+ny*0f+nz > 0.0001f) {
                int bits=Float.floatToIntBits(centerZ);
                int sortable=bits^((bits>>31)&0x7fffffff);
                depthOrder[count++]=((long)sortable<<32)|(f&0xffffffffL);
            }
        }
        java.util.Arrays.sort(depthOrder,0,count);
        for(int n=0;n<count;n++){
            int f=(int)depthOrder[n]; int[] face=drawFaces[f];
            float[] ca=cameraPoint(drawVertices[face[0]][0],drawVertices[face[0]][1],drawVertices[face[0]][2]);
            float[] cb=cameraPoint(drawVertices[face[1]][0],drawVertices[face[1]][1],drawVertices[face[1]][2]);
            float[] cc=cameraPoint(drawVertices[face[2]][0],drawVertices[face[2]][1],drawVertices[face[2]][2]);
            float abx=cb[0]-ca[0],aby=cb[1]-ca[1],abz=cb[2]-ca[2];
            float acx=cc[0]-ca[0],acy=cc[1]-ca[1],acz=cc[2]-ca[2];
            float nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
            Path path=new Path();
            path.moveTo(p[face[0]][0],p[face[0]][1]);
            for(int k=1;k<face.length;k++) path.lineTo(p[face[k]][0],p[face[k]][1]);
            path.close();
            if(!meshEditMode&&renderData.wireFaces!=null&&f<renderData.wireFaces.length&&renderData.wireFaces[f]){
                int displayColor=f<renderData.displayColors.length?renderData.displayColors[f]:0xFFFFFFFF;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((renderData.activeFaces[f]?2.2f:1.35f)*getResources().getDisplayMetrics().density);
                paint.setColor(renderData.activeFaces[f]?0xFFFFB34D:displayColor);
                canvas.drawPath(path,paint);
                paint.setStyle(Paint.Style.FILL);
                continue;
            }
            int side = Math.min(FACE_COLORS.length-1,f / (MESH_SUBDIVISIONS * MESH_SUBDIVISIONS));
            int base = FACE_COLORS[side];
            int shaded = meshEditMode && document.isFaceSelected(f) ? 0xFFFFA52B : blendColor(base, meshTint, 0.48f);
            if(!meshEditMode && f<renderData.activeFaces.length && renderData.activeFaces[f]) shaded=blendColor(shaded,0xFFFFB34D,0.20f);
            if(hasActiveTexture)shaded=blendColor(shaded,document.sampleFaceTextureColor(f),0.72f);
            if(meshEditMode&&sculpting&&vertexMask.length==vertices.length){
                float faceMask=0;for(int id:face)faceMask+=vertexMask[id]/face.length;
                if(faceMask>0.001f)shaded=blendColor(shaded,0xFF7B3446,Math.min(.72f,faceMask*.72f));
            }
            if(!(meshEditMode&&document.isFaceSelected(f))){
                float wx=0f,wy=0f,wz=0f;
                for(int id:face){wx+=drawVertices[id][0]/face.length;wy+=drawVertices[id][1]/face.length;wz+=drawVertices[id][2]/face.length;}
                float[] sceneLight=meshEditMode?new float[]{viewportLight[0],viewportLight[1],viewportLight[2],1f,1f,1f}
                        :sceneLightForPoint(wx,wy,wz);
                shaded=tintBySceneLight(shaded,sceneLight);
                shaded=shadeViewportColor(shaded,nx,ny,nz,sceneLight);
            }
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
                for(int i=0;i<drawVertices.length;i++){
                    paint.setColor(document.isVertexSelected(i)?0xFFFFA52B:0xFFDBE2E9);
                    canvas.drawCircle(p[i][0],p[i][1],(document.isVertexSelected(i)?4.5f:2.1f)*density,paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        if(polyBuildMode&&!polyBuildPoints.isEmpty()){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*getResources().getDisplayMetrics().density);paint.setColor(0xFFFFC04A);
            for(int i=1;i<polyBuildPoints.size();i++){float[] a=p[polyBuildPoints.get(i-1)],b=p[polyBuildPoints.get(i)];canvas.drawLine(a[0],a[1],b[0],b[1],paint);}
            paint.setStyle(Paint.Style.FILL);
        }
        if(sculpting&&sculptCursorVisible){
            float d=getResources().getDisplayMetrics().density;
            float radius=Math.max(4f,Math.min(width,height)*sculptRadius*.55f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);paint.setColor(0xFFF2F4F7);
            canvas.drawCircle(sculptCursorX,sculptCursorY,radius,paint);
            paint.setStrokeWidth(1f*d);paint.setColor(0x887E8792);
            canvas.drawCircle(sculptCursorX,sculptCursorY,radius*(1f-sculptStrength*.35f),paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if(measurementHasStart) {
            float d=getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f*d);
            paint.setColor(0xFF52D8D0);
            if(measurementHasEnd) {
                canvas.drawLine(measurementStartX,measurementStartY,measurementEndX,measurementEndY,paint);
                canvas.drawCircle(measurementStartX,measurementStartY,4f*d,paint);
                canvas.drawCircle(measurementEndX,measurementEndY,4f*d,paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(11f*d);
                paint.setColor(0xFFE8FFFF);
                canvas.drawText(String.format(java.util.Locale.US,"≈ %.3f units",measurementDistance),
                        (measurementStartX+measurementEndX)*.5f+6f*d,
                        (measurementStartY+measurementEndY)*.5f-6f*d,paint);
            } else {
                canvas.drawCircle(measurementStartX,measurementStartY,5f*d,paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        drawSceneObjectMarkers(canvas,cx,cy,unit);
        drawSceneCursor(canvas,cx,cy,unit);
        if(objectBoxSelecting){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*getResources().getDisplayMetrics().density);
            paint.setColor(0xFF63A9F2);
            canvas.drawRect(Math.min(downX,objectBoxEndX),Math.min(downY,objectBoxEndY),
                    Math.max(downX,objectBoxEndX),Math.max(downY,objectBoxEndY),paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if(modifierEvaluationWarning!=null){
            float d=getResources().getDisplayMetrics().density;
            paint.setColor(0xFFFFB06A);paint.setTextSize(10f*d);paint.setStyle(Paint.Style.FILL);
            String warning="Modifier: "+modifierEvaluationWarning;
            while(paint.measureText(warning)>width-24f*d&&warning.length()>12)warning=warning.substring(0,warning.length()-4)+"…";
            canvas.drawText(warning,12f*d,height-12f*d,paint);
        }
        if(!meshEditMode&&objectSelectionEnabled&&objectTransformTool>=0)drawObjectTransformGizmo(canvas,cx,cy,unit);
        drawNavigationGizmo(canvas, width, 18f*getResources().getDisplayMetrics().density);
    }

    private void drawSceneObjectMarkers(Canvas canvas,float cx,float cy,float unit) {
        float density=getResources().getDisplayMetrics().density;
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()) {
            if(object.mesh!=null||!objectScene.isVisibleInViewport(object)
                    ||(localViewActive&&!localViewObjectIds.contains(object.id)))continue;
            int color=object.selected?0xFFFFA23A:0xFFBFC7D0;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((object.selected?2.0f:1.45f)*density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if("CAMERA".equals(object.type)) {
                // Blender-like camera object: small camera housing plus a
                // rectangular perspective frustum oriented by the object's transform.
                float[][] local={
                    {-0.34f,-0.23f,0.12f},{0.34f,-0.23f,0.12f},
                    {0.34f,0.23f,0.12f},{-0.34f,0.23f,0.12f},
                    {-0.24f,-0.17f,-0.22f},{0.24f,-0.17f,-0.22f},
                    {0.24f,0.17f,-0.22f},{-0.24f,0.17f,-0.22f},
                    {-0.82f,-0.58f,-1.65f},{0.82f,-0.58f,-1.65f},
                    {0.82f,0.58f,-1.65f},{-0.82f,0.58f,-1.65f}
                };
                float[][] screen=new float[local.length][];
                for(int i=0;i<local.length;i++){
                    float[] w=objectScene.transformPoint(object.id,local[i][0],local[i][1],local[i][2]);
                    screen[i]=project(w[0],w[1],w[2],cx,cy,unit);
                }
                // Rear camera housing.
                Path housing=new Path();
                housing.moveTo(screen[0][0],screen[0][1]);
                for(int i=1;i<4;i++)housing.lineTo(screen[i][0],screen[i][1]);
                housing.close();canvas.drawPath(housing,paint);
                // Lens barrel.
                for(int i=0;i<4;i++)canvas.drawLine(screen[i][0],screen[i][1],
                        screen[4+i][0],screen[4+i][1],paint);
                Path lens=new Path();
                lens.moveTo(screen[4][0],screen[4][1]);
                for(int i=5;i<8;i++)lens.lineTo(screen[i][0],screen[i][1]);
                lens.close();canvas.drawPath(lens,paint);
                // Camera view frustum.
                Path frame=new Path();
                frame.moveTo(screen[8][0],screen[8][1]);
                for(int i=9;i<12;i++)frame.lineTo(screen[i][0],screen[i][1]);
                frame.close();canvas.drawPath(frame,paint);
                for(int i=0;i<4;i++)canvas.drawLine(screen[4+i][0],screen[4+i][1],
                        screen[8+i][0],screen[8+i][1],paint);
                // Small top housing detail.
                canvas.drawLine(screen[0][0],screen[0][1],screen[1][0],screen[1][1],paint);
            } else if("LIGHT".equals(object.type)) {
                // Blender-style point-light marker: luminous centre and radial rays.
                float[] world=objectScene.transformPoint(object.id,0,0,0);
                float[] center=project(world[0],world[1],world[2],cx,cy,unit);
                float radius=7.0f*density;
                paint.setStrokeWidth((object.selected?2.0f:1.4f)*density);
                canvas.drawCircle(center[0],center[1],radius,paint);
                canvas.drawCircle(center[0],center[1],2.0f*density,paint);
                for(int i=0;i<8;i++){
                    double a=Math.PI*i/4.0;
                    float inner=radius+3.0f*density,outer=radius+8.0f*density;
                    canvas.drawLine(center[0]+(float)Math.cos(a)*inner,
                            center[1]+(float)Math.sin(a)*inner,
                            center[0]+(float)Math.cos(a)*outer,
                            center[1]+(float)Math.sin(a)*outer,paint);
                }
            } else {
                float[] world=objectScene.transformPoint(object.id,0,0,0);
                float[] screen=project(world[0],world[1],world[2],cx,cy,unit);
                float radius=Math.max(5f,object.displaySize*9f*density);
                canvas.drawLine(screen[0]-radius,screen[1],screen[0]+radius,screen[1],paint);
                canvas.drawLine(screen[0],screen[1]-radius,screen[0],screen[1]+radius,paint);
                canvas.drawCircle(screen[0],screen[1],radius*.3f,paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(10f*density);
                paint.setColor(color);
                canvas.drawText(object.name,screen[0]+radius+3f*density,screen[1]-radius-2f*density,paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawSceneCursor(Canvas canvas,float cx,float cy,float unit) {
        float[] cursor=objectScene.getCursorLocation();
        float[] screen=project(cursor[0],cursor[1],cursor[2],cx,cy,unit);
        float d=getResources().getDisplayMetrics().density,r=9f*d;
        paint.setColor(0xFFFFC84A);paint.setStrokeWidth(2f*d);paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(screen[0],screen[1],r,paint);
        canvas.drawLine(screen[0]-r*1.7f,screen[1],screen[0]+r*1.7f,screen[1],paint);
        canvas.drawLine(screen[0],screen[1]-r*1.7f,screen[0],screen[1]+r*1.7f,paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawObjectTransformGizmo(Canvas canvas,float cx,float cy,float unit) {
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active==null)return;
        float[] origin=objectScene.transformPoint(active.id,0,0,0);
        float[] center=project(origin[0],origin[1],origin[2],cx,cy,unit);
        float density=getResources().getDisplayMetrics().density;
        float handleLength=76f*density;
        float[][] axes={{1,0,0},{0,1,0},{0,0,1}};
        int[] colors={0xFFE45C5C,0xFF5CBD83,0xFF6AA7F5};
        String[] names={"X","Y","Z"};
        paint.setTextSize(12f*density);
        for(int axis=0;axis<3;axis++){
            float[] direction=gizmoAxisDirection(active,axis);
            float[] end=project(origin[0]+direction[0],origin[1]+direction[1],origin[2]+direction[2],cx,cy,unit);
            float dx=end[0]-center[0],dy=end[1]-center[1],len=(float)Math.sqrt(dx*dx+dy*dy);
            if(len<1e-4f)continue;
            dx=dx/len*handleLength;dy=dy/len*handleLength;
            float ex=center[0]+dx,ey=center[1]+dy;
            paint.setColor(colors[axis]);paint.setStrokeWidth(3.3f*density);paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(center[0],center[1],ex,ey,paint);
            paint.setStyle(Paint.Style.FILL);
            if(objectTransformTool==2){
                canvas.drawRect(ex-5*density,ey-5*density,ex+5*density,ey+5*density,paint);
            }else{
                float bx=ex-dx*.16f,by=ey-dy*.16f;
                float px=-dy*.13f,py=dx*.13f;
                Path arrow=new Path();arrow.moveTo(ex,ey);arrow.lineTo(bx+px,by+py);arrow.lineTo(bx-px,by-py);arrow.close();
                canvas.drawPath(arrow,paint);
            }
            canvas.drawText(names[axis],ex+5*density,ey-5*density,paint);
        }
        if(objectTransformTool==1){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*density);paint.setColor(0xFFE2E5E9);
            canvas.drawCircle(center[0],center[1],handleLength*.72f,paint);
        } else if(objectTransformTool==2) {
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xFFF1F3F5);
            canvas.drawCircle(center[0],center[1],7f*density,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*density);
            canvas.drawCircle(center[0],center[1],11f*density,paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private boolean beginObjectGizmoDrag(float x,float y) {
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(active==null)return false;
        float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.min(getWidth(),getHeight())*.235f*zoom;
        float[] world=objectScene.transformPoint(active.id,0,0,0);
        float[] center=project(world[0],world[1],world[2],cx,cy,unit);
        float density=getResources().getDisplayMetrics().density;
        float handleLength=76f*density,hitWidth=18f*density;
        float[][] axes={{1,0,0},{0,1,0},{0,0,1}};
        float nearest=Float.MAX_VALUE;int selectedAxis=-1;float selectedX=0,selectedY=0;
        objectGizmoUniformScale=false;
        if(objectTransformTool==2
                &&Math.hypot(x-center[0],y-center[1])<=16f*density) {
            selectedAxis=3;selectedX=1f;selectedY=0f;objectGizmoUniformScale=true;
        }
        for(int axis=0;axis<3&&selectedAxis<0;axis++){
            float[] direction=gizmoAxisDirection(active,axis);
            float[] end=project(world[0]+direction[0],world[1]+direction[1],world[2]+direction[2],cx,cy,unit);
            float vx=end[0]-center[0],vy=end[1]-center[1],len=(float)Math.sqrt(vx*vx+vy*vy);
            if(len<1e-4f)continue;
            vx=vx/len*handleLength;vy=vy/len*handleLength;
            float t=((x-center[0])*vx+(y-center[1])*vy)/(handleLength*handleLength);
            t=Math.max(0f,Math.min(1f,t));
            float dx=x-(center[0]+vx*t),dy=y-(center[1]+vy*t);
            float distance=(float)Math.sqrt(dx*dx+dy*dy);
            if(distance<hitWidth&&distance<nearest){nearest=distance;selectedAxis=axis;selectedX=vx/handleLength;selectedY=vy/handleLength;}
        }
        objectGizmoRotationRing=false;
        if(objectTransformTool==1&&selectedAxis<0){
            float dx=x-center[0],dy=y-center[1],rad=(float)Math.sqrt(dx*dx+dy*dy);
            if(Math.abs(rad-handleLength*.72f)<hitWidth){
                selectedAxis=2;selectedX=1f;selectedY=0f;objectGizmoRotationRing=true;
                gizmoStartWorldX=(float)Math.atan2(dy,dx);
            }
        }
        if(selectedAxis<0)return false;
        gizmoObjectIds.clear();gizmoInitialTransforms.clear();gizmoInitialWorldLocations.clear();gizmoInitialWorldMatrices.clear();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> transformTargets=
                new java.util.ArrayList<>(objectScene.getSelectedObjects());
        if(transformTargets.isEmpty())transformTargets.add(active);
        transformTargets.sort((a,b)->Integer.compare(
                objectScene.getObjectHierarchyDepth(a.id),objectScene.getObjectHierarchyDepth(b.id)));
        for(Meshy3DObjectScene.Object3D object:transformTargets){
            gizmoObjectIds.add(object.id);gizmoInitialTransforms.add(object.transform.copy());
            gizmoInitialWorldLocations.add(objectScene.worldLocation(object.id));
            gizmoInitialWorldMatrices.add(objectScene.getWorldMatrixCopy(object.id));
        }
        objectGizmoAxis=selectedAxis;gizmoStartX=x;gizmoStartY=y;
        gizmoAxisScreenX=selectedX;gizmoAxisScreenY=selectedY;
        if(objectTransformTool==1&&objectGizmoRotationRing)
            gizmoStartWorldX=(float)Math.atan2(y-center[1],x-center[0]);
        objectGizmoDragging=true;moved=true;
        if(onObjectTransformStart!=null)onObjectTransformStart.run();
        return true;
    }

    private void updateObjectGizmoDrag(float x,float y) {
        float density=getResources().getDisplayMetrics().density;
        float dx=x-gizmoStartX,dy=y-gizmoStartY;
        float along=dx*gizmoAxisScreenX+dy*gizmoAxisScreenY;
        float perpendicular=dx*(-gizmoAxisScreenY)+dy*gizmoAxisScreenX;
        float amount=along/Math.max(1f,76f*density);
        float rotation=perpendicular*1.5f;
        Meshy3DObjectScene.Object3D active=objectScene.getObject(objectSceneActiveId);
        if(objectTransformTool==1&&objectGizmoRotationRing&&active!=null){
            float cx=getWidth()*.5f,cy=getHeight()*.49f,unit=Math.min(getWidth(),getHeight())*.235f*zoom;
            float[] world=objectScene.transformPoint(active.id,0,0,0);
            float[] center=project(world[0],world[1],world[2],cx,cy,unit);
            float now=(float)Math.atan2(y-center[1],x-center[0]);
            rotation=(float)Math.toDegrees(now-gizmoStartWorldX);
        }
        float[] axis=active==null?new float[]{0,0,1}:gizmoAxisDirection(active,objectGizmoAxis);
        if(objectTransformTool==0){
            for(int i=0;i<gizmoObjectIds.size();i++){
                String id=gizmoObjectIds.get(i);float[] initial=gizmoInitialWorldLocations.get(i);
                objectScene.setWorldLocation(id,initial[0]+axis[0]*amount,
                        initial[1]+axis[1]*amount,initial[2]+axis[2]*amount);
            }
        }else{
            double factor=Math.max(.001f,1f+amount);
            double[] localDelta=objectTransformTool==1
                    ?rotationAroundAxisMatrix(axis,Math.toRadians(rotation))
                    :objectGizmoUniformScale
                    ?uniformScaleMatrix(factor)
                    :scaleAlongAxisMatrix(axis,factor);
            double[] delta=pivotedWorldDelta(localDelta);
            for(int i=0;i<gizmoObjectIds.size();i++){
                String id=gizmoObjectIds.get(i);
                double[] initial=gizmoInitialWorldMatrices.get(i);
                double[] objectDelta=delta;
                if(objectPivotMode==3){
                    float[] origin=gizmoInitialWorldLocations.get(i);
                    objectDelta=multiplyMatrix4(translationMatrix4(origin[0],origin[1],origin[2]),
                            multiplyMatrix4(localDelta,translationMatrix4(-origin[0],-origin[1],-origin[2])));
                }
                objectScene.setWorldMatrix(id,multiplyMatrix4(objectDelta,initial));
            }
        }
        invalidate();
    }

    private void drawNavigationGizmo(Canvas canvas,float width,float margin) {
        float d=getResources().getDisplayMetrics().density;
        float r=Math.max(7*d,Math.min(12*d,getHeight()*.08f));
        float gx=width-34*d, gy=Math.max(r+4*d,Math.min(48*d,getHeight()*.22f));
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF20252C);
        canvas.drawCircle(gx,gy,r+8*d,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1*d);
        paint.setColor(0xFF65717D); canvas.drawCircle(gx,gy,r,paint);
        float[][] axes={{1,0,0},{0,1,0},{0,0,1}};
        int[] colors={0xFFE35D5D,0xFF59B77C,0xFF669BE8};
        String[] labels={"X","Y","Z"};
        for(int i=0;i<3;i++){
            float[] q=cameraDirectionPoint(axes[i][0],axes[i][1],axes[i][2]);
            float ex=gx+q[0]*r*0.88f, ey=gy-q[1]*r*0.88f;
            paint.setColor(colors[i]); paint.setStrokeWidth(3*d);
            canvas.drawLine(gx,gy,ex,ey,paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(ex,ey,4.5f*d,paint);
            paint.setTextSize(10*d); canvas.drawText(labels[i],ex+4*d,ey-3*d,paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private float[] sceneLightForPoint(float x,float y,float z) {
        // Keep a small ambient/directional fill so unlit areas remain readable.
        double sx=-.45*.22,sy=.82*.22,sz=.65*.22,total=.22;
        double red=.22,green=.22,blue=.22;
        for(Meshy3DObjectScene.Object3D light:objectScene.getObjects()) {
            if(!"LIGHT".equals(light.type)||!objectScene.isVisibleInRender(light)||light.lightPower<=0f)continue;
            float[] origin=objectScene.transformPoint(light.id,0,0,0);
            double dx,dy,dz,weight;
            String kind=light.lightType==null?"POINT":light.lightType.toUpperCase(java.util.Locale.ROOT);
            if("SUN".equals(kind)) {
                float[] directionPoint=objectScene.transformPoint(light.id,0,0,-1);
                dx=directionPoint[0]-origin[0];dy=directionPoint[1]-origin[1];dz=directionPoint[2]-origin[2];
                weight=Math.min(4.0,light.lightPower/1000.0);
            } else {
                dx=origin[0]-x;dy=origin[1]-y;dz=origin[2]-z;
                double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
                if(distance<1e-5)continue;
                weight=(light.lightPower/1000.0)/(1.0+.03*distance*distance);
                if("AREA".equals(kind))weight*=1.35;
                if("SPOT".equals(kind)) {
                    float[] axisPoint=objectScene.transformPoint(light.id,0,0,-1);
                    double ax=axisPoint[0]-origin[0],ay=axisPoint[1]-origin[1],az=axisPoint[2]-origin[2];
                    double axisLength=Math.sqrt(ax*ax+ay*ay+az*az);
                    double toX=-dx/distance,toY=-dy/distance,toZ=-dz/distance;
                    if(axisLength<1e-6)continue;
                    double cone=(ax*toX+ay*toY+az*toZ)/axisLength;
                    double limit=Math.cos(Math.toRadians(Math.max(1f,light.lightAngle)*.5));
                    if(cone<limit)continue;
                    weight*=Math.max(.08,Math.min(1.0,(cone-limit)/Math.max(.001,1-limit)));
                }
                dx/=distance;dy/=distance;dz/=distance;
            }
            double directionLength=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(directionLength<1e-6||weight<=0)continue;
            dx/=directionLength;dy/=directionLength;dz/=directionLength;
            sx+=dx*weight;sy+=dy*weight;sz+=dz*weight;total+=weight;
            red+=((light.lightColor>>16)&255)/255.0*weight;
            green+=((light.lightColor>>8)&255)/255.0*weight;
            blue+=(light.lightColor&255)/255.0*weight;
        }
        double length=Math.sqrt(sx*sx+sy*sy+sz*sz);
        if(length<1e-6)length=1;
        float[] camera=cameraDirectionPoint((float)(sx/length),(float)(sy/length),(float)(sz/length));
        return new float[]{camera[0],camera[1],camera[2],
                (float)Math.max(.15,Math.min(1.5,red/total)),
                (float)Math.max(.15,Math.min(1.5,green/total)),
                (float)Math.max(.15,Math.min(1.5,blue/total))};
    }

    private int tintBySceneLight(int color,float[] light) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        r=Math.max(0,Math.min(255,Math.round(r*light[3])));
        g=Math.max(0,Math.min(255,Math.round(g*light[4])));
        b=Math.max(0,Math.min(255,Math.round(b*light[5])));
        return (color&0xFF000000)|(r<<16)|(g<<8)|b;
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

    private int virtualAxisConstraint = -1;

    /** Phone input API: all virtual controls manipulate the same viewport state as touch gestures. */
    public void orbitBy(float dx, float dy) {
        cameraViewActive=false;
        float density=getResources().getDisplayMetrics().density;
        yaw-=dx*0.008f/Math.max(.75f,density);
        pitch=Math.max(-1.35f,Math.min(1.35f,pitch+dy*0.008f/Math.max(.75f,density)));
        invalidate();
    }

    public void panByPixels(float dx, float dy) {
        cameraViewActive=false;
        float unit=Math.max(1f,Math.min(getWidth(),getHeight())*.235f*zoom);
        float[] delta=cameraVectorToWorld(-dx/unit,dy/unit,0f);
        viewTargetX+=delta[0];viewTargetY+=delta[1];viewTargetZ+=delta[2];
        invalidate();
    }

    public void zoomBy(float factor) {
        if(!Float.isFinite(factor)||factor<=0f)return;
        cameraViewActive=false;
        zoom=Math.max(.015f,Math.min(5.0f,zoom*factor));
        invalidate();
    }

    /** Front, back, right, left, top and bottom orthographic-style view presets. */
    public void setViewPreset(int preset) {
        cameraViewActive=false;viewRoll=0f;
        switch(preset) {
            case 0: yaw=0f;pitch=0f;break;
            case 1: yaw=(float)Math.PI;pitch=0f;break;
            case 2: yaw=(float)(Math.PI*.5);pitch=0f;break;
            case 3: yaw=(float)(-Math.PI*.5);pitch=0f;break;
            case 4: yaw=0f;pitch=1.35f;break;
            case 5: yaw=0f;pitch=-1.35f;break;
            default:return;
        }
        invalidate();
    }

    /** Emulates the primary virtual-mouse click at the viewport centre. */
    public void virtualLeftClick() {
        float x=getWidth()*.5f,y=getHeight()*.49f;
        if(meshEditMode)selectAt(x,y,false);
        else if(objectSelectionEnabled)selectObjectAt(x,y,false);
        performClick();
    }

    public void setKeyboardAxisConstraint(int axis) {
        virtualAxisConstraint = axis < 0 || axis > 2 ? -1 : axis;
    }

    public int getKeyboardAxisConstraint() { return virtualAxisConstraint; }

    /** Applies a typed value using the active G/R/S operation and selected axis constraint. */
    public void applyNumericTransform(int axis,float amount) {
        if(axis == -1) axis = virtualAxisConstraint >= 0 ? virtualAxisConstraint : 0;
        if(axis<0||axis>2||!Float.isFinite(amount))throw new IllegalArgumentException("Invalid axis or value");
        int operation=objectTransformTool<0?0:objectTransformTool;
        if(meshEditMode) {
            float x=axis==0?amount:operation==2?1f:0f;
            float y=axis==1?amount:operation==2?1f:0f;
            float z=axis==2?amount:operation==2?1f:0f;
            if(operation==2){x=axis==0?amount:1f;y=axis==1?amount:1f;z=axis==2?amount:1f;}
            document.transformSelected(x,y,z,operation);
            syncViewportFromCore();
        } else {
            float x=axis==0?amount:operation==2?1f:0f;
            float y=axis==1?amount:operation==2?1f:0f;
            float z=axis==2?amount:operation==2?1f:0f;
            if(operation==2){x=axis==0?amount:1f;y=axis==1?amount:1f;z=axis==2?amount:1f;}
            if(operation==1){x=axis==0?amount:0f;y=axis==1?amount:0f;z=axis==2?amount:0f;}
            transformActiveObject(operation,x,y,z);
        }
        invalidate();
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

    /** Store both active-mesh shape data and every scene object's transform. */
    public void storeMeshKeyframe(int frame){
        int key=Math.max(0,frame);
        float[][] snapshot=new float[vertices.length][3];
        for(int i=0;i<vertices.length;i++)System.arraycopy(vertices[i],0,snapshot[i],0,3);
        meshKeyframes.put(key,snapshot);
        java.util.HashMap<String,Meshy3DObjectScene.Transform> transforms=new java.util.HashMap<>();
        for(Meshy3DObjectScene.Object3D object:objectScene.getObjects())
            transforms.put(object.id,object.transform.copy());
        sceneTransformKeyframes.put(key,transforms);
    }

    public void deleteMeshKeyframe(int frame){
        int key=Math.max(0,frame);
        meshKeyframes.remove(key);
        sceneTransformKeyframes.remove(key);
    }

    public int getMeshKeyframeCount(){return getMeshKeyframeFrames().size();}

    public java.util.ArrayList<Integer> getMeshKeyframeFrames(){
        java.util.TreeSet<Integer> unique=new java.util.TreeSet<>();
        unique.addAll(meshKeyframes.keySet());
        unique.addAll(sceneTransformKeyframes.keySet());
        return new java.util.ArrayList<>(unique);
    }

    public void setAnimationInterpolationMode(int mode) {
        animationInterpolationMode = Math.max(0, Math.min(2, mode));
    }
    public int getAnimationInterpolationMode() { return animationInterpolationMode; }

    /** Duplicate a stored shape-and-transform keyframe to another frame. */
    public boolean duplicateMeshKeyframe(int sourceFrame,int targetFrame) {
        int source=Math.max(0,sourceFrame),target=Math.max(0,targetFrame);
        if(source==target)return false;
        boolean copied=false;
        float[][] geometry=meshKeyframes.get(source);
        if(geometry!=null){
            float[][] snapshot=new float[geometry.length][3];
            for(int i=0;i<geometry.length;i++)System.arraycopy(geometry[i],0,snapshot[i],0,3);
            meshKeyframes.put(target,snapshot);copied=true;
        }
        java.util.HashMap<String,Meshy3DObjectScene.Transform> sourceTransforms=sceneTransformKeyframes.get(source);
        if(sourceTransforms!=null){
            java.util.HashMap<String,Meshy3DObjectScene.Transform> transforms=new java.util.HashMap<>();
            for(java.util.Map.Entry<String,Meshy3DObjectScene.Transform> entry:sourceTransforms.entrySet())
                transforms.put(entry.getKey(),entry.getValue().copy());
            sceneTransformKeyframes.put(target,transforms);copied=true;
        }
        invalidate();
        return copied;
    }

    /** Duplicate all keyed scene/mesh states reflected around the supplied frame. */
    public int mirrorMeshKeyframesAround(int centerFrame) {
        java.util.HashMap<Integer,float[][]> meshCopies=new java.util.HashMap<>();
        java.util.HashMap<Integer,java.util.HashMap<String,Meshy3DObjectScene.Transform>> transformCopies=new java.util.HashMap<>();
        for(Integer source:new java.util.ArrayList<>(meshKeyframes.keySet())){
            int target=2*centerFrame-source;
            if(target<1||target>250||target==source)continue;
            float[][] data=meshKeyframes.get(source);
            float[][] copy=new float[data.length][3];
            for(int i=0;i<data.length;i++)System.arraycopy(data[i],0,copy[i],0,3);
            meshCopies.put(target,copy);
        }
        for(java.util.Map.Entry<Integer,java.util.HashMap<String,Meshy3DObjectScene.Transform>> entry:
                new java.util.ArrayList<>(sceneTransformKeyframes.entrySet())){
            int target=2*centerFrame-entry.getKey();
            if(target<1||target>250||target==entry.getKey())continue;
            java.util.HashMap<String,Meshy3DObjectScene.Transform> copy=new java.util.HashMap<>();
            for(java.util.Map.Entry<String,Meshy3DObjectScene.Transform> object:entry.getValue().entrySet())
                copy.put(object.getKey(),object.getValue().copy());
            transformCopies.put(target,copy);
        }
        meshKeyframes.putAll(meshCopies);
        sceneTransformKeyframes.putAll(transformCopies);
        invalidate();
        return Math.max(meshCopies.size(),transformCopies.size());
    }

    /** Interpolates mesh shape and location/rotation/scale for every keyed object. */
    public void applyAnimationFrame(float frame){
        java.util.ArrayList<Integer> keys=getMeshKeyframeFrames();
        if(keys.isEmpty())return;
        int before=keys.get(0),after=keys.get(keys.size()-1);
        for(int k:keys){if(k<=frame)before=k;if(k>=frame){after=k;break;}}
        float raw=after==before?0f:Math.max(0f,Math.min(1f,(frame-before)/(float)(after-before)));
        float t;
        if(animationInterpolationMode==1)t=raw;
        else if(animationInterpolationMode==2)t=raw>=1f?1f:0f;
        else t=raw*raw*(3f-2f*raw);

        float[][] a=meshKeyframes.get(before),b=meshKeyframes.get(after);
        if(a!=null&&b!=null&&a.length==vertices.length&&b.length==vertices.length){
            for(int i=0;i<vertices.length;i++)for(int axis=0;axis<3;axis++)
                vertices[i][axis]=a[i][axis]+(b[i][axis]-a[i][axis])*t;
            syncCoreFromViewport();
        }

        java.util.HashMap<String,Meshy3DObjectScene.Transform> ta=sceneTransformKeyframes.get(before);
        java.util.HashMap<String,Meshy3DObjectScene.Transform> tb=sceneTransformKeyframes.get(after);
        if(ta!=null||tb!=null){
            for(Meshy3DObjectScene.Object3D object:objectScene.getObjects()){
                Meshy3DObjectScene.Transform start=ta==null?null:ta.get(object.id);
                Meshy3DObjectScene.Transform end=tb==null?null:tb.get(object.id);
                if(start==null&&end==null)continue;
                if(start==null)start=end;
                if(end==null)end=start;
                Meshy3DObjectScene.Transform value=new Meshy3DObjectScene.Transform();
                value.x=start.x+(end.x-start.x)*t;
                value.y=start.y+(end.y-start.y)*t;
                value.z=start.z+(end.z-start.z)*t;
                value.rotationX=start.rotationX+(end.rotationX-start.rotationX)*t;
                value.rotationY=start.rotationY+(end.rotationY-start.rotationY)*t;
                value.rotationZ=start.rotationZ+(end.rotationZ-start.rotationZ)*t;
                value.scaleX=start.scaleX+(end.scaleX-start.scaleX)*t;
                value.scaleY=start.scaleY+(end.scaleY-start.scaleY)*t;
                value.scaleZ=start.scaleZ+(end.scaleZ-start.scaleZ)*t;
                objectScene.setTransform(object.id,value);
            }
        }
        invalidate();
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
    public boolean isFloorGridVisible(){return showFloorGrid;}
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

    public void setSculptStrokeMethod(int method){sculptStrokeMethod=Math.max(0,Math.min(2,method));}

    public void setSculptSettings(boolean enabled, int brush, float radius, float strength,
                                   float falloff, int symmetry, boolean subtract, float pressure) {
        sculpting = enabled;
        if (enabled) {
            meshEditMode = false;
            objectSelectionEnabled = false;
            objectTransformTool = -1;
            objectGizmoDragging = false;
        }
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
            float[] cn=cameraDirectionPoint(normals[i][0],normals[i][1],normals[i][2]);
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
        if(sculptStrokeMethod==1){applySculptDab(x,y,dx,dy,pressure);return;}
        float spacing=sculptStrokeMethod==0?.12f:.25f;
        float step=Math.max(2f,Math.min(getWidth(),getHeight())*sculptRadius*.55f*spacing);
        int count=Math.max(1,(int)Math.ceil(Math.hypot(dx,dy)/step));
        for(int i=1;i<=count;i++)applySculptDab(x-dx+dx*i/count,y-dy+dy*i/count,dx/count,dy/count,pressure);
    }
    private void applySculptDab(float x,float y,float dragX,float dragY,float eventPressure){
        if(vertices.length==0||getWidth()<=0||getHeight()<=0)return;
        sculptCursorX=x;sculptCursorY=y;sculptCursorVisible=true;
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
            float[] cameraNormal=cameraDirectionPoint(normal[0],normal[1],normal[2]);
            if(cameraNormal[2]<(sculptBrush==18?-0.05f:0.015f))continue;
            float t=Math.max(0,1-distance/brushPx);
            float falloff=(float)Math.pow(t,0.5f+sculptFalloff*2.5f);
            float mask=i<vertexMask.length?Math.max(0,Math.min(1,vertexMask[i])):0;
            float weight=falloff*(sculptBrush==11?mask:1-mask);
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
                if(measureMode) {
                    lastX=event.getX();lastY=event.getY();
                    return true;
                }
                if(!sculpting&&!meshEditMode&&objectSelectionEnabled&&objectBoxSelectEnabled){
                    objectBoxSelecting=true;objectBoxEndX=downX;objectBoxEndY=downY;invalidate();return true;
                }
                if(!sculpting&&!meshEditMode&&objectSelectionEnabled&&objectTransformTool>=0
                        &&beginObjectGizmoDrag(event.getX(),event.getY()))return true;
                if(sculpting){
                    if(onSculptStrokeStart!=null)onSculptStrokeStart.run();
                    strokeActive=true;
                    strokeX=previousStrokeX=event.getX();
                    strokeY=previousStrokeY=event.getY();
                    beginSculptStroke(strokeX,strokeY);
                    applySculptDab(strokeX,strokeY,0f,0f,event.getPressure());
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastPinchDistance=pointerDistance(event);
                if(event.getPointerCount()>=2){
                    lastPinchCenterX=(event.getX(0)+event.getX(1))*.5f;
                    lastPinchCenterY=(event.getY(0)+event.getY(1))*.5f;
                }
                moved=true; return true;
            case MotionEvent.ACTION_MOVE:
                if(measureMode) {
                    if(measurementHasStart) {
                        measurementEndX=event.getX();measurementEndY=event.getY();
                        measurementHasEnd=true;
                        float unit=Math.max(1f,Math.min(getWidth(),getHeight())*.235f*zoom);
                        measurementDistance=(float)Math.hypot(measurementEndX-measurementStartX,
                                measurementEndY-measurementStartY)/unit;
                    }
                    invalidate();
                    return true;
                }
                if(cameraViewActive&&(Math.abs(event.getX()-downX)+Math.abs(event.getY()-downY)>4*d))cameraViewActive=false;
                if(objectBoxSelecting&&event.getPointerCount()==1){
                    objectBoxEndX=event.getX();objectBoxEndY=event.getY();
                    if(Math.abs(objectBoxEndX-downX)+Math.abs(objectBoxEndY-downY)>4*d)moved=true;
                    invalidate();return true;
                }
                if(objectGizmoDragging&&event.getPointerCount()==1){
                    updateObjectGizmoDrag(event.getX(),event.getY());return true;
                }
                if(sculpting && strokeActive && event.getPointerCount()==1){
                    float sx=event.getX(), sy=event.getY();
                    applySculptSegment(sx,sy,sx-previousStrokeX,sy-previousStrokeY,event.getPressure());
                    previousStrokeX=sx; previousStrokeY=sy;
                    moved=true;
                    return true;
                }
                if(event.getPointerCount()>=2){
                    float distance=pointerDistance(event);
                    float centerX=(event.getX(0)+event.getX(1))*.5f;
                    float centerY=(event.getY(0)+event.getY(1))*.5f;
                    if(lastPinchDistance>0 && distance>0){
                        zoom=Math.max(0.015f,Math.min(5.0f,zoom*distance/lastPinchDistance));
                        panByPixels(centerX-lastPinchCenterX,centerY-lastPinchCenterY);
                        invalidate();
                    }
                    lastPinchDistance=distance;
                    lastPinchCenterX=centerX;lastPinchCenterY=centerY;
                } else {
                    float dx=event.getX()-lastX, dy=event.getY()-lastY;
                    if(Math.abs(event.getX()-downX)+Math.abs(event.getY()-downY)>4*d) moved=true;
                    if(viewportNavigationMode==1) panByPixels(dx,dy);
                    else {
                        yaw-=dx*0.008f;
                        pitch=Math.max(-1.35f,Math.min(1.35f,pitch+dy*0.008f));
                    }
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
                if(measureMode) {
                    if(!measurementHasStart) {
                        measurementStartX=event.getX();measurementStartY=event.getY();
                        measurementHasStart=true;measurementHasEnd=false;
                    } else {
                        measurementEndX=event.getX();measurementEndY=event.getY();
                        measurementHasEnd=true;
                        float unit=Math.max(1f,Math.min(getWidth(),getHeight())*.235f*zoom);
                        measurementDistance=(float)Math.hypot(measurementEndX-measurementStartX,
                                measurementEndY-measurementStartY)/unit;
                        measureMode=false;
                    }
                    invalidate();performClick();return true;
                }
                if(objectBoxSelecting){
                    objectBoxSelecting=false;performObjectBoxSelection(downX,downY,event.getX(),event.getY());
                    invalidate();performClick();return true;
                }
                if(objectGizmoDragging){objectGizmoDragging=false;objectGizmoAxis=-1;objectGizmoRotationRing=false;
                    gizmoObjectIds.clear();gizmoInitialTransforms.clear();gizmoInitialWorldLocations.clear();
                    gizmoInitialWorldMatrices.clear();
                    invalidate();performClick();return true;}
                if(polyBuildMode){if(!moved)addPolyBuildPoint(event.getX(),event.getY());performClick();return true;}
                if(sculpting){syncCoreFromViewport();}
                if(!moved && !sculpting){
                    float radius=Math.max(13*d,Math.min(22*d,getHeight()*.16f));
                    float gx=getWidth()-34*d, gy=Math.max(radius*.5f,Math.min(48*d,getHeight()*.22f));
                    float dx=event.getX()-gx,dy=event.getY()-gy;
                    if(dx*dx+dy*dy<=radius*radius){
                        snapToNearestAxis(dx,dy);
                    } else if(meshEditMode){
                        boolean additive=(event.getMetaState()&android.view.KeyEvent.META_SHIFT_ON)!=0
                                ||event.getEventTime()-event.getDownTime()>=450;
                        selectAt(event.getX(),event.getY(),additive);
                    }
                    else if(objectSelectionEnabled)selectObjectAt(event.getX(),event.getY(),
                            (event.getMetaState()&android.view.KeyEvent.META_SHIFT_ON)!=0
                                    ||event.getEventTime()-event.getDownTime()>=450);
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
            state.put("objectScene",objectScene.exportState());
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
            state.put("yaw", yaw); state.put("pitch", pitch); state.put("roll",viewRoll);state.put("zoom", zoom);
            state.put("viewTargetX",viewTargetX);state.put("viewTargetY",viewTargetY);state.put("viewTargetZ",viewTargetZ);
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
            org.json.JSONArray sceneKeys = new org.json.JSONArray();
            for (java.util.Map.Entry<Integer,java.util.HashMap<String,Meshy3DObjectScene.Transform>> entry
                    : sceneTransformKeyframes.entrySet()) {
                org.json.JSONObject frameData = new org.json.JSONObject();
                frameData.put("frame", entry.getKey());
                org.json.JSONArray objects = new org.json.JSONArray();
                for (java.util.Map.Entry<String,Meshy3DObjectScene.Transform> objectEntry
                        : entry.getValue().entrySet()) {
                    Meshy3DObjectScene.Transform tr = objectEntry.getValue();
                    org.json.JSONObject objectData = new org.json.JSONObject();
                    objectData.put("id", objectEntry.getKey());
                    objectData.put("x", tr.x); objectData.put("y", tr.y); objectData.put("z", tr.z);
                    objectData.put("rx", tr.rotationX); objectData.put("ry", tr.rotationY); objectData.put("rz", tr.rotationZ);
                    objectData.put("sx", tr.scaleX); objectData.put("sy", tr.scaleY); objectData.put("sz", tr.scaleZ);
                    objects.put(objectData);
                }
                frameData.put("objects", objects);
                sceneKeys.put(frameData);
            }
            state.put("sceneTransformKeyframes", sceneKeys);
            state.put("animationInterpolationMode", animationInterpolationMode);
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
            viewRoll=(float)state.optDouble("roll",viewRoll);
            zoom = Math.max(0.35f, Math.min(3.4f, (float) state.optDouble("zoom", zoom)));
            viewTargetX=(float)state.optDouble("viewTargetX",viewTargetX);
            viewTargetY=(float)state.optDouble("viewTargetY",viewTargetY);
            viewTargetZ=(float)state.optDouble("viewTargetZ",viewTargetZ);
            meshTint = state.optInt("meshTint", meshTint);
            animationInterpolationMode = Math.max(0,Math.min(2,state.optInt("animationInterpolationMode",animationInterpolationMode)));
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
            sceneTransformKeyframes.clear();
            org.json.JSONArray savedSceneKeys=state.optJSONArray("sceneTransformKeyframes");
            if(savedSceneKeys!=null)for(int i=0;i<savedSceneKeys.length();i++){
                org.json.JSONObject frameData=savedSceneKeys.getJSONObject(i);
                int frameNumber=frameData.optInt("frame",i);
                java.util.HashMap<String,Meshy3DObjectScene.Transform> transforms=new java.util.HashMap<>();
                org.json.JSONArray objects=frameData.optJSONArray("objects");
                if(objects!=null)for(int j=0;j<objects.length();j++){
                    org.json.JSONObject objectData=objects.getJSONObject(j);
                    String id=objectData.optString("id","");
                    if(id.isEmpty())continue;
                    Meshy3DObjectScene.Transform tr=new Meshy3DObjectScene.Transform();
                    tr.x=(float)objectData.optDouble("x",0);tr.y=(float)objectData.optDouble("y",0);tr.z=(float)objectData.optDouble("z",0);
                    tr.rotationX=(float)objectData.optDouble("rx",0);tr.rotationY=(float)objectData.optDouble("ry",0);tr.rotationZ=(float)objectData.optDouble("rz",0);
                    tr.scaleX=(float)objectData.optDouble("sx",1);tr.scaleY=(float)objectData.optDouble("sy",1);tr.scaleZ=(float)objectData.optDouble("sz",1);
                    transforms.put(id,tr);
                }
                sceneTransformKeyframes.put(frameNumber,transforms);
            }
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
            org.json.JSONObject savedScene=state.optJSONObject("objectScene");
            if(savedScene!=null){
                if(!objectScene.importState(savedScene))return false;
                objectSceneActiveId=objectScene.getActiveObjectId();
                if(objectSceneActiveId==null&&!objectScene.getObjects().isEmpty())
                    objectSceneActiveId=objectScene.getObjects().get(0).id;
                if(objectSceneActiveId!=null)activateSceneObject(objectSceneActiveId);
            }else{
                if(objectScene.getObjects().isEmpty()){
                    Meshy3DObjectScene.Object3D starter=objectScene.createMeshObject("Cube",document);
                    objectSceneActiveId=starter.id;
                }
                syncCoreFromViewport();
            }
            // Importing the scene reactivates its active mesh datablock. Restore the
            // active object's PNG texture after that datablock has been loaded.
            String restoredTexture=state.optString("texturePng","");
            if(!restoredTexture.isEmpty()){
                byte[] data=android.util.Base64.decode(restoredTexture,android.util.Base64.DEFAULT);
                android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeByteArray(data,0,data.length);
                if(image!=null){
                    int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];
                    image.getPixels(pixels,0,w,0,0,w,h);image.recycle();document.setTexturePixels(pixels,w,h);
                }
            }
            invalidate();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override public boolean performClick(){super.performClick();return true;}
}
