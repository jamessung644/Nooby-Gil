package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatically selected local travel memories. No manual editing workflow. */
public final class RecapExperience {
    public interface Host {
        boolean canInteract();
        List<ScenicCaptureStore.Record> records();
        void onOpenRecords();
        default String journeyId(){return "";}
        default String journeyTitle(){return "선택한 여정";}
    }
    private static final int INK=ScenicDesign.INK,MUTED=ScenicDesign.MUTED,LINE=ScenicDesign.LINE,SOFT=ScenicDesign.SOFT,WHITE=ScenicDesign.SURFACE,ACCENT=ScenicDesign.ACCENT;
    private static final int VIDEO_PICK=705,FOLDER_PICK=706;
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final PersonalVideoStore personalVideos;
    private final RecapOriginalDownloads originalDownloads;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<String> pending=new HashSet<>(),failedAnalysis=new HashSet<>(),failedSources=new HashSet<>();
    private final LinkedHashMap<String,Bitmap> bitmaps=new LinkedHashMap<>();
    private final ArrayList<View> controls=new ArrayList<>();
    private volatile boolean destroyed;
    private boolean active,wide,archive,scanning,saving,collecting;
    private int imports,archivePage,movieProgress;
    private String status="",sourceError="",pickerJourneyId="",pickerJourneyTitle="",movieStory="";
    private RecapStoryPlanner.Story opened;
    private LinearLayout panel;
    private View root;
    private FrameLayout experienceRoot;
    private ScrollView feedScroll;
    private RecapStoryView storyView;
    private List<MemoryPhoto> visibleStoryPhotos=Collections.emptyList();
    private AlertDialog dialog;
    private VideoView video;
    private AtomicBoolean movieCancel;
    private final HashSet<String> moviesFailed=new HashSet<>();
    private static final class MemoryPhoto {
        final String path,asset,label,credit;
        final PersonalVideoStore.Record video;
        final AutoRecapAnalyzer.Scene scene;
        MemoryPhoto(String path,String asset,String label,String credit,PersonalVideoStore.Record video,AutoRecapAnalyzer.Scene scene){this.path=path;this.asset=asset;this.label=label;this.credit=credit;this.video=video;this.scene=scene;}
    }
    public RecapExperience(Activity activity,Host host){
        this.activity=Objects.requireNonNull(activity);this.host=Objects.requireNonNull(host);
        prefs=activity.getSharedPreferences("scenic_monthly_recap",0);
        personalVideos=new PersonalVideoStore(new PersonalVideoStore.Persistence(){public String read(){return prefs.getString("personal_videos_v1","");}public void write(String value){prefs.edit().putString("personal_videos_v1",value).apply();}});
        originalDownloads=new RecapOriginalDownloads(activity);
    }
    public View createView(boolean wide){
        if(destroyed)throw new IllegalStateException("Recap destroyed");
        this.wide=wide&&activity.getResources().getConfiguration().screenWidthDp>=840&&activity.getResources().getConfiguration().fontScale<1.3f;
        active=true;stopPlayback();if(storyView!=null){storyView.destroy();storyView=null;}panel=column();panel.setPadding(dp(8),dp(8),dp(8),dp(24));
        experienceRoot=new FrameLayout(activity);feedScroll=new ScrollView(activity);feedScroll.setFillViewport(true);feedScroll.setVerticalScrollBarEnabled(false);feedScroll.addView(panel);experienceRoot.addView(feedScroll,new FrameLayout.LayoutParams(-1,-1));root=experienceRoot;
        final View mounted=root;root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){if(root==mounted&&!destroyed){active=true;main.removeCallbacks(watch);main.post(watch);if(storyView!=null)storyView.resume();}}public void onViewDetachedFromWindow(View v){if(root==mounted)pause();}});
        refreshOpened();render();analyzeExisting();scanFolder();return root;
    }
    public void pause(){active=false;main.removeCallbacks(watch);stopPlayback();if(storyView!=null)storyView.pause();if(dialog!=null){dialog.dismiss();dialog=null;}if(movieCancel!=null)movieCancel.set(true);}
    public void onAppResume(){if(destroyed)return;collecting=true;main.removeCallbacks(sourceWatch);main.post(sourceWatch);analyzeExisting();}
    public void onAppPause(){collecting=false;main.removeCallbacks(sourceWatch);pause();}
    private final Runnable sourceWatch=new Runnable(){public void run(){if(!collecting||destroyed)return;scanFolder();main.postDelayed(this,60000);}};
    public void destroy(){if(destroyed)return;onAppPause();destroyed=true;if(storyView!=null)storyView.destroy();originalDownloads.destroy();worker.shutdownNow();bitmaps.clear();panel=null;root=null;}
    public boolean handleBack(){if(dialog!=null){stopPlayback();if(dialog!=null)dialog.dismiss();dialog=null;return true;}if(opened!=null){opened=null;status="";render();scrollTop();return true;}if(archive){archive=false;status="";render();scrollTop();return true;}return false;}
    private void scrollTop(){if(feedScroll!=null){ScrollView scroll=feedScroll;scroll.post(()->scroll.scrollTo(0,0));}}
    private final Runnable watch=new Runnable(){public void run(){if(!active||destroyed)return;if(!host.canInteract()){stopPlayback();if(dialog!=null){dialog.dismiss();dialog=null;}if(movieCancel!=null)movieCancel.set(true);}for(View control:controls){control.setEnabled(host.canInteract()&&!saving&&!Boolean.FALSE.equals(control.getTag()));control.setAlpha(control.isEnabled()?1:.45f);}main.postDelayed(this,500);}};
    public boolean handleActivityResult(int request,int result,Intent data){
        if(request!=VIDEO_PICK&&request!=FOLDER_PICK)return false;
        if(result!=Activity.RESULT_OK||data==null||destroyed)return true;
        if(request==FOLDER_PICK){Uri uri=data.getData();if(uri==null)return true;try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);prefs.edit().putString("recap_source_tree",uri.toString()).apply();sourceError="";status="폴더를 연결했어요. 여행 장면을 모으고 있어요.";scanFolder();}catch(SecurityException denied){status="폴더를 읽을 수 없어요. 다시 연결해 주세요.";}if(active)render();return true;}
        ArrayList<Uri> selected=new ArrayList<>();if(data.getData()!=null)selected.add(data.getData());ClipData clips=data.getClipData();if(clips!=null)for(int i=0;i<Math.min(12,clips.getItemCount());i++)selected.add(clips.getItemAt(i).getUri());
        for(Uri uri:selected)try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);queueVideo(uri,"",0,pickerJourneyId,pickerJourneyTitle,true);}catch(SecurityException denied){status="영상 접근 권한을 유지할 수 없어요. 다시 선택해 주세요.";}
        if(active)render();return true;
    }
    public void importLegacyVideo(Uri uri,String title,long durationMs,long capturedAt,String journeyId,String journeyTitle){queueVideo(uri,title,capturedAt,journeyId,journeyTitle,true);}
    public void migrateLegacyVideo(Uri uri,String title,long durationMs,long capturedAt,String journeyId,String journeyTitle){if(uri!=null&&!prefs.getBoolean(legacyKey(uri),false))queueVideo(uri,title,capturedAt,journeyId,journeyTitle,false);}
    private static String legacyKey(Uri uri){return "legacy_import_"+hash(uri.toString());}
    private void pickVideos(){if(!allowed())return;pickerJourneyId=host.journeyId();pickerJourneyTitle=host.journeyTitle();Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);launchPicker(intent,VIDEO_PICK);}
    private void connectFolder(){if(!allowed())return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);launchPicker(intent,FOLDER_PICK);}
    private void launchPicker(Intent intent,int request){try{activity.startActivityForResult(new Intent(intent).setPackage("com.android.documentsui"),request);}catch(ActivityNotFoundException absent){try{activity.startActivityForResult(intent,request);}catch(ActivityNotFoundException error){toast("이 기기에는 파일 선택기가 없어요.");}}}
    private void scanFolder(){
        String source=prefs.getString("recap_source_tree","");if(destroyed||scanning||source.isEmpty())return;scanning=true;
        Set<String> excluded=new HashSet<>(pending);excluded.addAll(failedSources);for(PersonalVideoStore.Record record:personalVideos.all())excluded.add(record.uri);
        worker.submit(()->{try{List<AutoRecapSource.SourceVideo> found=AutoRecapSource.scan(activity,Uri.parse(source),excluded,()->destroyed||Thread.currentThread().isInterrupted());main.post(()->{scanning=false;if(destroyed)return;sourceError="";int added=0;for(AutoRecapSource.SourceVideo file:found){if(!hasUri(file.uri)&&!pending.contains(file.uri.toString())&&added++<12)queueVideo(file.uri,file.title,0,"","",false);}if(active)render();});}catch(Exception error){main.post(()->{scanning=false;if(destroyed)return;sourceError="연결한 폴더를 읽을 수 없어요. 폴더를 다시 연결해 주세요.";if(active)render();});}});
    }
    private boolean hasUri(Uri uri){for(PersonalVideoStore.Record r:personalVideos.all())if(r.uri.equals(uri.toString()))return true;return false;}
    private void queueVideo(Uri uri,String title,long capturedAt,String journeyId,String journeyTitle,boolean reveal){
        if(destroyed||uri==null||!"content".equals(uri.getScheme())||hasUri(uri)||!pending.add(uri.toString()))return;
        imports++;if(active)render();
        worker.submit(()->{MediaMetadataRetriever reader=new MediaMetadataRetriever();Bitmap thumb=null;try{
            reader.setDataSource(activity,uri);long duration=Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(duration<1000||duration>3600000)throw new IllegalArgumentException("1초~1시간 길이의 영상을 연결해 주세요.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=activity.getContentResolver().openInputStream(uri)){if(input==null)throw new IOException();byte[] buffer=new byte[65536];int count;long total=0;while((count=input.read(buffer))!=-1){if(destroyed||Thread.currentThread().isInterrupted())throw new InterruptedException();total+=count;if(total>512L*1024*1024)throw new IllegalArgumentException("512MB 이하의 영상을 연결해 주세요.");digest.update(buffer,0,count);}}
            String id=hex(digest.digest());String name=clean(title,120);if(name.isEmpty())try(Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=clean(c.getString(0),120);}String filename=name.isEmpty()?"여행 영상":name;
            long mediaDate=PersonalVideoStore.parseMediaDate(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));long date=capturedAt>0?capturedAt:mediaDate>0?mediaDate:System.currentTimeMillis();String dateSource=capturedAt>0?"record":mediaDate>0?"media":"import";
            String place=clean(journeyTitle,180),group=clean(journeyId,180);if(place.isEmpty()||place.equals("선택한 여정")){place=placeFromMetadata(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));group=place.isEmpty()?"":"place:"+place;}
            PersonalVideoStore.Record draft=new PersonalVideoStore.Record(id,uri.toString(),filename,group,place,duration,date,0,Math.min(4000,duration),true,dateSource);
            thumb=reader.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270);if(thumb!=null)try(OutputStream output=new FileOutputStream(new File(thumbnailDir(),id+".jpg"))){thumb.compress(Bitmap.CompressFormat.JPEG,85,output);}
            boolean analyzed=true;try{AutoRecapAnalyzer.analyze(activity,draft,()->destroyed||Thread.currentThread().isInterrupted());}catch(Exception error){if(destroyed||Thread.currentThread().isInterrupted())throw error;analyzed=false;}
            final String finalPlace=place,finalGroup=group;final boolean analysisOk=analyzed;main.post(()->{pending.remove(uri.toString());imports=Math.max(0,imports-1);if(destroyed)return;personalVideos.add(id,uri.toString(),filename,finalGroup,finalPlace,duration,date,dateSource);prefs.edit().putBoolean(legacyKey(uri),true).apply();status=analysisOk?"":"장면 선별을 마치지 못했어요. 모든 기록에서 원본을 볼 수 있어요.";if(!analysisOk)failedAnalysis.add(id);if(reveal){archive=false;opened=null;}if(active){refreshOpened();render();}});
        }catch(Exception error){main.post(()->{pending.remove(uri.toString());failedSources.add(uri.toString());imports=Math.max(0,imports-1);if(destroyed)return;status=error instanceof IllegalArgumentException?error.getMessage():"영상을 읽지 못했어요. 원본 파일을 다시 연결해 주세요.";if(active)render();});}finally{if(thumb!=null)thumb.recycle();try{reader.release();}catch(Exception ignored){}}});
    }
    private void analyzeExisting(){for(PersonalVideoStore.Record record:personalVideos.all())if(AutoRecapAnalyzer.load(activity,record)==null&&!pending.contains(record.id)&&!failedAnalysis.contains(record.id)){pending.add(record.id);imports++;worker.submit(()->{boolean ok=true;try{AutoRecapAnalyzer.analyze(activity,record,()->destroyed||Thread.currentThread().isInterrupted());}catch(Exception error){ok=false;}final boolean success=ok;main.post(()->{pending.remove(record.id);imports=Math.max(0,imports-1);if(destroyed)return;if(!success)failedAnalysis.add(record.id);if(active){refreshOpened();render();}});});}}
    private List<RecapStoryPlanner.Story> stories(){return RecapStoryPlanner.plan(personalVideos.all(),host.records(),System.currentTimeMillis());}
    private void refreshOpened(){if(opened==null)return;for(RecapStoryPlanner.Story story:stories())if(story.id.equals(opened.id)){opened=story;return;}if(!opened.sample)opened=null;}
    private List<MemoryPhoto> photos(RecapStoryPlanner.Story story){
        // The legacy test input was itself an exported sample recap, with the old UI
        // already burned into its frames. Rebuild that demo from its credited source
        // photographs; keep the imported original unchanged in the archive.
        if(legacySampleImport(story)){
            ArrayList<MemoryPhoto> cleanSample=new ArrayList<>();
            for(String key:new String[]{"haeundae","gwangalli","coast"})cleanSample.add(new MemoryPhoto(null,"scenic-demo/"+key+".jpg",key.equals("haeundae")?"해운대":key.equals("gwangalli")?"광안리":"청사포",credit(key),null,null));
            return cleanSample;
        }
        ArrayList<MemoryPhoto> candidates=new ArrayList<>();for(RecapStoryPlanner.Moment moment:story.moments){if(moment.video!=null){AutoRecapAnalyzer.Result result=AutoRecapAnalyzer.load(activity,moment.video);if(result!=null)for(AutoRecapAnalyzer.Scene scene:result.scenes)candidates.add(new MemoryPhoto(scene.photoPath,null,moment.video.title,"",moment.video,scene));}else if(moment.capture!=null&&!"none".equals(moment.capture.imageKey)){String key=imageKey(moment.capture.imageKey);candidates.add(new MemoryPhoto(null,"scenic-demo/"+key+".jpg",moment.capture.segmentName,credit(key),null,null));}}
        if(candidates.size()<=6)return candidates;
        ArrayList<MemoryPhoto> ranked=new ArrayList<>(candidates);ranked.sort((a,b)->Double.compare(b.scene==null?1:b.scene.quality,a.scene==null?1:a.scene.quality));ArrayList<MemoryPhoto> chosen=new ArrayList<>();Set<String> seen=new HashSet<>();for(MemoryPhoto photo:ranked){String key=photo.video==null?photo.asset:photo.video.id;if(seen.add(key)){chosen.add(photo);if(chosen.size()==6)break;}}for(MemoryPhoto photo:ranked)if(chosen.size()<6&&!chosen.contains(photo))chosen.add(photo);chosen.sort(Comparator.comparingLong((MemoryPhoto p)->p.video==null?0:p.video.createdAt).thenComparingLong(p->p.scene==null?0:p.scene.timestampMs));return chosen;
    }
    private boolean legacySampleImport(RecapStoryPlanner.Story story){return story.moments.size()==1&&story.moments.get(0).video!=null&&"SCENIC_TEST_SAMPLE.mp4".equals(story.moments.get(0).video.title);}
    private String storyMemoryLabel(RecapStoryPlanner.Story story){return legacySampleImport(story)?"샘플 리캡":story.memoryLabel;}
    private void render(){
        if(panel==null||destroyed)return;
        if(opened!=null){feedScroll.setVisibility(View.GONE);renderStory(opened);return;}
        if(storyView!=null){storyView.destroy();experienceRoot.removeView(storyView);storyView=null;visibleStoryPhotos=Collections.emptyList();}
        feedScroll.setVisibility(View.VISIBLE);panel.removeAllViews();controls.clear();if(archive){renderArchive();return;}
        panel.addView(text("다시 꺼내보는 여행",32,INK,true));gap(panel,8);panel.addView(text("자동으로 모은 그날의 순간들",17,MUTED,false));gap(panel,24);
        ArrayList<RecapStoryPlanner.Story> real=new ArrayList<>(),samples=new ArrayList<>();for(RecapStoryPlanner.Story story:stories())(story.sample?samples:real).add(story);
        if(real.isEmpty())renderEmpty();else{
            panel.addView(storyCard(real.get(0),true),size(-1,-2));gap(panel,24);
            if(real.size()>1){panel.addView(text("지난 여행의 기억",24,INK,true));gap(panel,16);for(int i=1;i<Math.min(5,real.size());){LinearLayout line=wide?row():column();for(int col=0;col<(wide?2:1)&&i<Math.min(5,real.size());col++,i++){LinearLayout.LayoutParams p=wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2);if(col>0)p.setMargins(dp(16),0,0,0);line.addView(storyCard(real.get(i),false),p);}panel.addView(line,size(-1,-2));gap(panel,16);}}
        }
        if(imports>0||scanning){panel.addView(text(imports>0?"여행 장면을 고르고 있어요 · "+imports+"개":"새 여행 영상을 확인하고 있어요",16,ACCENT,false));gap(panel,16);}
        addStatus();gap(panel,8);renderSourceControls();
        if(real.isEmpty()){gap(panel,16);RecapStoryPlanner.Story sample=samples.isEmpty()?sampleStory():samples.get(0);panel.addView(button("샘플 추억 보기",false,()->openStory(sample)),size(-1,-2));}
        gap(panel,24);panel.addView(button("모든 기록 보기",false,()->{if(!allowed())return;archive=true;archivePage=0;status="";render();scrollTop();}),size(-1,-2));
    }
    private void renderEmpty(){
        LinearLayout card=column();card.setPadding(dp(28),dp(32),dp(28),dp(32));card.setBackground(background(WHITE,LINE,ScenicDesign.CARD_RADIUS));card.addView(text("여행이 끝나면,\n추억이 시작돼요",30,INK,true));gap(card,16);card.addView(text("여행 영상에서 좋은 장면을 골라 사진과 리캡으로 모아둘게요.",18,MUTED,false));gap(card,24);
        card.addView(button(prefs.getString("recap_source_tree","").isEmpty()?"빌트인캠 폴더 연결":"연결한 영상 확인",true,()->{if(prefs.getString("recap_source_tree","").isEmpty())connectFolder();else if(allowed())scanFolder();}),size(-1,-2));panel.addView(card,size(-1,-2));gap(panel,24);
    }
    private View storyCard(RecapStoryPlanner.Story story,boolean hero){
        boolean landscape=hero&&wide;
        LinearLayout card=landscape?row():column();card.setGravity(Gravity.CENTER_VERTICAL);card.setBackground(background(WHITE,LINE,ScenicDesign.CARD_RADIUS));card.setClipToOutline(true);
        List<MemoryPhoto> selected=photos(story);FrameLayout artwork=new FrameLayout(activity);artwork.setBackgroundColor(SOFT);
        ImageView cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap image=selected.isEmpty()?originalThumbnail(story):photoBitmap(selected.get(0));if(image!=null)cover.setImageBitmap(image);cover.setContentDescription(story.title);artwork.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        TextView badge=text(storyMemoryLabel(story),18,WHITE,true);badge.setPadding(dp(14),dp(9),dp(14),dp(9));badge.setBackground(background(ACCENT,0,8));FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);badgeParams.setMargins(dp(16),dp(16),dp(16),0);artwork.addView(badge,badgeParams);
        card.addView(artwork,landscape?new LinearLayout.LayoutParams(0,dp(410),1.4f):size(-1,hero?270:250));
        LinearLayout details=column();details.setPadding(dp(28),dp(24),dp(28),dp(24));details.addView(text(story.title,hero?32:24,INK,true));gap(details,14);details.addView(text(story.dateLabel,hero?20:17,MUTED,false));if(!story.place.isEmpty()){gap(details,8);details.addView(text(story.place,18,MUTED,false));}gap(details,24);details.addView(button(selected.isEmpty()?"기록 보기":"추억 보기",hero,()->openStory(story)),size(-1,-2));card.addView(details,landscape?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));return card;
    }
    private void renderSourceControls(){boolean connected=!prefs.getString("recap_source_tree","").isEmpty();LinearLayout actions=wide?row():column();if(connected||archive||!personalVideos.all().isEmpty())addAction(actions,button(connected?"빌트인캠 폴더 변경":"빌트인캠 폴더 연결",false,this::connectFolder));addAction(actions,button("여행 영상 연결",false,this::pickVideos));panel.addView(actions,size(-1,-2));if(!sourceError.isEmpty()){gap(panel,8);panel.addView(text(sourceError,16,MUTED,false));}}
    private void renderArchive(){
        panel.addView(button("‹ 리캡으로 돌아가기",false,()->{archive=false;render();scrollTop();}),size(-1,-2));gap(panel,24);panel.addView(text("모든 기록",32,INK,true));gap(panel,24);List<RecapStoryPlanner.Story> all=new ArrayList<>(stories());all.sort((a,b)->Long.compare(b.endedAt,a.endedAt));int pageSize=8;archivePage=Math.max(0,Math.min(archivePage,Math.max(0,(all.size()-1)/pageSize)));if(all.isEmpty()){panel.addView(text("아직 저장된 여행이 없어요",22,INK,true));gap(panel,24);}else for(int i=archivePage*pageSize;i<Math.min(all.size(),(archivePage+1)*pageSize);){LinearLayout line=wide?row():column();for(int col=0;col<(wide?2:1)&&i<Math.min(all.size(),(archivePage+1)*pageSize);col++,i++){LinearLayout.LayoutParams p=wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2);if(col>0)p.setMargins(dp(16),0,0,0);line.addView(storyCard(all.get(i),false),p);}panel.addView(line,size(-1,-2));gap(panel,16);}
        if(all.size()>pageSize){LinearLayout pages=row();TextView before=button("이전",false,()->{archivePage--;render();scrollTop();}),next=button("다음",false,()->{archivePage++;render();scrollTop();});before.setTag(archivePage>0);next.setTag((archivePage+1)*pageSize<all.size());before.setEnabled(archivePage>0);next.setEnabled((archivePage+1)*pageSize<all.size());pages.addView(before,new LinearLayout.LayoutParams(0,-2,1));TextView page=text((archivePage+1)+" / "+((all.size()+pageSize-1)/pageSize),17,MUTED,false);page.setGravity(Gravity.CENTER);pages.addView(page,size(96,-2));pages.addView(next,new LinearLayout.LayoutParams(0,-2,1));panel.addView(pages);gap(panel,24);}
        renderArchiveOriginals(all.subList(archivePage*pageSize,Math.min(all.size(),(archivePage+1)*pageSize)));
        panel.addView(button("주행 기록·북마크",false,()->{if(allowed())host.onOpenRecords();}),size(-1,-2));gap(panel,16);renderSourceControls();addStatus();
    }
    private void renderArchiveOriginals(List<RecapStoryPlanner.Story> page){
        LinkedHashMap<String,PersonalVideoStore.Record> originals=new LinkedHashMap<>();
        for(RecapStoryPlanner.Story story:page)for(RecapStoryPlanner.Moment moment:story.moments)if(moment.video!=null)originals.put(moment.video.id,moment.video);
        if(originals.isEmpty())return;
        gap(panel,8);panel.addView(text("원본 영상",24,INK,true));gap(panel,12);
        for(PersonalVideoStore.Record original:originals.values()){
            LinearLayout file=row();file.setGravity(Gravity.CENTER_VERTICAL);file.setPadding(dp(18),dp(12),dp(12),dp(12));file.setBackground(background(WHITE,LINE,ScenicDesign.CONTROL_RADIUS));
            TextView label=text(original.title,18,INK,false);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);file.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            TextView download=button(originalDownloads.saving()?"저장 중…":"다운로드",false,()->downloadOriginal(original));download.setTextSize(ScenicTypography.size(14));download.setPadding(dp(14),dp(8),dp(14),dp(8));download.setContentDescription(original.title+" 원본 영상 다운로드");download.setTag(!originalDownloads.saving());download.setEnabled(!originalDownloads.saving());
            LinearLayout.LayoutParams action=size(-2,-2);action.setMargins(dp(16),0,0,0);file.addView(download,action);panel.addView(file,size(-1,-2));gap(panel,8);
        }
        gap(panel,16);
    }
    private void downloadOriginal(PersonalVideoStore.Record original){
        if(!allowed()||originalDownloads.saving())return;
        status="원본 영상을 저장하고 있어요";
        originalDownloads.save(original,(success,message)->{if(destroyed)return;status=message;if(active){render();toast(message);}});
        render();
    }
    private void openStory(RecapStoryPlanner.Story story){if(!allowed())return;stopPlayback();opened=story;status="";render();}
    private void renderStory(RecapStoryPlanner.Story story){
        if(storyView==null){
            storyView=new RecapStoryView(activity,new RecapStoryView.Host(){
                public boolean canInteract(){return active&&!destroyed&&host.canInteract();}
                public void onBack(){if(!allowed())return;opened=null;status="";render();scrollTop();}
                public void onSaveVideo(){if(opened==null)return;File movie=movieFile(opened);if(movie!=null)saveMedia(movie,"video/mp4",opened.id);}
                public void onSavePhoto(int index){if(opened!=null&&index>=0&&index<visibleStoryPhotos.size())savePhoto(visibleStoryPhotos.get(index),opened);}
                public void onRetryMovie(){if(opened!=null&&allowed()){moviesFailed.remove(opened.id);ensureMovie(opened);render();}}
            },wide);
            experienceRoot.addView(storyView,new FrameLayout.LayoutParams(-1,-1));
        }
        visibleStoryPhotos=photos(story);ArrayList<RecapStoryView.Photo> shown=new ArrayList<>();
        for(MemoryPhoto photo:visibleStoryPhotos)shown.add(new RecapStoryView.Photo(photoBitmap(photo),photo.label,photo.credit));
        storyView.bind(story.id,story.title,story.dateLabel+(story.place.isEmpty()?"":" · "+story.place),storyMemoryLabel(story),shown,movieFile(story),movieProgress,story.id.equals(movieStory),moviesFailed.contains(story.id),saving,status);
        if(active)storyView.resume();ensureMovie(story);
    }
    private void ensureMovie(RecapStoryPlanner.Story story){
        if(movieFile(story)!=null||!movieStory.isEmpty()||moviesFailed.contains(story.id)||!active||!host.canInteract())return;List<MemoryPhoto> chosen=photos(story);if(chosen.isEmpty())return;ArrayList<RecapVideoExporter.Frame> frames=new ArrayList<>();for(MemoryPhoto p:chosen)frames.add(p.video==null?new RecapVideoExporter.Frame(p.asset,p.label+" · 샘플",p.credit):new RecapVideoExporter.Frame(p.video.uri,p.scene.startMs,p.scene.endMs,story.place.isEmpty()?story.dateLabel:story.place));
        final String signature=movieSignature(story);final File output=new File(recapDir(),"NOOBY_AUTO_"+story.id+"_"+System.currentTimeMillis()+".mp4");final AtomicBoolean cancel=new AtomicBoolean();movieCancel=cancel;movieStory=story.id;movieProgress=0;
        worker.submit(()->{try{RecapVideoExporter.export(activity,frames,story.place.contains("바다")||story.place.contains("부산")?"sea":"lake",story.dateLabel+"\n"+story.title+(story.sample?" · 샘플":""),output,()->destroyed||cancel.get()||Thread.currentThread().isInterrupted(),percent->main.post(()->{if(!destroyed&&!cancel.get()){movieProgress=percent;if(active&&opened!=null&&opened.id.equals(story.id))render();}}));if(cancel.get()||destroyed){output.delete();return;}String previous=prefs.getString("auto_file_"+story.id,"");prefs.edit().putString("auto_file_"+story.id,output.getName()).putString("auto_signature_"+story.id,signature).apply();if(!previous.equals(output.getName())&&previous.matches("NOOBY_AUTO_[a-f0-9-]+_[0-9]+\\.mp4"))new File(recapDir(),previous).delete();}catch(Exception error){output.delete();if(!cancel.get())main.post(()->moviesFailed.add(story.id));}finally{main.post(()->{if(movieCancel==cancel){movieCancel=null;movieStory="";}if(!destroyed&&active)render();});}});
    }
    private File movieFile(RecapStoryPlanner.Story story){if(!movieSignature(story).equals(prefs.getString("auto_signature_"+story.id,"")))return null;String name=prefs.getString("auto_file_"+story.id,"");if(!name.matches("NOOBY_AUTO_[a-f0-9-]+_[0-9]+\\.mp4"))return null;File file=new File(recapDir(),name);return file.isFile()&&file.length()>0?file:null;}
    private String movieSignature(RecapStoryPlanner.Story story){StringBuilder value=new StringBuilder("auto-v2-bottom-title:").append(story.id);for(MemoryPhoto p:photos(story))value.append('|').append(p.video==null?p.asset:p.video.id+":"+p.scene.timestampMs);return hash(value.toString());}
    private void playUri(Uri uri,String title){
        if(!allowed())return;stopPlayback();LinearLayout body=column();body.setPadding(dp(24),dp(24),dp(24),dp(24));body.addView(text(title,24,INK,true));gap(body,16);
        int height=Math.max(dp(180),Math.min(dp(520),activity.getResources().getDisplayMetrics().heightPixels-dp(260)));
        FrameLayout stage=new FrameLayout(activity);stage.setBackgroundColor(0xff101820);VideoView player=new VideoView(activity);video=player;stage.addView(player,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));body.addView(stage,new LinearLayout.LayoutParams(-1,height));
        MediaController controller=new MediaController(activity);controller.setAnchorView(stage);player.setMediaController(controller);gap(body,16);body.addView(button("닫기",false,this::stopPlayback),size(-1,-2));
        dialog=new AlertDialog.Builder(activity).setView(body).create();dialog.setOnDismissListener(d->{if(video==player){player.stopPlayback();video=null;}dialog=null;});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(1000),activity.getResources().getDisplayMetrics().widthPixels-dp(64)),-2);
        player.setOnPreparedListener(mp->{if(active&&host.canInteract()&&video==player){int width=Math.max(1,mp.getVideoWidth()),mediaHeight=Math.max(1,mp.getVideoHeight());int fittedWidth=Math.min(stage.getWidth(),Math.round(height*(float)width/mediaHeight));int fittedHeight=Math.min(height,Math.round(fittedWidth*(float)mediaHeight/width));player.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth,fittedHeight,Gravity.CENTER));player.start();}else stopPlayback();});
        player.setOnErrorListener((mp,a,b)->{stopPlayback();toast("영상을 열 수 없어요. 원본 연결을 확인해 주세요.");return true;});player.setVideoURI(uri);
    }
    private void stopPlayback(){VideoView old=video;video=null;if(old!=null){try{old.stopPlayback();}catch(RuntimeException ignored){}if(dialog!=null){AlertDialog d=dialog;dialog=null;d.dismiss();}}}
    private void savePhoto(MemoryPhoto photo,RecapStoryPlanner.Story story){if(photo.path!=null)saveMedia(new File(photo.path),"image/jpeg",hash(photo.path));else toast("샘플 사진은 출처가 표시된 리캡 영상으로 저장할 수 있어요.");}
    private void saveMedia(File file,String mime,String key){
        if(!allowed()||saving)return;final String saveKey=mime+hash(file.getAbsolutePath());if(Build.VERSION.SDK_INT<29){toast("추억이 앱 안에 저장되어 있어요.");return;}if(prefs.contains("auto_saved_"+saveKey)){toast("이미 앨범에 저장했어요.");return;}saving=true;status="앨범에 저장하고 있어요";render();
        worker.submit(()->{Uri destination=null;try{boolean movie=mime.startsWith("video");ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,file.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,(movie?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES)+"/누비길");values.put(MediaStore.MediaColumns.IS_PENDING,1);destination=activity.getContentResolver().insert(movie?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(destination==null)throw new IOException();try(InputStream input=new FileInputStream(file);OutputStream output=activity.getContentResolver().openOutputStream(destination)){if(output==null)throw new IOException();byte[] buffer=new byte[32768];int count;while((count=input.read(buffer))!=-1){if(destroyed||Thread.currentThread().isInterrupted())throw new InterruptedException();output.write(buffer,0,count);}}values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);activity.getContentResolver().update(destination,values,null,null);prefs.edit().putString("auto_saved_"+saveKey,destination.toString()).apply();main.post(()->{saving=false;if(!destroyed){status="앨범의 누비길 폴더에 저장했어요";if(active)render();}});}catch(Exception error){if(destination!=null)try{activity.getContentResolver().delete(destination,null,null);}catch(RuntimeException ignored){}main.post(()->{saving=false;if(!destroyed){status="앨범에 저장하지 못했어요. 추억은 앱에 남아 있어요.";if(active)render();}});}});
    }
    private void addStatus(){if(status==null||status.isEmpty())return;gap(panel,12);TextView view=text(status,16,MUTED,false);view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(view,size(-1,-2));}
    private RecapStoryPlanner.Story sampleStory(){Calendar date=Calendar.getInstance();date.add(Calendar.YEAR,-1);ArrayList<ScenicCaptureStore.Record> sample=new ArrayList<>();int i=0;for(String key:new String[]{"haeundae","gwangalli","coast"})sample.add(new ScenicCaptureStore.Record("recap-preview-"+key,"recap-preview","부산 바다 여행","부산",key.equals("haeundae")?"해운대":key.equals("gwangalli")?"광안리":"청사포",key,date.getTimeInMillis()+i++*60000,false));return RecapStoryPlanner.plan(Collections.emptyList(),sample,System.currentTimeMillis()).get(0);}
    private static String placeFromMetadata(String location){if(location==null)return "";java.util.regex.Matcher match=java.util.regex.Pattern.compile("^([+-][0-9]+(?:\\.[0-9]+)?)([+-][0-9]+(?:\\.[0-9]+)?)").matcher(location);if(!match.find())return "";try{double lat=Double.parseDouble(match.group(1)),lon=Double.parseDouble(match.group(2));if(Math.abs(lat)>90||Math.abs(lon)>180)return "";DriveRegion closest=null;double best=Double.MAX_VALUE;for(DriveRegion region:DriveRegion.ALL){double distance=Math.hypot((lat-region.lat)*111,(lon-region.lon)*111*Math.cos(Math.toRadians(lat)));if(distance<best){best=distance;closest=region;}}return best<35&&closest!=null?closest.name:String.format(Locale.KOREA,"%.3f°, %.3f°",lat,lon);}catch(NumberFormatException invalid){return "";}}
    private Bitmap originalThumbnail(RecapStoryPlanner.Story story){for(RecapStoryPlanner.Moment m:story.moments)if(m.video!=null){Bitmap image=decode(new File(thumbnailDir(),m.video.id+".jpg").getAbsolutePath(),null);if(image!=null)return image;}return null;}
    private Bitmap photoBitmap(MemoryPhoto photo){return decode(photo.path,photo.asset);}
    private Bitmap decode(String path,String asset){String key=path==null?asset:path;if(bitmaps.containsKey(key))return bitmaps.get(key);Bitmap image=null;try{BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;if(path!=null)image=BitmapFactory.decodeFile(path,options);else try(InputStream input=activity.getAssets().open(asset)){options.inSampleSize=2;image=BitmapFactory.decodeStream(input,null,options);}}catch(Exception ignored){}if(image!=null){if(bitmaps.size()>=18)bitmaps.remove(bitmaps.keySet().iterator().next());bitmaps.put(key,image);}return image;}
    private File thumbnailDir(){return directory("recap-thumbnails");}
    private File recapDir(){return directory("recaps");}
    private File directory(String name){File dir=new File(activity.getFilesDir(),name);if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Storage unavailable");return dir;}
    private boolean allowed(){if(active&&!destroyed&&host.canInteract())return true;stopPlayback();toast("정차 후 추억을 열어주세요.");return false;}
    private static String clean(String value,int max){String result=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").trim();return result.length()>max?result.substring(0,max):result;}
    private static String hash(String value){try{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}
    private static String imageKey(String key){return "gwangalli".equals(key)?key:"coast".equals(key)?key:"haeundae";}
    private static String credit(String key){return "gwangalli".equals(key)?"Masterhatch · CC BY-SA 4.0":"coast".equals(key)?"Andrewssi2 · CC BY-SA 3.0":"부산광역시 · 공공누리 제1유형";}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.TOP);return view;}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private void gap(LinearLayout parent,int height){parent.addView(new View(activity),size(1,height));}
    private void addAction(LinearLayout parent,TextView button){LinearLayout.LayoutParams p=wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2);if(parent.getChildCount()>0)p.setMargins(wide?dp(8):0,wide?0:dp(8),0,0);parent.addView(button,p);}
    private GradientDrawable background(int color,int stroke,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(stroke!=0)shape.setStroke(dp(1),stroke);return shape;}
    private TextView text(String value,int sp,int color,boolean bold){TextView text=new TextView(activity);text.setText(value);text.setTextSize(ScenicTypography.size(sp));text.setTextColor(color);text.setTypeface(ScenicTypography.font(activity,bold?600:400));text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;}
    private TextView button(String value,boolean primary,Runnable action){TextView button=text(value,17,primary?WHITE:INK,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(16),dp(16),dp(16),dp(16));button.setMinHeight(dp(ScenicDesign.TOUCH_HEIGHT));button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x28ffffff:ScenicDesign.RIPPLE),background(primary?ACCENT:WHITE,primary?0:LINE,ScenicDesign.CONTROL_RADIUS),null));button.setFocusable(true);button.setClickable(true);button.setContentDescription(value);button.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(Button.class.getName());}});button.setOnClickListener(v->action.run());controls.add(button);return button;}
    private void toast(String message){Toast.makeText(activity,message,Toast.LENGTH_SHORT).show();}
}



