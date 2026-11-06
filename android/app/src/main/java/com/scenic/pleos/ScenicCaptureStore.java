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
        public final long createdAt;
        public final boolean sharedPreview;
        Record(String id,String routeId,String routeName,String destination,String segmentName,String imageKey,long createdAt,boolean sharedPreview) {
            this.id=id;this.routeId=routeId;this.routeName=routeName;this.destination=destination;
            this.segmentName=segmentName;this.imageKey=imageKey;this.createdAt=createdAt;this.sharedPreview=sharedPreview;
        }
    }
    private static final int MAX_RECORDS=10000;
    private final Persistence persistence;
    private final ArrayList<Record> records=new ArrayList<>();
    private boolean sharingEnabled;

    public ScenicCaptureStore(Persistence persistence) {
        if(persistence==null)throw new IllegalArgumentException("Persistence is required");
        this.persistence=persistence;restore(persistence.read());
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
        StringBuilder data=new StringBuilder("SCENIC_CAPTURE_V1\n").append(sharingEnabled?"1":"0").append('\n');
        for(Record r:records)data.append(encode(r.id)).append('|').append(encode(r.routeId)).append('|').append(encode(r.routeName)).append('|')
            .append(encode(r.destination)).append('|').append(encode(r.segmentName)).append('|').append(encode(r.imageKey)).append('|')
            .append(r.createdAt).append('|').append(r.sharedPreview?"1":"0").append('\n');
        persistence.write(data.toString());
    }
    private void restore(String data) {
        if(data==null||data.length()>16*1024*1024)return;
        String[] lines=data.split("\n");
        if(lines.length<2||!"SCENIC_CAPTURE_V1".equals(lines[0]))return;
        sharingEnabled="1".equals(lines[1]);
        for(int i=2;i<lines.length&&records.size()<MAX_RECORDS;i++) {
            try {
                String[] fields=lines[i].split("\\|",-1);if(fields.length!=8)continue;
                String id=decode(fields[0]),routeId=decode(fields[1]),routeName=decode(fields[2]),destination=decode(fields[3]),segment=decode(fields[4]),image=decode(fields[5]);
                require(id);require(routeId);require(routeName);require(destination);require(segment);
                if(!"haeundae".equals(image)&&!"gwangalli".equals(image)&&!"coast".equals(image))continue;
                long time=Long.parseLong(fields[6]);if(time<0)continue;
                if(!"0".equals(fields[7])&&!"1".equals(fields[7]))continue;
                boolean duplicate=false;for(Record r:records)if(r.id.equals(id)){duplicate=true;break;}
                if(!duplicate)records.add(new Record(id,routeId,routeName,destination,segment,image,time,"1".equals(fields[7])));
            } catch(IllegalArgumentException ignored) { /* A bad row does not discard valid saved records. */ }
        }
    }
}
