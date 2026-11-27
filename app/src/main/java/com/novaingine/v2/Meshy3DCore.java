package com.novaingine.v2;

import java.util.*;
import org.json.*;

/**
 * Shared Meshy3D document core. The mesh is a connected indexed polygon mesh;
 * workspace editors operate on this document instead of private demo copies.
 */
public final class Meshy3DCore {
    public enum SelectMode { VERTEX, EDGE, FACE }
    public static final class Edge {
        public int a, b;
        public final ArrayList<Integer> faces = new ArrayList<>();
        Edge(int a, int b) { this.a=a; this.b=b; }
    }
    public static final class Face {
        public int[] vertices;
        public boolean selected;
        Face(int... v) { vertices=v; }
    }
    public static final class UvIsland {
        public final ArrayList<Integer> faces = new ArrayList<>();
        public float minU,minV,maxU,maxV;
    }
    public static final class Key {
        public int frame;
        public float value;
        Key(int f,float v){frame=f;value=v;}
    }
    public static final class AnimationChannel {
        public String property;
        public final ArrayList<Key> keys = new ArrayList<>();
        AnimationChannel(String p){property=p;}
        public void put(int frame,float value){
            for(Key k:keys)if(k.frame==frame){k.value=value;sort();return;}
            keys.add(new Key(frame,value));sort();
        }
        private void sort(){Collections.sort(keys,(a,b)->Integer.compare(a.frame,b.frame));}
        public float evaluate(float frame){
            if(keys.isEmpty())return 0;
            if(frame<=keys.get(0).frame)return keys.get(0).value;
            if(frame>=keys.get(keys.size()-1).frame)return keys.get(keys.size()-1).value;
            for(int i=0;i<keys.size()-1;i++){
                Key a=keys.get(i),b=keys.get(i+1);
                if(frame>=a.frame&&frame<=b.frame){
                    float t=(frame-a.frame)/(float)(b.frame-a.frame);
                    // Smooth Bezier-like ease-in/ease-out interpolation.
                    t=t*t*(3f-2f*t);
                    return a.value+(b.value-a.value)*t;
                }
            }
            return keys.get(keys.size()-1).value;
        }
    }
    public static final class Node {
        public String type;
        public float x,y,z,value;
        public Node(String type){this.type=type;}
    }

    private final ArrayList<float[]> vertices = new ArrayList<>();
    private final ArrayList<Face> faces = new ArrayList<>();
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final BitSet selectedVertices = new BitSet();
    private final BitSet selectedEdges = new BitSet();
    private final BitSet selectedFaces = new BitSet();
    private final HashSet<Long> seams = new HashSet<>();
    private final HashSet<Long> pins = new HashSet<>();
    private float[][][] faceUvs;
    private final ArrayList<UvIsland> islands = new ArrayList<>();
    private int[] texturePixels;
    private int textureWidth, textureHeight;
    private float[] sculptMask=new float[0];
    private int textureColor=0xFFFFFFFF;
    private float brushRadius=16f, brushOpacity=1f;
    private final HashMap<String,AnimationChannel> animation = new HashMap<>();
    private final ArrayList<Node> geometryNodes = new ArrayList<>();
    private final ArrayList<Node> shaderNodes = new ArrayList<>();
    private final ArrayList<Node> compositorNodes = new ArrayList<>();
    private final ArrayList<int[]> geometryLinks=new ArrayList<>(),shaderLinks=new ArrayList<>(),compositorLinks=new ArrayList<>();
    private SelectMode selectMode=SelectMode.FACE;
    private float metallic, roughness=0.65f;
    private int baseColor=0xFF9DAAB7;
    private long revision;

