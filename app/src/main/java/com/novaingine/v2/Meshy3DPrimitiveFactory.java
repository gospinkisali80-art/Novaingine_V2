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
        else if("Circle".equalsIgnoreCase(type))g=circle(32);
        else if("UV Sphere".equalsIgnoreCase(type))g=uvSphere(32,20);
        else if("Icosphere".equalsIgnoreCase(type))g=icoSphere(2);
        else if("Cylinder".equalsIgnoreCase(type))g=cylinder(32,1f,false);
        else if("Cone".equalsIgnoreCase(type))g=cylinder(32,1f,true);
        else if("Torus".equalsIgnoreCase(type))g=torus(48,16,.72f,.28f);
        else if("Grid".equalsIgnoreCase(type))g=grid(16,16);
        else if("Monkey".equalsIgnoreCase(type)||"Suzanne".equalsIgnoreCase(type))g=monkeyHead();
        else throw new IllegalArgumentException("Unsupported primitive: "+type);
        orientFacesForViewport(g,"Plane".equalsIgnoreCase(type)
                ||"Circle".equalsIgnoreCase(type)||"Grid".equalsIgnoreCase(type));
        mesh.replaceGeometry(g.vertices,g.faces);
        return mesh;
    }

    /**
     * The viewport uses back-face culling. Procedural meshes must therefore have
     * consistent outward winding or they can be created in the scene but appear
     * missing. Flat primitives face +Y; closed primitives are oriented outward
     * using their vertex centroid.
     */
    private static void orientFacesForViewport(Geometry geometry,boolean flatSurface) {
        if(geometry.vertices.length==0||geometry.faces.length==0)return;
        float cx=0f,cy=0f,cz=0f;
        for(float[] p:geometry.vertices){cx+=p[0];cy+=p[1];cz+=p[2];}
        cx/=geometry.vertices.length;cy/=geometry.vertices.length;cz/=geometry.vertices.length;
        double orientation=0.0;
        for(int[] face:geometry.faces) {
            if(face==null||face.length<3)continue;
            float[] a=geometry.vertices[face[0]];
            float[] b=geometry.vertices[face[1]];
            float[] d=geometry.vertices[face[2]];
            float abx=b[0]-a[0],aby=b[1]-a[1],abz=b[2]-a[2];
            float acx=d[0]-a[0],acy=d[1]-a[1],acz=d[2]-a[2];
            float nx=aby*acz-abz*acy;
            float ny=abz*acx-abx*acz;
            float nz=abx*acy-aby*acx;
            if(flatSurface)orientation+=ny;
            else {
                float fx=0f,fy=0f,fz=0f;
                for(int id:face){float[] p=geometry.vertices[id];fx+=p[0];fy+=p[1];fz+=p[2];}
                float inv=1f/face.length;
                orientation+=nx*(fx*inv-cx)+ny*(fy*inv-cy)+nz*(fz*inv-cz);
            }
        }
        if(orientation<0.0)for(int[] face:geometry.faces)
            for(int left=0,right=face.length-1;left<right;left++,right--){
                int swap=face[left];face[left]=face[right];face[right]=swap;
            }
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

    /** Blender-style Circle primitive represented as a filled n-gon disk. */
    private static Geometry circle(int segments) {
        ArrayList<float[]> v=new ArrayList<>();ArrayList<int[]> f=new ArrayList<>();
        int[] ring=new int[segments];
        for(int i=0;i<segments;i++){
            double angle=2.0*Math.PI*i/segments;
            ring[i]=v.size();
            v.add(new float[]{(float)Math.cos(angle),0f,(float)Math.sin(angle)});
        }
        f.add(ring);
        return new Geometry(v,f);
    }

    /** Regular quad grid with independent X/Y subdivision counts. */
    private static Geometry grid(int xSegments,int ySegments) {
        xSegments=Math.max(1,Math.min(128,xSegments));
        ySegments=Math.max(1,Math.min(128,ySegments));
        ArrayList<float[]> v=new ArrayList<>();
        ArrayList<int[]> f=new ArrayList<>();
        for(int y=0;y<=ySegments;y++){
            float z=-1f+2f*y/ySegments;
            for(int x=0;x<=xSegments;x++){
                float px=-1f+2f*x/xSegments;
                v.add(new float[]{px,0f,z});
            }
        }
        int stride=xSegments+1;
        for(int y=0;y<ySegments;y++)for(int x=0;x<xSegments;x++){
            int a=y*stride+x,b=a+1,d=(y+1)*stride+x,c=d+1;
            f.add(new int[]{a,b,c,d});
        }
        return new Geometry(v,f);
    }

    /**
     * A lightweight Suzanne-style monkey head made from connected-looking
     * ellipsoidal regions. It is an editable multi-shell approximation, not
     * Blender's original Suzanne topology.
     */
    private static Geometry monkeyHead() {
        ArrayList<float[]> vertices=new ArrayList<>();
        ArrayList<int[]> faces=new ArrayList<>();
        appendEllipsoid(vertices,faces,0f,0f,0f,.78f,.88f,.62f);
        appendEllipsoid(vertices,faces,-.82f,-.02f,0f,.30f,.43f,.25f);
        appendEllipsoid(vertices,faces,.82f,-.02f,0f,.30f,.43f,.25f);
        appendEllipsoid(vertices,faces,0f,-.28f,.48f,.43f,.34f,.30f);
        appendEllipsoid(vertices,faces,0f,-.52f,.43f,.34f,.20f,.24f);
        appendEllipsoid(vertices,faces,-.24f,.20f,.49f,.24f,.16f,.17f);
        appendEllipsoid(vertices,faces,.24f,.20f,.49f,.24f,.16f,.17f);
        appendEllipsoid(vertices,faces,0f,.02f,.70f,.12f,.15f,.13f);
        return new Geometry(vertices,faces);
    }

    private static void appendEllipsoid(ArrayList<float[]> vertices,ArrayList<int[]> faces,
                                        float cx,float cy,float cz,float rx,float ry,float rz) {
        Geometry source=uvSphere(16,12);
        int offset=vertices.size();
        for(float[] p:source.vertices)
            vertices.add(new float[]{cx+p[0]*rx,cy+p[1]*ry,cz+p[2]*rz});
        for(int[] face:source.faces){
            int[] copy=face.clone();
            for(int i=0;i<copy.length;i++)copy[i]+=offset;
            faces.add(copy);
        }
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
        int top;
        if(cone){top=v.size();v.add(new float[]{0,1,0});}
        else{
            for(int i=0;i<segments;i++){
                double a=2*Math.PI*i/segments;float x=(float)Math.cos(a)*radius,z=(float)Math.sin(a)*radius;
                v.add(new float[]{x,1,z});
            }
            top=v.size();v.add(new float[]{0,1,0});
        }
        int bottom=v.size();v.add(new float[]{0,-1,0});
        for(int i=0;i<segments;i++){
            int next=(i+1)%segments;
            if(cone)f.add(new int[]{i,next,top});
            else f.add(new int[]{i,next,segments+next,segments+i});
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
