package com.scenic.pleos;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Photo-first discovery. Public example photos and local records are explicitly distinguished. */
public final class ScenicDiscoveryView {
    public interface Host {
        boolean canInteract();
        void chooseDestination(DestinationPlace place,String theme,int extraMinutes);
        void openAiPlanner(); void startSampleJourney(); void openRecap(); void openNearby();
        java.util.List<ScenicCaptureStore.Record> records();
        default String originLabel(){return "경로에서 선택한 출발지";}
    }
    private static final int INK=0xff172323,MUTED=0xff687571,TEAL=0xff2d6659,LINE=0xffdce4df;
    private final Activity activity; private final Host host; private final SharedPreferences prefs;
    private String filter="전체"; private LinearLayout body; private boolean wide; private AlertDialog dialog;
    private final Map<String,Bitmap> images=new HashMap<>();
    private static final class Entry {
        final String id,name,subtitle,key,theme,label; final double lat,lon;
        Entry(String id,String name,String subtitle,String key,String theme,String label,double lat,double lon){this.id=id;this.name=name;this.subtitle=subtitle;this.key=key;this.theme=theme;this.label=label;this.lat=lat;this.lon=lon;}
    }
    private static final Entry[] ENTRIES={
        new Entry("7913306","해운대해수욕장","넓게 열린 수평선, 천천히 가는 오후","haeundae","coast","바다",35.1585232170784,129.159854668484),
        new Entry("126078","광안리해수욕장","도시와 바다가 만나는 해안 드라이브","gwangalli","coast","도시",35.1531932737,129.1189760936),
        new Entry("cheongsapo","청사포","작은 항구에서 마무리하는 바닷길","coast","coast","바다",35.16051,129.19129)
    };
    public ScenicDiscoveryView(Activity activity,Host host){this.activity=activity;this.host=host;this.prefs=activity.getSharedPreferences("scenic_discovery",0);}
    public View createView(boolean wide){this.wide=wide;body=col();body.setPadding(0,0,0,dp(24));render();ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(body);return scroll;}
    private void render(){
        if(body==null)return;body.removeAllViews();
        LinearLayout hero=wide?row():col();hero.setGravity(Gravity.TOP);
        LinearLayout intro=col();intro.setPadding(dp(8),dp(10),dp(24),dp(18));intro.addView(text("DISCOVER YOUR NEXT DRIVE",12,TEAL,true));gap(intro,12);intro.addView(text("마음에 드는 풍경에서,\n다음 드라이브를 시작하세요.",32,INK,true));gap(intro,12);intro.addView(text("풍경으로 고르고 · 가는 길을 비교하고 · 좋은 순간을 간직해요.",17,MUTED,false));gap(intro,18);
        LinearLayout actions=row();actions.addView(button("AI 코스 찾기  ↗",true,host::openAiPlanner));space(actions,10);actions.addView(button("도착지 맛집·주차",false,host::openNearby));intro.addView(actions);
        if(wide)hero.addView(intro,new LinearLayout.LayoutParams(0,-2,1.35f));else hero.addView(intro);
        LinearLayout recap=col();recap.setPadding(dp(24),dp(22),dp(24),dp(22));recap.setBackground(bg(0xffe3eee7,0));recap.addView(text("DRIVE → CAPTURE → RECAP",12,TEAL,true));gap(recap,12);recap.addView(text("오늘의 풍경이\n이번 달의 추억으로",25,INK,true));gap(recap,10);recap.addView(text("내 영상을 골라 음악과 기억을 더해 보세요.\n샘플 자동 기록 체험도 준비되어 있어요.",15,MUTED,false));gap(recap,16);recap.addView(button("내 영상으로 리캡 만들기",true,host::openRecap),size(-1,-2));gap(recap,8);recap.addView(button("자동 기록 체험 · 18초",false,host::startSampleJourney),size(-1,-2));gap(recap,8);recap.addView(button("나의 월간 리캡  ·  샘플 기록 "+host.records().size()+"개",false,host::openRecap),size(-1,-2));
        if(wide)hero.addView(recap,new LinearLayout.LayoutParams(0,-2,1));else hero.addView(recap,size(-1,-2));body.addView(hero);gap(body,26);
        LinearLayout section=row();section.addView(text("한 장으로 발견하는 드라이브",23,INK,true),new LinearLayout.LayoutParams(0,-2,1));section.addView(button("사진 출처",false,this::credits));body.addView(section);gap(body,8);body.addView(text("장소 사진과 차창 풍경은 다를 수 있어요. 사진을 눌러 출처·확인 상태·내 관찰 기록을 함께 살펴보세요.",13,MUTED,false));gap(body,16);
        LinearLayout filters=row();for(String label:new String[]{"전체","바다","도시","저장한 곳","내 관찰"}){TextView chip=button(label,filter.equals(label),()->{filter=label;render();});filters.addView(chip);space(filters,8);}HorizontalScrollView filterScroll=new HorizontalScrollView(activity);filterScroll.setHorizontalScrollBarEnabled(false);filterScroll.addView(filters);body.addView(filterScroll);gap(body,18);
        LinearLayout cards=wide?row():col();cards.setGravity(Gravity.TOP);int count=0;
        for(Entry entry:ENTRIES){if(!filter.equals("전체")&&!filter.equals(entry.label)&&!(filter.equals("저장한 곳")&&prefs.getBoolean(entry.id,false))&&!(filter.equals("내 관찰")&&observation(entry)!=null))continue;if(count++>0){if(wide)space(cards,16);else gap(cards,16);}View card=card(entry);cards.addView(card,wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}
        body.addView(cards,size(-1,-2));if(count==0)body.addView(text(filter.equals("내 관찰")?"장소 사진을 눌러 직접 본 위치·방향·날짜를 기록해 보세요.":"마음에 드는 장소의 ‘저장’을 눌러 모아보세요.",17,MUTED,false));
        java.util.List<ScenicCaptureStore.Record> shared=new ArrayList<>();for(ScenicCaptureStore.Record record:host.records())if(record.sharedPreview)shared.add(record);
        gap(body,28);body.addView(text("내가 남긴 풍경으로 다시 발견",23,INK,true));gap(body,8);body.addView(text("기록에서 공유 미리보기를 켠 장면이 여기에 표시됩니다. 이 기기에만 보이며 업로드하지 않습니다.",13,MUTED,false));gap(body,14);
        if(shared.isEmpty())body.addView(button("첫 장면 담기 · 자동 기록 체험",false,host::startSampleJourney),size(-1,-2));
        else {LinearLayout local=wide?row():col();local.setGravity(Gravity.TOP);for(int i=0;i<Math.min(3,shared.size());i++){ScenicCaptureStore.Record record=shared.get(i);LinearLayout item=col();item.setPadding(dp(14),dp(14),dp(14),dp(14));item.setBackground(bg(0xffffffff,LINE));item.addView(photo(record.imageKey,130));gap(item,10);item.addView(text(record.segmentName,18,INK,true));gap(item,6);item.addView(text("내 공유 미리보기 · 샘플 장면",13,MUTED,false));gap(item,10);item.addView(button("이 장소 다시 보기",false,()->detail(forKey(record.imageKey))),size(-1,-2));if(i>0){if(wide)space(local,12);else gap(local,12);}local.addView(item,wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}body.addView(local);}
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
    public void destroy(){pause();images.clear();}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=col();l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void gap(LinearLayout l,int n){l.addView(new View(activity),size(1,n));}private void space(LinearLayout l,int n){l.addView(new View(activity),size(n,1));}
    private GradientDrawable bg(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setTypeface(ScenicTypography.font(activity,bold?600:400));t.setLineSpacing(dp(3),1);t.setIncludeFontPadding(false);return t;}
    private TextView button(String s,boolean primary,Runnable run){TextView t=text(s,15,primary?0xffffffff:INK,true);t.setPadding(dp(16),dp(14),dp(16),dp(14));t.setMinHeight(dp(54));t.setGravity(Gravity.CENTER);t.setBackground(bg(primary?TEAL:0xffffffff,primary?0:LINE));t.setFocusable(true);t.setOnClickListener(v->{if(allowed())run.run();});return t;}
}
