#pragma once

#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <numeric>
#include <unordered_map>
#include <utility>
#include <vector>
#include "Mesh.h"

namespace nova {

// CPU-authoritative sculpt surface. GPU chunk buffers are disposable caches.
class SculptMesh {
public:
    struct AABB {
        Vec3 lo{ std::numeric_limits<float>::max(), std::numeric_limits<float>::max(), std::numeric_limits<float>::max() };
        Vec3 hi{ -std::numeric_limits<float>::max(), -std::numeric_limits<float>::max(), -std::numeric_limits<float>::max() };
        void add(const Vec3& p) {
            lo.x=std::min(lo.x,p.x); lo.y=std::min(lo.y,p.y); lo.z=std::min(lo.z,p.z);
            hi.x=std::max(hi.x,p.x); hi.y=std::max(hi.y,p.y); hi.z=std::max(hi.z,p.z);
        }
        void merge(const AABB& b) { add(b.lo); add(b.hi); }
        bool hit(const Vec3& o,const Vec3& d,float maxT) const {
            float t0=0.0f,t1=maxT;
            const float ov[3]={o.x,o.y,o.z}, dv[3]={d.x,d.y,d.z};
            const float mn[3]={lo.x,lo.y,lo.z}, mx[3]={hi.x,hi.y,hi.z};
            for(int i=0;i<3;i++){
                if(std::fabs(dv[i])<1e-8f){ if(ov[i]<mn[i]||ov[i]>mx[i]) return false; }
                else { float a=(mn[i]-ov[i])/dv[i], b=(mx[i]-ov[i])/dv[i]; if(a>b)std::swap(a,b); t0=std::max(t0,a); t1=std::min(t1,b); if(t1<t0)return false; }
            }
            return true;
        }
    };
    struct Chunk {
        std::vector<uint32_t> triangles;
        AABB bounds;
        GLuint vao=0,vbo=0,ibo=0;
        GLsizei indexCount=0;
        bool dirty=true;
        bool allocated=false;
        void release(){ if(ibo)glDeleteBuffers(1,&ibo); if(vbo)glDeleteBuffers(1,&vbo); if(vao)glDeleteVertexArrays(1,&vao); vao=vbo=ibo=0; indexCount=0; }
    };
    struct Hit { bool found=false; float distance=0; Vec3 point{},normal{}; uint32_t triangle=0; };
    struct BVHNode { AABB bounds; int left=-1,right=-1; uint32_t first=0,count=0; bool leaf()const{return left<0;} };

    std::vector<Vec3> positions;
    std::vector<Vec3> normals;
    std::vector<uint32_t> indices;
    std::vector<float> mask;
    std::vector<uint32_t> adjacencyOffsets;
    std::vector<uint32_t> adjacency;
    std::vector<uint32_t> faceOffsets;
    std::vector<uint32_t> vertexTriangles;
    std::vector<Chunk> chunks;
    std::vector<BVHNode> bvh;
    std::vector<uint32_t> triangleOrder;

    SculptMesh()=default;
    ~SculptMesh(){ releaseGpu(); }
    SculptMesh(const SculptMesh&)=delete;
    SculptMesh& operator=(const SculptMesh&)=delete;
    SculptMesh(SculptMesh&& o) noexcept { moveFrom(std::move(o)); }
    SculptMesh& operator=(SculptMesh&& o) noexcept { if(this!=&o){releaseGpu();moveFrom(std::move(o));}return *this; }

