package com.scenic.pleos;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checkpoint parsing, tracking and the automatic-recording store rules. No camera or UI is involved. */
public final class CheckpointTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Block{void run()throws Exception;}
    private static void rejects(Block block,String label)throws Exception{checks++;try{block.run();}catch(Exception|AssertionError expected){return;}throw new AssertionError(label);}
    private static JSONObject json(String id,String kind,double lat,double lon,double endLat,double endLon,double km)throws Exception{
        return new JSONObject().put("id",id).put("kind",kind).put("name",id+" 구간").put("lat",lat).put("lon",lon).put("endLat",endLat).put("endLon",endLon).put("km",km);
    }
    private static RouteCheckpoint stretch()throws Exception{return RouteCheckpoint.of("coast-1-2","coast","해안 구간",35.10,129.00,35.10,129.05,4.5);}
    private static RouteCheckpoint place()throws Exception{return RouteCheckpoint.of("tour-9-1","place","장소",35.20,129.10,35.20,129.10,0);}

    public static void main(String[] args)throws Exception {
        // ---- parsing ----
        JSONObject route=new JSONObject().put("checkpoints",new JSONArray().put(json("coast-1-2","coast",35.1,129,35.1,129.05,4.5)).put(json("tour-9-1","place",35.2,129.1,35.2,129.1,0)));
        List<RouteCheckpoint> parsed=RouteCheckpoint.parseAll(route);
        check(parsed.size()==2&&parsed.get(0).isStretch()&&!parsed.get(1).isStretch(),"Parse stretches and places");
        check(RouteCheckpoint.parseAll(new JSONObject()).isEmpty(),"A route without checkpoints is valid");
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",new JSONArray().put(json("a","sea",35.1,129,35.1,129.05,1)))),"Unknown kind");
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",new JSONArray().put(json("a b","coast",35.1,129,35.1,129.05,1)))),"Unsafe id");
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",new JSONArray().put(json("a","coast",10,129,35.1,129.05,1)))),"Outside Korea");
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",new JSONArray().put(json("a","coast",35.1,129,35.1,129.05,1)).put(json("a","water",35.1,129,35.1,129.05,1)))),"Duplicate ids");
        JSONArray many=new JSONArray();for(int i=0;i<7;i++)many.put(json("c"+i,"coast",35.1,129,35.1,129.05,1));
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",many)),"At most six checkpoints");
        rejects(()->RouteCheckpoint.parseAll(new JSONObject().put("checkpoints",new JSONArray().put(new JSONObject().put("id","a")))),"Missing fields");

        // ---- tracking ----
        CheckpointTracker tracker=new CheckpointTracker(parsed);
        check(tracker.update(35.0,129.0).isEmpty(),"Far from every checkpoint nothing happens");
        List<CheckpointTracker.Event> start=tracker.update(35.1005,129.0005);
        check(start.size()==1&&start.get(0).kind==CheckpointTracker.Kind.START&&tracker.active()!=null,"Arriving at a stretch starts a clip");
        check(tracker.update(35.1005,129.0006).isEmpty(),"A second fix at the start does not start it again");
        check(tracker.update(35.10,129.03).isEmpty()&&tracker.active()!=null,"Midway the clip keeps running");
        List<CheckpointTracker.Event> end=tracker.update(35.1,129.0502);
        check(end.size()==1&&end.get(0).kind==CheckpointTracker.Kind.END&&tracker.active()==null,"Reaching the end stops the clip");
        check(tracker.update(35.1,129.0).isEmpty(),"Driving back through a finished stretch records nothing");
        List<CheckpointTracker.Event> point=tracker.update(35.2,129.1);
        check(point.size()==2&&point.get(0).kind==CheckpointTracker.Kind.START&&point.get(1).kind==CheckpointTracker.Kind.END,"A place starts and ends at once");
        check(tracker.startedCount()==2,"Each checkpoint fires once");
        CheckpointTracker abandoned=new CheckpointTracker(parsed);
        abandoned.update(35.1,129.0);
        List<CheckpointTracker.Event> left=abandoned.update(35.4,129.5);
        check(left.size()==1&&left.get(0).kind==CheckpointTracker.Kind.END,"A stretch the road never finishes is closed once the car is far away");
        CheckpointTracker open=new CheckpointTracker(parsed);open.update(35.1,129.0);
        check(open.finish()!=null&&open.active()==null&&open.finish()==null,"Finish closes an open clip once");
        check(new CheckpointTracker(new ArrayList<RouteCheckpoint>()).update(35.1,129.0).isEmpty(),"No checkpoints, no events");
        check(new CheckpointTracker(parsed).update(Double.NaN,129.0).isEmpty(),"A bad fix is ignored");

        // ---- store ----
        final String[] saved={""};
        ScenicCaptureStore store=new ScenicCaptureStore(new ScenicCaptureStore.Persistence(){public String read(){return saved[0];}public void write(String value){saved[0]=value;}});
        check(!store.autoCaptureEnabled(),"Automatic recording starts off");
        rejects(()->store.saveAutomatic("r1","경로","목적지",stretch(),1000),"Nothing is recorded while automatic recording is off");
        store.setAutoCaptureEnabled(true);
        ScenicCaptureStore.Record first=store.saveAutomatic("r1","경로","목적지",stretch(),1000);
        check(first.automatic()&&first.checkpointId.equals("coast-1-2")&&first.imageKey.equals("coast"),"A coast checkpoint uses the coast sample");
        ScenicCaptureStore.Record mountain=store.saveAutomatic("r1","경로","목적지",RouteCheckpoint.of("mountain-3-4","mountain","산길 구간",35.3,129.2,35.3,129.25,6),2000);
        check(mountain.imageKey.equals("none"),"A kind without a sample photo shows no photo");
        check(store.saveAutomatic("r1","경로","목적지",stretch(),3000)==first,"One clip per checkpoint per route");
        check(store.saveAutomatic("r2","다른 경로","목적지",stretch(),4000)!=first,"The same stretch on another route is a new record");
        List<String> ids=new ArrayList<>();ids.add("coast-1-2");ids.add("mountain-3-4");
        check(store.countForCheckpoints("r1",ids)==2&&store.latestForCheckpoints("r1",ids)==mountain,"Newest clip and count for a route's checkpoints");
        check(store.countForCheckpoints("r1",new ArrayList<String>())==0,"No checkpoints, no clips");
        ScenicCaptureStore reopened=new ScenicCaptureStore(new ScenicCaptureStore.Persistence(){public String read(){return saved[0];}public void write(String value){saved[0]=value;}});
        check(reopened.autoCaptureEnabled()&&reopened.countForCheckpoints("r1",ids)==2,"Setting and records survive a restart");
        // Records and the sharing flag from before checkpoints existed are still read.
        String legacy="SCENIC_CAPTURE_V1\n1\n"+b("id1")+"|"+b("r1")+"|"+b("경로")+"|"+b("목적지")+"|"+b("청사포")+"|"+b("coast")+"|5|1\n";
        ScenicCaptureStore old=new ScenicCaptureStore(new ScenicCaptureStore.Persistence(){public String read(){return legacy;}public void write(String value){}});
        check(old.sharingEnabled()&&!old.autoCaptureEnabled()&&old.recordsForRoute("r1").size()==1&&!old.recordsForRoute("r1").get(0).automatic(),"Version 1 data still loads");
        // A tampered row is dropped without losing the others.
        String broken="SCENIC_CAPTURE_V2\n01\n"+b("id1")+"|"+b("r1")+"|"+b("경로")+"|"+b("목적지")+"|"+b("구간")+"|"+b("none")+"|5|0||\n"
            +b("id2")+"|"+b("r1")+"|"+b("경로")+"|"+b("목적지")+"|"+b("구간")+"|"+b("none")+"|5|0|"+b("bad id")+"|"+b("coast")+"\n"
            +b("id3")+"|"+b("r1")+"|"+b("경로")+"|"+b("목적지")+"|"+b("구간")+"|"+b("coast")+"|5|0|"+b("coast-1-2")+"|"+b("coast")+"\n";
        ScenicCaptureStore tampered=new ScenicCaptureStore(new ScenicCaptureStore.Persistence(){public String read(){return broken;}public void write(String value){}});
        check(tampered.recordsForRoute("r1").size()==1&&tampered.recordsForRoute("r1").get(0).id.equals("id3"),"Only a manual 'none' image is invalid; a bad checkpoint row is dropped");
        System.out.println("CheckpointTest: "+checks+" checks passed");
    }
    private static String b(String value){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
