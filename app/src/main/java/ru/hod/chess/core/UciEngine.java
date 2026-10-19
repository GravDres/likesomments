package ru.hod.chess.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One sequential client per native process, with bounded protocol waits. */
public final class UciEngine implements RootMoveEngine {
    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<String> output=new LinkedBlockingQueue<>(8192);
    private volatile boolean closed;
    private int configuredThreads, configuredVariations,configuredSkill=-1;
    public UciEngine(File executable,int threads) throws Exception {
        this(executable,threads,1);
    }
    public UciEngine(File executable,int threads,int variations) throws Exception {
        this(executable,threads,variations,20);
    }
    public UciEngine(File executable,int threads,int variations,int skill) throws Exception {
        process=new ProcessBuilder(executable.getAbsolutePath()).redirectErrorStream(true).start();
        input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        Thread reader=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null&&!closed){if(!output.offer(line)){output.poll();output.offer(line);}}}catch(IOException ignored){}},"uci-output");reader.setDaemon(true);reader.start();
        try {send("uci");await("uciok",8000);send("setoption name UCI_LimitStrength value false");configure(threads,variations,skill);newGame();}catch(Exception e){close();throw e;}
    }
    public String name(){return configuredSkill<20?"Stockfish 19 · уровень "+configuredSkill+"/20":"Stockfish 19";}
    public synchronized void configure(int threads,int variations)throws Exception{
        configure(threads,variations,configuredSkill<0?20:configuredSkill);
    }
    public synchronized void configure(int threads,int variations,int skill)throws Exception{
        int t=Math.max(1,Math.min(4,threads)),v=variations==3?3:1,s=Math.max(0,Math.min(20,skill));
        if(t==configuredThreads&&v==configuredVariations&&s==configuredSkill)return;
        if(t!=configuredThreads){send("setoption name Threads value "+t);send("setoption name Hash value 256");}
        if(s!=configuredSkill)send("setoption name Skill Level value "+s);
        // Skill selection uses at least four root candidates. Request their PVs
        // so a selected weaker bestmove keeps its OWN score and legal line.
        send("setoption name MultiPV value "+(s<20?4:v));send("isready");await("readyok",8000);configuredThreads=t;configuredVariations=v;configuredSkill=s;
    }
    /** Called only for a different game; normal moves retain the engine's search table. */
    public synchronized void newGame()throws Exception{send("ucinewgame");send("isready");await("readyok",8000);}
    private void send(String s)throws IOException{input.write(s);input.newLine();input.flush();}
    private void await(String expected,int millis)throws Exception{
        long end=System.nanoTime()+millis*1_000_000L;while(System.nanoTime()<end){String line=output.poll(100,TimeUnit.MILLISECONDS);if(line!=null&&line.equals(expected))return;if(!process.isAlive())throw new IOException("Шахматный движок завершился");}throw new IOException("Нет ответа движка: "+expected);
    }
    public synchronized void analyze(Position p,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeCommand(p,"position fen "+p.fen(),millis,progress);
    }
    /** Keep the actual game history so Stockfish can evaluate repetitions correctly. */
    public synchronized void analyzeGame(Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeGame(Position.start(),expected,history,millis,progress);
    }
    public synchronized void analyzeGame(Position initial,Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeCommand(expected,positionCommand(initial,expected,history),millis,progress);
    }
    public synchronized void analyzeMoveGame(Position initial,Position expected,List<Move> history,Move move,int millis,Consumer<Analysis> progress)throws Exception{
        if(!expected.legalMoves().contains(move))throw new IllegalArgumentException("Проверяемый ход недопустим");
        analyzeCommand(expected,positionCommand(initial,expected,history),millis," searchmoves "+move.uci(),progress);
    }
    public synchronized void compareMovesGame(Position initial,Position expected,List<Move> history,List<Move> roots,int millis,Consumer<Analysis> progress)throws Exception{
        List<Move> candidates=List.copyOf(roots);
        if(candidates.size()!=2||new HashSet<>(candidates).size()!=2||!expected.legalMoves().containsAll(candidates))throw new IllegalArgumentException("Нужны два разных легальных хода для сравнения");
        if(configuredSkill!=20)throw new IllegalStateException("Для тактического сравнения нужна максимальная сила Stockfish");
        String command=positionCommand(initial,expected,history);
        try{
            send("setoption name MultiPV value 2");send("isready");await("readyok",8000);
            analyzeCommand(expected,command,millis," searchmoves "+candidates.get(0).uci()+" "+candidates.get(1).uci(),new HashSet<>(candidates),progress);
        }finally{
            // Restore the normal search even after cancellation, preserving the
            // interrupt flag so the caller cannot publish an old recommendation.
            boolean interrupted=Thread.interrupted();
            try{if(!closed){send("setoption name MultiPV value "+configuredVariations);send("isready");await("readyok",2000);}}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private String positionCommand(Position initial,Position expected,List<Move> history){
        Position replay=initial;StringBuilder command=new StringBuilder(initial.fen().equals(Position.START)?"position startpos":"position fen "+initial.fen());
        if(!history.isEmpty())command.append(" moves");
        for(Move move:history){replay=replay.play(move);command.append(' ').append(move.uci());}
        if(!replay.fen().equals(expected.fen()))throw new IllegalArgumentException("История не соответствует позиции");
        return command.toString();
    }
    private void analyzeCommand(Position p,String command,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeCommand(p,command,millis,"",progress);
    }
    private void analyzeCommand(Position p,String command,int millis,String restriction,Consumer<Analysis> progress)throws Exception{
        analyzeCommand(p,command,millis,restriction,null,progress);
    }
    private void analyzeCommand(Position p,String command,int millis,String restriction,Set<Move> comparison,Consumer<Analysis> progress)throws Exception{
        if(closed)throw new IOException("Движок остановлен");output.clear();send(command);send("go movetime "+millis+restriction);
        Map<Integer,Analysis.Line> lines=new TreeMap<>();int depth=0;long nodes=0;boolean stopping=false,completed=false;
        Map<Move,Analysis> knownMoves=new HashMap<>();Analysis published=null,completedComparison=null;
        long end=System.nanoTime()+(millis+5000L)*1_000_000L;
        try{
            while(System.nanoTime()<end&&!closed){
                if(Thread.interrupted()){stopping=true;send("stop");end=System.nanoTime()+2000_000_000L;}
                String line=output.poll(50,TimeUnit.MILLISECONDS);if(line==null){if(!process.isAlive())throw new IOException("Движок завершился");continue;}
                if(line.startsWith("bestmove")){
                    if(!stopping){
                        String[] finalTokens=line.split("\\s+");
                        if(finalTokens.length<2)throw new IOException("Нет итогового хода");
                        Move best;
                        try{best=Move.parse(finalTokens[1]);}catch(IllegalArgumentException e){throw new IOException("Некорректный итоговый ход",e);}
                        if(!p.legalMoves().contains(best))throw new IOException("Итоговый ход недопустим в текущей позиции");
                        if(comparison!=null){
                            if(!comparison.contains(best))throw new IOException("Итоговый ход вне сравниваемой пары");
                            if(completedComparison==null)throw new RootMoveEngine.IncompleteComparisonException();
                            progress.accept(completedComparison);completed=true;break;
                        }
                        Analysis chosen=configuredSkill==20&&published!=null&&best.equals(published.bestMove())?published:knownMoves.get(best);
                        if(chosen==null)throw new IOException("Для итогового хода нет подтверждённого варианта");
                        progress.accept(chosen);
                    }
                    completed=true;break;
                }
                if(!line.startsWith("info ")||!line.contains(" pv ")||line.contains("lowerbound")||line.contains("upperbound")||stopping)continue;
                String[] t=line.split("\\s+");int rank=1,cp=0,d=0;boolean scored=false;Integer mate=null;List<Move> pv=new ArrayList<>();long n=nodes;
                try{
                    for(int i=1;i<t.length;i++)switch(t[i]){case "depth":d=Integer.parseInt(t[++i]);break;case "nodes":n=Long.parseLong(t[++i]);break;case "multipv":rank=Integer.parseInt(t[++i]);break;case "score":String type=t[++i];int score=Integer.parseInt(t[++i])*(p.white?1:-1);if(type.equals("mate")){mate=score;scored=true;}else if(type.equals("cp")){cp=score;scored=true;}break;case "pv":Position cursor=p;while(++i<t.length){Move m=Move.parse(t[i]);if(!cursor.legalMoves().contains(m))break;pv.add(m);cursor=cursor.applyUnchecked(m);}break;}
                    if(pv.isEmpty()||!scored||rank<1||d<1)continue;
                    if(comparison!=null&&(rank>2||!comparison.contains(pv.get(0))))continue;
                    knownMoves.put(pv.get(0),new Analysis(name(),d,n,cp,mate,List.of(new Analysis.Line(1,cp,mate,List.copyOf(pv)))));
                    if(d>depth){lines.clear();depth=d;}if(d<depth)continue;nodes=n;lines.put(rank,new Analysis.Line(rank,cp,mate,List.copyOf(pv)));
                    if(lines.containsKey(1)){
                        Analysis.Line first=lines.get(1);published=new Analysis(name(),depth,nodes,first.centipawns(),first.mate(),List.copyOf(lines.values()));
                        if(comparison==null){if(configuredSkill==20)progress.accept(published);}
                        else if(lines.containsKey(2)&&!first.moves().get(0).equals(lines.get(2).moves().get(0)))completedComparison=published;
                    }
                }catch(IllegalArgumentException|IndexOutOfBoundsException ignored){}
            }
        }catch(InterruptedException e){stopping=true;Thread.interrupted();send("stop");awaitBestMove();return;}
        finally{if(stopping)Thread.currentThread().interrupt();}
        if(!completed){close();throw new IOException("Движок не завершил анализ вовремя");}
    }
    private void awaitBestMove()throws Exception{long end=System.nanoTime()+2000_000_000L;while(System.nanoTime()<end){String s=output.poll(50,TimeUnit.MILLISECONDS);if(s!=null&&s.startsWith("bestmove"))return;}close();throw new IOException("Движок не остановился");}
    public void close(){closed=true;try{send("quit");}catch(Exception ignored){}process.destroy();}
}
