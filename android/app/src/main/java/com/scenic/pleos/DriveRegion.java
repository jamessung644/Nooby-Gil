package com.scenic.pleos;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;

/** Editorial exploration areas, not confirmed destinations or photograph camera positions. */
public final class DriveRegion {
    public final String id,name,scene,scenery,driveTheme,centerLabel,officialSource,officialSourceUrl,foodSourceUrl;
    public final double lat,lon;
    public final List<String> foodHints,filterTags;

    private DriveRegion(String id,String name,double lat,double lon,String scene,String scenery,String driveTheme,
                        String centerLabel,String[] foods,String[] filters,String source,String sourceUrl,String foodSourceUrl){
        this.id=id;this.name=name;this.lat=lat;this.lon=lon;this.scene=scene;this.scenery=scenery;this.driveTheme=driveTheme;
        this.centerLabel=centerLabel;foodHints=Collections.unmodifiableList(Arrays.asList(foods.clone()));
        filterTags=Collections.unmodifiableList(Arrays.asList(filters.clone()));officialSource=source;officialSourceUrl=sourceUrl;this.foodSourceUrl=foodSourceUrl;
    }
    public static final List<DriveRegion> ALL=Collections.unmodifiableList(Arrays.asList(
        new DriveRegion("region-gangneung","강릉",37.7945,128.9090,"coast","경포호와 동해를 품은 도시","바다와 호수를 함께","경포권",new String[]{"초당두부","커피"},new String[]{"바다","산·강"},
                "한국관광공사 · 대한민국 정책브리핑","https://www.korea.kr/news/policyNewsView.do?newsId=148795078","https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=1a557b38-ea23-4839-9879-19dbb972f99c"),
        new DriveRegion("region-sokcho-yangyang","속초·양양",38.1245,128.6286,"coast","동해 바다와 설악산의 만남","해안과 산자락을 따라","낙산권",new String[]{"물회","막국수"},new String[]{"바다","산·강"},
                "속초시 · 양양군","https://www.yangyang.go.kr/gw/portal/em_kanghyunmyeon_way","https://sokcho.go.kr/ct/tour/pleasure/food"),
        new DriveRegion("region-yeosu","여수",34.7394,127.7365,"coast","섬과 항구, 야경이 빛나는 도시","바다와 항구의 여유","돌산·여수 해안권",new String[]{"게장백반","서대회"},new String[]{"바다","도시"},
                "여수시 관광문화","https://yeosu.go.kr/tour/main.do","https://yeosu.go.kr/tour/main.do"),
        new DriveRegion("region-namhae","남해",34.8004,128.0463,"coast","굽이진 해안과 작은 바닷마을","해안 풍경을 천천히","삼동·물미해안권",new String[]{"멸치쌈밥","멸치회"},new String[]{"바다","산·강"},
                "한국관광공사","https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=4730e257-2973-4604-9ea3-9666b41545d4","https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=f6ecd4c4-8562-4124-9565-7e17ec8102c3"),
        new DriveRegion("region-gyeongju","경주",35.8435,129.2880,"water","보문호와 신라 유적이 있는 고도","호수에서 고도로","보문·경주 시내권",new String[]{"쌈밥","황남빵"},new String[]{"산·강","도시"},
                "경주시 문화관광","https://www.gyeongju.go.kr/tour/page.do?mnu_uid=4840","https://www.gyeongju.go.kr/tour/page.do?mnu_uid=4824"),
        new DriveRegion("region-jeju","제주 동부",33.5224,126.8599,"coast","오름과 현무암 해안이 펼쳐진 곳","동쪽 해안 따라가기","구좌·동부 해안권",new String[]{"고기국수","갈치 요리"},new String[]{"바다"},
                "제주관광공사 Visit Jeju","https://www.visitjeju.net/kr/detail/view?contentsid=CNTS_000000000018371","https://www.visitjeju.net/pdf/Official%20Jeju%20Tourism%20Guidebook_kr.pdf"),
        new DriveRegion("region-busan","부산 해안",35.1587,129.1604,"city","해변과 도시의 야경이 만나는 곳","도시에서 바다로","해운대·동부 해안권",new String[]{"밀면","돼지국밥"},new String[]{"바다","도시"},
                "부산관광공사 Visit Busan","https://www.visitbusan.net/","https://www.visitbusan.net/index.do?lang_cd=ko&menuCd=DOM_000000201001001000&uc_seq=1005"),
        new DriveRegion("region-gapyeong","가평",37.8175,127.5107,"water","북한강과 숲을 따라 쉬어 가는 곳","강과 숲 사이로","가평·북한강권",new String[]{"잣국수","잣두부"},new String[]{"산·강"},
                "한국관광공사","https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=4abccc7d-f3f8-4c1d-899e-abcf7b5725ca","https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=cb635d3e-0f73-4ccd-9c2a-cdec1027f3eb")
    ));
    public boolean matchesFilter(String filter){return "전체".equals(filter)||filterTags.contains(filter);}
    public static DriveRegion byId(String id){for(DriveRegion region:ALL)if(region.id.equals(id))return region;return null;}
    public static String[] foodHints(DestinationPlace place){DriveRegion region=place==null?null:byId(place.id);return region==null?new String[0]:region.foodHints.toArray(new String[0]);}
    public DestinationPlace toDestination()throws JSONException{return DestinationPlace.of(id,name,"지역 탐색 기준점 · "+centerLabel+" · 방문할 장소는 사진에서 선택",lat,lon);}
    public String foodLabel(){return String.join(" · ",foodHints);}
}
