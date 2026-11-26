package com.novaingine.v2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scene-level object data for Meshy3D Object Mode.
 *
 * Mesh data is owned by Meshy3DCore. Objects own transforms, hierarchy,
 * collection membership and visibility. Linked duplicates deliberately share
 * the same Meshy3DCore instance; ordinary duplicates copy its serialized data.
 */
public final class Meshy3DObjectScene {
    public static final class Transform {
        public float x, y, z;
        public float rotationX, rotationY, rotationZ;
        public float scaleX = 1f, scaleY = 1f, scaleZ = 1f;

        public Transform copy() {
            Transform t = new Transform();
            t.x=x; t.y=y; t.z=z;
            t.rotationX=rotationX; t.rotationY=rotationY; t.rotationZ=rotationZ;
            t.scaleX=scaleX; t.scaleY=scaleY; t.scaleZ=scaleZ;
            return t;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o=new JSONObject();
            o.put("location", vector(x,y,z));
            o.put("rotation", vector(rotationX,rotationY,rotationZ));
            o.put("scale", vector(scaleX,scaleY,scaleZ));
            return o;
        }

        static Transform fromJson(JSONObject o) {
            Transform t=new Transform();
            if(o==null)return t;
            readVector(o.optJSONArray("location"),v->{t.x=v[0];t.y=v[1];t.z=v[2];});
            readVector(o.optJSONArray("rotation"),v->{t.rotationX=v[0];t.rotationY=v[1];t.rotationZ=v[2];});
            readVector(o.optJSONArray("scale"),v->{t.scaleX=v[0];t.scaleY=v[1];t.scaleZ=v[2];});
            t.scaleX=safeScale(t.scaleX);t.scaleY=safeScale(t.scaleY);t.scaleZ=safeScale(t.scaleZ);
            return t;
        }
    }

    public static final class Object3D {
        public final String id;
        public String name;
        public String type;
        public final Transform transform = new Transform();
        public Meshy3DCore mesh;
        public String parentId;
        public final ArrayList<String> collectionIds = new ArrayList<>();
        public boolean selected;
        public boolean active;
        public boolean visible = true;
        public boolean selectable = true;
        public boolean renderVisible = true;

        private Object3D(String id,String name,String type,Meshy3DCore mesh) {
            this.id=id;this.name=name;this.type=type;this.mesh=mesh;
        }
    }

    public static final class Collection3D {
        public final String id;
        public String name;
        public boolean visible=true;
        public boolean renderVisible=true;
        private Collection3D(String id,String name){this.id=id;this.name=name;}
    }

    private final LinkedHashMap<String,Object3D> objects=new LinkedHashMap<>();
    private final LinkedHashMap<String,Collection3D> collections=new LinkedHashMap<>();
    private String activeObjectId;
    private long revision;

    public Meshy3DObjectScene() {
        Collection3D collection=new Collection3D("collection-scene","Collection");
        collections.put(collection.id,collection);
    }

    public long getRevision(){return revision;}
    public int getObjectCount(){return objects.size();}
    public int getCollectionCount(){return collections.size();}
    public List<Object3D> getObjects(){return Collections.unmodifiableList(new ArrayList<>(objects.values()));}
    public List<Collection3D> getCollections(){return Collections.unmodifiableList(new ArrayList<>(collections.values()));}
    public Object3D getObject(String id){return objects.get(id);}
    public Object3D getActiveObject(){return objects.get(activeObjectId);}
    public String getActiveObjectId(){return activeObjectId;}

    public Object3D createMeshObject(String name,Meshy3DCore mesh) {
        if(mesh==null)throw new IllegalArgumentException("Mesh data is required");
        String id=UUID.randomUUID().toString();
        Object3D object=new Object3D(id,uniqueObjectName(name), "MESH", mesh);
        String collectionId=collections.keySet().iterator().next();
        object.collectionIds.add(collectionId);
        objects.put(id,object);
        selectOnly(id);
        revision++;
        return object;
    }

