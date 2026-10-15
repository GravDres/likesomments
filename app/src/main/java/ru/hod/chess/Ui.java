package ru.hod.chess;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG=Color.rgb(16,21,18),CARD=Color.rgb(27,36,30),INK=Color.rgb(243,246,239),MUTED=Color.rgb(155,172,157),ACCENT=Color.rgb(194,239,151),LINE=Color.rgb(48,63,52);
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static TextView text(Context c,String s,float sp,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    static TextView bold(Context c,String s,float sp,int color){TextView t=text(c,s,sp,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static LinearLayout col(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(android.view.Gravity.CENTER_VERTICAL);return l;}
    static void pad(View v,int n){int p=dp(v.getContext(),n);v.setPadding(p,p,p,p);}
    static void gap(LinearLayout parent,int n){View v=new View(parent.getContext());parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),n)));}
    static Button button(Context c,String label,boolean primary,Runnable action){Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?BG:INK);b.setBackground(shape(primary?ACCENT:LINE,dp(c,16)));b.setMinHeight(dp(c,52));b.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));b.setOnClickListener(v->action.run());return b;}
    static LinearLayout card(Context c){LinearLayout l=col(c);pad(l,20);l.setBackground(shape(CARD,dp(c,24)));return l;}
}
