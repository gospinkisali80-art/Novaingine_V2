package com.novaingine.v2;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** glTF 2.0 binary exporter for Meshy3D mesh objects. */
final class Meshy3DGlbExporter {
    private static final int MAX_BINARY_BYTES = 96 * 1024 * 1024;

    private Meshy3DGlbExporter() {}

    static byte[] export(List<Meshy3DGeometryExporter.ObjectSnapshot> objects) throws Exception {
        if (objects == null || objects.isEmpty()) throw new IllegalArgumentException("There are no mesh objects to export.");
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        JSONArray bufferViews = new JSONArray();
        JSONArray accessors = new JSONArray();
        JSONArray meshes = new JSONArray();
        JSONArray nodes = new JSONArray();
        JSONArray sceneNodes = new JSONArray();

        for (Meshy3DGeometryExporter.ObjectSnapshot object : objects) {
            float[][] positions = transform(object);
            int[] indices = triangulate(object.faces, positions.length);
            if (indices.length == 0) continue;
            float[][] normals = calculateNormals(positions, indices);
            align4(binary);
            int positionOffset = binary.size();
            writeFloats(binary, positions);
            int positionLength = binary.size() - positionOffset;
            JSONArray positionMin = new JSONArray(), positionMax = new JSONArray();
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (float[] p : positions) for (int axis=0; axis<3; axis++) {
                min[axis] = Math.min(min[axis], p[axis]); max[axis] = Math.max(max[axis], p[axis]);
            }
            for (int axis=0;axis<3;axis++){positionMin.put(min[axis]);positionMax.put(max[axis]);}
            int positionView = bufferViews.length();
            bufferViews.put(new JSONObject().put("buffer",0).put("byteOffset",positionOffset)
                    .put("byteLength",positionLength).put("target",34962));
            int positionAccessor = accessors.length();
            accessors.put(new JSONObject().put("bufferView",positionView).put("componentType",5126)
                    .put("count",positions.length).put("type","VEC3")
                    .put("min",positionMin).put("max",positionMax));

            align4(binary);
            int normalOffset = binary.size();
            writeFloats(binary, normals);
            int normalView = bufferViews.length();
            bufferViews.put(new JSONObject().put("buffer",0).put("byteOffset",normalOffset)
                    .put("byteLength",binary.size()-normalOffset).put("target",34962));
            int normalAccessor = accessors.length();
            accessors.put(new JSONObject().put("bufferView",normalView).put("componentType",5126)
                    .put("count",normals.length).put("type","VEC3"));

            align4(binary);
            int indexOffset = binary.size();
            ByteBuffer indexBytes = ByteBuffer.allocate(indices.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int index : indices) indexBytes.putInt(index);
            binary.write(indexBytes.array());
            int indexView = bufferViews.length();
            bufferViews.put(new JSONObject().put("buffer",0).put("byteOffset",indexOffset)
                    .put("byteLength",binary.size()-indexOffset).put("target",34963));
            int indexAccessor = accessors.length();
            accessors.put(new JSONObject().put("bufferView",indexView).put("componentType",5125)
                    .put("count",indices.length).put("type","SCALAR")
                    .put("min",new JSONArray().put(0))
                    .put("max",new JSONArray().put(maxIndex(indices))));

            int meshIndex = meshes.length();
            String meshName = safeName(object.name);
            meshes.put(new JSONObject().put("name",meshName)
                    .put("primitives",new JSONArray().put(new JSONObject()
                            .put("attributes",new JSONObject().put("POSITION",positionAccessor).put("NORMAL",normalAccessor))
                            .put("indices",indexAccessor).put("mode",4))));
            nodes.put(new JSONObject().put("name",meshName).put("mesh",meshIndex));
            sceneNodes.put(nodes.length()-1);
            if (binary.size() > MAX_BINARY_BYTES)
                throw new IllegalArgumentException("GLB export exceeds the 96 MB mobile memory limit.");
        }
        if (meshes.length()==0) throw new IllegalArgumentException("No valid triangle geometry was found.");
        align4(binary);
        byte[] bin = binary.toByteArray();
        JSONObject root = new JSONObject()
                .put("asset",new JSONObject().put("version","2.0").put("generator","Meshy3D"))
                .put("scene",0)
                .put("scenes",new JSONArray().put(new JSONObject().put("nodes",sceneNodes)))
                .put("nodes",nodes).put("meshes",meshes)
                .put("buffers",new JSONArray().put(new JSONObject().put("byteLength",bin.length)))
                .put("bufferViews",bufferViews).put("accessors",accessors);
        byte[] json = root.toString().getBytes(StandardCharsets.UTF_8);
        int jsonPadded = (json.length + 3) & ~3;
        int binPadded = (bin.length + 3) & ~3;
        long total = 12L + 8L + jsonPadded + 8L + binPadded;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("GLB file is too large.");
        ByteBuffer glb = ByteBuffer.allocate((int)total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67); // glTF
        glb.putInt(2);
        glb.putInt((int)total);
        glb.putInt(jsonPadded);
        glb.putInt(0x4E4F534A); // JSON
        glb.put(json);
        while (glb.position() < 20 + jsonPadded) glb.put((byte)0x20);
        glb.putInt(binPadded);
        glb.putInt(0x004E4942); // BIN
        glb.put(bin);
        while (glb.hasRemaining()) glb.put((byte)0);
        return glb.array();
    }

