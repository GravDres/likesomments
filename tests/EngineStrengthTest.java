import ru.hod.chess.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** UCI protocol tests; the fixture does not simulate Stockfish strength or human play. */
public final class EngineStrengthTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        check(EngineStrength.fromPreference(EngineStrength.DEFAULT_SKILL)==EngineStrength.MEDIUM,"default app strength is 10/20");
        check(EngineStrength.fromPreference(999)==EngineStrength.MEDIUM&&EngineStrength.fromPreference(-1)==EngineStrength.MEDIUM,"invalid stored settings normalize to the default");
        File fixture=new File("tests/fake-uci.sh");fixture.setExecutable(true);
        for(EngineStrength strength:EngineStrength.values()){
            check(EngineStrength.fromPreference(strength.skill())==strength,"every selectable level persists");
            try(UciEngine engine=new UciEngine(fixture,2,1,strength.skill())){
                verify(engine,Position.start(),strength.skill()<20?"g1f3":"e2e4",strength.skill()<20?-100:35,strength.skill()<20);
                Position black=Position.start().play(Move.parse("e2e4"));var reports=new ArrayList<Analysis>();
                engine.analyzeGame(black,List.of(Move.parse("e2e4")),40,reports::add);
                Analysis finalResult=reports.get(reports.size()-1);check(finalResult.bestMove().uci().equals(strength.skill()<20?"g8f6":"e7e5"),"selected move follows history for Black");check(finalResult.centipawns()==(strength.skill()<20?100:-35),"selected move's evaluation is normalized for Black");
                if(strength.skill()<20)check(reports.size()==1,"no stronger intermediate move is exposed for Black");
                engine.configure(4,3);verify(engine,Position.start(),strength.skill()<20?"g1f3":"d2d4",strength.skill()<20?-100:10,strength.skill()<20);
                engine.configure(2,1,20);verify(engine,Position.start(),"e2e4",35,false);
                engine.configure(2,1,5);verify(engine,Position.start(),"g1f3",-100,true);
                var cancelled=new ArrayList<Analysis>();Thread.currentThread().interrupt();try{engine.analyze(Position.start(),100,cancelled::add);}finally{Thread.interrupted();}check(cancelled.isEmpty(),"cancelled limited search never publishes its suggestion");verify(engine,black,"g8f6",100,true);
                engine.newGame();verify(engine,Position.start(),"g1f3",-100,true);
            }
        }
        for(String mode:new String[]{"unreported","illegal"}){
            Path script=Files.createTempFile("hod-strength-", ".sh");try{
                Files.writeString(script,"#!/usr/bin/env bash\nexec java -cp artifacts/core-tests FakeUci "+mode+"\n");script.toFile().setExecutable(true);
                try(UciEngine engine=new UciEngine(script.toFile(),2,1,10)){
                    var reports=new ArrayList<Analysis>();boolean rejected=false;try{engine.analyze(Position.start(),40,reports::add);}catch(IOException expected){rejected=true;}
                    check(rejected&&reports.isEmpty(),"invalid or unverified final selection cannot fall back to a stronger move: "+mode);
                }
            }finally{Files.deleteIfExists(script);}
        }
        System.out.println("PASS: "+checks+" strength/protocol assertions (host UCI fixture, not measured Elo or detection tests)");
    }
    private static void verify(UciEngine engine,Position p,String expected,int cp,boolean limited)throws Exception{
        var reports=new ArrayList<Analysis>();engine.analyze(p,40,reports::add);check(!reports.isEmpty(),"analysis produces a result");Analysis a=reports.get(reports.size()-1);
        check(a.bestMove().uci().equals(expected)&&p.legalMoves().contains(a.bestMove()),"authoritative final selection remains legal");check(a.centipawns()==cp,"selected move keeps its own score rather than the top move's score");check(a.lines().get(0).moves().get(0).equals(a.bestMove()),"principal variation starts with the selected move");
        if(limited){check(reports.size()==1,"limited search exposes only the final selection");check(a.lines().size()==1&&a.engine().contains("/20"),"limited result is labelled and has one chosen variation");}
    }
}
