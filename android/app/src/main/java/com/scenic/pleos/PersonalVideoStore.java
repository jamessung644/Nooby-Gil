package com.scenic.pleos;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Durable metadata only; original user videos are never copied, changed or deleted. */
public final class PersonalVideoStore {
    public interface Persistence { String read(); void write(String value); }
    public static final int MAX_SELECTED=6;
    public static final long MAX_CLIP_MS=8000;
    public static final class Record {
        public final String id,uri,title,journeyId,journeyTitle,dateSource;
        public final long durationMs,createdAt,startMs,endMs;
        public final boolean included;
        Record(String id,String uri,String title,String journeyId,String journeyTitle,long durationMs,long createdAt,long startMs,long endMs,boolean included,String dateSource){
            this.id=id;this.uri=uri;this.title=title;this.journeyId=journeyId;this.journeyTitle=journeyTitle;this.durationMs=durationMs;this.createdAt=createdAt;this.startMs=startMs;this.endMs=endMs;this.included=included;this.dateSource=dateSource;
        }
        public String month(){return new SimpleDateFormat("yyyy-MM",Locale.US).format(new Date(createdAt));}
    }
    private final Persistence persistence;
    private final ArrayList<Record> records=new ArrayList<>();
    public PersonalVideoStore(Persistence persistence){this.persistence=persistence;restore(persistence.read());}
    public List<Record> all(){return Collections.unmodifiableList(new ArrayList<>(records));}
    public List<Record> forMonth(String month){ArrayList<Record> result=new ArrayList<>();for(Record record:records)if(record.month().equals(month))result.add(record);Collections.sort(result,Comparator.comparingLong(r->r.createdAt));return result;}
    public List<Record> selected(String month){ArrayList<Record> result=new ArrayList<>();for(Record record:forMonth(month))if(record.included&&result.size()<MAX_SELECTED)result.add(record);return result;}
    public Record add(String fingerprint,String uri,String title,String journeyId,String journeyTitle,long durationMs,long createdAt){return add(fingerprint,uri,title,journeyId,journeyTitle,durationMs,createdAt,"record");}
    public Record add(String fingerprint,String uri,String title,String journeyId,String journeyTitle,long durationMs,long createdAt,String dateSource){
        if(!fingerprint.matches("[a-f0-9]{64}")||uri==null||!uri.startsWith("content://")||durationMs<1000||durationMs>3600000||createdAt<0)throw new IllegalArgumentException("Invalid personal video");
        for(int i=0;i<records.size();i++){Record record=records.get(i);if(record.id.equals(fingerprint)||record.uri.equals(uri)){
            // A reselected duplicate may provide a new durable document URI after a file move.
            if(record.id.equals(fingerprint)&&!record.uri.equals(uri)){record=new Record(record.id,uri,record.title,record.journeyId,record.journeyTitle,record.durationMs,record.createdAt,record.startMs,record.endMs,record.included,record.dateSource);records.set(i,record);persist();}return record;
        }}
        String month=new SimpleDateFormat("yyyy-MM",Locale.US).format(new Date(createdAt));
        Record record=new Record(fingerprint,uri,clean(title,120),clean(journeyId,180),clean(journeyTitle,180),durationMs,createdAt,0,Math.min(4000,durationMs),selected(month).size()<MAX_SELECTED,validDateSource(dateSource));
        records.add(record);persist();return record;
    }
    public void edit(String id,long startMs,long endMs,boolean included){
        for(int i=0;i<records.size();i++){Record old=records.get(i);if(!old.id.equals(id))continue;
            if(startMs<0||endMs>old.durationMs||endMs-startMs<1000||endMs-startMs>MAX_CLIP_MS)throw new IllegalArgumentException("Choose a clip between one and eight seconds");
            if(included&&!old.included&&selected(old.month()).size()>=MAX_SELECTED)throw new IllegalArgumentException("At most six clips can be selected");
            records.set(i,new Record(old.id,old.uri,old.title,old.journeyId,old.journeyTitle,old.durationMs,old.createdAt,startMs,endMs,included,old.dateSource));persist();return;
        }
    }
    public void remove(String id){records.removeIf(record->record.id.equals(id));persist();}
    /** One per day first, then a second pass. Ordering remains chronological. */
    public void selectAcrossDays(String month){
        Map<String,List<Record>> days=new LinkedHashMap<>();SimpleDateFormat day=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        for(Record record:forMonth(month))days.computeIfAbsent(day.format(new Date(record.createdAt)),key->new ArrayList<>()).add(record);
        ArrayList<String> chosen=new ArrayList<>();for(int round=0;chosen.size()<MAX_SELECTED;round++){boolean found=false;for(List<Record> group:days.values())if(round<group.size()){found=true;chosen.add(group.get(round).id);if(chosen.size()==MAX_SELECTED)break;}if(!found)break;}
        for(int i=0;i<records.size();i++){Record r=records.get(i);if(r.month().equals(month))records.set(i,new Record(r.id,r.uri,r.title,r.journeyId,r.journeyTitle,r.durationMs,r.createdAt,r.startMs,r.endMs,chosen.contains(r.id),r.dateSource));}persist();
    }
    private void persist(){StringBuilder value=new StringBuilder("SCENIC_PERSONAL_VIDEO_V1\n");for(Record r:records)value.append(r.id).append('|').append(enc(r.uri)).append('|').append(enc(r.title)).append('|').append(enc(r.journeyId)).append('|').append(enc(r.journeyTitle)).append('|').append(r.durationMs).append('|').append(r.createdAt).append('|').append(r.startMs).append('|').append(r.endMs).append('|').append(r.included?'1':'0').append('|').append(r.dateSource).append('\n');persistence.write(value.toString());}
    private void restore(String value){
        if(value==null||!value.startsWith("SCENIC_PERSONAL_VIDEO_V1\n"))return;
        for(String line:value.split("\n")){
            String[] fields=line.split("\\|",-1);if(fields.length!=10&&fields.length!=11)continue;
            try{
                String id=fields[0],uri=dec(fields[1]);long duration=Long.parseLong(fields[5]),created=Long.parseLong(fields[6]),start=Long.parseLong(fields[7]),end=Long.parseLong(fields[8]);
                if(!id.matches("[a-f0-9]{64}")||!uri.startsWith("content://")||duration<1000||duration>3600000||created<0||start<0||end>duration||end-start<1000||end-start>MAX_CLIP_MS||(!"0".equals(fields[9])&&!"1".equals(fields[9])))continue;
                boolean duplicate=false;for(Record record:records)if(record.id.equals(id)||record.uri.equals(uri)){duplicate=true;break;}
                if(!duplicate)records.add(new Record(id,uri,clean(dec(fields[2]),120),clean(dec(fields[3]),180),clean(dec(fields[4]),180),duration,created,start,end,"1".equals(fields[9]),validDateSource(fields.length==11?fields[10]:"record")));
            }catch(IllegalArgumentException ignored){}
        }
    }
    public static long parseMediaDate(String value){
        if(value==null||value.length()>64)return 0;
        String[] formats={"yyyyMMdd'T'HHmmss.SSS'Z'","yyyyMMdd'T'HHmmss'Z'","yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for(String format:formats){SimpleDateFormat parser=new SimpleDateFormat(format,Locale.US);parser.setLenient(false);parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));java.text.ParsePosition position=new java.text.ParsePosition(0);Date date=parser.parse(value,position);if(date!=null&&position.getIndex()==value.length()&&date.getTime()>=946684800000L&&date.getTime()<=System.currentTimeMillis()+172800000L)return date.getTime();}return 0;
    }
    private static String validDateSource(String value){return "media".equals(value)?"media":"import".equals(value)?"import":"record";}
    private static String clean(String value,int max){String result=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").trim();return result.length()>max?result.substring(0,max):result;}
    private static String enc(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String dec(String value){return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}
}
