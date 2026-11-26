package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Draggable node canvas with editable output-to-input links. */
public final class Meshy3DNodeGraphView extends View {
    public interface OnLinkListener { void onLink(int from,int to); }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String[] nodes=new String[0];
    private int[][] links=new int[0][];
    private float[] xs=new float[0],ys=new float[0];
    private int active=-1,linkStart=-1;
    private float lastX,lastY,pointerX,pointerY;
    private OnLinkListener linkListener;
    public Meshy3DNodeGraphView(Context context){super(context);setBackgroundColor(0xFF171C23);}
    public void setOnLinkListener(OnLinkListener listener){linkListener=listener;}
    public void setNodes(String[] value){
        nodes=value==null?new String[0]:value.clone();xs=new float[nodes.length];ys=new float[nodes.length];
        for(int i=0;i<nodes.length;i++){xs[i]=24+(i%3)*190;ys[i]=35+(i/3)*125;}invalidate();
    }
    public void setLinks(int[][] value){links=value==null?new int[0][]:value.clone();invalidate();}
    private int hit(float x,float y){for(int i=nodes.length-1;i>=0;i--)if(x>=xs[i]&&x<=xs[i]+158&&y>=ys[i]&&y<=ys[i]+76)return i;return -1;}
    private int inputHit(float x,float y){for(int i=nodes.length-1;i>=0;i--){float dx=x-xs[i],dy=y-(ys[i]+38);if(dx*dx+dy*dy<18*18)return i;}return -1;}
    private int outputHit(float x,float y){for(int i=nodes.length-1;i>=0;i--){float dx=x-(xs[i]+158),dy=y-(ys[i]+38);if(dx*dx+dy*dy<18*18)return i;}return -1;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);p.setColor(0xFF252D37);p.setStrokeWidth(1);
        for(int x=0;x<getWidth();x+=24)c.drawLine(x,0,x,getHeight(),p);
        for(int y=0;y<getHeight();y+=24)c.drawLine(0,y,getWidth(),y,p);
        p.setColor(0xFF72A9D8);p.setStrokeWidth(3);
        for(int[] link:links)if(link.length==2&&link[0]>=0&&link[0]<nodes.length&&link[1]>=0&&link[1]<nodes.length)
            drawWire(c,xs[link[0]]+158,ys[link[0]]+38,xs[link[1]],ys[link[1]]+38);
        if(linkStart>=0)drawWire(c,xs[linkStart]+158,ys[linkStart]+38,pointerX,pointerY);
        for(int i=0;i<nodes.length;i++){
            p.setStyle(Paint.Style.FILL);p.setColor(i==active?0xFF455B70:0xFF303B48);
            c.drawRoundRect(xs[i],ys[i],xs[i]+158,ys[i]+76,8,8,p);
            p.setColor(0xFF8AB4DD);c.drawRect(xs[i],ys[i],xs[i]+158,ys[i]+22,p);
            p.setColor(0xFFF0F3F6);p.setTextSize(12);c.drawText(nodes[i],xs[i]+9,ys[i]+43,p);
            p.setColor(0xFF9EABB8);p.setTextSize(9);c.drawText("INPUT       OUTPUT",xs[i]+9,ys[i]+62,p);
            p.setColor(0xFF58C5A5);c.drawCircle(xs[i],ys[i]+38,6,p);
            p.setColor(0xFF72A9D8);c.drawCircle(xs[i]+158,ys[i]+38,6,p);
        }
        p.setColor(0xFFCBD5DF);p.setTextSize(11);
        c.drawText("DRAG NODES  •  CONNECT OUTPUT TO INPUT",12,getHeight()-12,p);
    }
    private void drawWire(Canvas c,float x1,float y1,float x2,float y2){
        Path wire=new Path();wire.moveTo(x1,y1);wire.cubicTo(x1+38,y1,x2-38,y2,x2,y2);c.drawPath(wire,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            pointerX=e.getX();pointerY=e.getY();linkStart=outputHit(e.getX(),e.getY());
            if(linkStart<0){active=hit(e.getX(),e.getY());lastX=e.getX();lastY=e.getY();}
            invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
            pointerX=e.getX();pointerY=e.getY();
            if(linkStart<0&&active>=0){xs[active]+=e.getX()-lastX;ys[active]+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();}
            invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP){
            if(linkStart>=0){int target=inputHit(e.getX(),e.getY());if(target>=0&&linkListener!=null)linkListener.onLink(linkStart,target);}
            linkStart=-1;active=-1;invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){linkStart=-1;active=-1;return true;}
        return true;
    }
}