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
                if(command.contains(" searchmoves ")){
                    String[] roots=command.substring(command.indexOf(" searchmoves ")+13).trim().split(" ");best=roots[0];
                    if(roots.length==1)emit("info depth 10 multipv 1 score cp -250 nodes 200 pv "+best);
                    else{
                        if(roots.length!=2||variations!=2||skill!=20)throw new IllegalStateException("Root comparison must use exactly two PVs at full strength");
                        emit("info depth 12 multipv 1 score cp 35 nodes 200 pv "+roots[0]);
                        if(args.length==0||!args[0].equals("pair-incomplete"))emit("info depth 12 multipv 2 score cp -250 nodes 210 "+(args.length>0&&args[0].equals("pair-bound")?"upperbound ":"")+"pv "+(args.length>0&&args[0].equals("pair-duplicate")?roots[0]:roots[1]));
                        emit("info depth 13 multipv 1 score cp 9999 nodes 220 pv "+roots[1]);
                        emit("info depth 14 multipv 2 score cp"); // Truncated output is ignored.
                        best=roots[1]; // Final bestmove must not mix scores from the unfinished depth.
                    }
                }
                if(args.length>0&&args[0].equals("illegal"))best="e2e5";
                if(args.length>0&&args[0].equals("unreported"))best="g1h3";
                pending=timer.schedule(()->emit("bestmove "+best),Integer.parseInt(command.split(" ")[2]),TimeUnit.MILLISECONDS);
            }else if(command.equals("stop")){if(pending!=null)pending.cancel(false);emit("bestmove "+best);}
            else if(command.equals("quit"))break;
        }}finally{timer.shutdownNow();}
    }
}
