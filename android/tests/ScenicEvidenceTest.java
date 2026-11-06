package com.scenic.pleos;

import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONObject;

public final class ScenicEvidenceTest {
    private static int checks;
    private static final long NOW=LocalDate.of(2026,11,6).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Invalid { void run(); }
    private static void rejects(Invalid work){try{work.run();throw new AssertionError("Invalid observation was accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        ScenicEvidence.Observation saved=ScenicEvidence.create("haeundae","  해변로 교차로 ~ 공영주차장  ","2026-11-05","청사포 방향 · 오른쪽 바다","구름 많음\n정차 후 확인",NOW);
        check(saved.location.equals("해변로 교차로 ~ 공영주차장"),"Trim observation location");
        check(saved.note.equals("구름 많음 정차 후 확인"),"Preserve meaningful user note without control characters");
        check(saved.source().equals("사용자 직접 기록")&&!saved.isPlatformVerified(),"Local observation is never promoted to platform verification");
        ScenicEvidence.Observation restored=ScenicEvidence.restore(saved.toJson(),"haeundae");
        check(restored!=null&&restored.savedAt==NOW&&restored.date.equals(saved.date),"Observation survives storage with explicit date");
        check(restored.location.equals(saved.location)&&restored.direction.equals(saved.direction)&&restored.note.equals(saved.note),"Unicode evidence fields round-trip");
        check(ScenicEvidence.restore(saved.toJson(),"gwangalli")==null,"Evidence cannot appear on a different place");
        check(ScenicEvidence.restore("not JSON","haeundae")==null,"Corrupt local data fails closed");
        check(ScenicEvidence.restore(new JSONObject(saved.toJson()).put("verified",true).toString(),"haeundae")==null,"A forged verification field is rejected");
        check(ScenicEvidence.restore(new JSONObject(saved.toJson()).put("version","1").toString(),"haeundae")==null,"Version is not coerced from text");
        check(ScenicEvidence.restore(new JSONObject(saved.toJson()).put("savedAt",NOW+0.5).toString(),"haeundae")==null,"Fractional timestamps are rejected");
        check(ScenicEvidence.create("haeundae","해변로","2026-11-06","동쪽",null,NOW).note.isEmpty(),"Optional notes can be empty");
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-11-07","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-02-30","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-1-5","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae"," ","2026-11-05","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-11-05","","",NOW));
        rejects(()->ScenicEvidence.create("bad|place","해변로","2026-11-05","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로\u0001","2026-11-05","동쪽","",NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-11-05","동쪽",new String(new char[241]).replace('\0','가'),NOW));
        rejects(()->ScenicEvidence.create("haeundae","해변로","2026-11-05","동쪽","",0));
        System.out.println("ScenicEvidenceTest: "+checks+" checks passed");
    }
}
