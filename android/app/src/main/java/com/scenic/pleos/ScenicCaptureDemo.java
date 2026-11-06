package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** A permission-free, offline built-in-camera demonstration with truthful sample labels. */
public final class ScenicCaptureDemo {
    public interface Host {
        boolean canInteract();
        void onCaptureSaved(ScenicCaptureStore.Record record);
    }
    private static final int INK=0xff17191c,MUTED=0xff656970,LINE=0xffdfe1e4,SOFT=0xfff5f5f5,WHITE=0xffffffff;
    private static final String PREFS="scenic_capture_demo",KEY="local_records_v1";
    private final Activity activity;
    private final Host host;
    private final ScenicCaptureStore store;
    private final HashMap<String,Bitmap> bitmaps=new HashMap<>();
    private AlertDialog dialog;
    private LinearLayout panel;
    private String panelRouteId,panelRouteName,panelDestination,panelSegment;
    private boolean panelWide,panelAllRecords;

    public ScenicCaptureDemo(Activity activity,Host host) {
        this.activity=activity;this.host=host;
        final SharedPreferences preferences=activity.getSharedPreferences(PREFS,0);
        store=new ScenicCaptureStore(new ScenicCaptureStore.Persistence(){
            public String read(){return preferences.getString(KEY,"");}
            public void write(String value){preferences.edit().putString(KEY,value).apply();}
        });
    }
    /** A ScrollView on wide displays; a natural-height column inside the host's portrait scroll. */
    public View createView(String routeId,String routeName,String destination,String segmentName,boolean wide) {
        return createView(routeId,routeName,destination,segmentName,wide,false);
    }
    public View createView(String routeId,String routeName,String destination,String segmentName,boolean wide,boolean showAllRecords) {
        panelRouteId=routeId;panelRouteName=routeName;panelDestination=destination;panelSegment=segmentName;panelWide=wide;panelAllRecords=showAllRecords;
        panel=column();panel.setPadding(dp(24),dp(24),dp(24),dp(28));panel.setBackground(background(WHITE,LINE));
        renderPanel();
        if(!wide)return panel;
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));return scroll;
    }
    /** A compact route photo. Tapping it opens a sample preview, never initiates a capture. */
    public View createPreview(String routeId,String routeName,String destination,String segmentName) {
        String imageKey=ScenicCaptureStore.imageKeyFor(segmentName,destination);
        String caption=location(imageKey)+" · 공유 예시";
        String detail="경로 선택 화면을 위한 공유 예시입니다. 다른 운전자의 실제 기록이 아닙니다.";
        for(ScenicCaptureStore.Record record:store.recordsForRoute(routeId)){imageKey=record.imageKey;caption=record.segmentName+(record.sharedPreview?" · 내 공유 미리보기":" · 내 경로의 장면");detail="이 경로에 추가한 내 데모 장면입니다. 이 기기에만 저장되며 다른 운전자에게 전송되지 않습니다.";break;}
        final String previewKey=imageKey;final String route=routeName;final String previewDetail=detail;
        LinearLayout preview=column();
        preview.addView(photo(previewKey,108,caption),size(-1,108));
        TextView note=text("샘플 사진 · 실제 차량 촬영 아님",12,MUTED,false);note.setPadding(0,dp(6),0,0);preview.addView(note);
        preview.setContentDescription(caption+". 샘플 사진. 큰 미리보기 열기");preview.setFocusable(true);
        preview.setOnClickListener(v->{if(allowed())showPhoto(previewKey,route,previewDetail);});
        return preview;
    }
    public void setSharingEnabled(boolean enabled){store.setSharingEnabled(enabled);}
    public int savedCount(String routeId){return store.recordsForRoute(routeId).size();}
    public List<ScenicCaptureStore.Record> recordsForRoute(String routeId){return store.recordsForRoute(routeId);}

    public void showCapture(String routeId,String routeName,String destination,String segmentName) {
        if(!allowed())return;
        final ScenicCaptureStore.Record draft;
        try{draft=store.draft(routeId,routeName,destination,segmentName,System.currentTimeMillis());}
        catch(IllegalArgumentException invalid){toast("먼저 목적지와 경로를 선택해 주세요.");return;}
        dismiss();
        LinearLayout body=dialogBody();
        body.addView(text("빌트인 캠 · 데모",13,MUTED,true));gap(body,8);
        body.addView(text("데모 촬영 완료",24,INK,true));gap(body,8);
        body.addView(text(draft.destination+"까지 · "+draft.segmentName,16,INK,false));gap(body,16);
        body.addView(photo(draft.imageKey,190,location(draft.imageKey)+" · 예시 이미지"),size(-1,190));gap(body,8);
        body.addView(text("샘플 사진 · 실제 차량 촬영 아님",13,MUTED,false));gap(body,6);
        body.addView(sourceAction(draft.imageKey));gap(body,14);
        addSharingSwitch(body);
        gap(body,16);
        TextView save=button("경로 미리보기에 추가",true,()->{
            if(!allowed())return;
            ScenicCaptureStore.Record record=store.save(draft,host.canInteract());
            dismiss();renderPanel();host.onCaptureSaved(record);toast("데모 갤러리에 추가했어요.");
        });body.addView(save,size(-1,-2));gap(body,8);
        body.addView(button("닫기",false,this::dismiss),size(-1,-2));
        showDialog(body);
    }
    /** Used by the host's explicit demonstration timeline; no popup, camera, or server request. */
    public ScenicCaptureStore.Record saveDemoCapture(String routeId,String routeName,String destination,String segmentName) {
        if(!host.canInteract())return null;
        try {
            ScenicCaptureStore.Record record=store.save(store.draft(routeId,routeName,destination,segmentName,System.currentTimeMillis()),host.canInteract());
            renderPanel();host.onCaptureSaved(record);return record;
        } catch(IllegalArgumentException|IllegalStateException blocked){return null;}
    }
    public void refreshSafety(){if(!host.canInteract())dismiss();}
    public void dismiss(){AlertDialog old=dialog;dialog=null;if(old!=null)old.dismiss();}

    private void renderPanel() {
        if(panel==null)return;
        panel.removeAllViews();
        panel.addView(text("SCENIC CAMERA",12,MUTED,true));gap(panel,8);
        panel.addView(text("빌트인 캠 · 데모",26,INK,true));gap(panel,8);
        panel.addView(text("마음에 드는 풍경을 담아, 경로를 고를 때 다시 만나요.",16,MUTED,false));gap(panel,20);
        boolean selected=panelRouteId!=null&&!panelRouteId.trim().isEmpty();
        if(selected) {
            String key=ScenicCaptureStore.imageKeyFor(panelSegment,panelDestination);
            panel.addView(photo(key,panelWide?180:190,location(key)+" · 예시 이미지"),size(-1,panelWide?180:190));gap(panel,10);
            panel.addView(text(value(panelDestination,"선택한 목적지")+"까지",19,INK,true));gap(panel,5);
            panel.addView(text(value(panelSegment,"선택한 경로"),15,MUTED,false));gap(panel,14);
            panel.addView(button("빌트인 캠 촬영",true,()->showCapture(panelRouteId,panelRouteName,panelDestination,panelSegment)),size(-1,-2));gap(panel,8);
            panel.addView(text("샘플 사진으로 촬영을 체험해요. 실제 차량 촬영은 실행되지 않아요.",13,MUTED,false));
            panel.addView(sourceAction(key));gap(panel,22);
        } else {
            panel.addView(text("목적지와 경로를 선택하면 촬영 데모를 시작할 수 있어요.",15,MUTED,false));gap(panel,20);
        }
        View divider=new View(activity);divider.setBackgroundColor(LINE);panel.addView(divider,size(-1,1));gap(panel,20);
        List<ScenicCaptureStore.Record> records=store.recordsForRoute(panelAllRecords?null:panelRouteId);
        panel.addView(text("데모 갤러리  "+records.size(),21,INK,true));gap(panel,7);
        panel.addView(text(selected&&!panelAllRecords?"선택한 경로에 저장한 장면이에요.":"이 기기에 저장한 풍경 기록이에요.",14,MUTED,false));gap(panel,16);
        if(records.isEmpty()) {
            LinearLayout empty=column();empty.setPadding(dp(20),dp(24),dp(20),dp(24));empty.setBackground(background(SOFT,0));
            empty.addView(text("첫 번째 풍경을 담아 보세요",18,INK,true));gap(empty,8);
            empty.addView(text("촬영 후 ‘경로 미리보기에 추가’를 누르면 여기에 남아요.",14,MUTED,false));panel.addView(empty,size(-1,-2));
        } else for(ScenicCaptureStore.Record record:records) {
            LinearLayout card=column();card.setPadding(0,0,0,dp(20));
            card.addView(photo(record.imageKey,panelWide?160:176,record.sharedPreview?"공유 미리보기":"내 장면"),size(-1,panelWide?160:176));gap(card,10);
            card.addView(text(record.segmentName,19,INK,true));gap(card,5);
            card.addView(text(record.destination+"까지 · "+new SimpleDateFormat("M월 d일 HH:mm",Locale.KOREA).format(new Date(record.createdAt)),14,MUTED,false));gap(card,5);
            card.addView(text("샘플 사진 · 실제 차량 촬영 아님",12,MUTED,false));gap(card,3);
            card.addView(text(credit(record.imageKey),12,MUTED,false));
            card.setContentDescription(record.segmentName+". "+record.destination+"까지의 경로에 저장한 데모 사진. 상세 열기");
            card.setFocusable(true);card.setOnClickListener(v->{if(allowed())showPhoto(record.imageKey,record.segmentName,record.destination+"까지 · "+(record.sharedPreview?"공유 미리보기":"내 장면")+"\n이 기기에만 저장된 데모 기록입니다.");});
            panel.addView(card,size(-1,-2));
        }
        gap(panel,8);panel.addView(text("공유 미리보기는 이 기기에서만 보여요. 다른 운전자에게 전송되지 않아요.",13,MUTED,false));
    }
    private void addSharingSwitch(LinearLayout body) {
        Switch toggle=new Switch(activity);toggle.setText("공유 미리보기에 표시");toggle.setTextColor(INK);toggle.setTextSize(16);
        toggle.setTypeface(ScenicTypography.font(activity,600));toggle.setMinHeight(dp(56));toggle.setSwitchMinWidth(dp(52));toggle.setChecked(store.sharingEnabled());
        toggle.setOnCheckedChangeListener((view,checked)->{
            if(!host.canInteract()){view.setOnCheckedChangeListener(null);view.setChecked(store.sharingEnabled());dismiss();return;}
            store.setSharingEnabled(checked);
        });body.addView(toggle,size(-1,-2));gap(body,6);
        body.addView(text("다른 운전자의 경로 선택 화면을 본뜬 공유 미리보기에 표시해요. 이 기기에만 저장되며 다른 운전자에게 전송되지 않아요.",13,MUTED,false));
    }
    private void showPhoto(String key,String title,String detail) {
        dismiss();LinearLayout body=dialogBody();body.addView(text(value(title,"공유 예시"),22,INK,true));gap(body,12);
        body.addView(photo(key,220,location(key)+" · 예시 이미지"),size(-1,220));gap(body,10);
        body.addView(text("샘플 사진 · 실제 차량 촬영 아님",13,MUTED,true));gap(body,10);body.addView(text(detail,15,INK,false));gap(body,14);
        body.addView(sourceDetails(key));gap(body,16);body.addView(button("닫기",true,this::dismiss),size(-1,-2));showDialog(body);
    }
    private View sourceAction(String key) {
        LinearLayout source=column();
        TextView label=text(credit(key)+"  ·  출처 보기",12,MUTED,false);label.setPadding(0,dp(10),0,dp(10));label.setMinHeight(dp(44));label.setFocusable(true);
        TextView details=sourceDetails(key);details.setVisibility(View.GONE);source.addView(label);source.addView(details);
        label.setOnClickListener(v->{if(allowed())details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});return source;
    }
    private TextView sourceDetails(String key) {
        String url="haeundae".equals(key)?"https://commons.wikimedia.org/wiki/File:Busan_City_Haeundae_Beach.jpg":
            "gwangalli".equals(key)?"https://commons.wikimedia.org/wiki/File:Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg":"https://commons.wikimedia.org/wiki/File:Cheongsapo.jpg";
        String license="haeundae".equals(key)?"https://www.kogl.or.kr/info/licenseType1.do":"gwangalli".equals(key)?"https://creativecommons.org/licenses/by-sa/4.0/":"https://creativecommons.org/licenses/by-sa/3.0/";
        TextView source=text(credit(key)+"\n공개 라이선스 사진을 화면에 맞춰 잘라 표시합니다. 선택한 도로의 실제 주행 시야를 나타내지는 않습니다.\n원본 사진\n"+url+"\n라이선스\n"+license,12,MUTED,false);Linkify.addLinks(source,Linkify.WEB_URLS);source.setLinkTextColor(INK);source.setMovementMethod(LinkMovementMethod.getInstance());return source;
    }
    private View photo(String key,int height,String label) {
        FrameLayout frame=new FrameLayout(activity);frame.setBackground(background(0xffe5e9eb,0));frame.setClipToOutline(true);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(location(key)+" 샘플 사진. 실제 차량 촬영 아님.");
        Bitmap bitmap=bitmap(key);if(bitmap!=null)image.setImageBitmap(bitmap);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView tag=text(label,13,WHITE,true);tag.setPadding(dp(12),dp(7),dp(12),dp(7));tag.setBackground(background(0xb017191c,0));
        FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);badge.setMargins(dp(12),0,dp(12),dp(12));frame.addView(tag,badge);
        return frame;
    }
    private Bitmap bitmap(String key) {
        if(bitmaps.containsKey(key))return bitmaps.get(key);
        try(InputStream input=activity.getAssets().open("scenic-demo/"+key+".jpg")){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
            Bitmap decoded=BitmapFactory.decodeStream(input,null,options);bitmaps.put(key,decoded);return decoded;
        } catch(Exception unavailable){return null;}
    }
    private LinearLayout dialogBody(){LinearLayout body=column();body.setPadding(dp(24),dp(24),dp(24),dp(24));body.setBackgroundColor(WHITE);return body;}
    private void showDialog(LinearLayout body) {
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(body);
        dialog=new AlertDialog.Builder(activity).setView(scroll).create();dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(background(WHITE,0));int maxWidth=activity.getResources().getDisplayMetrics().widthPixels-dp(48);window.setLayout(Math.min(dp(620),maxWidth),-2);}
    }
    private boolean allowed(){if(host.canInteract())return true;toast("풍경 촬영과 미리보기는 정차 후 이용해 주세요.");dismiss();return false;}
    private void toast(String message){Toast.makeText(activity,message,Toast.LENGTH_SHORT).show();}
    private static String value(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value;}
    private static String location(String key){return "gwangalli".equals(key)?"광안리와 광안대교":"coast".equals(key)?"청사포 해안":"해운대해수욕장";}
    private static String credit(String key){return "gwangalli".equals(key)?"Masterhatch · CC BY-SA 4.0":"coast".equals(key)?"Andrewssi2 · CC BY-SA 3.0":"부산광역시 · 공공누리 제1유형";}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private void gap(LinearLayout parent,int height){parent.addView(new View(activity),size(1,height));}
    private GradientDrawable background(int color,int border){GradientDrawable result=new GradientDrawable();result.setColor(color);result.setCornerRadius(dp(10));if(border!=0)result.setStroke(dp(1),border);return result;}
    private TextView text(String value,int size,int color,boolean bold){TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setTypeface(ScenicTypography.font(activity,bold?600:400));text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;}
    private TextView button(String label,boolean primary,Runnable action){
        TextView button=text(label,16,primary?WHITE:INK,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(16),dp(14),dp(16),dp(14));button.setMinHeight(dp(56));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2017191c),background(primary?INK:WHITE,primary?0:LINE),null));button.setFocusable(true);button.setClickable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
        button.setOnClickListener(v->action.run());return button;
    }
}
