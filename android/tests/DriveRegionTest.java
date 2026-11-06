package com.scenic.pleos;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

public final class DriveRegionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Set<String> ids=new HashSet<>();check(DriveRegion.ALL.size()==8,"Eight explicit exploration areas");
        for(DriveRegion region:DriveRegion.ALL){
            check(ids.add(region.id)&&region.id.startsWith("region-"),"Region ids cannot be mistaken for tourist POI ids");
            check(CourseEngine.inKorea(region.lat,region.lon),"Domestic exploration center");
            check(DriveRegion.byId(region.id)==region&&region.matchesFilter("전체"),"Stable region lookup and all filter");
            check(DestinationRoutePlan.validTheme(region.scene)&&!region.foodHints.isEmpty()&&!region.filterTags.isEmpty(),"Region carries scenery, food and discovery filters");
            check(new URI(region.officialSourceUrl).getScheme().equals("https")&&new URI(region.foodSourceUrl).getScheme().equals("https"),"Official evidence uses HTTPS");
            DestinationPlace center=region.toDestination();check(center.id.equals(region.id)&&center.lat==region.lat&&center.address.contains("탐색 기준점")&&center.address.contains("사진에서 선택"),"Center cannot claim to be a chosen photograph destination");
            check(!region.matchesFilter("unknown"),"Unknown filter returns no invented region matches");
        }
        check(DriveRegion.byId("region-gapyeong").matchesFilter("산·강")&&!DriveRegion.byId("region-gapyeong").matchesFilter("바다"),"Inland region is not presented as coast");
        check(DriveRegion.byId("region-busan").matchesFilter("도시")&&DriveRegion.byId("region-busan").matchesFilter("바다"),"Mixed city/coast theme remains discoverable");
        check(DriveRegion.byId("tour-1234")==null&&DriveRegion.byId(null)==null,"Unknown tourist POI is not silently treated as a region");
        check(DriveRegion.foodHints(DriveRegion.ALL.get(0).toDestination()).length==2&&DriveRegion.foodHints(null).length==0,"Food hints use only an explicitly selected catalog region");
        check(DriveRegion.foodHints(DestinationPlace.of("direct-search","직접 검색 지역","",37.5,127.0)).length==0,"No invented food specialities for an unknown search region");
        try{DriveRegion.ALL.clear();throw new AssertionError("Mutable regions");}catch(UnsupportedOperationException expected){checks++;}
        try{DriveRegion.ALL.get(0).foodHints.clear();throw new AssertionError("Mutable foods");}catch(UnsupportedOperationException expected){checks++;}
        System.out.println("DriveRegionTest: "+checks+" checks passed");
    }
}