    static SculptMesh cubeSphere(int subdivisions=64,float radius=1.0f){
        SculptMesh out;
        subdivisions=std::clamp(subdivisions,2,128);
        const int n=subdivisions;
        struct Key { int x,y,z; bool operator==(const Key& b)const{return x==b.x&&y==b.y&&z==b.z;} };
        struct Hash { size_t operator()(const Key& k)const { size_t h=(uint32_t)k.x*73856093u; h^=(uint32_t)k.y*19349663u; h^=(uint32_t)k.z*83492791u; return h; } };
        std::unordered_map<Key,uint32_t,Hash> weld;
        weld.reserve((size_t)6*(n+1)*(n+1));
        auto add=[&](int x,int y,int z){
            const float q=100000.0f;
            Key key{(int)std::lround(x*q/n),(int)std::lround(y*q/n),(int)std::lround(z*q/n)};
            auto it=weld.find(key); if(it!=weld.end())return it->second;
            Vec3 p=normalize(Vec3((float)x/n,(float)y/n,(float)z/n))*radius;
            uint32_t id=(uint32_t)out.positions.size(); weld.emplace(key,id);
            out.positions.push_back(p); return id;
        };
        // Each face uses a consistent outward winding.
        for(int face=0;face<6;face++){
            for(int y=0;y<=n;y++) for(int x=0;x<=n;x++){
                int a=-n+2*x, b=-n+2*y, X=0,Y=0,Z=0;
                if(face==0){X=n;Y=b;Z=a;}
                if(face==1){X=-n;Y=b;Z=-a;}
                if(face==2){X=a;Y=n;Z=-b;}
                if(face==3){X=a;Y=-n;Z=b;}
                if(face==4){X=a;Y=b;Z=n;}
                if(face==5){X=-a;Y=b;Z=-n;}
                (void)add(X,Y,Z);
            }
            uint32_t base=(uint32_t)out.positions.size(); // not used for welded indices
            (void)base;
        }
        // Recreate face grids through the weld map, then emit outward triangles.
        for(int face=0;face<6;face++){
            std::vector<uint32_t> grid((size_t)(n+1)*(n+1));
            for(int y=0;y<=n;y++) for(int x=0;x<=n;x++){
                int a=-n+2*x,b=-n+2*y,X=0,Y=0,Z=0;
                if(face==0){X=n;Y=b;Z=a;}
                if(face==1){X=-n;Y=b;Z=-a;}
                if(face==2){X=a;Y=n;Z=-b;}
                if(face==3){X=a;Y=-n;Z=b;}
                if(face==4){X=a;Y=b;Z=n;}
                if(face==5){X=-a;Y=b;Z=-n;}
                Key key{(int)std::lround(X*100000.0f/n),(int)std::lround(Y*100000.0f/n),(int)std::lround(Z*100000.0f/n)};
                grid[(size_t)y*(n+1)+x]=weld.at(key);
            }
            for(int y=0;y<n;y++)for(int x=0;x<n;x++){
                uint32_t a=grid[(size_t)y*(n+1)+x],b=grid[(size_t)y*(n+1)+x+1],
                         c=grid[(size_t)(y+1)*(n+1)+x],d=grid[(size_t)(y+1)*(n+1)+x+1];
                auto emit=[&](uint32_t i,uint32_t j,uint32_t k){
                    Vec3 nn=cross(out.positions[j]-out.positions[i],out.positions[k]-out.positions[i]);
                    Vec3 center=out.positions[i]+out.positions[j]+out.positions[k];
                    if(dot(nn,center)<0)std::swap(j,k);
                    out.indices.push_back(i);out.indices.push_back(j);out.indices.push_back(k);
                };
                emit(a,b,d); emit(a,d,c);
            }
        }
        out.normals.resize(out.positions.size());
        for(size_t i=0;i<out.positions.size();++i)out.normals[i]=normalize(out.positions[i]);
        out.mask.assign(out.positions.size(),0.0f);
        out.buildAdjacency(); out.buildChunks(); out.buildBVH();
        return out;
    }

