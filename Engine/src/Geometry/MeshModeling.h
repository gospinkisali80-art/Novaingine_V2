#pragma once
#include "Mesh.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <unordered_map>
#include <vector>
#include <utility>

namespace nova {
class MeshModeling {
    static Vertex midpoint(const Vertex& a, const Vertex& b) {
        return Vertex((a.position+b.position)*0.5f, normalize(a.normal+b.normal),
                      (a.u+b.u)*0.5f, (a.v+b.v)*0.5f);
    }
    static Vec3 faceNormal(const Vertex& a,const Vertex& b,const Vertex& c) {
        return normalize(cross(b.position-a.position,c.position-a.position));
    }
public:
    static bool extrudeFace(Mesh& mesh,size_t face,float distance) {
        if(!mesh.isValid() || face>=mesh.triangleCount() || !std::isfinite(distance)) return false;
        auto v=mesh.getVertices(); auto ix=mesh.getIndices();
        size_t k=face*3; uint32_t a=ix[k],b=ix[k+1],c=ix[k+2];
        Vec3 n=faceNormal(v[a],v[b],v[c]);
        if(length(n)<0.001f) return false;
        uint32_t base=(uint32_t)v.size();
        for(uint32_t old:{a,b,c}) { Vertex q=v[old]; q.position=q.position+n*distance; v.push_back(q); }
        // Keep the original base face and add a raised cap plus three side walls.
        uint32_t A=base,B=base+1,C=base+2;
        ix.insert(ix.end(),{A,C,B, a,b,B, a,B,A, b,c,C, b,C,B, c,a,A, c,A,C});
        mesh.setData(std::move(v),std::move(ix)); mesh.recalculateNormals(); return mesh.isValid();
    }
    static bool insetFace(Mesh& mesh,size_t face,float amount) {
        if(!mesh.isValid() || face>=mesh.triangleCount() || amount<=0.0f || amount>=0.95f) return false;
        auto v=mesh.getVertices(); auto ix=mesh.getIndices(); size_t k=face*3;
        uint32_t a=ix[k],b=ix[k+1],c=ix[k+2];
        Vec3 center=(v[a].position+v[b].position+v[c].position)/3.0f;
        uint32_t base=(uint32_t)v.size();
        for(uint32_t old:{a,b,c}) { Vertex q=v[old]; q.position=center+(q.position-center)*(1.0f-amount); v.push_back(q); }
        uint32_t A=base,B=base+1,C=base+2;
        ix[k]=A; ix[k+1]=B; ix[k+2]=C;
        ix.insert(ix.end(),{a,b,B,a,B,A, b,c,C,b,C,B, c,a,A,c,A,C});
        mesh.setData(std::move(v),std::move(ix)); mesh.recalculateNormals(); return mesh.isValid();
    }
    static bool bevelEdge(Mesh& mesh,size_t edge,float width) {
        if(!mesh.isValid() || edge>=mesh.triangleCount()*3 || width<=0.0f) return false;
        auto v=mesh.getVertices(); auto ix=mesh.getIndices();
        size_t tri=(edge/3), local=edge%3, k=tri*3;
        uint32_t a=ix[k+local], b=ix[k+(local+1)%3];
        Vertex m=midpoint(v[a],v[b]);
        Vec3 center=(v[a].position+v[b].position)*0.5f;
        Vec3 toward=normalize((v[a].normal+v[b].normal));
        m.position=center+toward*std::min(width,0.5f);
        uint32_t mid=(uint32_t)v.size(); v.push_back(m);
        std::vector<uint32_t> out; out.reserve(ix.size()+12);
        for(size_t i=0;i<ix.size();i+=3) {
            uint32_t p=ix[i],q=ix[i+1],r=ix[i+2];
            bool split=false;
            for(int j=0;j<3;j++) {
                uint32_t x=ix[i+j],y=ix[i+(j+1)%3];
                if((x==a&&y==b)||(x==b&&y==a)) {
                    uint32_t z=ix[i+(j+2)%3];
                    out.insert(out.end(),{x,mid,z,mid,y,z}); split=true; break;
                }
            }
            if(!split) out.insert(out.end(),{p,q,r});
        }
        mesh.setData(std::move(v),std::move(out)); mesh.recalculateNormals(); return mesh.isValid();
    }
    static bool loopCut(Mesh& mesh) { return subdivide(mesh,1); }
    static bool subdivide(Mesh& mesh,int levels) {
        if(!mesh.isValid() || levels<1 || levels>3) return false;
        for(int level=0;level<levels;level++) {
            if(mesh.triangleCount()>75000) return false;
            auto v=mesh.getVertices(); const auto ix=mesh.getIndices();
            std::unordered_map<uint64_t,uint32_t> mids;
            auto getMid=[&](uint32_t a,uint32_t b)->uint32_t {
                uint32_t lo=std::min(a,b),hi=std::max(a,b);
                uint64_t key=(uint64_t(lo)<<32)|hi;
                auto it=mids.find(key); if(it!=mids.end()) return it->second;
                uint32_t id=(uint32_t)v.size(); v.push_back(midpoint(v[a],v[b])); mids[key]=id; return id;
            };
            std::vector<uint32_t> out; out.reserve(ix.size()*4);
            for(size_t i=0;i<ix.size();i+=3) {
                uint32_t a=ix[i],b=ix[i+1],c=ix[i+2];
                uint32_t ab=getMid(a,b),bc=getMid(b,c),ca=getMid(c,a);
                out.insert(out.end(),{a,ab,ca,ab,b,bc,ca,bc,c,ab,bc,ca});
            }
            mesh.setData(std::move(v),std::move(out)); mesh.recalculateNormals();
            if(mesh.vertexCount()>180000 || mesh.triangleCount()>300000) return false;
        }
        return mesh.isValid();
    }
    static bool mergeNearby(Mesh& mesh,float tolerance) {
        if(!mesh.isValid() || tolerance<=0.0f || mesh.vertexCount()>12000) return false;
        auto v=mesh.getVertices(); const auto ix=mesh.getIndices();
        std::vector<uint32_t> remap(v.size());
        for(size_t i=0;i<v.size();i++) {
            remap[i]=(uint32_t)i;
            for(size_t j=0;j<i;j++) if(length(v[i].position-v[j].position)<=tolerance) { remap[i]=remap[j]; break; }
        }
        std::vector<Vertex> outV; std::vector<uint32_t> map(v.size());
        for(size_t i=0;i<v.size();i++) {
            uint32_t root=remap[i];
            if(root==(uint32_t)i) { map[i]=(uint32_t)outV.size(); outV.push_back(v[i]); }
            else map[i]=map[root];
        }
        std::vector<uint32_t> outI; outI.reserve(ix.size());
        for(size_t i=0;i<ix.size();i+=3) {
            uint32_t a=map[ix[i]],b=map[ix[i+1]],c=map[ix[i+2]];
            if(a!=b&&b!=c&&a!=c) outI.insert(outI.end(),{a,b,c});
        }
        if(outI.empty()) return false;
        mesh.setData(std::move(outV),std::move(outI)); mesh.recalculateNormals(); return mesh.isValid();
    }
    static bool knifeTriangle(Mesh& mesh,size_t face) {
        // A deterministic face split primitive: subdivides the chosen triangle into four
        // triangles using edge midpoints. It is a real topology cut, not a visual overlay.
        if(!mesh.isValid() || face>=mesh.triangleCount()) return false;
        auto v=mesh.getVertices(); auto ix=mesh.getIndices(); size_t k=face*3;
        uint32_t a=ix[k],b=ix[k+1],c=ix[k+2];
        uint32_t ab=(uint32_t)v.size(); v.push_back(midpoint(v[a],v[b]));
        uint32_t bc=(uint32_t)v.size(); v.push_back(midpoint(v[b],v[c]));
        uint32_t ca=(uint32_t)v.size(); v.push_back(midpoint(v[c],v[a]));
        ix.erase(ix.begin()+k,ix.begin()+k+3);
        ix.insert(ix.begin()+k,{a,ab,ca,ab,b,bc,ca,bc,c,ab,bc,ca});
        mesh.setData(std::move(v),std::move(ix)); mesh.recalculateNormals(); return mesh.isValid();
    }
};
}
