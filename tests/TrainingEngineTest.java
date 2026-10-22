import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

/** Protocol/choice regressions. Not an accuracy target or a human rating benchmark. */
public final class TrainingEngineTest {
    private static int count;
    private static Path work;
    private static void check(boolean ok,String message){count++;if(!ok)throw new AssertionError(message);}
    private static String quote(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    private static UciEngine fixture(String mode,Path log,EngineKind kind,int variations)throws Exception{
        Path script=Files.createTempFile(work,"engine-",".sh");
        Files.writeString(script,"#!/usr/bin/env bash\nexec "+quote(System.getProperty("java.home")+"/bin/java")+" -cp "+quote(Path.of("artifacts/core-tests").toAbsolutePath().toString())+" FakeUci "+quote(mode)+" "+quote(log.toString())+"\n");
        check(script.toFile().setExecutable(true),"fixture executable");
        Path net=work.resolve("fixture.pnn");Files.writeString(net,"fixture");
        return new UciEngine(script.toFile(),kind,kind==EngineKind.CAISSA?net.toFile():null,1,variations,128);
    }
    private static Analysis analyze(UciEngine e,Position p)throws Exception{AtomicReference<Analysis> r=new AtomicReference<>();e.analyze(p,60,r::set);check(r.get()!=null&&p.legalMoves().contains(r.get().bestMove()),"legal final result");return r.get();}
    private static Analysis.Line line(int rank,int cp,String move){return new Analysis.Line(rank,cp,null,List.of(Move.parse(move)));}
    private static Analysis sample(boolean white){return new Analysis("Caissa",10,400,white?35:-35,null,white?List.of(line(1,35,"e2e4"),line(2,20,"d2d4"),line(3,5,"g1f3")):List.of(line(1,-35,"e7e5"),line(2,-20,"d7d5"),line(3,-5,"g8f6")));}
    public static void main(String[] args)throws Exception{
        work=Files.createTempDirectory("hod-training-");
        try{settings();texel();choices();exchanges();coach();System.out.println("PASS: "+count+" training-engine assertions (simulated protocol and bounded choices, no Elo benchmark)");}
        finally{try(var paths=Files.walk(work)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void settings(){
        check(EngineKind.fromPreference(null)==EngineKind.TEXEL&&EngineKind.fromPreference("bad")==EngineKind.TEXEL,"new engine default and recovery");
        check(AdviceStyle.fromPreference(null)==AdviceStyle.VARIED,"default advice");
        for(EngineKind kind:EngineKind.values())for(SearchLevel level:SearchLevel.values())for(AdviceStyle style:AdviceStyle.values()){
            EngineSettings s=new EngineSettings(TimeControlProfile.BLITZ_3_2,1500,4,128,level,kind,style);
            check(s.label().contains(kind.label())&&s.label().contains(level.label()),"honest engine and strength label");
            check(s.effectiveThreads()==(kind==EngineKind.TEXEL&&level!=SearchLevel.FULL?1:4),"Texel native limited strength uses one thread");
            check(s.variations(1)==(kind==EngineKind.CAISSA&&style==AdviceStyle.VARIED?3:1),"no accidental MultiPV or extra Texel weakening");
            check(s.variations(3)==3,"FEN alternatives still available");
            check(EngineKind.fromPreference(kind.key())==kind&&AdviceStyle.fromPreference(style.key())==style,"preference roundtrip");
        }
        Map<String,Object> previous=new HashMap<>();previous.put(SearchLevel.PREFERENCE,"full");previous.put("playerWhite",false);previous.put("panelX",121);previous.put("panelY",450);
        Map<String,Object> changes=TrainingUpgrade.changes(previous);
        check(changes.get(EngineKind.PREFERENCE).equals("texel")&&changes.get(SearchLevel.PREFERENCE).equals("moderate"),"update cannot inherit unrestricted strength from v26");
        check(changes.get("chessPlatform").equals("lichess")&&!changes.containsKey("panelX")&&!changes.containsKey("playerWhite"),"requested platform changes without changing window or side");
        previous.putAll(changes);previous.put(SearchLevel.PREFERENCE,"light");previous.put(EngineKind.PREFERENCE,"caissa");previous.put("chessPlatform","chesscom");
        check(TrainingUpgrade.changes(previous).isEmpty(),"later launches preserve all explicit selections");
        check(previous.get("panelX").equals(121)&&previous.get("panelY").equals(450)&&previous.get("playerWhite").equals(false),"preference delta preserves existing anchors and side");
        check(SearchLevel.LIGHT.texelElo()==1600&&SearchLevel.MODERATE.texelElo()==1900,"native training presets");
        check(!EngineKind.TEXEL.matchesId("Texel 1.120")&&!EngineKind.TEXEL.matchesId("Caissa 2.0")&&EngineKind.TEXEL.matchesId("Texel 1.12"),"strict engine identity");
    }
    private static void texel()throws Exception{
        Path log=work.resolve("texel.log");
        try(UciEngine e=fixture("texel-normal",log,EngineKind.TEXEL,1)){
            for(SearchLevel level:new SearchLevel[]{SearchLevel.LIGHT,SearchLevel.MODERATE,SearchLevel.FULL,SearchLevel.LIGHT}){
                e.setSearchLevel(level);analyze(e,Position.start());
                String commands=Files.readString(log);
                check(commands.lines().filter(l->l.startsWith("setoption name UCI_LimitStrength value ")).reduce((a,b)->b).orElseThrow().equals("setoption name UCI_LimitStrength value "+(level==SearchLevel.FULL?"false":"true")),"latest native limit flag "+level);
                check(commands.lines().filter(l->l.startsWith("setoption name UCI_Elo value ")).reduce((a,b)->b).orElseThrow().equals("setoption name UCI_Elo value "+level.texelElo()),"latest native Elo setting "+level);
                check(!commands.contains(" nodes ")&&!commands.contains("EvalFile")&&!commands.contains("EvalRandomization"),"Texel gets only its supported controls");
                check(commands.contains("setoption name Clear Hash\nucinewgame\nisready"),"old search work cleared before next request");
            }
            Position start=Position.start(),p=start.play(Move.parse("e2e4")).play(Move.parse("e7e5"));
            AtomicReference<Analysis> result=new AtomicReference<>();e.analyzeGame(start,p,List.of(Move.parse("e2e4"),Move.parse("e7e5")),60,result::set);
            check(result.get()!=null&&p.legalMoves().contains(result.get().bestMove()),"Texel full history after mode changes");
            e.newGame();analyze(e,Position.start());
            check(Files.readString(log).contains("setoption name OwnBook value false"),"no unrequested book");
        }
        try(UciEngine e=fixture("texel-bestmove-only",work.resolve("texel-bare.log"),EngineKind.TEXEL,1)){
            e.setSearchLevel(SearchLevel.MODERATE);Analysis a=analyze(e,Position.start());check(!a.hasSearchEvaluation()&&a.score().equals("—"),"Texel move-only result does not invent a score");
            analyze(e,Position.start().play(Move.parse("e2e4")));
        }
        for(String mode:new String[]{"wrong-engine","missing-option","illegal","none","crash"}){
            boolean rejected=false;try(UciEngine e=fixture("texel-"+mode,work.resolve("reject-"+mode),EngineKind.TEXEL,1)){e.setSearchLevel(SearchLevel.MODERATE);analyze(e,Position.start());}catch(IOException expected){rejected=true;}
            check(rejected,"Texel rejects "+mode+" without fallback");
        }
        try(UciEngine e=fixture("texel-slow-stop",work.resolve("texel-cancel"),EngineKind.TEXEL,1)){
            e.setSearchLevel(SearchLevel.MODERATE);ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{
                Future<?> old=queue.submit(()->{try{e.analyze(Position.start(),2000,a->started.countDown());}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception failure){throw new RuntimeException(failure);}});
                check(started.await(3,TimeUnit.SECONDS),"Texel search started");old.cancel(true);
                Analysis next=queue.submit(()->{e.setSearchLevel(SearchLevel.LIGHT);return analyze(e,Position.start().play(Move.parse("e2e4")));}).get(5,TimeUnit.SECONDS);
                check(next.bestMove().uci().equals("e7e5"),"Texel late White answer cannot leak after level switch");
            }finally{queue.shutdownNow();}
        }
        try(UciEngine e=fixture("varied",work.resolve("varied.log"),EngineKind.CAISSA,3)){
            e.setAdviceStyle(AdviceStyle.VARIED);Analysis a=analyze(e,Position.start());
            check(a.bestMove().equals(TrainingChoice.select(Position.start(),sample(true),EngineKind.CAISSA,AdviceStyle.VARIED).bestMove()),"real client applies selection only at final result");
        }
    }
    private static void choices(){
        int alternatives=0;
        List<Position> positions=new ArrayList<>();positions.add(Position.start());
        for(Move m:Position.start().legalMoves())positions.add(Position.start().play(m));
        for(Position p:positions){
            List<Move> quiet=p.legalMoves().stream().filter(m->p.at(m.to())=='.'&&m.promotion()=='\0'&&!p.applyUnchecked(m).inCheck(!p.white)).limit(3).toList();
            if(quiet.size()<3)continue;int sign=p.white?1:-1;
            Analysis best=new Analysis("Caissa",10,400,sign*35,null,List.of(new Analysis.Line(1,sign*35,null,List.of(quiet.get(0))),new Analysis.Line(2,sign*20,null,List.of(quiet.get(1))),new Analysis.Line(3,sign*5,null,List.of(quiet.get(2)))));
            Analysis selected=TrainingChoice.select(p,best,EngineKind.CAISSA,AdviceStyle.VARIED);
            check(p.legalMoves().contains(selected.bestMove()),"alternative legal for both colors");
            check(best.lines().stream().anyMatch(l->l.moves().get(0).equals(selected.bestMove())&&l.centipawns()==selected.centipawns()),"selected move keeps its own evaluation");
            check(sign*(best.centipawns()-selected.centipawns())<=60,"bounded loss uses side-to-move score");
            if(!selected.bestMove().equals(best.bestMove()))alternatives++;
            check(TrainingChoice.select(p,best,EngineKind.TEXEL,AdviceStyle.VARIED)==best,"no double weakening Texel");
            check(TrainingChoice.select(p,best,EngineKind.CAISSA,AdviceStyle.EXACT)==best,"exact mode unchanged");
            Position counters=Position.fromFen(p.fen().replaceAll(" \\d+ \\d+$"," 0 80"));
            check(TrainingChoice.select(counters,best,EngineKind.CAISSA,AdviceStyle.VARIED).bestMove().equals(selected.bestMove()),"refresh and counters preserve choice");
            Analysis shallow=new Analysis(best.engine(),4,50,best.centipawns(),null,best.lines());
            check(TrainingChoice.select(p,shallow,EngineKind.CAISSA,AdviceStyle.VARIED)==shallow,"insufficient depth cannot justify choosing an alternative");
        }
        check(alternatives>3,"varied mode actually selects alternatives on multiple positions");
        Position p=Position.start();Analysis best=sample(true);
        Analysis losing=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"e2e4"),line(2,-400,"d2d4"),line(3,-300,"g1f3")));
        check(TrainingChoice.select(p,losing,EngineKind.CAISSA,AdviceStyle.VARIED)==losing,"reject large artificial blunders");
        Analysis badAlternatives=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"e2e4"),new Analysis.Line(2,20,2,List.of(Move.parse("d2d4"))),line(3,5,"e2e5")));
        check(TrainingChoice.select(p,badAlternatives,EngineKind.CAISSA,AdviceStyle.VARIED)==badAlternatives,"mate and illegal alternatives are not centipawn candidates");
        Position danger=Position.fromFen("7k/8/8/8/8/8/r2Q4/6K1 w - - 0 1");
        Analysis defense=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"d2d3"),line(2,20,"d2e3"),line(3,5,"d2c3")));
        check(TrainingChoice.select(danger,defense,EngineKind.CAISSA,AdviceStyle.VARIED)==defense,"undefended queen threat preserves the authoritative defense");
        Analysis capture=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"d2a2"),line(2,20,"d2e3")));
        check(TrainingChoice.select(danger,capture,EngineKind.CAISSA,AdviceStyle.VARIED)==capture,"capture is not replaced with an arbitrary quiet move");
        Position risky=Position.fromFen("r6k/8/8/8/8/8/8/3Q2K1 w - - 0 1");
        Analysis trap=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"d1d2"),line(2,20,"d1a1")));
        check(TrainingChoice.select(risky,trap,EngineKind.CAISSA,AdviceStyle.VARIED)==trap,"do not move the queen onto an undefended attacked square");
        Analysis mate=new Analysis("Caissa",10,100,0,1,best.lines());
        check(TrainingChoice.select(p,mate,EngineKind.CAISSA,AdviceStyle.VARIED)==mate,"preserve mate");
        Analysis bare=Analysis.moveOnly("Caissa",Move.parse("e2e4"));
        check(TrainingChoice.select(p,bare,EngineKind.CAISSA,AdviceStyle.VARIED)==bare,"missing PV never becomes guessed weakening");
        Position checked=Position.fromFen("7k/8/8/8/8/8/4q3/4K3 w - - 0 1");Analysis forced=Analysis.moveOnly("Caissa",Move.parse("e1e2"));
        check(TrainingChoice.select(checked,forced,EngineKind.CAISSA,AdviceStyle.VARIED)==forced,"forced check answer preserved");
    }
    private static void exchanges(){
        String[] fens={
            "6k1/8/8/4p3/3Q4/4P3/8/6K1 b - - 0 1",
            "6k1/8/8/5n2/3N4/4P3/8/6K1 b - - 0 1",
            "4r1k1/8/8/4p3/3N4/4P3/8/4K3 b - - 0 1",
            "6k1/8/8/8/8/8/p7/6K1 b - - 0 1",
            "6k1/8/8/8/8/8/5pK1/8 b - - 0 1"};
        boolean[] allowed={false,true,false,false,true};
        for(int i=0;i<fens.length;i++){
            Position p=Position.fromFen(fens[i]);check(ExchangeSafety.allowsVariation(p)==allowed[i],"legal exchange guard case "+i);
            char[] mirror=new char[64];for(int square=0;square<64;square++){char piece=p.at(square);mirror[(7-square/8)*8+square%8]=piece=='.'?'.':Character.isUpperCase(piece)?Character.toLowerCase(piece):Character.toUpperCase(piece);}
            Position flipped=Position.fromDiagram(mirror,!p.white,0,-1,0,1);check(ExchangeSafety.allowsVariation(flipped)==allowed[i],"legal exchange guard opposite color case "+i);
        }
        int guarded=0;
        for(String rank:new String[]{"6K1","7K","5K2","4K3"}){
            Position p=Position.fromFen("6k1/8/8/4p3/8/2Q1P3/8/"+rank+" w - - 0 1");
            if(Math.floorMod(p.repetitionKey().hashCode(),100)<50)continue;guarded++;
            Analysis tempting=new Analysis("Caissa",10,100,35,null,List.of(line(1,35,"c3c2"),line(2,20,"c3d4")));
            check(TrainingChoice.select(p,tempting,EngineKind.CAISSA,AdviceStyle.VARIED)==tempting,"a defended queen is not worth trading for a pawn just to vary advice");
        }
        check(guarded>0,"protected-queen regression actually reaches the alternative-selection branch");
        check(ExchangeSafety.allowsVariation(Position.fromFen("6k1/8/8/8/3pP3/8/8/6K1 b - e3 0 1")),"en passant is a pawn capture, not a fabricated queen loss");
    }
    private static void coach(){
        check(PositionCoach.hint(Position.start(),Move.parse("g1f3")).contains("конём"),"fact-based knight hint");
        check(PositionCoach.hint(Position.start(),Move.parse("e2e4")).contains("пешечный"),"fact-based pawn hint");
        check(PositionCoach.hint(Position.fromFen("7k/8/8/8/8/8/4q3/4K3 w - - 0 1"),Move.parse("e1e2")).contains("шах"),"check warning");
        check(PositionCoach.hint(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1"),Move.parse("d1d8")).contains("мат в один"),"verified mate hint");
        check(PositionCoach.hint(Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 10"),Move.parse("e1g1")).contains("рокировку"),"castling hint");
        check(PositionCoach.hint(Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1"),Move.parse("a7a8q")).contains("превращение"),"promotion hint");
        check(PositionCoach.hint(Position.fromFen("7k/8/8/8/8/8/r2Q4/6K1 w - - 0 1"),Move.parse("d2d3")).contains("защиту ферзя"),"queen warning follows board attack geometry");
        for(String move:new String[]{"e2e4","g1f3","b1c3"})check(!PositionCoach.hint(Position.start(),Move.parse(move)).matches(".*[a-h][1-8].*"),"idea does not reveal coordinates");
    }
}