    public Object3D duplicate(String objectId,boolean linked) {
        Object3D source=requireObject(objectId);
        Meshy3DCore data;
        if(linked)data=source.mesh;
        else {
            data=new Meshy3DCore();
            if(!data.importState(source.mesh.exportState()))
                throw new IllegalStateException("Could not copy mesh data");
        }
        Object3D copy=createMeshObject(source.name+".copy",data);
        copy.type=source.type;
        copy.transform.x=source.transform.x;
        copy.transform.y=source.transform.y;
        copy.transform.z=source.transform.z;
        copy.transform.rotationX=source.transform.rotationX;
        copy.transform.rotationY=source.transform.rotationY;
        copy.transform.rotationZ=source.transform.rotationZ;
        copy.transform.scaleX=source.transform.scaleX;
        copy.transform.scaleY=source.transform.scaleY;
        copy.transform.scaleZ=source.transform.scaleZ;
        copy.visible=source.visible;
        copy.selectable=source.selectable;
        copy.renderVisible=source.renderVisible;
        copy.collectionIds.clear();
        copy.collectionIds.addAll(source.collectionIds);
        revision++;
        return copy;
    }

    public void delete(String objectId) {
        requireObject(objectId);
        ArrayList<String> children=new ArrayList<>();
        for(Object3D o:objects.values())if(objectId.equals(o.parentId))children.add(o.id);
        for(String child:children)objects.get(child).parentId=null;
        objects.remove(objectId);
        if(objectId.equals(activeObjectId))activeObjectId=null;
        revision++;
    }

    public void rename(String objectId,String name) {
        Object3D o=requireObject(objectId);
        String clean=name==null?"":name.trim();
        if(clean.isEmpty())throw new IllegalArgumentException("Object name cannot be empty");
        o.name=uniqueObjectName(clean,o.id);
        revision++;
    }

    public void selectOnly(String objectId) {
        if(objectId!=null)requireObject(objectId);
        for(Object3D o:objects.values()){o.selected=false;o.active=false;}
        activeObjectId=objectId;
        if(objectId!=null){Object3D o=objects.get(objectId);o.selected=true;o.active=true;}
        revision++;
    }

    public void setSelected(String objectId,boolean selected) {
        Object3D o=requireObject(objectId);
        if(!o.selectable&&selected)return;
        o.selected=selected;
        if(selected){activeObjectId=objectId;for(Object3D other:objects.values())other.active=false;o.active=true;}
        else if(o.active){o.active=false;activeObjectId=null;}
        revision++;
    }

    public List<Object3D> getSelectedObjects() {
        ArrayList<Object3D> result=new ArrayList<>();
        for(Object3D o:objects.values())if(o.selected)result.add(o);
        return result;
    }

    public void setTransform(String objectId,Transform transform) {
        if(transform==null)throw new IllegalArgumentException("Transform is required");
        Object3D o=requireObject(objectId);
        o.transform.x=finite(transform.x);o.transform.y=finite(transform.y);o.transform.z=finite(transform.z);
        o.transform.rotationX=finite(transform.rotationX);o.transform.rotationY=finite(transform.rotationY);o.transform.rotationZ=finite(transform.rotationZ);
        o.transform.scaleX=safeScale(transform.scaleX);o.transform.scaleY=safeScale(transform.scaleY);o.transform.scaleZ=safeScale(transform.scaleZ);
        revision++;
    }

    public void translate(String objectId,float x,float y,float z) {
        Object3D o=requireObject(objectId);o.transform.x+=finite(x);o.transform.y+=finite(y);o.transform.z+=finite(z);revision++;
    }
    public void rotate(String objectId,float x,float y,float z) {
        Object3D o=requireObject(objectId);o.transform.rotationX+=finite(x);o.transform.rotationY+=finite(y);o.transform.rotationZ+=finite(z);revision++;
    }
    public void scale(String objectId,float x,float y,float z) {
        Object3D o=requireObject(objectId);o.transform.scaleX=safeScale(o.transform.scaleX*x);o.transform.scaleY=safeScale(o.transform.scaleY*y);o.transform.scaleZ=safeScale(o.transform.scaleZ*z);revision++;
    }

