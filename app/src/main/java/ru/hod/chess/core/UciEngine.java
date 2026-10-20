package ru.hod.chess.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One sequential Caissa process. Never reuses an undrained or timed-out search. */
public final class UciEngine implements HistoryEngine {
    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<String> output=new LinkedBlockingQueue<>(4096);
    private volatile boolean closed;
    private String engineName;
    private final Set<String> options=new HashSet<>();
    private int configuredThreads,configuredVariations,configuredHash;
    public UciEngine(File executable,File network,int threads,int variations,int hashMiB)throws Exception{
        Objects.requireNonNull(network);
        String path=network.getAbsolutePath();
        if(!network.isFile()||path.indexOf('\n')>=0||path.indexOf('\r')>=0)throw new IOException("Нет файла сети Caissa");
        process=new ProcessBuilder(executable.getAbsolutePath()).redirectErrorStream(true).start();
        input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        Thread reader=new Thread(()->{
            try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=r.readLine())!=null&&!closed){if(!output.offer(line)){close();break;}}
            }catch(IOException ignored){}
        },"caissa-output");reader.setDaemon(true);reader.start();
        try{
            send("uci");await("uciok",15000,line->{
                if(line.startsWith("id name "))engineName=line.substring(8).trim();
                if(line.startsWith("option name ")&&line.contains(" type "))options.add(line.substring(12,line.indexOf(" type ")));
            });
            if(engineName==null||!engineName.startsWith("Caissa 2.0 "))throw new IOException("В APK неверный движок: требуется Caissa 2.0");
            // Explicit DATA path. Require upstream's success acknowledgement, never HCE fallback.
            set("EvalFile",path);send("isready");
            boolean[] loaded={false};await("readyok",15000,line->{if(line.equals("info string Loaded neural network: "+path))loaded[0]=true;});
            if(!loaded[0])throw new IOException("Caissa не загрузила нейросеть. Расчёт без сети отключён");
            set("EvalRandomization","0");set("Ponder","false");set("UseSAN","false");
            set("ColorConsoleOutput","false");set("UCI_Chess960","false");set("MoveOverhead","20");
            configure(threads,variations,hashMiB);newGame();
        }catch(Exception e){close();throw e;}
    }
    public String name(){return engineName==null?"Caissa 2.0":engineName;}
    public boolean isRunning(){return !closed&&process.isAlive();}
    public synchronized void configure(int threads,int variations,int hashMiB)throws Exception{
        requireOpen();int t=Math.max(1,Math.min(4,threads)),v=variations==3?3:1,h=hashMiB<=64?64:hashMiB<=128?128:256;
        if(t==configuredThreads&&v==configuredVariations&&h==configuredHash)return;
        try{
            if(t!=configuredThreads)set("Threads",Integer.toString(t));
            if(h!=configuredHash)set("Hash",Integer.toString(h));
            if(v!=configuredVariations)set("MultiPV",Integer.toString(v));
            send("isready");await("readyok",8000,s->{});
            configuredThreads=t;configuredVariations=v;configuredHash=h;
        }catch(Exception failed){close();throw failed;}
    }
    public synchronized void configure(int threads,int variations)throws Exception{configure(threads,variations,configuredHash);}
    /** Only a new game clears search memory. Every move receives the complete known history. */
    public synchronized void newGame()throws Exception{requireOpen();send("ucinewgame");send("isready");await("readyok",8000,s->{});}
    private void set(String name,String value)throws IOException{
        if(!options.contains(name))throw new IOException("Caissa не поддерживает параметр "+name);
        send("setoption name "+name+" value "+value);
    }
    private void requireOpen()throws IOException{if(closed||!process.isAlive())throw new IOException("Caissa остановлена");}
    private void send(String command)throws IOException{requireOpen();input.write(command);input.newLine();input.flush();}
    private void await(String expected,int millis,Consumer<String> observer)throws Exception{
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(System.nanoTime()<end){
            requireOpen();String line=output.poll(50,TimeUnit.MILLISECONDS);if(line==null)continue;
            if(line.equals(expected))return;checkError(line);observer.accept(line);
        }
        throw new IOException("Caissa не ответила: "+expected);
    }
    private static void checkError(String line)throws IOException{
        if(line.startsWith("Invalid ")||line.startsWith("ERROR")||line.startsWith("Error:"))throw new IOException("Ошибка протокола Caissa");
    }
    public synchronized void analyze(Position p,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeCommand(p,"position fen "+p.fen(),millis,progress);
    }
    public synchronized void analyzeGame(Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception{
        analyzeGame(Position.start(),expected,history,millis,progress);
    }
    public synchronized void analyzeGame(Position initial,Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception{
        Position replay=initial;
        StringBuilder command=new StringBuilder(initial.fen().equals(Position.START)?"position startpos":"position fen "+initial.fen());
        List<Move> moves=List.copyOf(history);if(!moves.isEmpty())command.append(" moves");
        for(Move m:moves){if(Thread.currentThread().isInterrupted())throw new InterruptedException();replay=replay.play(m);command.append(' ').append(m.uci());}
        if(!replay.fen().equals(expected.fen()))throw new IllegalArgumentException("История не соответствует позиции");
        analyzeCommand(expected,command.toString(),millis,progress);
    }
    private void analyzeCommand(Position p,String command,int millis,Consumer<Analysis> progress)throws Exception{
        requireOpen();if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(millis<1||millis>8000)throw new IllegalArgumentException("Неверный бюджет расчёта");
        List<Move> legal=p.legalMoves();
        if(legal.isEmpty()){progress.accept(new Analysis(name(),0,0,0,p.inCheck(p.white)?0:null,List.of()));return;}
        output.clear();send(command);send("go movetime "+millis);
        Map<Move,Analysis> known=new HashMap<>();Map<Integer,Analysis.Line> lines=new TreeMap<>();
        int depth=0;long nodes=0;Analysis published=null;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis+5000L);
        try{
            while(System.nanoTime()<deadline){
                requireOpen();String line=output.poll(50,TimeUnit.MILLISECONDS);if(line==null)continue;checkError(line);
                if(line.startsWith("bestmove ")){
                    String[] tokens=line.trim().split("\\s+");Move best;
                    try{best=Move.parse(tokens[1]);}catch(IllegalArgumentException|IndexOutOfBoundsException bad){throw new IOException("Caissa вернула неверный ход",bad);}
                    if(!legal.contains(best))throw new IOException("Ход Caissa недопустим в текущей позиции");
                    Analysis answer=published!=null&&best.equals(published.bestMove())?published:known.get(best);
                    if(answer==null)throw new IOException("Нет подтверждённого варианта для итогового хода Caissa");
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    progress.accept(answer);return;
                }
                if(!line.startsWith("info ")||!line.contains(" pv ")||line.contains("lowerbound")||line.contains("upperbound"))continue;
                String[] t=line.trim().split("\\s+");int rank=1,d=0,cp=0;Integer mate=null;long n=nodes;boolean scored=false;List<Move> pv=new ArrayList<>();
                try{
                    for(int i=1;i<t.length;i++)switch(t[i]){
                        case "depth":d=Integer.parseInt(t[++i]);break;
                        case "nodes":n=Long.parseLong(t[++i]);break;
                        case "multipv":rank=Integer.parseInt(t[++i]);break;
                        case "score":String type=t[++i];int score=Integer.parseInt(t[++i])*(p.white?1:-1);if(type.equals("cp")){cp=score;scored=true;}else if(type.equals("mate")){mate=score;scored=true;}break;
                        case "pv":Position cursor=p;while(++i<t.length){Move m=Move.parse(t[i]);if(!cursor.legalMoves().contains(m))break;pv.add(m);cursor=cursor.applyUnchecked(m);}break;
                    }
                    if(!scored||pv.isEmpty()||rank<1||rank>configuredVariations||d<1||n<0)continue;
                    Analysis candidate=new Analysis(name(),d,n,cp,mate,List.of(new Analysis.Line(1,cp,mate,List.copyOf(pv))));
                    Analysis previous=known.get(pv.get(0));if(previous==null||d>=previous.depth())known.put(pv.get(0),candidate);
                    if(d>depth){lines.clear();depth=d;}if(d<depth)continue;nodes=n;
                    lines.put(rank,new Analysis.Line(rank,cp,mate,List.copyOf(pv)));
                    if(lines.containsKey(1)){
                        Analysis.Line first=lines.get(1);published=new Analysis(name(),depth,nodes,first.centipawns(),first.mate(),List.copyOf(lines.values()));
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();progress.accept(published);
                    }
                }catch(IllegalArgumentException|IndexOutOfBoundsException malformed){}
            }
            throw new IOException("Caissa не завершила расчёт вовремя");
        }catch(InterruptedException cancelled){
            // Drain exactly this search. A future query must never consume its bestmove.
            Thread.interrupted();try{send("stop");awaitBestMove();}catch(Exception failure){close();}
            Thread.currentThread().interrupt();throw cancelled;
        }catch(Exception failure){close();throw failure;}
    }
    private void awaitBestMove()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end){requireOpen();String line=output.poll(50,TimeUnit.MILLISECONDS);if(line!=null&&line.startsWith("bestmove "))return;}
        throw new IOException("Caissa не остановилась");
    }
    /** Nonblocking even when the native process is stuck; safe from the Android main thread. */
    public void close(){closed=true;process.destroyForcibly();}
}
