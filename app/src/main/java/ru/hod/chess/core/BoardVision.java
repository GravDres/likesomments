package ru.hod.chess.core;

import java.util.*;
import java.io.*;

/** Per-theme shape templates, trained on a verified initial board, no network. */
public final class BoardVision {
    private static final int N=24;
    public record Region(int left,int top,int size) { public boolean inside(int w,int h){return size>=160&&left>=0&&top>=0&&left+size<=w&&top+size<=h;} }
    public record Feature(float[] mask,float ink,float occupancy,float[] silhouette,float[] normalized) {
        public Feature(float[] mask,float ink,float occupancy){this(mask,ink,occupancy,canonical(mask),normalize(mask));}
    }
    public record Observation(char[] board,float confidence,int uncertain) {}
    private record Template(char piece,Feature feature) {}
    private final List<Template> templates=new ArrayList<>();
    private final boolean whiteBottom;
    public BoardVision(boolean whiteBottom){this.whiteBottom=whiteBottom;}
    public BoardVision withOrientation(boolean whiteBottom){BoardVision copy=new BoardVision(whiteBottom);copy.templates.addAll(templates);return copy;}
    public boolean whiteBottom(){return whiteBottom;}
    public int templateCount(){return templates.size();}
    void writeTo(DataOutput out)throws IOException{
        out.writeBoolean(whiteBottom);out.writeInt(templates.size());
        for(Template t:templates){out.writeChar(t.piece());out.writeFloat(t.feature().ink());out.writeFloat(t.feature().occupancy());for(float value:t.feature().mask())out.writeFloat(value);}
    }
    static BoardVision readFrom(DataInput in)throws IOException{
        BoardVision model=new BoardVision(in.readBoolean());int count=in.readInt();
        if(count<12||count>96)throw new IOException("Некорректные шаблоны фигур");
        Set<Character> kinds=new HashSet<>();
        for(int i=0;i<count;i++){
            char piece=in.readChar();if("pnbrqkPNBRQK".indexOf(piece)<0)throw new IOException("Некорректный шаблон");kinds.add(piece);
            float ink=unit(in.readFloat()),occupancy=unit(in.readFloat());float[] mask=new float[N*N];for(int k=0;k<mask.length;k++)mask[k]=unit(in.readFloat());
            model.templates.add(new Template(piece,new Feature(mask,ink,occupancy)));
        }
        if(kinds.size()!=12)throw new IOException("Нет всех типов фигур");return model;
    }
    private static float unit(float value)throws IOException{if(!Float.isFinite(value)||value<0||value>1)throw new IOException("Повреждённый шаблон");return value;}
    /** Only use labels explicitly confirmed by the user or by a legal stable observation. */
    public BoardVision learnConfirmed(int[] pixels,int w,int h,Region region,Position confirmed){
        if(!region.inside(w,h))throw new IllegalArgumentException("Доска вне экрана");
        BoardVision model=withOrientation(whiteBottom);Set<Character> labels=new HashSet<>();
        for(Template t:model.templates)labels.add(t.piece());
        for(int display=0;display<64;display++){
            char piece=confirmed.at(whiteBottom?display:63-display);if(piece=='.')continue;
            Feature feature=extract(pixels,w,region,display);
            if(feature.occupancy()<.045f||feature.occupancy()>.88f)throw new IllegalArgumentException("Проверь фигуру на "+Position.square(whiteBottom?display:63-display));
            model.templates.add(new Template(piece,feature));labels.add(piece);
        }
        if(labels.size()!=12)throw new IllegalArgumentException("Не хватает шаблонов всех фигур: сначала обучи тему на начальной доске");
        while(model.templates.size()>96){
            Map<Character,Integer> counts=new HashMap<>();for(Template t:model.templates)counts.merge(t.piece(),1,Integer::sum);
            for(int i=0;i<model.templates.size();i++)if(counts.get(model.templates.get(i).piece())>1){model.templates.remove(i);break;}
        }
        return model;
    }
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
        Arrays.sort(rs);Arrays.sort(gs);Arrays.sort(bs);int br=rs[8],bg=gs[8],bb=bs[8];float[] mask=new float[N*N];float ink=0,occupied=0;int foreground=0;
        for(int y=0;y<N;y++)for(int x=0;x<N;x++){
            int xx=x0+Math.round((.12f+(x+.5f)/N*.76f)*cell),yy=y0+Math.round((.08f+(y+.5f)/N*.84f)*cell);int px=pixels[yy*w+xx];int r=(px>>16)&255,g=(px>>8)&255,b=px&255;
            float dist=(r-br)*(r-br)+(g-bg)*(g-bg)+(b-bb)*(b-bb);float m=Math.max(0f,Math.min(1f,(dist-900f)/2200f));mask[y*N+x]=m;occupied+=m;if(m>.12f)foreground++;ink+=m*(.2126f*r+.7152f*g+.0722f*b)/255f;
        }return new Feature(mask,occupied>0?ink/occupied:0,foreground/(float)(N*N));
    }
    private static float[] canonical(float[] mask){
        boolean[] expanded=new boolean[N*N],outside=new boolean[N*N];int[] queue=new int[N*N];int head=0,tail=0;
        for(int y=0;y<N;y++)for(int x=0;x<N;x++){
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int xx=x+dx,yy=y+dy;if(xx>=0&&xx<N&&yy>=0&&yy<N&&mask[yy*N+xx]>.12f)expanded[y*N+x]=true;}
        }
        for(int y=0;y<N;y++)for(int x=0;x<N;x++)if((x==0||y==0||x==N-1||y==N-1)&&!expanded[y*N+x]){outside[y*N+x]=true;queue[tail++]=y*N+x;}
        while(head<tail){int index=queue[head++],x=index%N,y=index/N;for(int direction=0;direction<4;direction++){int xx=x+(direction==0?-1:direction==1?1:0),yy=y+(direction==2?-1:direction==3?1:0);if(xx<0||xx>=N||yy<0||yy>=N)continue;int next=yy*N+xx;if(!expanded[next]&&!outside[next]){outside[next]=true;queue[tail++]=next;}}}
        float[] result=new float[N*N];
        for(int y=1;y<N-1;y++)for(int x=1;x<N-1;x++){boolean filled=true;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(outside[(y+dy)*N+x+dx])filled=false;if(filled)result[y*N+x]=1;}
        return result;
    }
    private static float[] normalize(float[] mask){
        float peak=.12f;for(float value:mask)peak=Math.max(peak,value);
        float[] result=new float[mask.length];for(int i=0;i<mask.length;i++)result[i]=mask[i]/peak;return result;
    }
    private static float distance(Feature a,Feature b){float shape=0,filled=0;for(int i=0;i<N*N;i++){shape+=Math.abs(a.normalized()[i]-b.normalized()[i]);filled+=Math.abs(a.silhouette()[i]-b.silhouette()[i]);}return Math.min(shape,filled)/(N*N)+Math.abs(a.ink()-b.ink())*.22f;}
}
