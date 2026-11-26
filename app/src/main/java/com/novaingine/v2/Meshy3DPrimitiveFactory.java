package com.novaingine.v2;

import java.util.ArrayList;

/** Procedural mesh data for the primitive objects offered by Object Mode. */
public final class Meshy3DPrimitiveFactory {
    private Meshy3DPrimitiveFactory() {}

    public static Meshy3DCore create(String primitive) {
        String type=primitive==null?"Cube":primitive.trim();
        Meshy3DCore mesh=new Meshy3DCore();
        if("Cube".equalsIgnoreCase(type))return mesh;
        Geometry g;
        if("Plane".equalsIgnoreCase(type))g=plane();
        else if("UV Sphere".equalsIgnoreCase(type))g=uvSphere(24,16);
        else if("Icosphere".equalsIgnoreCase(type))g=icoSphere(2);
        else if("Cylinder".equalsIgnoreCase(type))g=cylinder(32,1f,false);
        else if("Cone".equalsIgnoreCase(type))g=cylinder(32,1f,true);
        else if("Torus".equalsIgnoreCase(type))g=torus(32,12,.72f,.28f);
        else throw new IllegalArgumentException("Unsupported primitive: "+type);
        mesh.replaceGeometry(g.vertices,g.faces);
        return mesh;
    }

    private static final class Geometry {
        final float[][] vertices; final int[][] faces;
        Geometry(ArrayList<float[]> v,ArrayList<int[]> f){vertices=v.toArray(new float[0][]);faces=f.toArray(new int[0][]);}
    }

    private static Geometry plane() {
        ArrayList<float[]> v=new ArrayList<>();ArrayList<int[]> f=new ArrayList<>();
        v.add(new float[]{-1,0,-1});v.add(new float[]{1,0,-1});
        v.add(new float[]{1,0,1});v.add(new float[]{-1,0,1});
        f.add(new int[]{0,1,2,3});return new Geometry(v,f);
    }

    private static Geometry uvSphere(int segments,int rings) {
        ArrayList<float[]> v=new ArrayList<>();ArrayList<int[]> f=new ArrayList<>();
        for(int r=0;r<=rings;r++){
            double phi=Math.PI*r/rings;
            for(int s=0;s<=segments;s++){
                double theta=2*Math.PI*s/segments;
                v.add(new float[]{(float)(Math.sin(phi)*Math.cos(theta)),
                        (float)Math.cos(phi),(float)(Math.sin(phi)*Math.sin(theta))});
            }
        }
        for(int r=0;r<rings;r++)for(int s=0;s<segments;s++){
            int a=r*(segments+1)+s,b=a+1,d=(r+1)*(segments+1)+s,c=d+1;
            if(r==0)f.add(new int[]{a,c,d});
            else if(r==rings-1)f.add(new int[]{a,b,d});
            else f.add(new int[]{a,b,c,d});
        }
        return new Geometry(v,f);
    }

    private static Geometry cylinder(int segments,float radius,boolean cone) {
        ArrayList<float[]> v=new ArrayList<>();ArrayList<int[]> f=new ArrayList<>();
        for(int i=0;i<segments;i++){
            double a=2*Math.PI*i/segments;float x=(float)Math.cos(a)*radius,z=(float)Math.sin(a)*radius;
            v.add(new float[]{x,-1,z});
        }
        for(int i=0;i<segments;i++){
            double a=2*Math.PI*i/segments;float x=(float)Math.cos(a)*radius,z=(float)Math.sin(a)*radius;
            v.add(new float[]{cone?0:x,1,cone?0:z});
        }
        int bottom=v.size();v.add(new float[]{0,-1,0});
        int top=v.size();v.add(new float[]{0,1,0});
        for(int i=0;i<segments;i++){
            int next=(i+1)%segments;
            f.add(new int[]{i,next,segments+next,segments+i});
            f.add(new int[]{bottom,next,i});
            if(!cone)f.add(new int[]{top,segments+i,segments+next});
        }
        return new Geometry(v,f);
    }

