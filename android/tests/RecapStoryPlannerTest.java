package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public final class RecapStoryPlannerTest {
    public static void main(String[] args){
        TimeZone prior=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try{groupingAndDates();anniversariesUseCaptureDates();sampleSeparation();stableOrderingAndOriginals();localMidnight();}
        finally{TimeZone.setDefault(prior);}
        System.out.println("RecapStoryPlannerTest passed");
    }
    private static void groupingAndDates(){
        PersonalVideoStore store=store();long now=at(2026,11,15,12,0);
        PersonalVideoStore.Record morning=add(store,1,"gangneung","강릉",at(2026,11,14,9,0),"record");
        PersonalVideoStore.Record evening=add(store,2,"gangneung","강릉",at(2026,11,14,20,0),"record");
        add(store,3,"sokcho","속초",at(2026,11,14,16,0),"record");
        add(store,4,"gangneung","강릉",at(2026,11,15,9,0),"record");
        List<RecapStoryPlanner.Story> stories=RecapStoryPlanner.plan(store.all(),null,now);
        check(stories.size()==3,"A trip on separate days or separate journeys must not merge");
        RecapStoryPlanner.Story group=find(stories,morning.id);
        check(group.moments.size()==2&&group.moments.get(1).video==evening,"All video moments retained in capture order");
        check(group.place.equals("강릉")&&group.dateLabel.equals("11월 14일"),"Actual place and local date shown");
        check(group.currentMonth&&group.memoryLabel.equals("이번 달"),"Current month story label");
    }
    private static void anniversariesUseCaptureDates(){
        PersonalVideoStore store=store();long now=at(2026,11,15,12,0);
        PersonalVideoStore.Record anniversary=add(store,1,"old","제주",at(2023,11,15,15,0),"media");
        PersonalVideoStore.Record yesterday=add(store,2,"recent","부산",at(2026,11,14,15,0),"record");
        PersonalVideoStore.Record imported=add(store,3,"old","제주",at(2023,11,15,15,0),"import");
        List<RecapStoryPlanner.Story> stories=RecapStoryPlanner.plan(store.all(),null,now);
        check(stories.get(0).moments.get(0).video==anniversary,"Anniversary featured before recent journey");
        check(stories.get(0).yearsAgo==3&&stories.get(0).memoryLabel.equals("3년 전 오늘"),"Correct elapsed years");
        check(!find(stories,yesterday.id).anniversary,"Other dates are not anniversaries");
        RecapStoryPlanner.Story unknown=find(stories,imported.id);
        check(!unknown.anniversary&&!unknown.capturedDateKnown&&unknown.dateLabel.endsWith("가져온 날짜"),"Import time never presented as an actual capture anniversary");
        check(stories.size()==3,"Unknown capture dates not combined with known ones");
        PersonalVideoStore leap=store();add(leap,4,"leap","가평",at(2024,2,29,8,0),"record");
        check(!RecapStoryPlanner.plan(leap.all(),null,at(2025,2,28,12,0)).get(0).anniversary,"Leap day must not invent Feb 28 anniversary");
    }
    private static void sampleSeparation(){
        PersonalVideoStore store=store();long now=at(2026,11,15,12,0);
        PersonalVideoStore.Record video=add(store,1,"same","강릉",now-1000,"record");
        ScenicCaptureStore.Record sample=new ScenicCaptureStore.Record("sample","same","강릉 코스","강릉","바닷길","coast",now,false);
        List<RecapStoryPlanner.Story> stories=RecapStoryPlanner.plan(store.all(),Arrays.asList(sample),now);
        check(stories.size()==2&&!stories.get(0).sample&&stories.get(1).sample,"User media always separated from newer samples");
        check(stories.get(0).moments.get(0).video==video&&stories.get(1).memoryLabel.equals("샘플 리캡"),"Samples stay visibly labeled");
        check(!stories.get(1).anniversary&&!stories.get(1).currentMonth,"Samples do not masquerade as personal memories");
    }
    private static void stableOrderingAndOriginals(){
        PersonalVideoStore store=store();long now=at(2026,11,15,12,0);
        PersonalVideoStore.Record first=add(store,1,"trip","선택한 여정",now-2000,"record");
        PersonalVideoStore.Record second=add(store,2,"trip","선택한 여정",now-1000,"record");
        List<PersonalVideoStore.Record> source=new ArrayList<>(Arrays.asList(second,null,first,first));
        List<RecapStoryPlanner.Story> a=RecapStoryPlanner.plan(source,null,now);Collections.reverse(source);
        List<RecapStoryPlanner.Story> b=RecapStoryPlanner.plan(source,null,now);
        check(a.size()==1&&a.get(0).moments.size()==2,"Duplicate source IDs ignored");
        check(a.get(0).id.equals(b.get(0).id)&&a.get(0).moments.get(0).video==first,"Stable story ID and deterministic order");
        check(a.get(0).place.isEmpty()&&a.get(0).title.equals("다시 보고 싶은 하루"),"Unknown place not inferred from filenames or generic journey title");
        check(first.startMs==0&&second.endMs==4000,"Planner never changes original clip metadata");
        try{a.clear();throw new AssertionError("Mutable result");}catch(UnsupportedOperationException expected){}
        try{a.get(0).moments.clear();throw new AssertionError("Mutable moments");}catch(UnsupportedOperationException expected){}
        check(RecapStoryPlanner.plan(null,null,now).isEmpty(),"Empty source supported");
    }
    private static void localMidnight(){
        PersonalVideoStore store=store();long now=at(2026,11,15,12,0);
        add(store,1,"trip","속초",at(2026,11,14,23,59),"record");
        add(store,2,"trip","속초",at(2026,11,15,0,1),"record");
        check(RecapStoryPlanner.plan(store.all(),null,now).size()==2,"Local midnight splits story days");
        PersonalVideoStore future=store();add(future,3,"trip","속초",at(2026,11,16,0,1),"record");
        check(!RecapStoryPlanner.plan(future.all(),null,now).get(0).currentMonth,"Future timestamp not described as an existing memory this month");
    }
    private static PersonalVideoStore store(){return new PersonalVideoStore(new PersonalVideoStore.Persistence(){public String read(){return "";}public void write(String value){}});}
    private static PersonalVideoStore.Record add(PersonalVideoStore store,int id,String journey,String place,long when,String dateSource){return store.add(String.format("%064x",id),"content://videos/"+id,"clip-"+id,journey,place,20000,when,dateSource);}
    private static RecapStoryPlanner.Story find(List<RecapStoryPlanner.Story> stories,String videoId){for(RecapStoryPlanner.Story story:stories)for(RecapStoryPlanner.Moment moment:story.moments)if(moment.video!=null&&moment.video.id.equals(videoId))return story;throw new AssertionError("Missing story");}
    private static long at(int year,int month,int day,int hour,int minute){Calendar calendar=Calendar.getInstance();calendar.clear();calendar.set(year,month-1,day,hour,minute);return calendar.getTimeInMillis();}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
