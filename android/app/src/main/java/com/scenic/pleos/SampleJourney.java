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
    public void show(){if(!host.canInteract())return;pause();second=0;LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setPadding(d(24),d(22),d(24),d(22));heading=text("자동 기록 체험",25);box.addView(heading);box.addView(text("부산 해안 · 18초 시연",15));
        image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams photo=new LinearLayout.LayoutParams(-1,d(200));photo.setMargins(0,d(18),0,d(12));box.addView(image,photo);showImage(0);
        caption=text("샘플 사진을 사용하는 위치 도착 시뮬레이션입니다.\n실제 차량 촬영·현재 위치 추적은 하지 않습니다.",14);box.addView(caption);
        progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(18);LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,d(5));bar.setMargins(0,d(16),0,d(12));box.addView(progress,bar);
        state=text("6초마다 장면을 자동 저장합니다. 저장된 장면은 리캡에 연결됩니다.",16);box.addView(state);
        music=new Switch(a);music.setText("시연 시작 시 분위기 음악 재생");music.setMinHeight(d(56));music.setChecked(a.getPreferences(0).getBoolean("scene_autoplay",false));box.addView(music);
        share=new Switch(a);share.setText("내 발견 피드에 공유 미리보기 표시");share.setMinHeight(d(56));share.setChecked(false);box.addView(share);box.addView(text("기기 내 미리보기 · 외부 업로드 없음",12));
        action=text("18초 시연 시작",18);action.setGravity(Gravity.CENTER);action.setPadding(d(16),d(16),d(16),d(16));action.setTextColor(0xffffffff);action.setBackgroundColor(0xff2d6659);action.setFocusable(true);action.setOnClickListener(v->{if(second>=18){pause();host.openRecap();}else if(!active)start();});box.addView(action,new LinearLayout.LayoutParams(-1,d(56)));
        ScrollView scroll=new ScrollView(a);scroll.addView(box);dialog=new AlertDialog.Builder(a).setView(scroll).setNegativeButton("닫기",(x,w)->pause()).create();dialog.setOnDismissListener(x->{active=false;handler.removeCallbacks(tick);host.soundtrack(false);});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(d(680),a.getResources().getDisplayMetrics().widthPixels-d(40)),-2);
    }
    private void start(){if(!host.canInteract())return;active=true;second=0;host.sharePreview(share.isChecked());host.soundtrack(music.isChecked());music.setEnabled(false);share.setEnabled(false);action.setText("풍경 구간을 지나고 있어요…");action.setEnabled(false);state.setText("해운대 해안 접근 · 자동 기록 대기");handler.postDelayed(tick,1000);}
    private final Runnable tick=new Runnable(){public void run(){if(!active)return;if(!host.canInteract()){pause();return;}second++;progress.setProgress(second);int scene=Math.min(2,second/6);if(second%6==0){host.capture(segments[second/6-1]);state.setText((second/6)+" / 3 장면 저장 · "+segments[second/6-1]);}if(second>=18){active=false;host.soundtrack(false);heading.setText("세 장면이 추억이 되었어요");action.setText("월간 리캡 만들기  →");action.setEnabled(true);caption.setText("자동 저장한 샘플 장면 3개가 이번 달 기록에 추가됐습니다.");return;}if(second==6||second==12)showImage(scene);handler.postDelayed(this,1000);}};
    public void checkSafety(){if(dialog!=null&&!host.canInteract())pause();}
    public void pause(){active=false;handler.removeCallbacks(tick);host.soundtrack(false);AlertDialog old=dialog;dialog=null;if(old!=null)old.dismiss();}
    private void showImage(int index){try(InputStream in=a.getAssets().open("scenic-demo/"+keys[index]+".jpg")){image.setImageBitmap(BitmapFactory.decodeStream(in));image.setContentDescription(segments[index]+" · 공개 샘플 사진");}catch(IOException ignored){}}
    private int d(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(a);t.setText(s);t.setTextColor(0xff172323);t.setTextSize(size);t.setTypeface(ScenicTypography.font(a,size>=18?600:400));t.setPadding(0,d(5),0,d(5));return t;}
}