    /** Set or clear a parent while preserving the complete world-space TRS transform. */
    public void setParent(String childId,String parentId) {
        Object3D child=requireObject(childId);
        if(parentId!=null){
            requireObject(parentId);
            if(childId.equals(parentId)||isDescendant(parentId,childId))
                throw new IllegalArgumentException("Parenting would create a hierarchy cycle");
        }
        double[] world=worldMatrix(childId);
        child.parentId=parentId;
        double[] local=parentId==null?world:multiply(invertAffine(worldMatrix(parentId)),world);
        decompose(local,child.transform);
        revision++;
    }

    public float[] worldLocation(String objectId) {
        double[] m=worldMatrix(objectId);
        return new float[]{(float)m[3],(float)m[7],(float)m[11]};
    }

    /** Transform a mesh-local point through its object and every parent transform. */
    public float[] transformPoint(String objectId,float x,float y,float z) {
        double[] m=worldMatrix(objectId);
        return new float[]{(float)(m[0]*x+m[1]*y+m[2]*z+m[3]),
                (float)(m[4]*x+m[5]*y+m[6]*z+m[7]),
                (float)(m[8]*x+m[9]*y+m[10]*z+m[11])};
    }

    private double[] worldMatrix(String objectId) {
        Object3D o=requireObject(objectId);
        double[] local=localMatrix(o.transform);
        if(o.parentId==null)return local;
        return multiply(worldMatrix(o.parentId),local);
    }

    private static double[] localMatrix(Transform t) {
        double rx=Math.toRadians(t.rotationX),ry=Math.toRadians(t.rotationY),rz=Math.toRadians(t.rotationZ);
        double sx=Math.sin(rx),cx=Math.cos(rx),sy=Math.sin(ry),cy=Math.cos(ry),sz=Math.sin(rz),cz=Math.cos(rz);
        double[] m=new double[16];
        m[0]=cz*cy*t.scaleX;m[1]=(cz*sy*sx-sz*cx)*t.scaleY;m[2]=(cz*sy*cx+sz*sx)*t.scaleZ;m[3]=t.x;
        m[4]=sz*cy*t.scaleX;m[5]=(sz*sy*sx+cz*cx)*t.scaleY;m[6]=(sz*sy*cx-cz*sx)*t.scaleZ;m[7]=t.y;
        m[8]=-sy*t.scaleX;m[9]=cy*sx*t.scaleY;m[10]=cy*cx*t.scaleZ;m[11]=t.z;
        m[12]=0;m[13]=0;m[14]=0;m[15]=1;return m;
    }

    private static double[] multiply(double[] a,double[] b) {
        double[] out=new double[16];
        for(int r=0;r<4;r++)for(int c=0;c<4;c++)
            for(int k=0;k<4;k++)out[r*4+c]+=a[r*4+k]*b[k*4+c];
        return out;
    }

    private static double[] invertAffine(double[] m) {
        double a=m[0],b=m[1],c=m[2],d=m[4],e=m[5],f=m[6],g=m[8],h=m[9],i=m[10];
        double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        if(Math.abs(det)<1e-12)throw new IllegalArgumentException("Parent transform is not invertible");
        double inv=1.0/det;double[] out=new double[16];
        out[0]=(e*i-f*h)*inv;out[1]=(c*h-b*i)*inv;out[2]=(b*f-c*e)*inv;
        out[4]=(f*g-d*i)*inv;out[5]=(a*i-c*g)*inv;out[6]=(c*d-a*f)*inv;
        out[8]=(d*h-e*g)*inv;out[9]=(b*g-a*h)*inv;out[10]=(a*e-b*d)*inv;
        out[3]=-(out[0]*m[3]+out[1]*m[7]+out[2]*m[11]);
        out[7]=-(out[4]*m[3]+out[5]*m[7]+out[6]*m[11]);
        out[11]=-(out[8]*m[3]+out[9]*m[7]+out[10]*m[11]);out[15]=1;return out;
    }

