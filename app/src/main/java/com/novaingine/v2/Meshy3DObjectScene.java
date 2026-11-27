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
        public final ArrayList<Constraint3D> constraints = new ArrayList<>();
        public final ArrayList<Modifier3D> modifiers = new ArrayList<>();
        public final LinkedHashMap<String,CustomProperty3D> customProperties = new LinkedHashMap<>();
        public boolean selected;
        public boolean active;
        public boolean visible = true;
        public boolean selectable = true;
        public boolean renderVisible = true;
        public float cameraLens = 50f;
        public float cameraClipStart = 0.1f;
        public float cameraClipEnd = 1000f;
        public String lightType = "POINT";
        public float lightPower = 1000f;
        public float lightAngle = 45f;
        public int lightColor = 0xFFFFFFFF;
        public float displaySize = 1f;
        public String viewportDisplayType = "SOLID";
        public int viewportDisplayColor = 0xFFFFFFFF;

        private Object3D(String id,String name,String type,Meshy3DCore mesh) {
            this.id=id;this.name=name;this.type=type;this.mesh=mesh;
        }
    }

    public static final class Constraint3D {
        public final String id;
        public String type;
        public String targetId;
        public float influence=1f;
        public boolean enabled=true;
        private Constraint3D(String id,String type,String targetId,float influence,boolean enabled){
            this.id=id;this.type=type;this.targetId=targetId;this.influence=influence;this.enabled=enabled;
        }
    }

    public static final class Modifier3D {
        public final String id;
        public String type;
        public boolean enabled=true;
        public int axis=0;
        public int count=2;
        public int levels=1;
        public float value=0.1f;
        public float offsetX=2f,offsetY,offsetZ;
        private Modifier3D(String id,String type){
            this.id=id;this.type=type;
            if("BEVEL".equals(type))value=.1f;
            else if("SOLIDIFY".equals(type))value=.1f;
            else if("WIREFRAME".equals(type))value=.05f;
            else if("SUBDIVIDE".equals(type)||"CATMULL_CLARK".equals(type))levels=1;
            else if("PLANAR_DECIMATE".equals(type))value=15f;
            else if("SMOOTH".equals(type)){value=.25f;levels=2;}
        }
    }

    public static final class CustomProperty3D {
        public final String key;
        public String type;
        public String value;
        private CustomProperty3D(String key,String type,String value){
            this.key=key;this.type=type;this.value=value;
        }
    }

    public static final class Collection3D {
        public final String id;
        public String name;
        public String parentId;
        public boolean visible=true;
        public boolean renderVisible=true;
        private Collection3D(String id,String name){this.id=id;this.name=name;}
    }

    private final LinkedHashMap<String,Object3D> objects=new LinkedHashMap<>();
    private final LinkedHashMap<String,Collection3D> collections=new LinkedHashMap<>();
    private String activeObjectId;
    private String activeCameraId;
    private float cursorX,cursorY,cursorZ;
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
    public String getActiveCameraId(){return activeCameraId;}
    public Object3D getActiveCamera(){return activeCameraId==null?null:objects.get(activeCameraId);}
    public float[] getCursorLocation(){return new float[]{cursorX,cursorY,cursorZ};}
    public void setCursorLocation(float x,float y,float z){cursorX=finite(x);cursorY=finite(y);cursorZ=finite(z);revision++;}
    public void setWorldLocation(String objectId,float x,float y,float z){
        Object3D object=requireObject(objectId);double lx=x,ly=y,lz=z;
        if(object.parentId!=null){
            double[] inv=invertAffine(worldMatrix(object.parentId));
            lx=inv[0]*x+inv[1]*y+inv[2]*z+inv[3];
            ly=inv[4]*x+inv[5]*y+inv[6]*z+inv[7];
            lz=inv[8]*x+inv[9]*y+inv[10]*z+inv[11];
        }
        object.transform.x=finite((float)lx);object.transform.y=finite((float)ly);object.transform.z=finite((float)lz);revision++;
    }
    public int snapSelectedToGrid(float step){
        if(!Float.isFinite(step)||step<=0f||step>10000f)throw new IllegalArgumentException("Grid step must be positive");
        ArrayList<Object3D> targets=new ArrayList<>(getSelectedObjects());
        if(targets.isEmpty()&&activeObjectId!=null)targets.add(requireObject(activeObjectId));
        for(Object3D object:targets){float[] p=worldLocation(object.id);
            setWorldLocation(object.id,Math.round(p[0]/step)*step,Math.round(p[1]/step)*step,Math.round(p[2]/step)*step);}
        return targets.size();
    }
    public int snapSelectedToCursor(){
        ArrayList<Object3D> targets=new ArrayList<>(getSelectedObjects());
        if(targets.isEmpty()&&activeObjectId!=null)targets.add(requireObject(activeObjectId));
        for(Object3D object:targets)setWorldLocation(object.id,cursorX,cursorY,cursorZ);
        return targets.size();
    }

    private static long edgeKey(int a,int b){
        int min=Math.min(a,b),max=Math.max(a,b);
        return (((long)min)<<32)|(max&0xffffffffL);
    }

    public int snapSelectedToActiveGeometry(int mode){
        if(mode<0||mode>2)throw new IllegalArgumentException("Snap mode must be Vertex, Edge or Face.");
        Object3D target=getActiveObject();
        if(target==null||!"MESH".equals(target.type)||target.mesh==null)
            throw new IllegalStateException("Make a mesh object active to use it as the snap target.");
        Meshy3DCore targetMesh=target.modifiers.isEmpty()?target.mesh:
                Meshy3DModifierEvaluator.evaluate(target);
        float[][] vertices=targetMesh.copyVertices();
        int[][] faces=targetMesh.copyFaces();
        if(vertices.length==0||faces.length==0)
            throw new IllegalStateException("The active snap target has no surface geometry.");

        ArrayList<Object3D> sources=new ArrayList<>(getSelectedObjects());
        sources.removeIf(object->object.id.equals(target.id));
        if(sources.isEmpty())
            throw new IllegalStateException("Select at least one other object and keep the snap target active.");
        for(Object3D source:sources){
            float[] origin=worldLocation(source.id);
            float[] nearest=null;float nearestDistance=Float.MAX_VALUE;
            if(mode==0){
                for(float[] vertex:vertices){
                    float[] world=transformPoint(target.id,vertex[0],vertex[1],vertex[2]);
                    float distance=distanceSquared(origin,world);
                    if(distance<nearestDistance){nearestDistance=distance;nearest=world;}
                }
            }else if(mode==1){
                java.util.HashSet<Long> checkedEdges=new java.util.HashSet<>();
                for(int[] face:faces)for(int corner=0;corner<face.length;corner++){
                    int a=face[corner],b=face[(corner+1)%face.length];
                    long key=(((long)Math.min(a,b))<<32)|(Math.max(a,b)&0xffffffffL);if(!checkedEdges.add(key))continue;
                    float[] start=transformPoint(target.id,vertices[a][0],vertices[a][1],vertices[a][2]);
                    float[] end=transformPoint(target.id,vertices[b][0],vertices[b][1],vertices[b][2]);
                    float[] closest=closestPointOnSegment(origin,start,end);
                    float distance=distanceSquared(origin,closest);
                    if(distance<nearestDistance){nearestDistance=distance;nearest=closest;}
                }
            }else{
                for(int[] face:faces){
                    if(face.length<3)continue;
                    float[] a=transformPoint(target.id,vertices[face[0]][0],vertices[face[0]][1],vertices[face[0]][2]);
                    for(int corner=1;corner<face.length-1;corner++){
                        float[] b=transformPoint(target.id,vertices[face[corner]][0],vertices[face[corner]][1],vertices[face[corner]][2]);
                        float[] c=transformPoint(target.id,vertices[face[corner+1]][0],vertices[face[corner+1]][1],vertices[face[corner+1]][2]);
                        float[] closest=closestPointOnTriangle(origin,a,b,c);
                        float distance=distanceSquared(origin,closest);
                        if(distance<nearestDistance){nearestDistance=distance;nearest=closest;}
                    }
                }
            }
            if(nearest==null)throw new IllegalStateException("The active snap target has no usable vertices, edges or faces.");
            setWorldLocation(source.id,nearest[0],nearest[1],nearest[2]);
        }
        return sources.size();
    }

    private static float distanceSquared(float[] a,float[] b){
        float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];
        return x*x+y*y+z*z;
    }

    private static float[] closestPointOnSegment(float[] point,float[] a,float[] b){
        float abx=b[0]-a[0],aby=b[1]-a[1],abz=b[2]-a[2];
        float denominator=abx*abx+aby*aby+abz*abz;
        float t=denominator<1e-12f?0f:((point[0]-a[0])*abx+(point[1]-a[1])*aby
                +(point[2]-a[2])*abz)/denominator;
        t=Math.max(0f,Math.min(1f,t));
        return new float[]{a[0]+abx*t,a[1]+aby*t,a[2]+abz*t};
    }

    /** Closest point on a triangle, using the Voronoi-region tests from Real-Time Collision Detection. */
    private static float[] closestPointOnTriangle(float[] p,float[] a,float[] b,float[] c){
        float abx=b[0]-a[0],aby=b[1]-a[1],abz=b[2]-a[2];
        float acx=c[0]-a[0],acy=c[1]-a[1],acz=c[2]-a[2];
        float apx=p[0]-a[0],apy=p[1]-a[1],apz=p[2]-a[2];
        float d1=abx*apx+aby*apy+abz*apz,d2=acx*apx+acy*apy+acz*apz;
        if(d1<=0f&&d2<=0f)return a.clone();
        float bpx=p[0]-b[0],bpy=p[1]-b[1],bpz=p[2]-b[2];
        float d3=abx*bpx+aby*bpy+abz*bpz,d4=acx*bpx+acy*bpy+acz*bpz;
        if(d3>=0f&&d4<=d3)return b.clone();
        float vc=d1*d4-d3*d2;
        if(vc<=0f&&d1>=0f&&d3<=0f){
            float v=d1/(d1-d3);
            return new float[]{a[0]+abx*v,a[1]+aby*v,a[2]+abz*v};
        }
        float cpx=p[0]-c[0],cpy=p[1]-c[1],cpz=p[2]-c[2];
        float d5=abx*cpx+aby*cpy+abz*cpz,d6=acx*cpx+acy*cpy+acz*cpz;
        if(d6>=0f&&d5<=d6)return c.clone();
        float vb=d5*d2-d1*d6;
        if(vb<=0f&&d2>=0f&&d6<=0f){
            float w=d2/(d2-d6);
            return new float[]{a[0]+acx*w,a[1]+acy*w,a[2]+acz*w};
        }
        float va=d3*d6-d5*d4;
        if(va<=0f&&(d4-d3)>=0f&&(d5-d6)>=0f){
            float w=(d4-d3)/((d4-d3)+(d5-d6));
            return new float[]{b[0]+(c[0]-b[0])*w,b[1]+(c[1]-b[1])*w,b[2]+(c[2]-b[2])*w};
        }
        float denominator=1f/(va+vb+vc),v=vb*denominator,w=vc*denominator;
        return new float[]{a[0]+abx*v+acx*w,a[1]+aby*v+acy*w,a[2]+abz*v+acz*w};
    }
    public void setActiveCamera(String objectId){
        Object3D camera=requireObject(objectId);
        if(!"CAMERA".equals(camera.type))throw new IllegalArgumentException("Active camera must be a Camera object");
        activeCameraId=objectId;revision++;
    }
    public void setCameraSettings(String objectId,float lens,float clipStart,float clipEnd){
        Object3D camera=requireObject(objectId);
        if(!"CAMERA".equals(camera.type))throw new IllegalArgumentException("Select a Camera object");
        if(!Float.isFinite(lens)||!Float.isFinite(clipStart)||!Float.isFinite(clipEnd)
                ||lens<1f||lens>500f||clipStart<=0f||clipEnd<=clipStart)
            throw new IllegalArgumentException("Camera lens or clipping range is invalid");
        camera.cameraLens=lens;camera.cameraClipStart=clipStart;camera.cameraClipEnd=clipEnd;revision++;
    }
    public void setLightSettings(String objectId,String type,float power,float angle,int color){
        Object3D light=requireObject(objectId);
        if(!"LIGHT".equals(light.type))throw new IllegalArgumentException("Select a Light object");
        String clean=type==null?"POINT":type.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"POINT".equals(clean)&&!"SUN".equals(clean)&&!"SPOT".equals(clean)&&!"AREA".equals(clean))
            throw new IllegalArgumentException("Unsupported light type");
        if(!Float.isFinite(power)||power<0f||power>10000000f
                ||!Float.isFinite(angle)||angle<0f||angle>180f)
            throw new IllegalArgumentException("Light power or angle is invalid");
        light.lightType=clean;light.lightPower=power;light.lightAngle=angle;light.lightColor=color;revision++;
    }

    public List<Constraint3D> getConstraints(String objectId){
        return Collections.unmodifiableList(new ArrayList<>(requireObject(objectId).constraints));
    }

    public Constraint3D addConstraint(String objectId,String type,String targetId){
        Object3D owner=requireObject(objectId);
        String clean=cleanConstraintType(type);
        Object3D target=requireObject(targetId);
        if(objectId.equals(targetId)||dependencyReaches(objects,targetId,objectId,new java.util.HashSet<>()))
            throw new IllegalArgumentException("That target would create a transform dependency cycle.");
        Constraint3D constraint=new Constraint3D(UUID.randomUUID().toString(),clean,target.id,1f,true);
        owner.constraints.add(constraint);revision++;return constraint;
    }

    public void setConstraintType(String objectId,String constraintId,String type){
        Constraint3D constraint=findConstraint(requireObject(objectId),constraintId);
        constraint.type=cleanConstraintType(type);revision++;
    }

    public void setConstraintTarget(String objectId,String constraintId,String targetId){
        Object3D owner=requireObject(objectId);Object3D target=requireObject(targetId);
        Constraint3D constraint=findConstraint(owner,constraintId);
        if(objectId.equals(targetId)||dependencyReaches(objects,targetId,objectId,new java.util.HashSet<>()))
            throw new IllegalArgumentException("That target would create a transform dependency cycle.");
        constraint.targetId=target.id;revision++;
    }

    public List<CustomProperty3D> getCustomProperties(String objectId){
        return Collections.unmodifiableList(new ArrayList<>(requireObject(objectId).customProperties.values()));
    }

    public CustomProperty3D addCustomProperty(String objectId,String key,String type,String value){
        Object3D object=requireObject(objectId);
        String cleanKey=cleanCustomPropertyKey(key);
        if(object.customProperties.containsKey(cleanKey))
            throw new IllegalArgumentException("A custom property with that name already exists.");
        String cleanType=cleanCustomPropertyType(type);
        String cleanValue=cleanCustomPropertyValue(cleanType,value);
        CustomProperty3D property=new CustomProperty3D(cleanKey,cleanType,cleanValue);
        object.customProperties.put(cleanKey,property);revision++;return property;
    }

    public void setCustomProperty(String objectId,String oldKey,String newKey,String type,String value){
        Object3D object=requireObject(objectId);
        CustomProperty3D existing=object.customProperties.get(oldKey);
        if(existing==null)throw new IllegalArgumentException("Custom property no longer exists.");
        String cleanKey=cleanCustomPropertyKey(newKey);
        if(!oldKey.equals(cleanKey)&&object.customProperties.containsKey(cleanKey))
            throw new IllegalArgumentException("A custom property with that name already exists.");
        String cleanType=cleanCustomPropertyType(type);
        String cleanValue=cleanCustomPropertyValue(cleanType,value);
        object.customProperties.remove(oldKey);
        existing.type=cleanType;existing.value=cleanValue;
        object.customProperties.put(cleanKey,new CustomProperty3D(cleanKey,cleanType,cleanValue));
        revision++;
    }

    public void removeCustomProperty(String objectId,String key){
        Object3D object=requireObject(objectId);
        if(object.customProperties.remove(key)==null)
            throw new IllegalArgumentException("Custom property no longer exists.");
        revision++;
    }

    private static String cleanCustomPropertyKey(String key){
        String clean=key==null?"":key.trim();
        if(clean.isEmpty()||clean.length()>64)
            throw new IllegalArgumentException("Custom property names must contain 1 to 64 characters.");
        return clean;
    }

    private static String cleanCustomPropertyType(String type){
        String clean=type==null?"":type.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"STRING".equals(clean)&&!"NUMBER".equals(clean)&&!"BOOLEAN".equals(clean))
            throw new IllegalArgumentException("Custom property type must be String, Number or Boolean.");
        return clean;
    }

    private static String cleanCustomPropertyValue(String type,String value){
        String clean=value==null?"":value.trim();
        if("NUMBER".equals(type)){
            try{
                double number=Double.parseDouble(clean);
                if(!Double.isFinite(number))throw new NumberFormatException();
                return Double.toString(number);
            }catch(NumberFormatException error){
                throw new IllegalArgumentException("Enter a finite numeric custom property value.");
            }
        }
        if("BOOLEAN".equals(type)){
            if(!"true".equalsIgnoreCase(clean)&&!"false".equalsIgnoreCase(clean))
                throw new IllegalArgumentException("Boolean values must be true or false.");
            return clean.toLowerCase(java.util.Locale.ROOT);
        }
        if(clean.length()>4096)throw new IllegalArgumentException("Text custom property values are limited to 4096 characters.");
        return clean;
    }

    public List<Modifier3D> getModifiers(String objectId){
        return Collections.unmodifiableList(new ArrayList<>(requireObject(objectId).modifiers));
    }

    public Modifier3D addModifier(String objectId,String type){
        Object3D object=requireObject(objectId);
        if(!"MESH".equals(object.type)||object.mesh==null)
            throw new IllegalArgumentException("Modifiers can only be added to mesh objects.");
        String clean=cleanModifierType(type);
        if(("BEVEL".equals(clean)||"SOLIDIFY".equals(clean)||"CATMULL_CLARK".equals(clean)
                ||"TRIANGULATE".equals(clean)||"PLANAR_DECIMATE".equals(clean)||"WIREFRAME".equals(clean))
                &&(object.mesh.hasTexture()||object.mesh.copyFaceUvs()!=null))
            throw new IllegalArgumentException(clean+" currently requires a mesh without an image texture or UV map so the existing UV data is not corrupted.");
        Modifier3D modifier=new Modifier3D(UUID.randomUUID().toString(),clean);
        object.modifiers.add(modifier);revision++;return modifier;
    }

    public void clearModifiers(String objectId){
        Object3D object=requireObject(objectId);
        if(!object.modifiers.isEmpty()){object.modifiers.clear();revision++;}
    }

    public void removeModifier(String objectId,String modifierId){
        Object3D object=requireObject(objectId);
        if(!object.modifiers.removeIf(m->m.id.equals(modifierId)))
            throw new IllegalArgumentException("Modifier no longer exists.");
        revision++;
    }

    public void removeModifiersThrough(String objectId,int index){
        Object3D object=requireObject(objectId);
        if(index<0||index>=object.modifiers.size())
            throw new IllegalArgumentException("Modifier index is outside the stack.");
        object.modifiers.subList(0,index+1).clear();revision++;
    }

    public void moveModifier(String objectId,String modifierId,int direction){
        Object3D object=requireObject(objectId);
        int index=-1;for(int i=0;i<object.modifiers.size();i++)
            if(object.modifiers.get(i).id.equals(modifierId)){index=i;break;}
        if(index<0)throw new IllegalArgumentException("Modifier no longer exists.");
        int target=Math.max(0,Math.min(object.modifiers.size()-1,index+Math.max(-1,Math.min(1,direction))));
        if(target==index)return;
        Modifier3D modifier=object.modifiers.remove(index);
        object.modifiers.add(target,modifier);revision++;
    }

    public void setModifierEnabled(String objectId,String modifierId,boolean enabled){
        findModifier(requireObject(objectId),modifierId).enabled=enabled;revision++;
    }

    public void setModifierParameters(String objectId,String modifierId,int axis,int count,
            int levels,float value,float offsetX,float offsetY,float offsetZ){
        Modifier3D modifier=findModifier(requireObject(objectId),modifierId);
        if(axis<0||axis>2||count<1||count>20||levels<1||levels>3
                ||!Float.isFinite(value)||Math.abs(value)>100f
                ||!Float.isFinite(offsetX)||!Float.isFinite(offsetY)||!Float.isFinite(offsetZ)
                ||Math.abs(offsetX)>1000f||Math.abs(offsetY)>1000f||Math.abs(offsetZ)>1000f)
            throw new IllegalArgumentException("Modifier parameters are outside their supported range.");
        if("BEVEL".equals(modifier.type)&&(value<.001f||value>.49f))
            throw new IllegalArgumentException("Bevel width must be between 0.001 and 0.49.");
        if("SOLIDIFY".equals(modifier.type)&&Math.abs(value)<.0001f)
            throw new IllegalArgumentException("Solidify thickness must be non-zero.");
        if("WIREFRAME".equals(modifier.type)&&(value<.001f||value>.5f))
            throw new IllegalArgumentException("Wireframe thickness must be between 0.001 and 0.5.");
        modifier.axis=axis;modifier.count=count;modifier.levels=levels;modifier.value=value;
        modifier.offsetX=offsetX;modifier.offsetY=offsetY;modifier.offsetZ=offsetZ;revision++;
    }

    private static String cleanModifierType(String type){
        String clean=type==null?"":type.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"MIRROR".equals(clean)&&!"ARRAY".equals(clean)&&!"BEVEL".equals(clean)
                &&!"SOLIDIFY".equals(clean)&&!"SUBDIVIDE".equals(clean)
                &&!"CATMULL_CLARK".equals(clean)&&!"TRIANGULATE".equals(clean)
                &&!"PLANAR_DECIMATE".equals(clean)&&!"SMOOTH".equals(clean)
                &&!"WIREFRAME".equals(clean))
            throw new IllegalArgumentException("Unsupported modifier type.");
        return clean;
    }

    private static Modifier3D findModifier(Object3D object,String id){
        for(Modifier3D modifier:object.modifiers)if(modifier.id.equals(id))return modifier;
        throw new IllegalArgumentException("Modifier no longer exists.");
    }

    public void setConstraintInfluence(String objectId,String constraintId,float influence){
        Constraint3D constraint=findConstraint(requireObject(objectId),constraintId);
        if(!Float.isFinite(influence)||influence<0f||influence>1f)
            throw new IllegalArgumentException("Constraint influence must be between 0 and 1.");
        constraint.influence=influence;revision++;
    }

    public void setConstraintEnabled(String objectId,String constraintId,boolean enabled){
        findConstraint(requireObject(objectId),constraintId).enabled=enabled;revision++;
    }

    public void removeConstraint(String objectId,String constraintId){
        Object3D owner=requireObject(objectId);
        if(!owner.constraints.removeIf(c->c.id.equals(constraintId)))
            throw new IllegalArgumentException("Constraint no longer exists.");
        revision++;
    }

    private static String cleanConstraintType(String type){
        String clean=type==null?"COPY_LOCATION":type.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"COPY_LOCATION".equals(clean)&&!"COPY_ROTATION".equals(clean)
                &&!"COPY_SCALE".equals(clean)&&!"COPY_TRANSFORMS".equals(clean))
            throw new IllegalArgumentException("Unsupported constraint type.");
        return clean;
    }

    private static Constraint3D findConstraint(Object3D object,String id){
        for(Constraint3D constraint:object.constraints)if(constraint.id.equals(id))return constraint;
        throw new IllegalArgumentException("Constraint no longer exists.");
    }

    private boolean dependencyReaches(String currentId,String destinationId,java.util.Set<String> visited){
        return dependencyReaches(objects,currentId,destinationId,visited);
    }

    private static boolean dependencyReaches(Map<String,Object3D> graph,String currentId,
            String destinationId,java.util.Set<String> visited){
        if(currentId==null)return false;
        if(currentId.equals(destinationId))return true;
        if(!visited.add(currentId))return false;
        Object3D current=graph.get(currentId);if(current==null)return false;
        if(dependencyReaches(graph,current.parentId,destinationId,visited))return true;
        for(Constraint3D constraint:current.constraints)
            if(dependencyReaches(graph,constraint.targetId,destinationId,visited))return true;
        return false;
    }

    private static double blendAngle(double from,double to,float influence){
        double delta=((to-from+540.0)%360.0)-180.0;
        return from+delta*influence;
    }

    private static double[] applyConstraint(double[] sourceMatrix,double[] targetMatrix,Constraint3D constraint){
        float weight=Math.max(0f,Math.min(1f,constraint.influence));
        if(weight<=0f)return sourceMatrix;
        // A location-only constraint must not decompose/rebuild the rotation and
        // scale basis: that would lose shear produced by rotated non-uniform parents.
        if("COPY_LOCATION".equals(constraint.type)){
            double[] result=sourceMatrix.clone();
            result[3]+=(targetMatrix[3]-result[3])*weight;
            result[7]+=(targetMatrix[7]-result[7])*weight;
            result[11]+=(targetMatrix[11]-result[11])*weight;
            return result;
        }
        Transform source=new Transform(),target=new Transform();
        decompose(sourceMatrix,source);decompose(targetMatrix,target);
        if("COPY_TRANSFORMS".equals(constraint.type)){
            source.x+=(target.x-source.x)*weight;
            source.y+=(target.y-source.y)*weight;
            source.z+=(target.z-source.z)*weight;
        }
        if("COPY_ROTATION".equals(constraint.type)||"COPY_TRANSFORMS".equals(constraint.type)){
            source.rotationX=(float)blendAngle(source.rotationX,target.rotationX,weight);
            source.rotationY=(float)blendAngle(source.rotationY,target.rotationY,weight);
            source.rotationZ=(float)blendAngle(source.rotationZ,target.rotationZ,weight);
        }
        if("COPY_SCALE".equals(constraint.type)||"COPY_TRANSFORMS".equals(constraint.type)){
            source.scaleX+=(target.scaleX-source.scaleX)*weight;
            source.scaleY+=(target.scaleY-source.scaleY)*weight;
            source.scaleZ+=(target.scaleZ-source.scaleZ)*weight;
        }
        return localMatrix(source);
    }

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

    public Object3D createTypedObject(String name,String type,Meshy3DCore viewportProxy) {
        String cleanType=type==null?"MESH":type.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"MESH".equals(cleanType)&&!"CAMERA".equals(cleanType)&&!"LIGHT".equals(cleanType)
                &&!"EMPTY".equals(cleanType))
            throw new IllegalArgumentException("Unsupported object type: "+cleanType);
        Object3D object;
        if(viewportProxy!=null)object=createMeshObject(name,viewportProxy);
        else if("MESH".equals(cleanType))throw new IllegalArgumentException("Mesh objects require mesh data");
        else {
            String id=UUID.randomUUID().toString();
            object=new Object3D(id,uniqueObjectName(name),cleanType,null);
            object.collectionIds.add(collections.keySet().iterator().next());
            objects.put(id,object);
            selectOnly(id);
        }
        object.type=cleanType;
        revision++;
        return object;
    }

    public List<Object3D> duplicateSelected(boolean linked) {
        ArrayList<Object3D> sources=new ArrayList<>(getSelectedObjects());
        if(sources.isEmpty()&&activeObjectId!=null)sources.add(requireObject(activeObjectId));
        if(sources.isEmpty())return Collections.emptyList();
        ArrayList<Object3D> copies=new ArrayList<>();
        for(Object3D source:sources)copies.add(duplicate(source.id,linked));
        replaceSelection(copies);
        return Collections.unmodifiableList(copies);
    }

    public Object3D duplicate(String objectId,boolean linked) {
        Object3D source=requireObject(objectId);
        Meshy3DCore data=null;
        if(source.mesh!=null){
            if(linked)data=source.mesh;
            else {
                data=new Meshy3DCore();
                if(!data.importState(source.mesh.exportState()))
                    throw new IllegalStateException("Could not copy mesh data");
            }
        }
        Object3D copy=data==null?createTypedObject(source.name+".copy",source.type,null)
                :createMeshObject(source.name+".copy",data);
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
        copy.parentId=source.parentId;
        copy.cameraLens=source.cameraLens;copy.cameraClipStart=source.cameraClipStart;copy.cameraClipEnd=source.cameraClipEnd;
        copy.lightType=source.lightType;copy.lightPower=source.lightPower;copy.lightAngle=source.lightAngle;
        copy.lightColor=source.lightColor;copy.displaySize=source.displaySize;
        copy.viewportDisplayType=source.viewportDisplayType;copy.viewportDisplayColor=source.viewportDisplayColor;
        for(Constraint3D constraint:source.constraints)
            copy.constraints.add(new Constraint3D(UUID.randomUUID().toString(),constraint.type,
                    constraint.targetId,constraint.influence,constraint.enabled));
        for(CustomProperty3D property:source.customProperties.values())
            copy.customProperties.put(property.key,new CustomProperty3D(property.key,property.type,property.value));
        for(Modifier3D modifier:source.modifiers){
            Modifier3D duplicated=new Modifier3D(UUID.randomUUID().toString(),modifier.type);
            duplicated.enabled=modifier.enabled;duplicated.axis=modifier.axis;duplicated.count=modifier.count;
            duplicated.levels=modifier.levels;duplicated.value=modifier.value;
            duplicated.offsetX=modifier.offsetX;duplicated.offsetY=modifier.offsetY;duplicated.offsetZ=modifier.offsetZ;
            copy.modifiers.add(duplicated);
        }
        copy.collectionIds.clear();
        copy.collectionIds.addAll(source.collectionIds);
        revision++;
        return copy;
    }

    public void delete(String objectId) {
        Object3D deleting=requireObject(objectId);
        if(objectId.equals(activeCameraId))activeCameraId=null;
        ArrayList<String> children=new ArrayList<>();
        for(Object3D o:objects.values())if(objectId.equals(o.parentId))children.add(o.id);
        // Blender-style delete must not make children jump when their parent disappears.
        for(String child:children)setParent(child,deleting.parentId);
        objects.remove(objectId);
        for(Object3D remaining:objects.values())
            remaining.constraints.removeIf(constraint->objectId.equals(constraint.targetId));
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
        if(selected){
            activeObjectId=objectId;
            for(Object3D other:objects.values())other.active=false;
            o.active=true;
        }else{
            o.active=false;
            if(objectId.equals(activeObjectId)){
                activeObjectId=null;
                for(Object3D candidate:objects.values())if(candidate.selected&&candidate.selectable)activeObjectId=candidate.id;
                for(Object3D candidate:objects.values())candidate.active=candidate.id.equals(activeObjectId);
            }
        }
        revision++;
    }

    public List<Object3D> getSelectedObjects() {
        ArrayList<Object3D> result=new ArrayList<>();
        for(Object3D o:objects.values())if(o.selected)result.add(o);
        return result;
    }

    public void selectAllObjects() {
        String last=null;
        for(Object3D o:objects.values()){o.selected=o.selectable;o.active=false;if(o.selected)last=o.id;}
        activeObjectId=last;
        if(last!=null)objects.get(last).active=true;
        revision++;
    }

    public void deselectAllObjects() {
        for(Object3D o:objects.values()){o.selected=false;o.active=false;}
        activeObjectId=null;revision++;
    }

    public void invertObjectSelection() {
        String last=null;
        for(Object3D o:objects.values()){o.selected=o.selectable&&!o.selected;o.active=false;if(o.selected)last=o.id;}
        activeObjectId=last;
        if(last!=null)objects.get(last).active=true;
        revision++;
    }

    public void setActiveObject(String objectId) {
        Object3D target=requireObject(objectId);
        if(!target.selectable)return;
        target.selected=true;
        for(Object3D o:objects.values())o.active=false;
        target.active=true;activeObjectId=objectId;revision++;
    }

    private void replaceSelection(List<Object3D> targets) {
        for(Object3D o:objects.values()){o.selected=false;o.active=false;}
        activeObjectId=null;
        for(Object3D target:targets)if(target!=null&&target.selectable)target.selected=true;
        if(!targets.isEmpty()){
            Object3D last=targets.get(targets.size()-1);
            if(last!=null&&last.selectable){last.active=true;activeObjectId=last.id;}
        }
        revision++;
    }

    public void selectActiveCamera(boolean extend) {
        Object3D camera=getActiveCamera();if(camera==null||!camera.selectable)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        if(!targets.contains(camera))targets.add(camera);
        replaceSelection(targets);
    }

    public void selectByType(String type,boolean extend) {
        String requested=type==null?"":type.trim().toUpperCase(java.util.Locale.ROOT);
        ArrayList<Object3D> targets=new ArrayList<>();
        if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())if(o.selectable&&o.type.equalsIgnoreCase(requested)&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    public int getMeshDataUserCount(String objectId) {
        Object3D object=requireObject(objectId);if(object.mesh==null)return 0;
        int count=0;for(Object3D candidate:objects.values())if(candidate.mesh==object.mesh)count++;
        return count;
    }

    public void linkSelectedToActiveMeshData() {
        Object3D active=getActiveObject();
        if(active==null||active.mesh==null)throw new IllegalStateException("Select an active mesh object first");
        ArrayList<Object3D> targets=new ArrayList<>(getSelectedObjects());
        if(targets.size()<2)throw new IllegalStateException("Select the active mesh and at least one target object");
        for(Object3D target:targets){
            if(target==active)continue;
            if(!"MESH".equals(target.type)||target.mesh==null)
                throw new IllegalStateException("Only mesh objects can share mesh data");
        }
        for(Object3D target:targets)if(target!=active)target.mesh=active.mesh;
        revision++;
    }

    public void selectLinkedData(boolean extend) {
        Object3D active=getActiveObject();if(active==null||active.mesh==null)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())if(o.selectable&&o.mesh==active.mesh&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    public void selectChildren(boolean recursive,boolean extend) {
        Object3D active=getActiveObject();if(active==null)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())if(o.selectable&&active.id.equals(o.parentId))targets.add(o);
        if(recursive){
            for(int index=0;index<targets.size();index++){
                Object3D parent=targets.get(index);
                for(Object3D o:objects.values())
                    if(o.selectable&&parent.id.equals(o.parentId)&&!targets.contains(o))targets.add(o);
            }
        }
        replaceSelection(targets);
    }

    public void selectParent(boolean extend) {
        Object3D active=getActiveObject();if(active==null||active.parentId==null)return;
        Object3D parent=objects.get(active.parentId);if(parent==null||!parent.selectable)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());targets.add(parent);
        replaceSelection(targets);
    }

    public void selectSiblings(boolean extend) {
        Object3D active=getActiveObject();if(active==null)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())if(o.selectable&&java.util.Objects.equals(o.parentId,active.parentId)&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    public void selectSameCollection(boolean extend) {
        Object3D active=getActiveObject();if(active==null)return;
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())if(o.selectable&&sharesCollection(active,o)&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    private boolean sharesCollection(Object3D a,Object3D b) {
        for(String id:a.collectionIds)if(b.collectionIds.contains(id))return true;
        return false;
    }

    public void selectPattern(String wildcard,boolean extend,boolean caseSensitive) {
        String pattern=wildcard==null?"*":wildcard.trim();if(pattern.isEmpty())pattern="*";
        StringBuilder regex=new StringBuilder("^");
        for(int i=0;i<pattern.length();i++){
            char ch=pattern.charAt(i);
            if(ch=='*')regex.append(".*");
            else if(ch=='?')regex.append('.');
            else if(ch=='['){
                int close=pattern.indexOf(']',i+1);
                if(close>i+1){
                    String group=pattern.substring(i+1,close);
                    boolean exclude=group.charAt(0)=='!'||group.charAt(0)=='^';
                    if(exclude)group=group.substring(1);
                    regex.append('[');if(exclude)regex.append('^');
                    for(int j=0;j<group.length();j++){
                        char item=group.charAt(j);
                        if(item=='\\'||item==']'||item=='^')regex.append('\\');
                        regex.append(item);
                    }
                    regex.append(']');i=close;
                }else regex.append("\\\\[");
            }else regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        regex.append('$');
        int flags=caseSensitive?0:java.util.regex.Pattern.CASE_INSENSITIVE;
        java.util.regex.Pattern compiled=java.util.regex.Pattern.compile(regex.toString(),flags);
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())
            if(o.selectable&&compiled.matcher(o.name).matches()&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    public void alignSelectedLocationsToActive(int axis) {
        if(axis<0||axis>3)throw new IllegalArgumentException("Axis must be X, Y, Z or ALL");
        Object3D active=getActiveObject();if(active==null)throw new IllegalStateException("There is no active object");
        ArrayList<Object3D> targets=new ArrayList<>(getSelectedObjects());
        if(targets.size()<2)throw new IllegalStateException("Select at least two objects");
        float[] anchor=worldLocation(active.id);
        for(Object3D target:targets){
            if(target==active)continue;
            float[] location=worldLocation(target.id);
            if(axis==0||axis==3)location[0]=anchor[0];
            if(axis==1||axis==3)location[1]=anchor[1];
            if(axis==2||axis==3)location[2]=anchor[2];
            setWorldLocation(target.id,location[0],location[1],location[2]);
        }
        revision++;
    }

    public void parentSelectedToActive() {
        Object3D parent=getActiveObject();if(parent==null)throw new IllegalStateException("Choose an active parent object first");
        ArrayList<Object3D> children=new ArrayList<>(getSelectedObjects());
        for(Object3D child:children)if(!child.id.equals(parent.id))setParent(child.id,parent.id);
    }

    public void clearSelectedParents() {
        ArrayList<Object3D> targets=new ArrayList<>(getSelectedObjects());
        for(Object3D child:targets)if(child.parentId!=null)setParent(child.id,null);
    }

    public void hideSelected() {
        for(Object3D o:getSelectedObjects())o.visible=false;
        revision++;
    }

    public void hideUnselected() {
        for(Object3D o:objects.values())if(!o.selected)o.visible=false;
        revision++;
    }

    public void showAllObjects() {
        for(Object3D o:objects.values())o.visible=true;
        for(Collection3D c:collections.values())c.visible=true;
        revision++;
    }

    public void invertHiddenObjects() {
        for(Object3D o:objects.values())o.visible=!o.visible;
        revision++;
    }

    public void setSelectedSelectable(boolean selectable) {
        for(Object3D o:getSelectedObjects())o.selectable=selectable;
        revision++;
    }

    public void setSelectedRenderVisible(boolean visible) {
        for(Object3D o:getSelectedObjects())o.renderVisible=visible;
        revision++;
    }

    private boolean belongsToCollectionTree(Object3D object,String collectionId) {
        for(String memberId:object.collectionIds){
            Collection3D member=collections.get(memberId);int guard=0;
            while(member!=null&&guard++<=collections.size()){
                if(collectionId.equals(member.id))return true;
                member=member.parentId==null?null:collections.get(member.parentId);
            }
        }
        return false;
    }

    public void selectCollection(String collectionId,boolean extend) {
        requireCollection(collectionId);
        ArrayList<Object3D> targets=new ArrayList<>();if(extend)targets.addAll(getSelectedObjects());
        for(Object3D o:objects.values())
            if(o.selectable&&belongsToCollectionTree(o,collectionId)&&!targets.contains(o))targets.add(o);
        replaceSelection(targets);
    }

    private static float[][] generatePlanarFaceUvs(float[][] vertices,int[][] faces) {
        float[][] result=new float[faces.length][];
        for(int fi=0;fi<faces.length;fi++){
            int[] face=faces[fi];float[] a=vertices[face[0]],b=vertices[face[1]],d=vertices[face[2]];
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2];
            float vx=d[0]-a[0],vy=d[1]-a[1],vz=d[2]-a[2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            int drop=Math.abs(nx)>=Math.abs(ny)&&Math.abs(nx)>=Math.abs(nz)?0:
                    Math.abs(ny)>=Math.abs(nz)?1:2;
            float minU=Float.MAX_VALUE,minV=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
            for(int id:face){
                float u=drop==0?vertices[id][1]:vertices[id][0];
                float v=drop==2?vertices[id][1]:vertices[id][2];
                if(drop==1)v=vertices[id][2];
                minU=Math.min(minU,u);minV=Math.min(minV,v);maxU=Math.max(maxU,u);maxV=Math.max(maxV,v);
            }
            float spanU=Math.max(1e-6f,maxU-minU),spanV=Math.max(1e-6f,maxV-minV);
            float[] uv=new float[face.length*2];
            for(int corner=0;corner<face.length;corner++){
                int id=face[corner];
                float u=drop==0?vertices[id][1]:vertices[id][0];
                float v=vertices[id][2];
                if(drop==2)v=vertices[id][1];
                uv[corner*2]=(u-minU)/spanU;uv[corner*2+1]=(v-minV)/spanV;
            }
            result[fi]=uv;
        }
        return result;
    }

    private static float[][][] toCornerUvArray(float[][] packed) {
        float[][][] out=new float[packed.length][][];
        for(int face=0;face<packed.length;face++){
            int corners=packed[face].length/2;out[face]=new float[corners][2];
            for(int corner=0;corner<corners;corner++){
                out[face][corner][0]=packed[face][corner*2];
                out[face][corner][1]=packed[face][corner*2+1];
            }
        }
        return out;
    }

    private static double linearDeterminant(double[] m) {
        return m[0]*(m[5]*m[10]-m[6]*m[9])
                -m[1]*(m[4]*m[10]-m[6]*m[8])
                +m[2]*(m[4]*m[9]-m[5]*m[8]);
    }

    /** Join selected mesh objects into the active object's local coordinate space. */
    public Object3D joinSelectedMeshes() {
        Object3D active=getActiveObject();
        if(active==null)throw new IllegalStateException("There is no active object");
        ArrayList<Object3D> selected=new ArrayList<>(getSelectedObjects());
        if(selected.size()<2)throw new IllegalStateException("Select at least two mesh objects to join");
        for(Object3D object:selected)if(!"MESH".equals(object.type)||object.mesh==null)
            throw new IllegalStateException("Join supports mesh objects only");

        Meshy3DCore referenceMesh=active.mesh;
        int referenceColor=referenceMesh.getBaseColor();
        float referenceMetallic=referenceMesh.getMetallic(),referenceRoughness=referenceMesh.getRoughness();
        boolean referenceHasTexture=referenceMesh.hasTexture();
        int referenceTextureWidth=referenceMesh.getTextureWidth(),referenceTextureHeight=referenceMesh.getTextureHeight();
        int[] referenceTexture=referenceMesh.getTexturePixels();
        boolean anyUv=false;
        for(Object3D object:selected){
            Meshy3DCore mesh=object.mesh;
            if(mesh.getBaseColor()!=referenceColor
                    ||Math.abs(mesh.getMetallic()-referenceMetallic)>1e-6f
                    ||Math.abs(mesh.getRoughness()-referenceRoughness)>1e-6f)
                throw new IllegalStateException("Join cannot merge different object materials yet. Assign matching Base Color, Metallic and Roughness first.");
            if(mesh.hasTexture()!=referenceHasTexture
                    ||mesh.getTextureWidth()!=referenceTextureWidth||mesh.getTextureHeight()!=referenceTextureHeight
                    ||!java.util.Arrays.equals(mesh.getTexturePixels(),referenceTexture))
                throw new IllegalStateException("Join cannot merge different image textures yet. Use the same texture image on the selected meshes first.");
            float[][][] checkedUvs=mesh.copyFaceUvs();
            if(referenceHasTexture){
                if(checkedUvs==null||checkedUvs.length!=mesh.getFaceCount())
                    throw new IllegalStateException("Every textured mesh needs complete UV coordinates before joining.");
                int[][] checkedFaces=mesh.copyFaces();
                for(int fi=0;fi<checkedFaces.length;fi++)
                    if(checkedUvs[fi]==null||checkedUvs[fi].length!=checkedFaces[fi].length)
                        throw new IllegalStateException("A textured mesh has missing UV corners on face "+fi+". Repair its UV map before joining.");
            }
            anyUv|=checkedUvs!=null;
        }

        double[] inverseActive=invertAffine(worldMatrix(active.id));
        ArrayList<float[]> mergedVertices=new ArrayList<>();
        ArrayList<int[]> mergedFaces=new ArrayList<>();
        ArrayList<float[][]> mergedUvFaces=new ArrayList<>();
        for(Object3D source:selected){
            double[] toActive=multiply(inverseActive,worldMatrix(source.id));
            float[][] sourceVertices=source.mesh.copyVertices();
            int[][] sourceFaces=source.mesh.copyFaces();
            float[][][] sourceUvs=source.mesh.copyFaceUvs();
            int offset=mergedVertices.size();
            for(float[] point:sourceVertices){
                mergedVertices.add(new float[]{
                        finite((float)(toActive[0]*point[0]+toActive[1]*point[1]+toActive[2]*point[2]+toActive[3])),
                        finite((float)(toActive[4]*point[0]+toActive[5]*point[1]+toActive[6]*point[2]+toActive[7])),
                        finite((float)(toActive[8]*point[0]+toActive[9]*point[1]+toActive[10]*point[2]+toActive[11]))});
            }
            boolean needsGeneratedUvs=anyUv&&(sourceUvs==null);
            if(anyUv&&sourceUvs!=null)for(int fi=0;fi<sourceFaces.length;fi++)
                if(fi>=sourceUvs.length||sourceUvs[fi]==null
                        ||sourceUvs[fi].length!=sourceFaces[fi].length){needsGeneratedUvs=true;break;}
            float[][] generatedUvs=needsGeneratedUvs&&!referenceHasTexture
                    ?generatePlanarFaceUvs(sourceVertices,sourceFaces):null;
            boolean mirrored=linearDeterminant(toActive)<0;
            for(int fi=0;fi<sourceFaces.length;fi++){
                int[] original=sourceFaces[fi];
                int[] mapped=original.clone();
                for(int corner=0;corner<mapped.length;corner++)mapped[corner]+=offset;
                if(mirrored){
                    for(int left=0,right=mapped.length-1;left<right;left++,right--){
                        int swap=mapped[left];mapped[left]=mapped[right];mapped[right]=swap;
                    }
                }
                mergedFaces.add(mapped);
                if(anyUv){
                    float[][] uvFace=new float[original.length][2];
                    if(sourceUvs!=null&&fi<sourceUvs.length&&sourceUvs[fi]!=null
                            &&sourceUvs[fi].length==original.length){
                        for(int corner=0;corner<original.length;corner++){
                            int uvCorner=mirrored?original.length-1-corner:corner;
                            uvFace[corner]=sourceUvs[fi][uvCorner].clone();
                        }
                    }else{
                        for(int corner=0;corner<original.length;corner++){
                            int uvCorner=mirrored?original.length-1-corner:corner;
                            uvFace[corner][0]=generatedUvs[fi][uvCorner*2];
                            uvFace[corner][1]=generatedUvs[fi][uvCorner*2+1];
                        }
                    }
                    mergedUvFaces.add(uvFace);
                }
            }
        }
        if(mergedVertices.isEmpty()||mergedFaces.isEmpty())
            throw new IllegalStateException("Selected meshes contain no joinable geometry");

        // Never mutate a mesh datablock still used by an unselected linked object.
        if(getMeshDataUserCount(active.id)>1)active.mesh=active.mesh.copy();

        java.util.HashSet<String> deletingIds=new java.util.HashSet<>();
        for(Object3D source:selected)if(source!=active)deletingIds.add(source.id);
        // Reparent active before deleting any selected ancestor, preserving its world matrix.
        String ancestor=active.parentId;int ancestorGuard=0;
        while(ancestor!=null&&deletingIds.contains(ancestor)&&ancestorGuard++<objects.size()){
            Object3D parent=objects.get(ancestor);
            String next=parent==null?null:parent.parentId;
            setParent(active.id,next);ancestor=next;
        }
        for(Object3D source:selected)if(source!=active){
            ArrayList<String> children=new ArrayList<>();
            for(Object3D candidate:objects.values())
                if(source.id.equals(candidate.parentId)&&!candidate.selected)children.add(candidate.id);
            for(String child:children)setParent(child,active.id);
        }
        float[][] vertices=mergedVertices.toArray(new float[0][]);
        int[][] faces=mergedFaces.toArray(new int[0][]);
        float[][][] uvs=anyUv?mergedUvFaces.toArray(new float[0][][]):null;
        active.mesh.replaceGeometry(vertices,faces,uvs);
        for(Object3D source:selected)if(source!=active){
            if(source.id.equals(activeCameraId))activeCameraId=null;
            objects.remove(source.id);
        }
        for(Object3D remaining:objects.values())
            remaining.constraints.removeIf(constraint->deletingIds.contains(constraint.targetId));
        replaceSelection(java.util.Collections.singletonList(active));
        return active;
    }

    public float[] dimensions(String objectId) {
        Object3D object=requireObject(objectId);
        if(object.mesh==null)return new float[]{object.displaySize,object.displaySize,object.displaySize};
        Meshy3DCore dimensionMesh=object.mesh;
        if(!object.modifiers.isEmpty()){
            try{dimensionMesh=Meshy3DModifierEvaluator.evaluate(object);}
            catch(RuntimeException ignored){dimensionMesh=object.mesh;}
        }
        float[][] vertices=dimensionMesh.copyVertices();
        if(vertices.length==0)return new float[]{0f,0f,0f};
        float[] min={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},max={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(float[] p:vertices){
            float[] world=transformPoint(objectId,p[0],p[1],p[2]);
            for(int axis=0;axis<3;axis++){min[axis]=Math.min(min[axis],world[axis]);max[axis]=Math.max(max[axis],world[axis]);}
        }
        return new float[]{max[0]-min[0],max[1]-min[1],max[2]-min[2]};
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
            if(childId.equals(parentId)||dependencyReaches(parentId,childId,new java.util.HashSet<>()))
                throw new IllegalArgumentException("Parenting would create a transform dependency cycle");
        }
        double[] world=worldMatrix(childId);
        child.parentId=parentId;
        double[] local=parentId==null?world:multiply(invertAffine(worldMatrix(parentId)),world);
        decompose(local,child.transform);
        revision++;
    }

    public int getObjectHierarchyDepth(String objectId) {
        Object3D object=requireObject(objectId);
        int depth=0,guard=0;String parent=object.parentId;
        while(parent!=null&&guard++<objects.size()){
            Object3D ancestor=objects.get(parent);if(ancestor==null)break;
            depth++;parent=ancestor.parentId;
        }
        return depth;
    }

    public float[] worldLocation(String objectId) {
        double[] m=worldMatrix(objectId);
        return new float[]{(float)m[3],(float)m[7],(float)m[11]};
    }

    public double[] getWorldMatrixCopy(String objectId) {
        return worldMatrix(objectId).clone();
    }

    public void setWorldMatrix(String objectId,double[] worldMatrix) {
        if(worldMatrix==null||worldMatrix.length!=16)throw new IllegalArgumentException("World matrix must contain 16 values");
        Object3D object=requireObject(objectId);
        double[] local=object.parentId==null?worldMatrix.clone()
                :multiply(invertAffine(worldMatrix(object.parentId)),worldMatrix);
        decompose(local,object.transform);
        revision++;
    }

    /** Convert a world-space point into this object's local mesh coordinates. */
    public float[] inverseTransformPoint(String objectId,float x,float y,float z) {
        double[] inverse=invertAffine(worldMatrix(objectId));
        return new float[]{(float)(inverse[0]*x+inverse[1]*y+inverse[2]*z+inverse[3]),
                (float)(inverse[4]*x+inverse[5]*y+inverse[6]*z+inverse[7]),
                (float)(inverse[8]*x+inverse[9]*y+inverse[10]*z+inverse[11])};
    }

    /** Transform a mesh-local point through its object and every parent transform. */
    public float[] transformPoint(String objectId,float x,float y,float z) {
        double[] m=worldMatrix(objectId);
        return new float[]{(float)(m[0]*x+m[1]*y+m[2]*z+m[3]),
                (float)(m[4]*x+m[5]*y+m[6]*z+m[7]),
                (float)(m[8]*x+m[9]*y+m[10]*z+m[11])};
    }

    private double[] worldMatrix(String objectId) {
        return worldMatrix(objectId,new java.util.HashSet<>());
    }

    private double[] worldMatrix(String objectId,java.util.Set<String> evaluatingConstraints) {
        Object3D o=requireObject(objectId);
        double[] local=localMatrix(o.transform);
        double[] result=o.parentId==null?local:multiply(worldMatrix(o.parentId,evaluatingConstraints),local);
        if(!evaluatingConstraints.add(objectId))
            throw new IllegalStateException("A transform constraint cycle was detected in the scene.");
        for(Constraint3D constraint:o.constraints){
            if(!constraint.enabled||constraint.influence<=0f)continue;
            double[] target=worldMatrix(constraint.targetId,evaluatingConstraints);
            result=applyConstraint(result,target,constraint);
        }
        evaluatingConstraints.remove(objectId);
        return result;
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
        double determinant=m[0]*(m[5]*m[10]-m[6]*m[9])
                -m[1]*(m[4]*m[10]-m[6]*m[8])
                +m[2]*(m[4]*m[9]-m[5]*m[8]);
        // Store reflection in X scale so the extracted rotation remains right-handed.
        if(determinant<0)sx=-sx;
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

    public void setViewportDisplay(String objectId,String displayType,int color){
        Object3D object=requireObject(objectId);
        String type=displayType==null?"SOLID":displayType.trim().toUpperCase(java.util.Locale.ROOT);
        if(!"SOLID".equals(type)&&!"WIRE".equals(type)&&!"BOUNDS".equals(type))
            throw new IllegalArgumentException("Viewport display must be Solid, Wire or Bounds.");
        object.viewportDisplayType=type;
        object.viewportDisplayColor=0xFF000000|(color&0x00FFFFFF);
        revision++;
    }

    public void setVisibility(String objectId,boolean visible) {requireObject(objectId).visible=visible;revision++;}
    public void setSelectable(String objectId,boolean selectable) {requireObject(objectId).selectable=selectable;revision++;}
    public void setRenderVisibility(String objectId,boolean visible) {requireObject(objectId).renderVisible=visible;revision++;}

    public Collection3D createCollection(String name) {return createCollection(name,null);}
    public Collection3D createCollection(String name,String parentId) {
        if(parentId!=null)requireCollection(parentId);
        String clean=name==null?"":name.trim();if(clean.isEmpty())clean="Collection";
        Collection3D c=new Collection3D(UUID.randomUUID().toString(),uniqueCollectionName(clean));
        c.parentId=parentId;collections.put(c.id,c);revision++;return c;
    }
    public void setCollectionParent(String collectionId,String parentId) {
        Collection3D collection=requireCollection(collectionId);
        if(collectionId.equals(parentId))throw new IllegalArgumentException("A collection cannot parent itself");
        if(parentId!=null)requireCollection(parentId);
        String p=parentId;int guard=0;
        while(p!=null&&guard++<=collections.size()){
            if(collectionId.equals(p))throw new IllegalArgumentException("Collection parenting would create a cycle");
            Collection3D parent=collections.get(p);if(parent==null)break;p=parent.parentId;
        }
        if(guard>collections.size())throw new IllegalArgumentException("Collection hierarchy is invalid");
        collection.parentId=parentId;revision++;
    }
    public int getCollectionDepth(String collectionId) {
        Collection3D collection=requireCollection(collectionId);
        int depth=0,guard=0;String p=collection.parentId;
        while(p!=null&&guard++<collections.size()){
            Collection3D parent=collections.get(p);if(parent==null)break;depth++;p=parent.parentId;
        }
        return depth;
    }
    public void renameCollection(String collectionId,String name) {
        Collection3D c=requireCollection(collectionId);String clean=name==null?"":name.trim();
        if(clean.isEmpty())throw new IllegalArgumentException("Collection name cannot be empty");
        c.name=uniqueCollectionName(clean,collectionId);revision++;
    }
    public void setCollectionVisibility(String collectionId,boolean visible) {requireCollection(collectionId).visible=visible;revision++;}
    public void setCollectionRenderVisibility(String collectionId,boolean visible) {requireCollection(collectionId).renderVisible=visible;revision++;}
    public void deleteCollection(String collectionId) {
        Collection3D deleting=requireCollection(collectionId);
        if(collections.size()<=1)throw new IllegalStateException("A scene must keep at least one collection");
        String fallback=null;
        for(String id:collections.keySet())if(!id.equals(collectionId)){
            Collection3D candidate=collections.get(id);
            if(java.util.Objects.equals(candidate.parentId,deleting.parentId)){fallback=id;break;}
        }
        if(fallback==null)for(String id:collections.keySet())if(!id.equals(collectionId)){fallback=id;break;}
        for(Collection3D child:collections.values())if(collectionId.equals(child.parentId))child.parentId=deleting.parentId;
        for(Object3D object:objects.values()){
            object.collectionIds.remove(collectionId);
            if(object.collectionIds.isEmpty())object.collectionIds.add(fallback);
        }
        collections.remove(deleting.id);revision++;
    }
    public void moveToCollection(String objectId,String collectionId) {
        Object3D o=requireObject(objectId);requireCollection(collectionId);
        o.collectionIds.clear();o.collectionIds.add(collectionId);revision++;
    }
    public void linkToCollection(String objectId,String collectionId) {
        Object3D o=requireObject(objectId);requireCollection(collectionId);
        if(!o.collectionIds.contains(collectionId))o.collectionIds.add(collectionId);revision++;
    }
    public void removeFromCollection(String objectId,String collectionId) {
        Object3D o=requireObject(objectId);
        if(o.collectionIds.size()>1)o.collectionIds.remove(collectionId);
        revision++;
    }
    private boolean collectionVisible(String id,boolean render) {
        Collection3D c=collections.get(id);int guard=0;
        while(c!=null&&guard++<=collections.size()){
            if(render?!c.renderVisible:!c.visible)return false;
            c=c.parentId==null?null:collections.get(c.parentId);
        }
        return true;
    }

    public boolean isVisibleInViewport(Object3D o) {
        if(!o.visible)return false;
        boolean anyVisibleCollection=false;
        for(String id:o.collectionIds)if(collectionVisible(id,false)){anyVisibleCollection=true;break;}
        if(!anyVisibleCollection)return false;
        String parent=o.parentId;int guard=0;
        while(parent!=null&&guard++<objects.size()){
            Object3D p=objects.get(parent);if(p==null||!p.visible)return false;parent=p.parentId;
        }
        return true;
    }
    public boolean isVisibleInRender(Object3D o) {
        if(!o.renderVisible)return false;
        boolean anyVisibleCollection=false;
        for(String id:o.collectionIds)if(collectionVisible(id,true)){anyVisibleCollection=true;break;}
        if(!anyVisibleCollection)return false;
        String parent=o.parentId;int guard=0;
        while(parent!=null&&guard++<objects.size()){
            Object3D p=objects.get(parent);if(p==null||!p.renderVisible)return false;parent=p.parentId;
        }
        return true;
    }

    private LinkedHashMap<String,double[]> captureDirectChildWorldTransforms(String parentId) {
        LinkedHashMap<String,double[]> snapshots=new LinkedHashMap<>();
        for(Object3D object:objects.values())
            if(parentId.equals(object.parentId))snapshots.put(object.id,worldMatrix(object.id));
        return snapshots;
    }

    private void restoreDirectChildWorldTransforms(String parentId,LinkedHashMap<String,double[]> snapshots) {
        if(snapshots.isEmpty())return;
        double[] inverseParent=invertAffine(worldMatrix(parentId));
        for(Map.Entry<String,double[]> entry:snapshots.entrySet()){
            Object3D child=objects.get(entry.getKey());
            if(child!=null&&parentId.equals(child.parentId))
                decompose(multiply(inverseParent,entry.getValue()),child.transform);
        }
    }

    /** Bake local translation into mesh coordinates without changing the visible object. */
    public void applyLocation(String objectId) {
        Object3D o=requireObject(objectId);
        if(o.mesh==null)throw new IllegalStateException("This object type has no editable mesh data");
        LinkedHashMap<String,double[]> childWorld=captureDirectChildWorldTransforms(objectId);
        float x=o.transform.x,y=o.transform.y,z=o.transform.z;
        float[][] vertices=o.mesh.copyVertices();
        double rx=Math.toRadians(o.transform.rotationX),ry=Math.toRadians(o.transform.rotationY),rz=Math.toRadians(o.transform.rotationZ);
        float sx=o.transform.scaleX,sy=o.transform.scaleY,sz=o.transform.scaleZ;
        // Bake T into mesh coordinates using inverse(Rz * Ry * Rx * Scale).
        float tx=(float)(x*Math.cos(ry)*Math.cos(rz)+y*Math.cos(ry)*Math.sin(rz)-z*Math.sin(ry));
        float ty=(float)(-x*Math.sin(rz)+y*Math.cos(rz));
        float tz=(float)(x*Math.sin(ry)*Math.cos(rz)+y*Math.sin(ry)*Math.sin(rz)+z*Math.cos(ry));
        float ux=tx/sx,uy=(float)((ty*Math.cos(rx)+tz*Math.sin(rx))/sy),uz=(float)((-ty*Math.sin(rx)+tz*Math.cos(rx))/sz);
        for(float[] p:vertices){p[0]+=ux;p[1]+=uy;p[2]+=uz;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x=o.transform.y=o.transform.z=0f;
        restoreDirectChildWorldTransforms(objectId,childWorld);revision++;
    }

    /** Move the origin to the local mesh bounds center while preserving world geometry. */
    public void setOriginToGeometry(String objectId) {
        Object3D o=requireObject(objectId);
        if(o.mesh==null)throw new IllegalStateException("This object type has no editable mesh origin");
        LinkedHashMap<String,double[]> childWorld=captureDirectChildWorldTransforms(objectId);
        float[][] vertices=o.mesh.copyVertices();
        if(vertices.length==0)return;
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(float[] p:vertices){minX=Math.min(minX,p[0]);minY=Math.min(minY,p[1]);minZ=Math.min(minZ,p[2]);
            maxX=Math.max(maxX,p[0]);maxY=Math.max(maxY,p[1]);maxZ=Math.max(maxZ,p[2]);}
        float cx=(minX+maxX)*.5f,cy=(minY+maxY)*.5f,cz=(minZ+maxZ)*.5f;
        float[] offset=transformVector(o.transform,cx,cy,cz);
        for(float[] p:vertices){p[0]-=cx;p[1]-=cy;p[2]-=cz;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x+=offset[0];o.transform.y+=offset[1];o.transform.z+=offset[2];
        restoreDirectChildWorldTransforms(objectId,childWorld);revision++;
    }

    /** Place the origin at the world-space 3D cursor (the world origin in this editor). */
    public void setOriginToCursor(String objectId) {
        Object3D o=requireObject(objectId);
        if(o.mesh==null)throw new IllegalStateException("This object type has no editable mesh origin");
        double[] inverse=invertAffine(worldMatrix(objectId));
        float cx=(float)(inverse[0]*cursorX+inverse[1]*cursorY+inverse[2]*cursorZ+inverse[3]);
        float cy=(float)(inverse[4]*cursorX+inverse[5]*cursorY+inverse[6]*cursorZ+inverse[7]);
        float cz=(float)(inverse[8]*cursorX+inverse[9]*cursorY+inverse[10]*cursorZ+inverse[11]);
        LinkedHashMap<String,double[]> childWorld=captureDirectChildWorldTransforms(objectId);
        float[][] vertices=o.mesh.copyVertices();
        float[] offset=transformVector(o.transform,cx,cy,cz);
        for(float[] p:vertices){p[0]-=cx;p[1]-=cy;p[2]-=cz;}
        o.mesh.replaceGeometry(vertices,o.mesh.copyFaces());
        o.transform.x+=offset[0];o.transform.y+=offset[1];o.transform.z+=offset[2];
        restoreDirectChildWorldTransforms(objectId,childWorld);revision++;
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
        if(o.mesh==null)throw new IllegalStateException("This object type has no editable mesh data");
        LinkedHashMap<String,double[]> childWorld=captureDirectChildWorldTransforms(objectId);
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
        restoreDirectChildWorldTransforms(objectId,childWorld);
        revision++;
    }

    public JSONObject exportState() {
        JSONObject root=new JSONObject();
        try{
            root.put("version",1);root.put("activeObjectId",activeObjectId);
            JSONArray objectArray=new JSONArray();
            java.util.IdentityHashMap<Meshy3DCore,String> meshIds=new java.util.IdentityHashMap<>();
            int meshSequence=0;
            for(Object3D o:objects.values()){
                JSONObject item=new JSONObject();item.put("id",o.id);item.put("name",o.name);item.put("type",o.type);
                if(o.mesh!=null){
                    String meshId=meshIds.get(o.mesh);
                    if(meshId==null){meshId="mesh-data-"+(meshSequence++);meshIds.put(o.mesh,meshId);item.put("mesh",o.mesh.exportState());}
                    item.put("meshDataId",meshId);
                }
                item.put("transform",o.transform.toJson());
                item.put("parentId",o.parentId==null?JSONObject.NULL:o.parentId);
                item.put("visible",o.visible);item.put("selectable",o.selectable);item.put("renderVisible",o.renderVisible);
                item.put("cameraLens",o.cameraLens);item.put("cameraClipStart",o.cameraClipStart);item.put("cameraClipEnd",o.cameraClipEnd);
                item.put("lightType",o.lightType);item.put("lightPower",o.lightPower);item.put("lightAngle",o.lightAngle);
                item.put("lightColor",o.lightColor);item.put("displaySize",o.displaySize);
                item.put("viewportDisplayType",o.viewportDisplayType);
                item.put("viewportDisplayColor",o.viewportDisplayColor);
                item.put("selected",o.selected);JSONArray members=new JSONArray();for(String id:o.collectionIds)members.put(id);item.put("collections",members);
                JSONArray constraintArray=new JSONArray();
                for(Constraint3D constraint:o.constraints){
                    JSONObject saved=new JSONObject();saved.put("id",constraint.id);saved.put("type",constraint.type);
                    saved.put("targetId",constraint.targetId);saved.put("influence",constraint.influence);
                    saved.put("enabled",constraint.enabled);constraintArray.put(saved);
                }
                item.put("constraints",constraintArray);
                JSONArray customArray=new JSONArray();
                for(CustomProperty3D property:o.customProperties.values()){
                    JSONObject saved=new JSONObject();saved.put("key",property.key);
                    saved.put("type",property.type);saved.put("value",property.value);customArray.put(saved);
                }
                item.put("customProperties",customArray);
                JSONArray modifierArray=new JSONArray();
                for(Modifier3D modifier:o.modifiers){
                    JSONObject saved=new JSONObject();saved.put("id",modifier.id);saved.put("type",modifier.type);
                    saved.put("enabled",modifier.enabled);saved.put("axis",modifier.axis);
                    saved.put("count",modifier.count);saved.put("levels",modifier.levels);
                    saved.put("value",modifier.value);saved.put("offsetX",modifier.offsetX);
                    saved.put("offsetY",modifier.offsetY);saved.put("offsetZ",modifier.offsetZ);
                    modifierArray.put(saved);
                }
                item.put("modifiers",modifierArray);
                objectArray.put(item);
            }
            root.put("objects",objectArray);
            root.put("activeCameraId",activeCameraId==null?JSONObject.NULL:activeCameraId);
            root.put("cursor",vector(cursorX,cursorY,cursorZ));
            JSONArray collectionArray=new JSONArray();
            for(Collection3D c:collections.values()){
                JSONObject item=new JSONObject();item.put("id",c.id);item.put("name",c.name);
                item.put("parentId",c.parentId==null?JSONObject.NULL:c.parentId);
                item.put("visible",c.visible);item.put("renderVisible",c.renderVisible);collectionArray.put(item);
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
                Object parent=item.opt("parentId");
                c.parentId=parent==null||parent==JSONObject.NULL?null:String.valueOf(parent);
                c.visible=item.optBoolean("visible",true);c.renderVisible=item.optBoolean("renderVisible",true);newCollections.put(id,c);
            }
            if(newCollections.isEmpty()){Collection3D c=new Collection3D("collection-scene","Collection");newCollections.put(c.id,c);}
            for(Collection3D collection:newCollections.values()){
                String p=collection.parentId;int guard=0;
                while(p!=null&&guard++<=newCollections.size()){
                    if(collection.id.equals(p))return false;
                    Collection3D parent=newCollections.get(p);if(parent==null)return false;p=parent.parentId;
                }
                if(guard>newCollections.size())return false;
            }
            LinkedHashMap<String,Object3D> newObjects=new LinkedHashMap<>();
            java.util.HashMap<String,Meshy3DCore> meshDataMap=new java.util.HashMap<>();
            JSONArray objectArray=root.getJSONArray("objects");
            for(int i=0;i<objectArray.length();i++){
                JSONObject item=objectArray.getJSONObject(i);String id=item.getString("id");
                String objectType=item.optString("type","MESH");
                String meshId=item.optString("meshDataId",id);
                Meshy3DCore mesh=meshDataMap.get(meshId);
                if(mesh==null){
                    JSONObject meshState=item.optJSONObject("mesh");
                    if(meshState!=null){mesh=new Meshy3DCore();if(!mesh.importState(meshState))return false;meshDataMap.put(meshId,mesh);}
                    else if("MESH".equals(objectType))return false;
                }
                Object3D o=new Object3D(id,item.optString("name","Object"),objectType,mesh);
                Transform t=Transform.fromJson(item.optJSONObject("transform"));
                o.transform.x=t.x;o.transform.y=t.y;o.transform.z=t.z;o.transform.rotationX=t.rotationX;o.transform.rotationY=t.rotationY;o.transform.rotationZ=t.rotationZ;o.transform.scaleX=t.scaleX;o.transform.scaleY=t.scaleY;o.transform.scaleZ=t.scaleZ;
                Object parent=item.opt("parentId");o.parentId=parent==null||parent==JSONObject.NULL?null:String.valueOf(parent);
                o.visible=item.optBoolean("visible",true);o.selectable=item.optBoolean("selectable",true);o.renderVisible=item.optBoolean("renderVisible",true);o.selected=item.optBoolean("selected",false);
                o.cameraLens=(float)item.optDouble("cameraLens",50f);o.cameraClipStart=(float)item.optDouble("cameraClipStart",.1f);o.cameraClipEnd=(float)item.optDouble("cameraClipEnd",1000f);
                o.lightType=item.optString("lightType","POINT");o.lightPower=(float)item.optDouble("lightPower",1000f);
                o.lightAngle=(float)item.optDouble("lightAngle",45f);o.lightColor=item.optInt("lightColor",0xFFFFFFFF);
                o.displaySize=(float)item.optDouble("displaySize",1f);
                String displayType=item.optString("viewportDisplayType","SOLID").toUpperCase(java.util.Locale.ROOT);
                if(!"SOLID".equals(displayType)&&!"WIRE".equals(displayType)&&!"BOUNDS".equals(displayType))return false;
                o.viewportDisplayType=displayType;
                o.viewportDisplayColor=item.optInt("viewportDisplayColor",0xFFFFFFFF);
                JSONArray memberships=item.optJSONArray("collections");
                if(memberships!=null)for(int j=0;j<memberships.length();j++){String cid=memberships.getString(j);if(newCollections.containsKey(cid))o.collectionIds.add(cid);}
                if(o.collectionIds.isEmpty())o.collectionIds.add(newCollections.keySet().iterator().next());
                JSONArray savedConstraints=item.optJSONArray("constraints");
                if(savedConstraints!=null)for(int j=0;j<savedConstraints.length();j++){
                    JSONObject saved=savedConstraints.getJSONObject(j);
                    String constraintType=cleanConstraintType(saved.optString("type","COPY_LOCATION"));
                    String targetId=saved.optString("targetId","");
                    float influence=(float)saved.optDouble("influence",1f);
                    if(targetId.isEmpty()||!Float.isFinite(influence)||influence<0f||influence>1f)return false;
                    o.constraints.add(new Constraint3D(saved.optString("id",UUID.randomUUID().toString()),
                            constraintType,targetId,influence,saved.optBoolean("enabled",true)));
                }
                JSONArray savedCustomProperties=item.optJSONArray("customProperties");
                if(savedCustomProperties!=null)for(int j=0;j<savedCustomProperties.length();j++){
                    JSONObject saved=savedCustomProperties.getJSONObject(j);
                    String key=cleanCustomPropertyKey(saved.optString("key",""));
                    String type=cleanCustomPropertyType(saved.optString("type","STRING"));
                    String value=cleanCustomPropertyValue(type,saved.optString("value",""));
                    if(o.customProperties.containsKey(key))return false;
                    o.customProperties.put(key,new CustomProperty3D(key,type,value));
                }
                JSONArray savedModifiers=item.optJSONArray("modifiers");
                if(savedModifiers!=null)for(int j=0;j<savedModifiers.length();j++){
                    JSONObject saved=savedModifiers.getJSONObject(j);
                    Modifier3D modifier=new Modifier3D(saved.optString("id",UUID.randomUUID().toString()),
                            cleanModifierType(saved.optString("type","")));
                    modifier.enabled=saved.optBoolean("enabled",true);
                    modifier.axis=saved.optInt("axis",0);modifier.count=saved.optInt("count",2);
                    modifier.levels=saved.optInt("levels",1);modifier.value=(float)saved.optDouble("value",.1f);
                    modifier.offsetX=(float)saved.optDouble("offsetX",2f);
                    modifier.offsetY=(float)saved.optDouble("offsetY",0f);
                    modifier.offsetZ=(float)saved.optDouble("offsetZ",0f);
                    if(modifier.axis<0||modifier.axis>2||modifier.count<1||modifier.count>20
                            ||modifier.levels<1||modifier.levels>3||!Float.isFinite(modifier.value)
                            ||!Float.isFinite(modifier.offsetX)||!Float.isFinite(modifier.offsetY)
                            ||!Float.isFinite(modifier.offsetZ))return false;
                    o.modifiers.add(modifier);
                }
                newObjects.put(id,o);
            }
            for(Object3D o:newObjects.values()){
                if(o.parentId!=null&&!newObjects.containsKey(o.parentId))return false;
                for(Constraint3D constraint:o.constraints){
                    if(!newObjects.containsKey(constraint.targetId)||o.id.equals(constraint.targetId)
                            ||dependencyReaches(newObjects,constraint.targetId,o.id,new java.util.HashSet<>()))
                        return false;
                }
            }
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
            Object camera=root.opt("activeCameraId");
            activeCameraId=camera==null||camera==JSONObject.NULL?null:String.valueOf(camera);
            if(activeCameraId!=null&&(!objects.containsKey(activeCameraId)||!"CAMERA".equals(objects.get(activeCameraId).type)))activeCameraId=null;
            cursorX=cursorY=cursorZ=0f;
            readVector(root.optJSONArray("cursor"),v->{cursorX=finite(v[0]);cursorY=finite(v[1]);cursorZ=finite(v[2]);});
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
    private static JSONArray vector(float x,float y,float z) throws JSONException {JSONArray a=new JSONArray();a.put(x);a.put(y);a.put(z);return a;}
    private interface VectorConsumer{void accept(float[] v);}
    private static void readVector(JSONArray a,VectorConsumer consumer){if(a==null||a.length()!=3)return;consumer.accept(new float[]{(float)a.optDouble(0,0),(float)a.optDouble(1,0),(float)a.optDouble(2,0)});}
    private static float finite(float v){return Float.isNaN(v)||Float.isInfinite(v)?0f:Math.max(-100000f,Math.min(100000f,v));}
    private static float safeScale(float v){if(Float.isNaN(v)||Float.isInfinite(v))return 1f;if(Math.abs(v)<.0001f)return v<0?-.0001f:.0001f;return Math.max(-10000f,Math.min(10000f,v));}
}
