package ru.hod.chess;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import java.util.function.Consumer;
import ru.hod.chess.core.*;

/** Explicit recovery when missing history cannot be inferred from the screen. */
final class PositionReview {
    record Choice(Position position,boolean whiteBottom) {}
    private final Context context;private final char[] pieces;private boolean whiteBottom;
    private final AlertDialog dialog;private final BoardView board;
    private final CheckBox[] castling=new CheckBox[4];
    private boolean closed;private AlertDialog piecePicker;
    PositionReview(Context context,char[] observed,Position reference,boolean whiteBottom,Consumer<Choice> done,Runnable cancelled){
        this.context=context;this.pieces=observed.clone();this.whiteBottom=whiteBottom;
        ScrollView scroll=new ScrollView(context);LinearLayout content=Ui.col(context);Ui.pad(content,16);scroll.addView(content);
        content.addView(Ui.text(context,"Проверь фигуры. Нажми клетку, чтобы исправить её. Подтверди, чей сейчас ход и какие рокировки ещё разрешены.",14,Ui.MUTED));Ui.gap(content,8);
        board=new BoardView(context);content.addView(board,new LinearLayout.LayoutParams(-1,-2));refresh();Ui.gap(content,8);
        Switch orientation=new Switch(context);orientation.setText("Белые снизу на экране");orientation.setTextColor(Ui.INK);orientation.setChecked(whiteBottom);orientation.setOnCheckedChangeListener((button,value)->{for(int i=0;i<32;i++){char piece=pieces[i];pieces[i]=pieces[63-i];pieces[63-i]=piece;}this.whiteBottom=value;refresh();updateRights();});content.addView(orientation);
        content.addView(Ui.bold(context,"Сейчас ход",15,Ui.INK));RadioGroup turn=new RadioGroup(context);turn.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton white=new RadioButton(context);white.setId(301);white.setText("Белых");white.setTextColor(Ui.INK);turn.addView(white);
        RadioButton black=new RadioButton(context);black.setId(302);black.setText("Чёрных");black.setTextColor(Ui.INK);turn.addView(black);turn.check(reference.white?301:302);content.addView(turn);
        String[] labels={"Белым: короткая рокировка","Белым: длинная рокировка","Чёрным: короткая рокировка","Чёрным: длинная рокировка"};
        for(int i=0;i<4;i++){castling[i]=new CheckBox(context);castling[i].setText(labels[i]);castling[i].setTextColor(Ui.INK);castling[i].setChecked((reference.rights&(1<<i))!=0);content.addView(castling[i]);}updateRights();
        EditText ep=field(content,"Взятие на проходе: поле или -",reference.matches(observed)&&reference.ep>=0?Position.square(reference.ep):"-",false);
        EditText half=field(content,"Полуходов без взятия и хода пешки",reference.matches(observed)?Integer.toString(reference.halfmove):"0",true);
        EditText full=field(content,"Номер хода",Integer.toString(reference.fullmove),true);
        content.addView(Ui.text(context,"Если прошлые ходы потеряны, повторения до этой позиции неизвестны. Подтверждай только доску, которую видишь сейчас.",12,Ui.MUTED));
        dialog=new AlertDialog.Builder(context).setTitle("Продолжить текущую партию").setView(scroll).setPositiveButton("Продолжить",null).setNegativeButton("Отмена",(d,w)->{close();cancelled.run();}).create();
        dialog.setCanceledOnTouchOutside(false);dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnCancelListener(d->{close();cancelled.run();});
        dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->{try{
            int rights=0;for(int i=0;i<4;i++)if(castling[i].isEnabled()&&castling[i].isChecked())rights|=1<<i;
            String square=ep.getText().toString().trim();int target=square.equals("-")||square.isEmpty()?-1:Position.index(square);
            Position position=Position.fromDiagram(pieces,turn.getCheckedRadioButtonId()==301,rights,target,Integer.parseInt(half.getText().toString()),Integer.parseInt(full.getText().toString()));
            close();done.accept(new Choice(position,this.whiteBottom));
        }catch(IllegalArgumentException e){Toast.makeText(context,"Проверь позицию: "+e.getMessage(),Toast.LENGTH_LONG).show();}});});
        dialog.show();android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();dialog.getWindow().setLayout(metrics.widthPixels-Ui.dp(context,24),Math.min(metrics.heightPixels-Ui.dp(context,80),Ui.dp(context,680)));
    }
    private EditText field(LinearLayout parent,String label,String value,boolean number){parent.addView(Ui.text(context,label,12,Ui.MUTED));EditText input=new EditText(context);input.setText(value);input.setTextColor(Ui.INK);input.setTextSize(14);input.setSingleLine(true);if(number)input.setInputType(InputType.TYPE_CLASS_NUMBER);parent.addView(input);return input;}
    private void refresh(){board.showPieces(pieces,whiteBottom,this::choosePiece);}
    private void updateRights(){
        for(int i=0;i<4;i++){if(castling[i]==null)continue;boolean white=i<2;int king=white?60:4,rook=switch(i){case 0->63;case 1->56;case 2->7;default->0;};boolean enabled=pieces[king]==(white?'K':'k')&&pieces[rook]==(white?'R':'r');castling[i].setEnabled(enabled);if(!enabled)castling[i].setChecked(false);}
    }
    private void choosePiece(int square){
        String[] names={"Пустая клетка","Белая пешка","Белый конь","Белый слон","Белая ладья","Белый ферзь","Белый король","Чёрная пешка","Чёрный конь","Чёрный слон","Чёрная ладья","Чёрный ферзь","Чёрный король"};String codes=".PNBRQKpnbrqk";
        piecePicker=new AlertDialog.Builder(context).setTitle("Клетка "+Position.square(square)).setItems(names,(d,index)->{pieces[square]=codes.charAt(index);refresh();updateRights();}).create();piecePicker.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);piecePicker.show();
    }
    void close(){if(closed)return;closed=true;if(piecePicker!=null)piecePicker.dismiss();dialog.dismiss();}
}
