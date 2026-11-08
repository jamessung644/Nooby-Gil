package com.scenic.pleos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Local, simulated captures only. This class never accesses a camera or network. */
public final class ScenicCaptureStore {
    public interface Persistence { String read(); void write(String value); }
    public static final class Record {
        public final String id, routeId, routeName, destination, segmentName, imageKey;
        /** Empty for a capture the user started by hand; otherwise the route checkpoint that triggered it. */
        public final String checkpointId, checkpointKind;
        public final long createdAt;
        public final boolean sharedPreview;
        Record(String id,String routeId,String routeName,String destination,String segmentName,String imageKey,long createdAt,boolean sharedPreview) {
            this(id,routeId,routeName,destination,segmentName,imageKey,createdAt,sharedPreview,"","");
        }
        Record(String id,String routeId,String routeName,String destination,String segmentName,String imageKey,long createdAt,boolean sharedPreview,String checkpointId,String checkpointKind) {
            this.id=id;this.routeId=routeId;this.routeName=routeName;this.destination=destination;
            this.segmentName=segmentName;this.imageKey=imageKey;this.createdAt=createdAt;this.sharedPreview=sharedPreview;
            this.checkpointId=checkpointId;this.checkpointKind=checkpointKind;
        }
        public boolean automatic(){return !checkpointId.isEmpty();}
    }
    private static final int MAX_RECORDS=10000;
    private final Persistence persistence;
    private final ArrayList<Record> records=new ArrayList<>();
    private boolean sharingEnabled;