    private static float[][] transform(Meshy3DGeometryExporter.ObjectSnapshot object) {
        float[][] result = new float[object.vertices.length][3];
        double[] m = object.world;
        if (m == null || m.length < 16) throw new IllegalArgumentException("Invalid object transform.");
        for (int i=0;i<result.length;i++) {
            float[] p=object.vertices[i];
            double x=m[0]*p[0]+m[1]*p[1]+m[2]*p[2]+m[3];
            double y=m[4]*p[0]+m[5]*p[1]+m[6]*p[2]+m[7];
            double z=m[8]*p[0]+m[9]*p[1]+m[10]*p[2]+m[11];
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||Math.abs(x)>100000||Math.abs(y)>100000||Math.abs(z)>100000)
                throw new IllegalArgumentException("An object transform produced invalid coordinates.");
            result[i][0]=(float)x;result[i][1]=(float)y;result[i][2]=(float)z;
        }
        return result;
    }

    private static int[] triangulate(int[][] faces,int vertexCount) {
        ArrayList<Integer> result=new ArrayList<>();
        for(int[] face:faces){
            if(face==null||face.length<3)continue;
            for(int index:face)if(index<0||index>=vertexCount)
                throw new IllegalArgumentException("A mesh face references an invalid vertex.");
            for(int i=1;i+1<face.length;i++){result.add(face[0]);result.add(face[i]);result.add(face[i+1]);}
        }
        int[] out=new int[result.size()];for(int i=0;i<out.length;i++)out[i]=result.get(i);return out;
    }

    private static float[][] calculateNormals(float[][] p,int[] indices) {
        float[][] n=new float[p.length][3];
        for(int i=0;i+2<indices.length;i+=3){
            int ia=indices[i],ib=indices[i+1],ic=indices[i+2];
            float[] a=p[ia],b=p[ib],c=p[ic];
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2];
            float vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            for(int id:new int[]{ia,ib,ic}){n[id][0]+=nx;n[id][1]+=ny;n[id][2]+=nz;}
        }
        for(float[] v:n){float l=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
            if(l>1e-12f){v[0]/=l;v[1]/=l;v[2]/=l;}else{v[0]=0;v[1]=1;v[2]=0;}}
        return n;
    }
    private static void writeFloats(ByteArrayOutputStream out,float[][] values){
        ByteBuffer b=ByteBuffer.allocate(values.length*12).order(ByteOrder.LITTLE_ENDIAN);
        for(float[] v:values){b.putFloat(v[0]);b.putFloat(v[1]);b.putFloat(v[2]);}
        out.write(b.array(),0,b.array().length);
    }
    private static int maxIndex(int[] values){int max=0;for(int v:values)max=Math.max(max,v);return max;}
    private static void align4(ByteArrayOutputStream out){while((out.size()&3)!=0)out.write(0);}
    private static String safeName(String name){
        if(name==null||name.trim().isEmpty())return "Mesh";
        return name.trim().replaceAll("[^A-Za-z0-9 _.-]+","_");
    }
}