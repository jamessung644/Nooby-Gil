package com.scenic.pleos;

public final class ScenicCaptureStoreTest {
    private static final class Memory implements ScenicCaptureStore.Persistence {
        String value="";int writes;
        public String read(){return value;}
        public void write(String value){this.value=value;writes++;}
    }
    private static void check(boolean truth,String message){if(!truth)throw new AssertionError(message);}
    public static void main(String[] args) {
        Memory memory=new Memory();ScenicCaptureStore store=new ScenicCaptureStore(memory);
        check(!store.sharingEnabled(),"Sharing preview starts disabled");
        ScenicCaptureStore.Record draft=store.draft("route-a","해운대까지 | 풍경 길","해운대해수욕장","광안리 해변",1740000000000L);
        check(memory.writes==0,"Opening a capture preview does not save a record");
        check("gwangalli".equals(draft.imageKey),"Scene image follows the selected segment before destination");
        try{store.save(draft,false);throw new AssertionError("A blocked interaction saved a capture");}
        catch(IllegalStateException expected){check(store.recordsForRoute(null).isEmpty(),"Blocked save leaves gallery unchanged");}
        store.setSharingEnabled(true);ScenicCaptureStore.Record saved=store.save(draft,true);
        check(saved.sharedPreview,"Sharing choice made on the preview is captured when saving");
        store.save(draft,true);check(store.recordsForRoute("route-a").size()==1,"Double tapping add does not duplicate a record");
        check(store.recordsForRoute("route-b").isEmpty(),"A different route does not inherit records");
        store.save(store.draft("route-b","송정까지","송정해수욕장","청사포 해안",1740000000100L),true);
        ScenicCaptureStore restored=new ScenicCaptureStore(memory);
        check(restored.sharingEnabled(),"Sharing preference survives recreation");
        check(restored.recordsForRoute(null).size()==2,"All records survive recreation");
        ScenicCaptureStore.Record record=restored.recordsForRoute("route-a").get(0);
        check(saved.id.equals(record.id),"Stable capture id survives recreation");
        check("해운대까지 | 풍경 길".equals(record.routeName),"Unicode and delimiter characters survive persistence");
        check("해운대해수욕장".equals(record.destination)&&"광안리 해변".equals(record.segmentName),"Route context stays attached to its capture");
        check(record.sharedPreview,"Record preview visibility survives recreation");
        restored.setSharingEnabled(false);
        check(restored.recordsForRoute("route-a").get(0).sharedPreview,"Changing next capture preference does not rewrite earlier captures");
        check("coast".equals(restored.recordsForRoute("route-b").get(0).imageKey),"Cheongsapo receives the actual coastal sample");
        memory.value+="corrupted|capture|row\n";
        check(new ScenicCaptureStore(memory).recordsForRoute(null).size()==2,"A malformed row does not discard other captures");
        try{store.draft("","route","destination","segment",1);throw new AssertionError("Missing route context accepted");}
        catch(IllegalArgumentException expected){}
        System.out.println("ScenicCaptureStoreTest passed");
    }
}
