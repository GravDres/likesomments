import ru.hod.chess.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MaiaPolicyTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        check(EngineMode.fromPreference(null)==EngineMode.MAIA,"new integration defaults to Maia");
        check(MaiaModel.fromPreference(0)==MaiaModel.M2200,"default conditioning is 2200, not a measured Elo claim");
        for(MaiaModel m:MaiaModel.values())check(MaiaModel.fromPreference(m.rating())==m,"rating choice round trip");
        check(TimeControlProfile.fromPreference(null)==TimeControlProfile.BLITZ_3_2,"default time control is 3+2");
        check(TimeControlProfile.BLITZ_3_2.stockfishMillis(4000)==600&&TimeControlProfile.RAPID_10_0.stockfishMillis(300)==1500,"explicit controls affect Stockfish budget");
        check(TimeControlProfile.CUSTOM.stockfishMillis(-99)==300&&TimeControlProfile.CUSTOM.stockfishMillis(99999)==8000,"custom budget is bounded");
        check(MaiaNetwork.fromPreference("23m")==MaiaNetwork.PRECISE&&MaiaNetwork.fromPreference(null)==MaiaNetwork.FAST,"network selection round trip");
        Position start=Position.start(),black=start.play(Move.parse("e2e4"));float[] encoded=MaiaPolicy.encode(start,start,List.of());
        check(encoded.length==64*97,"exact exported input shape");
        int ones=0;boolean binary=true;for(float v:encoded){binary&=v==0||v==1;if(v==1)ones++;}check(binary,"token values are finite one-hot values");check(ones==256,"short history pads 32 pieces across eight frames");
        check(encoded[4*97+5]==1&&encoded[60*97+11]==1,"white input uses Python a1 indexing and correct king channels");
        float[] history=MaiaPolicy.encode(start,black,List.of(Move.parse("e2e4")));
        check(history[4*97+7*12+5]==1,"black-to-move king is mirrored to e1 and represented as own king");
        check(history[36*97+7*12+6]==1,"white pawn e4 becomes opposing pawn e5 for Black");
        check(history[4*97+5]==1,"the oldest padded frame retains its own perspective");
        boolean mismatch=false;try{MaiaPolicy.encode(start,start,List.of(Move.parse("e2e4")));}catch(IllegalArgumentException expected){mismatch=true;}check(mismatch,"bad expected metadata cannot be encoded as another game");
        check(MaiaPolicy.moveIndex(Move.parse("e1g1"),true)==MaiaPolicy.moveIndex(Move.parse("e8g8"),false),"castling indices mirror only ranks");
        for(char role:"qrbn".toCharArray())check(MaiaPolicy.moveIndex(Move.parse("a7a8"+role),true)==MaiaPolicy.moveIndex(Move.parse("a2a1"+role),false),"both-colour promotion vocabulary matches");
        float[] logits=new float[MaiaPolicy.VOCAB];Arrays.fill(logits,100000);for(Move move:start.legalMoves())logits[MaiaPolicy.moveIndex(move,true)]=0;
        Map<Move,Integer> counts=new HashMap<>();boolean allLegal=true,normalized=true;for(int i=0;i<2000;i++){var c=MaiaPolicy.choose(start,logits,true,(i+.5)/2000.0);allLegal&=start.legalMoves().contains(c.move());normalized&=Math.abs(c.probability()-.05)<1e-9;counts.merge(c.move(),1,Integer::sum);}check(allLegal,"illegal high-logit moves cannot be sampled");check(normalized,"probability is normalized over legal moves");check(counts.size()==20&&counts.values().stream().allMatch(n->n==100),"uniform legal policy covers all moves without a preferred opening");
        Arrays.fill(logits,-1000);logits[MaiaPolicy.moveIndex(Move.parse("g1f3"),true)]=1000;check(MaiaPolicy.choose(start,logits,false,.99).move().uci().equals("g1f3"),"argmax chooses the most likely move with stable exponentiation");
        Position checked=Position.fromFen("k3r3/8/8/8/8/8/8/4K2R w K - 0 1");Arrays.fill(logits,0);logits[MaiaPolicy.moveIndex(Move.parse("e1g1"),true)]=100000;
        for(double unit:new double[]{0,.25,.5,.99}){Move m=MaiaPolicy.choose(checked,logits,true,unit).move();check(checked.legalMoves().contains(m)&&!m.uci().equals("e1g1"),"check cannot be escaped by illegal castling");}
        for(Position p:new Position[]{Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1"),Position.fromFen("7k/8/8/8/8/8/p7/7K b - - 0 1")})for(char role:"qrbn".toCharArray()){
            Move promotion=Move.parse(p.white?"a7a8"+role:"a2a1"+role);Arrays.fill(logits,-1000);logits[MaiaPolicy.moveIndex(promotion,p.white)]=1000;check(MaiaPolicy.choose(p,logits,false,0).move().equals(promotion),"all underpromotions remain available to the model");
        }
        logits[0]=Float.NaN;boolean bad=false;try{MaiaPolicy.choose(start,logits,true,.5);}catch(IllegalArgumentException expected){bad=true;}check(bad,"NaN network output is rejected");
        Analysis a=new Analysis("Maia",0,1,0,null,List.of(new Analysis.Line(1,0,null,List.of(Move.parse("e2e4")))),.5);check(a.score().equals("P 50%"),"human move likelihood is not displayed as a position evaluation");
        modelFiles();System.out.println("PASS: "+checks+" Maia adapter assertions (legal policy, history, promotion and model integrity; no measured Elo)");
    }
    private static void modelFiles()throws Exception{
        Path directory=Files.createTempDirectory("hod-maia-test-");File target=directory.resolve("model.onnx").toFile();byte[] bytes="test-data-not-a-real-network".getBytes(java.nio.charset.StandardCharsets.UTF_8);Path reference=directory.resolve("reference");Files.write(reference,bytes);String sha=ModelStore.hash(reference.toFile());AtomicInteger opens=new AtomicInteger();
        try{
            ModelStore.ensure(target,sha,()->{opens.incrementAndGet();return new ByteArrayInputStream(bytes);});check(Arrays.equals(Files.readAllBytes(target.toPath()),bytes),"verified model extracted atomically");
            ModelStore.ensure(target,sha,()->{throw new IOException("must reuse");});check(opens.get()==1,"cached correct model needs no asset reopen");
            Files.writeString(target.toPath(),"corrupt");ModelStore.ensure(target,sha,()->new ByteArrayInputStream(bytes));check(ModelStore.hash(target).equals(sha),"corrupted cache is repaired from bundled data");
            boolean corrupt=false;try{ModelStore.ensure(target,"0".repeat(64),()->new ByteArrayInputStream(bytes));}catch(IOException expected){corrupt=true;}check(corrupt&&ModelStore.hash(target).equals(sha),"invalid replacement cannot overwrite the previous verified model");
            boolean missing=false;try{ModelStore.ensure(target,null,()->new ByteArrayInputStream(bytes));}catch(IOException expected){missing=true;}check(missing,"missing manifest checksum cannot load a model");
            try(var files=Files.list(directory)){check(files.noneMatch(p->p.toString().endsWith(".part")),"failed extraction leaves no partial model");}
        }finally{try(var files=Files.list(directory)){for(Path p:files.toList())Files.deleteIfExists(p);}Files.delete(directory);}
    }
}