    public ScenicCaptureStore(Persistence persistence) {
        if(persistence==null)throw new IllegalArgumentException("Persistence is required");
        this.persistence=persistence;restore(persistence.read());
    }
    private boolean autoCapture;
    /** Recording at route checkpoints is off until the user turns it on. */
    public boolean autoCaptureEnabled(){return autoCapture;}
    public void setAutoCaptureEnabled(boolean enabled){autoCapture=enabled;persist();}
    /** The newest clip recorded for any of these checkpoint ids on this route, or null. */
    public Record latestForCheckpoints(String routeId,java.util.Collection<String> checkpointIds) {
        for(Record r:records)if(r.automatic()&&r.routeId.equals(routeId)&&checkpointIds.contains(r.checkpointId))return r;
        return null;
    }
    public int countForCheckpoints(String routeId,java.util.Collection<String> checkpointIds) {
        int count=0;for(Record r:records)if(r.automatic()&&r.routeId.equals(routeId)&&checkpointIds.contains(r.checkpointId))count++;return count;
    }
    /**
     * A clip started by a checkpoint, not by a tap. It needs no screen interaction, so it may be saved
     * while driving, but only when the user has turned automatic recording on. One clip per checkpoint per route.
     * Returns the newly saved record, or null when this route's checkpoint was already recorded.
     */
    public Record saveAutomatic(String routeId,String routeName,String destination,RouteCheckpoint checkpoint,long now) {
        if(!autoCapture)throw new IllegalStateException("Automatic recording is off");
        require(routeId);require(routeName);require(destination);if(checkpoint==null||now<0)throw new IllegalArgumentException("Invalid checkpoint capture");
        for(Record r:records)if(r.automatic()&&r.routeId.equals(routeId)&&r.checkpointId.equals(checkpoint.id))return null;
        if(records.size()>=MAX_RECORDS)throw new IllegalStateException("Capture limit reached");
        String image="coast".equals(checkpoint.kind)?"coast":"none";
        Record saved=new Record(UUID.randomUUID().toString(),routeId,routeName,destination,checkpoint.name,image,now,sharingEnabled,checkpoint.id,checkpoint.kind);
        records.add(0,saved);persist();return saved;
    }
    public boolean sharingEnabled(){return sharingEnabled;}
    public void setSharingEnabled(boolean enabled){sharingEnabled=enabled;persist();}
    public List<Record> recordsForRoute(String routeId) {
        ArrayList<Record> result=new ArrayList<>();
        for(Record r:records)if(routeId==null||routeId.isEmpty()||routeId.equals(r.routeId))result.add(r);
        return Collections.unmodifiableList(result);
    }
    public Record draft(String routeId,String routeName,String destination,String segmentName,long now) {
        require(routeId);require(routeName);require(destination);require(segmentName);
        if(now<0)throw new IllegalArgumentException("Invalid capture time");
        return new Record(UUID.randomUUID().toString(),routeId,routeName,destination,segmentName,
            imageKeyFor(segmentName,destination),now,sharingEnabled);
    }
    /** The interaction guard is checked again when a preview is saved. */
    public Record save(Record draft,boolean interactionAllowed) {
        if(!interactionAllowed)throw new IllegalStateException("Captures may only be added while stopped");
        if(draft==null)throw new IllegalArgumentException("A draft is required");
        for(Record record:records)if(record.id.equals(draft.id))return record;
        Record saved=new Record(draft.id,draft.routeId,draft.routeName,draft.destination,draft.segmentName,draft.imageKey,draft.createdAt,sharingEnabled);
        if(records.size()>=MAX_RECORDS)throw new IllegalStateException("기록이 10,000개에 도달했어요. 필요한 기록을 보관한 뒤 일부를 직접 삭제해 주세요.");
        records.add(0,saved);
        persist();return saved;
    }
    /** Removing a local sample never removes a user's original video. */
    public boolean remove(String id){for(int i=0;i<records.size();i++)if(records.get(i).id.equals(id)){records.remove(i);persist();return true;}return false;}
    public static String imageKeyFor(String segmentName,String destination) {
        String segment=segmentName==null?"":segmentName;
        if(segment.contains("광안")||segment.contains("민락")||segment.contains("수영"))return "gwangalli";
        if(segment.contains("청사포")||segment.contains("달맞이")||segment.contains("송정"))return "coast";
        if(segment.contains("해운대")||segment.contains("동백"))return "haeundae";
        String target=destination==null?"":destination;
        if(target.contains("광안"))return "gwangalli";
        if(target.contains("청사포")||target.contains("송정"))return "coast";
        return "haeundae";
    }
    private static void require(String value){if(value==null||value.trim().isEmpty())throw new IllegalArgumentException("Capture context is required");}
    private static String encode(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String decode(String value){return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}
    private void persist() {
        StringBuilder data=new StringBuilder("SCENIC_CAPTURE_V2\n").append(sharingEnabled?"1":"0").append(autoCapture?"1":"0").append('\n');
        for(Record r:records)data.append(encode(r.id)).append('|').append(encode(r.routeId)).append('|').append(encode(r.routeName)).append('|')
            .append(encode(r.destination)).append('|').append(encode(r.segmentName)).append('|').append(encode(r.imageKey)).append('|')
            .append(r.createdAt).append('|').append(r.sharedPreview?"1":"0").append('|').append(encode(r.checkpointId)).append('|').append(encode(r.checkpointKind)).append('\n');
        persistence.write(data.toString());
    }
    private void restore(String data) {
        if(data==null||data.length()>16*1024*1024)return;
        String[] lines=data.split("\n");
        boolean v2="SCENIC_CAPTURE_V2".equals(lines[0]);
        if(lines.length<2||!(v2||"SCENIC_CAPTURE_V1".equals(lines[0])))return;
        sharingEnabled=lines[1].startsWith("1");autoCapture=v2&&lines[1].length()>1&&lines[1].charAt(1)=='1';
        for(int i=2;i<lines.length&&records.size()<MAX_RECORDS;i++) {
            try {
                String[] fields=lines[i].split("\\|",-1);if(fields.length!=(v2?10:8))continue;
                String checkpointId=v2?decode(fields[8]):"",checkpointKind=v2?decode(fields[9]):"";
                if(!checkpointId.isEmpty()&&(!checkpointId.matches("[A-Za-z0-9_-]{1,100}")||!RouteCheckpoint.validKind(checkpointKind)))continue;
                String id=decode(fields[0]),routeId=decode(fields[1]),routeName=decode(fields[2]),destination=decode(fields[3]),segment=decode(fields[4]),image=decode(fields[5]);
                require(id);require(routeId);require(routeName);require(destination);require(segment);
                if(!"haeundae".equals(image)&&!"gwangalli".equals(image)&&!"coast".equals(image)&&!("none".equals(image)&&!checkpointId.isEmpty()))continue;
                long time=Long.parseLong(fields[6]);if(time<0)continue;
                if(!"0".equals(fields[7])&&!"1".equals(fields[7]))continue;
                boolean duplicate=false;for(Record r:records)if(r.id.equals(id)){duplicate=true;break;}
                if(!duplicate)records.add(new Record(id,routeId,routeName,destination,segment,image,time,"1".equals(fields[7]),checkpointId,checkpointKind));
            } catch(IllegalArgumentException ignored) { /* A bad row does not discard valid saved records. */ }
        }
    }
}
