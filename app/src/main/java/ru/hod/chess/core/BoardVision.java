package ru.hod.chess.core;

import java.util.*;

/** Per-theme shape templates, trained on a verified initial board, no network. */
public final class BoardVision {
    private static final int N=24;
    public record Region(int left,int top,int size) { public boolean inside(int w,int h){return size>=160&&left>=0&&top>=0&&left+size<=w&&top+size<=h;} }
    public record Feature(float[] mask,float ink,float occupancy) {}
    public record Observation(char[] board,float confidence,int uncertain) {}
    private record Template(char piece,Feature feature) {}
    private final List<Template> templates=new ArrayList<>();
    private final boolean whiteBottom;
    public BoardVision(boolean whiteBottom){this.whiteBottom=whiteBottom;}
    public int templateCount(){return templates.size();}
    public void calibrate(int[] pixels,int w,int h,Region region) {
        if(!region.inside(w,h))throw new IllegalArgumentException("Область доски вне экрана");
        List<Template> pending=new ArrayList<>();Position start=Position.start();Set<Character> labels=new HashSet<>();
        for(int display=0;display<64;display++){
            int logical=whiteBottom?display:63-display;char piece=start.at(logical);Feature f=extract(pixels,w,region,display);
            if(piece!='.') {if(f.occupancy()<0.065f||f.occupancy()>0.85f)throw new IllegalArgumentException("Не видны все фигуры. Нужна начальная позиция и точные границы доски.");pending.add(new Template(piece,f));labels.add(piece);}
            else if(f.occupancy()>0.06f)throw new IllegalArgumentException("В центре доски есть фигуры или рамка выделена неточно");
        }
        if(labels.size()!=12)throw new IllegalArgumentException("Не удалось выделить 12 типов фигур");
        // Reject indistinguishable labels rather than giving confident false moves.
        for(int i=0;i<pending.size();i++)for(int j=i+1;j<pending.size();j++)if(pending.get(i).piece()!=pending.get(j).piece()&&distance(pending.get(i).feature(),pending.get(j).feature())<0.012f)throw new IllegalArgumentException("Шаблоны фигур неразличимы. Увеличь доску или смени тему фигур.");
        templates.clear();templates.addAll(pending);
    }
    public Observation recognize(int[] pixels,int w,int h,Region region){
        if(templates.isEmpty()||!region.inside(w,h))throw new IllegalStateException("Нужна калибровка");
        char[] board=new char[64];float confidence=1f;int uncertain=0;
        for(int display=0;display<64;display++){
            Feature f=extract(pixels,w,region,display);char best='.';float first=Float.MAX_VALUE,second=Float.MAX_VALUE;
            if(f.occupancy()<0.045f){board[whiteBottom?display:63-display]='.';continue;}
            Map<Character,Float> scores=new HashMap<>();for(Template t:templates)scores.merge(t.piece(),distance(f,t.feature()),Math::min);
            for(var s:scores.entrySet()){if(s.getValue()<first){second=first;first=s.getValue();best=s.getKey();}else if(s.getValue()<second)second=s.getValue();}
            float c=Math.min(1f,Math.min((second-first)/Math.max(0.018f,second),1f-first/0.30f));
            if(first>0.13f||c<0.16f){uncertain++;c=0f;}
            board[whiteBottom?display:63-display]=best;confidence=Math.min(confidence,Math.max(0f,c));
        }return new Observation(board,confidence,uncertain);
    }
    public static Feature extract(int[] pixels,int w,Region region,int square){
        float cell=region.size()/8f;int col=square%8,row=square/8;
        int x0=Math.round(region.left()+col*cell),y0=Math.round(region.top()+row*cell);
        // Median of inset corners ignores coordinates and most rounded piece shadows.
        int[] rs=new int[16],gs=new int[16],bs=new int[16];int k=0;
        for(float dy:new float[]{.10f,.18f,.82f,.90f})for(float dx:new float[]{.10f,.18f,.82f,.90f}){int px=pixels[(y0+Math.round(dy*cell))*w+x0+Math.round(dx*cell)];rs[k]=(px>>16)&255;gs[k]=(px>>8)&255;bs[k]=px&255;k++;}
        Arrays.sort(rs);Arrays.sort(gs);Arrays.sort(bs);int br=rs[8],bg=gs[8],bb=bs[8];float[] mask=new float[N*N];float ink=0,occupied=0;
        for(int y=0;y<N;y++)for(int x=0;x<N;x++){
            int xx=x0+Math.round((.12f+(x+.5f)/N*.76f)*cell),yy=y0+Math.round((.08f+(y+.5f)/N*.84f)*cell);int px=pixels[yy*w+xx];int r=(px>>16)&255,g=(px>>8)&255,b=px&255;
            float dist=(r-br)*(r-br)+(g-bg)*(g-bg)+(b-bb)*(b-bb);float m=Math.max(0f,Math.min(1f,(dist-900f)/2200f));mask[y*N+x]=m;occupied+=m;ink+=m*(.2126f*r+.7152f*g+.0722f*b)/255f;
        }return new Feature(mask,occupied>0?ink/occupied:0,occupied/(N*N));
    }
    private static float distance(Feature a,Feature b){float sum=0;for(int i=0;i<N*N;i++)sum+=Math.abs(a.mask()[i]-b.mask()[i]);return sum/(N*N)+Math.abs(a.ink()-b.ink())*.22f;}
}
