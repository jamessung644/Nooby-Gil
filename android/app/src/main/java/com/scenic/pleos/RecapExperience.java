package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local monthly movie creation. Existing captures are explicitly labelled sample photographs. */
public final class RecapExperience {
    public interface Host {
        boolean canInteract();
        List<ScenicCaptureStore.Record> records();
        void onOpenRecords();
    }
    private static final int INK=0xff1c2924,MUTED=0xff6f7771,LINE=0xffe0e5df,SOFT=0xfff3f6f0,WHITE=0xffffffff,GREEN=0xff355947;
    private static final int MAX_FRAMES=6;
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final HashMap<String,Bitmap> bitmaps=new HashMap<>();
    private final ArrayList<View> editingControls=new ArrayList<>();
    private String month,mood,caption,status="";
    private boolean wide,active,destroyed,busy,saving,sampleMode;
    private int generation,progress;
    private AtomicBoolean cancellation;
    private Future<?> job;
    private File exportedFile;
    private Uri savedUri;
    private View root;
    private LinearLayout panel;
    private TextView statusView,generateButton,playButton,saveButton,posterCaption;
    private ProgressBar progressView;
    private AlertDialog dialog;
    private VideoView video;
    private EditText captionEditor;
    private boolean voicePending;
    private String voiceMonth,pendingVoiceText;

    public RecapExperience(Activity activity,Host host) {
        if(activity==null||host==null)throw new IllegalArgumentException("Activity and host are required");
        this.activity=activity;this.host=host;prefs=activity.getSharedPreferences("scenic_monthly_recap",0);
        month=monthKey(System.currentTimeMillis());loadChoices();
    }

