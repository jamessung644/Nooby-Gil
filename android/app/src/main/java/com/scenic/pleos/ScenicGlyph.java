package com.scenic.pleos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small scalable outline glyphs; the adjacent tab label supplies their accessible name. */
final class ScenicGlyph extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind,size;
    ScenicGlyph(Context context,int kind,int color){
        this.kind=kind;size=Math.round(22*context.getResources().getDisplayMetrics().density);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas canvas){
        int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        Path p=new Path();
        if(kind==3){canvas.drawCircle(12,12,9,paint);p.moveTo(15.8f,8.2f);p.lineTo(13.5f,13.5f);p.lineTo(8.2f,15.8f);p.lineTo(10.5f,10.5f);p.close();}
        else if(kind==0){p.moveTo(3,5);p.lineTo(9,3);p.lineTo(15,5);p.lineTo(21,3);p.lineTo(21,19);p.lineTo(15,21);p.lineTo(9,19);p.lineTo(3,21);p.close();p.moveTo(9,3);p.lineTo(9,19);p.moveTo(15,5);p.lineTo(15,21);}
        else if(kind==4){canvas.drawRoundRect(3,4,21,20,3,3,paint);p.moveTo(10,8);p.lineTo(16,12);p.lineTo(10,16);p.close();}
        else if(kind==5){canvas.drawRoundRect(3,3,21,21,4,4,paint);p.moveTo(9,17);p.lineTo(9,7);p.lineTo(13,7);p.cubicTo(17,7,17,13,13,13);p.lineTo(9,13);}
        else{p.moveTo(6,3);p.lineTo(18,3);p.lineTo(18,21);p.lineTo(12,17);p.lineTo(6,21);p.close();}
        canvas.drawPath(p,paint);canvas.restoreToCount(save);
    }
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
