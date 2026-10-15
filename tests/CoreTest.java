import ru.hod.chess.core.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;

public final class CoreTest {
    private static int count;
    static void check(boolean v,String label){count++;if(!v)throw new AssertionError(label);}
    static long perft(Position p,int depth){if(depth==0)return 1;long n=0;for(Move m:p.legalMoves())n+=perft(p.applyUnchecked(m),depth-1);return n;}
    static Position play(Position p,String...moves){for(String s:moves)p=p.play(Move.parse(s));return p;}
    public static void main(String[] args) throws Exception {
        Position start=Position.start();check(start.fen().equals(Position.START),"FEN round trip");
        check(perft(start,1)==20,"start perft 1");check(perft(start,2)==400,"start perft 2");check(perft(start,3)==8902,"start perft 3");check(perft(start,4)==197281,"start perft 4");
        Position kiwi=Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        check(perft(kiwi,1)==48,"kiwipete 1");check(perft(kiwi,2)==2039,"kiwipete 2");check(perft(kiwi,3)==97862,"kiwipete 3");
        Position ep=play(start,"e2e4","a7a6","e4e5","d7d5").play(Move.parse("e5d6"));check(ep.at(Position.index("d5"))=='.',"en passant removes pawn");
        Position pinned=Position.fromFen("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");check(!pinned.legalMoves().contains(Move.parse("e5d6")),"en passant exposing king prohibited");
        Position castle=Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").play(Move.parse("e1g1"));check(castle.at(61)=='R'&&castle.at(62)=='K'&&castle.rights==12,"castling updates rook and rights");
        Position promotions=Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1");check(promotions.legalMoves().stream().filter(m->m.from()==8).count()==4,"four promotions");
        Position mate=play(start,"f2f3","e7e5","g2g4","d8h4");check(mate.inCheck(true)&&mate.legalMoves().isEmpty(),"checkmate");
        Position stale=Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");check(!stale.inCheck(false)&&stale.legalMoves().isEmpty(),"stalemate");
        check(Position.fromFen("7k/8/8/8/8/8/8/2B4K w - - 0 1").insufficientMaterial(),"K+B vs K draw");
        check(!Position.fromFen("7k/8/8/8/8/8/8/2NN3K w - - 0 1").insufficientMaterial(),"two knights still permit a mating position");
        check(Position.fromFen("7k/8/8/8/8/8/8/7K w - - 150 76").halfmove==150,"75 move counter");
        GameTracker repetitions=new GameTracker(start);Position rep=start;
        for(int cycle=0;cycle<4;cycle++)for(String uci:new String[]{"g1f3","g8f6","f3g1","f6g8"}){rep=rep.play(Move.parse(uci));repetitions.accept(rep.pieces());}
        check(repetitions.repetitionCount()==5&&repetitions.automaticDraw()!=null,"fivefold draw");
        check(repetitions.claimableDraw()!=null,"threefold claim available");
        GameTracker tracker=new GameTracker(start);Position pair=play(start,"e2e4","e7e5");check(tracker.accept(pair.pieces()).status()==GameTracker.Status.MOVED,"recover two plies");check(tracker.position().fen().equals(pair.fen()),"retain complete metadata");check(tracker.history().size()==2,"history");
        char[] noise=pair.pieces();noise[0]='.';check(tracker.accept(noise).status()==GameTracker.Status.UNRECOGNIZED,"reject impossible transition");check(tracker.position().fen().equals(pair.fen()),"unrecognized frame does not mutate state");
        AtomicReference<Analysis> result=new AtomicReference<>();new LocalEngine().analyze(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1"),400,result::set);
        check(result.get()!=null&&result.get().bestMove()!=null,"fallback returns move");check(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1").legalMoves().contains(result.get().bestMove()),"engine move legal");
        for(String invalid:new String[]{"8/8/8/8/8/8/8/4K2k w K - 0 1","8/8/8/8/8/8/8/4Kk2 w - - 0 1","P6k/8/8/8/8/8/8/7K w - - 0 1"}){boolean rejected=false;try{Position.fromFen(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject impossible FEN");}
        visionTests();
        arrowTests();
        uciTests();
        System.out.println("PASS: "+count+" assertions, including 197281 + 97862 perft leaf positions");
    }
    static void arrowTests(){
        BoardVision.Region r=new BoardVision.Region(20,100,640);
        ArrowGeometry white=new ArrowGeometry(r,Move.parse("g1f3"),true);
        check(white.start.x()==540&&white.start.y()==700,"arrow white source g1");
        check(white.end.x()==460&&white.end.y()==540,"arrow white target f3");
        ArrowGeometry black=new ArrowGeometry(r,Move.parse("g1f3"),false);
        check(black.start.x()==140&&black.start.y()==140&&black.end.x()==220&&black.end.y()==300,"arrow rotates 180 degrees");
        check(white.covers(white.start.x(),white.start.y(),0)&&white.covers(white.end.x(),white.end.y(),0),"mask includes source and tip");
        check(!white.covers(30,110,4),"unrelated board area unmasked");
        ArrowGeometry castle=new ArrowGeometry(r,Move.parse("e1g1"),true);check(castle.start.y()==castle.end.y()&&castle.end.x()-castle.start.x()==160,"castling points to king destination");
        ArrowGeometry ep=new ArrowGeometry(r,Move.parse("e5d6"),true);check(ep.end.x()==300&&ep.end.y()==300,"en passant points to empty destination");
        ArrowGeometry promotion=new ArrowGeometry(r,Move.parse("a7a8n"),true);check(promotion.move.promotion()=='n'&&promotion.end.y()==140,"underpromotion retained for badge");
        Position p=play(Position.start(),"e2e4","e7e5");BoardVision.Region board=new BoardVision.Region(0,0,640);
        int[] clean=render(p,false,false);ArrowGeometry shape=new ArrowGeometry(board,Move.parse("g1f3"),true);int[] visible=withArrow(clean,640,shape);
        check(!BoardChangeDetector.changed(clean,visible,640,640,shape),"our arrow does not look like board motion");
        int[] moved=withArrow(render(p.play(Move.parse("g1f3")),false,false),640,shape);
        check(BoardChangeDetector.changed(clean,moved,640,640,shape),"move under arrow still detected outside painted pixels");
        Position reply=p.play(Move.parse("f1c4"));check(BoardChangeDetector.changed(clean,withArrow(render(reply,false,false),640,shape),640,640,shape),"different move triggers fresh recognition");
        check(BoardChangeDetector.changed(clean,new int[5],640,640,shape),"changed image dimensions invalidate overlay");
        Position throughCheck=Position.fromFen("k4r2/8/8/8/8/8/8/4K2R w K - 0 1");check(!throughCheck.legalMoves().contains(Move.parse("e1g1")),"cannot castle through an attacked square");
        Position rookTaken=Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").play(Move.parse("a1a8"));check((rookTaken.rights&10)==0,"moving and capturing rooks remove both queenside rights");
        Position under=Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1").play(Move.parse("a7a8n"));check(under.at(0)=='N',"actual underpromotion to knight");
    }
    static int[] withArrow(int[] clean,int width,ArrowGeometry shape){int[] image=clean.clone();for(int y=0;y<image.length/width;y++)for(int x=0;x<width;x++)if(shape.covers(x,y,0))image[y*width+x]=0xffc2ef67;return image;}
    static void uciTests()throws Exception{
        java.io.File fixture=new java.io.File("tests/fake-uci.sh");fixture.setExecutable(true);
        try(UciEngine engine=new UciEngine(fixture,2)){
            AtomicReference<Analysis> result=new AtomicReference<>();engine.analyze(Position.start(),120,result::set);
            check(result.get().bestMove().uci().equals("e2e4"),"UCI valid move parsed");check(result.get().depth()==8&&result.get().centipawns()==35,"invalid UCI variation rejected");
            result.set(null);engine.analyze(play(Position.start(),"e2e4"),120,result::set);check(result.get().centipawns()==-35,"UCI score normalized for Black");
            result.set(null);engine.analyzeGame(play(Position.start(),"e2e4"),List.of(Move.parse("e2e4")),120,result::set);check(result.get().bestMove().uci().equals("e7e5"),"full game history reaches the UCI process");
            boolean mismatch=false;try{engine.analyzeGame(Position.start(),List.of(Move.parse("e2e4")),120,result::set);}catch(IllegalArgumentException e){mismatch=true;}check(mismatch,"history/position mismatch never sent to native engine");
            engine.configure(4,3);result.set(null);engine.analyze(Position.start(),120,result::set);check(result.get().bestMove().uci().equals("d2d4"),"authoritative bestmove overrides the last intermediate top PV");check(result.get().centipawns()==10,"final move keeps its own evaluation");engine.configure(2,1);
            ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{
                Future<?> first=executor.submit(()->{try{engine.analyze(Position.start(),2000,a->started.countDown());}catch(Exception e){throw new RuntimeException(e);}});
                check(started.await(3,TimeUnit.SECONDS),"UCI search started");first.cancel(true);
                Future<?> second=executor.submit(()->{try{engine.analyze(play(Position.start(),"e2e4"),120,result::set);}catch(Exception e){throw new RuntimeException(e);}});second.get(5,TimeUnit.SECONDS);
                check(result.get().bestMove().uci().equals("e7e5"),"UCI cancellation drains old bestmove before new search");
            }finally{executor.shutdownNow();}
        }
    }
    static void visionTests(){
        BoardVision.Region region=new BoardVision.Region(0,0,640);BoardVision vision=new BoardVision(true);
        int[] initial=render(Position.start(),false,false);vision.calibrate(initial,640,640,region);check(vision.templateCount()==32,"vision learns 32 occupied squares");
        BoardVision.Observation obs=vision.recognize(initial,640,640,region);check(Arrays.equals(obs.board(),Position.start().pieces())&&obs.uncertain()==0,"recognize training image");
        Position p=play(Position.start(),"e2e4","b8c6");obs=vision.recognize(render(p,true,false),640,640,region);check(Arrays.equals(obs.board(),p.pieces())&&obs.uncertain()==0,"recognize moved pieces on highlighted squares");
        BoardVision black=new BoardVision(false);black.calibrate(render(Position.start(),false,true),640,640,region);obs=black.recognize(render(p,false,true),640,640,region);check(Arrays.equals(obs.board(),p.pieces()),"black orientation");
    }
    static int[] render(Position p,boolean highlights,boolean rotated){
        int[] a=new int[640*640];String kinds="pnbrqk";
        for(int display=0;display<64;display++){int x0=display%8*80,y0=display/8*80;char piece=p.at(rotated?63-display:display);int bg=(display/8+display%8)%2==0?0xffe6e7cb:0xff789154;if(highlights&&(display==36||display==42))bg=0xffbdd660;
            for(int y=0;y<80;y++)for(int x=0;x<80;x++)a[(y0+y)*640+x0+x]=bg;
            if(piece=='.')continue;int type=kinds.indexOf(Character.toLowerCase(piece)),ink=Position.isWhite(piece)?0xfffafafa:0xff242424;
            for(int y=18;y<66;y++)for(int x=24;x<56;x++){boolean draw=y>58||x>29&&x<51&&y>35||y<35&&((x/5+type)%(type+2)==0);if(draw)a[(y0+y)*640+x0+x]=ink;}
        }return a;
    }
}
