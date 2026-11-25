#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace nova {
class LandscapeSystem {
public:
    struct Layer {
        std::string name;
        std::vector<float> heightDelta;
        std::vector<std::array<float,4>> paintDelta;
        float heightAlpha=1.0f, paintAlpha=1.0f;
        bool visible=true, locked=false;
    };
    struct SplinePoint { float x=0,z=0,width=4; };
    struct Chunk { int x=0,z=0,quads=32,lod=0; bool loaded=false; };

    bool initialize(int w,int h,float spacing,float ox,float oz) {
        if(w<3||h<3||w>4097||h>4097||spacing<=0)return false;
        width_=w;height_=h;spacing_=spacing;originX_=ox;originZ_=oz;
        size_t n=static_cast<size_t>(w)*h;
        baseHeight_.assign(n,0);baseWeights_.assign(n,{1,0,0,0});
        holes_.assign(static_cast<size_t>(w-1)*(h-1),0);
        layers_.clear();splines_.clear();addLayer("Base Sculpt");buildChunks(32);return true;
    }
    bool valid()const{return width_>=3&&height_>=3&&baseHeight_.size()==static_cast<size_t>(width_)*height_;}
    int width()const{return width_;} int height()const{return height_;}
    float spacing()const{return spacing_;} float originX()const{return originX_;} float originZ()const{return originZ_;}
    const std::vector<Layer>& layers()const{return layers_;}
    const std::vector<float>& baseHeights() const { return baseHeight_; }
    const std::vector<std::array<float,4>>& baseWeights() const { return baseWeights_; }
    bool restoreLayers(const std::vector<Layer>& restored) {
        if (!valid() || restored.empty() || restored.size() > 32) return false;
        for (const Layer& layer : restored) {
            if (layer.heightDelta.size() != baseHeight_.size() ||
                layer.paintDelta.size() != baseHeight_.size()) return false;
        }
        layers_ = restored;
        active_ = static_cast<int>(layers_.size()) - 1;
        return true;
    }
    bool restoreSplines(const std::vector<std::vector<SplinePoint>>& restored) {
        if (!valid() || restored.size() > 4096) return false;
        for (const auto& spline : restored) {
            if (spline.size() < 2 || spline.size() > 4096) return false;
            for (const auto& point : spline)
                if (!std::isfinite(point.x) || !std::isfinite(point.z) ||
                    !std::isfinite(point.width) || point.width < 0.5f || point.width > 32.0f)
                    return false;
        }
        splines_ = restored;
        return true;
    }
    const std::vector<Chunk>& chunks()const{return chunks_;}
    const std::vector<uint8_t>& holes()const{return holes_;}
    const std::vector<std::vector<SplinePoint>>& splines()const{return splines_;}
    bool setBaseHeights(const std::vector<float>& h){if(!valid()||h.size()!=baseHeight_.size())return false;baseHeight_=h;return true;}
    bool setBaseWeights(const std::vector<std::array<float,4>>& w){if(!valid()||w.size()!=baseWeights_.size())return false;baseWeights_=w;for(auto& value:baseWeights_)normalize(value);return true;}
    std::vector<float> heights()const{
        auto out=baseHeight_;for(const auto& l:layers_)if(l.visible&&l.heightDelta.size()==out.size())
            for(size_t i=0;i<out.size();++i)out[i]+=l.heightDelta[i]*l.heightAlpha;return out;
    }
    std::vector<std::array<float,4>> weights()const{
        auto out=baseWeights_;for(const auto& l:layers_)if(l.visible&&l.paintDelta.size()==out.size())
            for(size_t i=0;i<out.size();++i)for(int c=0;c<4;++c)out[i][c]+=l.paintDelta[i][c]*l.paintAlpha;
        for(auto& w:out)normalize(w);return out;
    }
    int addLayer(const std::string& name){if(!valid()||layers_.size()>=32)return -1;Layer l;l.name=name.empty()?"Landscape Layer":name;l.heightDelta.assign(baseHeight_.size(),0);l.paintDelta.assign(baseHeight_.size(),{0,0,0,0});layers_.push_back(std::move(l));active_=static_cast<int>(layers_.size())-1;return active_;}
    bool selectLayer(int i){if(i<0||i>=static_cast<int>(layers_.size()))return false;active_=i;return true;}
    bool setLayerState(int i,bool visible,bool locked,float hAlpha,float pAlpha){if(i<0||i>=static_cast<int>(layers_.size()))return false;auto& l=layers_[i];l.visible=visible;l.locked=locked;l.heightAlpha=std::clamp(hAlpha,0.0f,1.0f);l.paintAlpha=std::clamp(pAlpha,0.0f,1.0f);return true;}
    bool moveLayer(int from,int to){if(from<0||to<0||from>=static_cast<int>(layers_.size())||to>=static_cast<int>(layers_.size()))return false;Layer l=std::move(layers_[from]);layers_.erase(layers_.begin()+from);layers_.insert(layers_.begin()+to,std::move(l));active_=to;return true;}
    bool deleteLayer(int i){if(i<0||i>=static_cast<int>(layers_.size())||layers_.size()==1)return false;layers_.erase(layers_.begin()+i);active_=std::clamp(active_,0,static_cast<int>(layers_.size())-1);return true;}
    bool sculpt(float wx,float wz,float radius,float strength,int mode){
        if(!valid()||active_<0)return false;auto& l=layers_[active_];if(l.locked)return false;
        auto h=heights();radius=std::max(radius,spacing_);bool changed=false;
        for(int z=0;z<height_;++z)for(int x=0;x<width_;++x){size_t i=idx(x,z);float dx=originX_+x*spacing_-wx,dz=originZ_+z*spacing_-wz,d=std::sqrt(dx*dx+dz*dz);if(d>radius)continue;float t=1-d/radius,f=t*t*(3-2*t),delta=0;
            if(mode==0)delta=strength*f;else if(mode==1)delta=-strength*f;
            else if(mode==2||mode==6){float sum=0;int n=0;for(int oz=-1;oz<=1;++oz)for(int ox=-1;ox<=1;++ox){int xx=x+ox,zz=z+oz;if(xx<0||zz<0||xx>=width_||zz>=height_)continue;sum+=h[idx(xx,zz)];++n;}if(n){float diff=h[i]-sum/n;if(mode==2)delta=-diff*std::clamp(std::fabs(strength),0.0f,1.0f)*f;else if(diff>0.1f)delta=-std::min(diff-0.1f,std::fabs(strength)*0.22f*f);}}
            else if(mode==3)delta=-h[i]*std::clamp(std::fabs(strength),0.0f,1.0f)*f;
            else if(mode==4)delta=std::sin((wx+dx)*2.37f)*std::cos((wz+dz)*1.91f)*strength*0.35f*f;
            else if(mode==5)delta=((wz+dz-wz)/radius*strength*2-h[i])*f;
            if(std::fabs(delta)>1e-6f){l.heightDelta[i]+=delta;changed=true;}
        }return changed;
    }
    bool paint(float wx,float wz,float radius,float strength,int material){
        if(!valid()||active_<0||material<0||material>3)return false;auto& l=layers_[active_];if(l.locked)return false;
        radius=std::max(radius,spacing_);strength=std::clamp(strength,0.0f,1.0f);auto old=weights();bool changed=false;
        for(int z=0;z<height_;++z)for(int x=0;x<width_;++x){size_t i=idx(x,z);float dx=originX_+x*spacing_-wx,dz=originZ_+z*spacing_-wz,d=std::sqrt(dx*dx+dz*dz);if(d>radius)continue;float t=1-d/radius,a=strength*t*t*(3-2*t);auto w=old[i];
            if(material==3)w[3]=std::clamp(w[3]+a,0.0f,1.0f);else{for(int c=0;c<3;++c)w[c]*=(1-a);w[material]+=a;float sum=w[0]+w[1]+w[2];if(sum>1e-6f)for(int c=0;c<3;++c)w[c]/=sum;}
            for(int c=0;c<4;++c){float dlt=w[c]-old[i][c];if(std::fabs(dlt)>1e-6f){l.paintDelta[i][c]+=dlt;changed=true;}}
        }return changed;
    }
    bool hydraulicErosion(int drops,float rain,float capacity,float erosion,float deposition,uint32_t seed=0x51A7u){
        if(!valid()||active_<0||layers_[active_].locked)return false;auto& l=layers_[active_];drops=std::clamp(drops,1,20000);auto h=heights();uint32_t s=seed?seed:1;
        auto rnd=[&](){s^=s<<13;s^=s>>17;s^=s<<5;return float(s&0xffffffu)/16777215.0f;};
        rain=std::clamp(rain,.001f,2.0f);capacity=std::clamp(capacity,.01f,8.0f);erosion=std::clamp(erosion,.001f,1.0f);deposition=std::clamp(deposition,.001f,1.0f);
        for(int d=0;d<drops;++d){float x=rnd()*(width_-1),z=rnd()*(height_-1),water=rain,sed=0,dx=0,dz=0;
            for(int step=0;step<32&&water>.01f;++step){int ix=std::clamp(int(x),0,width_-2),iz=std::clamp(int(z),0,height_-2);size_t i=idx(ix,iz);float best=h[i];int bx=ix,bz=iz;
                for(int oz=-1;oz<=1;++oz)for(int ox=-1;ox<=1;++ox){int xx=ix+ox,zz=iz+oz;if(xx<0||zz<0||xx>=width_||zz>=height_)continue;float v=h[idx(xx,zz)];if(v<best){best=v;bx=xx;bz=zz;}}
                if(bx==ix&&bz==iz){h[i]+=sed*deposition;break;}dx=.35f*dx+.65f*(bx-ix);dz=.35f*dz+.65f*(bz-iz);float slope=std::max(.001f,h[i]-best),carry=slope*water*capacity;
                if(sed>carry){float q=(sed-carry)*deposition;h[i]+=q;sed-=q;}else{float q=std::min((carry-sed)*erosion,h[i]-best);h[i]-=q;sed+=q;}
                x=std::clamp(x+dx,0.0f,float(width_-1));z=std::clamp(z+dz,0.0f,float(height_-1));water*=.94f;
            }
        }writeComposedToLayer(l,h);return true;
    }
    bool thermalErosion(int iterations,float talus,float rate){
        if(!valid()||active_<0||layers_[active_].locked)return false;auto& l=layers_[active_];iterations=std::clamp(iterations,1,256);talus=std::clamp(talus,.001f,10.0f);rate=std::clamp(rate,.001f,.5f);auto h=heights(),next=h;
        for(int it=0;it<iterations;++it){next=h;for(int z=1;z<height_-1;++z)for(int x=1;x<width_-1;++x){size_t i=idx(x,z);int xx[4]={x-1,x+1,x,x},zz[4]={z,z,z-1,z+1};for(int k=0;k<4;++k){size_t j=idx(xx[k],zz[k]);float excess=h[i]-h[j]-talus;if(excess>0){float q=excess*rate*.25f;next[i]-=q;next[j]+=q;}}}h.swap(next);}writeComposedToLayer(l,h);return true;
    }
    bool setHole(int x,int z,bool value){if(x<0||z<0||x>=width_-1||z>=height_-1)return false;holes_[static_cast<size_t>(z*(width_-1)+x)]=value?1:0;return true;}
    bool isHole(int x,int z)const{return x<0||z<0||x>=width_-1||z>=height_-1||holes_[static_cast<size_t>(z*(width_-1)+x)]!=0;}
    bool addSpline(const std::vector<SplinePoint>& p){
        if(p.size()<2||p.size()>4096||active_<0||layers_[active_].locked)return false;
        splines_.push_back(p);
        auto& layer=layers_[active_];
        auto h=heights();auto oldWeights=weights();
        for(size_t seg=1;seg<p.size();++seg){
            const auto&a=p[seg-1];const auto&b=p[seg];
            const float dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
            const float width=std::max(0.5f,(a.width+b.width)*0.5f);
            const int ax=std::clamp(int(std::round((a.x-originX_)/spacing_)),0,width_-1);
            const int az=std::clamp(int(std::round((a.z-originZ_)/spacing_)),0,height_-1);
            const int bx=std::clamp(int(std::round((b.x-originX_)/spacing_)),0,width_-1);
            const int bz=std::clamp(int(std::round((b.z-originZ_)/spacing_)),0,height_-1);
            const float roadHeight=(h[idx(ax,az)]+h[idx(bx,bz)])*0.5f;
            for(int z=0;z<height_;++z)for(int x=0;x<width_;++x){
                const float wx=originX_+x*spacing_,wz=originZ_+z*spacing_;
                const float t=len2>1e-6f?std::clamp(((wx-a.x)*dx+(wz-a.z)*dz)/len2,0.0f,1.0f):0.0f;
                const float px=a.x+dx*t,pz=a.z+dz*t;
                const float d=std::sqrt((wx-px)*(wx-px)+(wz-pz)*(wz-pz));
                if(d>width*1.5f)continue;
                const float blend=std::clamp(1.0f-d/(width*1.5f),0.0f,1.0f);
                const size_t i=idx(x,z);
                const float target=h[i]+(roadHeight-h[i])*blend;
                layer.heightDelta[i]+=target-h[i];h[i]=target;
                auto w=oldWeights[i];const float paint=blend*0.92f;
                for(int c=0;c<3;++c)w[c]*=(1.0f-paint);
                w[2]+=paint;float total=w[0]+w[1]+w[2];
                if(total>1e-6f)for(int c=0;c<3;++c)w[c]/=total;
                for(int c=0;c<4;++c)layer.paintDelta[i][c]+=w[c]-oldWeights[i][c];
            }
        }
        return true;
    }
    void buildChunks(int size){chunks_.clear();size=std::clamp(size,8,128);for(int z=0;z<height_-1;z+=size)for(int x=0;x<width_-1;x+=size){Chunk c;c.x=x;c.z=z;c.quads=std::min(size,std::min(width_-1-x,height_-1-z));chunks_.push_back(c);}}
    void setChunkState(size_t i,bool loaded,int lod){if(i<chunks_.size()){chunks_[i].loaded=loaded;chunks_[i].lod=std::clamp(lod,0,4);}}
private:
    size_t idx(int x,int z)const{return static_cast<size_t>(z*width_+x);}
    static void normalize(std::array<float,4>& w){for(float& v:w)v=std::max(0.0f,v);float s=w[0]+w[1]+w[2];if(s>1e-6f)for(int c=0;c<3;++c)w[c]/=s;else w={1,0,0,w[3]};w[3]=std::clamp(w[3],0.0f,1.0f);}
    void writeComposedToLayer(Layer& l,const std::vector<float>& h){for(size_t i=0;i<h.size();++i){float below=baseHeight_[i];for(const auto& layer:layers_){if(&layer==&l)break;if(layer.visible)below+=layer.heightDelta[i]*layer.heightAlpha;}l.heightDelta[i]=h[i]-below;}}
    int width_=0,height_=0,active_=0;float spacing_=1,originX_=0,originZ_=0;
    std::vector<float> baseHeight_;std::vector<std::array<float,4>> baseWeights_;std::vector<uint8_t> holes_;
    std::vector<Layer> layers_;std::vector<std::vector<SplinePoint>> splines_;std::vector<Chunk> chunks_;
};
} // namespace nova
