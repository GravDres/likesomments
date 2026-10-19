import java.io.*;
import java.util.concurrent.*;

/** Protocol fixture: asynchronous UCI process which reacts to stop while searching. */
public final class FakeUci {
    private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> pending;
    private static boolean black;
    private static String best;
    private static int variations=1,skill=-1;
    private static boolean unlimited,hashReady,gameReady;
    private static synchronized void emit(String text){System.out.println(text);System.out.flush();}
    public static void main(String[] args)throws Exception{
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in))){String command;while((command=in.readLine())!=null){
            if(command.equals("uci")){emit("id name UCI fixture");emit("uciok");}
            else if(command.equals("isready")){gameReady=true;emit("readyok");}
            else if(command.equals("ucinewgame"))gameReady=false;
            else if(command.startsWith("setoption name Threads value "))hashReady=false;
            else if(command.equals("setoption name Hash value 256"))hashReady=true;
            else if(command.startsWith("setoption name Skill Level value "))skill=Integer.parseInt(command.substring(command.lastIndexOf(' ')+1));
            else if(command.equals("setoption name UCI_LimitStrength value false"))unlimited=true;
            else if(command.startsWith("position fen ")){black=command.contains(" b ");if(command.contains(" moves ")&&command.substring(command.indexOf(" moves ")+7).trim().split(" ").length%2==1)black=!black;}
            else if(command.startsWith("position startpos"))black=command.contains(" moves ")&&command.substring(command.indexOf(" moves ")+7).trim().split(" ").length%2==1;
            else if(command.startsWith("setoption name MultiPV value "))variations=Integer.parseInt(command.substring(command.lastIndexOf(' ')+1));
            else if(command.startsWith("go ")){
                if(!unlimited||skill<0||skill>20||!hashReady||!gameReady)throw new IllegalStateException("Engine configuration or readiness missing");
                if(skill<20&&variations<4)throw new IllegalStateException("Skill selection needs all four candidate PVs");
                best=black?"e7e5":"e2e4";
                emit("info depth 8 multipv 1 score cp 35 nodes 100 pv "+(black?"e7e5 g1f3":"e2e4 e7e5"));
                emit("info depth 9 multipv 1 score cp 999 nodes 110 pv e2e5"); // Invalid PV must not replace a valid result.
                if(variations>=3){best=black?"d7d5":"d2d4";emit("info depth 8 multipv 2 score cp 10 nodes 120 pv "+best);}
                if(variations>=4){emit("info depth 8 multipv 3 score cp -35 nodes 130 pv "+(black?"c7c5":"c2c4"));best=black?"g8f6":"g1f3";emit("info depth 8 multipv 4 score cp -100 nodes 140 pv "+best);}
                if(command.contains(" searchmoves ")){best=command.substring(command.indexOf(" searchmoves ")+13).trim();emit("info depth 10 multipv 1 score cp -250 nodes 200 pv "+best);}
                if(args.length>0)best=args[0].equals("illegal")?"e2e5":"g1h3";
                pending=timer.schedule(()->emit("bestmove "+best),Integer.parseInt(command.split(" ")[2]),TimeUnit.MILLISECONDS);
            }else if(command.equals("stop")){if(pending!=null)pending.cancel(false);emit("bestmove "+best);}
            else if(command.equals("quit"))break;
        }}finally{timer.shutdownNow();}
    }
}
