package com.scenic.pleos;

import org.json.JSONArray;
import org.json.JSONObject;

/** Reject untrusted plans before they can become the selected route. */
public final class LivePlanTest {
    private static int checks;
    private interface Change { void apply(JSONObject response) throws Exception; }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static JSONObject response() throws Exception {
        return new JSONObject("{\"source\":\"TourAPI + OpenAI + Kakao Mobility\",\"generatedAt\":\"2026-11-04T13:00:00Z\",\"radiusMeters\":20000,\"candidateCount\":5,\"status\":\"ok\",\"message\":\"도로 경로를 확인했어요.\",\"courses\":[{\"id\":\"live-test-route\",\"title\":\"서울의 두 장면\",\"reason\":\"가까운 공원과 한강을 연결했어요.\",\"theme\":\"lake\",\"spots\":[{\"id\":\"12345\",\"title\":\"첫 공원\",\"address\":\"서울\",\"lat\":37.51,\"lon\":126.99},{\"id\":\"67890\",\"title\":\"둘째 공원\",\"address\":\"서울\",\"lat\":37.52,\"lon\":127.00}],\"routing\":{\"coordinates\":[[37.50,126.98],[37.51,126.99],[37.52,127.00]],\"distanceKm\":3.5,\"durationMin\":12.5,\"source\":\"Kakao Mobility\",\"fetchedAt\":\"2026-11-04T13:00:00Z\"},\"visitDurationMin\":30,\"totalDurationMin\":42.5}]}");
    }
    private static JSONObject item(JSONObject response)throws Exception{return response.getJSONArray("courses").getJSONObject(0);}
    private static void reject(Change change,String label)throws Exception{
        JSONObject json=response();change.apply(json);try{LivePlan.parse(json,90,37.50,126.98,20000);throw new AssertionError(label);}catch(org.json.JSONException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        LivePlan plan=LivePlan.parse(response(),90,37.50,126.98,20000);
        check(plan.course!=null&&plan.course.id.equals("live-test-route")&&CourseEngine.byId(plan.course.id)==null,"Accept dynamic course absent from offline catalog");
        check(plan.roadCoordinates.length==3&&plan.roadCoordinates[0][0]==37.50,"Preserve approach-origin prefix in validated geometry");
        check(plan.totalDurationMin==42.5&&plan.roadDurationMin==12.5&&plan.visitDurationMin==30,"Preserve exact provider road plus visit metrics");
        check(plan.course.spots.length==2&&plan.course.spots[0].title.equals("첫 공원"),"Use actual chosen POIs as spots, not synthetic origin");
        check(plan.course.minutes==43,"Display integral total rounded up without losing exact budget check");
        check(LivePlan.parse(response(),43,37.50,126.98,20000).course!=null,"Accept total at budget boundary");
        try{LivePlan.parse(response(),42,37.50,126.98,20000);throw new AssertionError("Overbudget");}catch(org.json.JSONException expected){checks++;}
        for(String status:new String[]{"no_candidates","no_feasible_route"}){
            JSONObject json=response().put("status",status).put("courses",new JSONArray());
            LivePlan empty=LivePlan.parse(json,90,37.50,126.98,20000);check(empty.course==null&&empty.status.equals(status),"Represent empty response without inventing offline course");
        }
        reject(j->j.put("source","OpenAI"),"Reject wrong pipeline source");
        reject(j->j.put("candidateCount","5"),"Reject coerced numeric count");
        reject(j->j.put("radiusMeters",50000),"Reject radius different from request");
        reject(j->j.put("status","other"),"Reject unknown status");
        reject(j->j.put("status","no_candidates"),"Reject empty state with a course");
        reject(j->j.put("courses",new JSONArray()),"Successful response needs a course");
        reject(j->j.getJSONArray("courses").put(item(j)),"Reject excess courses");
        reject(j->item(j).put("theme","secret"),"Reject unknown scenery");
        reject(j->item(j).put("title",new JSONObject()),"Reject non-text model title");
        reject(j->item(j).put("reason",""),"Reject blank rationale");
        reject(j->item(j).put("totalDurationMin",42),"Reject road/visit inconsistency");
        reject(j->item(j).put("visitDurationMin",0),"Require per-stop visit allowance");
        reject(j->item(j).getJSONObject("routing").put("distanceKm",0),"Reject zero-length road");
        reject(j->item(j).getJSONObject("routing").put("durationMin",-1),"Reject negative road time");
        reject(j->item(j).getJSONObject("routing").put("source","offline"),"Reject unverified geometry source");
        reject(j->item(j).getJSONObject("routing").put("distanceKm","3.5"),"Reject coerced distance");
        reject(j->item(j).getJSONArray("spots").getJSONObject(1).put("id","12345"),"Reject duplicate actual spot IDs");
        reject(j->item(j).getJSONArray("spots").getJSONObject(0).put("id","model-invented"),"Require TourAPI numeric IDs");
        reject(j->item(j).getJSONArray("spots").getJSONObject(0).put("lat",-37),"Reject outside-Korea stop");
        reject(j->item(j).getJSONArray("spots").getJSONObject(0).put("lat",35),"Reject stop outside requested vicinity");
        reject(j->item(j).getJSONObject("routing").put("coordinates",new JSONArray("[[37.5,126.98]]")),"Reject one-point road");
        reject(j->item(j).getJSONObject("routing").put("coordinates",new JSONArray("[[35,129],[37.52,127]]")),"Reject road missing requested origin approach");
        reject(j->item(j).getJSONObject("routing").put("coordinates",new JSONArray("[[37.5,126.98],[35,129]]")),"Reject road not ending at final stop");
        reject(j->item(j).getJSONObject("routing").put("coordinates",new JSONArray("[[37.5,126.98],[37.52,127,1]]")),"Reject malformed coordinate tuples");
        reject(j->{JSONArray huge=new JSONArray();for(int i=0;i<50001;i++)huge.put(new JSONArray("[37.5,126.98]"));item(j).getJSONObject("routing").put("coordinates",huge);},"Bound route memory use");
        JSONObject literal=response();item(literal).put("title","풍경 <b>그대로</b>");
        check(LivePlan.parse(literal,90,37.5,126.98,20000).course.name.contains("<b>"),"Model text remains a plain literal, never interpreted as HTML");
        CourseEngine.Position requestedFix=new CourseEngine.Position(37.5,126.98,true,10,100_000_000_000L,101_000_000_000L);
        LivePlanOrigin origin=LivePlanOrigin.current(requestedFix);
        CourseEngine.Position refreshedFix=new CourseEngine.Position(37.5001,126.98,true,10,180_000_000_000L,181_000_000_000L);
        check(origin.canApply(1,1,refreshedFix),"Slow pipeline accepts updated fresh fix without requiring the original fix still be younger than 30 seconds");
        check(!origin.canApply(1,2,refreshedFix),"Changed filters reject older request generation");
        check(!origin.canApply(1,1,new CourseEngine.Position(37.6,126.98,true,10,180_000_000_000L,181_000_000_000L)),"Material movement rejects old-origin result");
        check(!origin.canApply(1,1,new CourseEngine.Position(37.5,126.98,true,10,100_000_000_000L,181_000_000_000L)),"Stale completion fix cannot masquerade as current location");
        LivePlanOrigin demo=LivePlanOrigin.seoulCityHall();
        check(demo.publicDemo&&demo.label().contains("실제 현재 위치 아님")&&demo.canApply(1,1,null),"Explicit public-origin demo never requires or pretends to be device GPS");
        check(!demo.canApply(1,2,null),"Public-origin demo still rejects changed request generation");
        System.out.println("LivePlanTest: "+checks+" checks passed");
    }
}
