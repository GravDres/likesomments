package ru.hod.chess.core;

/** Verifiable move/board facts; does not invent a calculated strategic explanation. */
public final class PositionCoach {
    private PositionCoach(){}
    public static String hint(Position p,Move move){
        if(p.legalMoves().isEmpty())return p.inCheck(p.white)?"Мат: легальных ответов нет":"Пат: легальных ходов нет";
        if(p.inCheck(p.white))return "Королю шах: ищи взятие, перекрытие или отход короля";
        for(int i=0;i<64;i++)if(p.at(i)==(p.white?'Q':'q')&&p.attacked(i,!p.white))return "Проверь защиту ферзя: он находится под атакой";
        if(move==null)return "Перед ходом проверь шахи, взятия и угрозы соперника";
        if(!p.legalMoves().contains(move))throw new IllegalArgumentException("Намёк требует легального хода");
        char piece=Character.toLowerCase(p.at(move.from()));Position after=p.applyUnchecked(move);
        if(after.inCheck(after.white)&&after.legalMoves().isEmpty())return "В позиции есть мат в один: найди завершающий шах";
        if(move.promotion()!='\0')return "Проверь превращение пешки и выбор фигуры";
        if(piece=='k'&&Math.abs(move.to()-move.from())==2)return "Рассмотри рокировку: безопасность короля и подключение ладьи";
        if(p.at(move.to())!='.'||piece=='p'&&move.from()%8!=move.to()%8)return "Рассмотри взятие; проверь ответное взятие соперника";
        if(after.inCheck(after.white))return "Рассмотри шах и все доступные ответы соперника";
        return switch(piece){case 'n'->"Рассмотри манёвр конём и контроль центральных полей";case 'b'->"Рассмотри диагонали слона и защиту своих фигур";case 'r'->"Рассмотри активность ладьи на линиях";case 'q'->"Рассмотри активность ферзя, сохраняя его безопасность";case 'k'->"Рассмотри безопасное поле для короля";default->"Рассмотри пешечный ход и поля, которые он ослабляет";};
    }
}