    /** Returns a scrollable view on wide screens and a natural-height column otherwise. */
    public View createView(boolean wide) {
        if(destroyed)throw new IllegalStateException("Recap experience was destroyed");
        this.wide=wide;active=true;stopPlayback();applyPendingVoice();
        panel=column();panel.setPadding(dp(24),dp(20),dp(24),dp(28));panel.setBackgroundColor(WHITE);
        if(wide){ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));root=scroll;}else root=panel;
        final View mounted=root;
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){if(root==mounted&&!destroyed){active=true;main.removeCallbacks(safetyWatch);main.post(safetyWatch);}}
            public void onViewDetachedFromWindow(View view){if(root==mounted)pause();}
        });
        render();return root;
    }

    /** Called on tab leave, lifecycle pause, or a vehicle restriction. Never auto-resumes video. */
    public void pause() {
        active=false;main.removeCallbacks(safetyWatch);stopPlayback();
        if(dialog!=null){AlertDialog old=dialog;dialog=null;old.dismiss();}
        if(busy||saving)cancelWork("화면을 벗어나 제작을 멈췄어요. 정차 후 다시 시작할 수 있어요.");
    }

    public void destroy() {
        if(destroyed)return;pause();destroyed=true;worker.shutdownNow();
        // Keep queued completion callbacks: their destroyed guards clean up cancelled files/URIs.
        // They never update views after destruction. Only the repeating safety watch is removed.
        main.removeCallbacks(safetyWatch);
        // Views may still draw during Activity teardown, so let the bitmap cache be collected normally.
        bitmaps.clear();root=null;panel=null;
    }

    /** Forward Activity.onActivityResult here. Recognition begins only after a user tap. */
    public boolean handleActivityResult(int requestCode,int resultCode,Intent data){
        if(requestCode!=704)return false;
        if(!voicePending||destroyed)return true;voicePending=false;
        if(resultCode!=Activity.RESULT_OK||data==null||!month.equals(voiceMonth))return true;
        ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if(results==null||results.isEmpty()||results.get(0)==null)return true;
        pendingVoiceText=normalizedCaption(results.get(0));
        // Activity results can precede onResume. Apply only after the recap is active and safe.
        if(active){applyPendingVoice();render();}return true;
    }

    private void applyPendingVoice(){
        if(pendingVoiceText==null)return;String result=pendingVoiceText;pendingVoiceText=null;
        if(!active||destroyed||!host.canInteract()||!month.equals(voiceMonth)){status="정차 후 음성 메모를 다시 시작해 주세요.";return;}
        if(result.isEmpty())return;caption=result;prefs.edit().putString("caption_"+month,caption).apply();invalidateExport();status="음성 메모를 넣었어요. 내용을 확인하고 자유롭게 고쳐 보세요.";
    }

    private void requestVoice(){
        if(!allowed()||busy||saving)return;
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"이번 달 기억하고 싶은 순간을 말해 주세요");
        if(intent.resolveActivity(activity.getPackageManager())==null){voiceUnavailable();return;}
        voicePending=true;voiceMonth=month;pendingVoiceText=null;
        try{activity.startActivityForResult(intent,704);}catch(ActivityNotFoundException|SecurityException unavailable){voicePending=false;voiceUnavailable();}
    }

    private void voiceUnavailable(){
        toast("이 기기에는 음성 입력 앱이 없어요. 메모를 직접 입력해 주세요.");
        if(captionEditor!=null){captionEditor.requestFocus();InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(captionEditor,InputMethodManager.SHOW_IMPLICIT);}
    }

    private final Runnable safetyWatch=new Runnable(){public void run(){
        if(!active||destroyed)return;
        if(!host.canInteract()){
            stopPlayback();if(dialog!=null){AlertDialog old=dialog;dialog=null;old.dismiss();}
            if(busy||saving)cancelWork("정차 후 리캡을 만들고 재생할 수 있어요.");
            updateControls();
        }else updateControls();
        main.postDelayed(this,400);
    }};

    private void loadChoices(){
        mood=prefs.getString("mood_"+month,"lake");if(!"sea".equals(mood)&&!"night".equals(mood))mood="lake";
        caption=normalizedCaption(prefs.getString("caption_"+month,"이번 달, 기억하고 싶은 풍경."));
    }

    private List<ScenicCaptureStore.Record> monthlyRecords(){
        ArrayList<ScenicCaptureStore.Record> result=new ArrayList<>();List<ScenicCaptureStore.Record> all=host.records();
        if(all!=null)for(ScenicCaptureStore.Record record:all)if(record!=null&&month.equals(monthKey(record.createdAt)))result.add(record);
        Collections.sort(result,(a,b)->Long.compare(b.createdAt,a.createdAt));return result;
    }

    private ArrayList<RecapVideoExporter.Frame> frames(){
        ArrayList<RecapVideoExporter.Frame> result=new ArrayList<>();
        List<ScenicCaptureStore.Record> records=monthlyRecords();
        if(records.isEmpty()&&sampleMode){
            for(String key:new String[]{"haeundae","gwangalli","coast"})result.add(new RecapVideoExporter.Frame("scenic-demo/"+key+".jpg",location(key)+" · 샘플",credit(key)));
        }else for(ScenicCaptureStore.Record record:records){
            if(result.size()==MAX_FRAMES)break;String key=imageKey(record.imageKey);
            result.add(new RecapVideoExporter.Frame("scenic-demo/"+key+".jpg",clean(record.segmentName,70)+" · 샘플",credit(key)));
        }
        return result;
    }

    private void render(){
        if(panel==null||destroyed)return;
        panel.removeAllViews();editingControls.clear();captionEditor=null;posterCaption=null;
        generateButton=null;playButton=null;saveButton=null;statusView=null;progressView=null;
        List<ScenicCaptureStore.Record> records=monthlyRecords();
        if(!records.isEmpty())sampleMode=false;
        if(!busy&&!saving)restoreExport();
        TextView eyebrow=text("MONTHLY RECAP",12,GREEN,true);eyebrow.setLetterSpacing(.13f);panel.addView(eyebrow);gap(panel,8);
        panel.addView(text("이번 달을 한 편으로",28,INK,true));gap(panel,7);
        panel.addView(text("좋아한 풍경에 음악과 한 줄의 기억을 더해요.",15,MUTED,false));gap(panel,22);
        LinearLayout selector=row();selector.setGravity(Gravity.CENTER_VERTICAL);
        List<String> months=months();int index=months.indexOf(month);
        TextView previous=button("‹",false,()->selectAdjacent(1));previous.setContentDescription("이전 달 선택");previous.setTextSize(28);selector.addView(previous,size(52,52));
        TextView monthButton=button(monthLabel(month)+"  ▾",false,this::showMonths);LinearLayout.LayoutParams monthParams=new LinearLayout.LayoutParams(0,dp(52),1);monthParams.setMargins(dp(10),0,dp(10),0);selector.addView(monthButton,monthParams);
        TextView next=button("›",false,()->selectAdjacent(-1));next.setContentDescription("다음 달 선택");next.setTextSize(28);selector.addView(next,size(52,52));
        previous.setEnabled(index>=0&&index<months.size()-1&&!busy&&!saving);next.setEnabled(index>0&&!busy&&!saving);
        previous.setAlpha(previous.isEnabled()?1:.35f);next.setAlpha(next.isEnabled()?1:.35f);editingControls.add(monthButton);
        panel.addView(selector,size(-1,-2));gap(panel,18);

        if(records.isEmpty()&&!sampleMode){renderEmpty();return;}
        LinearLayout provenance=column();provenance.setPadding(dp(16),dp(13),dp(16),dp(13));provenance.setBackground(background(SOFT,0,12));
        provenance.addView(text(sampleMode?"샘플 리캡 체험":"저장한 데모 장면 "+records.size()+"개",14,GREEN,true));gap(provenance,4);
        provenance.addView(text(sampleMode?"공개 사진 3장으로 체험해요. 실제 나의 주행 기록은 아니에요.":"저장한 장면은 샘플 사진이에요. 실제 차량 촬영이나 다른 운전자의 영상이 아니에요.",13,MUTED,false));
        panel.addView(provenance,size(-1,-2));gap(panel,20);

        LinearLayout layout=wide?row():column();layout.setGravity(Gravity.TOP);
        View poster=poster(records);int posterWidth=wide?218:208;
        LinearLayout.LayoutParams posterParams=size(posterWidth,Math.round(posterWidth*16f/9f));
        if(!wide)posterParams.gravity=Gravity.CENTER_HORIZONTAL;
        layout.addView(poster,posterParams);
        LinearLayout details=column();LinearLayout.LayoutParams detailsParams=wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2);
        if(wide)detailsParams.setMargins(dp(24),0,0,0);else detailsParams.setMargins(0,dp(22),0,0);layout.addView(details,detailsParams);
        details.addView(text("영상에 담을 장면",18,INK,true));gap(details,6);
        details.addView(text(sampleMode?"해운대 · 광안리 · 청사포의 공개 사진":records.size()>MAX_FRAMES?"이번 달 저장한 장면 중 최근 "+MAX_FRAMES+"개를 사용해요.":"이 달에 저장한 "+records.size()+"개의 데모 장면을 사용해요.",13,MUTED,false));gap(details,12);
        LinearLayout thumbnails=row();List<String> keys=new ArrayList<>();
        if(sampleMode)Collections.addAll(keys,"haeundae","gwangalli","coast");else for(int i=0;i<Math.min(MAX_FRAMES,records.size());i++)keys.add(imageKey(records.get(i).imageKey));
        for(String key:keys){ImageView thumbnail=new ImageView(activity);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);thumbnail.setImageBitmap(bitmap(key));thumbnail.setContentDescription(location(key)+" 샘플 사진");thumbnail.setBackground(background(SOFT,0,8));thumbnail.setClipToOutline(true);LinearLayout.LayoutParams thumb=new LinearLayout.LayoutParams(0,dp(58),1);thumb.setMargins(0,0,dp(6),0);thumbnails.addView(thumbnail,thumb);}details.addView(thumbnails,size(-1,58));gap(details,19);
        details.addView(text("어떤 분위기로 남길까요?",17,INK,true));gap(details,10);
        LinearLayout moods=row();addMood(moods,"lake","잔잔한 오후");addMood(moods,"sea","산뜻한 바다");addMood(moods,"night","도시의 밤");details.addView(moods,size(-1,-2));gap(details,5);
        details.addView(text("앱에서 만든 오리지널 음악을 영상에 함께 담아요.",12,MUTED,false));gap(details,18);
        details.addView(text("기억하고 싶은 한 줄",17,INK,true));gap(details,8);
        captionEditor=new EditText(activity);captionEditor.setTextSize(15);captionEditor.setTextColor(INK);captionEditor.setHintTextColor(MUTED);captionEditor.setTypeface(ScenicTypography.font(activity,400));captionEditor.setGravity(Gravity.TOP);captionEditor.setMinLines(2);captionEditor.setMaxLines(3);captionEditor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});captionEditor.setPadding(dp(14),dp(12),dp(14),dp(12));captionEditor.setBackground(background(WHITE,LINE,10));captionEditor.setText(caption);captionEditor.setHint("예: 바다를 보며 오래 웃었던 오후");captionEditor.setContentDescription("영상에 넣을 기억 메모. 최대 60자");editingControls.add(captionEditor);
        captionEditor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){caption=s.toString();prefs.edit().putString("caption_"+month,caption).apply();invalidateExport();if(posterCaption!=null)posterCaption.setText(displayCaption());updateControls();}public void afterTextChanged(Editable e){}});
        details.addView(captionEditor,size(-1,-2));gap(details,5);details.addView(text("최대 60자 · 이 기기에 저장하고 영상에 자막으로 넣어요.",12,MUTED,false));gap(details,9);
        TextView voice=button("음성으로 메모",false,this::requestVoice);details.addView(voice,size(-1,-2));editingControls.add(voice);gap(details,5);
        details.addView(text("누르면 설치된 음성 입력 앱이 열려요. 음성은 해당 제공자가 처리할 수 있어요.",12,MUTED,false));gap(details,19);
        generateButton=button(exportedFile==null?"음악을 입혀 리캡 만들기":"새 리캡 만들기",true,this::generate);details.addView(generateButton,size(-1,-2));gap(details,8);
        TextView format=text("세로 9:16  ·  약 12초  ·  사진과 음악이 있는 영상",12,MUTED,false);format.setGravity(Gravity.CENTER);details.addView(format,size(-1,-2));
        panel.addView(layout,size(-1,-2));gap(panel,18);
        progressView=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progressView.setMax(100);progressView.setProgressTintList(ColorStateList.valueOf(GREEN));panel.addView(progressView,size(-1,5));gap(panel,9);
        statusView=text(status.isEmpty()?(exportedFile!=null?"리캡이 준비됐어요. 재생하거나 동영상으로 저장해 보세요.":"장면과 음악을 골랐으면 나만의 한 줄을 더해 보세요."):status,14,MUTED,false);statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(statusView);gap(panel,12);
        LinearLayout actions=row();playButton=button("리캡 재생",false,this::play);saveButton=button(savedUri==null?"동영상 저장":"저장 완료",false,this::saveToGallery);
        LinearLayout.LayoutParams actionA=new LinearLayout.LayoutParams(0,-2,1);actionA.setMargins(0,0,dp(8),0);actions.addView(playButton,actionA);actions.addView(saveButton,new LinearLayout.LayoutParams(0,-2,1));panel.addView(actions,size(-1,-2));gap(panel,18);
        TextView sources=button("사진 출처와 음악 정보",false,this::showSources);panel.addView(sources,size(-1,-2));editingControls.add(sources);gap(panel,7);
        panel.addView(text("샘플 사진의 출처와 라이선스는 영상 마지막 장면에도 표시해요. 공개 공유 서버로 전송하지 않아요.",12,MUTED,false));updateControls();
    }

    private void renderEmpty(){
        LinearLayout empty=column();empty.setGravity(Gravity.CENTER_HORIZONTAL);empty.setPadding(dp(28),dp(34),dp(28),dp(32));empty.setBackground(background(SOFT,0,18));
        TextView icon=text("▤",40,GREEN,false);empty.addView(icon);gap(empty,15);
        TextView title=text("아직 저장한 장면이 없어요",21,INK,true);title.setGravity(Gravity.CENTER);empty.addView(title);gap(empty,9);
        TextView message=text("풍경 기록에 장면을 남기면\n이곳에서 한 달의 리캡을 만들 수 있어요.",15,MUTED,false);message.setGravity(Gravity.CENTER);empty.addView(message);gap(empty,22);
        TextView sample=button("샘플 리캡 체험",true,()->{if(!allowed())return;sampleMode=true;status="공개 사진으로 체험 중이에요. 나의 주행 이력에는 추가하지 않아요.";render();});empty.addView(sample,size(-1,-2));editingControls.add(sample);gap(empty,9);
        TextView records=button("풍경 기록으로",false,()->{if(allowed())host.onOpenRecords();});empty.addView(records,size(-1,-2));editingControls.add(records);gap(empty,12);
        TextView note=text("공개 사진으로 먼저 체험할 수 있어요.\n실제 촬영 기록을 만들거나 추가하지 않아요.",12,MUTED,false);note.setGravity(Gravity.CENTER);empty.addView(note);panel.addView(empty,size(-1,-2));updateControls();
    }

    private View poster(List<ScenicCaptureStore.Record> records){
        String key=records.isEmpty()?"gwangalli":imageKey(records.get(0).imageKey);
        FrameLayout frame=new FrameLayout(activity);frame.setBackground(background(INK,0,18));frame.setClipToOutline(true);frame.setContentDescription("9대16 샘플 리캡 미리보기. 실제 차량 촬영 아님.");
        ImageView photo=new ImageView(activity);photo.setImageBitmap(bitmap(key));photo.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(activity);scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x251c2924,0x001c2924,0xe51c2924}));frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        TextView tag=text("샘플 리캡  ·  9:16",11,WHITE,true);tag.setPadding(dp(10),dp(7),dp(10),dp(7));tag.setBackground(background(0x88352947,0,20));FrameLayout.LayoutParams tagParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);tagParams.setMargins(dp(14),dp(16),dp(12),0);frame.addView(tag,tagParams);
        LinearLayout labels=column();labels.setPadding(dp(18),0,dp(18),dp(20));TextView brand=text("SCENIC",12,0xffd9e7da,true);brand.setLetterSpacing(.14f);labels.addView(brand);gap(labels,8);labels.addView(text(monthLabel(month),22,WHITE,true));gap(labels,7);posterCaption=text(displayCaption(),14,WHITE,false);posterCaption.setMaxLines(4);labels.addView(posterCaption);gap(labels,13);labels.addView(text("공개 사진 · 실제 주행 영상 아님",10,0xffe1e8e1,false));FrameLayout.LayoutParams labelParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);frame.addView(labels,labelParams);return frame;
    }

    private void addMood(LinearLayout row,String key,String label){
        TextView option=button(label,key.equals(mood),()->{if(!allowed()||busy||saving)return;mood=key;prefs.edit().putString("mood_"+month,mood).apply();invalidateExport();render();});option.setTextSize(13);option.setPadding(dp(6),dp(13),dp(6),dp(13));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.setMargins(0,0,dp(5),0);row.addView(option,params);editingControls.add(option);
    }

    private void selectAdjacent(int offset){if(!allowed()||busy||saving)return;List<String> months=months();int next=months.indexOf(month)+offset;if(next>=0&&next<months.size())selectMonth(months.get(next));}
    private void selectMonth(String value){if(!allowed()||busy||saving)return;month=value;sampleMode=false;status="";exportedFile=null;savedUri=null;loadChoices();render();}
    private List<String> months(){
        TreeSet<String> keys=new TreeSet<>(Collections.reverseOrder());Calendar calendar=Calendar.getInstance();for(int i=0;i<12;i++){keys.add(monthKey(calendar.getTimeInMillis()));calendar.add(Calendar.MONTH,-1);}
        List<ScenicCaptureStore.Record> all=host.records();if(all!=null)for(ScenicCaptureStore.Record record:all)if(record!=null)keys.add(monthKey(record.createdAt));keys.add(month);return new ArrayList<>(keys);
    }
    private void showMonths(){if(!allowed()||busy||saving)return;final List<String> keys=months();String[] labels=new String[keys.size()];for(int i=0;i<keys.size();i++)labels[i]=monthLabel(keys.get(i));dialog=new AlertDialog.Builder(activity).setTitle("어느 달을 돌아볼까요?").setSingleChoiceItems(labels,keys.indexOf(month),(dlg,which)->{dlg.dismiss();dialog=null;if(allowed())selectMonth(keys.get(which));}).setNegativeButton("닫기",null).create();dialog.show();}

    private void generate(){
        if(!allowed()||busy||saving)return;final ArrayList<RecapVideoExporter.Frame> selection=frames();if(selection.isEmpty()){toast("먼저 저장한 장면이나 샘플 리캡을 선택해 주세요.");return;}
        if(captionEditor!=null){captionEditor.clearFocus();InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(captionEditor.getWindowToken(),0);}
        stopPlayback();busy=true;progress=0;status="사진과 음악으로 리캡을 만들고 있어요. 잠시만 기다려 주세요.";updateControls();
        final int token=++generation;final AtomicBoolean cancel=new AtomicBoolean();cancellation=cancel;
        final String chosenMonth=month,chosenMood=mood,chosenCaption=monthLabel(month)+" · 샘플 리캡\n"+normalizedCaption(caption),signature=signature();
        final File output=new File(recapDir(),"SCENIC_SAMPLE_"+month.replace("-","")+"_"+System.currentTimeMillis()+".mp4");
        job=worker.submit(()->{
            try{
                RecapVideoExporter.export(activity.getApplicationContext(),selection,chosenMood,chosenCaption,output,()->cancel.get()||Thread.currentThread().isInterrupted(),percent->main.post(()->{if(current(token,cancel)){progress=percent;updateControls();}}));
                if(cancel.get()){output.delete();return;}
                main.post(()->{
                    if(!current(token,cancel)){output.delete();return;}
                    busy=false;job=null;exportedFile=output;savedUri=null;progress=100;
                    prefs.edit().putString("file_"+chosenMonth,output.getName()).putString("signature_"+chosenMonth,signature).remove("saved_"+chosenMonth).apply();
                    status="리캡이 완성됐어요. 음악이 담긴 실제 영상으로 재생할 수 있어요.";render();
                });
            }catch(Exception error){output.delete();main.post(()->{if(!current(token,cancel))return;busy=false;job=null;status="영상 제작을 마치지 못했어요. 정차 상태에서 다시 시도해 주세요.";android.util.Log.w("ScenicRecap","Local movie generation failed",error);updateControls();});}
        });
    }

    private void play(){
        if(!allowed()||busy||saving||exportedFile==null||!exportedFile.isFile())return;stopPlayback();
        LinearLayout body=column();body.setPadding(dp(20),dp(18),dp(20),dp(18));body.setBackgroundColor(WHITE);body.addView(text(monthLabel(month)+" · 샘플 리캡",20,INK,true));gap(body,5);body.addView(text("공개 사진으로 만든 영상 · 실제 차량 촬영 아님",12,MUTED,false));gap(body,12);
        int height=Math.max(dp(180),Math.min(dp(450),activity.getResources().getDisplayMetrics().heightPixels-dp(220)));FrameLayout stage=new FrameLayout(activity);stage.setBackgroundColor(INK);video=new VideoView(activity);final VideoView player=video;player.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN);FrameLayout.LayoutParams videoParams=new FrameLayout.LayoutParams(Math.round(height*9f/16f),height,Gravity.CENTER);stage.addView(player,videoParams);body.addView(stage,new LinearLayout.LayoutParams(-1,height));gap(body,12);
        TextView toggle=button("일시정지",false,()->{if(!allowed())return;if(video!=player)return;if(player.isPlaying()){player.pause();}else player.start();});body.addView(toggle,size(-1,-2));gap(body,8);body.addView(button("닫기",true,this::stopPlayback),size(-1,-2));
        dialog=new AlertDialog.Builder(activity).setView(body).create();dialog.setOnDismissListener(dlg->{if(video==player){try{player.stopPlayback();}catch(RuntimeException ignored){}video=null;}dialog=null;});dialog.show();Window window=dialog.getWindow();if(window!=null)window.setLayout(Math.min(dp(540),activity.getResources().getDisplayMetrics().widthPixels-dp(36)),-2);
        toggle.setOnClickListener(v->{if(!allowed()||video!=player)return;if(player.isPlaying()){player.pause();toggle.setText("이어 재생");}else{player.start();toggle.setText("일시정지");}});
        player.setOnPreparedListener(mp->{if(video!=player||!active||!host.canInteract()){stopPlayback();return;}mp.setLooping(false);player.start();});
        player.setOnCompletionListener(mp->{if(video==player)toggle.setText("다시 재생");});
        player.setOnErrorListener((mp,what,extra)->{if(video==player){stopPlayback();status="영상을 재생할 수 없어요. 새 리캡을 만들어 다시 시도해 주세요.";updateControls();}return true;});
        player.setVideoURI(Uri.fromFile(exportedFile));
    }

    private void saveToGallery(){
        if(!allowed()||busy||saving||exportedFile==null||!exportedFile.isFile())return;
        if(savedUri!=null){toast("이미 동영상으로 저장한 리캡이에요.");return;}
        if(Build.VERSION.SDK_INT<29){toast("리캡이 앱 안에 저장되어 있어요. 여기서 언제든 재생할 수 있어요.");return;}
        saving=true;status="동영상 앨범에 저장하고 있어요.";updateControls();final File source=exportedFile;final String chosenMonth=month;final int token=++generation;final AtomicBoolean cancel=new AtomicBoolean();cancellation=cancel;
        job=worker.submit(()->{
            Uri uri=null;try{
                ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,source.getName());values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/SCENIC");values.put(MediaStore.Video.Media.IS_PENDING,1);values.put(MediaStore.Video.Media.DESCRIPTION,"SCENIC 샘플 리캡. 공개 사진과 앱에서 합성한 오리지널 음악. 사진 출처와 라이선스는 마지막 장면에 포함.");
                uri=activity.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("MediaStore unavailable");
                try(InputStream in=new FileInputStream(source);OutputStream out=activity.getContentResolver().openOutputStream(uri)){if(out==null)throw new IllegalStateException("Video output unavailable");byte[] bytes=new byte[32768];int count;while((count=in.read(bytes))!=-1){if(cancel.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();out.write(bytes,0,count);}}
                if(cancel.get())throw new InterruptedException();values.clear();values.put(MediaStore.Video.Media.IS_PENDING,0);activity.getContentResolver().update(uri,values,null,null);
                final Uri published=uri;main.post(()->{if(!current(token,cancel)){deletePublished(published);return;}prefs.edit().putString("saved_"+chosenMonth,published.toString()).apply();saving=false;job=null;savedUri=published;status="동영상 앨범의 SCENIC 폴더에 저장했어요.";render();});
            }catch(Exception error){if(uri!=null)deletePublished(uri);main.post(()->{if(!current(token,cancel))return;saving=false;job=null;status="앨범에 저장하지 못했어요. 만든 영상은 앱 안에 그대로 있어요.";updateControls();});}
        });
    }

    private void showSources(){
        if(!allowed())return;String attribution;
        try(InputStream input=activity.getAssets().open("scenic-demo/ATTRIBUTION.txt");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] block=new byte[2048];int read;while((read=input.read(block))!=-1)bytes.write(block,0,read);attribution=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}catch(Exception unavailable){attribution=credit("haeundae")+"\n"+credit("gwangalli")+"\n"+credit("coast");}
        LinearLayout body=column();body.setPadding(dp(22),dp(20),dp(22),dp(20));body.addView(text("사진 출처와 음악",22,INK,true));gap(body,12);body.addView(text("리캡은 공개 사진을 세로 화면에 맞춰 자르고 자막을 더한 영상이에요. 실제 차량이나 다른 운전자의 촬영물이 아니에요.\n\n배경음악은 선택한 분위기에 맞춰 앱 안에서 합성한 오리지널 사운드예요. 외부 음원이나 음악 서비스는 사용하지 않아요.",14,INK,false));gap(body,18);TextView sources=text(attribution,12,MUTED,false);sources.setTextIsSelectable(true);body.addView(sources);ScrollView scroll=new ScrollView(activity);scroll.addView(body);dialog=new AlertDialog.Builder(activity).setView(scroll).setPositiveButton("확인",null).create();dialog.show();
    }

    private void updateControls(){
        boolean enabled=active&&!destroyed&&host.canInteract()&&!busy&&!saving;
        for(View control:editingControls){control.setEnabled(enabled);control.setAlpha(enabled?1:.5f);}
        if(generateButton!=null){generateButton.setEnabled(enabled);generateButton.setAlpha(enabled?1:.55f);generateButton.setText(busy?"리캡 만드는 중  "+progress+"%":exportedFile==null?"음악을 입혀 리캡 만들기":"새 리캡 만들기");}
        if(playButton!=null){playButton.setEnabled(enabled&&exportedFile!=null);playButton.setAlpha(playButton.isEnabled()?1:.4f);}
        if(saveButton!=null){saveButton.setEnabled(enabled&&exportedFile!=null&&savedUri==null);saveButton.setAlpha(saveButton.isEnabled()?1:.4f);saveButton.setText(saving?"저장 중…":savedUri!=null?"저장 완료":"동영상 저장");}
        if(progressView!=null){progressView.setVisibility(busy?View.VISIBLE:View.GONE);progressView.setProgress(progress);}
        if(statusView!=null){String message=!host.canInteract()?"정차 후 리캡을 만들고 재생할 수 있어요.":status;statusView.setText(message.isEmpty()?(exportedFile!=null?"리캡이 준비됐어요. 재생하거나 동영상으로 저장해 보세요.":"장면과 음악을 골랐으면 나만의 한 줄을 더해 보세요."):message);}
    }
    private boolean allowed(){if(active&&!destroyed&&host.canInteract())return true;stopPlayback();toast("정차 후 리캡을 만들고 재생해 주세요.");return false;}
    private boolean current(int token,AtomicBoolean cancel){if(destroyed||!active||token!=generation||cancel.get())return false;if(!host.canInteract()){cancelWork("정차 후 다시 시작해 주세요.");return false;}return true;}
    private void cancelWork(String message){generation++;if(cancellation!=null)cancellation.set(true);if(job!=null)job.cancel(true);job=null;busy=false;saving=false;status=message;updateControls();}
    private void stopPlayback(){VideoView old=video;video=null;if(old!=null){try{old.stopPlayback();}catch(RuntimeException ignored){}}if(old!=null&&dialog!=null){AlertDialog oldDialog=dialog;dialog=null;oldDialog.dismiss();}}
    private void invalidateExport(){if(exportedFile!=null||savedUri!=null){exportedFile=null;savedUri=null;status="내용이 바뀌었어요. 새 리캡을 만들어 주세요.";}}
    private File recapDir(){File directory=new File(activity.getFilesDir(),"recaps");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot create recap directory");return directory;}
    private void restoreExport(){
        if(frames().isEmpty()){exportedFile=null;savedUri=null;return;}
        String filename=prefs.getString("file_"+month,"");
        if(!signature().equals(prefs.getString("signature_"+month,""))||!filename.matches("SCENIC_SAMPLE_[A-Za-z0-9_.-]+\\.mp4")){exportedFile=null;savedUri=null;return;}
        File file=new File(recapDir(),filename);if(!file.isFile()||file.length()==0){exportedFile=null;savedUri=null;return;}exportedFile=file;String uri=prefs.getString("saved_"+month,"");savedUri=uri.isEmpty()?null:Uri.parse(uri);
    }
    private String signature(){
        StringBuilder data=new StringBuilder(month).append('|').append(mood).append('|').append(normalizedCaption(caption)).append('|').append(sampleMode);
        List<ScenicCaptureStore.Record> records=monthlyRecords();for(int i=0;i<Math.min(MAX_FRAMES,records.size());i++){ScenicCaptureStore.Record r=records.get(i);data.append('|').append(r.id).append('|').append(r.imageKey).append('|').append(r.segmentName);}
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.US,"%02x",b&255));return value.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private void deletePublished(Uri uri){try{activity.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored){}}
    private String displayCaption(){String normalized=normalizedCaption(caption);return normalized.isEmpty()?"기억하고 싶은 풍경":normalized;}
    // Match saved-video signatures to the caption restored from preferences, including multiline edits.
    private static String normalizedCaption(String value){return clean(value,60).trim();}
    private static String monthKey(long time){return new SimpleDateFormat("yyyy-MM",Locale.US).format(new Date(time));}
    private static String monthLabel(String value){String[] parts=value.split("-");return parts[0]+"년 "+Integer.parseInt(parts[1])+"월";}
    private static String clean(String value,int length){String cleaned=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").trim();return cleaned.length()>length?cleaned.substring(0,length):cleaned;}
    private static String imageKey(String key){return "gwangalli".equals(key)?key:"coast".equals(key)?key:"haeundae";}
    private static String location(String key){return "gwangalli".equals(key)?"광안리와 광안대교":"coast".equals(key)?"청사포 해안":"해운대해수욕장";}
    private static String credit(String key){return "gwangalli".equals(key)?"Masterhatch · CC BY-SA 4.0":"coast".equals(key)?"Andrewssi2 · CC BY-SA 3.0":"부산광역시 · 공공누리 제1유형";}
    private Bitmap bitmap(String key){if(bitmaps.containsKey(key))return bitmaps.get(key);try(InputStream input=activity.getAssets().open("scenic-demo/"+imageKey(key)+".jpg")){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;options.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap bitmap=BitmapFactory.decodeStream(input,null,options);bitmaps.put(key,bitmap);return bitmap;}catch(Exception unavailable){return null;}}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private void gap(LinearLayout parent,int height){parent.addView(new View(activity),size(1,height));}
    private GradientDrawable background(int color,int stroke,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(stroke!=0)shape.setStroke(dp(1),stroke);return shape;}
    private TextView text(String value,int sp,int color,boolean bold){TextView text=new TextView(activity);text.setText(value);text.setTextSize(sp);text.setTextColor(color);text.setTypeface(ScenicTypography.font(activity,bold?600:400));text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;}
    private TextView button(String value,boolean primary,Runnable action){TextView button=text(value,15,primary?WHITE:INK,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(12),dp(14),dp(12),dp(14));button.setMinHeight(dp(50));button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18355947),background(primary?GREEN:WHITE,primary?0:LINE,11),null));button.setFocusable(true);button.setClickable(true);button.setContentDescription(value);button.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(view,info);info.setClassName(Button.class.getName());}});button.setOnClickListener(v->action.run());return button;}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_SHORT).show();}
}
