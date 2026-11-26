package com.novaingine.v2;

import java.util.*;

/** Least-Squares Conformal Mapping (LSCM) UV parameterization with pinned anchors. */
public final class Meshy3DUvUnwrapper {
    private Meshy3DUvUnwrapper(){}
    private static final class Row {final int[] ids;final double[] c;Row(int[] i,double[] v){ids=i;c=v;}}
    public static float[][][] unwrap(float[][] vertices,int[][] faces,List<Meshy3DCore.UvIsland> islands,Set<Long> pins,float[][][] previousUvs){
        float[][][] result=new float[faces.length][][];
        int cols=Math.max(1,(int)Math.ceil(Math.sqrt(islands.size()))),rows=(int)Math.ceil(islands.size()/(float)cols);
        for(int islandIndex=0;islandIndex<islands.size();islandIndex++){
            List<Integer> faceIds=islands.get(islandIndex).faces;
            LinkedHashMap<Integer,Integer> local=new LinkedHashMap<>();
            for(int fi:faceIds)for(int id:faces[fi])if(!local.containsKey(id))local.put(id,local.size());
            int count=local.size();if(count<3)continue;
            Integer[] ids=local.keySet().toArray(new Integer[0]);
            ArrayList<Row> equations=new ArrayList<>();
            for(int fi:faceIds){int[] f=faces[fi];for(int k=1;k<f.length-1;k++){
                int a=local.get(f[0]),b=local.get(f[k]),c=local.get(f[k+1]);
                addTriangleEquations(vertices,ids,a,b,c,equations);
            }}
            int pinA=0,pinB=1;double farthest=-1;
            for(int i=0;i<count;i++)for(int j=i+1;j<count;j++){
                float[] a=vertices[ids[i]],b=vertices[ids[j]];
                double d=sq(a[0]-b[0])+sq(a[1]-b[1])+sq(a[2]-b[2]);
                if(d>farthest){farthest=d;pinA=i;pinB=j;}
            }
            ArrayList<Integer> pinned=new ArrayList<>();for(int i=0;i<count;i++)if(pins.contains((long)ids[i]))pinned.add(i);
            if(pinned.size()>=2){pinA=pinned.get(0);pinB=pinned.get(1);}
            else if(pinned.size()==1)pinA=pinned.get(0);
            double[] target=new double[count*2],penalty=new double[count*2];
            float[] oldA=findOldUv(previousUvs,faceIds,ids[pinA]);
            float[] oldB=findOldUv(previousUvs,faceIds,ids[pinB]);
            target[pinA*2]=oldA==null?0:oldA[0];target[pinA*2+1]=oldA==null?0:oldA[1];
            target[pinB*2]=oldB==null?target[pinA*2]+1:oldB[0];target[pinB*2+1]=oldB==null?target[pinA*2+1]:oldB[1];
            double lambda=100000.0;
            penalty[pinA*2]=lambda;penalty[pinA*2+1]=lambda;penalty[pinB*2]=lambda;penalty[pinB*2+1]=lambda;
            double[] rhs=new double[count*2];rhs[pinA*2]=lambda*target[pinA*2];rhs[pinA*2+1]=lambda*target[pinA*2+1];
            rhs[pinB*2]=lambda*target[pinB*2];rhs[pinB*2+1]=lambda*target[pinB*2+1];
            double[] x=solve(equations,penalty,rhs,count*2);
            double minU=Double.POSITIVE_INFINITY,minV=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
            for(int i=0;i<count;i++){minU=Math.min(minU,x[2*i]);maxU=Math.max(maxU,x[2*i]);minV=Math.min(minV,x[2*i+1]);maxV=Math.max(maxV,x[2*i+1]);}
            if(!Double.isFinite(minU)||maxU-minU<1e-7||maxV-minV<1e-7){
                planarFallback(vertices,ids,x);
                minU=Double.POSITIVE_INFINITY;minV=Double.POSITIVE_INFINITY;maxU=Double.NEGATIVE_INFINITY;maxV=Double.NEGATIVE_INFINITY;
                for(int i=0;i<count;i++){minU=Math.min(minU,x[2*i]);maxU=Math.max(maxU,x[2*i]);minV=Math.min(minV,x[2*i+1]);maxV=Math.max(maxV,x[2*i+1]);}
            }
            double rangeU=Math.max(1e-7,maxU-minU),rangeV=Math.max(1e-7,maxV-minV);
            float tileW=1f/cols,tileH=1f/rows,margin=.035f;
            float scale=(float)Math.min((tileW-2*margin)/rangeU,(tileH-2*margin)/rangeV);
            float usedW=(float)rangeU*scale,usedH=(float)rangeV*scale;
            float ox=(islandIndex%cols)*tileW+(tileW-usedW)*.5f;
            float oy=(islandIndex/cols)*tileH+(tileH-usedH)*.5f;
            HashMap<Integer,float[]> uvByVertex=new HashMap<>();
            for(int i=0;i<count;i++)uvByVertex.put(ids[i],new float[]{ox+(float)(x[2*i]-minU)*scale,oy+(float)(x[2*i+1]-minV)*scale});
            for(int fi:faceIds){int[] f=faces[fi];result[fi]=new float[f.length][2];for(int j=0;j<f.length;j++)result[fi][j]=uvByVertex.get(f[j]).clone();}
        }
        return result;
    }
    private static float[] findOldUv(float[][][] old,List<Integer> faceIds,int vertex){
        if(old==null)return null;for(int fi:faceIds)if(fi<old.length&&old[fi]!=null)
            for(int j=0;j<old[fi].length;j++)if(old[fi][j]!=null)return old[fi][j].clone();
        return null;
    }
    private static void addTriangleEquations(float[][] vertices,Integer[] ids,int a,int b,int c,List<Row> rows){
        float[] p0=vertices[ids[a]],p1=vertices[ids[b]],p2=vertices[ids[c]];
        double dx1=p1[0]-p0[0],dy1=p1[1]-p0[1],dz1=p1[2]-p0[2];
        double length=Math.sqrt(dx1*dx1+dy1*dy1+dz1*dz1);if(length<1e-8)return;
        double ex=dx1/length,ey=dy1/length,ez=dz1/length;
        double dx2=p2[0]-p0[0],dy2=p2[1]-p0[1],dz2=p2[2]-p0[2];
        double x2=dx2*ex+dy2*ey+dz2*ez;
        double d2=dx2*dx2+dy2*dy2+dz2*dz2;
        double y2=Math.sqrt(Math.max(0,d2-x2*x2));if(y2<1e-8)return;
        double area=length*y2;
        double gx0=-y2/area,gx1=y2/area,gx2=0;
        double gy0=(x2-length)/area,gy1=-x2/area,gy2=length/area;
        double scale=Math.sqrt(area);
        int[] indexes={2*a,2*b,2*c,2*a+1,2*b+1,2*c+1};
        rows.add(new Row(indexes,new double[]{gx0*scale,gx1*scale,gx2*scale,-gy0*scale,-gy1*scale,-gy2*scale}));
        rows.add(new Row(indexes,new double[]{gy0*scale,gy1*scale,gy2*scale,gx0*scale,gx1*scale,gx2*scale}));
    }
    private static double[] solve(List<Row> rows,double[] diagonal,double[] rhs,int size){
        double[] x=new double[size],r=rhs.clone(),p=r.clone(),ap=new double[size];
        double rr=dot(r,r);if(rr<1e-20)return x;
        for(int iteration=0;iteration<180;iteration++){
            apply(rows,diagonal,p,ap);double denom=dot(p,ap);if(Math.abs(denom)<1e-24)break;
            double alpha=rr/denom;for(int i=0;i<size;i++){x[i]+=alpha*p[i];r[i]-=alpha*ap[i];}
            double next=dot(r,r);if(next<1e-12)break;double beta=next/rr;
            for(int i=0;i<size;i++)p[i]=r[i]+beta*p[i];rr=next;
        }return x;
    }
    private static void apply(List<Row> rows,double[] diagonal,double[] x,double[] out){
        Arrays.fill(out,0);
        for(Row row:rows){double sum=0;for(int i=0;i<row.ids.length;i++)sum+=row.c[i]*x[row.ids[i]];
            for(int i=0;i<row.ids.length;i++)out[row.ids[i]]+=row.c[i]*sum;}
        for(int i=0;i<out.length;i++)out[i]+=diagonal[i]*x[i]+1e-8*x[i];
    }
    private static double dot(double[] a,double[] b){double s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return s;}
    private static double sq(double v){return v*v;}
    private static void planarFallback(float[][] vertices,Integer[] ids,double[] out){
        float[] a=vertices[ids[0]],b=vertices[ids[1]],c=vertices[ids[2]];
        float[] u={b[0]-a[0],b[1]-a[1],b[2]-a[2]},n=cross(u,new float[]{c[0]-a[0],c[1]-a[1],c[2]-a[2]});
        normalize(u);normalize(n);float[] v=cross(n,u);normalize(v);
        for(int i=0;i<ids.length;i++){float[] p=vertices[ids[i]];float[] d={p[0]-a[0],p[1]-a[1],p[2]-a[2]};out[2*i]=dot(d,u);out[2*i+1]=dot(d,v);}
    }
    private static float[] cross(float[] a,float[] b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static void normalize(float[] a){float l=(float)Math.sqrt(dot(a,a));if(l<1e-8)return;for(int i=0;i<a.length;i++)a[i]/=l;}
    private static float dot(float[] a,float[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
}