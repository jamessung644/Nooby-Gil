package com.scenic.pleos;

import org.json.JSONObject;

/** Explicit local observations, never a live occupancy feed or an inferred review score. */
public final class ParkingEvidenceStore {
    public interface Persistence {String read();void write(String data);}
    public static final long FRESH_MILLIS=30L*24*60*60*1000;
    public static final class Evidence {
        public final String status,source,note;public final long checkedAt;
        Evidence(String status,String source,String note,long time){this.status=status;this.source=source;this.note=note;checkedAt=time;}
        public boolean recent(long now){return checkedAt<=now&&now-checkedAt<=FRESH_MILLIS;}
        public String decision(long now){return recent(now)?status:"unknown";}
    }
    private final Persistence persistence;private JSONObject entries;
    public ParkingEvidenceStore(Persistence persistence){this.persistence=persistence;try{entries=new JSONObject(persistence.read());}catch(Exception empty){entries=new JSONObject();}}
    public Evidence get(String id){try{JSONObject o=entries.getJSONObject(id);return new Evidence(o.getString("status"),o.getString("source"),o.getString("note"),o.getLong("checkedAt"));}catch(Exception absent){return null;}}
    public void save(String id,String status,String source,String note,long now){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||!status.matches("available|unavailable|unknown")||source==null||source.trim().isEmpty()||source.length()>80||note==null||note.trim().isEmpty()||note.length()>300||now<=0)throw new IllegalArgumentException("Parking evidence and time required");
        try{entries.put(id,new JSONObject().put("status",status).put("source",source.trim()).put("note",note.trim()).put("checkedAt",now));persistence.write(entries.toString());}catch(Exception failed){throw new IllegalStateException(failed);}
    }
    public void remove(String id){entries.remove(id);persistence.write(entries.toString());}
}
