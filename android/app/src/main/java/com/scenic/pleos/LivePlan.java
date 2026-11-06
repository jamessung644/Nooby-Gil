package com.scenic.pleos;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict boundary for a dynamically composed route verified by the road provider. */
public final class LivePlan {
    public static final String SOURCE="TourAPI + OpenAI + Kakao Mobility";
    public final String status,message,generatedAt;
    public final int radiusMeters,candidateCount;
    public final boolean returnToOrigin;
    public final int stayMinutes;
    public final CourseEngine.Course course;
    public final double[][] roadCoordinates;
    public final double roadDistanceKm,roadDurationMin,visitDurationMin,totalDurationMin;
    private LivePlan(String status,String message,String generatedAt,int radiusMeters,int candidateCount,CourseEngine.Course course,double[][] geometry,double distance,double duration,double visit,double total,boolean returnToOrigin,int stayMinutes){
        this.status=status;this.message=message;this.generatedAt=generatedAt;this.radiusMeters=radiusMeters;this.candidateCount=candidateCount;
        this.course=course;roadCoordinates=geometry;roadDistanceKm=distance;roadDurationMin=duration;visitDurationMin=visit;totalDurationMin=total;
        this.returnToOrigin=returnToOrigin;this.stayMinutes=stayMinutes;
    }
    public static LivePlan parse(JSONObject json,int budget,double originLat,double originLon,int requestedRadius)throws JSONException{
        return parse(json,budget,originLat,originLon,requestedRadius,false,15);
    }
    public static LivePlan parse(JSONObject json,int budget,double originLat,double originLon,int requestedRadius,boolean requestedReturnToOrigin,int requestedStayMinutes)throws JSONException{
        if(json==null||budget<1||budget>720||!CourseEngine.inKorea(originLat,originLon))throw invalid();
        if(requestedStayMinutes!=0&&requestedStayMinutes!=15&&requestedStayMinutes!=30)throw invalid();
        if(!SOURCE.equals(text(json,"source",80)))throw invalid();
        String created=timestamp(json,"generatedAt"),status=text(json,"status",32),message=text(json,"message",400);
        int radius=integer(json,"radiusMeters",1000,50000),count=integer(json,"candidateCount",0,30);
        if(radius!=requestedRadius)throw invalid();
        JSONArray courses=json.getJSONArray("courses");
        if("no_candidates".equals(status)||"no_feasible_route".equals(status)){
            if(courses.length()!=0)throw invalid();
            return new LivePlan(status,message,created,radius,count,null,null,0,0,0,0,requestedReturnToOrigin,requestedStayMinutes);
        }
        if(!"ok".equals(status)||courses.length()!=1||count<2)throw invalid();
        JSONObject item=courses.getJSONObject(0);String id=text(item,"id",100),title=text(item,"title",120),reason=text(item,"reason",400),theme=text(item,"theme",16);
        boolean returns=false;if(item.has("returnToOrigin")){Object flag=item.get("returnToOrigin");if(!(flag instanceof Boolean))throw invalid();returns=(Boolean)flag;}
        int stay=item.has("stayMinutes")?integer(item,"stayMinutes",0,30):15;
        if((stay!=0&&stay!=15&&stay!=30)||returns!=requestedReturnToOrigin||stay!=requestedStayMinutes)throw invalid();
        if(!id.matches("live-[A-Za-z0-9_-]{1,90}"))throw invalid();
        String scene,tag;
        if("coast".equals(theme)){scene="sea";tag="바다 · 해안";}
        else if("city".equals(theme)){scene="night";tag="도시 · 문화";}
        else if("lake".equals(theme)){scene="lake";tag="호수 · 물길";}
        else if("forest".equals(theme)){scene="forest";tag="숲 · 자연";}
        else throw invalid();
        JSONArray rawSpots=item.getJSONArray("spots");
        if(rawSpots.length()<2||rawSpots.length()>4||rawSpots.length()>count)throw invalid();
        CourseEngine.Spot[] spots=new CourseEngine.Spot[rawSpots.length()];Set<String> ids=new HashSet<>();
        for(int i=0;i<spots.length;i++){
            JSONObject spot=rawSpots.getJSONObject(i);String spotId=text(spot,"id",32);
            if(!spotId.matches("[0-9]{1,32}")||!ids.add(spotId))throw invalid();
            String spotTitle=text(spot,"title",200),address=text(spot,"address",300);
            double lat=number(spot,"lat",33,38.65),lon=number(spot,"lon",124.5,131.95);
            if(CourseEngine.distanceMeters(originLat,originLon,lat,lon)>radius+1000)throw invalid();
            spots[i]=new CourseEngine.Spot(spotTitle,address+" · 한국관광공사 TourAPI",lat,lon);
        }
        JSONObject road=item.getJSONObject("routing");if(!"Kakao Mobility".equals(text(road,"source",40)))throw invalid();timestamp(road,"fetchedAt");
        double distance=number(road,"distanceKm",0,2000),duration=number(road,"durationMin",0,720),visit=number(item,"visitDurationMin",0,720),total=number(item,"totalDurationMin",0,budget);
        if(distance<=0||duration<=0||Math.abs(visit-spots.length*stay)>0.001||Math.abs(total-duration-visit)>0.02)throw invalid();
        JSONArray coordinates=road.getJSONArray("coordinates");if(coordinates.length()<2||coordinates.length()>50000)throw invalid();
        double[][] geometry=new double[coordinates.length()][2];
        for(int i=0;i<geometry.length;i++){
            JSONArray point=coordinates.getJSONArray(i);if(point.length()!=2)throw invalid();
            geometry[i][0]=number(point.get(0),33,38.65);geometry[i][1]=number(point.get(1),124.5,131.95);
        }
        CourseEngine.Spot last=spots[spots.length-1];double[] firstPoint=geometry[0],lastPoint=geometry[geometry.length-1];
        // Kakao may snap the input to an adjacent road, but a spots-only route omits the approach.
        double finishLat=returns?originLat:last.lat,finishLon=returns?originLon:last.lon;
        if(CourseEngine.distanceMeters(originLat,originLon,firstPoint[0],firstPoint[1])>2000||CourseEngine.distanceMeters(finishLat,finishLon,lastPoint[0],lastPoint[1])>(returns?250:2000))throw invalid();
        CourseEngine.Course course=new CourseEngine.Course(id,title,reason,scene,tag,String.format(Locale.KOREA,"도로 %.1f km",distance),(int)Math.ceil(total),"live","주변 실제 명소",spots[0].lat,spots[0].lon,spots);
        return new LivePlan(status,message,created,radius,count,course,geometry,distance,duration,visit,total,returns,stay);
    }
    private static String text(JSONObject object,String key,int max)throws JSONException{
        Object value=object.get(key);if(!(value instanceof String))throw invalid();
        String text=((String)value).trim();if(text.isEmpty()||text.length()>max)throw invalid();
        for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i))&&!Character.isWhitespace(text.charAt(i)))throw invalid();
        return text;
    }
    private static String timestamp(JSONObject object,String key)throws JSONException{String value=text(object,key,40);try{Instant.parse(value);}catch(RuntimeException e){throw invalid();}return value;}
    private static double number(JSONObject object,String key,double min,double max)throws JSONException{return number(object.get(key),min,max);}
    private static double number(Object value,double min,double max)throws JSONException{if(!(value instanceof Number))throw invalid();double number=((Number)value).doubleValue();if(!Double.isFinite(number)||number<min||number>max)throw invalid();return number;}
    private static int integer(JSONObject object,String key,int min,int max)throws JSONException{double value=number(object,key,min,max);if(value!=Math.rint(value))throw invalid();return (int)value;}
    private static JSONException invalid(){return new JSONException("Invalid live itinerary response");}
}
