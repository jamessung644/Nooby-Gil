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
    private static final int INK=ScenicDesign.INK,MUTED=ScenicDesign.MUTED,LINE=ScenicDesign.LINE,SOFT=ScenicDesign.SOFT,WHITE=ScenicDesign.SURFACE;
    private static final String PREFS="scenic_capture_demo",KEY="local_records_v1";
    private final Activity activity;
    private final Host host;
    private final ScenicCaptureStore store;
    private final HashMap<String,Bitmap> bitmaps=new HashMap<>();
    private AlertDialog dialog;
    private LinearLayout panel;
    private String panelRouteId,panelRouteName,panelDestination,panelSegment;
    private boolean panelWide,panelAllRecords,captureExpanded;

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
        int inset=wide?24:16;
        panel=column();panel.setPadding(dp(inset),dp(24),dp(inset),dp(32));panel.setBackground(background(WHITE,LINE));
        renderPanel();
        if(!wide)return panel;
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));return scroll;
    }
    /** A compact route photo. Tapping it opens a sample preview, never initiates a capture. */
    public View createPreview(String routeId,String routeName,String destination,String segmentName) {
        return createPreview(routeId,routeName,destination,segmentName,java.util.Collections.emptyList());
    }
    /** With checkpoints, the newest clip recorded at one of them on this route is shown first. */
    public View createPreview(String routeId,String routeName,String destination,String segmentName,List<RouteCheckpoint> checkpoints) {
        String imageKey=ScenicCaptureStore.imageKeyFor(segmentName,destination);
        String caption=location(imageKey)+" · 샘플 사진";
        String detail="경로 선택 화면을 위한 공유 예시입니다. 다른 운전자의 실제 기록이 아닙니다.";
        String note="샘플 사진";
        java.util.ArrayList<String> ids=new java.util.ArrayList<>();for(RouteCheckpoint checkpoint:checkpoints)ids.add(checkpoint.id);
        ScenicCaptureStore.Record clip=routeId==null?null:store.latestForCheckpoints(routeId,ids);
        if(clip!=null){
            imageKey=clip.imageKey;caption=clip.segmentName+" · 자동 기록 시연";
            detail="이 길의 촬영 지점에서 자동으로 저장한 데모 기록입니다. 이 기기에만 저장되며 다른 운전자에게 전송되지 않습니다.";
            note="none".equals(imageKey)?"샘플 이미지 없음":note;
        } else for(ScenicCaptureStore.Record record:store.recordsForRoute(routeId)){imageKey=record.imageKey;caption=record.segmentName+(record.sharedPreview?" · 내 공유 미리보기":" · 내 경로의 장면");detail="이 경로에 추가한 내 데모 장면입니다. 이 기기에만 저장되며 다른 운전자에게 전송되지 않습니다.";break;}
        final String previewKey=imageKey;final String route=routeName;final String previewDetail=detail;
        LinearLayout preview=column();
        preview.addView(photo(previewKey,108,caption),size(-1,108));
        TextView noteView=text(note,14,MUTED,false);noteView.setPadding(0,dp(8),0,dp(8));preview.addView(noteView);
        preview.setContentDescription(caption+". 샘플 사진. 큰 미리보기 열기");tappable(preview);
        preview.setOnClickListener(v->{if(allowed())showPhoto(previewKey,route,previewDetail);});
        if(!checkpoints.isEmpty()){
            LinearLayout wrapper=column();wrapper.addView(preview);
            int count=routeId==null?0:store.countForCheckpoints(routeId,ids);
            TextView summary=text("저장 "+count+"개 · 촬영 지점 "+checkpoints.size()+"곳",20,INK,true);summary.setPadding(0,dp(16),0,0);wrapper.addView(summary);
            wrapper.addView(autoCaptureSwitch());
            return wrapper;
        }
        return preview;
    }
    /** Recording at checkpoints is the user's choice and stays off until switched on. */
    private View autoCaptureSwitch() {
        Switch toggle=new Switch(activity);toggle.setText("자동 기록 · 시연");toggle.setTextColor(INK);toggle.setTextSize(ScenicTypography.size(18));
        toggle.setTypeface(ScenicTypography.font(activity,600));styleSwitch(toggle);toggle.setChecked(store.autoCaptureEnabled());
        toggle.setOnCheckedChangeListener((view,checked)->{
            if(!host.canInteract()){view.setOnCheckedChangeListener(null);view.setChecked(store.autoCaptureEnabled());dismiss();return;}
            store.setAutoCaptureEnabled(checked);
        });
        LinearLayout box=column();box.addView(toggle,size(-1,-2));
        return box;
    }
    public boolean autoCaptureEnabled(){return store.autoCaptureEnabled();}
    /**
     * Called when the route reaches a checkpoint. A passive record: no popup, camera or server request,
     * and nothing happens unless the user turned automatic recording on.
     */
    public ScenicCaptureStore.Record saveCheckpointCapture(String routeId,String routeName,String destination,RouteCheckpoint checkpoint) {
        try {
            ScenicCaptureStore.Record record=store.saveAutomatic(routeId,routeName,destination,checkpoint,System.currentTimeMillis());
            renderPanel();return record;
        } catch(IllegalArgumentException|IllegalStateException blocked){return null;}
    }
    public int checkpointRecordCount(String routeId,List<RouteCheckpoint> checkpoints) {
        java.util.ArrayList<String> ids=new java.util.ArrayList<>();for(RouteCheckpoint checkpoint:checkpoints)ids.add(checkpoint.id);
        return routeId==null?0:store.countForCheckpoints(routeId,ids);
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
        body.addView(text("샘플 촬영",30,INK,true));gap(body,12);
        body.addView(text(draft.segmentName,22,INK,true));gap(body,8);
        body.addView(text(draft.destination+"까지",15,MUTED,false));gap(body,16);
        body.addView(photo(draft.imageKey,216,location(draft.imageKey)+" · 샘플"),size(-1,216));gap(body,8);
        addSharingSwitch(body);
        gap(body,16);
        TextView save=button("장면 저장",true,()->{
            if(!allowed())return;
            ScenicCaptureStore.Record record;try{record=store.save(draft,host.canInteract());}catch(IllegalStateException full){toast(full.getMessage());return;}
            dismiss();renderPanel();host.onCaptureSaved(record);toast("샘플 장면을 저장했어요.");
        });body.addView(save,size(-1,-2));gap(body,8);
        body.addView(button("닫기",false,this::dismiss),size(-1,-2));
        gap(body,16);body.addView(sourceAction(draft.imageKey));
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
        panel.addView(text("풍경 기록",34,INK,true));gap(panel,8);
        panel.addView(text("샘플 장면",14,MUTED,false));gap(panel,24);
        boolean selected=panelRouteId!=null&&!panelRouteId.trim().isEmpty();
        List<ScenicCaptureStore.Record> records=store.recordsForRoute(panelAllRecords?null:panelRouteId);
        panel.addView(text("저장한 장면 "+records.size()+"개",26,INK,true));gap(panel,16);
        if(records.isEmpty()) {
            LinearLayout empty=column();empty.setPadding(dp(24),dp(24),dp(24),dp(24));empty.setBackground(background(SOFT,0));
            empty.addView(text("아직 저장한 장면이 없어요",22,INK,true));gap(empty,16);
            if(selected)empty.addView(button("샘플 촬영 체험",true,()->showCapture(panelRouteId,panelRouteName,panelDestination,panelSegment)),size(-1,-2));
            else empty.addView(text("경로를 선택하면 샘플 촬영을 체험할 수 있어요.",16,MUTED,false));
            panel.addView(empty,size(-1,-2));gap(panel,24);
        } else for(ScenicCaptureStore.Record record:records) {
            LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(background(SOFT,LINE));
            card.addView(photo(record.imageKey,panelWide?160:176,record.sharedPreview?"공유 미리보기":"내 장면"),size(-1,panelWide?160:176));gap(card,16);
            card.addView(text(record.segmentName,24,INK,true));gap(card,8);
            card.addView(text(record.destination+"까지 · "+new SimpleDateFormat("M월 d일 HH:mm",Locale.KOREA).format(new Date(record.createdAt)),14,MUTED,false));gap(card,8);
            card.addView(text("샘플 사진 · "+credit(record.imageKey),12,MUTED,false));
            card.setContentDescription(record.segmentName+". "+record.destination+"까지의 경로에 저장한 데모 사진. 상세 열기");
            tappable(card);card.setOnClickListener(v->{if(allowed())showPhoto(record.imageKey,record.segmentName,record.destination+"까지 · "+(record.sharedPreview?"공유 미리보기":"내 장면")+"\n이 기기에만 저장된 데모 기록입니다.");});
            panel.addView(card,size(-1,-2));gap(panel,8);
            panel.addView(button("기록 삭제",false,()->{if(!allowed())return;dialog=new AlertDialog.Builder(activity).setTitle("이 샘플 기록을 삭제할까요?").setMessage("이 기기의 샘플 기록만 삭제합니다. 이미 저장한 리캡 영상은 유지됩니다.").setPositiveButton("삭제",(d,w)->{if(allowed()){store.remove(record.id);renderPanel();}}).setNegativeButton("취소",null).show();}),size(-1,-2));gap(panel,24);
        }
        if(selected&&!records.isEmpty()){
            TextView captureToggle=button(captureExpanded?"샘플 촬영 접기":"샘플 촬영 체험",false,()->{if(allowed()){captureExpanded=!captureExpanded;renderPanel();}});
            captureToggle.setContentDescription("샘플 촬영 체험 · "+(captureExpanded?"펼쳐짐 · 접기":"접힘 · 펼치기"));panel.addView(captureToggle,size(-1,-2));
            if(captureExpanded){gap(panel,16);String key=ScenicCaptureStore.imageKeyFor(panelSegment,panelDestination);
                panel.addView(text(value(panelSegment,"선택한 경로"),22,INK,true));gap(panel,8);
                panel.addView(text(value(panelDestination,"선택한 목적지")+"까지",15,MUTED,false));gap(panel,16);
                panel.addView(button("샘플 촬영 열기",true,()->showCapture(panelRouteId,panelRouteName,panelDestination,panelSegment)),size(-1,-2));
                panel.addView(sourceAction(key));
            }
        }
    }
    private void addSharingSwitch(LinearLayout body) {
        Switch toggle=new Switch(activity);toggle.setText("내 피드에 표시");toggle.setTextColor(INK);toggle.setTextSize(ScenicTypography.size(18));
        toggle.setTypeface(ScenicTypography.font(activity,600));styleSwitch(toggle);toggle.setChecked(store.sharingEnabled());
        toggle.setOnCheckedChangeListener((view,checked)->{
            if(!host.canInteract()){view.setOnCheckedChangeListener(null);view.setChecked(store.sharingEnabled());dismiss();return;}
            store.setSharingEnabled(checked);
        });body.addView(toggle,size(-1,-2));
    }
    private void showPhoto(String key,String title,String detail) {
        dismiss();LinearLayout body=dialogBody();body.addView(text(value(title,"공유 예시"),24,INK,true));gap(body,16);
        body.addView(photo(key,224,location(key)+" · 예시 이미지"),size(-1,224));gap(body,16);
        body.addView(text("샘플 사진",14,MUTED,true));gap(body,8);body.addView(text(detail,16,INK,false));gap(body,16);
        body.addView(sourceDetails(key));gap(body,16);body.addView(button("닫기",true,this::dismiss),size(-1,-2));showDialog(body);
    }
    private View sourceAction(String key) {
        LinearLayout source=column();if("none".equals(key))return source;
        TextView label=text(credit(key)+"  ·  출처 보기",14,ScenicDesign.ACCENT,false);label.setPadding(dp(8),dp(16),dp(8),dp(16));label.setMinHeight(dp(ScenicDesign.TOUCH_HEIGHT));label.setGravity(Gravity.CENTER_VERTICAL);label.setContentDescription("사진 출처 펼치기. "+credit(key));tappable(label);
        TextView details=sourceDetails(key);details.setVisibility(View.GONE);source.addView(label);source.addView(details);
        label.setOnClickListener(v->{if(allowed()){boolean expanded=details.getVisibility()!=View.VISIBLE;details.setVisibility(expanded?View.VISIBLE:View.GONE);label.setText(credit(key)+(expanded?"  ·  출처 닫기":"  ·  출처 보기"));label.setContentDescription((expanded?"사진 출처 접기. ":"사진 출처 펼치기. ")+credit(key));}});return source;
    }
    private TextView sourceDetails(String key) {
        if("none".equals(key)){TextView none=text("이 구간의 샘플 사진이 없어 표시하지 않습니다. 실제 차량 촬영이 아닙니다.",14,MUTED,false);return none;}
        String url="haeundae".equals(key)?"https://commons.wikimedia.org/wiki/File:Busan_City_Haeundae_Beach.jpg":
            "gwangalli".equals(key)?"https://commons.wikimedia.org/wiki/File:Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg":"https://commons.wikimedia.org/wiki/File:Cheongsapo.jpg";
        String license="haeundae".equals(key)?"https://www.kogl.or.kr/info/licenseType1.do":"gwangalli".equals(key)?"https://creativecommons.org/licenses/by-sa/4.0/":"https://creativecommons.org/licenses/by-sa/3.0/";
        TextView source=text(credit(key)+"\n공개 라이선스 사진을 화면에 맞춰 잘라 표시합니다.\n원본 사진\n"+url+"\n라이선스\n"+license,14,MUTED,false);Linkify.addLinks(source,Linkify.WEB_URLS);source.setLinkTextColor(ScenicDesign.ACCENT);source.setMovementMethod(LinkMovementMethod.getInstance());return source;
    }
    private View photo(String key,int height,String label) {
        FrameLayout frame=new FrameLayout(activity);frame.setBackground(background(SOFT,0));frame.setClipToOutline(true);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(location(key)+" 샘플 사진. 실제 차량 촬영 아님.");
        Bitmap bitmap=bitmap(key);if(bitmap!=null)image.setImageBitmap(bitmap);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView tag=text(label,14,WHITE,true);tag.setPadding(dp(12),dp(8),dp(12),dp(8));tag.setBackground(background(ScenicDesign.HERO,0));
        FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);badge.setMargins(dp(16),0,dp(16),dp(16));frame.addView(tag,badge);
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
    private static String location(String key){return "none".equals(key)?"촬영 구간":"gwangalli".equals(key)?"광안리와 광안대교":"coast".equals(key)?"청사포 해안":"해운대해수욕장";}
    private static String credit(String key){return "none".equals(key)?"샘플 이미지 없음": "gwangalli".equals(key)?"Masterhatch · CC BY-SA 4.0":"coast".equals(key)?"Andrewssi2 · CC BY-SA 3.0":"부산광역시 · 공공누리 제1유형";}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private void gap(LinearLayout parent,int height){parent.addView(new View(activity),size(1,height));}
    private GradientDrawable background(int color,int border){GradientDrawable result=new GradientDrawable();result.setColor(color);result.setCornerRadius(dp(ScenicDesign.CARD_RADIUS));if(border!=0)result.setStroke(dp(1),border);return result;}
    private TextView text(String value,int size,int color,boolean bold){TextView text=new TextView(activity);text.setText(value);text.setTextSize(ScenicTypography.size(size));text.setTextColor(color);text.setTypeface(ScenicTypography.font(activity,bold?600:400));text.setIncludeFontPadding(false);text.setLineSpacing(dp(4),1);return text;}
    private void styleSwitch(Switch toggle){toggle.setMinHeight(dp(ScenicDesign.TOUCH_HEIGHT));toggle.setSwitchMinWidth(dp(56));toggle.setSwitchPadding(dp(16));toggle.setThumbTintList(ColorStateList.valueOf(WHITE));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ScenicDesign.ACCENT,MUTED}));}
    private void tappable(View view){view.setFocusable(true);view.setClickable(true);view.setForeground(new RippleDrawable(ColorStateList.valueOf(ScenicDesign.RIPPLE),null,background(WHITE,0)));view.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});}
    private TextView button(String label,boolean primary,Runnable action){
        TextView button=text(label,primary?20:16,primary?WHITE:INK,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(16),dp(16),dp(16),dp(16));button.setMinHeight(dp(ScenicDesign.TOUCH_HEIGHT));
        GradientDrawable surface=background(primary?ScenicDesign.ACCENT:WHITE,primary?0:LINE);surface.setCornerRadius(dp(ScenicDesign.CONTROL_RADIUS));
        int ripple=primary?Color.argb(40,255,255,255):ScenicDesign.RIPPLE;button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple),surface,null));button.setFocusable(true);button.setClickable(true);button.setContentDescription(label);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
        button.setOnClickListener(v->action.run());return button;
    }
}
