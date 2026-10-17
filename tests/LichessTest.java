import ru.hod.chess.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Rendered Cburnett artwork and Lichess colours, not screenshots of an Android device. */
public final class LichessTest {
    private static int checks;private static BufferedImage atlas;
    private static final String PIECES="PNBRQKpnbrqk";
    private static int[][] THEMES;private static String[] themeNames;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        atlas=ImageIO.read(Path.of("tests/fixtures/lichess/cburnett-atlas.png").toFile());
        loadThemes();check(THEMES.length==25,"all fixed colour palettes from Lichess chessground catalog loaded");
        check(ChessPlatform.fromPreference(null)==ChessPlatform.CHESS_COM,"legacy installations keep their original storage");
        check(ChessPlatform.fromPreference("lichess")==ChessPlatform.LICHESS,"Lichess preference restored");
        check(ChessPlatform.CHESS_COM.sessionFile().equals("last-game.hod"),"old game needs no destructive migration");
        check(!ChessPlatform.CHESS_COM.sessionFile().equals(ChessPlatform.LICHESS.sessionFile())&&!ChessPlatform.CHESS_COM.archiveFile().equals(ChessPlatform.LICHESS.archiveFile()),"histories and archives cannot collide");
        check(ChessPlatform.LICHESS.packages()[0].equals("org.lichess.mobileV2"),"official Flutter application is preferred");
        String[] copy=ChessPlatform.LICHESS.packages();copy[0]="wrong";check(!ChessPlatform.LICHESS.packages()[0].equals("wrong"),"package list is immutable");
        String[] opening={"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","a7a6","c1e3","e7e5","d4b3","c8e6","f1e2","f8e7","e1g1","e8g8"};
        for(int theme=0;theme<THEMES.length;theme++)for(boolean flipped:new boolean[]{false,true}){
            int size=theme%2==0?576:512;BoardVision.Region region=new BoardVision.Region(0,0,size);int[] initial=render(Position.start(),size,flipped,theme,false,false,null);
            BoardLocator.Detection found=new BoardLocator().find(initial,size,size);
            check(found!=null&&found.automatic()&&found.initialLayout()&&found.orientationKnown()&&found.whiteBottom()!=flipped,"Cburnett initial board detected: theme="+theme+" flipped="+flipped+" result="+found);
            BoardVision vision=new BoardVision(!flipped);vision.calibrate(initial,size,size,region);assertRead(vision,Position.start(),initial,size,region,"initial "+theme+"/"+flipped);
            GameTracker game=new GameTracker(Position.start());Position p=Position.start();
            for(String uci:opening){Move move=Move.parse(uci);p=p.play(move);int[] pixels=render(p,size,flipped,theme,true,true,move);BoardVision.Observation seen=vision.recognize(pixels,size,size,region);
                check(seen.uncertain()==0&&p.matches(seen.board()),"highlighted move "+uci+" theme="+theme+" flipped="+flipped+" uncertain="+seen.uncertain()+" mismatches="+mismatches(p,seen));
                check(game.accept(seen.board()).status()==GameTracker.Status.MOVED,"every observed move remains legal, including castling");
            }
            check(game.position().fen().equals(p.fen())&&game.history().size()==opening.length,"full history, castling rights and turn preserved");
            for(String kind:new String[]{"q","r","b","n"}){
                Position promoted=Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1").play(Move.parse("a7a8"+kind));
                assertRead(vision,promoted,render(promoted,size,flipped,theme,true,true,null),size,region,"promotion "+kind);
            }
            Position ep=CoreTest.play(Position.start(),"e2e4","a7a6","e4e5","d7d5","e5d6");assertRead(vision,ep,render(ep,size,flipped,theme,true,true,Move.parse("e5d6")),size,region,"en passant");
        }
        layoutsAndRefresh(opening);
        System.out.println("PASS: "+checks+" Lichess assertions (Cburnett renders, "+THEMES.length+" palettes, both sides; not device screenshots or real textures)");
    }
    private static void loadThemes()throws Exception{
        String json=Files.readString(Path.of("tests/fixtures/lichess/palettes.json"));
        var pattern=java.util.regex.Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"light\\\"\\s*:\\s*\\\"(0x[0-9a-f]+)\\\"\\s*,\\s*\\\"dark\\\"\\s*:\\s*\\\"(0x[0-9a-f]+)\\\"");
        var matcher=pattern.matcher(json);var names=new ArrayList<String>();var colors=new ArrayList<int[]>();while(matcher.find()){names.add(matcher.group(1));colors.add(new int[]{Long.decode(matcher.group(2)).intValue(),Long.decode(matcher.group(3)).intValue()});}
        THEMES=colors.toArray(new int[0][]);themeNames=names.toArray(new String[0]);
    }
    private static String mismatches(Position p,BoardVision.Observation seen){StringBuilder s=new StringBuilder();for(int i=0;i<64;i++)if(p.at(i)!=seen.board()[i])s.append(Position.square(i)).append(':').append(p.at(i)).append('/').append(seen.board()[i]).append(' ');return s.toString();}
    private static void assertRead(BoardVision vision,Position p,int[] pixels,int size,BoardVision.Region region,String label){var o=vision.recognize(pixels,size,size,region);check(o.uncertain()==0&&p.matches(o.board()),label+": uncertain="+o.uncertain()+" "+mismatches(p,o));}
    private static void layoutsAndRefresh(String[] moves)throws Exception{
        BoardVision model=new BoardVision(true);model.calibrate(render(Position.start(),576,false,0,false,true,null),576,576,new BoardVision.Region(0,0,576));
        GameTracker game=GameTracker.replay(java.util.List.of(Move.parse("e2e4"),Move.parse("c7c5")));String fen=game.position().fen();
        for(int[] layout:new int[][]{{720,1440,0,300,720},{720,1440,24,470,672},{1440,720,24,24,672},{720,1440,84,780,552}})for(boolean flipped:new boolean[]{false,true}){
            int w=layout[0],h=layout[1],left=layout[2],top=layout[3],size=layout[4];int[] screen=new int[w*h];Arrays.fill(screen,0xff161512);
            int[] board=render(game.position(),size,flipped,0,true,true,Move.parse("c7c5"));for(int y=0;y<size;y++)System.arraycopy(board,y*size,screen,(top+y)*w+left,size);
            AutoBoardSearch finder=new AutoBoardSearch(false,"Lichess");var first=finder.inspect(screen,w,h,model,game,new BoardVision.Region(0,300,720));
            check(first.state()==AutoBoardSearch.State.VERIFYING,"phone/immersive/landscape layout "+Arrays.toString(layout)+" flipped="+flipped+": "+first);
            var next=finder.inspect(screen,w,h,model,game);check(next.state()==AutoBoardSearch.State.READY&&next.calibration().reuseGame()&&next.calibration().whiteBottom()!=flipped,"layout switch retains game on independent frame");
            check(game.position().fen().equals(fen),"search does not alter current game");
        }
        byte[] lichess=GameMemory.encode(game,model);GameTracker other=GameTracker.replay(java.util.List.of(Move.parse("d2d4"),Move.parse("d7d5")));byte[] chesscom=GameMemory.encode(other,model);
        check(GameMemory.decode(lichess).game().position().fen().equals(fen)&&GameMemory.decode(chesscom).game().position().fen().equals(other.position().fen()),"independent platform memories restore different openings");
        int[] blank=new int[720*1440];Arrays.fill(blank,0xff161512);var searching=new AutoBoardSearch(false,"Lichess").inspect(blank,720,1440,null,null);check(searching.message().contains("Lichess")&&!searching.message().contains("Chess.com"),"Lichess messages name correct application");
    }
    static int[] render(Position p,int size,boolean flipped,int theme,boolean highlight,boolean coordinates,Move last){
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        for(int display=0;display<64;display++){
            int row=display/8,col=display%8,x=Math.round(col*size/8f),y=Math.round(row*size/8f),x1=Math.round((col+1)*size/8f),y1=Math.round((row+1)*size/8f),logical=flipped?63-display:display;int bg=THEMES[theme][(row+col)&1];g.setColor(new Color(bg,true));g.fillRect(x,y,x1-x,y1-y);
            if(highlight&&last!=null&&(logical==last.from()||logical==last.to())){g.setColor(new Color(themeNames[theme].equals("green")?0x69009bc7:0x809cc700,true));g.fillRect(x,y,x1-x,y1-y);}
            if(coordinates){g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,Math.max(8,size/72)));g.setColor(new Color(THEMES[theme][1-((row+col)&1)],true));if(col==0)g.drawString(Integer.toString(flipped?row+1:8-row),x+2,y+Math.max(9,size/72));if(row==7)g.drawString(Character.toString((char)((flipped?'h':'a')+(flipped?-col:col))),x1-Math.max(8,size/72),y1-2);}
            char piece=p.at(logical);if(piece!='.'){int sprite=PIECES.indexOf(piece);g.drawImage(atlas,x,y,x1,y1,sprite*144,0,(sprite+1)*144,144,null);}
        }
        g.dispose();return image.getRGB(0,0,size,size,null,0,size);
    }
}
