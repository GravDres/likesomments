import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import ru.hod.chess.core.*;

/** Contract fixture only. Never packaged or presented as the real Caissa engine. */
public final class FakeUci {
    private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> pending;
    private static Move best;
    private static Position position=Position.start();
    private static int variations=1,threads,hash;
    private static boolean gameReady,loaded;
    private static String mode="normal";
    private static synchronized void emit(String text){System.out.println(text);System.out.flush();}
    public static void main(String[] args)throws Exception{
        if(args.length>0)mode=args[0];
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in))){String command;while((command=in.readLine())!=null){
            if(args.length>1)Files.writeString(Path.of(args[1]),command+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if(command.equals("uci")){
                emit("id name "+(mode.equals("wrong-engine")?"Other engine":"Caissa 2.0 ARM NEON fixture"));
                for(String option:new String[]{"EvalFile","EvalRandomization","Ponder","UseSAN","ColorConsoleOutput","UCI_Chess960","MoveOverhead","Threads","Hash","MultiPV"})
                    if(!mode.equals("missing-option")||!option.equals("EvalFile"))emit("option name "+option+" type string default test");
                emit("uciok");
            }else if(command.equals("isready")){gameReady=true;emit("readyok");}
            else if(command.equals("ucinewgame"))gameReady=false;
            else if(command.startsWith("setoption name EvalFile value ")){
                loaded=!mode.equals("no-network");if(loaded)emit("info string Loaded neural network: "+command.substring(30));
            }else if(command.startsWith("setoption name Threads value "))threads=Integer.parseInt(command.substring(command.lastIndexOf(' ')+1));
            else if(command.startsWith("setoption name Hash value "))hash=Integer.parseInt(command.substring(command.lastIndexOf(' ')+1));
            else if(command.startsWith("setoption name MultiPV value "))variations=Integer.parseInt(command.substring(command.lastIndexOf(' ')+1));
            else if(command.startsWith("setoption name ")){
                if(command.contains("Skill")||command.contains("LimitStrength")||command.contains("searchmoves"))throw new AssertionError("Unsupported engine setting");
            }else if(command.startsWith("position ")){
                String[] parts=command.substring(9).split(" moves ",2);
                position=parts[0].equals("startpos")?Position.start():Position.fromFen(parts[0].substring(4));
                if(parts.length==2)for(String move:parts[1].split(" "))position=position.play(Move.parse(move));
            }else if(command.startsWith("go ")){
                if(!loaded||threads<1||hash<64||!gameReady)throw new AssertionError("Caissa configuration not ready");
                if(mode.equals("crash"))return;
                List<Move> legal=position.legalMoves();
                Move first=legal.contains(Move.parse("e2e4"))?Move.parse("e2e4"):legal.contains(Move.parse("e7e5"))?Move.parse("e7e5"):legal.get(0);
                if(mode.startsWith("move:")||mode.startsWith("bestmove-only:")){first=Move.parse(mode.substring(mode.indexOf(':')+1));if(!legal.contains(first))throw new AssertionError("Requested test move illegal");}
                best=first;
                if(!mode.startsWith("bestmove-only")){
                    if(!mode.equals("bound-only")&&!mode.equals("malformed-only"))emit("info depth 8 multipv 1 score cp 35 nodes 100 pv "+first.uci());
                    emit("info depth 9 multipv 1 score cp 999 nodes 110 pv e2e5");
                    emit("info depth 12 multipv 1 score cp 999 lowerbound nodes 110 pv "+first.uci());
                    emit("info depth 12 multipv 2 score cp");
                    if(variations>=3){Move second=legal.contains(Move.parse("d2d4"))?Move.parse("d2d4"):legal.contains(Move.parse("d7d5"))?Move.parse("d7d5"):legal.get(legal.get(0).equals(first)?1:0);best=second;emit("info depth 8 multipv 2 score cp 10 nodes 120 pv "+second.uci());}
                }
                String result=mode.equals("illegal")?"e2e5":mode.equals("unreported")?"g1h3":best.uci();
                if(mode.equals("nullmove"))result="0000";
                if(mode.equals("none"))result="(none)";
                String finalResult=result;
                if(!mode.equals("hang")&&!mode.equals("ignore-stop"))pending=timer.schedule(()->emit("bestmove "+finalResult),Integer.parseInt(command.split(" ")[2]),TimeUnit.MILLISECONDS);
            }else if(command.equals("stop")){
                if(pending!=null)pending.cancel(false);
                if(mode.equals("ignore-stop"))continue;
                String answer="bestmove "+best.uci();
                if(mode.equals("slow-stop"))timer.schedule(()->emit(answer),150,TimeUnit.MILLISECONDS);else emit(answer);
            }else if(command.equals("quit"))break;
        }}finally{timer.shutdownNow();}
    }
}
