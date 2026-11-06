package com.scenic.pleos;

import java.util.HashSet;
import java.util.Set;

/** Pure Java checks cover candidate membership and time, not rendering implementation. */
public final class CourseEngineTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static CourseEngine.Position position(double lat,double lon){return new CourseEngine.Position(lat,lon,true,10,100_000_000_000L,101_000_000_000L);}
    private static CourseEngine.Course course(String id,String region,double lat,double lon,int minutes,String scene){return new CourseEngine.Course(id,id,"",scene,"","",minutes,region,region,lat,lon,new CourseEngine.Spot("Start","",lat,lon),new CourseEngine.Spot("End","",lat+.001,lon+.001));}
    public static void main(String[] args){
        CourseEngine.Course seoul=course("seoul-test","11",37.5665,126.9780,45,"night");
        CourseEngine.Course busan=course("busan-test","26",35.1796,129.0756,45,"sea");
        CourseEngine.Course jeju=course("jeju-test","50",33.4996,126.5312,45,"sea");
        CourseEngine.Course[] cities={seoul,busan,jeju};
        CourseEngine.Result result=CourseEngine.recommend(cities,120,"all","all",true,position(37.5665,126.9780),100);
        check(result.matches.size()==1&&result.matches.get(0).course==seoul,"Seoul GPS excludes Busan and Jeju");
        result=CourseEngine.recommend(cities,120,"all","all",true,position(35.1796,129.0756),100);
        check(result.matches.size()==1&&result.matches.get(0).course==busan,"Busan GPS does not reuse Seoul origin");
        result=CourseEngine.recommend(cities,120,"all","all",true,position(33.4996,126.5312),100);
        check(result.matches.size()==1&&result.matches.get(0).course==jeju,"Jeju GPS stays on Jeju");
        check(CourseEngine.recommend(cities,180,"all","all",true,position(33.504,126.952),50).state==CourseEngine.State.UNSUPPORTED_ISLAND,"Udo cannot approach Jeju courses by straight-line car estimate");
        check(CourseEngine.recommend(cities,180,"all","all",true,position(33.169,126.271),50).state==CourseEngine.State.UNSUPPORTED_ISLAND,"Gapado ferry origin excluded");
        check(CourseEngine.recommend(cities,180,"all","all",true,position(33.953,126.310),50).state==CourseEngine.State.UNSUPPORTED_ISLAND,"Chujado ferry origin excluded");
        CourseEngine.Course mainland=course("mainland-edge","46",34.0,126.5,30,"sea");
        CourseEngine.Course island=course("jeju-edge","50",33.6,126.5,30,"sea");
        result=CourseEngine.recommend(new CourseEngine.Course[]{mainland,island},720,"all","all",true,position(33.6,126.5),100);
        check(result.matches.size()==1&&result.matches.get(0).course==island,"Short straight cross-water approach excluded");
        check(CourseEngine.recommend(cities,120,"all","all",true,null,0).state==CourseEngine.State.LOCATION_REQUIRED,"No GPS is never default Seoul");
        CourseEngine.Position old=new CourseEngine.Position(37.5665,126.9780,true,10,1,40_000_000_000L);
        check(CourseEngine.recommend(cities,120,"all","all",true,old,0).state==CourseEngine.State.LOCATION_STALE,"Stale GPS excluded");
        check(CourseEngine.recommend(cities,120,"all","all",true,position(37.42,-122.08),0).state==CourseEngine.State.OUTSIDE_KOREA,"Default emulator US position explicitly outside Korea");
        result=CourseEngine.recommend(cities,120,"all","26",false,null,0);
        check(result.state==CourseEngine.State.BROWSING&&result.matches.size()==1&&result.matches.get(0).course==busan,"Manual region works without GPS");
        check(result.matches.get(0).approachMinutes==0&&Double.isNaN(result.matches.get(0).distanceKm),"Browsing does not invent current distance");
        check(CourseEngine.recommend(cities,120,"all","all",true,position(36.5,127.5),20).state==CourseEngine.State.NO_NEARBY,"No nearby gives empty result, not nationwide fallback");
        CourseEngine.Course remote=course("approach","11",37.6565,126.9780,45,"night");
        result=CourseEngine.recommend(new CourseEngine.Course[]{remote},60,"all","all",true,position(37.5665,126.9780),20);
        check(result.state==CourseEngine.State.TIME_TOO_SHORT&&result.matches.isEmpty(),"Approach plus course must fit budget");
        result=CourseEngine.recommend(new CourseEngine.Course[]{remote},90,"all","all",true,position(37.5665,126.9780),20);
        check(result.matches.size()==1&&result.matches.get(0).approachMinutes>=25&&result.matches.get(0).totalMinutes<=90,"Rounded approach included in total");
        check(CourseEngine.recommend(cities,1,"all","all",false,null,0).state==CourseEngine.State.TIME_TOO_SHORT,"Short budget returns explicit empty state");
        CourseEngine.Course a=course("a","11",37.5665,126.9780,45,"night"),b=course("b","11",37.5665,126.9780,45,"night");
        result=CourseEngine.recommend(new CourseEngine.Course[]{b,a},90,"all","all",true,position(37.5665,126.9780),20);
        check(result.matches.get(0).course==a,"Tie order deterministic by ID");
        check(CourseEngine.recommend(cities,60,"all","all",false,null,0).radiusKm==20,"Short automatic radius");
        check(CourseEngine.recommend(cities,120,"all","all",false,null,0).radiusKm==50,"Medium automatic radius");
        check(CourseEngine.recommend(cities,180,"all","all",false,null,0).radiusKm==100,"Long automatic radius");
        check(CourseEngine.COURSES.length>=500,"Nationwide catalog contains at least 500 courses");
        Set<String> ids=new HashSet<>(),regions=new HashSet<>();
        for(CourseEngine.Course c:CourseEngine.COURSES){check(ids.add(c.id),"Unique course ID");check(c.spots.length>=2&&c.spots.length<=7,"Supported route stop count");regions.add(c.regionId);for(int budget:new int[]{30,60,90,120,180}){double score=CourseEngine.score(c,budget,"lake");check(score>=0&&score<=100,"Score bounded");}}
        check(regions.size()==17,"All 17 tourism regions represented");
        result=CourseEngine.recommend(720,"all","all",false,null,0);check(result.matches.size()>12&&result.displayed().size()==12,"Render only 12 of matching catalog");
        for(CourseEngine.Position origin:new CourseEngine.Position[]{position(37.5665,126.9780),position(35.1796,129.0756),position(33.4996,126.5312)}){
            result=CourseEngine.recommend(180,"all","all",true,origin,50);check(!result.matches.isEmpty(),"Actual catalog has nearby city candidates");
            for(CourseEngine.Match match:result.matches){check(match.distanceKm<=50&&match.totalMinutes<=180,"Actual matches obey radius and total budget");check(CourseEngine.isJeju(origin.lat,origin.lon)=="50".equals(match.course.regionId),"Actual catalog excludes cross-water matches");}
        }
        check(CourseEngine.distanceMeters(37,127,37,127)<0.01,"Same point");
        double known=CourseEngine.distanceMeters(0,0,0,1);check(known>111000&&known<112000,"Haversine units");
        boolean rejected=false;try{CourseEngine.recommend(0,"all","all",false,null,0);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid time rejected");
        System.out.println("CourseEngineTest: "+checks+" checks passed");
    }
}
