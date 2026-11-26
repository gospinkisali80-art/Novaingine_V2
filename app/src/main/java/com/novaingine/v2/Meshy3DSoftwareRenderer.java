package com.novaingine.v2;

import android.graphics.Bitmap;
import java.util.Arrays;

/** CPU triangle rasterizer for deterministic Meshy3D final-image rendering. */
public final class Meshy3DSoftwareRenderer {
    private Meshy3DSoftwareRenderer(){}
    private static final class V {float x,y,z,sx,sy,depth;V(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}
    public static Bitmap render(float[][] vertices,int[][] faces,int width,int height,
                                float yaw,float pitch,int color,float metallic,float roughness,
                                int[] texture,int textureWidth,int textureHeight,float[][][] faceUvs){
        return render(vertices,faces,width,height,yaw,pitch,1f,color,metallic,roughness,
                texture,textureWidth,textureHeight,faceUvs);
    }

    public static Bitmap render(float[][] vertices,int[][] faces,int width,int height,
                                float yaw,float pitch,float zoom,int color,float metallic,float roughness,
                                int[] texture,int textureWidth,int textureHeight,float[][][] faceUvs){
        return render(vertices,faces,width,height,yaw,pitch,0f,zoom,color,metallic,roughness,
                texture,textureWidth,textureHeight,faceUvs);
    }

