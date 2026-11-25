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
    private int textureColor=0xFFFFFFFF;
    private float brushRadius=16f, brushOpacity=1f;
    private final HashMap<String,AnimationChannel> animation = new HashMap<>();
    private final ArrayList<Node> geometryNodes = new ArrayList<>();
    private final ArrayList<Node> shaderNodes = new ArrayList<>();
    private final ArrayList<Node> compositorNodes = new ArrayList<>();
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
    public int getSelectedCount(){
        return selectMode==SelectMode.VERTEX?selectedVertices.cardinality():
               selectMode==SelectMode.EDGE?selectedEdges.cardinality():selectedFaces.cardinality();
    }
    private static long edgeKey(int a,int b){int lo=Math.min(a,b),hi=Math.max(a,b);return (((long)lo)<<32)|(hi&0xffffffffL);}
    private void createDenseCube(int subdivisions){
        vertices.clear();faces.clear();edges.clear();
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
        rebuildEdges();revision++;
    }
    private void rebuildEdges(){
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
        else if(selectMode==SelectMode.EDGE)selectedEdges.set(0,edges.size());
        else {selectedFaces.set(0,faces.size());for(Face f:faces)f.selected=true;}
        revision++;
    }
    public void selectLinkedFromCurrent(){int seed=selectedFaces.nextSetBit(0);if(seed>=0)selectLinked(seed);}
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
        revision++;
    }
    public boolean isSeam(int edge){if(edge<0||edge>=edges.size())return false;Edge e=edges.get(edge);return seams.contains(edgeKey(e.a,e.b));}
    public void pinSelectedUvs(boolean pin){
        for(int fi=selectedFaces.nextSetBit(0);fi>=0;fi=selectedFaces.nextSetBit(fi+1))
            for(int v:faces.get(fi).vertices){if(pin)pins.add((long)v);else pins.remove((long)v);}
        revision++;
    }
    public void unwrapPlanar(){
        faceUvs=new float[faces.size()][];islands.clear();
        // Create seams at hard normal discontinuities, preserving explicitly marked seams.
        for(Edge edge:edges)if(edge.faces.size()==2){
            float[] a=normal(faces.get(edge.faces.get(0)).vertices);
            float[] b=normal(faces.get(edge.faces.get(1)).vertices);
            float la=(float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);
            float lb=(float)Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2]);
            if(la>1e-6&&lb>1e-6){
                float dot=(a[0]*b[0]+a[1]*b[1]+a[2]*b[2])/(la*lb);
                if(dot<.985f)seams.add(edgeKey(edge.a,edge.b));
            }
        }
        rebuildIslands();
        int cols=Math.max(1,(int)Math.ceil(Math.sqrt(islands.size())));
        int rows=Math.max(1,(int)Math.ceil(islands.size()/(float)cols));
        float tileW=1f/cols,tileH=1f/rows,margin=.035f;
        for(int islandIndex=0;islandIndex<islands.size();islandIndex++){
            UvIsland island=islands.get(islandIndex);
            float[] n={0,0,0};for(int fi:island.faces){float[] fn=normal(faces.get(fi).vertices);for(int k=0;k<3;k++)n[k]+=fn[k];}
            float nl=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(nl<1e-6)nl=1;
            for(int k=0;k<3;k++)n[k]/=nl;
            int[] first=faces.get(island.faces.get(0)).vertices;float[] p0=vertices.get(first[0]),p1=vertices.get(first[1]);
            float[] u={p1[0]-p0[0],p1[1]-p0[1],p1[2]-p0[2]};
            float dot=u[0]*n[0]+u[1]*n[1]+u[2]*n[2];for(int k=0;k<3;k++)u[k]-=dot*n[k];
            float ul=(float)Math.sqrt(u[0]*u[0]+u[1]*u[1]+u[2]*u[2]);if(ul<1e-6){u=new float[]{1,0,0};ul=1;}
            for(int k=0;k<3;k++)u[k]/=ul;
            float[] v={n[1]*u[2]-n[2]*u[1],n[2]*u[0]-n[0]*u[2],n[0]*u[1]-n[1]*u[0]};
            HashMap<Integer,float[]> projected=new HashMap<>();float minU=Float.MAX_VALUE,minV=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
            for(int fi:island.faces)for(int id:faces.get(fi).vertices)if(!projected.containsKey(id)){
                float[] p=vertices.get(id);float pu=p[0]*u[0]+p[1]*u[1]+p[2]*u[2],pv=p[0]*v[0]+p[1]*v[1]+p[2]*v[2];
                projected.put(id,new float[]{pu,pv});minU=Math.min(minU,pu);maxU=Math.max(maxU,pu);minV=Math.min(minV,pv);maxV=Math.max(maxV,pv);
            }
            float rangeU=Math.max(.0001f,maxU-minU),rangeV=Math.max(.0001f,maxV-minV);
            float scale=Math.min((tileW-2*margin)/rangeU,(tileH-2*margin)/rangeV);
            float usedW=rangeU*scale,usedH=rangeV*scale;
            float ox=(islandIndex%cols)*tileW+(tileW-usedW)*.5f;
            float oy=(islandIndex/cols)*tileH+(tileH-usedH)*.5f;
            for(int fi:island.faces){int[] f=faces.get(fi).vertices;float[][] uv=new float[f.length][2];
                for(int j=0;j<f.length;j++){float[] q=projected.get(f[j]);uv[j][0]=ox+(q[0]-minU)*scale;uv[j][1]=oy+(q[1]-minV)*scale;}
                faceUvs[fi]=uv;
            }
            island.minU=ox;island.minV=oy;island.maxU=ox+usedW;island.maxV=oy+usedH;
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
    public void packUvIslands(){
        if(faceUvs==null||islands.isEmpty())return;
        int cols=Math.max(1,(int)Math.ceil(Math.sqrt(islands.size()))),rows=(int)Math.ceil(islands.size()/(float)cols);
        float tw=1f/cols,th=1f/rows,margin=.025f;
        for(int i=0;i<islands.size();i++){
            UvIsland island=islands.get(i);float minU=Float.MAX_VALUE,minV=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
            for(int fi:island.faces)for(float[] uv:faceUvs[fi]){minU=Math.min(minU,uv[0]);minV=Math.min(minV,uv[1]);maxU=Math.max(maxU,uv[0]);maxV=Math.max(maxV,uv[1]);}
            float rw=Math.max(.0001f,maxU-minU),rh=Math.max(.0001f,maxV-minV);
            float scale=Math.min((tw-2*margin)/rw,(th-2*margin)/rh);
            float usedW=rw*scale,usedH=rh*scale;
            float ox=(i%cols)*tw+(tw-usedW)*.5f,oy=(i/cols)*th+(th-usedH)*.5f;
            for(int fi:island.faces)for(float[] uv:faceUvs[fi]){uv[0]=ox+(uv[0]-minU)*scale;uv[1]=oy+(uv[1]-minV)*scale;}
            island.minU=ox;island.minV=oy;island.maxU=ox+usedW;island.maxV=oy+usedH;
        }revision++;
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
    public int getTextureWidth(){return textureWidth;} public int getTextureHeight(){return textureHeight;}
    public void setMaterial(int color,float metal,float rough){baseColor=color;metallic=Math.max(0,Math.min(1,metal));roughness=Math.max(.02f,Math.min(1,rough));revision++;}
    public int getBaseColor(){return baseColor;}public float getMetallic(){return metallic;}public float getRoughness(){return roughness;}
    public void addShaderNode(String type){addShaderNode(type,0f);}
    public void addShaderNode(String type,float value){Node n=new Node(type);n.value=value;shaderNodes.add(n);revision++;}
    public void evaluateShaderGraph(){
        for(Node n:shaderNodes){
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
    public void evaluateGraph(int graph){
        if(graph==0)evaluateGeometryNodes();
        else if(graph==1)evaluateShaderGraph();
        else evaluateCompositorTexture();
    }
    public int getShaderNodeCount(){return shaderNodes.size();}
    public void addGeometryNode(String type,float x,float y,float z,float value){Node n=new Node(type);n.x=x;n.y=y;n.z=z;n.value=value;geometryNodes.add(n);revision++;}
    public void clearGeometryNodes(){geometryNodes.clear();revision++;}
    public void evaluateGeometryNodes(){
        for(Node n:geometryNodes){
            if("Transform Geometry".equals(n.type)){for(float[] p:vertices){p[0]+=n.x;p[1]+=n.y;p[2]+=n.z;}}
            else if("Set Position".equals(n.type)){for(float[] p:vertices)p[1]+=n.value;}
            else if("Smooth".equals(n.type))smooth(.2f,1);
            else if("Subdivide".equals(n.type)||"Subdivide Mesh".equals(n.type))subdivide();
            else if("Delete Geometry".equals(n.type))deleteSelectedFaces();
            else if("Set Material".equals(n.type))baseColor=(int)n.value;
            else if("Random Value".equals(n.type)){Random random=new Random(7);for(float[] p:vertices){p[0]+=(random.nextFloat()-.5f)*n.value;p[1]+=(random.nextFloat()-.5f)*n.value;p[2]+=(random.nextFloat()-.5f)*n.value;}}
            else if("Math".equals(n.type)){for(float[] p:vertices){p[0]*=n.value;p[1]*=n.value;p[2]*=n.value;}}
            else if("Instance on Points".equals(n.type))duplicateMesh(n.x,n.y,n.z);
        }revision++;
    }
    private void deleteSelectedFaces(){
        if(selectedFaces.isEmpty())return;
        ArrayList<Face> keep=new ArrayList<>();for(int i=0;i<faces.size();i++)if(!selectedFaces.get(i))keep.add(faces.get(i));
        faces.clear();faces.addAll(keep);clearSelection();rebuildEdges();
    }
    private void duplicateMesh(float dx,float dy,float dz){
        int base=vertices.size(),count=vertices.size();ArrayList<float[]> copy=new ArrayList<>();
        for(float[] p:vertices)copy.add(new float[]{p[0]+dx,p[1]+dy,p[2]+dz});
        ArrayList<Face> extra=new ArrayList<>();for(Face f:faces){int[] ids=f.vertices.clone();for(int i=0;i<ids.length;i++)ids[i]+=base;extra.add(new Face(ids));}
        vertices.addAll(copy);faces.addAll(extra);rebuildEdges();
    }
    public void addCompositorNode(String type,float value){Node n=new Node(type);n.value=value;compositorNodes.add(n);revision++;}
    public int getCompositorNodeCount(){return compositorNodes.size();}
    public int[] evaluateCompositor(int[] input,int width,int height){
        int[] out=input.clone();
        for(Node n:compositorNodes){
            if("Grayscale".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],g=(int)(.2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255));out[i]=0xFF000000|g<<16|g<<8|g;}}
            else if("Brightness".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],r=clamp(((c>>16)&255)+n.value),g=clamp(((c>>8)&255)+n.value),b=clamp((c&255)+n.value);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Invert".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i];out[i]=0xFF000000|((255-((c>>16)&255))<<16)|((255-((c>>8)&255))<<8)|(255-(c&255));}}
            else if("Contrast".equals(n.type)){float f=(float)((259*(n.value+255))/(255*(259-n.value)));for(int i=0;i<out.length;i++){int c=out[i],r=clamp(f*(((c>>16)&255)-128)+128),g=clamp(f*(((c>>8)&255)-128)+128),b=clamp(f*((c&255)-128)+128);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Blur".equals(n.type)&&width>2&&height>2){int[] src=out.clone();int radius=Math.max(1,Math.min(8,Math.round(n.value)));for(int y=0;y<height;y++)for(int x=0;x<width;x++){int rr=0,gg=0,bb=0,count=0;for(int oy=-radius;oy<=radius;oy++)for(int ox=-radius;ox<=radius;ox++){int xx=x+ox,yy=y+oy;if(xx<0||yy<0||xx>=width||yy>=height)continue;int q=src[yy*width+xx];rr+=(q>>16)&255;gg+=(q>>8)&255;bb+=q&255;count++;}out[y*width+x]=0xFF000000|(rr/count)<<16|(gg/count)<<8|(bb/count);}}
            else if("Color Balance".equals(n.type)){for(int i=0;i<out.length;i++){int c=out[i],r=clamp(((c>>16)&255)+n.value),g=clamp(((c>>8)&255)+n.value*.25f),b=clamp((c&255)-n.value);out[i]=0xFF000000|r<<16|g<<8|b;}}
            else if("Alpha Over".equals(n.type)){int overlay=baseColor;for(int i=0;i<out.length;i++)out[i]=blend(out[i],overlay,.25f);}
            else if("Composite".equals(n.type)){/* Final output node: retain the evaluated image. */}
        }return out;
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
        int[] compact=new int[vertices.size()];Arrays.fill(compact,-1);ArrayList<float[]> kept=new ArrayList<>();
        for(int i=0;i<vertices.size();i++)if(used[i]){compact[i]=kept.size();kept.add(vertices.get(i));}
        for(Face face:faces)for(int i=0;i<face.vertices.length;i++)face.vertices[i]=compact[face.vertices[i]];
        vertices.clear();vertices.addAll(kept);clearSelection();rebuildEdges();revision++;
    }
    public float[][] copyVertices(){float[][] out=new float[vertices.size()][3];for(int i=0;i<out.length;i++)System.arraycopy(vertices.get(i),0,out[i],0,3);return out;}
    public int[][] copyFaces(){int[][] out=new int[faces.size()][];for(int i=0;i<out.length;i++)out[i]=faces.get(i).vertices.clone();return out;}
    public void replaceGeometry(float[][] v,int[][] f){
        boolean sameTopology=v.length==vertices.size()&&f.length==faces.size();
        vertices.clear();for(float[] p:v)vertices.add(p.clone());faces.clear();
        for(int[] ids:f)faces.add(new Face(ids.clone()));
        if(!sameTopology)clearSelection();rebuildEdges();revision++;
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
    public JSONObject exportState(){
        JSONObject o=new JSONObject();JSONArray vs=new JSONArray(),fs=new JSONArray(),ss=new JSONArray();
        try{
            for(float[] p:vertices){JSONArray a=new JSONArray();for(float v:p)a.put(v);vs.put(a);}
            for(Face f:faces){JSONArray a=new JSONArray();for(int v:f.vertices)a.put(v);fs.put(a);}
            for(long s:seams)ss.put(Long.toString(s));
            o.put("vertices",vs);o.put("faces",fs);o.put("seams",ss);o.put("selectMode",selectMode.name());
            o.put("baseColor",baseColor);o.put("metallic",metallic);o.put("roughness",roughness);
            if(faceUvs!=null){JSONArray uvFaces=new JSONArray();for(float[][] face:faceUvs){JSONArray corners=new JSONArray();for(float[] uv:face){JSONArray pair=new JSONArray();pair.put(uv[0]);pair.put(uv[1]);corners.put(pair);}uvFaces.put(corners);}o.put("faceUvs",uvFaces);}
            o.put("geometryNodes",writeNodes(geometryNodes));o.put("shaderNodes",writeNodes(shaderNodes));o.put("compositorNodes",writeNodes(compositorNodes));
            JSONArray channels=new JSONArray();for(AnimationChannel channel:animation.values()){
                JSONObject co=new JSONObject();co.put("property",channel.property);JSONArray keys=new JSONArray();
                for(Key key:channel.keys){JSONObject ko=new JSONObject();ko.put("frame",key.frame);ko.put("value",key.value);keys.put(ko);}
                co.put("keys",keys);channels.put(co);
            }o.put("animation",channels);
        }catch(JSONException e){throw new IllegalStateException(e);}return o;
    }
    private JSONArray writeNodes(ArrayList<Node> list)throws JSONException{
        JSONArray array=new JSONArray();for(Node n:list){JSONObject o=new JSONObject();o.put("type",n.type);o.put("x",n.x);o.put("y",n.y);o.put("z",n.z);o.put("value",n.value);array.put(o);}return array;
    }
    private void readNodes(JSONArray array,ArrayList<Node> target)throws JSONException{
        target.clear();if(array==null)return;for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);Node n=new Node(o.optString("type",""));n.x=(float)o.optDouble("x",0);n.y=(float)o.optDouble("y",0);n.z=(float)o.optDouble("z",0);n.value=(float)o.optDouble("value",0);target.add(n);}
    }
    public boolean importState(JSONObject o){
        try{
            JSONArray vs=o.getJSONArray("vertices"),fs=o.getJSONArray("faces");if(vs.length()<4||fs.length()<4)return false;
            ArrayList<float[]> nv=new ArrayList<>();
            for(int i=0;i<vs.length();i++){JSONArray p=vs.getJSONArray(i);if(p.length()!=3)return false;float[] v={(float)p.getDouble(0),(float)p.getDouble(1),(float)p.getDouble(2)};for(float q:v)if(Float.isNaN(q)||Float.isInfinite(q)||Math.abs(q)>100000)return false;nv.add(v);}
            ArrayList<Face> nf=new ArrayList<>();
            for(int i=0;i<fs.length();i++){JSONArray f=fs.getJSONArray(i);if(f.length()<3)return false;int[] ids=new int[f.length()];for(int j=0;j<ids.length;j++){ids[j]=f.getInt(j);if(ids[j]<0||ids[j]>=nv.size())return false;}nf.add(new Face(ids));}
            vertices.clear();vertices.addAll(nv);faces.clear();faces.addAll(nf);seams.clear();
            JSONArray sm=o.optJSONArray("seams");if(sm!=null)for(int i=0;i<sm.length();i++)seams.add(Long.parseLong(sm.getString(i)));
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
            animation.clear();JSONArray channels=o.optJSONArray("animation");if(channels!=null)for(int i=0;i<channels.length();i++){
                JSONObject co=channels.getJSONObject(i);String property=co.optString("property","");AnimationChannel channel=new AnimationChannel(property);
                JSONArray keys=co.optJSONArray("keys");if(keys!=null)for(int j=0;j<keys.length();j++){JSONObject ko=keys.getJSONObject(j);channel.put(ko.optInt("frame",0),(float)ko.optDouble("value",0));}
                animation.put(property,channel);
            }
            revision++;return true;
        }catch(Exception e){return false;}
    }
}