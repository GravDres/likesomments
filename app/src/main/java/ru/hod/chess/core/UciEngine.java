package ru.hod.chess.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One sequential client per native process, with bounded protocol waits. */
public final class UciEngine implements ChessEngine {
    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<String> output=new LinkedBlockingQueue<>(8192);
    private volatile boolean closed;
    private int configuredThreads, configuredVariations;
    public UciEngine(File executable,int threads) throws Exception {
        this(executable,threads,1);
    }
    public UciEngine(File executable,int threads,int variations) throws Exception {
        process=new ProcessBuilder(executable.getAbsolutePath()).redirectErrorStream(true).start();
        input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        Thread reader=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null&&!closed){if(!output.offer(line)){output.poll();output.offer(line);}}}catch(IOException ignored){}},"uci-output");reader.setDaemon(true);reader.start();
        try {send("uci");await("uciok",8000);send("setoption name Skill Level value 20");send("setoption name UCI_LimitStrength value false");configure(threads,variations);newGame();}catch(Exception e){close();throw e;}
    }
    public String name(){return "Stockfish 19";}
    public synchronized void configure(int threads,int variations)throws Exception{
        int t=Math.max(1,Math.min(4,threads)),v=variations==3?3:1;
        if(t==configuredThreads&&v==configuredVariations)return;
        if(t!=configuredThreads){send("setoption name Threads value "+t);send("setoption name Hash value 256");}
        send("setoption name MultiPV value "+v);send("isready");await("readyok",8000);configuredThreads=t;configuredVariations=v;
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
        Position replay=initial;StringBuilder command=new StringBuilder(initial.fen().equals(Position.START)?"position startpos":"position fen "+initial.fen());
        if(!history.isEmpty())command.append(" moves");
        for(Move move:history){replay=replay.play(move);command.append(' ').append(move.uci());}
        if(!replay.fen().equals(expected.fen()))throw new IllegalArgumentException("История не соответствует позиции");
        analyzeCommand(expected,command.toString(),millis,progress);
    }
    private void analyzeCommand(Position p,String command,int millis,Consumer<Analysis> progress)throws Exception{
        if(closed)throw new IOException("Движок остановлен");output.clear();send(command);send("go movetime "+millis);
        Map<Integer,Analysis.Line> lines=new TreeMap<>();int depth=0;long nodes=0;boolean stopping=false,completed=false;
        Map<Move,Analysis> knownMoves=new HashMap<>();Analysis published=null;
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
                        Analysis chosen=published!=null&&best.equals(published.bestMove())?published:knownMoves.get(best);
                        if(chosen==null)throw new IOException("Для итогового хода нет подтверждённого варианта");
                        progress.accept(chosen);
                    }
                    completed=true;break;
                }
                if(!line.startsWith("info ")||!line.contains(" pv ")||line.contains("lowerbound")||line.contains("upperbound")||stopping)continue;
                String[] t=line.split(" ");int rank=1,cp=0,d=0;Integer mate=null;List<Move> pv=new ArrayList<>();long n=nodes;
                try{
                    for(int i=1;i<t.length;i++)switch(t[i]){case "depth":d=Integer.parseInt(t[++i]);break;case "nodes":n=Long.parseLong(t[++i]);break;case "multipv":rank=Integer.parseInt(t[++i]);break;case "score":String type=t[++i];int score=Integer.parseInt(t[++i])*(p.white?1:-1);if(type.equals("mate"))mate=score;else cp=score;break;case "pv":Position cursor=p;while(++i<t.length){Move m=Move.parse(t[i]);if(!cursor.legalMoves().contains(m))break;pv.add(m);cursor=cursor.applyUnchecked(m);}break;}
                    if(pv.isEmpty())continue;
                    knownMoves.put(pv.get(0),new Analysis(name(),d,n,cp,mate,List.of(new Analysis.Line(1,cp,mate,List.copyOf(pv)))));
                    if(d>depth){lines.clear();depth=d;}if(d<depth)continue;nodes=n;lines.put(rank,new Analysis.Line(rank,cp,mate,List.copyOf(pv)));
                    if(lines.containsKey(1)){Analysis.Line first=lines.get(1);published=new Analysis(name(),depth,nodes,first.centipawns(),first.mate(),List.copyOf(lines.values()));progress.accept(published);}
                }catch(IllegalArgumentException ignored){}
            }
        }catch(InterruptedException e){Thread.interrupted();send("stop");awaitBestMove();return;}
        finally{if(stopping)Thread.currentThread().interrupt();}
        if(!completed){close();throw new IOException("Движок не завершил анализ вовремя");}
    }
    private void awaitBestMove()throws Exception{long end=System.nanoTime()+2000_000_000L;while(System.nanoTime()<end){String s=output.poll(50,TimeUnit.MILLISECONDS);if(s!=null&&s.startsWith("bestmove"))return;}close();throw new IOException("Движок не остановился");}
    public void close(){closed=true;try{send("quit");}catch(Exception ignored){}process.destroy();}
}