    void buildAdjacency(){
        const size_t count=positions.size();
        std::vector<std::pair<uint32_t,uint32_t>> edges; edges.reserve(indices.size());
        for(size_t i=0;i+2<indices.size();i+=3){
            uint32_t a=indices[i],b=indices[i+1],c=indices[i+2];
            if(a>=count||b>=count||c>=count)continue;
            edges.emplace_back(a,b);edges.emplace_back(b,a);edges.emplace_back(b,c);
            edges.emplace_back(c,b);edges.emplace_back(c,a);edges.emplace_back(a,c);
        }
        std::sort(edges.begin(),edges.end()); edges.erase(std::unique(edges.begin(),edges.end()),edges.end());
        adjacencyOffsets.assign(count+1,0);
        for(auto e:edges)++adjacencyOffsets[e.first+1];
        for(size_t i=1;i<adjacencyOffsets.size();++i)adjacencyOffsets[i]+=adjacencyOffsets[i-1];
        adjacency.resize(edges.size());
        std::vector<uint32_t> cursor=adjacencyOffsets;
        for(auto e:edges)adjacency[cursor[e.first]++]=e.second;
        std::vector<std::pair<uint32_t,uint32_t>> incident;
        incident.reserve(indices.size());
        for(uint32_t t=0;t<indices.size()/3;t++) for(int k=0;k<3;k++) {
            uint32_t v=indices[t*3+k];
            if(v<count) incident.emplace_back(v,t);
        }
        std::sort(incident.begin(),incident.end());
        faceOffsets.assign(count+1,0);
        for(const auto& e:incident) ++faceOffsets[e.first+1];
        for(size_t i=1;i<faceOffsets.size();++i) faceOffsets[i]+=faceOffsets[i-1];
        vertexTriangles.resize(incident.size());
        std::vector<uint32_t> faceCursor=faceOffsets;
        for(const auto& e:incident) vertexTriangles[faceCursor[e.first]++]=e.second;
    }

    void buildChunks(){
        for(auto& c:chunks)c.release(); chunks.clear();
        if(indices.empty())return;
        // Spatial 3D bins keep chunk bounds local; cap at approximately 4k tris.
        struct CellKey { int x,y,z; bool operator==(const CellKey& b)const{return x==b.x&&y==b.y&&z==b.z;} };
        struct CellHash { size_t operator()(const CellKey& k)const{return (uint32_t)k.x*73856093u^(uint32_t)k.y*19349663u^(uint32_t)k.z*83492791u;} };
        std::unordered_map<CellKey,std::vector<uint32_t>,CellHash> bins;
        for(uint32_t t=0;t<indices.size()/3;t++){
            Vec3 c=(positions[indices[t*3]]+positions[indices[t*3+1]]+positions[indices[t*3+2]])/3.0f;
            CellKey key{(int)std::floor((c.x+1.001f)*2.0f),(int)std::floor((c.y+1.001f)*2.0f),(int)std::floor((c.z+1.001f)*2.0f)};
            bins[key].push_back(t);
        }
        for(auto& entry:bins){
            auto& list=entry.second;
            for(size_t begin=0;begin<list.size();begin+=4000){
                Chunk chunk; size_t end=std::min(list.size(),begin+4000);
                chunk.triangles.assign(list.begin()+begin,list.begin()+end);
                for(uint32_t t:chunk.triangles)for(int k=0;k<3;k++)chunk.bounds.add(positions[indices[t*3+k]]);
                chunks.push_back(std::move(chunk));
            }
        }
    }

