package com.scenic.pleos;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Region search context must never replace the user's selected photographic destination. */
public final class PhotoDestinationRoutesTest {
    private static int checks;
    private interface Change {void apply(JSONObject object)throws Exception;}
    private static DestinationPlace region()throws Exception{return DestinationPlace.of("region-busan","부산 해안","탐색 기준",35.1587,129.1604);}
    private static DestinationPlace photo()throws Exception{return DestinationPlace.of("tour-12345","청사포","관광 장소",35.1595,129.1926);}
    private static JSONObject place(DestinationPlace p)throws Exception{return new JSONObject().put("id",p.id).put("name",p.name).put("address",p.address).put("lat",p.lat).put("lon",p.lon);}
    private static JSONObject response()throws Exception{
        JSONObject route=new JSONObject().put("id","fast").put("title","사진 장소로 가는 길").put("reason","선택한 사진 장소로 갑니다.").put("source","Kakao Mobility")
            .put("distanceKm",21).put("durationMin",35).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.1595,129.1926]]"))
            .put("scenicLabels",new JSONArray().put("청사포")).put("waypoints",new JSONArray());
        return new JSONObject().put("mode","photo-destination").put("requestedDestination",place(region())).put("destination",place(photo()))
            .put("origin",new JSONObject().put("lat",35.1151).put("lon",129.0414)).put("scenicStatus","ok").put("message","관광 장소로 가는 도로를 확인했어요.").put("routes",new JSONArray().put(route));
    }
    private static DestinationRoutePlan parse(JSONObject o)throws Exception{return DestinationRoutePlan.parsePhotoDestination(o,region(),photo(),35.1151,129.0414,"coast");}
    private static JSONObject withScenic(double minutes,String coordinates)throws Exception{
        JSONObject o=response();
        JSONObject scenic=new JSONObject().put("id","scenic").put("title","풍경 경로").put("reason","달맞이길을 지나 같은 목적지로 갑니다.").put("source","Kakao Mobility")
            .put("distanceKm",23).put("durationMin",minutes).put("coordinates",new JSONArray(coordinates)).put("headline","달맞이길 경유")
            .put("scenicLabels",new JSONArray().put("해안 3km")).put("waypoints",new JSONArray().put(new JSONObject().put("id","tour-777").put("name","달맞이길").put("lat",35.15).put("lon",129.15).put("source","한국관광공사 TourAPI")));
        o.getJSONArray("routes").put(scenic);o.put("scenicStatus","ok");return o;
    }
    private static JSONObject withOrderedStops(String id,String coordinates)throws Exception{
        JSONObject o=withScenic(40,coordinates);
        o.getJSONArray("routes").getJSONObject(1).put("id",id).put("waypoints",new JSONArray()
            .put(new JSONObject().put("id","first").put("name","첫 해안").put("lat",35.15).put("lon",129.09).put("source","한국관광공사 TourAPI"))
            .put(new JSONObject().put("id","second").put("name","둘째 해안").put("lat",35.12).put("lon",129.15).put("source","한국관광공사 TourAPI")));
        return o;
    }
    private static void rejectExtras(JSONObject o)throws Exception{try{DestinationRoutePlan.parsePhotoDestination(o,region(),photo(),35.1151,129.0414,"coast",30);throw new AssertionError("Accepted a scenic road that misses the photo place or the time budget");}catch(JSONException expected){checks++;}}
    private static void reject(Change change)throws Exception{JSONObject o=response();change.apply(o);try{parse(o);throw new AssertionError("Accepted substituted/unreachable photo");}catch(JSONException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        DestinationRoutePlan plan=parse(response());
        if(!plan.destination.id.equals(photo().id)||plan.routes.size()!=1||plan.scenic!=null||plan.fast.waypoints.size()!=0)throw new AssertionError("Photo is final, no region detour");checks++;
        CourseEngine.Course course=plan.fast.toCourse();if(!course.spots[course.spots.length-1].title.equals(photo().name)||course.spots.length!=2)throw new AssertionError("Map ends at photo");checks++;
        reject(o->o.put("destination",place(region())));
        reject(o->o.getJSONObject("requestedDestination").put("id","another-region"));
        reject(o->o.getJSONObject("destination").put("lat",35.161));
        reject(o->o.getJSONArray("routes").getJSONObject(0).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.1587,129.1604]]")));
        reject(o->o.getJSONObject("origin").put("lat",37.56));
        reject(o->o.put("mode","fixed-destination"));
        reject(o->o.put("scenicStatus","disabled"));
        reject(o->o.getJSONArray("routes").put(o.getJSONArray("routes").getJSONObject(0)));
        reject(o->o.getJSONArray("routes").getJSONObject(0).put("source","invented-road"));
        JSONObject near=response();near.getJSONArray("routes").getJSONObject(0).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.1600,129.1926]]"));parse(near);checks++;
        // With extra minutes the same photo place may also be reached by a scenic road.
        DestinationRoutePlan extras=DestinationRoutePlan.parsePhotoDestination(withScenic(40,"[[35.1151,129.0414],[35.1500,129.1500],[35.1595,129.1926]]"),region(),photo(),35.1151,129.0414,"coast",30);
        if(extras.routes.size()!=2||extras.scenic==null||!extras.destination.id.equals(photo().id)||extras.scenic.waypoints.size()!=1)throw new AssertionError("Scenic road to the photo place");checks++;
        rejectExtras(withScenic(80,"[[35.1151,129.0414],[35.1500,129.1500],[35.1595,129.1926]]"));
        rejectExtras(withScenic(40,"[[35.1151,129.0414],[35.1500,129.1500],[35.1587,129.1604]]"));
        for(String id:new String[]{"curated","scenic"}){
            rejectExtras(withOrderedStops(id,"[[35.1151,129.0414],[35.12,129.15],[35.15,129.09],[35.1595,129.1926]]"));
            for(String geometry:new String[]{
                "[[35.1151,129.0414],[35.15,129.09],[35.12,129.15],[35.1595,129.1926]]",
                "[[35.1151,129.0414],[35.12,129.15],[35.15,129.09],[35.12,129.15],[35.1595,129.1926]]"}){
                DestinationRoutePlan ordered=DestinationRoutePlan.parsePhotoDestination(withOrderedStops(id,geometry),region(),photo(),35.1151,129.0414,"coast",30);
                if(ordered.routes.get(1).waypoints.size()!=2)throw new AssertionError("Ordered stops survive an earlier pass of the second place");checks++;
            }
        }
        try{DestinationRoutePlan.parsePhotoDestination(withScenic(40,"[[35.1151,129.0414],[35.1500,129.1500],[35.1595,129.1926]]"),region(),photo(),35.1151,129.0414,"coast");throw new AssertionError("Extra roads need extra minutes");}catch(JSONException expected){checks++;}
        System.out.println("PhotoDestinationRoutesTest: "+checks+" checks passed");
    }
}
