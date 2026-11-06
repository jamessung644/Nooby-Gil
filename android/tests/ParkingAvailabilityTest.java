package com.scenic.pleos;

import java.time.Instant;
import org.json.JSONObject;

public final class ParkingAvailabilityTest {
    private static int checks;
    private static final long SERVER=Instant.parse("2026-11-06T08:00:00Z").toEpochMilli(),LOCAL=SERVER-17*60*60*1000L;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface InvalidCase { void run()throws Exception; }
    private static void rejected(InvalidCase action,String label)throws Exception{boolean failed=false;try{action.run();}catch(org.json.JSONException expected){failed=true;}check(failed,label);}
    private static JSONObject row(String status,int free,long age)throws Exception{
        return new JSONObject().put("parkingStatus",status).put("parking",new JSONObject().put("status",status).put("reason","live")
                .put("availableSpaces",free).put("totalSpaces",100).put("observedAt",Instant.ofEpochMilli(SERVER-age).toString())
                .put("fetchedAt",Instant.ofEpochMilli(SERVER).toString()).put("source","서울열린데이터광장 · 시영주차장")
                .put("sourceUrl","https://data.seoul.go.kr/dataList/OA-21709/A/1/datasetView.do").put("providerId","seoul:001"));
    }
    private static JSONObject unknown(String reason)throws Exception{JSONObject value=row("unknown",0,1000);value.getJSONObject("parking").put("reason",reason).put("availableSpaces",JSONObject.NULL);return value;}
    public static void main(String[] args)throws Exception{
        ParkingAvailability available=ParkingAvailability.read(row("available",3,120000),LOCAL);
        check(available.isAvailable(LOCAL),"Real free counts are available despite device/server clock offset");
        check(!available.isFull(LOCAL),"Positive free count is not full");
        check(available.availableSpaces==3&&available.totalSpaces==100,"Exact provider inventory retained");
        check(available.source.contains("서울")&&available.providerId.equals("seoul:001"),"Source and provider ID retained");
        check(available.observedAtMillis==SERVER-120000&&available.fetchedAtMillis==SERVER,"Provider times retained");
        check(available.isAvailable(LOCAL+480000),"Exactly ten minutes remains fresh");
        check(!available.isAvailable(LOCAL+480001),"Inventory ages while visible without a new response");
        check(available.currentStatus(LOCAL+480001).equals("unknown"),"Aged free inventory becomes unknown");
        check(!available.isFresh(LOCAL-1),"Local clock rollback is not fresh");
        check(available.priority(LOCAL)==0&&available.priority(LOCAL+600000)==1,"Stale positive count loses recommendation priority");
        check(available.unavailableReason(LOCAL+600000).contains("오래"),"Expired inventory explains stale status");
        ParkingAvailability full=ParkingAvailability.read(row("full",0,0),LOCAL);
        check(full.isFull(LOCAL)&&!full.isAvailable(LOCAL),"Verified zero spaces is full");
        check(full.priority(LOCAL)==2,"Full sorts after unknown");
        check(!full.isFull(LOCAL+600001),"Expired full observation cannot force automatic reroute");
        check(ParkingAvailability.read(row("available",2,600001),LOCAL).currentStatus(LOCAL).equals("unknown"),"Provider-old available is not trusted");
        check(ParkingAvailability.read(row("full",0,600001),LOCAL).currentStatus(LOCAL).equals("unknown"),"Provider-old full is not trusted");
        check(ParkingAvailability.read(row("available",2,-60000),LOCAL).isAvailable(LOCAL),"One-minute provider timestamp tolerance");
        check(!ParkingAvailability.read(row("available",2,-60001),LOCAL).isAvailable(LOCAL),"Larger future timestamp cannot produce availability");
        for(String reason:new String[]{"stale","unsupported","unavailable","unmatched"}){
            ParkingAvailability state=ParkingAvailability.read(unknown(reason),LOCAL);
            check(!state.isAvailable(LOCAL)&&!state.isFull(LOCAL),reason+" never infers live state");
            check(state.priority(LOCAL)==1&&!state.unavailableReason(LOCAL).isEmpty(),reason+" stays explicit unknown");
        }
        JSONObject stalePositive=unknown("stale");stalePositive.getJSONObject("parking").put("availableSpaces",99);
        check(!ParkingAvailability.read(stalePositive,LOCAL).isAvailable(LOCAL),"Historical positive count is never live");
        check(ParkingAvailability.read(new JSONObject().put("parkingStatus","unknown"),LOCAL).currentStatus(LOCAL).equals("unknown"),"Legacy unknown-only server accepted");
        check(ParkingAvailability.read(new JSONObject().put("parkingStatus","unknown").put("parking",JSONObject.NULL),LOCAL).currentStatus(LOCAL).equals("unknown"),"Explicit null inventory accepted only as unknown");
        rejected(()->ParkingAvailability.read(new JSONObject().put("parkingStatus","available"),LOCAL),"No live state without evidence object");
        rejected(()->ParkingAvailability.read(row("available",0,0),LOCAL),"Zero cannot be available");
        rejected(()->ParkingAvailability.read(row("full",1,0),LOCAL),"Positive cannot be full");
        rejected(()->ParkingAvailability.read(row("available",101,0),LOCAL),"Free count cannot exceed capacity");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("availableSpaces","3");ParkingAvailability.read(bad,LOCAL);},"String counts rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("availableSpaces",1.5);ParkingAvailability.read(bad,LOCAL);},"Fractional counts rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("totalSpaces",-1);ParkingAvailability.read(bad,LOCAL);},"Negative capacity rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("availableSpaces",JSONObject.NULL);ParkingAvailability.read(bad,LOCAL);},"Missing count cannot be available");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("status","full");ParkingAvailability.read(bad,LOCAL);},"Envelope status mismatch rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("reason","stale");ParkingAvailability.read(bad,LOCAL);},"Non-live reason cannot claim available");
        rejected(()->ParkingAvailability.read(row("unknown",0,0),LOCAL),"Unknown cannot claim live provenance");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("source","");ParkingAvailability.read(bad,LOCAL);},"Live source required");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("providerId",JSONObject.NULL);ParkingAvailability.read(bad,LOCAL);},"Live provider identity required");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("sourceUrl","javascript:alert(1)");ParkingAvailability.read(bad,LOCAL);},"Unsafe source link rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("sourceUrl","https://user:pass@example.com/data");ParkingAvailability.read(bad,LOCAL);},"Source links cannot contain credentials");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("observedAt","2026-11-06 08:00:00");ParkingAvailability.read(bad,LOCAL);},"Ambiguous timestamp rejected");
        rejected(()->{JSONObject bad=row("available",3,0);bad.getJSONObject("parking").put("observedAt",JSONObject.NULL);ParkingAvailability.read(bad,LOCAL);},"Live provider timestamp required");
        JSONObject noCapacity=row("available",3,0);noCapacity.getJSONObject("parking").put("totalSpaces",JSONObject.NULL);
        check(ParkingAvailability.read(noCapacity,LOCAL).isAvailable(LOCAL),"Explicit free counts can be used without inventing a total");
        System.out.println("ParkingAvailabilityTest: "+checks+" checks passed");
    }
}
