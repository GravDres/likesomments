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
        for(String invalid:new String[]{"8/8/8/8/8/8/8/4K2k w K - 0 1","8/8/8/8/8/8/8/4Kk2 w - - 0 1","P6k/8/8/8/8/8/8/7K w - - 0 1","7k/8/8/8/8/8/8/7K w - - 32768 1","7k/8/8/8/8/8/8/7K w - - 0 50001"}){boolean rejected=false;try{Position.fromFen(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject impossible FEN");}
        for(String invalid:new String[]{"7k/8/8/8/8/8/8/7K/ w - - 0 1","7k/3p4/8/3pP3/8/8/8/7K w - d6 0 10","7k/8/8/3pP3/8/8/8/7K w - d6 1 10"}){
            boolean rejected=false;try{Position.fromFen(invalid);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"reject malformed ranks or en passant inconsistent with the last pawn move");
        }
        visionTests();
        locatorTests();
        autoSearchTests();
        sessionTests();
        recoveryAndGateTests();
        arrowTests();
        uciTests();
        System.out.println("PASS: "+count+" assertions, including 197281 + 97862 perft leaf positions");
    }
    static void recoveryAndGateTests()throws Exception{
        CleanFrameGate gate=new CleanFrameGate();
        check(gate.accept(1),"new capture admits clean frames without an Image timestamp assumption");
        long version=gate.version();gate.block();check(!gate.accept(Long.MAX_VALUE)&&gate.version()!=version,"visible overlay blocks recognition regardless of time");
        gate.hidden(1_000_000_000L);check(!gate.accept(1_050_000_000L),"compositor settling interval enforced");
        check(!gate.accept(1_080_000_000L)&&!gate.accept(1_100_000_000L),"queued images discarded after hiding an arrow");
        check(gate.accept(1_120_000_000L)&&gate.accept(1_200_000_000L),"frame gate reopens and stays open after the boundary");
        gate.block();gate.reset();check(gate.accept(2),"replacement capture resets a blocked frame gate");
        GameTracker three=new GameTracker(Position.start());Position target=play(Position.start(),"e2e4","d7d5","e4e5");
        GameTracker.Update recovered=three.recover(target.pieces());
        check(recovered.status()==GameTracker.Status.MOVED&&three.history().size()==3,"recover a unique missed three-ply sequence");
        check(three.position().fen().equals(target.fen()),"three-ply recovery preserves turn and complete FEN metadata");
        GameTracker four=new GameTracker(Position.start());Position captured=play(Position.start(),"e2e4","d7d5","e4d5","d8d5");
        check(four.recover(captured.pieces()).status()==GameTracker.Status.MOVED&&four.history().size()==4,"recover a forced chain of four missed capture plies");
        check(four.position().fen().equals(captured.fen()),"four-ply recovery retains castling rights and move counters");
        GameTracker ambiguous=new GameTracker(Position.start());Position multiple=play(Position.start(),"e2e4","e7e5","g1f3");
        check(ambiguous.recover(multiple.pieces()).status()==GameTracker.Status.AMBIGUOUS,"ambiguous missing move order is never guessed");
        check(ambiguous.history().isEmpty()&&ambiguous.position().fen().equals(Position.START),"ambiguous recovery leaves history unchanged");
        GameTracker interrupted=new GameTracker(Position.start());Thread.currentThread().interrupt();
        try{check(interrupted.recover(target.pieces()).status()==GameTracker.Status.RECOVERY_LIMIT&&interrupted.history().isEmpty(),"cancelled recovery cannot commit an unverified partial history");}finally{Thread.interrupted();}
        BoardVision model=new BoardVision(true);model.calibrate(render(Position.start(),false,false),640,640,new BoardVision.Region(0,0,640));
        GameMemory.Restored restored=GameMemory.decode(GameMemory.encode(four,model));
        check(restored.game().history().size()==4&&restored.game().position().fen().equals(captured.fen()),"recovered history survives another service restart");
    }
    static void sessionTests()throws Exception{
        Position start=Position.start(),e4=play(start,"e2e4"),pair=play(start,"e2e4","e7e5");
        check(PlayerSide.WHITE.ownsTurn(start)&&!PlayerSide.BLACK.ownsTurn(start),"only White receives a suggestion at start");
        check(!PlayerSide.WHITE.ownsTurn(e4)&&PlayerSide.BLACK.ownsTurn(e4),"White waits after e4, Black can analyze");
        check(PlayerSide.WHITE.ownsTurn(pair)&&!PlayerSide.BLACK.ownsTurn(pair),"White resumes only after opponent's reply");
        StableBoard stability=new StableBoard();GameTracker game=new GameTracker(start);
        BoardVision.Observation clean=new BoardVision.Observation(e4.pieces(),1,0);
        check(!stability.accept(clean),"one moving frame never confirms a move");
        check(!stability.accept(new BoardVision.Observation(pair.pieces(),0,2)),"uncertain animation resets stable verification");
        check(!stability.accept(clean)&&stability.accept(clean),"two clean frames after animation confirm");game.accept(e4.pieces());
        check(game.history().size()==1&&game.position().fen().equals(e4.fen()),"only observed legal moves enter game history");
        check(game.accept(start.pieces()).status()==GameTracker.Status.UNRECOGNIZED&&game.history().size()==1,"stale start screen cannot reset a running game");
        BoardVision vision=new BoardVision(true);vision.calibrate(render(start,false,false),640,640,new BoardVision.Region(0,0,640));
        byte[] bytes=GameMemory.encode(game,vision);GameMemory.Restored restored=GameMemory.decode(bytes);
        check(restored.game().position().fen().equals(e4.fen())&&restored.game().history().equals(game.history()),"restart restores exact turn, castling and en passant metadata");
        BoardVision.Observation seen=restored.vision().recognize(render(pair,true,false),640,640,new BoardVision.Region(0,0,640));
        check(seen.uncertain()==0&&restored.game().accept(seen.board()).status()==GameTracker.Status.MOVED,"restored piece theme can continue the saved game");
        GameTracker repeat=GameTracker.replay(List.of(Move.parse("g1f3"),Move.parse("g8f6"),Move.parse("f3g1"),Move.parse("f6g8"),Move.parse("g1f3"),Move.parse("g8f6"),Move.parse("f3g1"),Move.parse("f6g8")));
        GameMemory.Restored repeats=GameMemory.decode(GameMemory.encode(repeat,vision));
        check(repeats.game().repetitionCount()==3&&repeats.game().claimableDraw()!=null,"restart preserves repetition counts, including returns to initial placement");
        check(repeats.game().history().size()==8,"a return to initial arrangement retains every ply");
        AutoBoardSearch search=new AutoBoardSearch();int[] initial=screen(720,1440,40,300,640,false);
        check(search.inspect(initial,720,1440,vision,game).state()==AutoBoardSearch.State.VERIFYING,"a different starting board begins a new two-frame verification without mutating the old game");
        check(search.inspect(initial,720,1440,vision,game).calibration().reuseGame()==false&&game.history().size()==1,"confirmed new game is explicit in the result and probing preserves the old history");
        AutoBoardSearch returned=new AutoBoardSearch();returned.inspect(initial,720,1440,vision,repeat);
        check(returned.inspect(initial,720,1440,vision,repeat).calibration().reuseGame(),"legal return to start can be relocated without losing repetition history");
        byte[] broken=bytes.clone();broken[broken.length/2]^=0x42;boolean rejected=false;
        try{GameMemory.decode(broken);}catch(java.io.IOException e){rejected=true;}check(rejected,"corrupt session snapshot rejected");
        boolean illegal=false;try{GameTracker.replay(List.of(Move.parse("e2e5")));}catch(IllegalArgumentException e){illegal=true;}check(illegal,"restoration rejects illegal move history");
        Position baseline=Position.fromDiagram(pair.pieces(),true,15,-1,0,17);GameTracker rebased=new GameTracker(baseline);rebased.accept(baseline.play(Move.parse("g1f3")).pieces());
        GameMemory.Restored midgame=GameMemory.decode(GameMemory.encode(rebased,vision));
        check(midgame.game().initial().fen().equals(baseline.fen())&&midgame.game().history().size()==1&&midgame.game().position().fullmove==17,"manual recovery stores its own baseline and subsequent history without pretending to start at move one");
        for(String name:new String[]{"legacy-v04.hod","legacy-v06.hod"}){
            GameMemory.Restored legacy=GameMemory.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("tests/fixtures",name)));
            check(legacy.game().position().fen().equals(game.position().fen())&&legacy.game().history().equals(game.history()),name+" keeps legal history");
            var oldSeen=legacy.vision().recognize(render(pair,true,false),640,640,new BoardVision.Region(0,0,640));check(oldSeen.uncertain()==0&&pair.matches(oldSeen.board()),name+" keeps original contrast-based templates");
            var migrated=GameMemory.decode(GameMemory.encode(legacy.game(),legacy.vision()));var migratedSeen=migrated.vision().recognize(render(pair,true,false),640,640,new BoardVision.Region(0,0,640));
            check(migratedSeen.uncertain()==0&&pair.matches(migratedSeen.board()),name+" survives resaving in extended format");
        }
        int[] full=screenWith(pair,1000,1500,220,350,640,false),crop=new int[640*640];
        for(int row=0;row<640;row++)System.arraycopy(full,(350+row)*1000+220,crop,row*640,640);
        check(vision.recognize(crop,640,640,new BoardVision.Region(0,0,640)).uncertain()==0&&Arrays.equals(vision.recognize(crop,640,640,new BoardVision.Region(0,0,640)).board(),pair.pieces()),"board-only capture preserves recognition at nonzero screen coordinates");
        ArrowGeometry local=new ArrowGeometry(new BoardVision.Region(0,0,640),Move.parse("g1f3"),true);
        check(!BoardChangeDetector.changed(crop,withArrow(crop,640,local),640,640,local),"cropped arrow baseline does not produce false board movement");
    }
    static void locatorTests(){
        BoardLocator locator=new BoardLocator();
        int[][] cases={{480,1000,0,220,480},{1080,2280,16,450,1040},{1264,2780,16,620,1232},{1900,1000,500,100,800},{720,1440,48,300,624}};
        for(int[] c:cases)for(boolean black:new boolean[]{false,true}){
            int[] image=screen(c[0],c[1],c[2],c[3],c[4],black);
            BoardLocator.Detection detection=locator.find(image,c[0],c[1]);
            check(detection!=null,"board found at "+Arrays.toString(c));
            check(BoardLocator.near(detection.region(),new BoardVision.Region(c[2],c[3],c[4])),"correct automatic bounds");
            check(detection.automatic()&&detection.initialLayout(),"initial grid accepted confidently");
            check(detection.orientationKnown()&&detection.whiteBottom()!=black,"automatic side orientation");
        }
        int[] blank=new int[480*1000];Arrays.fill(blank,0xff161b19);check(locator.find(blank,480,1000)==null,"plain app screen rejected");
        int[] image=screen(480,1000,0,220,480,false);
        for(int y=370;y<500;y++)Arrays.fill(image,y*480+100,y*480+390,0xff212121);
        BoardLocator.Detection occluded=locator.find(image,480,1000);check(occluded==null||!occluded.automatic(),"dialog on board is not accepted automatically");
        int[] themed=screen(720,1440,48,300,624,false);Random noise=new Random(12);
        for(int i=0;i<themed.length;i++){int p=themed[i];if(p==0xffe6e7cb)p=0xffd8e6ef;else if(p==0xff789154)p=0xff46759b;int delta=noise.nextInt(7)-3;themed[i]=0xff000000|Math.max(0,Math.min(255,(p>>16&255)+delta))<<16|Math.max(0,Math.min(255,(p>>8&255)+delta))<<8|Math.max(0,Math.min(255,(p&255)+delta));}
        BoardLocator.Detection blue=locator.find(themed,720,1440);check(blue!=null&&blue.automatic()&&BoardLocator.near(blue.region(),new BoardVision.Region(48,300,624)),"different palette and pixel noise");
        int[] two=screen(1000,1600,10,100,400,false);int[] second=screen(1000,1600,560,1000,400,true);for(int y=1000;y<1400;y++)System.arraycopy(second,y*1000+560,two,y*1000+560,400);BoardLocator.Detection ambiguous=locator.find(two,1000,1600);check(ambiguous!=null&&ambiguous.ambiguous()&&!ambiguous.automatic(),"two large boards require user verification");
    }
    static void autoSearchTests(){
        int[] first=screen(720,1440,40,300,640,false);AutoBoardSearch search=new AutoBoardSearch();
        AutoBoardSearch.Result a=search.inspect(first,720,1440,null,null);check(a.state()==AutoBoardSearch.State.VERIFYING,"one screenshot does not auto-calibrate");
        AutoBoardSearch.Result b=search.inspect(first,720,1440,null,null);check(b.state()==AutoBoardSearch.State.READY&&b.calibration().whiteBottom()&&!b.calibration().reuseGame(),"two stable screenshots calibrate without a rectangle");
        GameTracker game=new GameTracker(Position.start());Position played=play(Position.start(),"e2e4","e7e5");game.accept(played.pieces());
        int[] moved=screenWith(played,1000,1500,220,350,640,true);AutoBoardSearch relocated=new AutoBoardSearch();
        AutoBoardSearch.Result before=relocated.inspect(moved,1000,1500,b.calibration().vision(),game);check(before.state()==AutoBoardSearch.State.VERIFYING,"existing game can be relocated in the middle");
        AutoBoardSearch.Result after=relocated.inspect(moved,1000,1500,b.calibration().vision(),game);check(after.state()==AutoBoardSearch.State.READY&&after.calibration().reuseGame()&&!after.calibration().whiteBottom(),"relocation detects rotation and retains game");check(game.history().size()==2&&game.position().fen().equals(played.fen()),"probing does not mutate live history");
        AutoBoardSearch fresh=new AutoBoardSearch();check(fresh.inspect(moved,1000,1500,null,null).state()==AutoBoardSearch.State.NEED_INITIAL,"fresh midgame screenshot is not mislabeled as starting pieces");
        int[] gray=first.clone();for(int i=0;i<gray.length;i++)if(gray[i]==0xfffafafa||gray[i]==0xff242424)gray[i]=0xff606060;
        check(new AutoBoardSearch().inspect(gray,720,1440,null,null).state()==AutoBoardSearch.State.NEED_ORIENTATION,"ambiguous piece colors are not guessed");
        AutoBoardSearch interrupted=new AutoBoardSearch();interrupted.inspect(first,720,1440,null,null);int[] empty=new int[first.length];Arrays.fill(empty,0xff181c19);interrupted.inspect(empty,720,1440,null,null);check(interrupted.inspect(first,720,1440,null,null).state()==AutoBoardSearch.State.VERIFYING,"lost board resets stable-frame verification");
    }
    static int[] screen(int w,int h,int left,int top,int size,boolean rotated){return screenWith(Position.start(),w,h,left,top,size,rotated);}
    static int[] screenWith(Position p,int w,int h,int left,int top,int size,boolean rotated){int[] pixels=new int[w*h];Arrays.fill(pixels,0xff202523);int[] board=render(p,false,rotated);for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[(top+y)*w+left+x]=board[(y*640/size)*640+x*640/size];return pixels;}
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
        try(UciEngine engine=new UciEngine(fixture,new java.io.File("tests/caissa-fixture.pnn"),2,1,128)){
            AtomicReference<Analysis> result=new AtomicReference<>();engine.analyze(Position.start(),120,result::set);
            check(result.get().bestMove().uci().equals("e2e4"),"UCI valid move parsed");check(result.get().depth()==8&&result.get().centipawns()==35,"invalid UCI variation rejected");
            result.set(null);engine.analyze(play(Position.start(),"e2e4"),120,result::set);check(result.get().centipawns()==-35,"UCI score normalized for Black");
            result.set(null);engine.analyzeGame(play(Position.start(),"e2e4"),List.of(Move.parse("e2e4")),120,result::set);check(result.get().bestMove().uci().equals("e7e5"),"full game history reaches the UCI process");
            boolean mismatch=false;try{engine.analyzeGame(Position.start(),List.of(Move.parse("e2e4")),120,result::set);}catch(IllegalArgumentException e){mismatch=true;}check(mismatch,"history/position mismatch never sent to native engine");
            Position base=Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 17");result.set(null);
            engine.analyzeGame(base,base.play(Move.parse("e2e4")),List.of(Move.parse("e2e4")),120,result::set);
            check(result.get().bestMove().uci().equals("e7e5"),"confirmed midgame base FEN and its subsequent moves reach the native process");
            boolean wrongBase=false;try{engine.analyzeGame(Position.start(),base.play(Move.parse("e2e4")),List.of(Move.parse("e2e4")),120,result::set);}catch(IllegalArgumentException e){wrongBase=true;}check(wrongBase,"wrong starting FEN is rejected even when pieces and move sequence match");
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
