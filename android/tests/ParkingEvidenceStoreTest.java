package com.scenic.pleos;
public final class ParkingEvidenceStoreTest {
    private static int checks;private static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    public static void main(String[] args){
        final String[] data={"{}"};ParkingEvidenceStore.Persistence p=new ParkingEvidenceStore.Persistence(){public String read(){return data[0];}public void write(String value){data[0]=value;}};ParkingEvidenceStore store=new ParkingEvidenceStore(p);long now=1750000000000L;
        check(store.get("1")==null,"Unverified places have no invented evidence");store.save("1","available","식당에 전화 확인","뒷문 주차장 가능",now);ParkingEvidenceStore.Evidence e=new ParkingEvidenceStore(p).get("1");check(e.note.equals("뒷문 주차장 가능")&&e.checkedAt==now,"Evidence and date survive restart");check(e.decision(now+1000).equals("available"),"Recent observation stays available");check(e.decision(now+ParkingEvidenceStore.FRESH_MILLIS+1).equals("unknown"),"Old evidence becomes uncertain, not a live guarantee");check(e.decision(now-1).equals("unknown"),"Future evidence is not current");
        store.save("1","unavailable","직접 방문 확인","주차장 없음",now);check(store.get("1").status.equals("unavailable"),"Explicit contradictory observation replaces old claim");
        try{store.save("2","available","","",now);throw new AssertionError("Missing evidence accepted");}catch(IllegalArgumentException expected){checks++;}
        store.remove("1");check(new ParkingEvidenceStore(p).get("1")==null,"User can delete own observation");System.out.println("ParkingEvidenceStoreTest: "+checks+" checks passed");
    }
}