    private static void decompose(double[] m,Transform t) {
        double sx=Math.sqrt(m[0]*m[0]+m[4]*m[4]+m[8]*m[8]);
        double sy=Math.sqrt(m[1]*m[1]+m[5]*m[5]+m[9]*m[9]);
        double sz=Math.sqrt(m[2]*m[2]+m[6]*m[6]+m[10]*m[10]);
        if(sx<1e-8||sy<1e-8||sz<1e-8)throw new IllegalArgumentException("Transform cannot be decomposed");
        double r00=m[0]/sx,r10=m[4]/sx,r20=m[8]/sx;
        double r21=m[9]/sy,r22=m[10]/sz,r01=m[1]/sy,r11=m[5]/sy;
        double ry=Math.asin(Math.max(-1,Math.min(1,-r20)));
        double cy=Math.cos(ry),rx,rz;
        if(Math.abs(cy)>1e-7){rx=Math.atan2(r21,r22);rz=Math.atan2(r10,r00);}
        else {rx=Math.atan2(-m[6]/sz,r11);rz=0;}
        t.x=(float)m[3];t.y=(float)m[7];t.z=(float)m[11];
        t.rotationX=(float)Math.toDegrees(rx);t.rotationY=(float)Math.toDegrees(ry);t.rotationZ=(float)Math.toDegrees(rz);
        t.scaleX=(float)sx;t.scaleY=(float)sy;t.scaleZ=(float)sz;
    }

    private boolean isDescendant(String candidate,String ancestor) {
        String parent=objects.get(candidate).parentId;
        int guard=0;
        while(parent!=null&&guard++<objects.size()){
            if(parent.equals(ancestor))return true;
            Object3D p=objects.get(parent);if(p==null)break;parent=p.parentId;
        }
        return false;
    }

    public void setVisibility(String objectId,boolean visible) {requireObject(objectId).visible=visible;revision++;}
    public void setSelectable(String objectId,boolean selectable) {requireObject(objectId).selectable=selectable;revision++;}
    public void setRenderVisibility(String objectId,boolean visible) {requireObject(objectId).renderVisible=visible;revision++;}

    public Collection3D createCollection(String name) {
        String clean=name==null?"":name.trim();if(clean.isEmpty())clean="Collection";
        Collection3D c=new Collection3D(UUID.randomUUID().toString(),uniqueCollectionName(clean));
        collections.put(c.id,c);revision++;return c;
    }
    public void renameCollection(String collectionId,String name) {
        Collection3D c=requireCollection(collectionId);String clean=name==null?"":name.trim();
        if(clean.isEmpty())throw new IllegalArgumentException("Collection name cannot be empty");
        c.name=uniqueCollectionName(clean,collectionId);revision++;
    }
    public void setCollectionVisibility(String collectionId,boolean visible) {requireCollection(collectionId).visible=visible;revision++;}
    public void moveToCollection(String objectId,String collectionId) {
        Object3D o=requireObject(objectId);requireCollection(collectionId);
        if(!o.collectionIds.contains(collectionId))o.collectionIds.add(collectionId);
        revision++;
    }
    public void removeFromCollection(String objectId,String collectionId) {
        Object3D o=requireObject(objectId);
        if(o.collectionIds.size()>1)o.collectionIds.remove(collectionId);
        revision++;
    }
    public boolean isVisibleInViewport(Object3D o) {
        if(!o.visible)return false;
        for(String id:o.collectionIds){Collection3D c=collections.get(id);if(c!=null&&!c.visible)return false;}
        String parent=o.parentId;int guard=0;
        while(parent!=null&&guard++<objects.size()){
            Object3D p=objects.get(parent);if(p==null||!p.visible)return false;parent=p.parentId;
        }
        return true;
    }

