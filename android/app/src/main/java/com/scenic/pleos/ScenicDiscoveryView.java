package com.scenic.pleos;

import android.app.*;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Region discovery first; real destination photos follow an explicit area selection. */
public final class ScenicDiscoveryView {
    public interface Host {
        boolean canInteract();
        void chooseDestination(DestinationPlace place,String theme,int extraMinutes);
        default void searchDestination(){openAiPlanner();}
        default void chooseRegion(DestinationPlace region){chooseDestination(region,"all",30);}
        default void searchRegion(){searchDestination();}
        void openAiPlanner(); void startSampleJourney(); void openRecap(); void openNearby();
        java.util.List<ScenicCaptureStore.Record> records();
        default String originLabel(){return "경로에서 선택한 출발지";}
    }
    private static final int INK=0xff19332c,MUTED=0xff65766f,TEAL=0xff2c6656,LINE=0xffdee7e1,PANEL=0xffffffff,SOFT=0xffeaf1ec;
    private final Activity activity; private final Host host; private final SharedPreferences prefs;
    private String filter="전체"; private LinearLayout body; private boolean wide,samplesExpanded; private AlertDialog dialog;
    private int cardColumns=1;
    private final Map<String,Bitmap> images=new HashMap<>();
    private final Map<String,JSONObject> regionPhotos=new HashMap<>();
    private static final class Entry {
        final String id,name,subtitle,key,theme,label; final double lat,lon;
        Entry(String id,String name,String subtitle,String key,String theme,String label,double lat,double lon){this.id=id;this.name=name;this.subtitle=subtitle;this.key=key;this.theme=theme;this.label=label;this.lat=lat;this.lon=lon;}
    }
    private static final Entry[] ENTRIES={
        new Entry("7913306","해운대해수욕장","넓게 열린 수평선, 천천히 가는 오후","haeundae","coast","바다",35.1585232170784,129.159854668484),
        new Entry("126078","광안리해수욕장","도시와 바다가 만나는 해안 드라이브","gwangalli","coast","도시",35.1531932737,129.1189760936),
        new Entry("cheongsapo","청사포","작은 항구에서 마무리하는 바닷길","coast","coast","바다",35.16051,129.19129)
    };
    public ScenicDiscoveryView(Activity activity,Host host){this.activity=activity;this.host=host;this.prefs=activity.getSharedPreferences("scenic_discovery",0);loadRegionPhotos();}
    private void loadRegionPhotos(){try(InputStream input=activity.getAssets().open("scenic-regions/manifest.json")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>65536)return;bytes.write(buffer,0,count);}JSONArray photos=new JSONObject(bytes.toString("UTF-8")).getJSONArray("photos");for(int i=0;i<photos.length();i++){JSONObject photo=photos.getJSONObject(i);String id=photo.getString("regionId");if(DriveRegion.byId(id)!=null&&photo.getString("file").equals(id+".jpg")&&ScenicPhotoCollection.safeImageUrl(photo.getString("originalUrl")))regionPhotos.put(id,photo);}}catch(Exception ignored){regionPhotos.clear();}}
    public View createView(boolean wide){
        this.wide=wide;cardColumns=regionColumns(activity.getResources().getConfiguration().screenWidthDp-48);
        body=col();body.setPadding(0,0,0,dp(24));render();
        LinearLayout viewBody=body;
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(body);
        scroll.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            if(body!=viewBody||right<=left||right-left==oldRight-oldLeft)return;
            int columns=regionColumns(Math.round((right-left)/activity.getResources().getDisplayMetrics().density));
            if(columns!=cardColumns){cardColumns=columns;viewBody.post(()->{if(body==viewBody)render();});}
        });
        return scroll;
    }
    private int regionColumns(int widthDp){int minimum=Math.round(330*Math.max(1f,activity.getResources().getConfiguration().fontScale));return Math.max(1,Math.min(3,(widthDp+16)/(minimum+16)));}
    private void render(){
        if(body==null)return;body.removeAllViews();
        boolean roomy=cardColumns>1;
        LinearLayout hero=roomy?row():col();hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout intro=col();intro.addView(text("어디로 떠나고 싶나요?",28,INK,true));gap(intro,8);
        intro.addView(text("지역을 고르면, AI가 주변 풍경을 찾아요.",17,MUTED,false));
        if(roomy){hero.addView(intro,new LinearLayout.LayoutParams(0,-2,1));space(hero,24);}else{hero.addView(intro,size(-1,-2));gap(hero,16);}
        LinearLayout search=row();search.addView(button("지역 검색",true,host::searchRegion),new LinearLayout.LayoutParams(0,-2,1));space(search,8);
        search.addView(button("AI 코스 찾기",false,host::openAiPlanner),new LinearLayout.LayoutParams(0,-2,1));
        hero.addView(search,roomy?size(316,-2):size(-1,-2));body.addView(hero,size(-1,-2));gap(body,24);

        List<DriveRegion> shown=new ArrayList<>();for(DriveRegion region:DriveRegion.ALL)if(region.matchesFilter(filter))shown.add(region);
        LinearLayout heading=roomy?row():col();
        TextView sectionTitle=text("드라이브 지역  ·  "+shown.size()+"곳",22,INK,true);
        if(roomy)heading.addView(sectionTitle,new LinearLayout.LayoutParams(0,-2,1));else{heading.addView(sectionTitle);gap(heading,12);}
        LinearLayout filters=row();for(String label:new String[]{"전체","바다","산·강","도시"}){
            if(filters.getChildCount()>0)space(filters,8);
            TextView chip=button(label,false,()->{filter=label;render();});boolean selected=filter.equals(label);
            chip.setTextSize(15);chip.setTextColor(selected?TEAL:MUTED);chip.setSelected(selected);
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),bg(selected?SOFT:PANEL,selected?TEAL:LINE,12),null));
            chip.setContentDescription(label+" 지역"+(selected?" · 선택됨":""));filters.addView(chip);
        }
        HorizontalScrollView filterScroll=new HorizontalScrollView(activity);filterScroll.setHorizontalScrollBarEnabled(false);filterScroll.addView(filters);
        heading.addView(filterScroll,roomy?size(-2,-2):size(-1,-2));body.addView(heading,size(-1,-2));gap(body,8);
        body.addView(text("풍경 사진에서 갈 곳을 고르고, 맛집·주차까지 이어가세요.",14,MUTED,false));gap(body,16);
        int columns=cardColumns;
        for(int index=0;index<shown.size();){
            LinearLayout line=row();line.setGravity(Gravity.TOP);
            for(int col=0;col<columns;col++){
                if(col>0)space(line,16);
                if(index<shown.size())line.addView(regionCard(shown.get(index++)),new LinearLayout.LayoutParams(0,-2,1));
                else line.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
            }
            body.addView(line,size(-1,-2));gap(body,24);
        }
        body.addView(text("지역은 사진을 찾는 기준점이에요. 방문할 장소와 도로 경로는 다음 단계에서 선택해요.",14,MUTED,false));gap(body,24);

        LinearLayout tools=col();tools.setPadding(dp(20),dp(20),dp(20),dp(20));tools.setBackground(bg(SOFT,0));
        tools.addView(text("나의 드라이브 이어가기",22,INK,true));gap(tools,8);
        tools.addView(text("정해둔 목적지를 찾거나, 다녀온 영상을 리캡으로 남겨보세요.",17,MUTED,false));gap(tools,16);
        LinearLayout actions=roomy?row():col();
        addUtility(actions,"목적지 검색",host::searchDestination,roomy);addUtility(actions,"맛집·주차 찾기",host::openNearby,roomy);addUtility(actions,"내 영상으로 리캡",host::openRecap,roomy);
        tools.addView(actions,size(-1,-2));body.addView(tools,size(-1,-2));gap(body,24);
        body.addView(button(samplesExpanded?"부산 샘플·내 관찰 기록  −":"부산 샘플·내 관찰 기록  ＋",false,()->{samplesExpanded=!samplesExpanded;render();}),size(-1,-2));
        if(samplesExpanded)renderSamples();
    }
    private void addUtility(LinearLayout actions,String label,Runnable action,boolean horizontal){if(actions.getChildCount()>0){if(horizontal)space(actions,8);else gap(actions,8);}actions.addView(button(label,false,action),horizontal?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}
    private View regionCard(DriveRegion region){
        LinearLayout card=col();card.setBackground(bg(PANEL,LINE));card.setClipToOutline(true);
        FrameLayout artwork=new FrameLayout(activity);artwork.setBackgroundColor(SOFT);
        JSONObject photoInfo=regionPhotos.get(region.id);boolean loaded=false;
        if(photoInfo!=null)try{String key="region:"+region.id;Bitmap bitmap=images.get(key);if(bitmap==null){try(InputStream input=activity.getAssets().open("scenic-regions/"+region.id+".jpg")){bitmap=BitmapFactory.decodeStream(input);}if(bitmap!=null)images.put(key,bitmap);}if(bitmap!=null){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(bitmap);image.setContentDescription(region.name+" · "+photoInfo.optString("placeName")+" 실제 공개 사진");artwork.addView(image,new FrameLayout.LayoutParams(-1,-1));loaded=true;}}catch(Exception ignored){}
        if(!loaded)artwork.addView(new RegionIllustration(region),new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(activity);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x0010231c,0xe619332c}));artwork.addView(shade,new FrameLayout.LayoutParams(-1,dp(120),Gravity.BOTTOM));
        LinearLayout caption=col();caption.setPadding(dp(20),dp(16),dp(20),dp(18));caption.addView(text(region.name,26,PANEL,true));gap(caption,4);caption.addView(text(region.driveTheme,15,PANEL,false));
        artwork.addView(caption,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));card.addView(artwork,size(-1,cardColumns==3?190:cardColumns==2?210:230));
        LinearLayout details=col();details.setPadding(dp(20),dp(16),dp(20),dp(20));
        TextView credit=text(loaded?photoInfo.optString("placeName")+" · 한국관광공사":"여행 테마 일러스트 · 사진을 읽지 못했어요",14,MUTED,false);credit.setMinHeight(dp(40));details.addView(credit);gap(details,12);
        TextView landscape=text(region.scenery,17,INK,false);landscape.setMinHeight(dp(48));details.addView(landscape);gap(details,8);
        details.addView(text("맛집 키워드  ·  "+region.foodLabel(),14,TEAL,true));gap(details,16);
        LinearLayout actions=row();actions.addView(button("풍경 사진 보기  →",true,()->choose(region)),new LinearLayout.LayoutParams(0,-2,1));space(actions,8);
        TextView source=button("출처",false,()->regionInfo(region));source.setTextSize(14);source.setContentDescription(region.name+" 지역·사진 출처 보기");actions.addView(source,size(72,-2));details.addView(actions,size(-1,-2));
        card.addView(details,size(-1,-2));return card;
    }
    private void choose(DriveRegion region){if(!allowed())return;pause();try{host.chooseRegion(region.toDestination());}catch(Exception invalid){Toast.makeText(activity,"지역 정보를 확인하지 못했어요. 다른 지역을 선택해 주세요.",Toast.LENGTH_SHORT).show();}}
    private void regionInfo(DriveRegion region){
        if(!allowed())return;pause();JSONObject photo=regionPhotos.get(region.id);
        String credit=photo==null?"사진을 읽지 못해 여행 테마 일러스트를 표시합니다.":photo.optString("placeName")+"\n한국관광공사 TourAPI 제공 · "+photo.optString("license")+"\n"+photo.optString("photographer")+"\n확인일: "+photo.optString("fetchedAt").substring(0,10)+"\n가로세로 비율 유지 축소·JPEG 압축, 카드 중앙 맞춤 표시\n정확한 촬영 지점·방향·시기는 미확인";
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle(region.name+" · 여행과 사진")
                .setMessage(region.scenery+"\n"+region.driveTheme+"\n\n함께 맛볼 음식: "+region.foodLabel()+"\n\n사진: "+credit+"\n\n사진 탐색 기준: "+region.centerLabel+"\n탐색 기준점은 지역 사진 검색용이며 사진 촬영 지점이나 최종 목적지가 아닙니다.\n\n지역·음식 자료: "+region.officialSource+"\n"+region.foodSourceUrl)
                .setPositiveButton("관광 정보 열기",(d,w)->openOfficial(region.officialSourceUrl)).setNegativeButton("닫기",null);
        if(photo!=null)builder.setNeutralButton("사진 원본·출처",(d,w)->openOfficial(photo.optString("originalUrl")));
        dialog=builder.show();
    }
    private void openOfficial(String url){if(!allowed())return;try{activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception unavailable){Toast.makeText(activity,"링크를 열 수 있는 앱이 없어요.",Toast.LENGTH_SHORT).show();}}
    private final class RegionIllustration extends View {
        private final DriveRegion region;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RegionIllustration(DriveRegion region){super(activity);this.region=region;setContentDescription(region.name+" 여행 테마 일러스트. 실제 장소 사진이 아닙니다.");}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float w=getWidth(),h=getHeight();boolean city="city".equals(region.scene),water="water".equals(region.scene);
            canvas.drawColor(city?0xffe8e4da:water?0xffdfeae0:0xffdeedf0);
            paint.setColor(0xfff1d8a0);canvas.drawCircle(w*.81f,h*.27f,h*.18f,paint);
            paint.setColor(city?0xffbac5c0:water?0xffa8c1ac:0xffb1cdd2);
            Path back=new Path();back.moveTo(0,h*.53f);back.cubicTo(w*.22f,h*.13f,w*.32f,h*.75f,w*.57f,h*.38f);back.cubicTo(w*.75f,h*.05f,w*.86f,h*.62f,w,h*.36f);back.lineTo(w,h);back.lineTo(0,h);back.close();canvas.drawPath(back,paint);
            paint.setColor(city?0xffd2d9ce:water?0xffccdfcf:0xffcce2e0);Path front=new Path();front.moveTo(0,h*.68f);front.cubicTo(w*.3f,h*.4f,w*.55f,h*.9f,w,h*.6f);front.lineTo(w,h);front.lineTo(0,h);front.close();canvas.drawPath(front,paint);
            paint.setColor(0x88ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));Path line=new Path();line.moveTo(w*.08f,h*.53f);line.cubicTo(w*.28f,h*.42f,w*.53f,h*.69f,w*.78f,h*.48f);canvas.drawPath(line,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private void renderSamples(){
        gap(body,14);body.addView(text("부산 공개 사진으로 살펴보는 샘플",20,INK,true));gap(body,8);
        body.addView(text("기존 공개 사진·직접 남긴 관찰 기록입니다. 다른 지역의 실제 풍경 사진으로 사용하지 않아요.",13,MUTED,false));gap(body,14);
        LinearLayout cards=wide?row():col();cards.setGravity(Gravity.TOP);for(int i=0;i<ENTRIES.length;i++){if(i>0){if(wide)space(cards,14);else gap(cards,14);}cards.addView(card(ENTRIES[i]),wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}body.addView(cards,size(-1,-2));gap(body,12);
        LinearLayout actions=wide?row():col();actions.addView(button("사진 출처",false,this::credits));if(wide)space(actions,8);else gap(actions,8);actions.addView(button("자동 기록 체험 · 18초",false,host::startSampleJourney));if(wide)space(actions,8);else gap(actions,8);actions.addView(button("AI 코스 찾기",false,host::openAiPlanner));body.addView(actions);gap(body,20);
        List<ScenicCaptureStore.Record> shared=new ArrayList<>();for(ScenicCaptureStore.Record record:host.records())if(record.sharedPreview)shared.add(record);
        if(!shared.isEmpty()){body.addView(text("내 공유 미리보기 · 이 기기에만 저장",18,INK,true));gap(body,12);LinearLayout local=wide?row():col();local.setGravity(Gravity.TOP);for(int i=0;i<Math.min(3,shared.size());i++){ScenicCaptureStore.Record record=shared.get(i);LinearLayout item=col();item.setPadding(dp(14),dp(14),dp(14),dp(14));item.setBackground(bg(0xffffffff,LINE));item.addView(photo(record.imageKey,120));gap(item,10);item.addView(text(record.segmentName,16,INK,true));gap(item,6);item.addView(text("샘플 장면 · 실제 촬영 아님",12,MUTED,false));gap(item,8);item.addView(button("관찰 기록 보기",false,()->detail(forKey(record.imageKey))),size(-1,-2));if(i>0){if(wide)space(local,12);else gap(local,12);}local.addView(item,wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}body.addView(local);}
    }
    private View card(Entry entry){LinearLayout card=col();card.setBackground(bg(0xffffffff,LINE));card.setPadding(dp(14),dp(14),dp(14),dp(18));View photo=photo(entry.key,wide?196:220);photo.setOnClickListener(v->{if(allowed())detail(entry);});photo.setContentDescription(entry.name+" 공개 사진 · 출처와 관찰 기록 보기");photo.setFocusable(true);card.addView(photo);gap(card,14);card.addView(text(entry.label+"  /  부산 · 공개 장소 사진",12,TEAL,true));gap(card,8);card.addView(text(entry.name,23,INK,true));gap(card,8);card.addView(text(entry.subtitle,15,MUTED,false));gap(card,6);card.addView(text("도로 조망 미검증 · 촬영 방향·시기 미확인",12,MUTED,false));gap(card,6);card.addView(text(credit(entry.key),12,MUTED,false));ScenicEvidence.Observation own=observation(entry);if(own!=null){gap(card,8);card.addView(text("내 관찰 기록 · "+own.date,13,TEAL,true));}gap(card,8);card.addView(button("사진 근거·내 관찰 보기",false,()->detail(entry)),size(-1,-2));gap(card,10);LinearLayout actions=row();actions.addView(button("이 풍경으로 경로 비교",true,()->choose(entry)),new LinearLayout.LayoutParams(0,-2,1));space(actions,8);actions.addView(button(prefs.getBoolean(entry.id,false)?"저장됨":"저장",false,()->{prefs.edit().putBoolean(entry.id,!prefs.getBoolean(entry.id,false)).apply();render();}),size(78,-2));card.addView(actions);return card;}
    private void choose(Entry entry){if(!allowed())return;pause();try{host.chooseDestination(DestinationPlace.of(entry.id,entry.name,"부산 · 공개 예시 목적지",entry.lat,entry.lon),entry.theme,30);}catch(Exception invalid){Toast.makeText(activity,"장소 정보를 확인하지 못했어요.",Toast.LENGTH_SHORT).show();}}
    private ScenicEvidence.Observation observation(Entry entry){return ScenicEvidence.restore(prefs.getString("observation_"+entry.id,""),entry.id);}
    private void detail(Entry entry){
        if(!allowed())return;pause();LinearLayout panel=dialogPanel();panel.addView(text(entry.name,26,INK,true));gap(panel,12);panel.addView(photo(entry.key,200));gap(panel,12);
        panel.addView(text("공개 장소 사진 · 도로 조망 미검증",15,TEAL,true));gap(panel,12);
        evidenceLine(panel,"사진에 등장하는 장소",entry.name);
        evidenceLine(panel,"목적지 좌표",String.format(Locale.US,"%.5f, %.5f",entry.lat,entry.lon)+" · 사진 촬영 좌표와 다를 수 있어요.");
        evidenceLine(panel,"촬영 방향·시기","미확인 · 사진만으로 진행 방향이나 계절을 단정하지 않아요.");
        evidenceLine(panel,"실제 차창에서 보이는 구간","미검증 · 도로 연결 조회는 풍경이 보인다는 증명이 아니에요.");
        evidenceLine(panel,"사진 출처",credit(entry.key));evidenceLine(panel,"경로 비교 출발지",host.originLabel());
        gap(panel,12);panel.addView(text("내 관찰 기록",20,INK,true));gap(panel,8);ScenicEvidence.Observation own=observation(entry);
        if(own==null)panel.addView(text("직접 가본 도로 구간과 방향을 남겨 다음 드라이브를 준비해 보세요.",14,MUTED,false));
        else{evidenceLine(panel,"관찰한 위치·도로 구간",own.location);evidenceLine(panel,"확인일",own.date);evidenceLine(panel,"진행 방향·보이는 쪽",own.direction);if(!own.note.isEmpty())evidenceLine(panel,"메모",own.note);panel.addView(text(own.source()+" · 이 기기에만 저장 · 서비스 검증 아님",13,TEAL,true));}
        gap(panel,12);panel.addView(button(own==null?"직접 본 풍경 기록하기":"내 관찰 기록 수정",false,()->editObservation(entry)),size(-1,-2));gap(panel,10);panel.addView(button("이 풍경으로 경로 비교",true,()->choose(entry)),size(-1,-2));
        dialog=new AlertDialog.Builder(activity).setView(scroll(panel)).setNegativeButton("닫기",null).create();dialog.show();sizeDialog();
    }
    private void editObservation(Entry entry){
        if(!allowed())return;pause();ScenicEvidence.Observation own=observation(entry);LinearLayout panel=dialogPanel();panel.addView(text(entry.name+" · 내 관찰",23,INK,true));gap(panel,10);panel.addView(text("정차 후 직접 확인한 내용만 남겨 주세요. 입력한 내용은 공개 사진의 촬영 정보와 구분해 이 기기에 저장합니다.",14,MUTED,false));gap(panel,14);
        EditText location=field(panel,"관찰한 위치·도로 구간",own==null?"":own.location,"예: 해변로 ○○교차로 ~ △△주차장",160);
        EditText date=field(panel,"확인일 · YYYY-MM-DD",own==null?new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date()):own.date,"예: 2026-11-06",10);
        EditText direction=field(panel,"진행 방향·풍경이 보이는 쪽",own==null?"":own.direction,"예: 청사포 방향, 도로 오른쪽 바다",160);
        EditText note=field(panel,"관찰 메모 · 선택",own==null?"":own.note,"날씨·시야를 가리는 건물·정차한 장소 등",240);
        CheckBox confirmed=new CheckBox(activity);confirmed.setText("해당 장소에서 직접 확인한 내용입니다.");confirmed.setTextColor(INK);confirmed.setTextSize(15);confirmed.setMinHeight(dp(54));panel.addView(confirmed);gap(panel,6);TextView error=text("",14,0xffa63f32,false);panel.addView(error);
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setView(scroll(panel)).setPositiveButton("내 관찰 저장",null).setNegativeButton("취소",null);if(own!=null)builder.setNeutralButton("기록 삭제",null);dialog=builder.create();dialog.show();sizeDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!allowed()){pause();return;}if(!confirmed.isChecked()){error.setText("직접 확인한 내용인지 체크해 주세요.");return;}try{ScenicEvidence.Observation saved=ScenicEvidence.create(entry.id,location.getText().toString(),date.getText().toString(),direction.getText().toString(),note.getText().toString(),System.currentTimeMillis());prefs.edit().putString("observation_"+entry.id,saved.toJson()).apply();pause();render();detail(entry);}catch(Exception invalid){error.setText(invalid instanceof IllegalArgumentException?invalid.getMessage():"기록을 저장하지 못했어요. 다시 시도해 주세요.");}});
        if(own!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(!allowed()){pause();return;}pause();dialog=new AlertDialog.Builder(activity).setTitle("내 관찰 기록을 삭제할까요?").setMessage("이 장소에 남긴 위치·방향·날짜 메모를 삭제합니다.").setPositiveButton("삭제",(d,w)->{if(allowed()){prefs.edit().remove("observation_"+entry.id).apply();render();detail(entry);}}).setNegativeButton("취소",(d,w)->{if(allowed())detail(entry);}).show();});
    }
    private LinearLayout dialogPanel(){LinearLayout panel=col();panel.setPadding(dp(24),dp(24),dp(24),dp(24));return panel;}
    private ScrollView scroll(View view){ScrollView scroll=new ScrollView(activity);scroll.addView(view);return scroll;}
    private void sizeDialog(){if(dialog!=null&&dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(760),activity.getResources().getDisplayMetrics().widthPixels-dp(48)),Math.min(dp(840),activity.getResources().getDisplayMetrics().heightPixels-dp(140)));}
    private void evidenceLine(LinearLayout panel,String label,String value){panel.addView(text(label,12,TEAL,true));gap(panel,4);panel.addView(text(value,14,INK,false));gap(panel,10);}
    private EditText field(LinearLayout panel,String label,String value,String hint,int maximum){panel.addView(text(label,14,INK,true));gap(panel,6);EditText input=new EditText(activity);input.setText(value);input.setHint(hint);input.setTextSize(15);input.setTextColor(INK);input.setMinHeight(dp(54));input.setMaxLines(3);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum)});panel.addView(input,size(-1,-2));gap(panel,12);return input;}
    private void credits(){if(!allowed())return;try(InputStream in=activity.getAssets().open("scenic-demo/ATTRIBUTION.txt")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);dialog=new AlertDialog.Builder(activity).setTitle("사진 출처 · 공개 라이선스").setMessage(out.toString("UTF-8")).setPositiveButton("확인",null).show();}catch(Exception ignored){}}
    private View photo(String key,int height){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(bg(0xffdce6df,0));image.setClipToOutline(true);try{Bitmap bitmap=images.get(key);if(bitmap==null){try(InputStream in=activity.getAssets().open("scenic-demo/"+key+".jpg")){bitmap=BitmapFactory.decodeStream(in);images.put(key,bitmap);}}image.setImageBitmap(bitmap);}catch(Exception ignored){}image.setLayoutParams(size(-1,height));return image;}
    private static Entry forKey(String key){for(Entry e:ENTRIES)if(e.key.equals(key))return e;return ENTRIES[0];}
    private static String credit(String key){return "gwangalli".equals(key)?"사진 Masterhatch · CC BY-SA 4.0":"coast".equals(key)?"사진 Andrewssi2 · CC BY-SA 3.0":"사진 부산광역시 · 공공누리 제1유형";}
    private boolean allowed(){return host.canInteract()&&!activity.isFinishing();}
    public void pause(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    public void destroy(){pause();body=null;images.clear();}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=col();l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void gap(LinearLayout l,int n){l.addView(new View(activity),size(1,n));}private void space(LinearLayout l,int n){l.addView(new View(activity),size(n,1));}
    private GradientDrawable bg(int color,int stroke){return bg(color,stroke,16);}
    private GradientDrawable bg(int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setTypeface(ScenicTypography.font(activity,bold?600:400));t.setLineSpacing(dp(3),1);t.setIncludeFontPadding(false);return t;}
    private TextView button(String s,boolean primary,Runnable run){TextView t=text(s,17,primary?PANEL:INK,true);t.setPadding(dp(16),dp(14),dp(16),dp(14));t.setMinHeight(dp(56));t.setGravity(Gravity.CENTER);t.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x28ffffff:0x182c6656),bg(primary?TEAL:PANEL,primary?0:LINE,12),null));t.setFocusable(true);t.setContentDescription(s);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});t.setOnClickListener(v->{if(allowed())run.run();});return t;}
}
