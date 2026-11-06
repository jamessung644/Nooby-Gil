package com.scenic.pleos;

import java.util.Collections;
import java.util.HashSet;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ParkingReroutePolicyTest {
    private static int checks;
    private static final long NOW=Instant.parse("2026-11-06T09:00:00Z").toEpochMilli();
    private static JSONObject row(String id,String status,int free)throws Exception{
        JSONObject inventory=new JSONObject().put("status",status).put("reason","unknown".equals(status)?"unsupported":"live").put("availableSpaces","unknown".equals(status)?JSONObject.NULL:free).put("totalSpaces",100)
                .put("observedAt","2026-11-06T08:59:00Z").put("fetchedAt","2026-11-06T09:00:00Z").put("source","서울시").put("sourceUrl","https://data.seoul.go.kr/").put("providerId",id);
        return new JSONObject().put("id",id).put("name",id+" 공영주차장").put("address","서울").put("lat",37.5665).put("lon",126.978).put("distanceMeters",100).put("parkingStatus",status).put("parking",inventory);
    }
    private static JSONObject response(JSONObject focus,JSONObject... rows)throws Exception{
        JSONArray places=new JSONArray();for(JSONObject row:rows)places.put(row);return new JSONObject().put("source","Kakao Local").put("kind","parking").put("focus",focus==null?JSONObject.NULL:focus).put("places",places);
    }
    public static void main(String[] args)throws Exception{
        JSONObject full=row("1","full",0),free=row("2","available",12),unknown=row("3","unknown",0);
        DestinationPlace target=DestinationPlace.read(full,"Kakao Local");
        check(ParkingReroutePolicy.evaluate(response(full,free),target,Collections.emptySet(),NOW).alternative.id.equals("2"),"Full focused lot can be outside ranked ten");
        check(ParkingReroutePolicy.evaluate(response(null,full,free),target,Collections.emptySet(),NOW).alternative.id.equals("2"),"Focus fallback finds exact selected lot");
        check(ParkingReroutePolicy.evaluate(response(row("1","available",2),free),target,Collections.emptySet(),NOW).alternative==null,"Available current lot never changes");
        check(ParkingReroutePolicy.evaluate(response(row("1","unknown",0),free),target,Collections.emptySet(),NOW).alternative==null,"Unknown current inventory never changes route");
        check(ParkingReroutePolicy.evaluate(response(null,free),target,Collections.emptySet(),NOW).alternative==null,"Missing current lot is not full");
        check(ParkingReroutePolicy.evaluate(response(full,unknown),target,Collections.emptySet(),NOW).alternative==null,"Unknown alternative never counts as free");
        check(ParkingReroutePolicy.evaluate(response(full,row("2","full",0)),target,Collections.emptySet(),NOW).alternative==null,"Another full lot never selected");
        check(ParkingReroutePolicy.evaluate(response(full,free),target,new HashSet<>(Collections.singletonList("2")),NOW).alternative==null,"Previously visited target is excluded");
        JSONObject stale=row("1","full",0);stale.getJSONObject("parking").put("observedAt","2026-11-06T08:45:00Z");
        check(ParkingReroutePolicy.evaluate(response(stale,free),target,Collections.emptySet(),NOW).alternative==null,"Stale full observation cannot reroute");
        JSONObject staleFree=row("2","available",12);staleFree.getJSONObject("parking").put("observedAt","2026-11-06T08:45:00Z");
        check(ParkingReroutePolicy.evaluate(response(full,staleFree),target,Collections.emptySet(),NOW).alternative==null,"Stale free observation cannot be replacement");
        reject(()->ParkingReroutePolicy.evaluate(response(row("9","full",0),free),target,Collections.emptySet(),NOW),"Different focused ID rejected");
        JSONObject moved=row("1","full",0).put("lat",37.6);reject(()->ParkingReroutePolicy.evaluate(response(moved,free),target,Collections.emptySet(),NOW),"Changed coordinates rejected");
        reject(()->ParkingReroutePolicy.evaluate(response(full,free).put("source","fixture"),target,Collections.emptySet(),NOW),"Wrong source rejected");
        JSONObject distant=row("2","available",12).put("distanceMeters",3001);reject(()->ParkingReroutePolicy.evaluate(response(full,distant),target,Collections.emptySet(),NOW),"Alternative must be near the meal anchor");
        System.out.println("ParkingReroutePolicyTest: "+checks+" checks passed");
    }
    private interface Checked{void run()throws Exception;}
    private static void reject(Checked test,String message)throws Exception{try{test.run();throw new AssertionError(message);}catch(org.json.JSONException expected){checks++;}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
