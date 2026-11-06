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
        System.out.println("PhotoDestinationRoutesTest: "+checks+" checks passed");
    }
}
