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
        LinearLayout recap=col();recap.setPadding(dp(24),dp(22),dp(24),dp(22));recap.setBackground(bg(0xffe3eee7,0));recap.addView(text("DRIVE → CAPTURE → RECAP",12,TEAL,true));gap(recap,12);recap.addView(text("오늘의 풍경이\n이번 달의 추억으로",25,INK,true));gap(recap,10);recap.addView(text("자동 기록 시연으로 세 장면을 담고,\n음악이 있는 세로 영상으로 만들어 보세요.",15,MUTED,false));gap(recap,16);recap.addView(button("자동 기록 체험 · 18초",true,host::startSampleJourney),size(-1,-2));gap(recap,8);recap.addView(button("나의 월간 리캡  ·  기록 "+host.records().size()+"개",false,host::openRecap),size(-1,-2));
        if(wide)hero.addView(recap,new LinearLayout.LayoutParams(0,-2,1));else hero.addView(recap,size(-1,-2));body.addView(hero);gap(body,26);
        LinearLayout section=row();section.addView(text("한 장으로 발견하는 드라이브",23,INK,true),new LinearLayout.LayoutParams(0,-2,1));section.addView(button("사진 출처",false,this::credits));body.addView(section);gap(body,8);body.addView(text("공개 사진으로 구성한 코스 예시입니다. 실제 차창 시야나 다른 운전자의 촬영 기록은 아닙니다.",13,MUTED,false));gap(body,16);
        LinearLayout filters=row();for(String label:new String[]{"전체","바다","도시","저장한 곳"}){TextView chip=button(label,filter.equals(label),()->{filter=label;render();});filters.addView(chip);space(filters,8);}body.addView(filters);gap(body,18);
        LinearLayout cards=wide?row():col();cards.setGravity(Gravity.TOP);int count=0;
        for(Entry entry:ENTRIES){if(!filter.equals("전체")&&!filter.equals(entry.label)&&!(filter.equals("저장한 곳")&&prefs.getBoolean(entry.id,false)))continue;if(count++>0){if(wide)space(cards,16);else gap(cards,16);}View card=card(entry);cards.addView(card,wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}
        body.addView(cards,size(-1,-2));if(count==0)body.addView(text("마음에 드는 장소의 ‘저장’을 눌러 모아보세요.",17,MUTED,false));
        java.util.List<ScenicCaptureStore.Record> shared=new ArrayList<>();for(ScenicCaptureStore.Record record:host.records())if(record.sharedPreview)shared.add(record);
        gap(body,28);body.addView(text("내가 남긴 풍경으로 다시 발견",23,INK,true));gap(body,8);body.addView(text("기록에서 공유 미리보기를 켠 장면이 여기에 표시됩니다. 이 기기에만 보이며 업로드하지 않습니다.",13,MUTED,false));gap(body,14);
        if(shared.isEmpty())body.addView(button("첫 장면 담기 · 자동 기록 체험",false,host::startSampleJourney),size(-1,-2));
        else {LinearLayout local=wide?row():col();local.setGravity(Gravity.TOP);for(int i=0;i<Math.min(3,shared.size());i++){ScenicCaptureStore.Record record=shared.get(i);LinearLayout item=col();item.setPadding(dp(14),dp(14),dp(14),dp(14));item.setBackground(bg(0xffffffff,LINE));item.addView(photo(record.imageKey,130));gap(item,10);item.addView(text(record.segmentName,18,INK,true));gap(item,6);item.addView(text("내 공유 미리보기 · 샘플 장면",13,MUTED,false));gap(item,10);item.addView(button("이 장소 다시 보기",false,()->detail(forKey(record.imageKey))),size(-1,-2));if(i>0){if(wide)space(local,12);else gap(local,12);}local.addView(item,wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));}body.addView(local);}
    }
    private View card(Entry entry){LinearLayout card=col();card.setBackground(bg(0xffffffff,LINE));card.setPadding(dp(14),dp(14),dp(14),dp(18));View photo=photo(entry.key,wide?196:220);photo.setOnClickListener(v->{if(allowed())detail(entry);});photo.setContentDescription(entry.name+" 공개 사진 · 크게 보기");photo.setFocusable(true);card.addView(photo);gap(card,14);card.addView(text(entry.label+"  /  부산 · 공개 코스 예시",12,TEAL,true));gap(card,8);card.addView(text(entry.name,23,INK,true));gap(card,8);card.addView(text(entry.subtitle,15,MUTED,false));gap(card,6);card.addView(text(credit(entry.key),12,MUTED,false));gap(card,14);LinearLayout actions=row();actions.addView(button("이 풍경으로 경로 비교",true,()->choose(entry)),new LinearLayout.LayoutParams(0,-2,1));space(actions,8);actions.addView(button(prefs.getBoolean(entry.id,false)?"저장됨":"저장",false,()->{prefs.edit().putBoolean(entry.id,!prefs.getBoolean(entry.id,false)).apply();render();}),size(78,-2));card.addView(actions);return card;}
    private void choose(Entry entry){if(!allowed())return;pause();try{host.chooseDestination(DestinationPlace.of(entry.id,entry.name,"부산 · 공개 예시 목적지",entry.lat,entry.lon),entry.theme,30);}catch(Exception invalid){Toast.makeText(activity,"장소 정보를 확인하지 못했어요.",Toast.LENGTH_SHORT).show();}}
    private void detail(Entry entry){if(!allowed())return;pause();LinearLayout panel=col();panel.setPadding(dp(24),dp(24),dp(24),dp(24));panel.addView(text(entry.name,26,INK,true));gap(panel,12);panel.addView(photo(entry.key,240));gap(panel,12);panel.addView(text("공개 사진 미리보기 · 실제 주행 영상 아님",14,TEAL,true));gap(panel,8);panel.addView(text(entry.subtitle+"\n선택하면 부산역 시연 출발지에서 실제 도로 경로를 비교합니다.",16,MUTED,false));gap(panel,10);panel.addView(text(credit(entry.key),13,MUTED,false));gap(panel,18);panel.addView(button("이 풍경으로 경로 비교",true,()->choose(entry)));dialog=new AlertDialog.Builder(activity).setView(panel).setNegativeButton("닫기",null).create();dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(720),activity.getResources().getDisplayMetrics().widthPixels-dp(48)),-2);}
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
