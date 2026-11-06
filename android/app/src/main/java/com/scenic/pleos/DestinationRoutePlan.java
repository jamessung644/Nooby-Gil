package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validates two road alternatives with exactly the same requested destination. */
public final class DestinationRoutePlan {
    public static final String ROAD_SOURCE="Kakao Mobility";
    public final DestinationPlace destination;
    public final double originLat,originLon;
    public final int extraMinutes;
    public final String theme,scenicStatus,message;
    public final List<Route> routes;
    public final Route fast,scenic;

    private DestinationRoutePlan(DestinationPlace destination,double lat,double lon,int extraMinutes,String theme,
                                 String status,String message,List<Route> routes) {
        this.destination=destination;originLat=lat;originLon=lon;this.extraMinutes=extraMinutes;
        this.theme=theme;scenicStatus=status;this.message=message;
        this.routes=Collections.unmodifiableList(routes);fast=routes.get(0);scenic=routes.size()==2?routes.get(1):null;
    }

    public static final class Route {
        public final String id,title,reason,source;
        public final double distanceKm,durationMin;
        public final double[][] coordinates;
        public final List<String> scenicLabels;
        public final List<CourseEngine.Spot> waypoints;
        private final DestinationPlace destination;
        private final double originLat,originLon;
        private final String theme;

        private Route(String id,String title,String reason,double distance,double duration,double[][] coordinates,
                      List<String> labels,List<CourseEngine.Spot> waypoints,DestinationPlace destination,
                      double originLat,double originLon,String theme) {
            this.id=id;this.title=title;this.reason=reason;source=ROAD_SOURCE;
            distanceKm=distance;durationMin=duration;this.coordinates=coordinates;
            scenicLabels=Collections.unmodifiableList(labels);this.waypoints=Collections.unmodifiableList(waypoints);
            this.destination=destination;this.originLat=originLat;this.originLon=originLon;this.theme=theme;
        }

