package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Offline catalog selection. Distance/time estimates are not road routing or live ETA. */
public final class CourseEngine {
    public static final int DISPLAY_LIMIT = 12;
    public static final String[] REGION_IDS = {"all","11","26","27","28","29","30","31","36","41","43","44","46","47","48","50","51","52"};
    public static final String[] REGION_NAMES = {"전국","서울","부산","대구","인천","광주","대전","울산","세종","경기","충북","충남","전남","경북","경남","제주","강원","전북"};
    public static final class Spot {
        public final String title, description;
        public final double lat, lon;
        public Spot(String title, String description, double lat, double lon) {
            this.title=title; this.description=description; this.lat=lat; this.lon=lon;
        }
    }
    public static final class Course {
        public final String id, name, subtitle, scene, tag, distance, regionId, regionName;
        public final int minutes;
        public final double centerLat, centerLon;
        public final Spot[] spots;
        public Course(String id,String name,String subtitle,String scene,String tag,String distance,int minutes,Spot... spots) {
            this(id,name,subtitle,scene,tag,distance,minutes,"","",spots.length==0?0:spots[0].lat,spots.length==0?0:spots[0].lon,spots);
        }
        public Course(String id,String name,String subtitle,String scene,String tag,String distance,int minutes,String regionId,String regionName,double centerLat,double centerLon,Spot... spots) {
            this.id=id; this.name=name; this.subtitle=subtitle; this.scene=scene; this.tag=tag;
            this.distance=distance; this.minutes=minutes; this.spots=spots;
            this.regionId=regionId; this.regionName=regionName; this.centerLat=centerLat; this.centerLon=centerLon;
        }
    }
    public static final Course[] COURSES = buildCatalog();
    private static Course[] buildCatalog(){
        // Keep the original IDs and geometry compatible with saved moments and map clients.
        Course[] legacy={
            new Course("chuncheon","춘천, 물빛을 따라서","호수와 숲 사이로 이어지는 느린 오후","lake","호수 · 숲","약 42 km",85,"51","강원",37.8617,127.6764,
                new Spot("의암호 출발","잔잔한 호수의 결을 만나는 구간",37.8617,127.6764),
                new Spot("의암호 스카이워크","주차 후 걸어서 감상하는 호수 전망",37.8574,127.6897),
                new Spot("공지천","도심의 물길과 만나는 산책 지점",37.8721,127.7075),
                new Spot("소양강 스카이워크","강 위의 빛으로 마무리하는 여정",37.8938,127.7227)),
            new Course("gangneung","강릉, 파도 옆으로","바닷바람이 흐르는 동해안의 장면들","sea","바다 · 해안","약 36 km",110,"51","강원",37.7729,128.9483,
                new Spot("안목해변 출발","커피 향과 함께 시작하는 해안 여정",37.7729,128.9483),
                new Spot("경포해변","넓게 펼쳐진 모래와 푸른 수평선",37.8055,128.9075),
                new Spot("사천해변","잔잔한 해변 마을에서 잠시 쉬어가기",37.8385,128.8771),
                new Spot("영진해변","시야 가득 들어오는 동해의 풍경",37.8701,128.8457)),
            new Course("seoul","서울, 빛이 흐르는 밤","한강과 도시의 불빛이 만드는 리듬","night","도시 · 야경","약 28 km",60,"11","서울",37.5100,126.9959,
                new Spot("반포한강공원 출발","도심의 빛과 강이 만나는 출발점",37.5100,126.9959),
                new Spot("잠원한강공원","주차 후 즐기는 수변 야경",37.5212,127.0108),
                new Spot("서울숲","도시 안에 남아 있는 조용한 녹지",37.5445,127.0374),
                new Spot("뚝섬한강공원","강 건너 빛으로 마무리하는 저녁",37.5293,127.0699))
        };
        Course[] generated=NationwideCatalog.courses(),all=new Course[legacy.length+generated.length];
        System.arraycopy(legacy,0,all,0,legacy.length);System.arraycopy(generated,0,all,legacy.length,generated.length);return all;
    }
    /** A monotonic-timestamped fix. No location is stored or sent by this class. */
    public static final class Position {
        public final double lat, lon;
        public final boolean hasAccuracy;
        public final float accuracy;
        public final long fixNanos, nowNanos;
        public Position(double lat,double lon,boolean hasAccuracy,float accuracy,long fixNanos,long nowNanos) {
            this.lat=lat;this.lon=lon;this.hasAccuracy=hasAccuracy;this.accuracy=accuracy;this.fixNanos=fixNanos;this.nowNanos=nowNanos;
        }
        public boolean fresh(){return LocationFixPolicy.isDisplayable(lat,lon,hasAccuracy,accuracy,fixNanos,nowNanos);}
    }
    public enum State { READY, BROWSING, LOCATION_REQUIRED, LOCATION_STALE, OUTSIDE_KOREA, UNSUPPORTED_ISLAND, NO_NEARBY, TIME_TOO_SHORT }
    public static final class Match {
        public final Course course;
        public final double distanceKm, score;
        public final int approachMinutes,totalMinutes;
        Match(Course course,double distanceKm,int approachMinutes,double score){this.course=course;this.distanceKm=distanceKm;this.approachMinutes=approachMinutes;this.totalMinutes=course.minutes+approachMinutes;this.score=score;}
    }
    public static final class Result {
        public final List<Match> matches;
        public final State state;
        public final int radiusKm,catalogCount;
        public final boolean nearby;
        Result(List<Match> matches,State state,int radiusKm,int catalogCount,boolean nearby){this.matches=Collections.unmodifiableList(matches);this.state=state;this.radiusKm=radiusKm;this.catalogCount=catalogCount;this.nearby=nearby;}
        public Match find(String id){for(Match m:matches)if(m.course.id.equals(id))return m;return null;}
        public List<Course> displayed(){List<Course> result=new ArrayList<>();for(int i=0;i<Math.min(DISPLAY_LIMIT,matches.size());i++)result.add(matches.get(i).course);return result;}
    }
    public static Result recommend(int budget,String scene,String regionId,boolean nearby,Position position,int radiusKm){return recommend(COURSES,budget,scene,regionId,nearby,position,radiusKm);}
    static Result recommend(Course[] catalog,int budget,String scene,String regionId,boolean nearby,Position position,int radiusKm){
        if(budget<1||budget>720)throw new IllegalArgumentException("Time must be 1–720 minutes");
        if(radiusKm!=0&&radiusKm!=20&&radiusKm!=50&&radiusKm!=100)throw new IllegalArgumentException("Unsupported radius");
        final int radius=radiusKm==0?(budget<=60?20:budget<=120?50:100):radiusKm;
        List<Match> result=new ArrayList<>();
        if(nearby){
            if(position==null)return new Result(result,State.LOCATION_REQUIRED,radius,catalog.length,true);
            if(!position.fresh())return new Result(result,State.LOCATION_STALE,radius,catalog.length,true);
            if(!inKorea(position.lat,position.lon))return new Result(result,State.OUTSIDE_KOREA,radius,catalog.length,true);
            if(unsupportedIslandOrigin(position.lat,position.lon))return new Result(result,State.UNSUPPORTED_ISLAND,radius,catalog.length,true);
        }
        boolean geographicalMatch=false;
        for(Course course:catalog){
            if(course.spots.length<2||course.minutes<1)continue;
            if(regionId!=null&&!"all".equals(regionId)&&!regionId.equals(course.regionId))continue;
            double km=Double.NaN;int approach=0;
            if(nearby){
                // An island crossing cannot be treated as a short, direct car approach.
                if(isJeju(position.lat,position.lon)!=isJejuCourse(course))continue;
                km=distanceMeters(position.lat,position.lon,course.spots[0].lat,course.spots[0].lon)/1000.0;
                if(km>radius)continue;
                approach=estimateApproachMinutes(km);
            }
            geographicalMatch=true;
            if(course.minutes+approach>budget)continue;
            double value=score(course,budget-approach,scene);
            if(nearby)value-=Math.min(35,km/radius*35);
            result.add(new Match(course,km,approach,value));
        }
        Collections.sort(result,new Comparator<Match>(){public int compare(Match a,Match b){
            int scoreOrder=Double.compare(b.score,a.score);if(scoreOrder!=0)return scoreOrder;
            int distanceOrder=nearby?Double.compare(a.distanceKm,b.distanceKm):0;if(distanceOrder!=0)return distanceOrder;
            return a.course.id.compareTo(b.course.id);
        }});
        State state=result.isEmpty()?(geographicalMatch?State.TIME_TOO_SHORT:State.NO_NEARBY):(nearby?State.READY:State.BROWSING);
        return new Result(result,state,radius,catalog.length,nearby);
    }
    public static int estimateApproachMinutes(double straightKm){
        if(!Double.isFinite(straightKm)||straightKm<0)throw new IllegalArgumentException("Invalid distance");
        if(straightKm<=0.3)return 0;
        // Approximate detour factor, 35 km/h and 5 min margin, rounded up to 5 min.
        return (int)Math.ceil((straightKm*1.35/35.0*60+5)/5)*5;
    }
    public static boolean inKorea(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=33.0&&lat<=38.65&&lon>=124.5&&lon<=131.95;}
    public static boolean unsupportedIslandOrigin(double lat,double lon){
        // Exclude known ferry-only satellite islands conservatively; never infer a road
        // connection to Jeju solely from a nearby coordinate in its broad region box.
        boolean udo=lat>=33.47&&lat<=33.54&&lon>=126.935&&lon<=126.98;
        boolean gapadoOrMarado=lat>=33.10&&lat<=33.20&&lon>=126.22&&lon<=126.30;
        boolean chujado=lat>=33.88&&lat<=34.0&&lon>=126.25&&lon<=126.40;
        return udo||gapadoOrMarado||chujado;
    }
    public static boolean isJeju(double lat,double lon){return lat>=33.0&&lat<=33.65&&lon>=126.05&&lon<=126.98;}
    private static boolean isJejuCourse(Course course){return "50".equals(course.regionId)||isJeju(course.spots[0].lat,course.spots[0].lon);}
    public static double score(Course c,int budgetMinutes,String preferredScene){
        if(budgetMinutes<=0)throw new IllegalArgumentException("Time must be positive");
        double timeFit=Math.max(0,100-Math.abs(c.minutes-budgetMinutes)*0.8);
        if(c.minutes>budgetMinutes)timeFit=Math.max(0,timeFit-(c.minutes-budgetMinutes)*0.6);
        boolean taste="all".equals(preferredScene)||c.scene.equals(preferredScene)||("lake".equals(preferredScene)&&"forest".equals(c.scene));
        return Math.min(100,Math.max(0,Math.round(timeFit*0.55+(taste?100:45)*0.45)));
    }
    public static List<Course> ranked(final int budget,final String preference){return recommend(budget,preference,"all",false,null,0).displayed();}
    public static Course byId(String id){for(Course course:COURSES)if(course.id.equals(id))return course;return null;}
    public static double distanceMeters(double lat1,double lon1,double lat2,double lon2){
        double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dp=p2-p1,dl=Math.toRadians(lon2-lon1);
        double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 6371000*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
    }
    private CourseEngine() {}
}
