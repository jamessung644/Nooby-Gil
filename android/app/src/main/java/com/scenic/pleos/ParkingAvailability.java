package com.scenic.pleos;

import java.net.URI;
import java.time.Instant;
import org.json.JSONException;
import org.json.JSONObject;

/** Verified inventory, separate from a parking place's name or a user's parking note. */
public final class ParkingAvailability {
    public static final long MAX_AGE_MILLIS=10*60*1000L;
    public static final long SNAPSHOT_MAX_AGE_MILLIS=90*1000L;
    private static final long FUTURE_TOLERANCE_MILLIS=60*1000L;
    public final String status,reason,source,sourceUrl,providerId,timeBasis;
    public final Integer availableSpaces,totalSpaces;
    public final long observedAtMillis,fetchedAtMillis,retrievedAtMillis;
    private final long receivedAtMillis;

    private ParkingAvailability(String status,String reason,String source,String sourceUrl,String providerId,Integer free,Integer total,long observed,long fetched,long received,String basis,long retrieved){
        this.status=status;this.reason=reason;this.source=source;this.sourceUrl=sourceUrl;this.providerId=providerId;
        availableSpaces=free;totalSpaces=total;observedAtMillis=observed;fetchedAtMillis=fetched;receivedAtMillis=received;timeBasis=basis;retrievedAtMillis=retrieved;
    }
    public static ParkingAvailability unknown(long now){return new ParkingAvailability("unknown","unsupported","","",null,null,null,0,now,now,"observed",0);}

    /** Reads a place row. Pass a monotonic receipt clock (Android elapsedRealtime), not wall time. */
    public static ParkingAvailability read(JSONObject row,long now)throws JSONException{
        String outer=text(row,"parkingStatus",16,false);
        if(!validStatus(outer))throw invalid();
        if(!row.has("parking")||row.isNull("parking")){if(!"unknown".equals(outer))throw invalid();return unknown(now);}
        JSONObject value=row.getJSONObject("parking");
        String status=text(value,"status",16,false),reason=text(value,"reason",24,false);
        if(!status.equals(outer)||!validStatus(status)||!("live".equals(reason)||"stale".equals(reason)||"unmatched".equals(reason)||"unavailable".equals(reason)||"unsupported".equals(reason)))throw invalid();
        Integer free=count(value,"availableSpaces"),total=count(value,"totalSpaces");
        if(free!=null&&total!=null&&free>total)throw invalid();
        String source=text(value,"source",120,true),url=text(value,"sourceUrl",500,true),id=value.isNull("providerId")?null:text(value,"providerId",120,false);
        if(!url.isEmpty())try{URI uri=new URI(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw invalid();}catch(Exception bad){throw invalid();}
        long observed=value.isNull("observedAt")?0:timestamp(value,"observedAt"),fetched=timestamp(value,"fetchedAt");
        String basis=value.has("timeBasis")?text(value,"timeBasis",16,false):"observed";
        if(!"observed".equals(basis)&&!"retrieved".equals(basis))throw invalid();
        long retrieved=!value.has("retrievedAt")||value.isNull("retrievedAt")?0:timestamp(value,"retrievedAt");
        if("retrieved".equals(basis)&&(observed!=0||"live".equals(reason)&&retrieved==0))throw invalid();
        if(!"unknown".equals(status)){
            if(!"live".equals(reason)||free==null||("observed".equals(basis)?observed==0:retrieved==0)||source.isEmpty()||url.isEmpty()||id==null
                    ||("available".equals(status)?free<=0:free!=0))throw invalid();
        }else if("live".equals(reason))throw invalid();
        return new ParkingAvailability(status,reason,source,url,id,free,total,observed,fetched,now,basis,retrieved);
    }
    /** Use the same monotonic clock passed to read; server/device clock offsets do not affect age. */
    public boolean isFresh(long now){
        long stamp=isSnapshot()?retrievedAtMillis:observedAtMillis,maxAge=isSnapshot()?SNAPSHOT_MAX_AGE_MILLIS:MAX_AGE_MILLIS;
        if(!"live".equals(reason)||stamp<=0)return false;
        long serverAge=fetchedAtMillis-stamp,elapsed=now-receivedAtMillis;
        return serverAge>=(isSnapshot()?0:-FUTURE_TOLERANCE_MILLIS)&&serverAge<=maxAge&&elapsed>=0
                &&elapsed<=maxAge-Math.max(0,serverAge);
    }
    public boolean isSnapshot(){return "retrieved".equals(timeBasis);}
    public boolean hasVerifiedObservation(long now){return !isSnapshot()&&isFresh(now);}
    public String currentStatus(long now){return isFresh(now)?status:"unknown";}
    public boolean isAvailable(long now){return "available".equals(currentStatus(now));}
    public boolean isFull(long now){return "full".equals(currentStatus(now));}
    /** Still available but about to fill: at most 5 free spaces, or at most 5% of a known total. */
    public boolean isLow(long now){
        if(!isAvailable(now)||availableSpaces==null)return false;
        return availableSpaces<=LOW_SPACES||totalSpaces!=null&&totalSpaces>0&&availableSpaces*100L<=totalSpaces*(long)LOW_PERCENT;
    }
    public static final int LOW_SPACES=5,LOW_PERCENT=5;
    public int priority(long now){return isAvailable(now)?0:isFull(now)?2:1;}
    public String unavailableReason(long now){
        if("stale".equals(reason)||"live".equals(reason)&&!isFresh(now))return isSnapshot()?"조회한 현황이 오래되어 빈자리를 다시 확인해야 해요.":"제공 시각이 오래되어 빈자리를 확인할 수 없어요.";
        if("unavailable".equals(reason))return "실시간 제공처에 연결하지 못했어요.";
        if("unmatched".equals(reason))return "이 주차장과 일치하는 실시간 정보를 확인하지 못했어요.";
        return "실시간 빈자리 정보가 제공되지 않는 주차장이에요.";
    }
    private static boolean validStatus(String status){return "available".equals(status)||"full".equals(status)||"unknown".equals(status);}
    private static Integer count(JSONObject row,String key)throws JSONException{
        if(row.isNull(key))return null;Object raw=row.get(key);if(!(raw instanceof Number))throw invalid();
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<0||value>100000||value!=Math.floor(value))throw invalid();return (int)value;
    }
    private static long timestamp(JSONObject row,String key)throws JSONException{
        try{long value=Instant.parse(text(row,key,40,false)).toEpochMilli();if(value<=0)throw invalid();return value;}catch(Exception bad){throw invalid();}
    }
    private static String text(JSONObject row,String key,int max,boolean empty)throws JSONException{return DestinationPlace.text(row,key,max,empty);}
    private static JSONException invalid(){return new JSONException("Invalid parking inventory");}
}