        /** Adapter for the existing map, saved moments and music, with no added visit time. */
        public CourseEngine.Course toCourse(){return toCourse("요청한 출발지");}
        public CourseEngine.Course toCourse(String originLabel){
            if(originLabel==null||originLabel.trim().isEmpty()||originLabel.length()>120||MusicContext.hasControl(originLabel))
                throw new IllegalArgumentException("Invalid origin label");
            CourseEngine.Spot[] spots=new CourseEngine.Spot[waypoints.size()+2];
            spots[0]=new CourseEngine.Spot(originLabel.trim(),"경로 조회 출발 좌표",originLat,originLon);
            for(int i=0;i<waypoints.size();i++)spots[i+1]=waypoints.get(i);
            spots[spots.length-1]=new CourseEngine.Spot(destination.name,destination.address.isEmpty()?"선택한 목적지":destination.address,destination.lat,destination.lon);
            String tag="fast".equals(id)?"빠른 길":"풍경 길";
            // A fast route has no verified scenery match; the requested preference is not route evidence.
            String scene="scenic".equals(id)?theme:"all";
            String identity=destination.id+"|"+destination.lat+"|"+destination.lon+"|"+originLat+"|"+originLon+"|"+theme;
            for(CourseEngine.Spot waypoint:waypoints)identity+="|"+waypoint.title.length()+":"+waypoint.title+"|"+waypoint.lat+"|"+waypoint.lon;
            String courseId="destination-"+UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))+"-"+id;
            return new CourseEngine.Course(courseId,title,reason,scene,tag,
                    String.format(Locale.KOREA,"도로 %.1f km",distanceKm),(int)Math.ceil(durationMin),
                    "destination","선택한 목적지",destination.lat,destination.lon,spots);
        }
    }

    public static DestinationRoutePlan parse(JSONObject response,DestinationPlace expected,double originLat,
                                            double originLon,int extraMinutes,String theme)throws JSONException {
        return parse(response,expected,originLat,originLon,extraMinutes,theme,null);
    }
    /** A selected photograph must route through that exact POI, without substitution. */
    public static DestinationRoutePlan parse(JSONObject response,DestinationPlace expected,double originLat,
                                            double originLon,int extraMinutes,String theme,DestinationPlace expectedScenicPlace)throws JSONException {
        if(response==null||expected==null||!CourseEngine.inKorea(originLat,originLon)
                ||extraMinutes<0||extraMinutes>120||!validTheme(theme))throw invalid();
        JSONObject origin=response.getJSONObject("origin");
        if(!same(number(origin,"lat",33,38.65),originLat)||!same(number(origin,"lon",124.5,131.95),originLon))throw invalid();
        DestinationPlace actual=DestinationPlace.read(response.getJSONObject("destination"),expected.source);
        if(!actual.id.equals(expected.id)||!actual.name.equals(expected.name)||!same(actual.lat,expected.lat)||!same(actual.lon,expected.lon))throw invalid();
        String status=text(response,"scenicStatus",32),message=text(response,"message",500);
        if(!"ok".equals(status)&&!"no_candidates".equals(status)&&!"no_feasible_route".equals(status)
                &&!"provider_unavailable".equals(status)&&!"disabled".equals(status))throw invalid();
        JSONArray raw=response.getJSONArray("routes");
        if(raw.length()<1||raw.length()>2||("ok".equals(status)!=(raw.length()==2))
                ||(extraMinutes==0&&!"disabled".equals(status))||("disabled".equals(status)&&extraMinutes!=0))throw invalid();
        List<Route> routes=new ArrayList<>();
        for(int i=0;i<raw.length();i++)routes.add(parseRoute(raw.getJSONObject(i),i==0?"fast":"scenic",expected,originLat,originLon,theme,expectedScenicPlace));
        // Provider durations originate as whole seconds. Permit only floating-point roundoff.
        if(routes.size()==2&&routes.get(1).durationMin*60>routes.get(0).durationMin*60+extraMinutes*60+0.000001)throw invalid();
        return new DestinationRoutePlan(expected,originLat,originLon,extraMinutes,theme,status,message,routes);
    }

    private static Route parseRoute(JSONObject object,String expectedId,DestinationPlace destination,
                                    double originLat,double originLon,String theme,DestinationPlace expectedScenicPlace)throws JSONException {
        String id=text(object,"id",20),title=text(object,"title",120),reason=text(object,"reason",500);
        if(!expectedId.equals(id)||!ROAD_SOURCE.equals(text(object,"source",40)))throw invalid();
        double distance=number(object,"distanceKm",0,1500),duration=number(object,"durationMin",0,720);
        if(distance<=0||duration<=0)throw invalid();
        JSONArray geometry=object.getJSONArray("coordinates");
        if(geometry.length()<2||geometry.length()>50000)throw invalid();
        double[][] coordinates=new double[geometry.length()][2];
        for(int i=0;i<coordinates.length;i++){
            JSONArray point=geometry.getJSONArray(i);if(point.length()!=2)throw invalid();
            coordinates[i][0]=DestinationPlace.number(point.get(0),33,38.65);
            coordinates[i][1]=DestinationPlace.number(point.get(1),124.5,131.95);
        }
        double[] start=coordinates[0],end=coordinates[coordinates.length-1];
        if(CourseEngine.distanceMeters(originLat,originLon,start[0],start[1])>1000
                ||CourseEngine.distanceMeters(destination.lat,destination.lon,end[0],end[1])>1000)throw invalid();
        JSONArray rawLabels=object.getJSONArray("scenicLabels");if(rawLabels.length()>8)throw invalid();
        List<String> labels=new ArrayList<>();Set<String> uniqueLabels=new HashSet<>();
        for(int i=0;i<rawLabels.length();i++){
            JSONObject label=new JSONObject().put("value",rawLabels.get(i));String value=text(label,"value",200);
            if(!uniqueLabels.add(value))throw invalid();labels.add(value);
        }
        JSONArray rawWaypoints=object.getJSONArray("waypoints");
        if(("fast".equals(id)&&rawWaypoints.length()!=0)||("scenic".equals(id)&&rawWaypoints.length()!=1))throw invalid();
        List<CourseEngine.Spot> waypoints=new ArrayList<>();
        for(int i=0;i<rawWaypoints.length();i++){
            JSONObject point=rawWaypoints.getJSONObject(i);String name=text(point,"name",200);
            String waypointId=text(point,"id",100),waypointSource=text(point,"source",80);
            if(!waypointId.matches("[A-Za-z0-9_-]{1,100}")||!"한국관광공사 TourAPI".equals(waypointSource))throw invalid();
            double lat=number(point,"lat",33,38.65),lon=number(point,"lon",124.5,131.95);
            if(expectedScenicPlace!=null&&(!waypointId.equals(expectedScenicPlace.id)||!name.equals(expectedScenicPlace.name)
                    ||!same(lat,expectedScenicPlace.lat)||!same(lon,expectedScenicPlace.lon)))throw invalid();
            if(distanceToRoute(lat,lon,coordinates,originLat)>(expectedScenicPlace==null?700.000001:150.000001))throw invalid();
            waypoints.add(new CourseEngine.Spot(name,"풍경 길 통과 지점 · 별도 체류 시간 없음 · "+waypointSource,lat,lon));
        }
        return new Route(id,title,reason,distance,duration,coordinates,labels,waypoints,destination,originLat,originLon,theme);
    }

    static boolean validTheme(String theme){
        return "all".equals(theme)||"coast".equals(theme)||"forest".equals(theme)||"mountain".equals(theme)
                ||"water".equals(theme)||"countryside".equals(theme)||"city".equals(theme);
    }
    /** Local equirectangular projection keeps sparse road polylines valid at a pass-through. */
    private static double distanceToRoute(double lat,double lon,double[][] coordinates,double referenceLat){
        double latitudeScale=6371000*Math.PI/180,longitudeScale=latitudeScale*Math.cos(Math.toRadians(referenceLat)),min=Double.POSITIVE_INFINITY;
        for(int i=1;i<coordinates.length;i++){
            double ax=(coordinates[i-1][1]-lon)*longitudeScale,ay=(coordinates[i-1][0]-lat)*latitudeScale;
            double bx=(coordinates[i][1]-lon)*longitudeScale,by=(coordinates[i][0]-lat)*latitudeScale;
            double dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy;
            double t=length==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/length));
            min=Math.min(min,Math.hypot(ax+t*dx,ay+t*dy));
        }
        return min;
    }
    private static boolean same(double a,double b){return Math.abs(a-b)<=0.0000001;}
    private static String text(JSONObject object,String key,int max)throws JSONException{return DestinationPlace.text(object,key,max,false);}
    private static double number(JSONObject object,String key,double min,double max)throws JSONException{return DestinationPlace.number(object.get(key),min,max);}
    private static JSONException invalid(){return new JSONException("Invalid destination route response");}
}
