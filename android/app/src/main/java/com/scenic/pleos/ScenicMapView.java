package com.scenic.pleos;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.Locale;

/** Illustrative course diagram or provider road geometry, without map tiles or navigation. */
public final class ScenicMapView extends View {
    private static final int BACKGROUND=0xfff5f5f5,CARD=0xffffffff,INK=0xff17191c,MUTED=0xff656970;
    private static final int BORDER=0xffdfe1e4,PRIMARY=0xff17191c,PALE=0xffecedef,ROUTE=0xff17191c;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density,scaledDensity;
    private CourseEngine.Course course;
    private int active;
    private boolean driving;
    private double[][] roadCoordinates;
    private double roadDistanceKm,roadDurationMin;
    public ScenicMapView(Context context) {
        super(context);
        density=getResources().getDisplayMetrics().density;
        scaledDensity=getResources().getDisplayMetrics().scaledDensity;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    public void setRoute(CourseEngine.Course c,int index,boolean moving) {
        if(course!=c||moving){roadCoordinates=null;roadDistanceKm=0;roadDurationMin=0;}
        course=c; active=c==null?0:Math.max(0,Math.min(index,c.spots.length-1));driving=moving;
        updateDescription();invalidate();
    }
    /** Coordinates are [latitude, longitude]; invalid results clear any previous provider geometry. */
    public void setRoadRoute(double[][] coordinates,double distanceKm,double durationMin){
        if(course==null||driving||!Double.isFinite(distanceKm)||!Double.isFinite(durationMin)||distanceKm<0||durationMin<0){clearRoadRoute();return;}
        try{roadCoordinates=RouteProjection.copyValidatedRoute(coordinates);}
        catch(IllegalArgumentException e){clearRoadRoute();return;}
        roadDistanceKm=distanceKm;roadDurationMin=durationMin;updateDescription();invalidate();
    }
    public void clearRoadRoute(){
        roadCoordinates=null;roadDistanceKm=0;roadDurationMin=0;updateDescription();invalidate();
    }
    private String roadSummary(){return String.format(Locale.KOREA,"%.1f km · 예상 %.0f분",roadDistanceKm,roadDurationMin);}
    private void updateDescription(){
        setContentDescription(course==null?"여정을 선택하세요":course.name+(roadCoordinates==null?" 경로 도식. 실제 도로 지도가 아닙니다.":". Kakao 도로 경로. "+roadSummary()+". 배경 지도 없음. 길 안내 아님."));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(course==null||getWidth()<=0||getHeight()<=0)return;
        final float width=getWidth(),height=getHeight(),inset=dp(16);
        final boolean road=roadCoordinates!=null&&!driving;
        RectF frame=new RectF(0,0,width,height);
        fill(canvas,frame,dp(8),BACKGROUND);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(BORDER);
        canvas.drawRoundRect(new RectF(dp(.5f),dp(.5f),width-dp(.5f),height-dp(.5f)),dp(8),dp(8),paint);
        paint.setStyle(Paint.Style.FILL);
        if(width<=inset*2||height<=inset*2)return;

        // Draw in physical pixels. Only lengths use dp and text uses sp; never scale the canvas.
        float textWidth=width-inset*2;
        StaticLayout title=layout(road?"Kakao 도로 경로":driving?"여정 진행 중":"선택한 여정",16,PRIMARY,true,textWidth-(road?dp(36):0),2);
        drawLayout(canvas,title,inset,inset);
        StaticLayout subtitle=layout(road?roadSummary():course.spots.length+"개 방문 장소 · 경로 도식",14,MUTED,false,textWidth,2);
        float subtitleTop=inset+title.getHeight()+dp(4);
        drawLayout(canvas,subtitle,inset,subtitleTop);
        if(road)drawNorth(canvas,width-inset-dp(10),inset+dp(9));

        String provenance=road?"Kakao 도로 경로 · 배경 지도 없음 · 길 안내 아님":"경로 도식 · 실제 도로 / 길 안내 아님";
        StaticLayout footer=layout(provenance,14,MUTED,false,textWidth,4);
        float footerTop=height-inset-footer.getHeight();
        paint.setColor(BORDER);paint.setStrokeWidth(dp(1));
        canvas.drawLine(inset,footerTop-dp(9),width-inset,footerTop-dp(9),paint);
        drawLayout(canvas,footer,inset,footerTop);

        RectF plot=new RectF(inset,subtitleTop+subtitle.getHeight()+dp(12),width-inset,footerTop-dp(20));
        float minimumPlot=2*(markerRadius()+dp(9));
        if(plot.width()<minimumPlot||plot.height()<minimumPlot)return;
        float[][] points=pointsFor(plot,road);
        LabelPlacement[] labels=placeLabels(points,plot);
        boolean legend=labels==null;
        if(legend){
            labels=legendLabels(plot);
            float legendTop=labels[0].bounds.top;
            RectF smaller=new RectF(plot.left,plot.top,plot.right,legendTop-dp(10));
            if(smaller.height()>=minimumPlot){
                plot=smaller;points=pointsFor(plot,road);
            }else{
                // Very large accessibility fonts retain the selected name without crowding the plot.
                labels=selectedLegend(plot);
                RectF selectedPlot=new RectF(plot.left,plot.top,plot.right,labels[0].bounds.top-dp(8));
                if(selectedPlot.height()>=minimumPlot){plot=selectedPlot;points=pointsFor(plot,road);}
                else labels=new LabelPlacement[0];
            }
        }
        canvas.save();canvas.clipRect(plot);
        Path route=road?roadPath(plot):samplePath(plot);
        line(canvas,route,CARD,dp(8));line(canvas,route,ROUTE,dp(3.5f));
        canvas.restore();
        if(!legend)drawLabelLeaders(canvas,labels,points);
        drawLabels(canvas,labels,legend);
        for(int i=0;i<points.length;i++)drawMarker(canvas,points[i][0],points[i][1],i+1,i==active);
    }

    private float dp(float value){return value*density;}
    private boolean wide(){return getWidth()/density>=700;}
    private float sp(float value){return (value+(wide()?4:0))*scaledDensity;}
    private StaticLayout layout(String value,float size,int color,boolean bold,float width,int lines){
        textPaint.setColor(color);textPaint.setTextSize(sp(size));
        textPaint.setTypeface(ScenicTypography.font(getContext(),bold?600:400));
        // Each layout retains its paint; later labels must not change earlier layouts' text size.
        TextPaint ownPaint=new TextPaint(textPaint);
        return StaticLayout.Builder.obtain(value,0,value.length(),ownPaint,Math.max(1,(int)Math.floor(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(dp(2),1).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
    }
    private void drawLayout(Canvas canvas,StaticLayout layout,float left,float top){
        canvas.save();canvas.translate(left,top);layout.draw(canvas);canvas.restore();
    }
    private void fill(Canvas canvas,RectF bounds,float radius,int color){
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawRoundRect(bounds,radius,radius,paint);
    }
    private void line(Canvas canvas,Path path,int color,float width){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
    }
    private RectF routeBounds(RectF plot){
        float margin=markerRadius()+dp(8);
        float marginX=Math.min(margin,plot.width()/2-dp(1)),marginY=Math.min(margin,plot.height()/2-dp(1));
        return new RectF(plot.left+marginX,plot.top+marginY,plot.right-marginX,plot.bottom-marginY);
    }
    private double[][] stops(){
        double[][] stops=new double[course.spots.length][2];
        for(int i=0;i<stops.length;i++){stops[i][0]=course.spots[i].lat;stops[i][1]=course.spots[i].lon;}
        return stops;
    }
    private RouteProjection roadProjection(RectF plot){
        RectF bounds=routeBounds(plot);
        return RouteProjection.fit(roadCoordinates,stops(),bounds.left,bounds.top,bounds.right,bounds.bottom);
    }
    private float[][] pointsFor(RectF plot,boolean road){
        float[][] points=new float[course.spots.length][2];
        if(road){
            RouteProjection projection=roadProjection(plot);
            for(int i=0;i<points.length;i++){
                double[] p=projection.project(course.spots[i].lat,course.spots[i].lon);
                points[i][0]=(float)p[0];points[i][1]=(float)p[1];
            }
        }else{
            float[][] base={{0,140},{330,50},{670,150},{1000,20}};
            for(int i=0;i<points.length;i++){
                float[] point=i<base.length?base[i]:new float[]{1000f*i/Math.max(1,points.length-1),100};
                points[i]=samplePoint(plot,point[0],point[1]);
            }
        }
        return points;
    }
    private float[] samplePoint(RectF plot,float x,float y){
        RectF bounds=routeBounds(plot);
        float scale=Math.min(bounds.width()/1000f,bounds.height()/200f);
        return new float[]{bounds.centerX()+(x-500)*scale,bounds.centerY()+(y-100)*scale};
    }
    private Path samplePath(RectF plot){
        Path path=new Path();float[] start=samplePoint(plot,0,140);path.moveTo(start[0],start[1]);
        sampleCurve(path,plot,130,140,190,30,330,50);
        sampleCurve(path,plot,470,70,530,180,670,150);
        sampleCurve(path,plot,800,125,840,20,1000,20);
        return path;
    }
    private void sampleCurve(Path path,RectF plot,float ax,float ay,float bx,float by,float x,float y){
        float[] a=samplePoint(plot,ax,ay),b=samplePoint(plot,bx,by),end=samplePoint(plot,x,y);
        path.cubicTo(a[0],a[1],b[0],b[1],end[0],end[1]);
    }
    private Path roadPath(RectF plot){
        RouteProjection projection=roadProjection(plot);Path path=new Path();
        for(int i=0;i<roadCoordinates.length;i++){
            double[] point=projection.project(roadCoordinates[i][0],roadCoordinates[i][1]);
            if(i==0)path.moveTo((float)point[0],(float)point[1]);else path.lineTo((float)point[0],(float)point[1]);
        }
        return path;
    }
    private void drawMarker(Canvas canvas,float x,float y,int number,boolean selected){
        float radius=markerRadius();
        if(selected){paint.setColor(PALE);canvas.drawCircle(x,y,radius+dp(7),paint);}
        paint.setColor(selected?PRIMARY:CARD);canvas.drawCircle(x,y,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(PRIMARY);canvas.drawCircle(x,y,radius,paint);paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(selected?CARD:PRIMARY);textPaint.setTextSize(sp(14));textPaint.setTypeface(ScenicTypography.font(getContext(),600));
        String label=String.valueOf(number);Paint.FontMetrics metrics=textPaint.getFontMetrics();
        canvas.drawText(label,x-textPaint.measureText(label)/2,y-(metrics.ascent+metrics.descent)/2,textPaint);
    }
    private float markerRadius(){
        textPaint.setTextSize(sp(14));textPaint.setTypeface(ScenicTypography.font(getContext(),600));
        Paint.FontMetrics metrics=textPaint.getFontMetrics();
        return Math.max(dp(wide()?14:11),(metrics.descent-metrics.ascent)/2+dp(2));
    }
    private void drawNorth(Canvas canvas,float x,float y){
        textPaint.setColor(MUTED);textPaint.setTextSize(sp(14));textPaint.setTypeface(ScenicTypography.font(getContext(),600));
        canvas.drawText("N",x-textPaint.measureText("N")/2,y+dp(4),textPaint);
        Path arrow=new Path();arrow.moveTo(x,y+dp(10));arrow.lineTo(x-dp(4),y+dp(19));arrow.lineTo(x+dp(4),y+dp(19));arrow.close();
        paint.setColor(PRIMARY);canvas.drawPath(arrow,paint);
    }
    private static final class LabelPlacement {
        final int index;final StaticLayout text;final RectF bounds;
        LabelPlacement(int index,StaticLayout text,RectF bounds){this.index=index;this.text=text;this.bounds=bounds;}
    }
    private StaticLayout spotLabel(int index,float width){
        return layout(course.spots[index].title.replace(" 출발",""),14,INK,false,width,2);
    }
    private LabelPlacement[] placeLabels(float[][] points,RectF plot){
        LabelPlacement[] labels=new LabelPlacement[points.length];
        for(int i=0;i<points.length;i++){
            float maximum=Math.min(dp(172),plot.width()/2-dp(8));
            StaticLayout text=spotLabel(i,Math.max(dp(30),maximum-dp(16)));
            float measured=0;for(int row=0;row<text.getLineCount();row++)measured=Math.max(measured,text.getLineWidth(row));
            float w=Math.min(maximum,measured+dp(16)),h=text.getHeight()+dp(10),x=points[i][0],y=points[i][1];
            if(h>plot.height()||w>plot.width())return null;
            float[][] candidates={{x-w/2,y+dp(20)},{x-w/2,y-dp(20)-h},{x+dp(20),y-h/2},{x-dp(20)-w,y-h/2}};
            RectF chosen=null;
            for(float[] candidate:candidates){
                float left=clamp(candidate[0],plot.left,plot.right-w),top=clamp(candidate[1],plot.top,plot.bottom-h);
                RectF option=new RectF(left,top,left+w,top+h);boolean collision=false;
                RectF padded=new RectF(option);padded.inset(-dp(4),-dp(4));
                for(int j=0;j<i;j++)if(RectF.intersects(padded,labels[j].bounds)){collision=true;break;}
                if(!collision)for(float[] marker:points){
                    float clearance=dp(wide()?18:14);
                    RectF markerBox=new RectF(marker[0]-clearance,marker[1]-clearance,marker[0]+clearance,marker[1]+clearance);
                    if(RectF.intersects(padded,markerBox)){collision=true;break;}
                }
                if(!collision){chosen=option;break;}
            }
            if(chosen==null)return null;
            labels[i]=new LabelPlacement(i,text,chosen);
        }
        return labels;
    }
    private LabelPlacement[] legendLabels(RectF plot){
        int count=course.spots.length,rows=(count+1)/2;float column=(plot.width()-dp(16))/2;
        StaticLayout[] texts=new StaticLayout[count];float[] heights=new float[rows];
        for(int i=0;i<count;i++){texts[i]=spotLabel(i,Math.max(dp(20),column-dp(28)));heights[i/2]=Math.max(heights[i/2],texts[i].getHeight()+dp(8));}
        float total=0;for(float h:heights)total+=h;total+=dp(4)*Math.max(0,rows-1);
        float top=plot.bottom-total;LabelPlacement[] labels=new LabelPlacement[count];
        for(int i=0;i<count;i++){
            float left=plot.left+(i%2)*(column+dp(16));
            labels[i]=new LabelPlacement(i,texts[i],new RectF(left,top,left+column,top+heights[i/2]));
            if(i%2==1)top+=heights[i/2]+dp(4);
        }
        return labels;
    }
    private LabelPlacement[] selectedLegend(RectF plot){
        StaticLayout text=spotLabel(active,Math.max(dp(20),plot.width()-dp(28)));
        return new LabelPlacement[]{new LabelPlacement(active,text,new RectF(plot.left,plot.bottom-text.getHeight()-dp(8),plot.right,plot.bottom))};
    }
    private void drawLabelLeaders(Canvas canvas,LabelPlacement[] labels,float[][] points){
        paint.setColor(BORDER);paint.setStrokeWidth(dp(1.25f));
        for(LabelPlacement label:labels){float[] p=points[label.index];canvas.drawLine(p[0],p[1],clamp(p[0],label.bounds.left,label.bounds.right),clamp(p[1],label.bounds.top,label.bounds.bottom),paint);}
    }
    private void drawLabels(Canvas canvas,LabelPlacement[] labels,boolean legend){
        for(LabelPlacement label:labels){
            if(legend){
                float cx=label.bounds.left+dp(8),cy=label.bounds.top+dp(4)+label.text.getHeight()/2f;
                textPaint.setColor(PRIMARY);textPaint.setTextSize(sp(14));textPaint.setTypeface(ScenicTypography.font(getContext(),600));
                Paint.FontMetrics fm=textPaint.getFontMetrics();String number=String.valueOf(label.index+1);
                canvas.drawText(number,cx-textPaint.measureText(number)/2,cy-(fm.ascent+fm.descent)/2,textPaint);
                drawLayout(canvas,label.text,label.bounds.left+dp(28),label.bounds.top+dp(4));
            }else{
                fill(canvas,label.bounds,dp(7),CARD);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(BORDER);canvas.drawRoundRect(label.bounds,dp(7),dp(7),paint);paint.setStyle(Paint.Style.FILL);
                drawLayout(canvas,label.text,label.bounds.left+dp(8),label.bounds.top+dp(5));
            }
        }
    }
    private static float clamp(float value,float low,float high){return Math.max(low,Math.min(high,value));}
}