    void buildBVH(){
        bvh.clear();triangleOrder.resize(indices.size()/3);
        std::iota(triangleOrder.begin(),triangleOrder.end(),0u);
        if(!triangleOrder.empty())buildNode(0,(uint32_t)triangleOrder.size());
    }
    AABB triangleBounds(uint32_t t)const{AABB b;for(int k=0;k<3;k++)b.add(positions[indices[t*3+k]]);return b;}
    Vec3 triangleCenter(uint32_t t)const{return (positions[indices[t*3]]+positions[indices[t*3+1]]+positions[indices[t*3+2]])/3.0f;}
    int buildNode(uint32_t first,uint32_t count){
        int id=(int)bvh.size();bvh.emplace_back(); AABB bounds,centers;
        for(uint32_t i=first;i<first+count;i++){uint32_t t=triangleOrder[i];bounds.merge(triangleBounds(t));centers.add(triangleCenter(t));}
        bvh[id].bounds=bounds;bvh[id].first=first;bvh[id].count=count;
        if(count<=8)return id;
        Vec3 e=centers.hi-centers.lo;int axis=(e.y>e.x)?1:0;if((axis==0?e.x:e.y)<e.z)axis=2;
        uint32_t mid=first+count/2;
        auto coord=[&](uint32_t t){Vec3 c=triangleCenter(t);return axis==0?c.x:(axis==1?c.y:c.z);};
        std::nth_element(triangleOrder.begin()+first,triangleOrder.begin()+mid,triangleOrder.begin()+first+count,[&](uint32_t a,uint32_t b){return coord(a)<coord(b);});
        int l=buildNode(first,mid-first),r=buildNode(mid,first+count-mid);
        bvh[id].left=l;bvh[id].right=r;bvh[id].count=0;return id;
    }
    void refitBVH(){
        for(int i=(int)bvh.size()-1;i>=0;--i){
            auto& n=bvh[(size_t)i];n.bounds=AABB{};
            if(n.leaf()){for(uint32_t j=n.first;j<n.first+n.count;j++)n.bounds.merge(triangleBounds(triangleOrder[j]));}
            else {n.bounds.merge(bvh[n.left].bounds);n.bounds.merge(bvh[n.right].bounds);}
        }
    }
    static bool rayTriangle(const Vec3& o,const Vec3& d,const Vec3& a,const Vec3& b,const Vec3& c,float& t,float& u,float& v){
        Vec3 e1=b-a,e2=c-a,p=cross(d,e2);float det=dot(e1,p);if(std::fabs(det)<1e-8f)return false;
        float inv=1.0f/det;Vec3 s=o-a;u=dot(s,p)*inv;if(u<0||u>1)return false;
        Vec3 q=cross(s,e1);v=dot(d,q)*inv;if(v<0||u+v>1)return false;
        t=dot(e2,q)*inv;return t>0.0001f;
    }
    Hit raycast(const Vec3& origin,const Vec3& direction)const{
        Hit hit;if(bvh.empty())return hit;
        std::array<int,128> stack{};int top=0;stack[top++]=0;float nearest=std::numeric_limits<float>::max();
        while(top){int ni=stack[--top];const auto& node=bvh[(size_t)ni];if(!node.bounds.hit(origin,direction,nearest))continue;
            if(node.leaf())for(uint32_t j=node.first;j<node.first+node.count;j++){
                uint32_t t=triangleOrder[j],a=indices[t*3],b=indices[t*3+1],c=indices[t*3+2];float d,u,v;
                if(rayTriangle(origin,direction,positions[a],positions[b],positions[c],d,u,v)&&d<nearest){
                    nearest=d;hit.found=true;hit.distance=d;hit.point=origin+direction*d;
                    hit.normal=normalize(normals[a]*(1-u-v)+normals[b]*u+normals[c]*v);hit.triangle=t;
                }
            }else{if(top+2<128){stack[top++]=node.left;stack[top++]=node.right;}}
        }return hit;
    }
    struct Brush {
        float radius=0.18f, strength=0.12f, hardness=0.5f, pressure=1.0f;
        int type=0;
        int symmetryAxis=0;
        bool subtract=false;
    };
    struct WeightedVertex { uint32_t index=0; float weight=0.0f; bool mirrored=false; };
    std::vector<WeightedVertex> grabVertices;
    Vec3 grabLastCenter{};
    bool grabCaptured=false;

    static float smoothStep(float x) {
        x=std::clamp(x,0.0f,1.0f);
        return x*x*(3.0f-2.0f*x);
    }
    static bool sphereIntersects(const AABB& b,const Vec3& p,float r) {
        float dx=std::max(std::max(b.lo.x-p.x,0.0f),p.x-b.hi.x);
        float dy=std::max(std::max(b.lo.y-p.y,0.0f),p.y-b.hi.y);
        float dz=std::max(std::max(b.lo.z-p.z,0.0f),p.z-b.hi.z);
        return dx*dx+dy*dy+dz*dz<=r*r;
    }
    void queryVertices(const Vec3& center,float radius,float hardness,std::vector<WeightedVertex>& out) const {
        out.clear();
        if(bvh.empty()||radius<=0.0f)return;
        std::vector<uint8_t> seen(positions.size(),0);
        std::array<int,128> stack{};int top=0;stack[top++]=0;
        const float r2=radius*radius;
        while(top) {
            int ni=stack[--top];const BVHNode& node=bvh[(size_t)ni];
            if(!sphereIntersects(node.bounds,center,radius))continue;
            if(node.leaf()) {
                for(uint32_t j=node.first;j<node.first+node.count;j++) {
                    uint32_t t=triangleOrder[j];
                    for(int k=0;k<3;k++) {
                        uint32_t v=indices[t*3+k];if(v>=positions.size()||seen[v])continue;
                        seen[v]=1;float d2=dot(positions[v]-center,positions[v]-center);
                        if(d2>r2)continue;
                        float falloff=1.0f-std::sqrt(d2)/radius;
                        float w=std::pow(smoothStep(falloff),1.0f+std::clamp(hardness,0.0f,1.0f)*7.0f);
                        if(w>0.00001f)out.push_back({v,w});
                    }
                }
            } else if(top+2<128) {stack[top++]=node.left;stack[top++]=node.right;}
        }
    }
    void beginStroke() { grabVertices.clear();grabCaptured=false;grabLastCenter=Vec3(); }
    void endStroke() { grabVertices.clear();grabCaptured=false; }

