package ru.hod.chess.core;

import java.util.*;

/** Board indexing: a8 = 0, h1 = 63. Immutable legal positions. */
public final class Position {
    public static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private final char[] board;
    public final boolean white;
    public final int rights, ep, halfmove, fullmove;
    private Position(char[] b, boolean w, int r, int e, int h, int f) { board=b; white=w; rights=r; ep=e; halfmove=h; fullmove=f; }
    public static Position start() { return fromFen(START); }
    public char at(int i) { return board[i]; }
    public char[] pieces() { return board.clone(); }
    public static int index(String s) {
        if(s == null || !s.matches("[a-h][1-8]")) throw new IllegalArgumentException("Некорректная клетка");
        return (8-(s.charAt(1)-'0'))*8+s.charAt(0)-'a';
    }
    public static String square(int i) { return ""+(char)('a'+i%8)+(8-i/8); }
    public static boolean isWhite(char p) { return p >= 'A' && p <= 'Z'; }
    public static Position fromFen(String fen) {
        String[] f=fen.trim().split("\\s+");
        if(f.length!=6 || !f[1].matches("[wb]") || !f[2].matches("-|K?Q?k?q?")) throw new IllegalArgumentException("Нужен полный FEN из 6 полей");
        String[] rows=f[0].split("/"); if(rows.length!=8) throw new IllegalArgumentException("Нужно 8 горизонталей");
        char[] b=new char[64]; Arrays.fill(b,'.'); int wk=0,bk=0;
        for(int r=0;r<8;r++) { int c=0; for(char p:rows[r].toCharArray()) {
            if(p>='1'&&p<='8') c+=p-'0';
            else { if("pnbrqkPNBRQK".indexOf(p)<0 || c>=8) throw new IllegalArgumentException("Некорректные фигуры"); b[r*8+c++]=p; if(p=='K')wk++; if(p=='k')bk++; }
        } if(c!=8) throw new IllegalArgumentException("Нужно 8 клеток в горизонтали"); }
        if(wk!=1||bk!=1) throw new IllegalArgumentException("На доске должно быть два короля");
        int rights=0; for(int i=0;i<4;i++) if(f[2].indexOf("KQkq".charAt(i))>=0)rights|=1<<i;
        int e=f[3].equals("-")?-1:index(f[3]);
        if(e>=0 && ((f[1].equals("w")&&e/8!=2)|| (f[1].equals("b")&&e/8!=5))) throw new IllegalArgumentException("Некорректное поле en passant");
        int h=Integer.parseInt(f[4]), n=Integer.parseInt(f[5]); if(h<0||n<1)throw new IllegalArgumentException("Некорректные счётчики");
        Position position=new Position(b,f[1].equals("w"),rights,e,h,n);
        int whitePieces=0,blackPieces=0,whitePawns=0,blackPawns=0;
        for(int i=0;i<64;i++){char piece=b[i];if(piece=='.')continue;if(isWhite(piece))whitePieces++;else blackPieces++;
            if(Character.toLowerCase(piece)=='p'){if(i/8==0||i/8==7)throw new IllegalArgumentException("Пешка на последней горизонтали должна превратиться");if(isWhite(piece))whitePawns++;else blackPawns++;}}
        if(whitePieces>16||blackPieces>16||whitePawns>8||blackPawns>8)throw new IllegalArgumentException("Слишком много фигур");
        if((rights&3)!=0&&b[60]!='K'||(rights&12)!=0&&b[4]!='k'||(rights&1)!=0&&b[63]!='R'||(rights&2)!=0&&b[56]!='R'||(rights&4)!=0&&b[7]!='r'||(rights&8)!=0&&b[0]!='r')throw new IllegalArgumentException("Права рокировки не соответствуют фигурам");
        if(e>=0&&(b[e]!='.'||b[e+(position.white?8:-8)]!=(position.white?'p':'P')))throw new IllegalArgumentException("Нет пешки для взятия на проходе");
        if(position.inCheck(!position.white))throw new IllegalArgumentException("Король стороны, которая уже сходила, не может оставаться под шахом");
        return position;
    }
    public String fen() {
        StringBuilder s=new StringBuilder();
        for(int r=0;r<8;r++){int n=0;for(int c=0;c<8;c++){char p=board[r*8+c];if(p=='.')n++;else{if(n>0){s.append(n);n=0;}s.append(p);}}if(n>0)s.append(n);if(r<7)s.append('/');}
        s.append(white?" w ":" b "); if(rights==0)s.append('-'); else for(int i=0;i<4;i++)if((rights&(1<<i))!=0)s.append("KQkq".charAt(i));
        return s+" "+(ep<0?"-":square(ep))+" "+halfmove+" "+fullmove;
    }
    public boolean matches(char[] b) { return Arrays.equals(board,b); }
    public boolean inCheck(boolean w) { int king=-1; for(int i=0;i<64;i++)if(board[i]==(w?'K':'k'))king=i; return king<0||attacked(king,!w); }
    public boolean attacked(int sq, boolean byWhite) {
        int r=sq/8,c=sq%8;
        int pawnRow=r+(byWhite?1:-1);
        for(int dc:new int[]{-1,1})if(valid(pawnRow,c+dc)&&board[pawnRow*8+c+dc]==(byWhite?'P':'p'))return true;
        for(int[] d:KNIGHT)if(valid(r+d[0],c+d[1])&&board[(r+d[0])*8+c+d[1]]==(byWhite?'N':'n'))return true;
        for(int[] d:KING) {
            int rr=r+d[0],cc=c+d[1],dist=1;
            while(valid(rr,cc)) { char p=board[rr*8+cc]; if(p!='.') {
                if(isWhite(p)==byWhite){char q=Character.toLowerCase(p);if((dist==1&&q=='k')||q=='q'||(d[0]*d[1]==0?q=='r':q=='b'))return true;} break;
            } rr+=d[0];cc+=d[1];dist++; }
        } return false;
    }
    private static final int[][] KNIGHT={{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
    private static final int[][] KING={{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
    private static boolean valid(int r,int c){return r>=0&&r<8&&c>=0&&c<8;}
    private void add(List<Move> m,int a,int b){if(Character.toLowerCase(board[a])=='p'&&(b/8==0||b/8==7))for(char p:"qrbn".toCharArray())m.add(new Move(a,b,p));else m.add(new Move(a,b));}
    public List<Move> legalMoves() {
        List<Move> moves=new ArrayList<>();
        for(int i=0;i<64;i++){char p=board[i];if(p=='.'||isWhite(p)!=white)continue;int r=i/8,c=i%8;
            switch(Character.toLowerCase(p)){
                case 'p': {
                    int dr=white?-1:1,rr=r+dr;
                    if(valid(rr,c)&&board[rr*8+c]=='.'){add(moves,i,rr*8+c);if(r==(white?6:1)&&board[(r+2*dr)*8+c]=='.')add(moves,i,(r+2*dr)*8+c);}
                    for(int dc:new int[]{-1,1})if(valid(rr,c+dc)){int t=rr*8+c+dc;char q=board[t];if((q!='.'&&isWhite(q)!=white&&Character.toLowerCase(q)!='k')||(t==ep&&board[t-dr*8]==(white?'p':'P')))add(moves,i,t);} break;
                }
                case 'n': for(int[]d:KNIGHT)if(valid(r+d[0],c+d[1])){int t=(r+d[0])*8+c+d[1];char q=board[t];if(q=='.'||(isWhite(q)!=white&&Character.toLowerCase(q)!='k'))add(moves,i,t);}break;
                default:
                    char k=Character.toLowerCase(p);
                    for(int[]d:KING){if(k=='b'&&d[0]*d[1]==0||k=='r'&&d[0]*d[1]!=0)continue;
                        int rr=r+d[0],cc=c+d[1];while(valid(rr,cc)){int t=rr*8+cc;char q=board[t];if(q!='.'&&isWhite(q)==white)break;if(Character.toLowerCase(q)!='k')add(moves,i,t);if(q!='.'||k=='k')break;rr+=d[0];cc+=d[1];}}
                    if(k=='k'&&i==(white?60:4)&&!inCheck(white)) {
                        int bit=white?1:4;
                        if((rights&bit)!=0&&board[i+1]=='.'&&board[i+2]=='.'&&board[i+3]==(white?'R':'r')&&!attacked(i+1,!white)&&!attacked(i+2,!white))add(moves,i,i+2);
                        if((rights&(bit<<1))!=0&&board[i-1]=='.'&&board[i-2]=='.'&&board[i-3]=='.'&&board[i-4]==(white?'R':'r')&&!attacked(i-1,!white)&&!attacked(i-2,!white))add(moves,i,i-2);
                    }
            }
        }
        moves.removeIf(m->applyUnchecked(m).inCheck(white)); return moves;
    }
    public Position play(Move m) { if(!legalMoves().contains(m))throw new IllegalArgumentException("Недопустимый ход: "+m.uci());return applyUnchecked(m); }
    /** Call only with a legal move generated from this position. */
    public Position applyUnchecked(Move m) {
        char[] b=board.clone();char p=b[m.from()],captured=b[m.to()];int r=rights,e=-1; b[m.from()]='.';b[m.to()]=p;
        if(Character.toLowerCase(p)=='p'){
            if(m.to()==ep&&captured=='.')b[m.to()+(white?8:-8)]='.';
            if(Math.abs(m.to()-m.from())==16)e=(m.from()+m.to())/2;
            if(m.promotion()!='\0')b[m.to()]=white?Character.toUpperCase(m.promotion()):m.promotion();
        }
        if(Character.toLowerCase(p)=='k') {r&=white?~3:~12;if(Math.abs(m.to()-m.from())==2){int rook=m.to()>m.from()?m.from()+3:m.from()-4;int dst=(m.to()+m.from())/2;b[dst]=b[rook];b[rook]='.';}}
        if(m.from()==63||m.to()==63)r&=~1;if(m.from()==56||m.to()==56)r&=~2;if(m.from()==7||m.to()==7)r&=~4;if(m.from()==0||m.to()==0)r&=~8;
        return new Position(b,!white,r,e,Character.toLowerCase(p)=='p'||captured!='.'?0:halfmove+1,fullmove+(white?0:1));
    }
    public String describe(Move m) {
        char p=Character.toLowerCase(board[m.from()]);String name=switch(p){case 'p'->"Пешка";case 'n'->"Конь";case 'b'->"Слон";case 'r'->"Ладья";case 'q'->"Ферзь";default->"Король";};
        if(p=='k'&&Math.abs(m.to()-m.from())==2)return m.to()>m.from()?"Короткая рокировка":"Длинная рокировка";
        return name+" "+square(m.from())+" → "+square(m.to())+(m.promotion()!='\0'?" · превращение в "+m.promotion():"");
    }
    public boolean insufficientMaterial(){
        int minors=0,knights=0,bishopColor=-1;boolean sameBishops=true;
        for(int i=0;i<64;i++)switch(Character.toLowerCase(board[i])){
            case 'p':case 'r':case 'q':return false;
            case 'n':minors++;knights++;break;
            case 'b':minors++;int color=(i/8+i%8)&1;if(bishopColor<0)bishopColor=color;else if(color!=bishopColor)sameBishops=false;break;
        }
        return minors<=1||(knights==0&&sameBishops);
    }
    /** Repetition ignores a nominal EP target when no legal en-passant capture exists. */
    public String repetitionKey(){
        String[] parts=fen().split(" ");boolean usableEp=ep>=0&&legalMoves().stream().anyMatch(m->m.to()==ep&&Character.toLowerCase(board[m.from()])=='p'&&board[m.to()]=='.');
        return parts[0]+" "+parts[1]+" "+parts[2]+" "+(usableEp?parts[3]:"-");
    }
}
