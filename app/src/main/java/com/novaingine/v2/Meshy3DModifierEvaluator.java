package com.novaingine.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Evaluates the Object Mode modifier stack against a temporary mesh copy.
 * The object's source mesh is never changed until a user explicitly applies
 * a modifier. This keeps the stack editable and safe for linked duplicates.
 */
public final class Meshy3DModifierEvaluator {
    private static final int MAX_EVALUATED_VERTICES=250000;
    private static final int MAX_EVALUATED_FACES=250000;

    private Meshy3DModifierEvaluator() {}

    public static Meshy3DCore evaluate(Meshy3DObjectScene.Object3D object) {
        if(object==null||object.mesh==null)return null;
        return evaluateThrough(object,object.modifiers.size()-1);
    }

    public static Meshy3DCore evaluateThrough(Meshy3DObjectScene.Object3D object,int lastIndex) {
        if(object==null||object.mesh==null)return null;
        Meshy3DCore result=object.mesh.copy();
        int end=Math.min(lastIndex,object.modifiers.size()-1);
        for(int i=0;i<=end;i++){
            Meshy3DObjectScene.Modifier3D modifier=object.modifiers.get(i);
            if(!modifier.enabled)continue;
            switch(modifier.type){
                case "MIRROR": mirror(result,modifier.axis);break;
                case "ARRAY": array(result,modifier.count,modifier.offsetX,modifier.offsetY,modifier.offsetZ);break;
                case "SUBDIVIDE":
                    for(int level=0;level<modifier.levels;level++)subdivide(result);
                    break;
                case "CATMULL_CLARK":
                    requireUvSafeMesh(result,"Catmull-Clark Subdivision");
                    for(int level=0;level<modifier.levels;level++){
                        result.subdivideCatmullClark(1);
                        checkLimits(result.getVertexCount(),result.getFaceCount());
                    }
                    break;
                case "TRIANGULATE":
                    requireUvSafeMesh(result,"Triangulate");
                    result.triangulateSelectedFaces();
                    break;
                case "PLANAR_DECIMATE":
                    requireUvSafeMesh(result,"Planar Decimate");
                    result.decimatePlanar(modifier.value,Math.max(4,result.getFaceCount()/2));
                    break;
                case "SMOOTH":
                    result.smooth(modifier.value,modifier.levels);
                    break;
                case "WIREFRAME":
                    requireUvSafeMesh(result,"Wireframe");
                    result.wireframe(modifier.value);
                    break;
                case "BEVEL":
                    requireUvSafeMesh(result,"Bevel");
                    result.clearSelection();
                    for(int edge=0;edge<result.getEdgeCount();edge++)result.selectEdge(edge,edge>0);
                    result.bevelSelectedEdges(modifier.value);
                    break;
                case "SOLIDIFY":
                    requireUvSafeMesh(result,"Solidify");
                    result.clearSelection();
                    result.solidifySelected(modifier.value);
                    break;
                default: throw new IllegalStateException("Unsupported modifier in evaluation: "+modifier.type);
            }
            checkLimits(result.getVertexCount(),result.getFaceCount());
        }
        return result;
    }

    private static void requireUvSafeMesh(Meshy3DCore mesh,String operation) {
        if(mesh.hasTexture()||mesh.copyFaceUvs()!=null)
            throw new IllegalStateException(operation+" is currently restricted to meshes without image textures or UV maps. Apply or remove UV data before using this modifier.");
    }

    private static void checkLimits(int vertices,int faces) {
        if(vertices>MAX_EVALUATED_VERTICES||faces>MAX_EVALUATED_FACES)
            throw new IllegalStateException("Modifier result exceeds the mobile viewport safety limit (250,000 vertices or faces). Reduce the modifier count or subdivision level.");
    }

    private static void mirror(Meshy3DCore mesh,int axis) {
        float[][] sourceVertices=mesh.copyVertices();
        int[][] sourceFaces=mesh.copyFaces();
        float[][][] sourceUvs=mesh.copyFaceUvs();
        validateUvs(sourceFaces,sourceUvs);
        checkLimits(sourceVertices.length*2,sourceFaces.length*2);
        float[][] vertices=new float[sourceVertices.length*2][3];
        for(int i=0;i<sourceVertices.length;i++){
            vertices[i]=sourceVertices[i].clone();
            vertices[i+sourceVertices.length]=sourceVertices[i].clone();
            vertices[i+sourceVertices.length][axis]*=-1f;
        }
        int[][] faces=new int[sourceFaces.length*2][];
        float[][][] uvs=sourceUvs==null?null:new float[sourceFaces.length*2][][];
        for(int i=0;i<sourceFaces.length;i++){
            faces[i]=sourceFaces[i].clone();
            int[] original=sourceFaces[i];
            int[] reflected=new int[original.length];
            for(int j=0;j<original.length;j++)
                reflected[j]=sourceVertices.length+original[original.length-1-j];
            faces[i+sourceFaces.length]=reflected;
            if(uvs!=null){
                uvs[i]=cloneUvFace(sourceUvs[i]);
                uvs[i+sourceFaces.length]=reverseUvFace(sourceUvs[i]);
            }
        }
        mesh.replaceGeometry(vertices,faces,uvs);
    }

