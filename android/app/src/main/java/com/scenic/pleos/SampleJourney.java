package com.scenic.pleos;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Explicit simulation of location-triggered capture; never opens a camera or microphone. */
public final class SampleJourney {
    public interface Host {boolean canInteract();void capture(String segment);void sharePreview(boolean enabled);void soundtrack(boolean play);void openRecap();}
    private final Activity a;private final Host host;private final Handler handler=new Handler(Looper.getMainLooper());
    private AlertDialog dialog;private TextView heading,caption,state,action;private ProgressBar progress;private ImageView image;private Switch music,share;private int second;private boolean active;
    private final String[] segments={"해운대 해안 하이라이트","광안리와 광안대교","청사포 항구 도착"};
    private final String[] keys={"haeundae","gwangalli","coast"};
    public SampleJourney(Activity activity,Host host){a=activity;this.host=host;}
    public void show(){if(!host.canInteract())return;pause();second=0;LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setPadding(d(24),d(22),d(24),d(22));heading=text("자동 기록 체험",30);box.addView(heading);box.addView(text("부산 해안 · 18초 시연",15));
        image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams photo=new LinearLayout.LayoutParams(-1,d(220));photo.setMargins(0,d(18),0,d(12));box.addView(image,photo);showImage(0);
        caption=text("샘플 사진",14);box.addView(caption);
        progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(18);LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,d(5));bar.setMargins(0,d(16),0,d(12));box.addView(progress,bar);
        state=text("0 / 3 장면",24);box.addView(state);
        music=new Switch(a);music.setText("배경음 재생");music.setTextSize(ScenicTypography.size(18));music.setMinHeight(d(56));music.setChecked(a.getPreferences(0).getBoolean("scene_autoplay",false));box.addView(music);
        share=new Switch(a);share.setText("내 피드에 표시");share.setTextSize(ScenicTypography.size(18));share.setMinHeight(d(56));share.setChecked(false);box.addView(share);
        action=text("18초 시연 시작",20);action.setGravity(Gravity.CENTER);action.setPadding(d(16),d(16),d(16),d(16));action.setMinHeight(d(56));action.setTextColor(0xffffffff);action.setBackgroundColor(ScenicDesign.ACCENT);action.setFocusable(true);action.setOnClickListener(v->{if(second>=18){pause();host.openRecap();}else if(!active)start();});box.addView(action,new LinearLayout.LayoutParams(-1,-2));
        TextView details=text("사진 출처  ＋",14);details.setTextColor(ScenicDesign.ACCENT);details.setMinHeight(d(56));details.setGravity(Gravity.CENTER_VERTICAL);details.setFocusable(true);details.setContentDescription("사진 출처 · 접힘 · 펼치기");details.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});box.addView(details);
        TextView information=text("해운대: 부산광역시 · 공공누리 제1유형\n광안리: Masterhatch · CC BY-SA 4.0\n청사포: Andrewssi2 · CC BY-SA 3.0\n화면에 맞춰 잘라 표시한 공개 사진입니다.\n\n원본·라이선스\nhttps://commons.wikimedia.org/wiki/File:Busan_City_Haeundae_Beach.jpg\nhttps://www.kogl.or.kr/info/licenseType1.do\nhttps://commons.wikimedia.org/wiki/File:Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg\nhttps://creativecommons.org/licenses/by-sa/4.0/\nhttps://commons.wikimedia.org/wiki/File:Cheongsapo.jpg\nhttps://creativecommons.org/licenses/by-sa/3.0/",14);information.setVisibility(View.GONE);android.text.util.Linkify.addLinks(information,android.text.util.Linkify.WEB_URLS);information.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());information.setLinkTextColor(ScenicDesign.ACCENT);box.addView(information);
        details.setOnClickListener(v->{if(!host.canInteract()){pause();return;}boolean expanded=information.getVisibility()!=View.VISIBLE;information.setVisibility(expanded?View.VISIBLE:View.GONE);details.setText("사진 출처"+(expanded?"  −":"  ＋"));details.setContentDescription("사진 출처 · "+(expanded?"펼쳐짐 · 접기":"접힘 · 펼치기"));});
        ScrollView scroll=new ScrollView(a);scroll.addView(box);dialog=new AlertDialog.Builder(a).setView(scroll).setNegativeButton("닫기",(x,w)->pause()).create();dialog.setOnDismissListener(x->{active=false;handler.removeCallbacks(tick);host.soundtrack(false);});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(d(680),a.getResources().getDisplayMetrics().widthPixels-d(40)),-2);
    }
    private void start(){if(!host.canInteract())return;active=true;second=0;host.sharePreview(share.isChecked());host.soundtrack(music.isChecked());music.setEnabled(false);share.setEnabled(false);action.setText("자동 기록 중…");action.setEnabled(false);state.setText("0 / 3 장면 · 기록 대기");handler.postDelayed(tick,1000);}
    private final Runnable tick=new Runnable(){public void run(){if(!active)return;if(!host.canInteract()){pause();return;}second++;progress.setProgress(second);int scene=Math.min(2,second/6);if(second%6==0){host.capture(segments[second/6-1]);state.setText((second/6)+" / 3 장면 저장");caption.setText(segments[second/6-1]+" · 샘플 사진");}if(second>=18){active=false;host.soundtrack(false);heading.setText("기록 완료");action.setText("리캡 만들기  →");action.setEnabled(true);caption.setText("샘플 장면 3개");return;}if(second==6||second==12)showImage(scene);handler.postDelayed(this,1000);}};
    public void checkSafety(){if(dialog!=null&&!host.canInteract())pause();}
    public void pause(){active=false;handler.removeCallbacks(tick);host.soundtrack(false);AlertDialog old=dialog;dialog=null;if(old!=null)old.dismiss();}
    private void showImage(int index){try(InputStream in=a.getAssets().open("scenic-demo/"+keys[index]+".jpg")){image.setImageBitmap(BitmapFactory.decodeStream(in));image.setContentDescription(segments[index]+" · 공개 샘플 사진");}catch(IOException ignored){}}
    private int d(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(a);t.setText(s);t.setTextColor(ScenicDesign.INK);t.setTextSize(ScenicTypography.size(size));t.setTypeface(ScenicTypography.font(a,size>=18?600:400));t.setPadding(0,d(5),0,d(5));return t;}
}