    private static Geometry torus(int majorSegments,int minorSegments,float majorRadius,float minorRadius) {
        ArrayList<float[]> v=new ArrayList<>();ArrayList<int[]> f=new ArrayList<>();
        for(int i=0;i<majorSegments;i++){
            double u=2*Math.PI*i/majorSegments;
            for(int j=0;j<minorSegments;j++){
                double w=2*Math.PI*j/minorSegments;
                float ring=majorRadius+minorRadius*(float)Math.cos(w);
                v.add(new float[]{ring*(float)Math.cos(u),minorRadius*(float)Math.sin(w),ring*(float)Math.sin(u)});
            }
        }
        for(int i=0;i<majorSegments;i++)for(int j=0;j<minorSegments;j++){
            int a=i*minorSegments+j,b=i*minorSegments+(j+1)%minorSegments;
            int c=((i+1)%majorSegments)*minorSegments+(j+1)%minorSegments;
            int d=((i+1)%majorSegments)*minorSegments+j;
            f.add(new int[]{a,b,c,d});
        }
        return new Geometry(v,f);
    }

    private static Geometry icoSphere(int subdivisions) {
        double t=(1.0+Math.sqrt(5.0))/2.0;
        ArrayList<float[]> v=new ArrayList<>();
        float[][] base={{-1,(float)t,0},{1,(float)t,0},{-1,(float)-t,0},{1,(float)-t,0},
                {0,-1,(float)t},{0,1,(float)t},{0,-1,(float)-t},{0,1,(float)-t},
                {(float)t,0,-1},{(float)t,0,1},{(float)-t,0,-1},{(float)-t,0,1}};
        for(float[] p:base)v.add(normalize(p));
        ArrayList<int[]> f=new ArrayList<>(java.util.Arrays.asList(
                new int[]{0,11,5},new int[]{0,5,1},new int[]{0,1,7},new int[]{0,7,10},new int[]{0,10,11},
                new int[]{1,5,9},new int[]{5,11,4},new int[]{11,10,2},new int[]{10,7,6},new int[]{7,1,8},
                new int[]{3,9,4},new int[]{3,4,2},new int[]{3,2,6},new int[]{3,6,8},new int[]{3,8,9},
                new int[]{4,9,5},new int[]{2,4,11},new int[]{6,2,10},new int[]{8,6,7},new int[]{9,8,1}));
        for(int level=0;level<subdivisions;level++){
            java.util.HashMap<Long,Integer> cache=new java.util.HashMap<>();ArrayList<int[]> next=new ArrayList<>();
            for(int[] face:f){
                int a=midpoint(face[0],face[1],v,cache),b=midpoint(face[1],face[2],v,cache),c=midpoint(face[2],face[0],v,cache);
                next.add(new int[]{face[0],a,c});next.add(new int[]{face[1],b,a});
                next.add(new int[]{face[2],c,b});next.add(new int[]{a,b,c});
            }
            f=next;
        }
        return new Geometry(v,f);
    }

    private static int midpoint(int a,int b,ArrayList<float[]> v,java.util.HashMap<Long,Integer> cache) {
        int lo=Math.min(a,b),hi=Math.max(a,b);long key=((long)lo<<32)|(hi&0xffffffffL);
        Integer id=cache.get(key);if(id!=null)return id;
        float[] p=v.get(a),q=v.get(b);
        int next=v.size();v.add(normalize(new float[]{(p[0]+q[0])*.5f,(p[1]+q[1])*.5f,(p[2]+q[2])*.5f}));
        cache.put(key,next);return next;
    }
    private static float[] normalize(float[] p) {
        float len=(float)Math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);
        return len<1e-8f?p:new float[]{p[0]/len,p[1]/len,p[2]/len};
    }
}
