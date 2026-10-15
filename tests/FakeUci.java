import java.io.*;
import java.util.concurrent.*;

/** Protocol fixture: asynchronous UCI process which reacts to stop while searching. */
public final class FakeUci {
    private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> pending;
    private static boolean black;
    private static String best;
    private static boolean multiple;
    private static boolean unlimited,fullSkill,hashReady,gameReady;
    private static synchronized void emit(String text){System.out.println(text);System.out.flush();}
    public static void main(String[] args)throws Exception{
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in))){String command;while((command=in.readLine())!=null){
            if(command.equals("uci")){emit("id name UCI fixture");emit("uciok");}
            else if(command.equals("isready")){gameReady=true;emit("readyok");}
            else if(command.equals("ucinewgame"))gameReady=false;
            else if(command.startsWith("setoption name Threads value "))hashReady=false;
            else if(command.equals("setoption name Hash value 256"))hashReady=true;
            else if(command.equals("setoption name Skill Level value 20"))fullSkill=true;
            else if(command.equals("setoption name UCI_LimitStrength value false"))unlimited=true;
            else if(command.startsWith("position fen ")){black=command.contains(" b ");if(command.contains(" moves ")&&command.substring(command.indexOf(" moves ")+7).trim().split(" ").length%2==1)black=!black;}
            else if(command.startsWith("position startpos"))black=command.contains(" moves ")&&command.substring(command.indexOf(" moves ")+7).trim().split(" ").length%2==1;
            else if(command.startsWith("setoption name MultiPV value "))multiple=command.endsWith("3");
            else if(command.startsWith("go ")){
                if(!unlimited||!fullSkill||!hashReady||!gameReady)throw new IllegalStateException("Full-strength engine configuration or readiness missing");
                best=black?"e7e5":"e2e4";
                emit("info depth 8 multipv 1 score cp 35 nodes 100 pv "+(black?"e7e5 g1f3":"e2e4 e7e5"));
                emit("info depth 9 multipv 1 score cp 999 nodes 110 pv e2e5"); // Invalid PV must not replace a valid result.
                if(multiple){best=black?"d7d5":"d2d4";emit("info depth 8 multipv 2 score cp 10 nodes 120 pv "+best);}
                pending=timer.schedule(()->emit("bestmove "+best),Integer.parseInt(command.substring(command.lastIndexOf(' ')+1)),TimeUnit.MILLISECONDS);
            }else if(command.equals("stop")){if(pending!=null)pending.cancel(false);emit("bestmove "+best);}
            else if(command.equals("quit"))break;
        }}finally{timer.shutdownNow();}
    }
}