    public Meshy3DCore(){createDenseCube(24);}
    public long getRevision(){return revision;}
    public int getVertexCount(){return vertices.size();}
    public int getEdgeCount(){return edges.size();}
    public int getFaceCount(){return faces.size();}
    public SelectMode getSelectMode(){return selectMode;}
    public void setSelectMode(SelectMode mode){selectMode=mode;clearSelection();}
    public List<Edge> getEdges(){return Collections.unmodifiableList(edges);}
    public List<Face> getFaces(){return Collections.unmodifiableList(faces);}
    public float[] getVertex(int i){return vertices.get(i).clone();}
    public int[] getFaceVertices(int i){return faces.get(i).vertices.clone();}
    public boolean isVertexSelected(int i){return selectedVertices.get(i);}
    public boolean isEdgeSelected(int i){return selectedEdges.get(i);}
    public boolean isFaceSelected(int i){return selectedFaces.get(i);}
    public float[] getSculptMask(){return sculptMask.clone();}
    public void setSculptMask(float[] mask){if(mask==null)return;if(mask.length!=vertices.size())return;sculptMask=mask.clone();}
    public int getSelectedCount(){
        return selectMode==SelectMode.VERTEX?selectedVertices.cardinality():
               selectMode==SelectMode.EDGE?selectedEdges.cardinality():selectedFaces.cardinality();
    }
    private static long edgeKey(int a,int b){int lo=Math.min(a,b),hi=Math.max(a,b);return (((long)lo)<<32)|(hi&0xffffffffL);}
    private void createDenseCube(int subdivisions){
        vertices.clear();faces.clear();edges.clear();sculptMask=new float[0];
        HashMap<String,Integer> welded=new HashMap<>();
        int n=subdivisions+1;
        int[][] side={{0,1,2,1},{0,2,1,-1},{1,2,0,1},{1,0,2,-1},{2,0,1,1},{2,1,0,-1}};
        for(int s=0;s<6;s++){
            int fixed=side[s][0],ua=side[s][1],va=side[s][2];
            for(int v=0;v<=subdivisions;v++)for(int u=0;u<=subdivisions;u++){
                float[] p=new float[3];p[fixed]=(s%2==0)?1f:-1f;
                p[ua]=-1f+2f*u/subdivisions;p[va]=-1f+2f*v/subdivisions;
                String key=Math.round(p[0]*100000)+":"+Math.round(p[1]*100000)+":"+Math.round(p[2]*100000);
                Integer id=welded.get(key);
                if(id==null){id=vertices.size();vertices.add(p);welded.put(key,id);}
            }
        }
        // Revisit the side grids and resolve welded vertex indices.
        for(int s=0;s<6;s++){
            int fixed=side[s][0],ua=side[s][1],va=side[s][2];
            int[][] ids=new int[n][n];
            for(int v=0;v<=subdivisions;v++)for(int u=0;u<=subdivisions;u++){
                float[] p=new float[3];p[fixed]=(s%2==0)?1f:-1f;
                p[ua]=-1f+2f*u/subdivisions;p[va]=-1f+2f*v/subdivisions;
                String key=Math.round(p[0]*100000)+":"+Math.round(p[1]*100000)+":"+Math.round(p[2]*100000);
                ids[v][u]=welded.get(key);
            }
            for(int v=0;v<subdivisions;v++)for(int u=0;u<subdivisions;u++)
                faces.add(new Face(ids[v][u],ids[v][u+1],ids[v+1][u+1],ids[v+1][u]));
        }
        sculptMask=new float[vertices.size()];rebuildEdges();revision++;
    }
    private void rebuildEdges(){
        if(sculptMask.length!=vertices.size())sculptMask=Arrays.copyOf(sculptMask,vertices.size());
        edges.clear();HashMap<Long,Integer> map=new HashMap<>();
        for(int fi=0;fi<faces.size();fi++){
            int[] f=faces.get(fi).vertices;
            for(int j=0;j<f.length;j++){
                int a=f[j],b=f[(j+1)%f.length];long key=edgeKey(a,b);
                Integer ei=map.get(key);
                if(ei==null){ei=edges.size();edges.add(new Edge(Math.min(a,b),Math.max(a,b)));map.put(key,ei);}
                edges.get(ei).faces.add(fi);
            }
        }
        selectedEdges.clear();
        for(int i=0;i<edges.size();i++){
            Edge e=edges.get(i);
            if(selectedVertices.get(e.a)&&selectedVertices.get(e.b))selectedEdges.set(i);
        }
        if(faceUvs!=null&&faceUvs.length!=faces.size()){faceUvs=null;islands.clear();}
    }
    public void clearSelection(){selectedVertices.clear();selectedEdges.clear();selectedFaces.clear();}
    public void selectVertex(int index,boolean additive){
        if(index<0||index>=vertices.size())return;if(!additive)clearSelection();
        selectedVertices.set(index);for(int i=0;i<edges.size();i++){Edge e=edges.get(i);if(e.a==index||e.b==index)selectedEdges.set(i);}
        revision++;
    }
    public void selectEdge(int index,boolean additive){
        if(index<0||index>=edges.size())return;if(!additive)clearSelection();
        selectedEdges.set(index);Edge e=edges.get(index);selectedVertices.set(e.a);selectedVertices.set(e.b);revision++;
    }
    public void selectFace(int index,boolean additive){
        if(index<0||index>=faces.size())return;if(!additive)clearSelection();
        selectedFaces.set(index);faces.get(index).selected=true;for(int v:faces.get(index).vertices)selectedVertices.set(v);revision++;
    }
    public void selectAll(){
        if(selectMode==SelectMode.VERTEX)selectedVertices.set(0,vertices.size());
        else if(selectMode==SelectMode.EDGE){
            selectedEdges.set(0,edges.size());for(Edge e:edges){selectedVertices.set(e.a);selectedVertices.set(e.b);}
        }else{
            selectedFaces.set(0,faces.size());for(Face f:faces){f.selected=true;for(int v:f.vertices)selectedVertices.set(v);}
        }
        revision++;
    }
    public void selectLinkedFromCurrent(){
        int seed=selectedFaces.nextSetBit(0);
        if(seed<0&&selectedEdges.nextSetBit(0)>=0){Edge e=edges.get(selectedEdges.nextSetBit(0));if(!e.faces.isEmpty())seed=e.faces.get(0);}
        if(seed<0&&selectedVertices.nextSetBit(0)>=0){int vertex=selectedVertices.nextSetBit(0);
            for(int i=0;i<faces.size()&&seed<0;i++)for(int id:faces.get(i).vertices)if(id==vertex){seed=i;break;}
        }
        if(seed>=0)selectLinked(seed);
    }
    public void selectLinked(int seedFace){
        if(seedFace<0||seedFace>=faces.size())return;
        clearSelection();ArrayDeque<Integer> q=new ArrayDeque<>();q.add(seedFace);selectedFaces.set(seedFace);
        while(!q.isEmpty()){
            int fi=q.removeFirst();Face f=faces.get(fi);
            for(int i=0;i<f.vertices.length;i++){
                int a=f.vertices[i],b=f.vertices[(i+1)%f.vertices.length];
                if(seams.contains(edgeKey(a,b)))continue;
                for(Edge e:edges)if(e.a==Math.min(a,b)&&e.b==Math.max(a,b))
                    for(int other:e.faces)if(!selectedFaces.get(other)){selectedFaces.set(other);q.add(other);}
            }
        }
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1))
            for(int v:faces.get(fi).vertices)selectedVertices.set(v);
        revision++;
    }
    public void transformSelected(float x,float y,float z,int operation){
        BitSet targets=(BitSet)selectedVertices.clone();
        if(targets.isEmpty())targets.set(0,vertices.size());
        for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){
            float[] p=vertices.get(i);
            if(operation==0){p[0]+=x;p[1]+=y;p[2]+=z;}
            else if(operation==1){double rx=Math.toRadians(x),ry=Math.toRadians(y),rz=Math.toRadians(z);
                float yy=(float)(p[1]*Math.cos(rx)-p[2]*Math.sin(rx)),zz=(float)(p[1]*Math.sin(rx)+p[2]*Math.cos(rx));
                float xx=(float)(p[0]*Math.cos(ry)+zz*Math.sin(ry)),z2=(float)(-p[0]*Math.sin(ry)+zz*Math.cos(ry));
                p[0]=(float)(xx*Math.cos(rz)-yy*Math.sin(rz));p[1]=(float)(xx*Math.sin(rz)+yy*Math.cos(rz));p[2]=z2;
            }else if(operation==2){p[0]*=x;p[1]*=y;p[2]*=z;}
        }revision++;
    }
    public void markSelectedSeams(boolean clear){
        if(clear){for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);seams.remove(edgeKey(e.a,e.b));}}
        else for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);seams.add(edgeKey(e.a,e.b));}
        rebuildIslands();revision++;
    }
    public boolean isSeam(int edge){if(edge<0||edge>=edges.size())return false;Edge e=edges.get(edge);return seams.contains(edgeKey(e.a,e.b));}
    public void pinSelectedUvs(boolean pin){
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1))
            for(int v:faces.get(fi).vertices){if(pin)pins.add((long)v);else pins.remove((long)v);}
        revision++;
    }
    public void unwrapPlanar(){unwrapLSCM();}
    public void unwrapLSCM(){
        float[][][] previousUvs=faceUvs;faceUvs=null;islands.clear();
        // Sharp normal discontinuities become default seams; user seams are retained.
        for(Edge edge:edges)if(edge.faces.size()==2){
            float[] a=normal(faces.get(edge.faces.get(0)).vertices),b=normal(faces.get(edge.faces.get(1)).vertices);
            float la=(float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);
            float lb=(float)Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2]);
            if(la>1e-6&&lb>1e-6){
                float dot=(a[0]*b[0]+a[1]*b[1]+a[2]*b[2])/(la*lb);
                if(dot<.985f)seams.add(edgeKey(edge.a,edge.b));
            }
        }
        rebuildIslands();
        faceUvs=Meshy3DUvUnwrapper.unwrap(copyVertices(),copyFaces(),islands,pins,previousUvs);
        for(UvIsland island:islands){
            island.minU=Float.MAX_VALUE;island.minV=Float.MAX_VALUE;
            island.maxU=-Float.MAX_VALUE;island.maxV=-Float.MAX_VALUE;
            for(int fi:island.faces)if(faceUvs[fi]!=null)for(float[] uv:faceUvs[fi]){
                island.minU=Math.min(island.minU,uv[0]);island.minV=Math.min(island.minV,uv[1]);
                island.maxU=Math.max(island.maxU,uv[0]);island.maxV=Math.max(island.maxV,uv[1]);
            }
        }
        revision++;
    }
    private float[] normal(int[] f){
        float[] a=vertices.get(f[0]),b=vertices.get(f[1]),c=vertices.get(f[2]);
        float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
        return new float[]{uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx};
    }
    private void rebuildIslands(){
        islands.clear();if(faceUvs==null)return;BitSet seen=new BitSet(faces.size());
        for(int start=0;start<faces.size();start++)if(!seen.get(start)){
            UvIsland island=new UvIsland();ArrayDeque<Integer> q=new ArrayDeque<>();q.add(start);seen.set(start);
            while(!q.isEmpty()){int fi=q.removeFirst();island.faces.add(fi);
                int[] f=faces.get(fi).vertices;
                for(int j=0;j<f.length;j++){int a=f[j],b=f[(j+1)%f.length];if(seams.contains(edgeKey(a,b)))continue;
                    for(Edge e:edges)if(e.a==Math.min(a,b)&&e.b==Math.max(a,b))
                        for(int other:e.faces)if(!seen.get(other)){seen.set(other);q.add(other);}
                }
            }islands.add(island);
        }
    }
    public int getUvIslandCount(){return islands.size();}
    public List<UvIsland> getUvIslands(){return Collections.unmodifiableList(islands);}
    private int selectedUvIsland=-1;
    public float[][][] copyFaceUvs(){
        if(faceUvs==null)return null;float[][][] out=new float[faceUvs.length][][];
        for(int i=0;i<out.length;i++){out[i]=new float[faceUvs[i].length][2];for(int j=0;j<out[i].length;j++)out[i][j]=faceUvs[i][j].clone();}return out;
    }
    public void selectUvIsland(int index){
        selectedUvIsland=index>=0&&index<islands.size()?index:-1;selectedFaces.clear();selectedVertices.clear();
        if(selectedUvIsland>=0)for(int fi:islands.get(selectedUvIsland).faces){selectedFaces.set(fi);for(int id:faces.get(fi).vertices)selectedVertices.set(id);}
    }
    public void packUvIslands(){packUvIslands(.025f);}
    public void packUvIslands(float requestedMargin){
        if(faceUvs==null||islands.isEmpty())return;
        int cols=Math.max(1,(int)Math.ceil(Math.sqrt(islands.size()))),rows=(int)Math.ceil(islands.size()/(float)cols);
        float tw=1f/cols,th=1f/rows;
        float margin=Math.max(0f,Math.min(Math.min(tw,th)*.45f,requestedMargin));
        for(int i=0;i<islands.size();i++){
            UvIsland island=islands.get(i);boolean hasPinned=false;
            for(int fi:island.faces)for(int vertex:faces.get(fi).vertices)if(pins.contains((long)vertex))hasPinned=true;
            if(hasPinned)continue;
            float minU=Float.MAX_VALUE,minV=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
            for(int fi:island.faces)for(float[] uv:faceUvs[fi]){minU=Math.min(minU,uv[0]);minV=Math.min(minV,uv[1]);maxU=Math.max(maxU,uv[0]);maxV=Math.max(maxV,uv[1]);}
            float rw=Math.max(.0001f,maxU-minU),rh=Math.max(.0001f,maxV-minV);
            float scale=Math.min((tw-2*margin)/rw,(th-2*margin)/rh);
            float usedW=rw*scale,usedH=rh*scale;
            float ox=(i%cols)*tw+(tw-usedW)*.5f,oy=(i/cols)*th+(th-usedH)*.5f;
            for(int fi:island.faces)for(float[] uv:faceUvs[fi]){uv[0]=ox+(uv[0]-minU)*scale;uv[1]=oy+(uv[1]-minV)*scale;}
            island.minU=ox;island.minV=oy;island.maxU=ox+usedW;island.maxV=oy+usedH;
        }revision++;
    }
    public boolean hasUvMap(){return faceUvs!=null;}
    public void clearUvMap(){
        faceUvs=null;islands.clear();selectedUvIsland=-1;
        selectedFaces.clear();selectedVertices.clear();revision++;
    }
    public int getSelectedUvIsland(){return selectedUvIsland;}
    public void transformSelectedUv(float du,float dv,float scale,float angle){
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return;
        UvIsland island=islands.get(selectedUvIsland);float cx=0,cy=0;int n=0;
        for(int fi:island.faces)for(float[] uv:faceUvs[fi]){cx+=uv[0];cy+=uv[1];n++;}
        if(n==0)return;cx/=n;cy/=n;float co=(float)Math.cos(angle),si=(float)Math.sin(angle);
        for(int fi:island.faces)for(float[] uv:faceUvs[fi]){
            float x=(uv[0]-cx)*scale,y=(uv[1]-cy)*scale;
            uv[0]=cx+x*co-y*si+du;uv[1]=cy+x*si+y*co+dv;
        }revision++;
    }
    private float[] uvIslandBounds(int index) {
        if(index<0||index>=islands.size()||faceUvs==null)return null;
        float minU=Float.MAX_VALUE,minV=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
        for(int fi:islands.get(index).faces)for(float[] uv:faceUvs[fi]){
            minU=Math.min(minU,uv[0]);minV=Math.min(minV,uv[1]);
            maxU=Math.max(maxU,uv[0]);maxV=Math.max(maxV,uv[1]);
        }
        return new float[]{minU,minV,maxU,maxV};
    }

    public void snapSelectedUvToPixels(int width,int height) {
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return;
        width=Math.max(1,width);height=Math.max(1,height);
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi]){
            uv[0]=Math.round(uv[0]*width)/(float)width;
            uv[1]=Math.round(uv[1]*height)/(float)height;
        }
        revision++;
    }

    /** Translate the selected island so its first corner snaps to the nearest other UV corner. */
    public boolean snapSelectedUvToNearestVertex(float maxDistance) {
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return false;
        int selectedFace=islands.get(selectedUvIsland).faces.get(0);
        if(faceUvs[selectedFace].length==0)return false;
        float[] anchorUv=faceUvs[selectedFace][0];
        float best=Float.MAX_VALUE,targetU=0,targetV=0;
        float max2=Math.max(0f,maxDistance)*Math.max(0f,maxDistance);
        for(int islandIndex=0;islandIndex<islands.size();islandIndex++){
            if(islandIndex==selectedUvIsland)continue;
            for(int fi:islands.get(islandIndex).faces)for(float[] uv:faceUvs[fi]){
                float du=uv[0]-anchorUv[0],dv=uv[1]-anchorUv[1],distance=du*du+dv*dv;
                if(distance<best){best=distance;targetU=uv[0];targetV=uv[1];}
            }
        }
        if(best==Float.MAX_VALUE||best>max2)return false;
        float du=targetU-anchorUv[0],dv=targetV-anchorUv[1];
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi]){
            uv[0]+=du;uv[1]+=dv;
        }
        revision++;
        return true;
    }

    public boolean selectPinnedUvIsland() {
        for(int i=0;i<islands.size();i++)
            for(int fi:islands.get(i).faces)
                for(int vertex:faces.get(fi).vertices)
                    if(pins.contains((long)vertex)){selectUvIsland(i);return true;}
        return false;
    }

    /** Select the first pair whose UV island bounds overlap in the 0–1 tile. */
    public boolean selectOverlappingUvIsland() {
        for(int i=0;i<islands.size();i++){
            float[] a=uvIslandBounds(i);if(a==null)continue;
            for(int j=i+1;j<islands.size();j++){
                float[] b=uvIslandBounds(j);if(b==null)continue;
                float overlapU=Math.min(a[2],b[2])-Math.max(a[0],b[0]);
                float overlapV=Math.min(a[3],b[3])-Math.max(a[1],b[1]);
                if(overlapU>1e-4f&&overlapV>1e-4f){selectUvIsland(i);return true;}
            }
        }
        return false;
    }

    public boolean selectBoundaryUvIsland() {
        if(islands.isEmpty())return false;
        int best=0;
        for(int i=1;i<islands.size();i++)
            if(islands.get(i).faces.size()>islands.get(best).faces.size())best=i;
        selectUvIsland(best);
        return true;
    }

    /** Mirror the active UV island around its U (axis 0) or V (axis 1) centroid. */
    public void mirrorSelectedUv(int axis) {
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return;
        axis=axis==1?1:0;
        float center=0;int count=0;
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi]){
            center+=uv[axis];count++;
        }
        if(count==0)return;center/=count;
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi])
            uv[axis]=2f*center-uv[axis];
        revision++;
    }

    /** Move the active island so its minimum U or V edge aligns with zero. */
    public void alignSelectedUv(int axis) {
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return;
        axis=axis==1?1:0;
        float min=Float.MAX_VALUE;
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi])
            min=Math.min(min,uv[axis]);
        if(min==Float.MAX_VALUE)return;
        for(int fi:islands.get(selectedUvIsland).faces)for(float[] uv:faceUvs[fi])
            uv[axis]-=min;
        revision++;
    }

    /** Weld duplicate UV corners that refer to the same mesh vertex in the active island. */
    public void weldSelectedUv(float tolerance) {
        if(faceUvs==null||selectedUvIsland<0||selectedUvIsland>=islands.size())return;
        tolerance=Math.max(0f,Math.min(.1f,tolerance));
        java.util.HashMap<Integer,float[]> sums=new java.util.HashMap<>();
        java.util.HashMap<Integer,Integer> counts=new java.util.HashMap<>();
        for(int fi:islands.get(selectedUvIsland).faces){
            int[] ids=faces.get(fi).vertices;
            for(int j=0;j<ids.length;j++){
                int id=ids[j];float[] sum=sums.get(id);
                if(sum==null){sum=new float[2];sums.put(id,sum);counts.put(id,0);}
                sum[0]+=faceUvs[fi][j][0];sum[1]+=faceUvs[fi][j][1];
                counts.put(id,counts.get(id)+1);
            }
        }
        for(int fi:islands.get(selectedUvIsland).faces){
            int[] ids=faces.get(fi).vertices;
            for(int j=0;j<ids.length;j++){
                float[] sum=sums.get(ids[j]);int n=counts.get(ids[j]);
                float u=sum[0]/n,v=sum[1]/n;
                if(Math.abs(faceUvs[fi][j][0]-u)<=tolerance+1e-6f
                        &&Math.abs(faceUvs[fi][j][1]-v)<=tolerance+1e-6f){
                    faceUvs[fi][j][0]=u;faceUvs[fi][j][1]=v;
                }
            }
        }
        revision++;
    }

    /** Split the selected UV island from neighboring faces by marking its shared boundary as seams. */
    public boolean splitSelectedUvIsland() {
        if(selectedUvIsland<0||selectedUvIsland>=islands.size())return false;
        java.util.HashSet<Integer> selected=new java.util.HashSet<>(islands.get(selectedUvIsland).faces);
        boolean changed=false;
        for(Edge edge:edges){
            int inside=0;
            for(int faceIndex:edge.faces)if(selected.contains(faceIndex))inside++;
            if(inside==1&&edge.faces.size()>1){
                changed|=seams.add(edgeKey(edge.a,edge.b));
            }
        }
        if(changed){unwrapLSCM();revision++;}
        return changed;
    }

    public int findUvIsland(float u,float v){
        if(faceUvs==null)return -1;
        for(int fi=0;fi<faceUvs.length;fi++){float[][] poly=faceUvs[fi];boolean inside=false;
            for(int i=0,j=poly.length-1;i<poly.length;j=i++){
                float xi=poly[i][0],yi=poly[i][1],xj=poly[j][0],yj=poly[j][1];
                if(((yi>v)!=(yj>v))&&(u<(xj-xi)*(v-yi)/(yj-yi+1e-8f)+xi))inside=!inside;
            }if(inside){for(int k=0;k<islands.size();k++)if(islands.get(k).faces.contains(fi))return k;}
        }return -1;
    }
    public void createTexture(int width,int height,int color){
        textureWidth=Math.max(16,Math.min(4096,width));textureHeight=Math.max(16,Math.min(4096,height));
        texturePixels=new int[textureWidth*textureHeight];Arrays.fill(texturePixels,color);if(faceUvs==null)unwrapPlanar();revision++;
    }
    public void setBrush(int color,float radius,float opacity){textureColor=color;brushRadius=Math.max(1,radius);brushOpacity=Math.max(0,Math.min(1,opacity));}
    public int getBrushColor(){return textureColor;}public float getBrushRadius(){return brushRadius;}public float getBrushOpacity(){return brushOpacity;}
    public void paintPixel(int x,int y){
        if(texturePixels==null)createTexture(1024,1024,0xFFFFFFFF);
        int r=(int)brushRadius;for(int py=Math.max(0,y-r);py<Math.min(textureHeight,y+r);py++)
            for(int px=Math.max(0,x-r);px<Math.min(textureWidth,x+r);px++){
                float d=(float)Math.hypot(px-x,py-y)/r;if(d>1)continue;
                int i=py*textureWidth+px;texturePixels[i]=blend(texturePixels[i],textureColor,brushOpacity*(1-d));
            }revision++;
    }
    private static int blend(int a,int b,float t){
        t=Math.max(0,Math.min(1,t));int aa=(a>>>24)&255,ar=(a>>>16)&255,ag=(a>>>8)&255,ab=a&255;
        int ba=(b>>>24)&255,br=(b>>>16)&255,bg=(b>>>8)&255,bb=b&255;
        return ((int)(aa+(ba-aa)*t)<<24)|((int)(ar+(br-ar)*t)<<16)|((int)(ag+(bg-ag)*t)<<8)|(int)(ab+(bb-ab)*t);
    }
    public int[] getTexturePixels(){return texturePixels==null?null:texturePixels.clone();}
    int[] getTexturePixelsUnsafe(){return texturePixels;}
    public boolean hasTexture(){return texturePixels!=null;}
    public int sampleTextureColor(float u,float v){if(texturePixels==null)return 0;int x=Math.max(0,Math.min(textureWidth-1,(int)(u*(textureWidth-1))));int y=Math.max(0,Math.min(textureHeight-1,(int)((1-v)*(textureHeight-1))));return texturePixels[y*textureWidth+x];}
    public boolean hasFaceUvs(){return faceUvs!=null;}
    public int sampleFaceTextureColor(int face){
        if(texturePixels==null||faceUvs==null||face<0||face>=faceUvs.length||faceUvs[face]==null)return 0;
        float u=0,v=0;for(float[] pair:faceUvs[face]){u+=pair[0];v+=pair[1];}
        if(faceUvs[face].length==0)return 0;
        return sampleTextureColor(u/faceUvs[face].length,v/faceUvs[face].length);
    }
    public int getTextureWidth(){return textureWidth;} public int getTextureHeight(){return textureHeight;}
    public void setMaterial(int color,float metal,float rough){baseColor=color;metallic=Math.max(0,Math.min(1,metal));roughness=Math.max(.02f,Math.min(1,rough));revision++;}
    public int getBaseColor(){return baseColor;}public float getMetallic(){return metallic;}public float getRoughness(){return roughness;}
    public void addShaderNode(String type){addShaderNode(type,0f);}
    public void addShaderNode(String type,float value){Node n=new Node(type);n.value=value;int index=shaderNodes.size();shaderNodes.add(n);if(index>0)shaderLinks.add(new int[]{index-1,index});revision++;}
    public void evaluateShaderGraph(){
        int[] order=graphExecutionOrder(1);
        for(int index:order){Node n=shaderNodes.get(index);
            if("Metallic".equals(n.type))metallic=Math.max(0,Math.min(1,n.value));
            else if("Roughness".equals(n.type))roughness=Math.max(.02f,Math.min(1,n.value));
            else if("Base Color".equals(n.type))baseColor=0xFF000000|((int)n.value&0xFFFFFF);
        }revision++;
    }
    public void evaluateCompositorTexture(){
        if(texturePixels==null)return;
        texturePixels=evaluateCompositor(texturePixels,textureWidth,textureHeight);revision++;
    }
    public String[] getGraphNodeLabels(int graph){
        ArrayList<Node> list=graph==0?geometryNodes:graph==1?shaderNodes:compositorNodes;
        String[] names=new String[list.size()];for(int i=0;i<list.size();i++)names[i]=list.get(i).type;return names;
    }
    public void addNodeToGraph(int graph,String type,float value){
        if(graph==0)addGeometryNode(type,0,0,0,value);
        else if(graph==1)addShaderNode(type,value);
        else addCompositorNode(type,value);
    }
    private ArrayList<Node> nodesForGraph(int graph){return graph==0?geometryNodes:graph==1?shaderNodes:compositorNodes;}
    private ArrayList<int[]> linksForGraph(int graph){return graph==0?geometryLinks:graph==1?shaderLinks:compositorLinks;}
    public float getGraphNodeValue(int graph,int index){ArrayList<Node> nodes=nodesForGraph(graph);return index>=0&&index<nodes.size()?nodes.get(index).value:0f;}
    public String getGraphNodeType(int graph,int index){ArrayList<Node> nodes=nodesForGraph(graph);return index>=0&&index<nodes.size()?nodes.get(index).type:"";}
    public void setGraphNodeValue(int graph,int index,float value){
        ArrayList<Node> nodes=nodesForGraph(graph);if(index<0||index>=nodes.size())return;
        Node node=nodes.get(index);node.value=value;
        if(graph==0&&"Transform Geometry".equals(node.type))node.x=value;
        revision++;
    }
    public int[][] getGraphLinks(int graph){ArrayList<int[]> links=linksForGraph(graph);int[][] out=new int[links.size()][2];for(int i=0;i<out.length;i++)out[i]=links.get(i).clone();return out;}
    public boolean connectGraphNodes(int graph,int from,int to){
        ArrayList<Node> nodes=nodesForGraph(graph);if(from<0||to<0||from>=nodes.size()||to>=nodes.size()||from==to)return false;
        ArrayList<int[]> links=linksForGraph(graph);
        boolean[] visited=new boolean[nodes.size()];ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(to);visited[to]=true;
        while(!queue.isEmpty()){int at=queue.removeFirst();if(at==from)return false;
            for(int[] link:links)if(link[0]==at&&link[1]!=to&&!visited[link[1]]){visited[link[1]]=true;queue.add(link[1]);}
        }
        links.removeIf(link->link[1]==to);links.add(new int[]{from,to});revision++;return true;
    }
    private int[] graphExecutionOrder(int graph){
        ArrayList<Node> nodes=nodesForGraph(graph);ArrayList<int[]> links=linksForGraph(graph);
        int n=nodes.size();int[] indegree=new int[n];ArrayList<ArrayList<Integer>> next=new ArrayList<>();
        for(int i=0;i<n;i++)next.add(new ArrayList<>());
        for(int[] link:links)if(link[0]>=0&&link[0]<n&&link[1]>=0&&link[1]<n){next.get(link[0]).add(link[1]);indegree[link[1]]++;}
        ArrayDeque<Integer> q=new ArrayDeque<>();for(int i=0;i<n;i++)if(indegree[i]==0)q.add(i);
        int[] order=new int[n];int count=0;while(!q.isEmpty()){int id=q.removeFirst();order[count++]=id;for(int to:next.get(id))if(--indegree[to]==0)q.add(to);}
        if(count!=n){for(int i=0;i<n;i++)order[i]=i;}
        return order;
    }
    public void evaluateGraph(int graph){
        if(graph==0)evaluateGeometryNodes();
        else if(graph==1)evaluateShaderGraph();
        else revision++;
    }
    public int getShaderNodeCount(){return shaderNodes.size();}
    public void addGeometryNode(String type,float x,float y,float z,float value){Node n=new Node(type);n.x=x;n.y=y;n.z=z;n.value=value;int index=geometryNodes.size();geometryNodes.add(n);if(index>0)geometryLinks.add(new int[]{index-1,index});revision++;}
    public void clearGeometryNodes(){geometryNodes.clear();geometryLinks.clear();revision++;}
    public void evaluateGeometryNodes(){
        int[] order=graphExecutionOrder(0);
        for(int index:order){Node n=geometryNodes.get(index);
            if("Transform Geometry".equals(n.type)){for(float[] p:vertices){p[0]+=n.x;p[1]+=n.y;p[2]+=n.z;}}
            else if("Set Position".equals(n.type)){for(float[] p:vertices)p[1]+=n.value;}
            else if("Smooth".equals(n.type))smooth(n.value==0?.2f:Math.max(0,Math.min(1,n.value)),1);
            else if("Subdivide".equals(n.type)||"Subdivide Mesh".equals(n.type))subdivide();
            else if("Delete Geometry".equals(n.type))deleteSelectedFaces();
            else if("Set Material".equals(n.type))baseColor=0xFF000000|((int)n.value&0xFFFFFF);
            else if("Random Value".equals(n.type)){Random random=new Random(7);for(float[] p:vertices){p[0]+=(random.nextFloat()-.5f)*n.value;p[1]+=(random.nextFloat()-.5f)*n.value;p[2]+=(random.nextFloat()-.5f)*n.value;}}
            else if("Math".equals(n.type)){for(float[] p:vertices){p[0]*=n.value;p[1]*=n.value;p[2]*=n.value;}}
            else if("Instance on Points".equals(n.type))instanceMesh(Math.max(1,Math.min(20,Math.round(n.value))),n.x,n.y,n.z);
        }revision++;
    }
    public void deleteSelectedFaces(){
        if(selectedFaces.isEmpty())return;
        ArrayList<Face> keep=new ArrayList<>();for(int i=0;i<faces.size();i++)if(!selectedFaces.get(i))keep.add(faces.get(i));
        faces.clear();faces.addAll(keep);compactUnusedVertices();clearSelection();rebuildEdges();revision++;
    }
    public void deleteSelectedVertices(){
        if(selectedVertices.isEmpty())return;
        faces.removeIf(face->{for(int id:face.vertices)if(selectedVertices.get(id))return true;return false;});
        compactUnusedVertices();clearSelection();rebuildEdges();revision++;
    }
    private void compactUnusedVertices(){
        boolean[] used=new boolean[vertices.size()];for(Face face:faces)for(int id:face.vertices)used[id]=true;
        int[] remap=new int[vertices.size()];Arrays.fill(remap,-1);ArrayList<float[]> kept=new ArrayList<>();ArrayList<Float> keptMask=new ArrayList<>();
        for(int i=0;i<vertices.size();i++)if(used[i]){remap[i]=kept.size();kept.add(vertices.get(i));keptMask.add(i<sculptMask.length?sculptMask[i]:0f);}
        for(Face face:faces)for(int i=0;i<face.vertices.length;i++)face.vertices[i]=remap[face.vertices[i]];
        vertices.clear();vertices.addAll(kept);sculptMask=new float[keptMask.size()];for(int i=0;i<sculptMask.length;i++)sculptMask[i]=keptMask.get(i);
    }
    private void duplicateMesh(float dx,float dy,float dz){instanceMesh(1,dx,dy,dz);}
    private void instanceMesh(int copies,float dx,float dy,float dz){
        float[][] sourceVertices=copyVertices();int[][] sourceFaces=copyFaces();
        for(int copy=1;copy<=copies;copy++){
            int base=vertices.size();float ox=dx*copy,oy=dy*copy,oz=dz*copy;
            for(float[] p:sourceVertices)vertices.add(new float[]{p[0]+ox,p[1]+oy,p[2]+oz});
            for(int[] face:sourceFaces){int[] ids=face.clone();for(int i=0;i<ids.length;i++)ids[i]+=base;faces.add(new Face(ids));}
        }rebuildEdges();
    }
    public void addCompositorNode(String type,float value){Node n=new Node(type);n.value=value;int index=compositorNodes.size();compositorNodes.add(n);if(index>0)compositorLinks.add(new int[]{index-1,index});revision++;}
    public int getCompositorNodeCount(){return compositorNodes.size();}
    public int[] evaluateCompositor(int[] input,int width,int height){
        int[] out=input.clone();
        int[] order=graphExecutionOrder(2);
        for(int nodeIndex:order){Node n=compositorNodes.get(nodeIndex);
            if("Grayscale".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],g=(int)(.2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255));out[i]=0xFF000000|g<<16|g<<8|g;}}
            else if("Brightness".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],r=clamp(((c>>16)&255)+n.value),g=clamp(((c>>8)&255)+n.value),b=clamp((c&255)+n.value);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Invert".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i];out[i]=0xFF000000|((255-((c>>16)&255))<<16)|((255-((c>>8)&255))<<8)|(255-(c&255));}}
            else if("Contrast".equals(n.type)){float f=(float)((259*(n.value+255))/(255*(259-n.value)));for(int i=0;i<out.length;i++){int c=out[i],r=clamp(f*(((c>>16)&255)-128)+128),g=clamp(f*(((c>>8)&255)-128)+128),b=clamp(f*((c&255)-128)+128);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Blur".equals(n.type)&&width>2&&height>2)out=boxBlur(out,width,height,Math.max(1,Math.min(8,Math.round(n.value))));
            else if("Color Balance".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],r=clamp(((c>>16)&255)+n.value),g=clamp(((c>>8)&255)+n.value*.25f),b=clamp((c&255)-n.value);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Alpha Over".equals(n.type)){int overlay=baseColor;for(int i=0;i<out.length;i++)out[i]=blend(out[i],overlay,Math.max(0,Math.min(1,n.value)));}
            else if("Composite".equals(n.type)){/* Final output node: retain the evaluated image. */}
        }return out;
    }
    private static int[] boxBlur(int[] source,int width,int height,int radius){
        int[] horizontal=new int[source.length],output=new int[source.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int r=0,g=0,b=0,count=0;for(int dx=-radius;dx<=radius;dx++){int xx=x+dx;if(xx<0||xx>=width)continue;int c=source[y*width+xx];r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;count++;}
            horizontal[y*width+x]=0xFF000000|(r/count)<<16|(g/count)<<8|(b/count);
        }
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int r=0,g=0,b=0,count=0;for(int dy=-radius;dy<=radius;dy++){int yy=y+dy;if(yy<0||yy>=height)continue;int c=horizontal[yy*width+x];r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;count++;}
            output[y*width+x]=0xFF000000|(r/count)<<16|(g/count)<<8|(b/count);
        }return output;
    }
    private int clamp(float v){return Math.max(0,Math.min(255,Math.round(v)));}
    public void insertKeyframe(String property,int frame,float value){
        AnimationChannel c=animation.get(property);if(c==null){c=new AnimationChannel(property);animation.put(property,c);}c.put(frame,value);revision++;
    }
    public float evaluateAnimation(String property,float frame,float fallback){
        AnimationChannel c=animation.get(property);return c==null||c.keys.isEmpty()?fallback:c.evaluate(frame);
    }
    public int getAnimationKeyCount(){int n=0;for(AnimationChannel c:animation.values())n+=c.keys.size();return n;}
    public void smooth(float amount,int iterations){
        float t=Math.max(0,Math.min(1,amount));
        for(int pass=0;pass<Math.max(1,Math.min(20,iterations));pass++){
            float[][] next=new float[vertices.size()][3];int[] counts=new int[vertices.size()];
            for(Edge e:edges){for(int k=0;k<3;k++){next[e.a][k]+=vertices.get(e.b)[k];next[e.b][k]+=vertices.get(e.a)[k];}counts[e.a]++;counts[e.b]++;}
            for(int i=0;i<vertices.size();i++)if(counts[i]>0)for(int k=0;k<3;k++){float avg=next[i][k]/counts[i];vertices.get(i)[k]+= (avg-vertices.get(i)[k])*t;}
        }revision++;
    }
    public void subdivide(){
        HashMap<Long,Integer> midpoint=new HashMap<>();ArrayList<Face> result=new ArrayList<>();
        for(Face f:faces){int[] v=f.vertices;if(v.length!=4){result.add(f);continue;}
            int[] m=new int[4];for(int i=0;i<4;i++){int a=v[i],b=v[(i+1)%4];long key=edgeKey(a,b);Integer id=midpoint.get(key);
                if(id==null){float[] p=vertices.get(a),q=vertices.get(b);id=vertices.size();vertices.add(new float[]{(p[0]+q[0])*.5f,(p[1]+q[1])*.5f,(p[2]+q[2])*.5f});midpoint.put(key,id);}m[i]=id;}
            float[] c=new float[3];for(int id:v)for(int k=0;k<3;k++)c[k]+=vertices.get(id)[k]*.25f;int center=vertices.size();vertices.add(c);
            for(int i=0;i<4;i++)result.add(new Face(v[i],m[i],center,m[(i+3)%4]));
        }faces.clear();faces.addAll(result);rebuildEdges();revision++;
    }
    public void insetSelected(float amount){
        if(selectedFaces.isEmpty())return;ArrayList<Face> out=new ArrayList<>();
        for(int fi=0;fi<faces.size();fi++){Face f=faces.get(fi);if(!selectedFaces.get(fi)){out.add(f);continue;}
            int[] v=f.vertices;float[] center=new float[3];for(int id:v)for(int k=0;k<3;k++)center[k]+=vertices.get(id)[k]/v.length;
            int[] inner=new int[v.length];for(int j=0;j<v.length;j++){float[] p=vertices.get(v[j]);inner[j]=vertices.size();vertices.add(new float[]{p[0]+(center[0]-p[0])*amount,p[1]+(center[1]-p[1])*amount,p[2]+(center[2]-p[2])*amount});}
            out.add(new Face(inner));for(int j=0;j<v.length;j++)out.add(new Face(v[j],v[(j+1)%v.length],inner[(j+1)%v.length],inner[j]));
        }faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void extrudeSelected(float distance){
        if(selectedFaces.isEmpty())return;
        HashSet<Integer> verts=new HashSet<>();HashSet<Integer> selectedFaceIds=new HashSet<>();
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1)){
            selectedFaceIds.add(fi);for(int v:faces.get(fi).vertices)verts.add(v);
        }
        float[] n={0,0,0};for(int fi:selectedFaceIds){float[] fn=normal(faces.get(fi).vertices);for(int k=0;k<3;k++)n[k]+=fn[k];}
        float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(len<1e-6)return;for(int k=0;k<3;k++)n[k]/=len;
        HashMap<Integer,Integer> duplicate=new HashMap<>();
        for(int v:verts){float[] p=vertices.get(v);int id=vertices.size();vertices.add(new float[]{p[0]+n[0]*distance,p[1]+n[1]*distance,p[2]+n[2]*distance});duplicate.put(v,id);}
        ArrayList<Face> out=new ArrayList<>(faces);
        for(int fi:selectedFaceIds){int[] f=faces.get(fi).vertices;int[] top=new int[f.length];for(int j=0;j<f.length;j++)top[j]=duplicate.get(f[j]);out.add(new Face(top));}
        // Side walls are generated only along the boundary of the selected face region.
        for(Edge edge:edges){int selectedAdj=0;for(int fi:edge.faces)if(selectedFaceIds.contains(fi))selectedAdj++;
            if(selectedAdj==1)out.add(new Face(edge.a,edge.b,duplicate.get(edge.b),duplicate.get(edge.a)));
        }
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void extrudeSelectedAlongNormals(float distance,boolean individual){
        if(selectedFaces.isEmpty())return;
        ArrayList<Face> out=new ArrayList<>(faces);
        if(individual){
            for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1)){
                Face face=faces.get(fi);float[] n=normal(face.vertices);float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(len<1e-8)continue;
                for(int k=0;k<3;k++)n[k]/=len;HashMap<Integer,Integer> dup=new HashMap<>();
                for(int id:face.vertices){float[] p=vertices.get(id);int next=vertices.size();vertices.add(new float[]{p[0]+n[0]*distance,p[1]+n[1]*distance,p[2]+n[2]*distance});dup.put(id,next);}
                int[] top=new int[face.vertices.length];for(int j=0;j<top.length;j++)top[j]=dup.get(face.vertices[j]);out.add(new Face(top));
                for(int j=0;j<face.vertices.length;j++){int a=face.vertices[j],b=face.vertices[(j+1)%face.vertices.length];out.add(new Face(a,b,dup.get(b),dup.get(a)));}
            }
        }else{
            float[][] normals=calculateNormals();BitSet selected=(BitSet)selectedVertices.clone();
            if(selected.isEmpty())for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1))for(int id:faces.get(fi).vertices)selected.set(id);
            HashMap<Integer,Integer> dup=new HashMap<>();
            for(int id=selected.nextSetBit(0);id>=0;id=selected.nextSetBit(id+1)){float[] p=vertices.get(id),n=normals[id];int next=vertices.size();vertices.add(new float[]{p[0]+n[0]*distance,p[1]+n[1]*distance,p[2]+n[2]*distance});dup.put(id,next);}
            HashSet<Integer> selectedIds=new HashSet<>();for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1))selectedIds.add(fi);
            for(int fi:selectedIds){int[] f=faces.get(fi).vertices;int[] top=new int[f.length];for(int j=0;j<f.length;j++)top[j]=dup.get(f[j]);out.add(new Face(top));}
            for(Edge edge:edges){int count=0;for(int fi:edge.faces)if(selectedIds.contains(fi))count++;if(count==1)out.add(new Face(edge.a,edge.b,dup.get(edge.b),dup.get(edge.a)));}
        }
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void dissolveSelectedEdges(){
        if(selectedEdges.isEmpty())return;
        HashSet<Long> targets=new HashSet<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);targets.add(edgeKey(e.a,e.b));}
        for(long target:targets){
            Edge edge=null;for(Edge candidate:edges)if(edgeKey(candidate.a,candidate.b)==target){edge=candidate;break;}
            if(edge==null||edge.faces.size()!=2)continue;
            int fa=edge.faces.get(0),fb=edge.faces.get(1);int[] a=faces.get(fa).vertices,b=faces.get(fb).vertices;
            ArrayList<Integer> pathA=boundaryPath(a,edge.b,edge.a,target),pathB=boundaryPath(b,edge.a,edge.b,target);
            if(pathA.size()<2||pathB.size()<2)continue;
            ArrayList<Integer> merged=new ArrayList<>(pathA);for(int i=1;i<pathB.size()-1;i++)merged.add(pathB.get(i));
            LinkedHashSet<Integer> unique=new LinkedHashSet<>(merged);if(unique.size()<3)continue;
            int[] polygon=new int[unique.size()];int k=0;for(int id:unique)polygon[k++]=id;
            int hi=Math.max(fa,fb),lo=Math.min(fa,fb);faces.remove(hi);faces.remove(lo);faces.add(new Face(polygon));rebuildEdges();
        }
        clearSelection();revision++;
    }
    private ArrayList<Integer> boundaryPath(int[] face,int start,int end,long sharedEdge){
        int startIndex=-1;for(int i=0;i<face.length;i++)if(face[i]==start){startIndex=i;break;}
        if(startIndex<0)return new ArrayList<>();
        for(int direction:new int[]{1,-1}){
            ArrayList<Integer> path=new ArrayList<>();int index=startIndex;path.add(face[index]);
            for(int step=0;step<face.length;step++){
                int next=(index+direction+face.length)%face.length;
                if(edgeKey(face[index],face[next])==sharedEdge)break;
                path.add(face[next]);if(face[next]==end)return path;index=next;
            }
        }return new ArrayList<>();
    }
    public void recalculateNormals(){
        int count=faces.size();ArrayList<int[]>[] adjacency=new ArrayList[count];
        for(int i=0;i<count;i++)adjacency[i]=new ArrayList<>();
        boolean closed=true;
        for(Edge edge:edges){
            if(edge.faces.size()!=2){closed=false;continue;}
            int a=edge.faces.get(0),b=edge.faces.get(1);
            boolean da=hasDirectedEdge(faces.get(a).vertices,edge.a,edge.b);
            boolean db=hasDirectedEdge(faces.get(b).vertices,edge.a,edge.b);
            int relation=da==db?1:0;
            adjacency[a].add(new int[]{b,relation});adjacency[b].add(new int[]{a,relation});
        }
        boolean[] visited=new boolean[count],flip=new boolean[count];
        for(int start=0;start<count;start++)if(!visited[start]){
            ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);visited[start]=true;
            while(!queue.isEmpty()){int current=queue.removeFirst();for(int[] link:adjacency[current]){
                int next=link[0];boolean required=flip[current]^(link[1]==1);
                if(!visited[next]){visited[next]=true;flip[next]=required;queue.add(next);}
            }}
        }
        for(int i=0;i<count;i++)if(flip[i])reverseFace(faces.get(i));
        if(closed){
            double volume=0;for(Face face:faces){int[] f=face.vertices;float[] p0=vertices.get(f[0]);
                for(int i=1;i<f.length-1;i++){float[] p1=vertices.get(f[i]),p2=vertices.get(f[i+1]);
                    volume+=((double)p0[0]*(p1[1]*p2[2]-p1[2]*p2[1])
                            +(double)p0[1]*(p1[2]*p2[0]-p1[0]*p2[2])
                            +(double)p0[2]*(p1[0]*p2[1]-p1[1]*p2[0]))/6.0;
                }}
            if(volume<0)for(Face face:faces)reverseFace(face);
        }
        clearSelection();rebuildEdges();revision++;
    }
    private boolean hasDirectedEdge(int[] face,int a,int b){
        for(int i=0;i<face.length;i++)if(face[i]==a&&face[(i+1)%face.length]==b)return true;return false;
    }
    private void reverseFace(Face face){
        for(int i=0,j=face.vertices.length-1;i<j;i++,j--){int t=face.vertices[i];face.vertices[i]=face.vertices[j];face.vertices[j]=t;}
    }
    public void triangulateSelectedFaces(){
        ArrayList<Face> out=new ArrayList<>();
        for(int i=0;i<faces.size();i++){Face f=faces.get(i);if(!selectedFaces.isEmpty()&&!selectedFaces.get(i)){out.add(f);continue;}
            int[] ids=f.vertices;for(int j=1;j<ids.length-1;j++)out.add(new Face(ids[0],ids[j],ids[j+1]));
        }faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void mergeSelectedByDistance(float threshold){
        if(selectedVertices.cardinality()<2)return;
        int[] map=new int[vertices.size()];for(int i=0;i<map.length;i++)map[i]=i;
        for(int a=selectedVertices.nextSetBit(0);a>=0;a=selectedVertices.nextSetBit(a+1))
            for(int b=selectedVertices.nextSetBit(a+1);b>=0;b=selectedVertices.nextSetBit(b+1)){
                float[] p=vertices.get(a),q=vertices.get(b);
                if(Math.hypot(Math.hypot(p[0]-q[0],p[1]-q[1]),p[2]-q[2])<=threshold)map[b]=a;
            }
        for(Face face:faces){
            ArrayList<Integer> ids=new ArrayList<>();
            for(int id:face.vertices){int mapped=map[id];if(ids.isEmpty()||ids.get(ids.size()-1)!=mapped)ids.add(mapped);}
            if(ids.size()>1&&ids.get(0).equals(ids.get(ids.size()-1)))ids.remove(ids.size()-1);
            int[] result=new int[ids.size()];for(int i=0;i<result.length;i++)result[i]=ids.get(i);face.vertices=result;
        }
        faces.removeIf(face->face.vertices.length<3);
        boolean[] used=new boolean[vertices.size()];for(Face face:faces)for(int id:face.vertices)used[id]=true;
        int[] compact=new int[vertices.size()];Arrays.fill(compact,-1);ArrayList<float[]> kept=new ArrayList<>();ArrayList<Float> keptMask=new ArrayList<>();
        for(int i=0;i<vertices.size();i++)if(used[i]){compact[i]=kept.size();kept.add(vertices.get(i));keptMask.add(i<sculptMask.length?sculptMask[i]:0f);}
        for(Face face:faces)for(int i=0;i<face.vertices.length;i++)face.vertices[i]=compact[face.vertices[i]];
        vertices.clear();vertices.addAll(kept);sculptMask=new float[keptMask.size()];for(int i=0;i<sculptMask.length;i++)sculptMask[i]=keptMask.get(i);faceUvs=null;islands.clear();clearSelection();rebuildEdges();revision++;
    }
    public float[][] copyVertices(){float[][] out=new float[vertices.size()][3];for(int i=0;i<out.length;i++)System.arraycopy(vertices.get(i),0,out[i],0,3);return out;}
    public int[][] copyFaces(){int[][] out=new int[faces.size()][];for(int i=0;i<out.length;i++)out[i]=faces.get(i).vertices.clone();return out;}
    public void setFaceUvs(float[][][] uvs){
        if(uvs==null){faceUvs=null;islands.clear();selectedUvIsland=-1;revision++;return;}
        if(uvs.length!=faces.size())throw new IllegalArgumentException("UV face count must match mesh face count");
        float[][][] copy=new float[uvs.length][][];
        for(int fi=0;fi<uvs.length;fi++){
            if(uvs[fi]==null||uvs[fi].length!=faces.get(fi).vertices.length)
                throw new IllegalArgumentException("UV corner count must match face "+fi);
            copy[fi]=new float[uvs[fi].length][2];
            for(int corner=0;corner<uvs[fi].length;corner++){
                if(uvs[fi][corner]==null||uvs[fi][corner].length<2
                        ||!Float.isFinite(uvs[fi][corner][0])||!Float.isFinite(uvs[fi][corner][1]))
                    throw new IllegalArgumentException("UV coordinates must be finite");
                copy[fi][corner][0]=uvs[fi][corner][0];copy[fi][corner][1]=uvs[fi][corner][1];
            }
        }
        faceUvs=copy;rebuildIslands();selectedUvIsland=-1;revision++;
    }
    /** Append primitive geometry to the active editable mesh in mesh-local coordinates. */
    public void appendGeometry(float[][] addedVertices,int[][] addedFaces,float offsetX,float offsetY,float offsetZ) {
        if(addedVertices==null||addedFaces==null)
            throw new IllegalArgumentException("Added mesh vertices and faces are required.");
        if(addedVertices.length==0||addedFaces.length==0)
            throw new IllegalArgumentException("The added primitive has no polygon geometry.");
        int vertexBase=vertices.size(),faceBase=faces.size();
        for(float[] point:addedVertices) {
            if(point==null||point.length<3||!Float.isFinite(point[0])||!Float.isFinite(point[1])
                    ||!Float.isFinite(point[2]))
                throw new IllegalArgumentException("Primitive coordinates must be finite XYZ values.");
            vertices.add(new float[]{point[0]+offsetX,point[1]+offsetY,point[2]+offsetZ});
        }
        for(int[] polygon:addedFaces) {
            if(polygon==null||polygon.length<3)
                throw new IllegalArgumentException("Primitive polygons need at least three vertices.");
            int[] copy=polygon.clone();
            for(int i=0;i<copy.length;i++) {
                if(copy[i]<0||copy[i]>=addedVertices.length)
                    throw new IllegalArgumentException("Primitive polygon index is out of range.");
                copy[i]+=vertexBase;
            }
            faces.add(new Face(copy));
        }
        faceUvs=null;islands.clear();selectedUvIsland=-1;
        clearSelection();
        for(int i=vertexBase;i<vertices.size();i++)selectedVertices.set(i);
        for(int i=faceBase;i<faces.size();i++)selectedFaces.set(i);
        rebuildEdges();
        revision++;
    }

    public void replaceGeometry(float[][] v,int[][] f){replaceGeometry(v,f,null,false);}
    public void replaceGeometry(float[][] v,int[][] f,float[][][] uvs){replaceGeometry(v,f,uvs,true);}
    private void replaceGeometry(float[][] v,int[][] f,float[][][] uvs,boolean replaceUvs){
        if(v==null||f==null)throw new IllegalArgumentException("Mesh vertices and faces are required");
        boolean sameTopology=v.length==vertices.size()&&f.length==faces.size();
        if(sameTopology)for(int fi=0;fi<f.length;fi++)
            if(!java.util.Arrays.equals(f[fi],faces.get(fi).vertices)){sameTopology=false;break;}
        vertices.clear();for(float[] p:v){
            if(p==null||p.length<3||!Float.isFinite(p[0])||!Float.isFinite(p[1])||!Float.isFinite(p[2]))
                throw new IllegalArgumentException("Mesh vertices must contain finite XYZ coordinates");
            vertices.add(new float[]{p[0],p[1],p[2]});
        }
        faces.clear();for(int[] ids:f){
            if(ids==null||ids.length<3)throw new IllegalArgumentException("Mesh faces need at least three vertices");
            int[] copy=ids.clone();for(int id:copy)if(id<0||id>=vertices.size())throw new IllegalArgumentException("Mesh face index is out of range");
            faces.add(new Face(copy));
        }
        if(!sameTopology)clearSelection();
        if(replaceUvs){
            if(uvs==null){faceUvs=null;islands.clear();}
            else{
                if(uvs.length!=faces.size())throw new IllegalArgumentException("UV face count must match mesh face count");
                float[][][] copy=new float[uvs.length][][];
                for(int fi=0;fi<uvs.length;fi++){
                    if(uvs[fi]==null||uvs[fi].length!=faces.get(fi).vertices.length)
                        throw new IllegalArgumentException("UV corner count must match face "+fi);
                    copy[fi]=new float[uvs[fi].length][2];
                    for(int ci=0;ci<uvs[fi].length;ci++){
                        if(uvs[fi][ci]==null||uvs[fi][ci].length<2
                                ||!Float.isFinite(uvs[fi][ci][0])||!Float.isFinite(uvs[fi][ci][1]))
                            throw new IllegalArgumentException("UV coordinates must be finite");
                        copy[fi][ci][0]=uvs[fi][ci][0];copy[fi][ci][1]=uvs[fi][ci][1];
                    }
                }
                faceUvs=copy;
            }
        }else if(!sameTopology){faceUvs=null;islands.clear();}
        rebuildEdges();if(faceUvs!=null)rebuildIslands();revision++;
    }
    public Meshy3DCore copy(){
        Meshy3DCore duplicate=new Meshy3DCore();
        if(!duplicate.importState(exportState()))throw new IllegalStateException("Could not copy mesh datablock");
        return duplicate;
    }
    public void selectNearestVertex(float x,float y,float cx,float cy,float unit,float yaw,float pitch,boolean additive){
        int best=-1;float bestD=Float.MAX_VALUE;
        for(int i=0;i<vertices.size();i++){float[] p=project(vertices.get(i),cx,cy,unit,yaw,pitch);float d=(p[0]-x)*(p[0]-x)+(p[1]-y)*(p[1]-y);if(d<bestD){bestD=d;best=i;}}
        if(best>=0&&bestD<900f)selectVertex(best,additive);
    }
    public void selectNearestFace(float x,float y,float cx,float cy,float unit,float yaw,float pitch,boolean additive){
        int best=-1;float bestD=Float.MAX_VALUE;
        for(int i=0;i<faces.size();i++){int[] f=faces.get(i).vertices;float ax=0,ay=0;for(int id:f){float[] p=project(vertices.get(id),cx,cy,unit,yaw,pitch);ax+=p[0];ay+=p[1];}ax/=f.length;ay/=f.length;float d=(ax-x)*(ax-x)+(ay-y)*(ay-y);if(d<bestD){bestD=d;best=i;}}
        if(best>=0&&bestD<2500f)selectFace(best,additive);
    }
    public void selectNearestEdge(float x,float y,float cx,float cy,float unit,float yaw,float pitch,boolean additive){
        int best=-1;float bestD=Float.MAX_VALUE;
        for(int i=0;i<edges.size();i++){Edge e=edges.get(i);float[] a=project(vertices.get(e.a),cx,cy,unit,yaw,pitch),b=project(vertices.get(e.b),cx,cy,unit,yaw,pitch);
            float dx=b[0]-a[0],dy=b[1]-a[1],t=((x-a[0])*dx+(y-a[1])*dy)/Math.max(.0001f,dx*dx+dy*dy);t=Math.max(0,Math.min(1,t));float px=a[0]+t*dx,py=a[1]+t*dy;float d=(px-x)*(px-x)+(py-y)*(py-y);if(d<bestD){bestD=d;best=i;}}
        if(best>=0&&bestD<625f)selectEdge(best,additive);
    }
    private static float[] project(float[] p,float cx,float cy,float unit,float yaw,float pitch){
        double cyaw=Math.cos(yaw),syaw=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
        double rx=p[0]*cyaw-p[2]*syaw,rz=p[0]*syaw+p[2]*cyaw;
        double ry=p[1]*cp-rz*sp,rz2=p[1]*sp+rz*cp;double depth=5.2-rz2;
        double perspective=5.2/Math.max(2.0,depth);return new float[]{(float)(cx+rx*unit*perspective),(float)(cy-ry*unit*perspective)};
    }
    public void selectEdgeLoops(){
        if(selectedEdges.isEmpty())return;
        ArrayDeque<Long> queue=new ArrayDeque<>();HashSet<Long> seen=new HashSet<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);long k=edgeKey(e.a,e.b);if(seen.add(k))queue.add(k);}
        HashMap<Long,Edge> map=new HashMap<>();for(Edge e:edges)map.put(edgeKey(e.a,e.b),e);
        while(!queue.isEmpty()){long key=queue.removeFirst();Edge e=map.get(key);if(e==null)continue;
            for(int fi:e.faces){int[] f=faces.get(fi).vertices;if(f.length!=4)continue;
                for(int j=0;j<4;j++)if(edgeKey(f[j],f[(j+1)%4])==key){
                    long opposite=edgeKey(f[(j+2)%4],f[(j+3)%4]);if(seen.add(opposite))queue.add(opposite);break;
                }
            }
        }
        selectedEdges.clear();selectedVertices.clear();
        for(int i=0;i<edges.size();i++){Edge e=edges.get(i);if(seen.contains(edgeKey(e.a,e.b))){selectedEdges.set(i);selectedVertices.set(e.a);selectedVertices.set(e.b);}}
        revision++;
    }
    public void selectSimilar(){
        if(selectMode==SelectMode.FACE){
            int seed=selectedFaces.nextSetBit(0);if(seed<0)return;int count=faces.get(seed).vertices.length;
            for(int i=0;i<faces.size();i++)if(faces.get(i).vertices.length==count){selectedFaces.set(i);for(int v:faces.get(i).vertices)selectedVertices.set(v);}
        }else if(selectMode==SelectMode.VERTEX){
            int seed=selectedVertices.nextSetBit(0);if(seed<0)return;int degree=0;
            for(Edge e:edges)if(e.a==seed||e.b==seed)degree++;
            for(int i=0;i<vertices.size();i++){int d=0;for(Edge e:edges)if(e.a==i||e.b==i)d++;if(d==degree)selectedVertices.set(i);}
        }else{
            int seed=selectedEdges.nextSetBit(0);if(seed<0)return;int count=edges.get(seed).faces.size();
            for(int i=0;i<edges.size();i++)if(edges.get(i).faces.size()==count)selectedEdges.set(i);
        }revision++;
    }
    private BitSet selectedOrAllVertices(){
        BitSet out=(BitSet)selectedVertices.clone();if(out.isEmpty())out.set(0,vertices.size());return out;
    }
    private float[][] calculateNormals(){
        float[][] normals=new float[vertices.size()][3];
        for(Face face:faces){int[] f=face.vertices;if(f.length<3)continue;float[] a=vertices.get(f[0]);
            for(int j=1;j<f.length-1;j++){float[] b=vertices.get(f[j]),d=vertices.get(f[j+1]);
                float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=d[0]-a[0],vy=d[1]-a[1],vz=d[2]-a[2];
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                for(int id:f){normals[id][0]+=nx;normals[id][1]+=ny;normals[id][2]+=nz;}
            }
        }
        for(float[] n:normals){float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(len>1e-8){n[0]/=len;n[1]/=len;n[2]/=len;}}
        return normals;
    }
    public void mirrorSelected(int axis){
        BitSet targets=selectedOrAllVertices();for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1))vertices.get(i)[Math.max(0,Math.min(2,axis))]*=-1;
        if(targets.cardinality()==vertices.size())for(Face f:faces)reverseFace(f);
        rebuildEdges();revision++;
    }
    public void shrinkFattenSelected(float amount){
        float[][] normals=calculateNormals();BitSet targets=selectedOrAllVertices();
        for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){float[] p=vertices.get(i),n=normals[i];p[0]+=n[0]*amount;p[1]+=n[1]*amount;p[2]+=n[2]*amount;}
        revision++;
    }
    public void makePlanarSelected(){
        BitSet targets=selectedOrAllVertices();if(targets.cardinality()<3)return;
        float[] center=new float[3];for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1))for(int k=0;k<3;k++)center[k]+=vertices.get(i)[k]/targets.cardinality();
        float[] normal={0,0,0};for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1)){float[] n=normal(faces.get(fi).vertices);for(int k=0;k<3;k++)normal[k]+=n[k];}
        float len=(float)Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
        if(len<1e-7){int[] ids=new int[targets.cardinality()];int p=0;for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1))ids[p++]=i;
            float[] a=vertices.get(ids[0]),b=vertices.get(ids[1]),d=vertices.get(ids[2]);float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=d[0]-a[0],vy=d[1]-a[1],vz=d[2]-a[2];
            normal=new float[]{uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx};len=(float)Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);}
        if(len<1e-7)return;for(int k=0;k<3;k++)normal[k]/=len;
        for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){float[] v=vertices.get(i);float d=(v[0]-center[0])*normal[0]+(v[1]-center[1])*normal[1]+(v[2]-center[2])*normal[2];for(int k=0;k<3;k++)v[k]-=normal[k]*d;}
        revision++;
    }
    public void randomizeSelected(float amount){
        BitSet targets=selectedOrAllVertices();for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){
            float[] p=vertices.get(i);float a=(float)Math.sin((i+1)*12.9898+revision*78.233),b=(float)Math.sin((i+1)*39.346+revision*11.135),d=(float)Math.sin((i+1)*73.156+revision*3.17);
            p[0]+=a*amount;p[1]+=b*amount;p[2]+=d*amount;
        }revision++;
    }
    public void toSphereSelected(float factor){
        BitSet targets=selectedOrAllVertices();if(targets.isEmpty())return;float[] center=new float[3];
        for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1))for(int k=0;k<3;k++)center[k]+=vertices.get(i)[k]/targets.cardinality();
        float radius=0;for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){float[] p=vertices.get(i);radius+=(float)Math.sqrt((p[0]-center[0])*(p[0]-center[0])+(p[1]-center[1])*(p[1]-center[1])+(p[2]-center[2])*(p[2]-center[2]))/targets.cardinality();}
        factor=Math.max(0,Math.min(1,factor));for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){float[] p=vertices.get(i);float dx=p[0]-center[0],dy=p[1]-center[1],dz=p[2]-center[2],len=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(len>1e-8){float t=radius/len*factor;p[0]+=dx*(t-1);p[1]+=dy*(t-1);p[2]+=dz*(t-1);}}
        revision++;
    }
    public void shearSelected(float amount){
        BitSet targets=selectedOrAllVertices();for(int i=targets.nextSetBit(0);i>=0;i=targets.nextSetBit(i+1)){float[] p=vertices.get(i);p[0]+=p[1]*amount;}revision++;
    }
    public void flipSelectedNormals(){
        if(selectedFaces.isEmpty()){for(Face f:faces)reverseFace(f);}else for(int i=selectedFaces.nextSetBit(0);i>=0;i=selectedFaces.nextSetBit(i+1))reverseFace(faces.get(i));
        rebuildEdges();revision++;
    }
    public void pokeSelectedFaces(float offset){
        if(selectedFaces.isEmpty())return;ArrayList<Face> out=new ArrayList<>();
        for(int i=0;i<faces.size();i++){Face f=faces.get(i);if(!selectedFaces.get(i)){out.add(f);continue;}
            float[] center=new float[3],n=normal(f.vertices);float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(len>1e-8){n[0]/=len;n[1]/=len;n[2]/=len;}
            for(int id:f.vertices)for(int k=0;k<3;k++)center[k]+=vertices.get(id)[k]/f.vertices.length;
            int centerId=vertices.size();vertices.add(new float[]{center[0]+n[0]*offset,center[1]+n[1]*offset,center[2]+n[2]*offset});
            for(int j=0;j<f.vertices.length;j++)out.add(new Face(f.vertices[j],f.vertices[(j+1)%f.vertices.length],centerId));
        }
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public int addVertex(float x,float y,float z){
        int id=vertices.size();vertices.add(new float[]{x,y,z});sculptMask=Arrays.copyOf(sculptMask,vertices.size());revision++;return id;
    }
    public boolean addFaceFromVertices(int[] ids){
        if(ids==null||ids.length<3)return false;HashSet<Integer> unique=new HashSet<>();
        for(int id:ids)if(id<0||id>=vertices.size()||!unique.add(id))return false;
        faces.add(new Face(ids.clone()));rebuildEdges();revision++;return true;
    }
    public void decimatePlanar(float maxAngleDegrees,int targetFaces){
        targetFaces=Math.max(4,targetFaces);float cos=(float)Math.cos(Math.toRadians(Math.max(0,Math.min(90,maxAngleDegrees))));
        int guard=0;
        while(faces.size()>targetFaces&&guard++<5000){
            int best=-1;float bestDot=cos-1e-5f;
            for(int i=0;i<edges.size();i++){Edge e=edges.get(i);if(e.faces.size()!=2)continue;
                float[] a=normal(faces.get(e.faces.get(0)).vertices),b=normal(faces.get(e.faces.get(1)).vertices);
                float la=(float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]),lb=(float)Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2]);
                if(la<1e-8||lb<1e-8)continue;float dot=(a[0]*b[0]+a[1]*b[1]+a[2]*b[2])/(la*lb);
                if(dot>bestDot){bestDot=dot;best=i;}
            }
            if(best<0)break;selectedEdges.clear();selectedEdges.set(best);dissolveSelectedEdges();
        }
        revision++;
    }
    /** Convert each unique mesh edge into an independent rectangular wire beam. */
    public void wireframe(float requestedThickness) {
        float radius=Math.max(.001f,Math.min(.5f,requestedThickness));
        if(edges.isEmpty())throw new IllegalStateException("Wireframe requires mesh edges.");
        if(edges.size()>30000)throw new IllegalStateException("Wireframe result would exceed the mobile geometry limit.");
        ArrayList<float[]> outputVertices=new ArrayList<>(edges.size()*8);
        ArrayList<int[]> outputFaces=new ArrayList<>(edges.size()*6);
        for(Edge edge:edges) {
            float[] a=vertices.get(edge.a),b=vertices.get(edge.b);
            float dx=b[0]-a[0],dy=b[1]-a[1],dz=b[2]-a[2];
            float length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(length<1e-7f)continue;
            dx/=length;dy/=length;dz/=length;
            float rx=Math.abs(dy)<.85f?0f:1f;
            float ry=Math.abs(dy)<.85f?1f:0f;
            float rz=0f;
            float ux=dy*rz-dz*ry,uy=dz*rx-dx*rz,uz=dx*ry-dy*rx;
            float ul=(float)Math.sqrt(ux*ux+uy*uy+uz*uz);
            if(ul<1e-7f)continue;
            ux/=ul;uy/=ul;uz/=ul;
            float vx=dy*uz-dz*uy,vy=dz*ux-dx*uz,vz=dx*uy-dy*ux;
            int base=outputVertices.size();
            for(int endpoint=0;endpoint<2;endpoint++){
                float[] p=endpoint==0?a:b;
                for(int corner=0;corner<4;corner++){
                    float su=(corner==0||corner==3)?-1f:1f;
                    float sv=(corner<2)?-1f:1f;
                    outputVertices.add(new float[]{p[0]+radius*(ux*su+vx*sv),
                            p[1]+radius*(uy*su+vy*sv),p[2]+radius*(uz*su+vz*sv)});
                }
            }
            for(int side=0;side<4;side++)
                outputFaces.add(new int[]{base+side,base+(side+1)%4,
                        base+4+(side+1)%4,base+4+side});
            outputFaces.add(new int[]{base+3,base+2,base+1,base});
            outputFaces.add(new int[]{base+4,base+5,base+6,base+7});
        }
        if(outputFaces.isEmpty())throw new IllegalStateException("No usable edges were found.");
        faceUvs=null;islands.clear();selectedUvIsland=-1;
        replaceGeometry(outputVertices.toArray(new float[0][]),outputFaces.toArray(new int[0][]));
        sculptMask=new float[vertices.size()];
        revision++;
    }

    public void splitSelectedFaces(){
        if(selectedFaces.isEmpty())return;
        HashMap<Integer,Integer> duplicates=new HashMap<>();
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1)){
            Face face=faces.get(fi);for(int j=0;j<face.vertices.length;j++){int old=face.vertices[j];Integer id=duplicates.get(old);
                if(id==null){id=vertices.size();vertices.add(vertices.get(old).clone());duplicates.put(old,id);}face.vertices[j]=id;}
        }clearSelection();rebuildEdges();revision++;
    }
    /** True Catmull-Clark subdivision for polygon meshes. */
    public void subdivideCatmullClark(int requestedLevels) {
        int levels=Math.max(1,Math.min(3,requestedLevels));
        for(int level=0;level<levels;level++) {
            int oldVertexCount=vertices.size(),oldFaceCount=faces.size();
            if(oldVertexCount==0||oldFaceCount==0)return;
            float[][] oldVertices=copyVertices();
            int[][] oldFaces=copyFaces();

            float[][] facePoints=new float[oldFaceCount][3];
            java.util.ArrayList<java.util.ArrayList<Integer>> vertexFaces=new java.util.ArrayList<>();
            java.util.ArrayList<java.util.ArrayList<Integer>> vertexNeighbours=new java.util.ArrayList<>();
            java.util.ArrayList<java.util.ArrayList<Integer>> boundaryNeighbours=new java.util.ArrayList<>();
            for(int i=0;i<oldVertexCount;i++){
                vertexFaces.add(new java.util.ArrayList<>());
                vertexNeighbours.add(new java.util.ArrayList<>());
                boundaryNeighbours.add(new java.util.ArrayList<>());
            }
            for(int fi=0;fi<oldFaceCount;fi++){
                int[] face=oldFaces[fi];
                for(int id:face){
                    for(int axis=0;axis<3;axis++)facePoints[fi][axis]+=oldVertices[id][axis]/face.length;
                    vertexFaces.get(id).add(fi);
                }
            }
            java.util.HashMap<Long,Integer> edgeFaces=new java.util.HashMap<>();
            for(Edge edge:edges){
                vertexNeighbours.get(edge.a).add(edge.b);
                vertexNeighbours.get(edge.b).add(edge.a);
                if(edge.faces.size()==1){
                    boundaryNeighbours.get(edge.a).add(edge.b);
                    boundaryNeighbours.get(edge.b).add(edge.a);
                }
            }

            java.util.ArrayList<float[]> outputVertices=new java.util.ArrayList<>();
            // Updated original-vertex points occupy the first vertex range.
            for(int id=0;id<oldVertexCount;id++){
                float[] p=oldVertices[id];
                java.util.ArrayList<Integer> neighbours=vertexNeighbours.get(id);
                java.util.ArrayList<Integer> incident=vertexFaces.get(id);
                java.util.ArrayList<Integer> boundary=boundaryNeighbours.get(id);
                float[] result=p.clone();
                if(boundary.size()==2){
                    float[] a=oldVertices[boundary.get(0)],b=oldVertices[boundary.get(1)];
                    for(int axis=0;axis<3;axis++)
                        result[axis]=.75f*p[axis]+.125f*a[axis]+.125f*b[axis];
                }else if(!neighbours.isEmpty()&&!incident.isEmpty()){
                    float n=neighbours.size();
                    float[] faceAverage={0,0,0},edgeAverage={0,0,0};
                    for(int fi:incident)for(int axis=0;axis<3;axis++)
                        faceAverage[axis]+=facePoints[fi][axis]/incident.size();
                    for(int other:neighbours)for(int axis=0;axis<3;axis++)
                        edgeAverage[axis]+=(p[axis]+oldVertices[other][axis])*.5f/n;
                    for(int axis=0;axis<3;axis++)
                        result[axis]=(faceAverage[axis]+2f*edgeAverage[axis]+(n-3f)*p[axis])/n;
                }
                outputVertices.add(result);
            }

            // Edge points: average endpoints and adjacent face points for manifold edges.
            java.util.HashMap<Long,Integer> edgePointIds=new java.util.HashMap<>();
            for(Edge edge:edges){
                float[] a=oldVertices[edge.a],b=oldVertices[edge.b];
                float[] point=new float[3];
                for(int axis=0;axis<3;axis++){
                    if(edge.faces.size()==2){
                        point[axis]=(a[axis]+b[axis]
                                +facePoints[edge.faces.get(0)][axis]
                                +facePoints[edge.faces.get(1)][axis])*.25f;
                    }else point[axis]=(a[axis]+b[axis])*.5f;
                }
                int id=outputVertices.size();outputVertices.add(point);
                edgePointIds.put(edgeKey(edge.a,edge.b),id);
            }

            int[] facePointIds=new int[oldFaceCount];
            for(int fi=0;fi<oldFaceCount;fi++){
                facePointIds[fi]=outputVertices.size();
                outputVertices.add(facePoints[fi]);
            }

            java.util.ArrayList<int[]> outputFaces=new java.util.ArrayList<>();
            for(int fi=0;fi<oldFaceCount;fi++){
                int[] face=oldFaces[fi];
                for(int corner=0;corner<face.length;corner++){
                    int current=face[corner],next=face[(corner+1)%face.length];
                    int previous=face[(corner+face.length-1)%face.length];
                    Integer nextEdge=edgePointIds.get(edgeKey(current,next));
                    Integer previousEdge=edgePointIds.get(edgeKey(previous,current));
                    if(nextEdge==null||previousEdge==null)continue;
                    outputFaces.add(new int[]{current,nextEdge,facePointIds[fi],previousEdge});
                }
            }
            faceUvs=null;islands.clear();selectedUvIsland=-1;
            float[][] newVertices=outputVertices.toArray(new float[0][]);
            int[][] newFaces=outputFaces.toArray(new int[0][]);
            replaceGeometry(newVertices,newFaces);
            sculptMask=java.util.Arrays.copyOf(sculptMask,newVertices.length);
            clearSelection();
        }
        revision++;
    }

    /** Extract selected faces (or fully selected vertex/edge faces) into a new mesh datablock. */
    public Meshy3DCore extractSelectedFaces() {
        java.util.BitSet chosen=(java.util.BitSet)selectedFaces.clone();
        if(chosen.isEmpty()&& !selectedVertices.isEmpty()){
            for(int fi=0;fi<faces.size();fi++){
                boolean all=true;
                for(int id:faces.get(fi).vertices)if(!selectedVertices.get(id)){all=false;break;}
                if(all)chosen.set(fi);
            }
        }
        if(chosen.isEmpty()&&!selectedEdges.isEmpty()){
            java.util.HashSet<Long> selectedKeys=new java.util.HashSet<>();
            for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){
                Edge edge=edges.get(i);selectedKeys.add(edgeKey(edge.a,edge.b));
            }
            for(int fi=0;fi<faces.size();fi++){
                int[] polygon=faces.get(fi).vertices;boolean all=true;
                for(int i=0;i<polygon.length;i++)
                    if(!selectedKeys.contains(edgeKey(polygon[i],polygon[(i+1)%polygon.length]))){all=false;break;}
                if(all)chosen.set(fi);
            }
        }
        if(chosen.isEmpty())return null;

        int[][] sourceFaces=copyFaces();
        float[][] sourceVertices=copyVertices();
        java.util.LinkedHashMap<Integer,Integer> extractedMap=new java.util.LinkedHashMap<>();
        java.util.ArrayList<float[]> extractedVertices=new java.util.ArrayList<>();
        java.util.ArrayList<int[]> extractedFaces=new java.util.ArrayList<>();
        java.util.ArrayList<int[]> remainingSourceFaces=new java.util.ArrayList<>();
        for(int fi=0;fi<sourceFaces.length;fi++){
            int[] polygon=sourceFaces[fi];
            if(chosen.get(fi)){
                int[] remapped=new int[polygon.length];
                for(int i=0;i<polygon.length;i++){
                    int old=polygon[i];
                    Integer next=extractedMap.get(old);
                    if(next==null){next=extractedVertices.size();extractedMap.put(old,next);extractedVertices.add(sourceVertices[old].clone());}
                    remapped[i]=next;
                }
                extractedFaces.add(remapped);
            }else remainingSourceFaces.add(polygon);
        }
        if(extractedFaces.isEmpty())return null;

        java.util.LinkedHashMap<Integer,Integer> remainingMap=new java.util.LinkedHashMap<>();
        java.util.ArrayList<float[]> remainingVertices=new java.util.ArrayList<>();
        java.util.ArrayList<int[]> remainingFaces=new java.util.ArrayList<>();
        for(int[] polygon:remainingSourceFaces){
            int[] remapped=new int[polygon.length];
            for(int i=0;i<polygon.length;i++){
                int old=polygon[i];
                Integer next=remainingMap.get(old);
                if(next==null){next=remainingVertices.size();remainingMap.put(old,next);remainingVertices.add(sourceVertices[old].clone());}
                remapped[i]=next;
            }
            remainingFaces.add(remapped);
        }
        Meshy3DCore separated=new Meshy3DCore();
        separated.replaceGeometry(extractedVertices.toArray(new float[0][]),extractedFaces.toArray(new int[0][]));
        separated.setMaterial(getBaseColor(),getMetallic(),getRoughness());
        replaceGeometry(remainingVertices.toArray(new float[0][]),remainingFaces.toArray(new int[0][]));
        clearSelection();
        revision++;
        return separated;
    }

    /**
     * Fill one selected boundary loop with a quad grid. The boundary must have
     * four equal edge spans (vertex count divisible by four); other loops are
     * rejected instead of producing unpredictable topology.
     */
    public boolean gridFillSelectedBoundary() {
        java.util.HashMap<Integer,java.util.ArrayList<Integer>> adjacency=new java.util.HashMap<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){
            Edge edge=edges.get(i);
            if(edge.faces.size()!=1)continue;
            adjacency.computeIfAbsent(edge.a,k->new java.util.ArrayList<>()).add(edge.b);
            adjacency.computeIfAbsent(edge.b,k->new java.util.ArrayList<>()).add(edge.a);
        }
        if(adjacency.size()<4||adjacency.size()%4!=0)return false;
        for(java.util.ArrayList<Integer> neighbours:adjacency.values())if(neighbours.size()!=2)return false;
        java.util.ArrayList<Integer> loop=new java.util.ArrayList<>();
        int start=adjacency.keySet().iterator().next(),previous=-1,current=start;
        do {
            loop.add(current);
            java.util.ArrayList<Integer> neighbours=adjacency.get(current);
            int next=neighbours.get(0)==previous?neighbours.get(1):neighbours.get(0);
            previous=current;current=next;
            if(loop.size()>adjacency.size()+1)return false;
        } while(current!=start);
        if(loop.size()!=adjacency.size())return false;
        int n=loop.size(),span=n/4;
        // Rotate the loop so the four most strongly turning corners define the grid corners.
        int bestOffset=0;double bestScore=-Double.MAX_VALUE;
        for(int offset=0;offset<n;offset++){
            double score=0;
            for(int side=0;side<4;side++){
                int index=(offset+side*span)%n;
                float[] before=vertices.get(loop.get((index+n-1)%n));
                float[] point=vertices.get(loop.get(index));
                float[] after=vertices.get(loop.get((index+1)%n));
                double ax=point[0]-before[0],ay=point[1]-before[1],az=point[2]-before[2];
                double bx=after[0]-point[0],by=after[1]-point[1],bz=after[2]-point[2];
                double al=Math.sqrt(ax*ax+ay*ay+az*az),bl=Math.sqrt(bx*bx+by*by+bz*bz);
                if(al>1e-8&&bl>1e-8)score+=1.0-(ax*bx+ay*by+az*bz)/(al*bl);
            }
            if(score>bestScore){bestScore=score;bestOffset=offset;}
        }
        java.util.ArrayList<Integer> ordered=new java.util.ArrayList<>(n);
        for(int i=0;i<n;i++)ordered.add(loop.get((bestOffset+i)%n));
        int p00=ordered.get(0),p10=ordered.get(span),p11=ordered.get(2*span),p01=ordered.get(3*span);
        int[][] grid=new int[span+1][span+1];
        for(int j=0;j<=span;j++)for(int i=0;i<=span;i++){
            if(j==0)grid[j][i]=ordered.get(i);
            else if(i==span)grid[j][i]=ordered.get(span+j);
            else if(j==span)grid[j][i]=ordered.get(3*span-i);
            else if(i==0)grid[j][i]=ordered.get((n-j)%n);
            else {
                float u=i/(float)span,v=j/(float)span;
                float[] bottom=vertices.get(ordered.get(i));
                float[] top=vertices.get(ordered.get(3*span-i));
                float[] left=vertices.get(ordered.get((n-j)%n));
                float[] right=vertices.get(ordered.get(span+j));
                float[] a=vertices.get(p00),b=vertices.get(p10),c=vertices.get(p11),d=vertices.get(p01);
                int id=vertices.size();
                float[] point=new float[3];
                for(int axis=0;axis<3;axis++){
                    float blend=(1-v)*bottom[axis]+v*top[axis]+(1-u)*left[axis]+u*right[axis];
                    float bilinear=(1-u)*(1-v)*a[axis]+u*(1-v)*b[axis]+u*v*c[axis]+(1-u)*v*d[axis];
                    point[axis]=blend-bilinear;
                }
                vertices.add(point);grid[j][i]=id;
            }
        }
        int firstNewFace=faces.size();
        for(int j=0;j<span;j++)for(int i=0;i<span;i++)
            faces.add(new Face(grid[j][i],grid[j][i+1],grid[j+1][i+1],grid[j+1][i]));
        faceUvs=null;islands.clear();selectedUvIsland=-1;clearSelection();
        for(int face=firstNewFace;face<faces.size();face++)selectedFaces.set(face);
        rebuildEdges();revision++;
        return true;
    }

    public void bisect(float nx,float ny,float nz,float offset){
        float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(len<1e-8)return;nx/=len;ny/=len;nz/=len;
        BitSet targets=(BitSet)selectedFaces.clone();boolean all=targets.isEmpty();
        HashMap<Long,Integer> intersections=new HashMap<>();ArrayList<Face> output=new ArrayList<>();
        for(int fi=0;fi<faces.size();fi++){Face face=faces.get(fi);if(!all&&!targets.get(fi)){output.add(face);continue;}
            boolean positive=false,negative=false;for(int id:face.vertices){float[] p=vertices.get(id);float d=p[0]*nx+p[1]*ny+p[2]*nz-offset;if(d>1e-6)positive=true;if(d< -1e-6)negative=true;}
            if(!positive||!negative){output.add(face);continue;}
            int[] a=clipPolygon(face.vertices,nx,ny,nz,offset,1,intersections);
            int[] b=clipPolygon(face.vertices,nx,ny,nz,offset,-1,intersections);
            if(a.length>=3)output.add(new Face(a));if(b.length>=3)output.add(new Face(b));
        }
        faces.clear();faces.addAll(output);clearSelection();rebuildEdges();revision++;
    }
    private int[] clipPolygon(int[] polygon,float nx,float ny,float nz,float offset,int sign,HashMap<Long,Integer> cache){
        ArrayList<Integer> out=new ArrayList<>();
        for(int i=0;i<polygon.length;i++){int current=polygon[i],previous=polygon[(i+polygon.length-1)%polygon.length];
            float[] a=vertices.get(previous),b=vertices.get(current);
            float da=(a[0]*nx+a[1]*ny+a[2]*nz-offset)*sign,db=(b[0]*nx+b[1]*ny+b[2]*nz-offset)*sign;
            boolean ina=da>=-1e-6f,inb=db>=-1e-6f;
            if(ina!=inb){long key=edgeKey(previous,current);Integer id=cache.get(key);
                if(id==null){float t=da/(da-db);id=vertices.size();vertices.add(new float[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t});cache.put(key,id);}out.add(id);}
            if(inb)out.add(current);
        }
        LinkedHashSet<Integer> unique=new LinkedHashSet<>(out);int[] result=new int[unique.size()];int i=0;for(int id:unique)result[i++]=id;return result;
    }
    public void bridgeSelectedBoundaryLoops(){
        HashMap<Integer,ArrayList<Integer>> adjacency=new HashMap<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);if(e.faces.size()!=1)continue;
            adjacency.computeIfAbsent(e.a,k->new ArrayList<>()).add(e.b);adjacency.computeIfAbsent(e.b,k->new ArrayList<>()).add(e.a);}
        ArrayList<ArrayList<Integer>> loops=new ArrayList<>();HashSet<Integer> visited=new HashSet<>();
        for(int start:adjacency.keySet())if(!visited.contains(start)){
            ArrayList<Integer> loop=new ArrayList<>();int previous=-1,current=start;
            do{if(!visited.add(current))break;loop.add(current);ArrayList<Integer> links=adjacency.get(current);if(links==null||links.size()!=2){loop.clear();break;}
                int next=links.get(0)==previous?links.get(1):links.get(0);previous=current;current=next;
            }while(current!=start&&loop.size()<=adjacency.size());
            if(current==start&&loop.size()>=3)loops.add(loop);
        }
        if(loops.size()!=2||loops.get(0).size()!=loops.get(1).size())return;
        ArrayList<Integer> a=loops.get(0),b=loops.get(1);int n=a.size(),best=0;boolean reverse=false;double score=Double.MAX_VALUE;
        for(int offset=0;offset<n;offset++)for(int rev=0;rev<2;rev++){double sum=0;for(int i=0;i<n;i++){float[] p=vertices.get(a.get(i)),q=vertices.get(b.get((offset+(rev==1?n-i:i))%n));sum+=(p[0]-q[0])*(p[0]-q[0])+(p[1]-q[1])*(p[1]-q[1])+(p[2]-q[2])*(p[2]-q[2]);}
            if(sum<score){score=sum;best=offset;reverse=rev==1;}}
        for(int i=0;i<n;i++){int ai=a.get(i),an=a.get((i+1)%n),bi=b.get((best+(reverse?n-i:i))%n),bn=b.get((best+(reverse?n-(i+1):(i+1)))%n);faces.add(new Face(ai,an,bn,bi));}
        clearSelection();rebuildEdges();revision++;
    }
    public void solidifySelected(float thickness){
        BitSet selected=(BitSet)selectedFaces.clone();if(selected.isEmpty())selected.set(0,faces.size());
        float[][] normals=calculateNormals();HashSet<Integer> ids=new HashSet<>();for(int fi=selected.nextSetBit(0);fi>=0;fi=selected.nextSetBit(fi+1))for(int id:faces.get(fi).vertices)ids.add(id);
        HashMap<Integer,Integer> duplicate=new HashMap<>();for(int id:ids){float[] p=vertices.get(id),n=normals[id];int next=vertices.size();vertices.add(new float[]{p[0]-n[0]*thickness,p[1]-n[1]*thickness,p[2]-n[2]*thickness});duplicate.put(id,next);}
        ArrayList<Face> out=new ArrayList<>(faces);for(int fi=selected.nextSetBit(0);fi>=0;fi=selected.nextSetBit(fi+1)){int[] f=faces.get(fi).vertices,inner=new int[f.length];for(int j=0;j<f.length;j++)inner[j]=duplicate.get(f[f.length-1-j]);out.add(new Face(inner));}
        for(Edge edge:edges){int count=0;for(int fi:edge.faces)if(selected.get(fi))count++;if(count==1)out.add(new Face(edge.a,edge.b,duplicate.get(edge.b),duplicate.get(edge.a)));}
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void spinSelectedFaces(float angle,int steps,float screwDistance){
        if(selectedFaces.isEmpty())return;steps=Math.max(1,Math.min(64,steps));
        ArrayList<int[]> source=new ArrayList<>();HashMap<Long,Integer> edgeUse=new HashMap<>();
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1)){
            int[] f=faces.get(fi).vertices.clone();source.add(f);
            for(int j=0;j<f.length;j++){long key=edgeKey(f[j],f[(j+1)%f.length]);edgeUse.put(key,edgeUse.getOrDefault(key,0)+1);}
        }
        float[] center=new float[3];HashSet<Integer> ids=new HashSet<>();for(int[] f:source)for(int id:f)ids.add(id);
        for(int id:ids)for(int k=0;k<3;k++)center[k]+=vertices.get(id)[k]/ids.size();
        HashMap<Integer,Integer> previous=new HashMap<>();for(int id:ids)previous.put(id,id);
        for(int step=1;step<=steps;step++){
            float theta=angle*step/steps,co=(float)Math.cos(theta),si=(float)Math.sin(theta);HashMap<Integer,Integer> current=new HashMap<>();
            for(int id:ids){float[] p=vertices.get(id);float x=p[0]-center[0],z=p[2]-center[2];int next=vertices.size();
                vertices.add(new float[]{center[0]+x*co-z*si,p[1]+screwDistance*step/steps,center[2]+x*si+z*co});current.put(id,next);}
            for(int[] f:source){
                int[] top=new int[f.length];for(int j=0;j<f.length;j++)top[j]=current.get(f[j]);faces.add(new Face(top));
                for(int j=0;j<f.length;j++){int a=f[j],b=f[(j+1)%f.length];if(edgeUse.getOrDefault(edgeKey(a,b),0)==1)
                    faces.add(new Face(previous.get(a),previous.get(b),current.get(b),current.get(a)));}
            }
            previous=current;
        }
        clearSelection();rebuildEdges();revision++;
    }
    public void fillSelectedBoundary(){
        ArrayList<Integer> loop=new ArrayList<>();HashMap<Integer,ArrayList<Integer>> adjacency=new HashMap<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);if(e.faces.size()!=1)continue;
            adjacency.computeIfAbsent(e.a,k->new ArrayList<>()).add(e.b);adjacency.computeIfAbsent(e.b,k->new ArrayList<>()).add(e.a);}
        if(adjacency.size()<3)return;for(ArrayList<Integer> links:adjacency.values())if(links.size()!=2)return;
        int start=adjacency.keySet().iterator().next(),previous=-1,current=start;
        do{loop.add(current);ArrayList<Integer> links=adjacency.get(current);int next=links.get(0)==previous?links.get(1):links.get(0);previous=current;current=next;if(loop.size()>adjacency.size()+1)return;}while(current!=start);
        if(loop.size()!=adjacency.size())return;int[] ids=new int[loop.size()];for(int i=0;i<ids.length;i++)ids[i]=loop.get(i);
        faces.add(new Face(ids));clearSelection();rebuildEdges();revision++;
    }
    public void slideSelected(float factor,boolean edgeMode){
        factor=Math.max(0,Math.min(1,factor));BitSet targets=(BitSet)selectedVertices.clone();if(targets.isEmpty())return;
        float[][] normals=calculateNormals();float[][] next=copyVertices();
        for(int id=targets.nextSetBit(0);id>=0;id=targets.nextSetBit(id+1)){
            ArrayList<Integer> linked=new ArrayList<>();for(Edge e:edges)if(e.a==id||e.b==id)linked.add(e.a==id?e.b:e.a);
            if(linked.size()<2)continue;float[] avg={0,0,0};for(int other:linked)for(int k=0;k<3;k++)avg[k]+=vertices.get(other)[k]/linked.size();
            float dx=avg[0]-vertices.get(id)[0],dy=avg[1]-vertices.get(id)[1],dz=avg[2]-vertices.get(id)[2];
            float dot=dx*normals[id][0]+dy*normals[id][1]+dz*normals[id][2];
            dx-=normals[id][0]*dot;dy-=normals[id][1]*dot;dz-=normals[id][2]*dot;
            float factorScale=edgeMode?.12f:.18f;
            next[id][0]+=dx*factor*factorScale;next[id][1]+=dy*factor*factorScale;next[id][2]+=dz*factor*factorScale;
        }
        for(int id=targets.nextSetBit(0);id>=0;id=targets.nextSetBit(id+1))System.arraycopy(next[id],0,vertices.get(id),0,3);
        revision++;
    }
    public void loopCutSelected(float factor){
        if(selectedEdges.isEmpty())return;
        HashMap<Long,Edge> edgeMap=new HashMap<>();for(Edge e:edges)edgeMap.put(edgeKey(e.a,e.b),e);
        HashSet<Long> cut=new HashSet<>();ArrayDeque<Long> queue=new ArrayDeque<>();
        for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){
            Edge e=edges.get(i);long key=edgeKey(e.a,e.b);if(cut.add(key))queue.add(key);
        }
        // Follow opposite edges through connected quad faces to form a complete loop.
        while(!queue.isEmpty()){
            long key=queue.removeFirst();Edge edge=edgeMap.get(key);if(edge==null)continue;
            for(int fi:edge.faces){int[] f=faces.get(fi).vertices;if(f.length!=4)continue;
                for(int j=0;j<4;j++)if(edgeKey(f[j],f[(j+1)%4])==key){
                    long opposite=edgeKey(f[(j+2)%4],f[(j+3)%4]);
                    if(cut.add(opposite))queue.add(opposite);break;
                }
            }
        }
        HashMap<Long,Integer> midpoints=new HashMap<>();ArrayList<Face> out=new ArrayList<>();
        for(Face face:faces){int[] f=face.vertices;int split=-1;
            for(int j=0;j<f.length;j++)if(f.length==4&&cut.contains(edgeKey(f[j],f[(j+1)%4]))
                    &&cut.contains(edgeKey(f[(j+2)%4],f[(j+3)%4]))){split=j;break;}
            if(split<0){out.add(face);continue;}
            int a=f[split],b=f[(split+1)%4],cc=f[(split+2)%4],d=f[(split+3)%4];
            int m1=midpoint(a,b,factor,midpoints),m2=midpoint(d,cc,factor,midpoints);
            out.add(new Face(a,m1,m2,d));out.add(new Face(m1,b,cc,m2));
        }
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    private int midpoint(int a,int b,float t,HashMap<Long,Integer> cache){
        long key=edgeKey(a,b);Integer existing=cache.get(key);if(existing!=null)return existing;
        float[] p=vertices.get(a),q=vertices.get(b);int id=vertices.size();
        vertices.add(new float[]{p[0]+(q[0]-p[0])*t,p[1]+(q[1]-p[1])*t,p[2]+(q[2]-p[2])*t});
        cache.put(key,id);return id;
    }
    public void bevelSelectedEdges(float width){
        if(selectedEdges.isEmpty())return;
        width=Math.max(.001f,Math.min(.49f,width));
        HashSet<Long> selected=new HashSet<>();for(int i=selectedEdges.nextSetBit(0);i>=0;i=selectedEdges.nextSetBit(i+1)){Edge e=edges.get(i);selected.add(edgeKey(e.a,e.b));}
        HashMap<Long,HashMap<Integer,int[]>> bevelCorners=new HashMap<>();ArrayList<Face> out=new ArrayList<>();
        for(int fi=0;fi<faces.size();fi++){
            int[] old=faces.get(fi).vertices;ArrayList<Integer> ring=new ArrayList<>();
            for(int j=0;j<old.length;j++){
                int a=old[j],b=old[(j+1)%old.length];long key=edgeKey(a,b);
                if(selected.contains(key)){
                    float[] pa=vertices.get(a),pb=vertices.get(b),prev=vertices.get(old[(j+old.length-1)%old.length]),next=vertices.get(old[(j+2)%old.length]);
                    int ia=vertices.size();vertices.add(new float[]{pa[0]+(prev[0]-pa[0])*width,pa[1]+(prev[1]-pa[1])*width,pa[2]+(prev[2]-pa[2])*width});
                    int ib=vertices.size();vertices.add(new float[]{pb[0]+(next[0]-pb[0])*width,pb[1]+(next[1]-pb[1])*width,pb[2]+(next[2]-pb[2])*width});
                    ring.add(ia);ring.add(ib);
                    HashMap<Integer,int[]> byFace=bevelCorners.get(key);if(byFace==null){byFace=new HashMap<>();bevelCorners.put(key,byFace);}
                    byFace.put(fi,a==Math.min(a,b)?new int[]{ia,ib}:new int[]{ib,ia});
                }else ring.add(a);
            }
            int[] result=new int[ring.size()];for(int i=0;i<result.length;i++)result[i]=ring.get(i);if(result.length>=3)out.add(new Face(result));
        }
        for(Map.Entry<Long,HashMap<Integer,int[]>> entry:bevelCorners.entrySet()){
            HashMap<Integer,int[]> byFace=entry.getValue();if(byFace.size()!=2)continue;
            Iterator<int[]> it=byFace.values().iterator();int[] a=it.next(),b=it.next();
            out.add(new Face(a[0],a[1],b[1],b[0]));
        }
        faces.clear();faces.addAll(out);clearSelection();rebuildEdges();revision++;
    }
    public void setTexturePixels(int[] pixels,int width,int height){
        if(pixels==null||width<1||height<1||pixels.length!=width*height)return;
        textureWidth=width;textureHeight=height;texturePixels=pixels.clone();revision++;
    }

    /** Mark direct edits through getTexturePixelsUnsafe() as a document change. */
    public void markTextureChanged(){revision++;}
    public JSONObject exportState(){
        JSONObject o=new JSONObject();JSONArray vs=new JSONArray(),fs=new JSONArray(),ss=new JSONArray(),ps=new JSONArray();
        try{
            for(float[] p:vertices){JSONArray a=new JSONArray();for(float v:p)a.put(v);vs.put(a);}
            for(Face f:faces){JSONArray a=new JSONArray();for(int v:f.vertices)a.put(v);fs.put(a);}
            for(long s:seams)ss.put(Long.toString(s));for(long pin:pins)ps.put(Long.toString(pin));
            o.put("vertices",vs);o.put("faces",fs);o.put("seams",ss);o.put("pins",ps);o.put("selectMode",selectMode.name());
            o.put("textureColor",textureColor);o.put("brushRadius",brushRadius);o.put("brushOpacity",brushOpacity);
            JSONArray masks=new JSONArray();for(float value:sculptMask)masks.put(value);o.put("sculptMask",masks);
            o.put("baseColor",baseColor);o.put("metallic",metallic);o.put("roughness",roughness);
            if(texturePixels!=null&&textureWidth>0&&textureHeight>0&&texturePixels.length==textureWidth*textureHeight){
                o.put("textureWidth",textureWidth);o.put("textureHeight",textureHeight);
                o.put("texturePixelsDeflate",exportCompressedTexture());
            }
            if(faceUvs!=null){JSONArray uvFaces=new JSONArray();for(float[][] face:faceUvs){JSONArray corners=new JSONArray();for(float[] uv:face){JSONArray pair=new JSONArray();pair.put(uv[0]);pair.put(uv[1]);corners.put(pair);}uvFaces.put(corners);}o.put("faceUvs",uvFaces);}
            o.put("geometryNodes",writeNodes(geometryNodes));o.put("shaderNodes",writeNodes(shaderNodes));o.put("compositorNodes",writeNodes(compositorNodes));
            o.put("geometryLinks",writeLinks(geometryLinks));o.put("shaderLinks",writeLinks(shaderLinks));o.put("compositorLinks",writeLinks(compositorLinks));
            JSONArray channels=new JSONArray();for(AnimationChannel channel:animation.values()){
                JSONObject co=new JSONObject();co.put("property",channel.property);JSONArray keys=new JSONArray();
                for(Key key:channel.keys){JSONObject ko=new JSONObject();ko.put("frame",key.frame);ko.put("value",key.value);keys.put(ko);}
                co.put("keys",keys);channels.put(co);
            }o.put("animation",channels);
        }catch(JSONException e){throw new IllegalStateException(e);}return o;
    }
    private String exportCompressedTexture() {
        try{
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.util.zip.Deflater deflater=new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
            try(java.util.zip.DeflaterOutputStream compressed=new java.util.zip.DeflaterOutputStream(bytes,deflater);
                java.io.DataOutputStream data=new java.io.DataOutputStream(compressed)){
                for(int pixel:texturePixels)data.writeInt(pixel);
            }finally{deflater.end();}
            return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(Exception error){throw new IllegalStateException("Could not serialize mesh texture",error);}
    }

    private static int[] importCompressedTexture(String encoded,int count) {
        if(encoded==null||encoded.length()>96*1024*1024)return null;
        try{
            byte[] compressed=java.util.Base64.getDecoder().decode(encoded);
            int[] pixels=new int[count];
            try(java.io.DataInputStream data=new java.io.DataInputStream(
                    new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed)))){
                for(int i=0;i<count;i++)pixels[i]=data.readInt();
                if(data.read()!=-1)return null;
            }
            return pixels;
        }catch(Exception error){return null;}
    }

    private JSONArray writeNodes(ArrayList<Node> list)throws JSONException{
        JSONArray array=new JSONArray();for(Node n:list){JSONObject o=new JSONObject();o.put("type",n.type);o.put("x",n.x);o.put("y",n.y);o.put("z",n.z);o.put("value",n.value);array.put(o);}return array;
    }
    private JSONArray writeLinks(ArrayList<int[]> links)throws JSONException{
        JSONArray array=new JSONArray();for(int[] link:links){JSONArray pair=new JSONArray();pair.put(link[0]);pair.put(link[1]);array.put(pair);}return array;
    }
    private void readLinks(JSONArray array,ArrayList<int[]> target)throws JSONException{
        target.clear();if(array==null)return;for(int i=0;i<array.length();i++){JSONArray pair=array.getJSONArray(i);if(pair.length()!=2)continue;target.add(new int[]{pair.getInt(0),pair.getInt(1)});}
    }
    private void readNodes(JSONArray array,ArrayList<Node> target)throws JSONException{
        target.clear();if(array==null)return;for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);Node n=new Node(o.optString("type",""));n.x=(float)o.optDouble("x",0);n.y=(float)o.optDouble("y",0);n.z=(float)o.optDouble("z",0);n.value=(float)o.optDouble("value",0);target.add(n);}
    }
    public boolean importState(JSONObject o){
        try{
            JSONArray vs=o.getJSONArray("vertices"),fs=o.getJSONArray("faces");if(vs.length()<3||fs.length()<1)return false;
            ArrayList<float[]> nv=new ArrayList<>();
            for(int i=0;i<vs.length();i++){JSONArray p=vs.getJSONArray(i);if(p.length()!=3)return false;float[] v={(float)p.getDouble(0),(float)p.getDouble(1),(float)p.getDouble(2)};for(float q:v)if(Float.isNaN(q)||Float.isInfinite(q)||Math.abs(q)>100000)return false;nv.add(v);}
            ArrayList<Face> nf=new ArrayList<>();
            for(int i=0;i<fs.length();i++){JSONArray f=fs.getJSONArray(i);if(f.length()<3)return false;int[] ids=new int[f.length()];for(int j=0;j<ids.length;j++){ids[j]=f.getInt(j);if(ids[j]<0||ids[j]>=nv.size())return false;}nf.add(new Face(ids));}
            vertices.clear();vertices.addAll(nv);faces.clear();faces.addAll(nf);texturePixels=null;textureWidth=textureHeight=0;seams.clear();
            String textureData=o.optString("texturePixelsDeflate","");
            if(!textureData.isEmpty()){
                int width=o.optInt("textureWidth",0),height=o.optInt("textureHeight",0);
                if(width<1||height<1||width>4096||height>4096
                        ||(long)width*height>16777216L)return false;
                int[] pixels=importCompressedTexture(textureData,width*height);
                if(pixels==null)return false;
                textureWidth=width;textureHeight=height;texturePixels=pixels;
            }
            JSONArray sm=o.optJSONArray("seams");if(sm!=null)for(int i=0;i<sm.length();i++)seams.add(Long.parseLong(sm.getString(i)));
            pins.clear();JSONArray pn=o.optJSONArray("pins");if(pn!=null)for(int i=0;i<pn.length();i++)pins.add(Long.parseLong(pn.getString(i)));
            textureColor=o.optInt("textureColor",textureColor);brushRadius=(float)o.optDouble("brushRadius",brushRadius);brushOpacity=(float)o.optDouble("brushOpacity",brushOpacity);
            JSONArray masks=o.optJSONArray("sculptMask");sculptMask=new float[nv.size()];if(masks!=null&&masks.length()==sculptMask.length)for(int i=0;i<masks.length();i++)sculptMask[i]=Math.max(0,Math.min(1,(float)masks.optDouble(i,0)));
            selectMode=SelectMode.valueOf(o.optString("selectMode","FACE"));
            baseColor=o.optInt("baseColor",baseColor);metallic=(float)o.optDouble("metallic",metallic);roughness=(float)o.optDouble("roughness",roughness);
            clearSelection();rebuildEdges();
            JSONArray uv=o.optJSONArray("faceUvs");
            if(uv!=null&&uv.length()==faces.size()){
                faceUvs=new float[uv.length()][][];
                for(int i=0;i<uv.length();i++){JSONArray corners=uv.getJSONArray(i);if(corners.length()!=faces.get(i).vertices.length)return false;
                    faceUvs[i]=new float[corners.length()][2];for(int j=0;j<corners.length();j++){JSONArray pair=corners.getJSONArray(j);faceUvs[i][j][0]=(float)pair.getDouble(0);faceUvs[i][j][1]=(float)pair.getDouble(1);}}
                rebuildIslands();
            }else{faceUvs=null;islands.clear();}
            readNodes(o.optJSONArray("geometryNodes"),geometryNodes);readNodes(o.optJSONArray("shaderNodes"),shaderNodes);readNodes(o.optJSONArray("compositorNodes"),compositorNodes);
            readLinks(o.optJSONArray("geometryLinks"),geometryLinks);readLinks(o.optJSONArray("shaderLinks"),shaderLinks);readLinks(o.optJSONArray("compositorLinks"),compositorLinks);
            animation.clear();JSONArray channels=o.optJSONArray("animation");if(channels!=null)for(int i=0;i<channels.length();i++){
                JSONObject co=channels.getJSONObject(i);String property=co.optString("property","");AnimationChannel channel=new AnimationChannel(property);
                JSONArray keys=co.optJSONArray("keys");if(keys!=null)for(int j=0;j<keys.length();j++){JSONObject ko=keys.getJSONObject(j);channel.put(ko.optInt("frame",0),(float)ko.optDouble("value",0));}
                animation.put(property,channel);
            }
            revision++;return true;
        }catch(Exception e){return false;}
    }
}