    private static void array(Meshy3DCore mesh,int count,float dx,float dy,float dz) {
        if(count<=1)return;
        float[][] sourceVertices=mesh.copyVertices();
        int[][] sourceFaces=mesh.copyFaces();
        float[][][] sourceUvs=mesh.copyFaceUvs();
        validateUvs(sourceFaces,sourceUvs);
        long vertexCount=(long)sourceVertices.length*count,faceCount=(long)sourceFaces.length*count;
        checkLimits((int)Math.min(Integer.MAX_VALUE,vertexCount),(int)Math.min(Integer.MAX_VALUE,faceCount));
        float[][] vertices=new float[(int)vertexCount][3];
        int[][] faces=new int[(int)faceCount][];
        float[][][] uvs=sourceUvs==null?null:new float[(int)faceCount][][];
        for(int copy=0;copy<count;copy++){
            int vertexOffset=copy*sourceVertices.length,faceOffset=copy*sourceFaces.length;
            for(int i=0;i<sourceVertices.length;i++){
                vertices[vertexOffset+i]=sourceVertices[i].clone();
                vertices[vertexOffset+i][0]+=dx*copy;
                vertices[vertexOffset+i][1]+=dy*copy;
                vertices[vertexOffset+i][2]+=dz*copy;
            }
            for(int i=0;i<sourceFaces.length;i++){
                int[] source=sourceFaces[i],mapped=source.clone();
                for(int corner=0;corner<mapped.length;corner++)mapped[corner]+=vertexOffset;
                faces[faceOffset+i]=mapped;
                if(uvs!=null)uvs[faceOffset+i]=cloneUvFace(sourceUvs[i]);
            }
        }
        mesh.replaceGeometry(vertices,faces,uvs);
    }

    private static void subdivide(Meshy3DCore mesh) {
        float[][] sourceVertices=mesh.copyVertices();
        int[][] sourceFaces=mesh.copyFaces();
        float[][][] sourceUvs=mesh.copyFaceUvs();
        validateUvs(sourceFaces,sourceUvs);
        long estimatedFaces=0;
        for(int[] face:sourceFaces)estimatedFaces+=face.length==4?4:1;
        long estimatedVertices=sourceVertices.length+(long)sourceFaces.length*5;
        checkLimits((int)Math.min(Integer.MAX_VALUE,estimatedVertices),
                (int)Math.min(Integer.MAX_VALUE,estimatedFaces));

        ArrayList<float[]> vertices=new ArrayList<>(sourceVertices.length+sourceFaces.length*5);
        for(float[] p:sourceVertices)vertices.add(p.clone());
        Map<Long,Integer> midpointIds=new HashMap<>();
        ArrayList<int[]> faces=new ArrayList<>((int)estimatedFaces);
        ArrayList<float[][]> uvFaces=sourceUvs==null?null:new ArrayList<>((int)estimatedFaces);
        for(int faceIndex=0;faceIndex<sourceFaces.length;faceIndex++){
            int[] face=sourceFaces[faceIndex];
            if(face.length!=4){
                faces.add(face.clone());
                if(uvFaces!=null)uvFaces.add(cloneUvFace(sourceUvs[faceIndex]));
                continue;
            }
            int[] midpoints=new int[4];
            for(int corner=0;corner<4;corner++){
                int a=face[corner],b=face[(corner+1)%4];
                long key=edgeKey(a,b);
                Integer midpoint=midpointIds.get(key);
                if(midpoint==null){
                    float[] pa=sourceVertices[a],pb=sourceVertices[b];
                    midpoint=vertices.size();
                    vertices.add(new float[]{(pa[0]+pb[0])*.5f,(pa[1]+pb[1])*.5f,(pa[2]+pb[2])*.5f});
                    midpointIds.put(key,midpoint);
                }
                midpoints[corner]=midpoint;
            }
            float[] center=new float[3];
            for(int id:face)for(int axis=0;axis<3;axis++)center[axis]+=sourceVertices[id][axis]*.25f;
            int centerId=vertices.size();vertices.add(center);
            float[][] uv=uvFaces==null?null:sourceUvs[faceIndex];
            float[] uvCenter=null;float[][] uvMidpoints=null;
            if(uv!=null){
                uvCenter=new float[2];uvMidpoints=new float[4][2];
                for(int i=0;i<4;i++)for(int axis=0;axis<2;axis++){
                    uvCenter[axis]+=uv[i][axis]*.25f;
                    uvMidpoints[i][axis]=(uv[i][axis]+uv[(i+1)%4][axis])*.5f;
                }
            }
            for(int corner=0;corner<4;corner++){
                int previous=(corner+3)%4;
                faces.add(new int[]{face[corner],midpoints[corner],centerId,midpoints[previous]});
                if(uvFaces!=null){
                    uvFaces.add(new float[][]{uv[corner].clone(),uvMidpoints[corner].clone(),
                            uvCenter.clone(),uvMidpoints[previous].clone()});
                }
            }
        }
        mesh.replaceGeometry(vertices.toArray(new float[0][]),faces.toArray(new int[0][]),
                uvFaces==null?null:uvFaces.toArray(new float[0][][]));
    }

    private static long edgeKey(int a,int b) {
        int min=Math.min(a,b),max=Math.max(a,b);
        return (((long)min)<<32)|(max&0xffffffffL);
    }

    private static void validateUvs(int[][] faces,float[][][] uvs) {
        if(uvs==null)return;
        if(uvs.length!=faces.length)
            throw new IllegalStateException("Modifier evaluation requires one UV face for every mesh face.");
        for(int i=0;i<faces.length;i++)
            if(uvs[i]==null||uvs[i].length!=faces[i].length)
                throw new IllegalStateException("Modifier evaluation found incomplete UV data on face "+i+".");
    }

    private static float[][] cloneUvFace(float[][] source) {
        float[][] result=new float[source.length][2];
        for(int i=0;i<source.length;i++)result[i]=source[i].clone();
        return result;
    }

    private static float[][] reverseUvFace(float[][] source) {
        float[][] result=new float[source.length][2];
        for(int i=0;i<source.length;i++)result[i]=source[source.length-1-i].clone();
        return result;
    }
}