    public static Bitmap render(float[][] vertices,int[][] faces,int width,int height,
                                float yaw,float pitch,float roll,float zoom,int color,float metallic,float roughness,
                                int[] texture,int textureWidth,int textureHeight,float[][][] faceUvs){
        width=Math.max(64,Math.min(2048,width));height=Math.max(64,Math.min(2048,height));
        zoom=Math.max(.2f,Math.min(4f,zoom));
        int[] pixels=new int[width*height];float[] depth=new float[width*height];
        Arrays.fill(depth,Float.POSITIVE_INFINITY);
        for(int y=0;y<height;y++){float t=y/(float)Math.max(1,height-1);int r=(int)(39+22*t),g=(int)(49+29*t),b=(int)(63+36*t);
            int c=0xFF000000|r<<16|g<<8|b;Arrays.fill(pixels,y*width,(y+1)*width,c);}
        V[] projected=new V[vertices.length];float unit=Math.min(width,height)*.31f*zoom;
        double cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
        for(int i=0;i<vertices.length;i++){
            float x=vertices[i][0],y=vertices[i][1],z=vertices[i][2];
            double rx=x*cy-z*sy,rz=x*sy+z*cy,ry=y*cp-rz*sp,rz2=y*sp+rz*cp;
            double cr=Math.cos(roll),sr=Math.sin(roll);
            double rolledX=rx*cr+ry*sr,rolledY=-rx*sr+ry*cr;
            float dep=(float)(5.2-rz2),perspective=(float)(5.2/Math.max(2.0,dep));
            V q=new V((float)rolledX,(float)rolledY,(float)rz2);q.depth=dep;
            q.sx=width*.5f+q.x*unit*perspective;q.sy=height*.49f-q.y*unit*perspective;projected[i]=q;
        }
        float br=((color>>16)&255)/255f,bg=((color>>8)&255)/255f,bb=(color&255)/255f;
        float[] light=normalize(new float[]{-.45f,.82f,.65f});
        float[] view=normalize(new float[]{0,0,1});
        float rough=Math.max(.03f,Math.min(1,roughness)),metal=Math.max(0,Math.min(1,metallic));
        for(int fi=0;fi<faces.length;fi++){
            int[] face=faces[fi];if(face.length<3)continue;V a=projected[face[0]],b=projected[face[1]],c=projected[face[2]];
            float[] n=normalize(cross(new float[]{b.x-a.x,b.y-a.y,b.z-a.z},new float[]{c.x-a.x,c.y-a.y,c.z-a.z}));
            if(dot(n,new float[]{-a.x,-a.y,5.2f-a.z})<=0)continue;
            for(int tri=1;tri<face.length-1;tri++)
                raster(projected[face[0]],projected[face[tri]],projected[face[tri+1]],n,width,height,pixels,depth,
                        br,bg,bb,metal,rough,light,view,
                        faceUvs!=null&&fi<faceUvs.length&&faceUvs[fi].length>tri+1?faceUvs[fi][0]:null,
                        faceUvs!=null&&fi<faceUvs.length&&faceUvs[fi].length>tri+1?faceUvs[fi][tri]:null,
                        faceUvs!=null&&fi<faceUvs.length&&faceUvs[fi].length>tri+1?faceUvs[fi][tri+1]:null,
                        texture,textureWidth,textureHeight);
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    /** Render a complete object scene using per-object mesh materials and textures. */
    public static Bitmap renderScene(float[][] vertices,int[][] faces,int width,int height,
                                     float yaw,float pitch,float zoom,
                                     int[] faceMaterialIds,int[] materialColors,
                                     float[] materialMetallic,float[] materialRoughness,
                                     int[][] materialTextures,int[] textureWidths,int[] textureHeights,
                                     float[][] faceLightDirections,float[][] faceLightTints,
                                     float[][][] faceUvs){
        return renderScene(vertices,faces,width,height,yaw,pitch,0f,zoom,
                faceMaterialIds,materialColors,materialMetallic,materialRoughness,
                materialTextures,textureWidths,textureHeights,faceLightDirections,faceLightTints,faceUvs);
    }

    public static Bitmap renderScene(float[][] vertices,int[][] faces,int width,int height,
                                     float yaw,float pitch,float roll,float zoom,
                                     int[] faceMaterialIds,int[] materialColors,
                                     float[] materialMetallic,float[] materialRoughness,
                                     int[][] materialTextures,int[] textureWidths,int[] textureHeights,
                                     float[][] faceLightDirections,float[][] faceLightTints,
                                     float[][][] faceUvs){
        width=Math.max(64,Math.min(2048,width));height=Math.max(64,Math.min(2048,height));
        zoom=Math.max(.2f,Math.min(4f,zoom));
        int[] pixels=new int[width*height];float[] depth=new float[width*height];
        Arrays.fill(depth,Float.POSITIVE_INFINITY);
        for(int y=0;y<height;y++){
            float t=y/(float)Math.max(1,height-1);
            int r=(int)(39+22*t),g=(int)(49+29*t),b=(int)(63+36*t);
            int bg=0xFF000000|r<<16|g<<8|b;Arrays.fill(pixels,y*width,(y+1)*width,bg);
        }
        V[] projected=new V[vertices.length];float unit=Math.min(width,height)*.31f*zoom;
        double cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
        for(int i=0;i<vertices.length;i++){
            float x=vertices[i][0],y=vertices[i][1],z=vertices[i][2];
            double rx=x*cy-z*sy,rz=x*sy+z*cy,ry=y*cp-rz*sp,rz2=y*sp+rz*cp;
            double cr=Math.cos(roll),sr=Math.sin(roll);
            double rolledX=rx*cr+ry*sr,rolledY=-rx*sr+ry*cr;
            float dep=(float)(5.2-rz2),perspective=(float)(5.2/Math.max(2.0,dep));
            V q=new V((float)rolledX,(float)rolledY,(float)rz2);q.depth=dep;
            q.sx=width*.5f+q.x*unit*perspective;q.sy=height*.49f-q.y*unit*perspective;
            projected[i]=q;
        }
        float[] defaultLight=normalize(new float[]{-.45f,.82f,.65f});
        float[] view=normalize(new float[]{0,0,1});
        for(int fi=0;fi<faces.length;fi++){
            int[] face=faces[fi];if(face.length<3)continue;
            int material=faceMaterialIds!=null&&fi<faceMaterialIds.length?faceMaterialIds[fi]:0;
            material=Math.max(0,Math.min(material,Math.max(0,materialColors.length-1)));
            int base=materialColors.length==0?0xFF9DA7B2:materialColors[material];
            float br=((base>>16)&255)/255f,bg=((base>>8)&255)/255f,bb=(base&255)/255f;
            float metal=materialMetallic!=null&&material<materialMetallic.length?materialMetallic[material]:0f;
            float rough=materialRoughness!=null&&material<materialRoughness.length?materialRoughness[material]:.65f;
            metal=Math.max(0,Math.min(1,metal));rough=Math.max(.03f,Math.min(1,rough));
            int[] texture=materialTextures!=null&&material<materialTextures.length?materialTextures[material]:null;
            int tw=textureWidths!=null&&material<textureWidths.length?textureWidths[material]:0;
            int th=textureHeights!=null&&material<textureHeights.length?textureHeights[material]:0;
            float[][] uv=faceUvs!=null&&fi<faceUvs.length?faceUvs[fi]:null;
            float[] light=faceLightDirections!=null&&fi<faceLightDirections.length
                    ?normalize(faceLightDirections[fi]):defaultLight;
            if(faceLightTints!=null&&fi<faceLightTints.length&&faceLightTints[fi]!=null){
                float[] tint=faceLightTints[fi];
                br*=tint.length>0?tint[0]:1f;bg*=tint.length>1?tint[1]:1f;bb*=tint.length>2?tint[2]:1f;
            }
            V a=projected[face[0]],b=projected[face[1]],cc=projected[face[2]];
            float[] n=normalize(cross(new float[]{b.x-a.x,b.y-a.y,b.z-a.z},
                    new float[]{cc.x-a.x,cc.y-a.y,cc.z-a.z}));
            if(dot(n,new float[]{-a.x,-a.y,5.2f-a.z})<=0)continue;
            for(int tri=1;tri<face.length-1;tri++){
                float[] uva=uv!=null&&uv.length>0?uv[0]:null;
                float[] uvb=uv!=null&&uv.length>tri?uv[tri]:null;
                float[] uvc=uv!=null&&uv.length>tri+1?uv[tri+1]:null;
                raster(projected[face[0]],projected[face[tri]],projected[face[tri+1]],n,
                        width,height,pixels,depth,br,bg,bb,metal,rough,light,view,
                        uva,uvb,uvc,texture,tw,th);
            }
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }

    private static void raster(V a,V b,V c,float[] n,int w,int h,int[] pixels,float[] zbuf,
                               float br,float bg,float bb,float metal,float rough,float[] light,float[] view,
                               float[] uva,float[] uvb,float[] uvc,int[] texture,int tw,int th){
        float area=edge(a.sx,a.sy,b.sx,b.sy,c.sx,c.sy);if(Math.abs(area)<.0001f)return;
        int minX=Math.max(0,(int)Math.floor(Math.min(a.sx,Math.min(b.sx,c.sx))));
        int maxX=Math.min(w-1,(int)Math.ceil(Math.max(a.sx,Math.max(b.sx,c.sx))));
        int minY=Math.max(0,(int)Math.floor(Math.min(a.sy,Math.min(b.sy,c.sy))));
        int maxY=Math.min(h-1,(int)Math.ceil(Math.max(a.sy,Math.max(b.sy,c.sy))));
        float ndl=Math.max(0,dot(n,light)),ndv=Math.max(.001f,dot(n,view));
        float[] half=normalize(new float[]{light[0]+view[0],light[1]+view[1],light[2]+view[2]});
        float ndh=Math.max(0,dot(n,half)),vdh=Math.max(0,dot(view,half));
        float alpha=rough*rough,alpha2=alpha*alpha;
        float denom=ndh*ndh*(alpha2-1f)+1f;
        float distribution=alpha2/(float)(Math.PI*denom*denom+1e-6);
        float k=(rough+1f)*(rough+1f)/8f;
        float geometryV=ndv/(ndv*(1-k)+k),geometryL=ndl/(ndl*(1-k)+k);
        float geometry=geometryV*geometryL;
        for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++){
            float px=x+.5f,py=y+.5f;
            float wa=edge(b.sx,b.sy,c.sx,c.sy,px,py)/area;
            float wb=edge(c.sx,c.sy,a.sx,a.sy,px,py)/area;
            float wc=1-wa-wb;if(wa<0||wb<0||wc<0)continue;
            float z=wa*a.depth+wb*b.depth+wc*c.depth;int index=y*w+x;
            if(z>=zbuf[index])continue;zbuf[index]=z;
            float texR=1,texG=1,texB=1;
            if(texture!=null&&tw>0&&th>0&&uva!=null&&uvb!=null&&uvc!=null){
                float u=wa*uva[0]+wb*uvb[0]+wc*uvc[0],v=wa*uva[1]+wb*uvb[1]+wc*uvc[1];
                int tx=Math.max(0,Math.min(tw-1,(int)(u*(tw-1))));
                int ty=Math.max(0,Math.min(th-1,(int)((1-v)*(th-1))));
                int tex=texture[ty*tw+tx];texR=((tex>>16)&255)/255f;texG=((tex>>8)&255)/255f;texB=(tex&255)/255f;
            }
            float cr=br*texR,cg=bg*texG,cb=bb*texB;
            float fr=.04f*(1-metal)+cr*metal,fg=.04f*(1-metal)+cg*metal,fb=.04f*(1-metal)+cb*metal;
            float fresnel=(float)Math.pow(1-vdh,5);
            fr+=(1-fr)*fresnel;fg+=(1-fg)*fresnel;fb+=(1-fb)*fresnel;
            float specScale=distribution*geometry/(4f*ndv*Math.max(.001f,ndl)+.001f);
            float ambient=.12f;
            float outR=(cr*(1-metal)*(1-fr)*(ndl*2.0f+ambient)/(float)Math.PI)+fr*specScale*ndl*2.0f;
            float outG=(cg*(1-metal)*(1-fg)*(ndl*2.0f+ambient)/(float)Math.PI)+fg*specScale*ndl*2.0f;
            float outB=(cb*(1-metal)*(1-fb)*(ndl*2.0f+ambient)/(float)Math.PI)+fb*specScale*ndl*2.0f;
            float fog=Math.max(.0f,Math.min(.25f,(z-3f)*.025f));
            // Simple filmic compression followed by display gamma.
            int r=clamp((float)Math.pow(outR/(1f+outR),1f/2.2f)*(1-fog)*255+fog*80);
            int g=clamp((float)Math.pow(outG/(1f+outG),1f/2.2f)*(1-fog)*255+fog*90);
            int bl=clamp((float)Math.pow(outB/(1f+outB),1f/2.2f)*(1-fog)*255+fog*105);
            pixels[index]=0xFF000000|r<<16|g<<8|bl;
        }
    }
    private static float edge(float ax,float ay,float bx,float by,float px,float py){return (px-ax)*(by-ay)-(py-ay)*(bx-ax);}
    private static float[] cross(float[] a,float[] b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static float dot(float[] a,float[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static float[] normalize(float[] a){float l=(float)Math.sqrt(dot(a,a));if(l<1e-6f)return new float[]{0,0,1};return new float[]{a[0]/l,a[1]/l,a[2]/l};}
    private static int clamp(float v){return Math.max(0,Math.min(255,Math.round(v)));}
}