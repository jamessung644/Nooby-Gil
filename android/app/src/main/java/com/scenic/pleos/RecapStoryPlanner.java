package com.scenic.pleos;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Date and journey grouping only; visual selection is performed separately on the device. */
public final class RecapStoryPlanner {
    private RecapStoryPlanner(){}

    public static final class Moment {
        public final String id,place;
        public final long createdAt;
        public final boolean sample,capturedDateKnown;
        public final PersonalVideoStore.Record video;
        public final ScenicCaptureStore.Record capture;
        private final String journeyId;
        private Moment(PersonalVideoStore.Record video){
            this.video=video;capture=null;id="video:"+video.id;createdAt=video.createdAt;
            place=knownPlace(video.journeyTitle);journeyId=clean(video.journeyId);
            sample=false;capturedDateKnown=!"import".equals(video.dateSource);
        }
        private Moment(ScenicCaptureStore.Record capture){
            this.capture=capture;video=null;id="capture:"+capture.id;createdAt=capture.createdAt;
            place=knownPlace(capture.destination);journeyId=clean(capture.routeId);
            sample=true;capturedDateKnown=true;
        }
    }

    public static final class Story {
        public final String id,title,place,dateLabel,memoryLabel;
        public final long startedAt,endedAt;
        public final boolean sample,anniversary,currentMonth,capturedDateKnown;
        public final int yearsAgo;
        public final List<Moment> moments;
        private Story(String key,List<Moment> group,long now){
            ArrayList<Moment> ordered=new ArrayList<>(group);
            Collections.sort(ordered,Comparator.comparingLong((Moment m)->m.createdAt).thenComparing(m->m.id));
            moments=Collections.unmodifiableList(ordered);
            Moment first=ordered.get(0);startedAt=first.createdAt;endedAt=ordered.get(ordered.size()-1).createdAt;
            sample=first.sample;capturedDateKnown=first.capturedDateKnown;place=first.place;
            id=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
            Calendar today=calendar(now),day=calendar(startedAt);
            int yearDifference=today.get(Calendar.YEAR)-day.get(Calendar.YEAR);
            anniversary=!sample&&capturedDateKnown&&yearDifference>0
                &&today.get(Calendar.MONTH)==day.get(Calendar.MONTH)
                &&today.get(Calendar.DAY_OF_MONTH)==day.get(Calendar.DAY_OF_MONTH);
            yearsAgo=anniversary?yearDifference:0;
            currentMonth=!sample&&capturedDateKnown&&startedAt<=now
                &&today.get(Calendar.YEAR)==day.get(Calendar.YEAR)
                &&today.get(Calendar.MONTH)==day.get(Calendar.MONTH);
            dateLabel=format(today.get(Calendar.YEAR)==day.get(Calendar.YEAR)?"M월 d일":"yyyy년 M월 d일",startedAt)+(capturedDateKnown?"":" · 가져온 날짜");
            memoryLabel=sample?"샘플 리캡":anniversary?yearsAgo+"년 전 오늘":!capturedDateKnown?"추가한 영상":currentMonth?"이번 달":yearDifference==1?"지난해 "+format("M월",startedAt):format("yyyy년 M월",startedAt);
            title=place.isEmpty()?"다시 보고 싶은 하루":place+"의 기억";
        }
    }

    /**
     * Builds one story per local calendar day and journey, without editing stored records.
     * Real media, imported media with unknown capture dates, and sample captures stay separate.
     * Anniversaries precede recent journeys; samples always follow the user's real videos.
     */
    public static List<Story> plan(List<PersonalVideoStore.Record> videos,List<ScenicCaptureStore.Record> captures,long now){
        if(now<0)throw new IllegalArgumentException("Invalid current time");
        ArrayList<Moment> source=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(videos!=null)for(PersonalVideoStore.Record video:videos){
            if(video==null||video.createdAt<0)continue;Moment moment=new Moment(video);
            if(seen.add(moment.id))source.add(moment);
        }
        if(captures!=null)for(ScenicCaptureStore.Record capture:captures){
            if(capture==null||capture.createdAt<0)continue;Moment moment=new Moment(capture);
            if(seen.add(moment.id))source.add(moment);
        }
        // Input order must not decide story IDs, artwork order, or placement.
        Collections.sort(source,Comparator.comparingLong((Moment m)->m.createdAt).thenComparing(m->m.id));
        Map<String,List<Moment>> groups=new LinkedHashMap<>();
        for(Moment moment:source){
            String context=moment.journeyId.isEmpty()?moment.place:moment.journeyId;
            // Length-prefix the context so unusual user titles cannot alias another group.
            String key=(moment.sample?"sample":"video")+"|"+(moment.capturedDateKnown?"captured":"imported")
                +"|"+format("yyyy-MM-dd",moment.createdAt)+"|"+context.length()+":"+context;
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(moment);
        }
        ArrayList<Story> result=new ArrayList<>();
        for(Map.Entry<String,List<Moment>> group:groups.entrySet())result.add(new Story(group.getKey(),group.getValue(),now));
        Collections.sort(result,(a,b)->{
            if(a.sample!=b.sample)return a.sample?1:-1;
            if(a.anniversary!=b.anniversary)return a.anniversary?-1:1;
            int recent=Long.compare(b.endedAt,a.endedAt);return recent!=0?recent:a.id.compareTo(b.id);
        });
        return Collections.unmodifiableList(result);
    }

    private static Calendar calendar(long time){Calendar calendar=Calendar.getInstance();calendar.setTimeInMillis(time);return calendar;}
    private static String format(String pattern,long time){return new SimpleDateFormat(pattern,Locale.KOREA).format(new Date(time));}
    private static String clean(String value){return value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").trim();}
    private static String knownPlace(String value){String place=clean(value);return place.equals("선택한 여정")||place.equals("여행")||place.equals("내 드라이브")?"":place;}
}