    bool applyDab(const Vec3& center,const Vec3& hitNormal,const Vec3& dragDelta,const Brush& input) {
        if(positions.empty()||indices.empty())return false;
        Brush b=input;
        b.radius=std::clamp(b.radius,0.02f,1.0f);
        b.strength=std::clamp(b.strength,0.0f,1.0f);
        b.hardness=std::clamp(b.hardness,0.0f,1.0f);
        b.pressure=std::clamp(b.pressure,0.0f,1.0f);
        if(b.strength<=0.0f||b.pressure<=0.0f)return false;
        auto applyOne=[&](const Vec3& c,const Vec3& hn,const Vec3& movement)->bool {
            std::vector<WeightedVertex> affected;
            queryVertices(c,b.radius,b.hardness,affected);
            if(affected.empty())return false;
            Vec3 averageNormal,averagePosition;
            float totalWeight=0.0f;
            for(const auto& item:affected) {
                averageNormal=averageNormal+normals[item.index]*item.weight;
                averagePosition=averagePosition+positions[item.index]*item.weight;
                totalWeight+=item.weight;
            }
            averageNormal=normalize(averageNormal);
            if(length(averageNormal)<0.0001f)averageNormal=normalize(hn);
            if(totalWeight>0.0001f)averagePosition=averagePosition/totalWeight;else averagePosition=c;
            const float signedStrength=b.strength*b.pressure*(b.subtract?-1.0f:1.0f);
            bool changed=false;
            if(b.type==2) {
                if(!grabCaptured) {
                    grabVertices=affected;grabLastCenter=c;grabCaptured=true;
                } else {
                    for(const auto& item:grabVertices) {
                        float protection=1.0f-std::clamp(mask[item.index],0.0f,1.0f);
                        Vec3 move=movement;
                        if(item.mirrored) {
                            if(b.symmetryAxis==0)move.x=-move.x;
                            if(b.symmetryAxis==1)move.y=-move.y;
                            if(b.symmetryAxis==2)move.z=-move.z;
                        }
                        Vec3 delta=move*(item.weight*protection);
                        if(length(delta)>1e-7f){positions[item.index]=positions[item.index]+delta;changed=true;}
                    }
                    grabLastCenter=c;
                }
            } else {
                for(const auto& item:affected) {
                    uint32_t i=item.index;float w=item.weight;
                    float protection=1.0f-std::clamp(mask[i],0.0f,1.0f);
                    Vec3 p=positions[i],ownNormal=normalize(normals[i]);
                    if(b.type==7) {
                        float before=mask[i];mask[i]=std::clamp(before+b.strength*b.pressure*w,0.0f,1.0f);
                        changed=changed||mask[i]!=before;continue;
                    }
                    if(b.type==11) {
                        float before=mask[i];mask[i]=std::max(0.0f,before-b.strength*b.pressure*w);
                        changed=changed||mask[i]!=before;continue;
                    }
                    if(protection<=0.0f)continue;
                    const float amount=signedStrength*w*protection;
                    switch(b.type) {
                        case 0: p=p+averageNormal*(amount*b.radius*0.1f);break;
                        case 1: {
                            Vec3 sum;uint32_t count=0;
                            for(uint32_t k=adjacencyOffsets[i];k<adjacencyOffsets[i+1];k++){sum=sum+positions[adjacency[k]];++count;}
                            if(count){Vec3 avg=sum/(float)count;p=p+(avg-p)*(std::fabs(amount)*0.5f);}
                            break;
                        }
                        case 3: p=p+ownNormal*(amount*b.radius*0.1f);break;
                        case 4: {
                            Vec3 lineStart=c-movement;
                            Vec3 axis=c-lineStart;float den=dot(axis,axis);
                            Vec3 closest=den>1e-8f?lineStart+axis*std::clamp(dot(p-lineStart,axis)/den,0.0f,1.0f):c;
                            p=p+normalize(closest-p)*(std::fabs(amount)*b.radius*0.45f);break;
                        }
                        case 5: p=p-averageNormal*(dot(p-averagePosition,averageNormal)*std::fabs(amount));break;
                        case 6: {
                            p=p-averageNormal*(std::fabs(amount)*b.radius*0.1f);
                            p=p+normalize(c-p)*(std::fabs(amount)*b.radius*0.08f);break;
                        }
                        case 8: {
                            p=p-averageNormal*(dot(p-c,averageNormal)*std::fabs(amount));
                            p=p+averageNormal*(amount*b.radius*0.1f);break;
                        }
                        case 9: p=p-averageNormal*(dot(p-averagePosition,averageNormal)*std::fabs(amount));break;
                        case 10: p=p+averageNormal*(std::max(0.0f,dot(c-p,averageNormal))*std::fabs(amount));break;
                    }
                    if(length(p-positions[i])>1e-7f){positions[i]=p;changed=true;}
                }
            }
            if(changed&&b.type!=7&&b.type!=11) {
                std::vector<uint8_t> touched(positions.size(),0);
                if(b.type==2) {
                    for(const auto& item:grabVertices)touched[item.index]=1;
                    for(const auto& item:grabVertices)for(uint32_t k=adjacencyOffsets[item.index];k<adjacencyOffsets[item.index+1];k++)touched[adjacency[k]]=1;
                } else {
                    for(const auto& item:affected)touched[item.index]=1;
                    for(const auto& item:affected)for(uint32_t k=adjacencyOffsets[item.index];k<adjacencyOffsets[item.index+1];k++)touched[adjacency[k]]=1;
                }
                for(size_t i=0;i<touched.size();i++)if(touched[i]) {
                    Vec3 sum;
                    for(uint32_t q=faceOffsets[i];q<faceOffsets[i+1];q++) {
                        uint32_t tri=vertexTriangles[q],a=indices[tri*3],bb=indices[tri*3+1],cc=indices[tri*3+2];
                        sum=sum+cross(positions[bb]-positions[a],positions[cc]-positions[a]);
                    }
                    if(length(sum)>1e-8f)normals[i]=normalize(sum);
                }
                refitBVH();
                for(auto& chunk:chunks) {
                    bool dirty=false;
                    for(uint32_t tri:chunk.triangles)if(touched[indices[tri*3]]||touched[indices[tri*3+1]]||touched[indices[tri*3+2]]){dirty=true;break;}
                    if(dirty)chunk.dirty=true;
                }
            }
            return changed;
        };
        bool wasGrabCaptured=grabCaptured;
        bool changed=applyOne(center,hitNormal,dragDelta);
        if(b.symmetryAxis>=0&&b.symmetryAxis<=2) {
            Vec3 mirrored=center,mirroredNormal=hitNormal,mirroredDelta=dragDelta;
            if(b.symmetryAxis==0){mirrored.x=-mirrored.x;mirroredNormal.x=-mirroredNormal.x;mirroredDelta.x=-mirroredDelta.x;}
            if(b.symmetryAxis==1){mirrored.y=-mirrored.y;mirroredNormal.y=-mirrored.y;mirroredDelta.y=-mirroredDelta.y;}
            if(b.symmetryAxis==2){mirrored.z=-mirrored.z;mirroredNormal.z=-mirroredNormal.z;mirroredDelta.z=-mirroredDelta.z;}
            if(length(mirrored-center)>0.0001f) {
                if(b.type==2) {
                    if(!wasGrabCaptured) {
                        std::vector<WeightedVertex> mirroredAffected;
                        queryVertices(mirrored,b.radius,b.hardness,mirroredAffected);
                        for(const auto& item:mirroredAffected) {
                            bool exists=false;
                            for(const auto& oldItem:grabVertices)if(oldItem.index==item.index){exists=true;break;}
                            if(!exists){WeightedVertex mirroredItem=item;mirroredItem.mirrored=true;grabVertices.push_back(mirroredItem);}
                        }
                        grabCaptured=true;grabLastCenter=center;
                    }
                } else changed=applyOne(mirrored,mirroredNormal,mirroredDelta)||changed;
            }
        }
        return changed;
    }