    /** Bake local translation into mesh coordinates without changing the visible object. */
    public void applyLocation(String objectId) {
        Object3D o=requireObject(objectId);float x=o.transform.x,y=o.transform.y,z=o.transform.z;
        float[][] vertices=o.mesh.copyVertices();
        for(float[] p:vertices){p[0]+=x;p[1]+=y;p[2]+=z;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x=o.transform.y=o.transform.z=0f;revision++;
    }

    /** Move the origin to the local mesh bounds center while preserving world geometry. */
    public void setOriginToGeometry(String objectId) {
        Object3D o=requireObject(objectId);float[][] vertices=o.mesh.copyVertices();
        if(vertices.length==0)return;
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(float[] p:vertices){minX=Math.min(minX,p[0]);minY=Math.min(minY,p[1]);minZ=Math.min(minZ,p[2]);
            maxX=Math.max(maxX,p[0]);maxY=Math.max(maxY,p[1]);maxZ=Math.max(maxZ,p[2]);}
        float cx=(minX+maxX)*.5f,cy=(minY+maxY)*.5f,cz=(minZ+maxZ)*.5f;
        float[] offset=transformVector(o.transform,cx,cy,cz);
        for(float[] p:vertices){p[0]-=cx;p[1]-=cy;p[2]-=cz;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x+=offset[0];o.transform.y+=offset[1];o.transform.z+=offset[2];revision++;
    }

    /** Place the origin at the world-space 3D cursor (the world origin in this editor). */
    public void setOriginToCursor(String objectId) {
        Object3D o=requireObject(objectId);double[] inverse=invertAffine(worldMatrix(objectId));
        float cx=(float)inverse[3],cy=(float)inverse[7],cz=(float)inverse[11];
        float[][] vertices=o.mesh.copyVertices();
        float[] offset=transformVector(o.transform,cx,cy,cz);
        for(float[] p:vertices){p[0]-=cx;p[1]-=cy;p[2]-=cz;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x+=offset[0];o.transform.y+=offset[1];o.transform.z+=offset[2];revision++;
    }

    public void clearLocation(String objectId) {Object3D o=requireObject(objectId);o.transform.x=o.transform.y=o.transform.z=0f;revision++;}
    public void clearRotation(String objectId) {Object3D o=requireObject(objectId);o.transform.rotationX=o.transform.rotationY=o.transform.rotationZ=0f;revision++;}
    public void clearScale(String objectId) {Object3D o=requireObject(objectId);o.transform.scaleX=o.transform.scaleY=o.transform.scaleZ=1f;revision++;}

    private static float[] transformVector(Transform t,float x,float y,float z) {
        x*=t.scaleX;y*=t.scaleY;z*=t.scaleZ;
        double rx=Math.toRadians(t.rotationX),ry=Math.toRadians(t.rotationY),rz=Math.toRadians(t.rotationZ);
        float y1=(float)(y*Math.cos(rx)-z*Math.sin(rx)),z1=(float)(y*Math.sin(rx)+z*Math.cos(rx));
        float x2=(float)(x*Math.cos(ry)+z1*Math.sin(ry)),z2=(float)(-x*Math.sin(ry)+z1*Math.cos(ry));
        return new float[]{(float)(x2*Math.cos(rz)-y1*Math.sin(rz)),
                (float)(x2*Math.sin(rz)+y1*Math.cos(rz)),z2};
    }

    /** Applies local scale and Euler rotation to mesh vertices, then resets those channels. */
    public void applyRotationAndScale(String objectId) {
        Object3D o=requireObject(objectId);
        float rx=(float)Math.toRadians(o.transform.rotationX),ry=(float)Math.toRadians(o.transform.rotationY),rz=(float)Math.toRadians(o.transform.rotationZ);
        float[][] vertices=o.mesh.copyVertices();
        for(float[] p:vertices){
            p[0]*=o.transform.scaleX;p[1]*=o.transform.scaleY;p[2]*=o.transform.scaleZ;
            float y=(float)(p[1]*Math.cos(rx)-p[2]*Math.sin(rx)),z=(float)(p[1]*Math.sin(rx)+p[2]*Math.cos(rx));
            float x=(float)(p[0]*Math.cos(ry)+z*Math.sin(ry)),z2=(float)(-p[0]*Math.sin(ry)+z*Math.cos(ry));
            p[0]=(float)(x*Math.cos(rz)-y*Math.sin(rz));p[1]=(float)(x*Math.sin(rz)+y*Math.cos(rz));p[2]=z2;
        }
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.rotationX=o.transform.rotationY=o.transform.rotationZ=0f;
        o.transform.scaleX=o.transform.scaleY=o.transform.scaleZ=1f;
        revision++;
    }

    public JSONObject exportState() {
        JSONObject root=new JSONObject();
        try{
            root.put("version",1);root.put("activeObjectId",activeObjectId);
            JSONArray objectArray=new JSONArray();
            for(Object3D o:objects.values()){
                JSONObject item=new JSONObject();item.put("id",o.id);item.put("name",o.name);item.put("type",o.type);
                item.put("transform",o.transform.toJson());item.put("mesh",o.mesh.exportState());
                item.put("parentId",o.parentId==null?JSONObject.NULL:o.parentId);
                item.put("visible",o.visible);item.put("selectable",o.selectable);item.put("renderVisible",o.renderVisible);
                item.put("selected",o.selected);JSONArray members=new JSONArray();for(String id:o.collectionIds)members.put(id);item.put("collections",members);
                objectArray.put(item);
            }
            root.put("objects",objectArray);
            JSONArray collectionArray=new JSONArray();
            for(Collection3D c:collections.values()){
                JSONObject item=new JSONObject();item.put("id",c.id);item.put("name",c.name);item.put("visible",c.visible);item.put("renderVisible",c.renderVisible);collectionArray.put(item);
            }
            root.put("collections",collectionArray);
        }catch(JSONException e){throw new IllegalStateException("Could not serialize object scene",e);}
        return root;
    }

    public boolean importState(JSONObject root) {
        if(root==null)return false;
        try{
            LinkedHashMap<String,Collection3D> newCollections=new LinkedHashMap<>();
            JSONArray collectionArray=root.optJSONArray("collections");
            if(collectionArray!=null)for(int i=0;i<collectionArray.length();i++){
                JSONObject item=collectionArray.getJSONObject(i);String id=item.getString("id");
                Collection3D c=new Collection3D(id,item.optString("name","Collection"));
                c.visible=item.optBoolean("visible",true);c.renderVisible=item.optBoolean("renderVisible",true);newCollections.put(id,c);
            }
            if(newCollections.isEmpty()){Collection3D c=new Collection3D("collection-scene","Collection");newCollections.put(c.id,c);}
            LinkedHashMap<String,Object3D> newObjects=new LinkedHashMap<>();
            JSONArray objectArray=root.getJSONArray("objects");
            for(int i=0;i<objectArray.length();i++){
                JSONObject item=objectArray.getJSONObject(i);String id=item.getString("id");
                Meshy3DCore mesh=new Meshy3DCore();if(!mesh.importState(item.getJSONObject("mesh")))return false;
                Object3D o=new Object3D(id,item.optString("name","Object"),item.optString("type","MESH"),mesh);
                Transform t=Transform.fromJson(item.optJSONObject("transform"));
                o.transform.x=t.x;o.transform.y=t.y;o.transform.z=t.z;o.transform.rotationX=t.rotationX;o.transform.rotationY=t.rotationY;o.transform.rotationZ=t.rotationZ;o.transform.scaleX=t.scaleX;o.transform.scaleY=t.scaleY;o.transform.scaleZ=t.scaleZ;
                Object parent=item.opt("parentId");o.parentId=parent==null||parent==JSONObject.NULL?null:String.valueOf(parent);
                o.visible=item.optBoolean("visible",true);o.selectable=item.optBoolean("selectable",true);o.renderVisible=item.optBoolean("renderVisible",true);o.selected=item.optBoolean("selected",false);
                JSONArray memberships=item.optJSONArray("collections");
                if(memberships!=null)for(int j=0;j<memberships.length();j++){String cid=memberships.getString(j);if(newCollections.containsKey(cid))o.collectionIds.add(cid);}
                if(o.collectionIds.isEmpty())o.collectionIds.add(newCollections.keySet().iterator().next());
                newObjects.put(id,o);
            }
            for(Object3D o:newObjects.values())if(o.parentId!=null&&!newObjects.containsKey(o.parentId))return false;
            // Reject cyclic or self-referential hierarchies before replacing the current scene.
            for(Object3D o:newObjects.values()){
                String p=o.parentId;int guard=0;
                while(p!=null&&guard++<=newObjects.size()){
                    if(p.equals(o.id))return false;
                    Object3D parent=newObjects.get(p);if(parent==null)return false;p=parent.parentId;
                }
                if(guard>newObjects.size())return false;
            }
            objects.clear();objects.putAll(newObjects);collections.clear();collections.putAll(newCollections);
            activeObjectId=root.optString("activeObjectId",null);
            if(!objects.containsKey(activeObjectId))activeObjectId=null;
            for(Object3D o:objects.values()){o.active=o.id.equals(activeObjectId);if(o.active)o.selected=true;}
            revision++;return true;
        }catch(Exception e){return false;}
    }

    private Object3D requireObject(String id){Object3D o=objects.get(id);if(o==null)throw new IllegalArgumentException("Unknown object: "+id);return o;}
    private Collection3D requireCollection(String id){Collection3D c=collections.get(id);if(c==null)throw new IllegalArgumentException("Unknown collection: "+id);return c;}
    private String uniqueObjectName(String base){return uniqueObjectName(base,null);}
    private String uniqueObjectName(String base,String exceptId){
        String candidate=base;int suffix=1;
        while(hasObjectName(candidate,exceptId))candidate=base+"."+String.format(java.util.Locale.US,"%03d",suffix++);
        return candidate;
    }
    private boolean hasObjectName(String name,String exceptId){for(Object3D o:objects.values())if(!o.id.equals(exceptId)&&o.name.equals(name))return true;return false;}
    private String uniqueCollectionName(String base){return uniqueCollectionName(base,null);}
    private String uniqueCollectionName(String base,String exceptId){
        String candidate=base;int suffix=1;
        while(true){boolean found=false;for(Collection3D c:collections.values())if(!c.id.equals(exceptId)&&c.name.equals(candidate)){found=true;break;}if(!found)return candidate;candidate=base+"."+String.format(java.util.Locale.US,"%03d",suffix++);}
    }
    private static JSONArray vector(float x,float y,float z){JSONArray a=new JSONArray();a.put(x);a.put(y);a.put(z);return a;}
    private interface VectorConsumer{void accept(float[] v);}
    private static void readVector(JSONArray a,VectorConsumer consumer){if(a==null||a.length()!=3)return;consumer.accept(new float[]{(float)a.optDouble(0,0),(float)a.optDouble(1,0),(float)a.optDouble(2,0)});}
    private static float finite(float v){return Float.isNaN(v)||Float.isInfinite(v)?0f:Math.max(-100000f,Math.min(100000f,v));}
    private static float safeScale(float v){if(Float.isNaN(v)||Float.isInfinite(v))return 1f;if(Math.abs(v)<.0001f)return v<0?-.0001f:.0001f;return Math.max(-10000f,Math.min(10000f,v));}
}
