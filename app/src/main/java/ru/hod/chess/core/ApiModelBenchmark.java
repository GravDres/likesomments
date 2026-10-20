package ru.hod.chess.core;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** A small protocol/tactics smoke test. Its scores are not an Elo estimate. */
public final class ApiModelBenchmark implements AutoCloseable {
    public interface Factory { ApiEngine create(ApiModel model) throws Exception; }
    public record Puzzle(String label, Position initial, List<Move> history, String expected) {
        public Puzzle { history = List.copyOf(history); }
        public Position position(){Position p=initial;for(Move move:history)p=p.play(move);return p;}
    }
    public static final List<Puzzle> PUZZLES = List.of(
            new Puzzle("Мат в один ход за белых",Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1"),List.of(),"d1d8"),
            new Puzzle("Мат за чёрных с историей",Position.start(),List.of(Move.parse("f2f3"),Move.parse("e7e5"),Move.parse("g2g4")),"d8h4"),
            new Puzzle("Выход из шаха",Position.fromFen("7k/8/8/8/8/8/4q3/4K3 w - - 0 1"),List.of(),"e1e2"),
            new Puzzle("Вилка конём на короля и ферзя",Position.fromFen("6k1/7q/8/3N4/8/8/8/6K1 w - - 0 1"),List.of(),"d5f6"));
    public static final int MAX_REQUESTS = ApiModel.values().length * PUZZLES.size();
    public static final long MAX_MEDIAN_MILLIS=3000,MAX_SINGLE_MILLIS=6000;
    public record Result(ApiModel model, int correct, int attempts, List<Long> millis, String problem) {
        public Result { millis=List.copyOf(millis); }
        public boolean qualified(){return correct==PUZZLES.size()&&attempts==PUZZLES.size()&&millis.size()==PUZZLES.size()&&problem==null&&medianMillis()<=MAX_MEDIAN_MILLIS&&millis.stream().allMatch(t->t<=MAX_SINGLE_MILLIS);}
        public long medianMillis(){if(millis.isEmpty())return Long.MAX_VALUE;List<Long> sorted=new ArrayList<>(millis);Collections.sort(sorted);int n=sorted.size();return n%2==0?(sorted.get(n/2-1)+sorted.get(n/2))/2:sorted.get(n/2);}
    }
    public record Progress(int attempted, int maximum, ApiModel model, String puzzle) {}
    public record Report(List<Result> results, ApiModel winner) {
        public Report { results=List.copyOf(results); }
        public String summary(){
            StringBuilder text=new StringBuilder(winner==null?"Ни одна модель не прошла четыре позиции и пределы времени. Выбор не изменён.":"Выбрана "+winner.label()+": наименьшая медиана среди прошедших 4/4 в пределах времени.");
            for(Result r:results)text.append('\n').append(r.model().label()).append(": ").append(r.correct()).append("/4 · ").append(r.attempts()).append(" попыток").append(r.millis().isEmpty()?"":" · медиана "+r.medianMillis()+" мс").append(r.problem()==null?"":" · "+r.problem());
            return text.append("\nПределы: медиана до 3 с, каждый ответ до 6 с. Это четыре простые позиции, не измерение 2000 Elo и не гарантия скорости следующего запроса.").toString();
        }
    }
    private static final class Sample {
        final ApiModel model;int correct,attempts;final List<Long> times=new ArrayList<>();String problem;
        Sample(ApiModel model){this.model=model;}
        Result result(){return new Result(model,correct,attempts,times,problem);}
    }
    private final Factory factory;
    private final LongSupplier clock;
    private volatile boolean closed;
    private volatile ApiEngine active;
    public ApiModelBenchmark(Factory factory){this(factory,System::nanoTime);}
    public ApiModelBenchmark(Factory factory,LongSupplier clock){this.factory=Objects.requireNonNull(factory);this.clock=Objects.requireNonNull(clock);}
    public Report run(Consumer<Progress> progress)throws InterruptedException{
        Sample[] samples=Arrays.stream(ApiModel.values()).map(Sample::new).toArray(Sample[]::new);int attempted=0;
        for(int round=0;round<PUZZLES.size();round++){
            Puzzle puzzle=PUZZLES.get(round);
            for(int offset=0;offset<samples.length;offset++){
                requireActive();Sample sample=samples[(round+offset)%samples.length];if(sample.problem!=null)continue;
                progress.accept(new Progress(attempted+1,MAX_REQUESTS,sample.model,puzzle.label()));requireActive();
                ApiEngine engine=null;long start=clock.getAsLong();sample.attempts++;attempted++;
                try{
                    engine=factory.create(sample.model);active=engine;requireActive();
                    final Analysis[] answer={null};engine.analyzeGame(puzzle.initial(),puzzle.position(),puzzle.history(),0,a->answer[0]=a);
                    requireActive();long elapsed=Math.max(0,(clock.getAsLong()-start)/1_000_000);sample.times.add(elapsed);
                    if(answer[0]!=null&&answer[0].bestMove()!=null&&puzzle.expected().equals(answer[0].bestMove().uci()))sample.correct++;
                    else sample.problem="Не решена позиция: "+puzzle.label();
                    if(sample.problem==null&&elapsed>MAX_SINGLE_MILLIS)sample.problem="Отклик превысил 6 с; модель исключена";
                }catch(InterruptedException cancelled){throw cancelled;}
                catch(Exception failure){sample.problem="Ошибка API; модель исключена из этой проверки";}
                finally{if(engine!=null)engine.close();active=null;}
            }
        }
        requireActive();List<Result> results=Arrays.stream(samples).map(Sample::result).toList();
        ApiModel winner=results.stream().filter(Result::qualified).min(Comparator.comparingLong(Result::medianMillis).thenComparingInt(r->r.model().ordinal())).map(Result::model).orElse(null);
        return new Report(results,winner);
    }
    private void requireActive()throws InterruptedException{if(closed||Thread.currentThread().isInterrupted())throw new InterruptedException("Проверка моделей отменена");}
    public void close(){closed=true;ApiEngine engine=active;if(engine!=null)engine.close();}
}