    void recalculateNormals(){
        normals.assign(positions.size(),Vec3());
        for(size_t i=0;i+2<indices.size();i+=3){uint32_t a=indices[i],b=indices[i+1],c=indices[i+2];Vec3 n=cross(positions[b]-positions[a],positions[c]-positions[a]);normals[a]=normals[a]+n;normals[b]=normals[b]+n;normals[c]=normals[c]+n;}
        for(auto& n:normals)n=normalize(n);
        refitBVH();
    }
    bool toMesh(Mesh& out)const{
        if(positions.empty()||indices.empty()||positions.size()!=normals.size())return false;
        std::vector<Vertex> v;v.reserve(positions.size());
        for(size_t i=0;i<positions.size();++i)v.emplace_back(positions[i],normals[i],0.0f,0.0f);
        out.setData(std::move(v),indices);return out.isValid();
    }
    void releaseGpu(){for(auto& c:chunks)c.release();}
    bool uploadChunks(){
        for(auto& c:chunks){
            if(c.vao&&!c.dirty)continue;
            std::vector<Vertex> verts;std::vector<uint32_t> inds;verts.reserve(c.triangles.size()*3);inds.reserve(c.triangles.size()*3);
            for(uint32_t t:c.triangles)for(int k=0;k<3;k++){uint32_t src=indices[t*3+k];uint32_t dst=(uint32_t)verts.size();verts.emplace_back(positions[src],normals[src],0,0);inds.push_back(dst);}
            bool fresh=!c.vao;
            if(fresh){glGenVertexArrays(1,&c.vao);glGenBuffers(1,&c.vbo);glGenBuffers(1,&c.ibo);}
            glBindVertexArray(c.vao);glBindBuffer(GL_ARRAY_BUFFER,c.vbo);
            if(fresh){glBufferData(GL_ARRAY_BUFFER,verts.size()*sizeof(Vertex),nullptr,GL_DYNAMIC_DRAW);glBufferSubData(GL_ARRAY_BUFFER,0,verts.size()*sizeof(Vertex),verts.data());}
            else if(c.dirty)glBufferSubData(GL_ARRAY_BUFFER,0,verts.size()*sizeof(Vertex),verts.data());
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)offsetof(Vertex,position));
            glEnableVertexAttribArray(1);glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)offsetof(Vertex,normal));
            glEnableVertexAttribArray(2);glVertexAttribPointer(2,2,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)offsetof(Vertex,u));
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,c.ibo);
            if(fresh)glBufferData(GL_ELEMENT_ARRAY_BUFFER,inds.size()*sizeof(uint32_t),inds.data(),GL_STATIC_DRAW);
            c.allocated=true;c.indexCount=(GLsizei)inds.size();c.dirty=false;
        }
        glBindVertexArray(0);return true;
    }
private:
    void moveFrom(SculptMesh&& o){positions=std::move(o.positions);normals=std::move(o.normals);indices=std::move(o.indices);mask=std::move(o.mask);adjacencyOffsets=std::move(o.adjacencyOffsets);adjacency=std::move(o.adjacency);faceOffsets=std::move(o.faceOffsets);vertexTriangles=std::move(o.vertexTriangles);chunks=std::move(o.chunks);bvh=std::move(o.bvh);triangleOrder=std::move(o.triangleOrder);o.chunks.clear();}
};
}
