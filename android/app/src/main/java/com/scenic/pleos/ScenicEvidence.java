package com.scenic.pleos;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONException;
import org.json.JSONObject;

/** A driver's local observation, never an assertion that SCENIC verified a road view. */
public final class ScenicEvidence {
    public static final String SOURCE="사용자 직접 기록";
    public static final class Observation {
        public final String placeId,location,date,direction,note;
        public final long savedAt;
        private Observation(String placeId,String location,String date,String direction,String note,long savedAt){
            this.placeId=placeId;this.location=location;this.date=date;this.direction=direction;this.note=note;this.savedAt=savedAt;
        }
        public boolean isPlatformVerified(){return false;}
        public String source(){return SOURCE;}
        public String toJson()throws JSONException {
            return new JSONObject().put("version",1).put("placeId",placeId).put("location",location).put("date",date)
                    .put("direction",direction).put("note",note).put("savedAt",savedAt).toString();
        }
    }
    public static Observation create(String placeId,String location,String date,String direction,String note,long savedAt){
        if(savedAt<=0)throw new IllegalArgumentException("기록 시각을 확인해 주세요.");
        String id=required(placeId,100,"장소"),where=required(location,160,"관찰한 위치·도로 구간"),when=required(date,10,"확인일"),heading=required(direction,160,"진행 방향·보이는 쪽");
        String memo=optional(note,240,"관찰 메모");
        if(!id.matches("[A-Za-z0-9_-]+")||!when.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("확인일은 YYYY-MM-DD 형식으로 입력해 주세요.");
        try {
            LocalDate observed=LocalDate.parse(when),today=Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault()).toLocalDate();
            if(observed.isAfter(today))throw new IllegalArgumentException("확인일은 오늘 또는 지난 날짜로 입력해 주세요.");
        } catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("올바른 확인 날짜를 입력해 주세요.");}
        return new Observation(id,where,when,heading,memo,savedAt);
    }
    public static Observation restore(String encoded,String expectedPlaceId){
        if(encoded==null||encoded.length()>5000)return null;
        try {
            JSONObject value=new JSONObject(encoded);
            if(value.length()!=7||!(value.get("version") instanceof Number)||((Number)value.get("version")).doubleValue()!=1||!(value.get("savedAt") instanceof Number))return null;
            for(String key:new String[]{"placeId","location","date","direction","note"})if(!(value.get(key) instanceof String))return null;
            Number raw=(Number)value.get("savedAt");long savedAt=raw.longValue();if(raw.doubleValue()!=savedAt)return null;
            Observation observation=create(value.getString("placeId"),value.getString("location"),value.getString("date"),value.getString("direction"),value.getString("note"),savedAt);
            return observation.placeId.equals(expectedPlaceId)?observation:null;
        } catch(JSONException|IllegalArgumentException invalid){return null;}
    }
    private static String required(String value,int maximum,String label){String result=optional(value,maximum,label);if(result.isEmpty())throw new IllegalArgumentException(label+"을 입력해 주세요.");return result;}
    private static String optional(String value,int maximum,String label){if(value==null)value="";String result=value.replaceAll("[\\r\\n\\t]+"," ");for(int i=0;i<result.length();i++)if(Character.isISOControl(result.charAt(i)))throw new IllegalArgumentException(label+"에 사용할 수 없는 문자가 있어요.");result=result.trim();if(result.length()>maximum)throw new IllegalArgumentException(label+"은 "+maximum+"자 이내로 입력해 주세요.");return result;}
    private ScenicEvidence(){}
}
