package com.scenic.pleos;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure JVM coverage for the durable user-media selection contract. */
public final class PersonalVideoStoreTest {
    private static int checks;
    private static final class Memory implements PersonalVideoStore.Persistence {String value="";public String read(){return value;}public void write(String value){this.value=value;}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static String id(int index){return String.format(Locale.US,"%064x",index+1);}
    private static long date(int day){Calendar time=Calendar.getInstance();time.set(2026,Calendar.NOVEMBER,day,12,0,0);time.set(Calendar.MILLISECOND,0);return time.getTimeInMillis();}
    private static PersonalVideoStore.Record add(PersonalVideoStore store,int index,int day){return store.add(id(index),"content://media/external/video/media/"+index,"영상 "+index,"trip-seoul","서울 나들이",12000,date(day));}
    public static void main(String[] args){
        Memory memory=new Memory();PersonalVideoStore store=new PersonalVideoStore(memory);PersonalVideoStore.Record first=add(store,0,1);
        check(first.included&&first.startMs==0&&first.endMs==4000,"Import selects a bounded default interval");
        store.edit(first.id,1500,5500,false);PersonalVideoStore restored=new PersonalVideoStore(memory);PersonalVideoStore.Record saved=restored.all().get(0);
        check(saved.startMs==1500&&saved.endMs==5500&&!saved.included,"Trim and exclusion survive restart");
        check(saved.title.equals("영상 0")&&saved.journeyTitle.equals("서울 나들이")&&saved.journeyId.equals("trip-seoul"),"Unicode title and journey identity survive restart");
        store.add(first.id,"content://provider/moved-file","Renamed","other","Other journey",12000,date(9));
        check(store.all().size()==1&&store.all().get(0).uri.equals("content://provider/moved-file"),"Content duplicate refreshes URI without duplicating record");
        check(store.all().get(0).startMs==1500&&!store.all().get(0).included,"Reselecting duplicate preserves edits");
        for(long[] range:new long[][]{{-1,2000},{0,500},{1000,10001},{10000,13000}}){boolean rejected=false;try{store.edit(first.id,range[0],range[1],true);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Reject invalid trim "+range[0]+".."+range[1]);}
        for(int i=1;i<80;i++)add(store,i,1+i%20);
        check(store.all().size()==80,"History does not silently discard records after sixty imports");
        restored=new PersonalVideoStore(memory);check(restored.all().size()==80,"Full history survives serialization");
        check(store.selected("2026-11").size()==6,"Initial selection stays within six clips");
        boolean limit=false;try{store.edit(first.id,1500,5500,true);}catch(IllegalArgumentException expected){limit=true;}check(limit,"Explicit selection rejects overflow instead of silently dropping another clip");
        store.selectAcrossDays("2026-11");List<PersonalVideoStore.Record> selected=store.selected("2026-11");Set<Long> days=new HashSet<>();long previous=-1;for(PersonalVideoStore.Record record:selected){days.add(record.createdAt);check(record.createdAt>=previous,"Selected clips ordered chronologically");previous=record.createdAt;}
        check(days.size()==6,"Automatic selection distributes across days before repeating one day");
        long duration=selected.stream().mapToLong(record->record.endMs-record.startMs).sum();check(duration>=6000&&duration<=48000,"Selected trim durations remain bounded");
        store.remove(first.id);check(store.all().size()==79,"Explicit metadata removal keeps all other records");
        check(new PersonalVideoStore(memory).all().size()==79,"Explicit removal survives restart");
        memory.value+="corrupt|row\n";check(new PersonalVideoStore(memory).all().size()==79,"Malformed trailing row does not discard valid history");
        long captured=java.time.Instant.parse("2024-01-01T01:02:03Z").toEpochMilli();
        check(PersonalVideoStore.parseMediaDate("20240101T010203.000Z")==captured,"Compact Android capture date parsed in UTC");
        check(PersonalVideoStore.parseMediaDate("2024-01-01T10:02:03+09:00")==captured,"ISO offset capture date identifies the same instant");
        check(PersonalVideoStore.parseMediaDate("20240230T010203.000Z")==0&&PersonalVideoStore.parseMediaDate("20990101T000000.000Z")==0,"Impossible or future capture dates fall back to import date");
        Memory datedMemory=new Memory();PersonalVideoStore dated=new PersonalVideoStore(datedMemory);dated.add(id(200),"content://video/200","old holiday","old-trip","지난 여행",9000,captured,"media");
        PersonalVideoStore.Record datedRecord=new PersonalVideoStore(datedMemory).all().get(0);check(datedRecord.month().equals("2024-01")&&datedRecord.dateSource.equals("media"),"Original capture month and date provenance survive restart");
        datedMemory.value=datedMemory.value.replace("|media\n","\n");check(new PersonalVideoStore(datedMemory).all().get(0).dateSource.equals("record"),"Ten-field legacy records remain readable");
        System.out.println("PersonalVideoStore: "+checks+" checks passed");
    }
}
