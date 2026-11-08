package com.scenic.pleos;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Native competition prototype with an optional local API backend. */
public final class MainActivity extends Activity implements LocationListener {
    private static final int BG=0xfff5f7f5,PANEL=0xffffffff,LINE=0xffdee7e1,INK=0xff19332c,MUTED=0xff65766f,ACCENT=0xff2c6656,SOFT=0xffeaf1ec;
    private boolean wide;
    private int windowWidthDp,windowHeightDp,fontBoost;
    private static final int GPS_PERMISSION=41,CAR_PERMISSION=42,VIDEO_PICK=43,AUDIO_PICK=44;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private CourseEngine.Course course=CourseEngine.COURSES[0];
    private int budget=90,spotIndex=0,tab=3;
    private String preference="all",regionId="all";
    private int radiusKm;
    private boolean nearbyRequested,refreshOnNextFix;
    private long candidateRevision,liveGeneration;
    private boolean preparedBrowse,liveWaitingForFix,liveRequestActive;
    private LivePlan livePlan;
    private LivePlanOrigin liveOrigin;
    private String liveMessage="출발지 주변 20km의 실제 명소를 찾아 시간에 맞는 새 코스를 만들어요.";
    private CourseEngine.Result localResult;
    private double rankedLat=Double.NaN,rankedLon=Double.NaN;
    private boolean demo=true,running=false,demoDriving=false,vehicleConnected=false,vehicleRestricted=false,resumed=false;
    private Float vehicleSpeed;
    private Location lastLocation;
    private String vehicleDetail="차량 연결 확인 중",gpsDetail="GPS 꺼짐",notice="시간과 풍경을 선택해 코스를 만들어 주세요.";
    private AmbientPlayer player;
    private VehicleBridge vehicle;
    private LocationManager locationManager;
    private LinearLayout root,content;
    private TextView statusView,detailView,progressView,noticeView,soundStatus;
    private ScenicMapView map;
    private KakaoMapView kakaoMap;
    private boolean useKakaoMap=true,centerNextFix,mapGpsEnabled;
    private String musicMessage="AI가 드라이브에 어울리는 실제 플레이리스트를 고릅니다.";
    private final MusicRecommendationState musicState=new MusicRecommendationState();
    private Future<?> musicJob;
    private ScenicApiClient musicClient;
    private boolean musicWasBlocked;
    private AlertDialog videoDialog, actionDialog;
    private MusicWebDialog musicWebDialog;
    private boolean actionDialogForConnection;
    private VideoView videoView;
    private JSONArray memories;
    private String apiBaseUrl="http://10.0.2.2:4173",apiMessage="실시간 코스 생성은 연결된 API 서버를 사용합니다.";
    private final ExecutorService apiExecutor=Executors.newFixedThreadPool(2);
    private final ApiRequestGate apiRequests=new ApiRequestGate();
    private final Future<?>[] apiJobs=new Future<?>[3];
    private final ScenicApiClient[] apiClients=new ScenicApiClient[3];
    private double[][] roadCoordinates;
    private double roadDistanceKm,roadDurationMin;
    private String roadCourseId;
    private TextView apiStatusView;
    private DestinationPlace destinationPlace;
    private DestinationRoutePlan destinationPlan;
    private DestinationRoutePlan.Route chosenDestinationRoute;
    private ScenicCaptureDemo captureDemo;
    private ScenicDiscoveryView discoveryView;
    private DestinationPhotoView destinationPhotoView;
    private ScenicPhotoCollection.Photo selectedScenicPhoto;
    private boolean destinationPhotoBrowsing;
    private DestinationPlace regionPlace;
    private boolean regionPhotoMode,pendingParkingNavigation;
    private RecapExperience recapExperience;
    private NearbyStopsView nearbyStops;
    private SampleJourney sampleJourney;
    private JourneyState journey;
    private PleosNavigationBridge nativeNavigation;
    private boolean plannerRoundTrip=true;
    private int plannerStayMinutes=0;
    private String navigationStatus="";
    private boolean parkingPermissionPending,parkingPermissionResolved;
    private DestinationPlace parkingPermissionTarget;
    private DestinationRoutePlan.Route parkingPermissionRoute;
    private String parkingPermissionJourney="";
    private int parkingPermissionTab;
    private long parkingPermissionDeadline,parkingPermissionRevision;
    private boolean parkingChecking;
    private int parkingCheckRedirects;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener parkingWatchListener=(prefs,key)->{if("proposalText".equals(key)||"message".equals(key))handler.post(()->{if(resumed&&tab==0)render();});};
    private long parkingCheckRevision;
    private ScenicApiClient parkingCheckClient;
    private Future<?> parkingCheckJob;
    private boolean plannerMode,legacyRecords;
    private String musicTaste="balanced";
    private boolean destinationPlanning,destinationSearching,destinationWaitingForFix,navigationDemoSaved,resumeDestinationAfterPermission;
    private int detourMinutes=30,navigationDemoStep;
    private String destinationTheme="coast",destinationMessage="";
    private long destinationRevision;
    private ScenicApiClient destinationClient;
    private Future<?> destinationJob;


    // Photo browsing keeps its own safety watcher active so controls recover when parked again.
    private final Runnable uiTicker=new Runnable(){public void run(){if(!resumed)return;refreshExpiredLocation();updateStatus();enforceVideoSafety();refreshMusicSafety();if(captureDemo!=null)captureDemo.refreshSafety();if(sampleJourney!=null)sampleJourney.checkSafety();if(isDistractingBlocked()){if(recapExperience!=null)recapExperience.pause();if(discoveryView!=null)discoveryView.pause();}handler.postDelayed(this,1000);}};
    private final Runnable simulator=new Runnable(){public void run(){
        if(!resumed||!running||!demo)return;
        if(spotIndex<course.spots.length-1){spotIndex++;notice=course.spots[spotIndex].title+"에 도착한 것으로 가정합니다. 실제 위치와 무관한 데모입니다.";render();handler.postDelayed(this,14000);}
        else{running=false;demoDriving=false;notice="데모 여정을 마쳤습니다. 저장한 장면은 ‘드라이브 기록’에서 확인하세요.";player.pause();render();}
    }};
    public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        apiBaseUrl=getPreferences(0).getString("api_base_url","http://10.0.2.2:4173");
        budget=getPreferences(0).getInt("budget_minutes",90);if(budget<1||budget>720)budget=90;localResult=CourseEngine.recommend(budget,preference,regionId,false,null,radiusKm);
        player=new AmbientPlayer(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        try{memories=new JSONArray(getPreferences(0).getString("memories","[]"));}catch(Exception e){memories=new JSONArray();}
        vehicle=new VehicleBridge(this,(connected,speed,restricted,detail)->runOnUiThread(()->{boolean wasBlocked=isCarUiBlocked();vehicleConnected=connected;vehicleSpeed=speed;vehicleRestricted=restricted;vehicleDetail=detail;if(isCarUiBlocked())invalidateApi(false,false);if(wasBlocked!=isCarUiBlocked())render();else updateStatus();enforceVideoSafety();}));
        initDestinationExperience();initExperiences();render();
    }
    @Override protected void onResume(){super.onResume();resumed=true;getSharedPreferences("parking_watch",0).registerOnSharedPreferenceChangeListener(parkingWatchListener);syncParkingWatch();handler.post(uiTicker);vehicle.start();if(parkingPermissionPending&&parkingPermissionResolved)resumeParkingPermissionNavigation();if(!demo||mapGpsEnabled)startGps();if(running&&demoDriving)handler.postDelayed(plannerMode?simulator:destinationDemoTicker,plannerMode?14000:7000);render();if(resumeDestinationAfterPermission&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){resumeDestinationAfterPermission=false;requestDestinationRoutes();}}
    @Override protected void onPause(){super.onPause();resumed=false;getSharedPreferences("parking_watch",0).unregisterOnSharedPreferenceChangeListener(parkingWatchListener);if(nativeNavigation!=null)nativeNavigation.pause();pauseExperiences();closeMusicWeb();cancelDestinationWork();if(captureDemo!=null)captureDemo.dismiss();handler.removeCallbacks(destinationDemoTicker);invalidateApi(false,false);handler.removeCallbacks(uiTicker);handler.removeCallbacks(simulator);player.pause();stopGps();vehicle.stop();if(kakaoMap!=null)kakaoMap.onPause();dismissActionDialog();if(videoDialog!=null)videoDialog.dismiss();updateStatus();}
    @Override protected void onDestroy(){if(nativeNavigation!=null)nativeNavigation.destroy();pauseExperiences();if(discoveryView!=null)discoveryView.destroy();if(destinationPhotoView!=null)destinationPhotoView.destroy();if(recapExperience!=null)recapExperience.destroy();if(nearbyStops!=null)nearbyStops.destroy();closeMusicWeb();cancelDestinationWork();if(captureDemo!=null)captureDemo.dismiss();apiExecutor.shutdownNow();handler.removeCallbacksAndMessages(null);player.release();vehicle.stop();if(kakaoMap!=null)kakaoMap.release();super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);pauseExperiences();if(captureDemo!=null)captureDemo.dismiss();render();if(actionDialog!=null)styleDialog(actionDialog);}
    private int d(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int stroke){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(d(16));if(stroke!=0)b.setStroke(d(1),stroke);return b;}
    private TextView txt(String s,int sp,int color){TextView t=new TextView(this);t.setTypeface(ScenicTypography.font(this,400));t.setText(s);t.setTextSize(Math.max(14,sp)+fontBoost);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);t.setLineSpacing(d(3),1);return t;}
    private TextView title(String s,int sp){TextView t=txt(s,sp,INK);t.setTypeface(ScenicTypography.font(this,600));return t;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,d(h)));}
    private void space(LinearLayout l,int w){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(d(w),1));}
    private void panelSpace(){if(wide)space(content,24);else gap(content,20);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:d(w),h<0?h:d(h));}
    private TextView button(String label,boolean primary,Runnable action){
        TextView b=txt(label,17,primary?PANEL:ACCENT);b.setGravity(Gravity.CENTER);b.setTypeface(ScenicTypography.font(this,600));
        b.setPadding(d(16),d(12),d(16),d(12));b.setMinHeight(d(56));
        GradientDrawable surface=bg(primary?ACCENT:PANEL,primary?0:LINE);surface.setCornerRadius(d(12));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),surface,null));
        b.setFocusable(true);b.setClickable(true);b.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});b.setOnClickListener(v->action.run());return b;
    }
    private TextView chip(String label,boolean selected,Runnable action){
        TextView b=button(label,false,action);b.setTextSize(16+fontBoost);b.setPadding(d(12),d(10),d(12),d(10));b.setMinHeight(d(56));b.setSelected(selected);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),bg(selected?SOFT:PANEL,selected?ACCENT:LINE),null));b.setTypeface(ScenicTypography.font(this,selected?600:400));return b;
    }
    private void addScrollableColumn(LinearLayout column,float weight){
        if(wide){ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setVerticalScrollBarEnabled(false);sc.addView(column,new ScrollView.LayoutParams(-1,-2));content.addView(sc,new LinearLayout.LayoutParams(0,-1,weight));}
        else content.addView(column,lp(-1,-2));
    }
    private void dismissActionDialog(){AlertDialog old=actionDialog;actionDialog=null;actionDialogForConnection=false;if(old!=null)old.dismiss();}
    private LinearLayout card(){LinearLayout l=col();l.setPadding(d(24),d(24),d(24),d(24));l.setBackground(bg(PANEL,LINE));return l;}
    private LinearLayout section(){LinearLayout l=col();l.setPadding(d(wide?4:0),d(8),d(wide?4:0),d(8));return l;}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,lp(-1,1));}
    private TextView textAction(String label,Runnable action){TextView b=button(label,false,action);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1817191c),bg(Color.TRANSPARENT,0),null));b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(0,d(12),0,d(12));return b;}
    private void setBusy(TextView button,boolean busy){button.setEnabled(!busy);button.setAlpha(busy?.5f:1f);}
    private void showInformation(String heading,String message){
        if(isDrivingInteractionBlocked()){toast("자세한 정보는 정차 후 확인해 주세요.");return;}
        actionDialogForConnection=false;actionDialog=new AlertDialog.Builder(this).setTitle(heading).setMessage(message).setPositiveButton("확인",null).show();styleDialog(actionDialog);
    }
    private void addTimeChoices(LinearLayout parent){
        LinearLayout label=row();label.addView(title("여유 시간",16),new LinearLayout.LayoutParams(0,-2,1));label.addView(txt(budget+"분",16,ACCENT));parent.addView(label);gap(parent,12);
        LinearLayout choices=row();int count=0;for(int minutes:new int[]{60,90,120}){if(count++>0)space(choices,8);choices.addView(chip(minutes+"분",budget==minutes,()->{if(canChangeRoute())setBudgetValue(minutes);}),new LinearLayout.LayoutParams(0,-2,1));}parent.addView(choices);gap(parent,4);parent.addView(textAction("시간 직접 입력  ＋",this::showBudgetInput),lp(-1,-2));
    }
    private void addSceneryChoices(LinearLayout parent,boolean live){
        parent.addView(title("보고 싶은 풍경",16));gap(parent,12);String[] ids={"all","lake","sea","night"},names={"모든 풍경","호수 · 숲","바다","도시 · 문화"};
        for(int start=0;start<ids.length;start+=2){LinearLayout choices=row();for(int i=start;i<start+2;i++){if(i>start)space(choices,8);final String id=ids[i];choices.addView(chip(names[i],preference.equals(id),()->{if(!canChangeRoute())return;preference=id;if(live)cancelLiveRequest("풍경 조건이 바뀌었어요. 새 코스를 만들어 주세요.",true);else refreshLocalRecommendations(true);render();}),new LinearLayout.LayoutParams(0,-2,1));}parent.addView(choices);if(start==0)gap(parent,8);}
    }
    private void addMetric(LinearLayout metrics,String value,String label){LinearLayout item=col();item.setPadding(0,0,d(12),0);item.addView(title(value,26));gap(item,6);item.addView(txt(label,14,MUTED));metrics.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
    private void addMainColumn(LinearLayout column,float weight){if(wide)content.addView(column,new LinearLayout.LayoutParams(0,-1,weight));else content.addView(column,lp(-1,-2));}
    private void readViewport(){
        Configuration config=getResources().getConfiguration();windowWidthDp=config.screenWidthDp;windowHeightDp=config.screenHeightDp;
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=getWindowManager().getCurrentWindowMetrics();android.graphics.Rect bounds=metrics.getBounds();android.graphics.Insets safe=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());float density=getResources().getDisplayMetrics().density;windowWidthDp=Math.round((bounds.width()-safe.left-safe.right)/density);windowHeightDp=Math.round((bounds.height()-safe.top-safe.bottom)/density);}
        wide=ScenicLayoutPolicy.useTwoColumns(windowWidthDp,windowHeightDp);fontBoost=0;
    }
    private ArrayAdapter<String> optionAdapter(String[] labels){
        return new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                TextView option=(TextView)super.getView(position,convert,parent);option.setTypeface(ScenicTypography.font(MainActivity.this,400));option.setTextSize(18+fontBoost);option.setTextColor(INK);option.setMinHeight(d(64));option.setPadding(d(24),d(16),d(24),d(16));option.setSingleLine(false);return option;
            }
        };
    }
    private void styleDialog(AlertDialog dialog){
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(d(Math.min(680,windowWidthDp-40)),-2);
        TextView message=dialog.findViewById(android.R.id.message);if(message!=null){message.setTypeface(ScenicTypography.font(this,400));message.setTextSize(16+fontBoost);message.setTextColor(INK);}
        int titleId=getResources().getIdentifier("alertTitle","id","android");TextView heading=dialog.findViewById(titleId);if(heading!=null){heading.setTypeface(ScenicTypography.font(this,600));heading.setTextSize(21+fontBoost);heading.setTextColor(INK);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button b=dialog.getButton(which);if(b!=null){b.setTypeface(ScenicTypography.font(this,600));b.setTextSize(16+fontBoost);b.setMinHeight(d(56));}}
    }
    private void showSettings(){
        actionDialogForConnection=true;
        actionDialog=new AlertDialog.Builder(this).setTitle("드라이브 설정")
            .setAdapter(optionAdapter(new String[]{"출발지 · "+originLabel(),"차량 · 위치 연결 정보","API 서버 연결"}),(dialog,which)->{if(which==0)showDestinationOrigin();else if(which==1)showConnections();else showApiSettings();}).setNegativeButton("닫기",null).show();styleDialog(actionDialog);
    }
    private void pauseExperiences(){if(destinationPhotoView!=null)destinationPhotoView.pause();if(discoveryView!=null)discoveryView.pause();if(recapExperience!=null)recapExperience.pause();if(nearbyStops!=null)nearbyStops.pause();if(sampleJourney!=null)sampleJourney.pause();}
    private void switchExperienceTab(int selectedTab){
        if(isDistractingBlocked()&&selectedTab!=0){toast("정차 후 이용해 주세요.");return;}
        if(selectedTab!=tab){pauseExperiences();if(tab==1)cancelMusicRecommendation();if(selectedTab!=0){cancelDestinationWork();if(liveWaitingForFix||liveRequestActive)cancelLiveRequest("화면을 바꾸어 코스 생성을 취소했습니다.",false);}}
        tab=selectedTab;render();
    }
    private LinearLayout navigation(){
        boolean stacked=!wide&&(windowWidthDp<640||getResources().getConfiguration().fontScale>1.3f);
        LinearLayout navigation=stacked?col():row();navigation.setPadding(d(6),d(6),d(6),d(6));navigation.setBackground(bg(PANEL,LINE));String[] tabs={"발견","경로","음악","리캡","맛집·주차","기록"};int[] ids={3,0,1,4,5,2};
        LinearLayout tabRow=stacked?row():navigation;if(stacked)navigation.addView(tabRow,lp(-1,-2));
        for(int i=0;i<tabs.length;i++){final int selectedTab=ids[i];TextView item=button(tabs[i],false,()->switchExperienceTab(selectedTab));
            item.setTextSize((wide?18:16)+fontBoost);item.setPadding(d(8),d(12),d(8),d(12));item.setTextColor(tab==selectedTab?PANEL:MUTED);item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),bg(tab==selectedTab?ACCENT:Color.TRANSPARENT,0),null));item.setSelected(tab==selectedTab);item.setContentDescription(tabs[i]+(tab==selectedTab?", 선택됨":""));
            if(stacked&&i==3){gap(navigation,4);tabRow=row();navigation.addView(tabRow,lp(-1,-2));}
            tabRow.addView(item,new LinearLayout.LayoutParams(0,-2,1));if(i<tabs.length-1&&(!stacked||i!=2))space(tabRow,4);
        }return navigation;
    }
    private void render(){
        readViewport();
        if(kakaoMap!=null){if(kakaoMap.getParent() instanceof ViewGroup)((ViewGroup)kakaoMap.getParent()).removeView(kakaoMap);kakaoMap.onPause();}
        root=col();int side=wide?28:20;root.setPadding(d(side),d(12),d(side),d(12));root.setBackgroundColor(BG);setContentView(root);
        // Pleos overlays system bars on API 34 too. Fit the real safe area once, on every API 30+ device.
        if(Build.VERSION.SDK_INT>=30){root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(d(side)+bars.left,d(12)+bars.top,d(side)+bars.right,d(12)+Math.max(bars.bottom,ime.bottom));return insets;});root.requestApplyInsets();}
        LinearLayout header=row();TextView brand=title("SCENIC",26);brand.setLetterSpacing(.05f);header.addView(brand,wide?lp(160,-2):new LinearLayout.LayoutParams(0,-2,1));
        if(wide){header.addView(navigation(),new LinearLayout.LayoutParams(0,-2,1));space(header,20);}
        TextView settings=button("설정",false,this::showSettings);settings.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),bg(Color.TRANSPARENT,0),null));header.addView(settings,lp(72,-2));root.addView(header,lp(-1,-2));gap(root,wide?24:12);
        if(!wide){root.addView(navigation(),lp(-1,-2));gap(root,16);}
        content=wide?row():col();content.setGravity(Gravity.TOP);
        if(wide)root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        else{ScrollView body=new ScrollView(this);body.setFillViewport(true);body.setVerticalScrollBarEnabled(false);body.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));}
        map=null;soundStatus=null;progressView=null;detailView=null;apiStatusView=null;noticeView=null;
        if(isCarUiBlocked())renderDrivingSafety();else if(tab==0)renderRoutes();else if(tab==1)renderSound();else if(tab==3)addExperienceView(discoveryView.createView(wide));else if(tab==4)addExperienceView(recapExperience.createView(wide));else if(tab==5)addExperienceView(nearbyStops.createView(wide));else renderScenicRecords();
        gap(root,10);statusView=txt("",14,MUTED);statusView.setPadding(0,d(6),0,d(6));root.addView(statusView,lp(-1,-2));updateStatus();
        root.post(()->{if(musicWebDialog!=null)musicWebDialog.relayout();});
    }
    private void renderRoutes(){if(destinationPhotoBrowsing&&!plannerMode){renderDestinationPhotos();return;}if(plannerMode){if(preparedBrowse)renderPreparedRoutes();else renderLiveRoutes();}else renderDestinationRoutes();}
    private void addExperienceView(View view){content.addView(view,wide?new LinearLayout.LayoutParams(-1,-1):lp(-1,windowHeightDp-190));}
    private void initExperiences(){
        musicTaste=getPreferences(0).getString("music_taste","balanced");
        plannerRoundTrip=getPreferences(0).getBoolean("planner_return",true);plannerStayMinutes=getPreferences(0).getInt("planner_stay",0);if(plannerStayMinutes!=0&&plannerStayMinutes!=15&&plannerStayMinutes!=30)plannerStayMinutes=0;
        nativeNavigation=new PleosNavigationBridge(this,new PleosNavigationBridge.Host(){public boolean canInteract(){return resumed&&!isDrivingInteractionBlocked();}public void onNavigationStatus(String message){navigationStatus=message;if(resumed&&!isDestroyed())render();}public void onNavigationRequested(DestinationPlace target,List<DestinationPlace> points){if(points.isEmpty()&&sameJourneyPlace(target,journey.parking())&&!ParkingWatchService.start(MainActivity.this,apiBaseUrl,journey,nearbyStops.excludedParkingIds())){navigationStatus="주차 안내를 요청했어요. 자동 빈자리 확인은 시작하지 못했으니 주차 목록을 다시 확인해 주세요.";}}});
        destinationPhotoView=new DestinationPhotoView(this,new DestinationPhotoView.Host(){
            public boolean canInteract(){return resumed&&tab==0&&destinationPhotoBrowsing&&!isDrivingInteractionBlocked();}
            public String serverBaseUrl(){return apiBaseUrl;}
            public void onPhotoSelected(ScenicPhotoCollection.Photo photo){chooseScenicPhoto(photo);}
            public void onSkip(){if(!canChangeRoute())return;destinationPhotoView.pause();destinationPhotoBrowsing=false;forgetScenicPhoto();if(regionPhotoMode&&regionPlace!=null){destinationPlace=regionPlace;journey.beginRegion(regionPlace);regionPhotoMode=false;}else journey.setScenicWaypoint(null);saveJourney();detourMinutes=0;requestDestinationRoutes();}
        });
        discoveryView=new ScenicDiscoveryView(this,new ScenicDiscoveryView.Host(){
            public boolean canInteract(){return resumed&&!isDrivingInteractionBlocked();}
            public java.util.List<ScenicCaptureStore.Record> records(){return captureDemo.recordsForRoute(null);}
            public void chooseDestination(DestinationPlace place,String theme,int extra){openExperienceDestination(place,theme,extra);}
            public void chooseRegion(DestinationPlace region){openRegion(region);}
            public void searchRegion(){if(!canChangeRoute())return;showDestinationSearch(true);}
            public void searchDestination(){if(!canChangeRoute())return;pauseExperiences();plannerMode=false;destinationPhotoBrowsing=false;tab=0;render();showDestinationSearch();}
            public void openAiPlanner(){if(!canChangeRoute())return;forgetScenicPhoto();destinationPhotoBrowsing=false;destinationPhotoView.pause();clearDestinationPlan("");plannerMode=true;preparedBrowse=false;switchExperienceTab(0);}
            public void startSampleJourney(){sampleJourney.show();}
            public void openRecap(){switchExperienceTab(4);}
            public void openNearby(){switchExperienceTab(5);}
            public String originLabel(){return MainActivity.this.originLabel();}
        });
        recapExperience=new RecapExperience(this,new RecapExperience.Host(){
            public boolean canInteract(){return resumed&&!isDistractingBlocked();}
            public java.util.List<ScenicCaptureStore.Record> records(){return captureDemo.recordsForRoute(null);}
            public void onOpenRecords(){switchExperienceTab(2);}
            public String journeyId(){return journey.id();}
            public String journeyTitle(){return journey.title();}
        });
        for(int i=0;i<memories.length();i++){JSONObject memory=memories.optJSONObject(i);if(memory!=null&&"video".equals(memory.optString("kind"))&&!memory.optString("uri").isEmpty())recapExperience.migrateLegacyVideo(Uri.parse(memory.optString("uri")),memory.optString("title"),memory.optLong("durationMs"),memory.optLong("createdAt"),"legacy_"+memory.optString("courseId"),memory.optString("course","이전 여정"));}
        nearbyStops=new NearbyStopsView(this,new NearbyStopsView.Host(){
            public boolean canInteract(){return resumed&&!isDrivingInteractionBlocked();}
            public String serverBaseUrl(){return apiBaseUrl;}
            public DestinationPlace anchor(){return journey.nearbyAnchor();}
            public List<DestinationPlace> anchors(){return journey.stops();}
            public DestinationPlace savedRestaurant(){return journey.restaurant();}
            public String[] foodHints(){return regionFoodHints();}
            public void selectRestaurant(DestinationPlace place){journey.selectRestaurant(place);saveJourney();}
            public void navigateToParking(DestinationPlace place,DestinationPlace restaurant){openParkingNavigation(place,restaurant);}
            public void chooseAnchor(DestinationPlace place){journey.selectNearbyAnchor(place);saveJourney();render();}
            public void navigate(DestinationPlace place,DestinationPlace restaurant,boolean parking){openNearbyDestination(place,restaurant,parking);}
        });
        sampleJourney=new SampleJourney(this,new SampleJourney.Host(){
            public boolean canInteract(){return resumed&&!isDrivingInteractionBlocked();}
            public void capture(String segment){captureDemo.saveDemoCapture("sample_busancoast","부산 해안 자동 기록 시연","청사포",segment);}
            public void sharePreview(boolean enabled){captureDemo.setSharingEnabled(enabled);}
            public void soundtrack(boolean play){if(play){closeMusicWeb();player.playScene(sceneTrack());}else player.pause();}
            public void openRecap(){switchExperienceTab(4);}
        });
    }
    private void openExperienceDestination(DestinationPlace place,String theme,int extra){
        if(!canChangeRoute())return;regionPlace=null;regionPhotoMode=false;journey.beginDestination(place);saveJourney();openJourneyTarget(place,theme,extra);
    }
    private void openRegion(DestinationPlace region){
        if(!canChangeRoute())return;pauseExperiences();cancelLiveRequest("",true);clearDestinationPlan("");forgetScenicPhoto();
        regionPlace=region;regionPhotoMode=true;destinationPlace=region;plannerMode=false;journey.beginRegion(region);saveJourney();dismissActionDialog();destinationPhotoBrowsing=true;tab=0;render();
    }
    private String[] regionFoodHints(){return DriveRegion.foodHints(journey.region());}
    private void openScenicDining(){if(!canChangeRoute())return;if(journey.scenic()!=null)journey.selectNearbyAnchor(journey.scenic());else if(selectedScenicPhoto!=null)journey.selectNearbyAnchor(selectedScenicPhoto.place);saveJourney();switchExperienceTab(5);}
    private void openParkingNavigation(DestinationPlace parking,DestinationPlace restaurant){
        openParkingNavigation(parking,restaurant,0);
    }
    private void openParkingNavigation(DestinationPlace parking,DestinationPlace restaurant,int redirects){
        if(!canChangeRoute())return;journey.visitParking(parking,restaurant);saveJourney();
        pauseExperiences();destinationPhotoBrowsing=false;regionPhotoMode=false;forgetScenicPhoto();plannerMode=false;clearDestinationPlan("");destinationPlace=parking;destinationTheme="all";detourMinutes=0;parkingCheckRedirects=redirects;tab=0;render();requestDestinationRoutes(true);
    }
    private void saveJourney(){ParkingWatchService.stopIfJourneyChanged(this,journey);getPreferences(0).edit().putString("journey_v2",journey.serialize()).putBoolean("region_photo_flow",regionPhotoMode).apply();}
    private void syncParkingWatch(){
        if(journey==null)return;DestinationPlace replacement=ParkingWatchService.replacement(this,journey);
        if(replacement!=null){journey.visitParking(replacement,journey.restaurant());destinationPlace=replacement;regionPhotoMode=false;destinationPhotoBrowsing=false;forgetScenicPhoto();plannerMode=false;detourMinutes=0;clearDestinationPlan("");saveJourney();destinationMessage="만차로 확인되어 "+replacement.name+"으로 순정 내비 안내를 변경했어요. 경로 확인으로 지도를 갱신할 수 있어요.";}
    }
    private void openNearbyDestination(DestinationPlace place,DestinationPlace restaurant,boolean parking){
        if(!canChangeRoute())return;if(parking)journey.visitParking(place,restaurant);else journey.visitRestaurant(place);saveJourney();openJourneyTarget(place,"all",0);
    }
    private void openJourneyTarget(DestinationPlace place,String theme,int extra){pauseExperiences();destinationPhotoBrowsing=false;regionPhotoMode=false;forgetScenicPhoto();plannerMode=false;clearDestinationPlan("");destinationPlace=place;destinationTheme=theme;detourMinutes=extra;tab=0;render();requestDestinationRoutes();}
    private void forgetScenicPhoto(){selectedScenicPhoto=null;getPreferences(0).edit().remove("selected_photo_v1").apply();}
    private void rememberScenicPhoto(){getPreferences(0).edit().putString("selected_photo_v1",ScenicPhotoCollection.serializeSelection(selectedScenicPhoto,regionPhotoMode&&regionPlace!=null?regionPlace:destinationPlace)).apply();}
    private void openDestinationPhotos(){
        if(!canChangeRoute()||destinationPlace==null)return;pauseExperiences();cancelLiveRequest("",true);plannerMode=false;tab=0;clearDestinationPlan("");regionPlace=journey.region();regionPhotoMode=regionPlace!=null;destinationPhotoBrowsing=true;render();
    }
    private void chooseSearchedDestination(DestinationPlace place){
        if(!canChangeRoute())return;pauseExperiences();cancelLiveRequest("",true);clearDestinationPlan("");plannerMode=false;forgetScenicPhoto();regionPlace=null;regionPhotoMode=false;destinationPlace=place;journey.beginDestination(place);saveJourney();dismissActionDialog();destinationPhotoBrowsing=true;tab=0;render();
    }
    private void chooseScenicPhoto(ScenicPhotoCollection.Photo photo){
        if(!canChangeRoute()||photo==null)return;destinationPhotoView.pause();selectedScenicPhoto=photo;rememberScenicPhoto();destinationPhotoBrowsing=false;destinationTheme=photo.scene;
        if(regionPhotoMode&&regionPlace!=null){destinationPlace=photo.place;if(detourMinutes==0)detourMinutes=30;journey.chooseScenicDestination(photo.place);}
        else{if(detourMinutes==0)detourMinutes=30;journey.beginDestination(destinationPlace);journey.setScenicWaypoint(photo.place);}saveJourney();requestDestinationRoutes();
    }
    private void renderDestinationPhotos(){
        if(isDrivingInteractionBlocked()){destinationPhotoView.pause();renderDrivingSafety();return;}
        LinearLayout page=col(),tools=row();tools.addView(textAction(regionPhotoMode?"지역 변경  ⌕":"목적지 변경  ⌕",()->showDestinationSearch(regionPhotoMode)),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(textAction(originLabel()+"  ›",this::showDestinationOrigin),new LinearLayout.LayoutParams(0,-2,1));page.addView(tools);gap(page,8);
        page.addView(destinationPhotoView.createView(regionPhotoMode&&regionPlace!=null?regionPlace:destinationPlace,wide,regionPhotoMode),wide?new LinearLayout.LayoutParams(-1,0,1):lp(-1,windowHeightDp-260));content.addView(page,new LinearLayout.LayoutParams(-1,-1));
    }
    private void selectPreparedJourney(){
        if(!hasBudgetCourse())return;if(nearbyRequested&&freshLocation())journey.setOrigin(true,lastLocation.getLatitude(),lastLocation.getLongitude(),"현재 위치");
        journey.beginCourse(course,false);saveJourney();
    }
    private void requestNativeNavigation(){
        if(parkingChecking)return;parkingPermissionPending=false;handler.removeCallbacks(parkingPermissionRetry);parkingCheckRedirects=0;requestNativeNavigation(false);
    }
    private void requestNativeNavigation(boolean inventoryChecked){
        if(!canChangeRoute()||nativeNavigation==null||parkingChecking||nativeNavigation.isBusy())return;
        if(sameJourneyPlace(destinationPlace,journey.parking())&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("parking_notification_asked",false)){
            getPreferences(0).edit().putBoolean("parking_notification_asked",true).apply();parkingPermissionPending=true;parkingPermissionResolved=false;parkingPermissionDeadline=0;parkingPermissionTarget=destinationPlace;parkingPermissionRoute=chosenDestinationRoute;parkingPermissionJourney=journey.id();parkingPermissionTab=tab;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},45);return;
        }
        try{List<DestinationPlace> points=new ArrayList<>();DestinationPlace target;
            if(plannerMode&&hasBudgetCourse()){for(int i=0;i<course.spots.length;i++){CourseEngine.Spot stop=course.spots[i];points.add(DestinationPlace.of("navi_stop_"+i,stop.title,stop.description,stop.lat,stop.lon));}if(isLiveSelection()&&livePlan.returnToOrigin){target=DestinationPlace.of("return_origin","출발지로 돌아오기",originLabel(),liveOrigin.lat,liveOrigin.lon);}else target=points.remove(points.size()-1);}
            else{target=destinationPlace;if(chosenDestinationRoute==null){toast("먼저 경로를 선택해 주세요.");return;}for(CourseEngine.Spot stop:chosenDestinationRoute.waypoints)points.add(DestinationPlace.of("navi_waypoint_"+points.size(),stop.title,"풍경 경유지",stop.lat,stop.lon));}
            if(sameJourneyPlace(target,journey.parking())&&!inventoryChecked){checkParkingBeforeNavigation(target);return;}
            if(!sameJourneyPlace(target,journey.parking()))ParkingWatchService.stop(this);
            nativeNavigation.request(target,points);
            // From here the car's own position decides when the route's checkpoints are reached.
            if(!plannerMode&&chosenDestinationRoute!=null&&!chosenDestinationRoute.checkpoints.isEmpty()){checkpointTracker=new CheckpointTracker(chosenDestinationRoute.checkpoints);demoCheckpointSaves=0;trackingLiveCheckpoints=true;}
        }catch(Exception invalid){toast("내비게이션에 전달할 장소를 확인하지 못했어요.");}
    }
    private CheckpointTracker checkpointTracker;private int demoCheckpointSaves;private boolean trackingLiveCheckpoints;
    private List<RouteCheckpoint> routeCheckpoints(){return chosenDestinationRoute==null?java.util.Collections.<RouteCheckpoint>emptyList():chosenDestinationRoute.checkpoints;}
    /** A checkpoint with no recorded clip yet starts one. Does nothing unless the user turned automatic recording on. */
    private void trackCheckpoint(double lat,double lon){
        if(checkpointTracker==null||chosenDestinationRoute==null)return;
        for(CheckpointTracker.Event event:checkpointTracker.update(lat,lon))
            if(event.kind==CheckpointTracker.Kind.START&&captureDemo.saveCheckpointCapture(destinationRouteId(),destinationRouteName(),destinationLabel(),event.checkpoint)!=null)demoCheckpointSaves++;
    }
    private int demoTotalSteps(){int count=routeCheckpoints().size();return count==0?3:count+2;}
    private void checkParkingBeforeNavigation(DestinationPlace target){
        final Set<String> excluded=new HashSet<>(nearbyStops.excludedParkingIds());
        if(excluded.contains(target.id)){navigationStatus="직접 만차·입차 불가로 제외한 주차장이에요. 맛집·주차에서 다른 곳을 선택해 주세요.";render();return;}
        final DestinationPlace restaurant=journey.restaurant(),anchor=restaurant==null?target:restaurant;
        final DestinationRoutePlan.Route requestedRoute=chosenDestinationRoute;
        final String requestedJourney=journey.id();final int requestedTab=tab,redirects=parkingCheckRedirects;
        final ScenicApiClient client=new ScenicApiClient(apiBaseUrl);cancelParkingCheck();parkingCheckClient=client;parkingChecking=true;final long revision=parkingCheckRevision;
        navigationStatus="순정 내비 안내 전에 주차 빈자리를 다시 확인하고 있어요…";render();
        parkingCheckJob=apiExecutor.submit(()->{
            ParkingReroutePolicy.Decision decision=null;String failure=null;
            try{decision=ParkingReroutePolicy.evaluate(client.nearbyParking(anchor,target.id),target,excluded,SystemClock.elapsedRealtime());}
            catch(Exception unavailable){failure="주차 상태를 다시 확인하지 못해 안내를 시작하지 않았어요. 연결을 확인하고 순정 내비 안내를 다시 눌러 주세요.";}
            final ParkingReroutePolicy.Decision result=decision;final String error=failure;
            handler.post(()->{
                if(!resumed||isDestroyed()||isFinishing()||revision!=parkingCheckRevision||tab!=requestedTab||!journey.id().equals(requestedJourney)
                        ||!sameJourneyPlace(destinationPlace,target)||!sameJourneyPlace(journey.parking(),target)||chosenDestinationRoute!=requestedRoute)return;
                parkingChecking=false;parkingCheckClient=null;parkingCheckJob=null;
                if(!canChangeRoute()){navigationStatus="주행 상태가 바뀌어 주차 안내 확인을 중단했어요. 정차 후 다시 눌러 주세요.";render();return;}
                if(error!=null||result==null){navigationStatus=error==null?"주차 응답을 확인하지 못했어요. 다시 시도해 주세요.":error;render();return;}
                long now=SystemClock.elapsedRealtime();
                if(result.current.isFull(now)){
                    if(result.alternative==null||!result.alternativeAvailability.isAvailable(now)){navigationStatus=target.name+"은 만차이며 빈자리가 확인된 대체 주차장이 없어요. 맛집·주차에서 다른 곳을 확인해 주세요.";render();return;}
                    if(redirects>=2){navigationStatus="주변 주차 상태가 계속 바뀌어 자동 변경을 멈췄어요. 맛집·주차에서 다시 선택해 주세요.";render();return;}
                    navigationStatus=target.name+"이 만차여서 "+result.alternative.name+"으로 바꾸어 도로와 빈자리를 확인합니다.";toast(navigationStatus);
                    openParkingNavigation(result.alternative,restaurant,redirects+1);return;
                }
                navigationStatus=result.current.isAvailable(now)?(result.current.isSnapshot()?"공식 조회 빈자리 "+result.current.availableSpaces+"대 · 제공처 갱신 시각은 미제공입니다.":"최신 빈자리 "+result.current.availableSpaces+"대를 확인했어요."):"빈자리는 미확인입니다. 선택한 주차장 위치로 안내합니다.";
                render();requestNativeNavigation(true);
            });
        });
    }
    private void cancelParkingCheck(){
        parkingCheckRevision++;if(parkingCheckClient!=null){parkingCheckClient.cancel();parkingCheckClient=null;}
        if(parkingCheckJob!=null){parkingCheckJob.cancel(true);parkingCheckJob=null;}
        if(parkingChecking)navigationStatus="주차 확인을 취소했어요. 안내를 다시 눌러 조회할 수 있어요.";parkingChecking=false;
    }
    private void resumeParkingPermissionNavigation(){
        if(!parkingPermissionPending||!parkingPermissionResolved||!resumed)return;
        if(parkingPermissionDeadline==0){parkingPermissionDeadline=SystemClock.elapsedRealtime()+5000;parkingPermissionRevision=destinationRevision;}
        navigationStatus="알림 설정을 마쳤어요. 차량의 정차 상태를 확인한 뒤 주차 안내를 이어갑니다.";handler.removeCallbacks(parkingPermissionRetry);handler.postDelayed(parkingPermissionRetry,250);
    }
    private final Runnable parkingPermissionRetry=new Runnable(){public void run(){
        if(!parkingPermissionPending||!parkingPermissionResolved||!resumed||isDestroyed()||isFinishing())return;
        if(tab!=parkingPermissionTab||destinationRevision!=parkingPermissionRevision||!journey.id().equals(parkingPermissionJourney)||chosenDestinationRoute!=parkingPermissionRoute
                ||!sameJourneyPlace(destinationPlace,parkingPermissionTarget)||!sameJourneyPlace(journey.parking(),parkingPermissionTarget)){
            parkingPermissionPending=false;navigationStatus="선택한 경로가 바뀌어 자동 안내 연결을 중단했어요. 경로를 확인하고 다시 눌러 주세요.";render();return;
        }
        if(SystemClock.elapsedRealtime()>parkingPermissionDeadline){parkingPermissionPending=false;navigationStatus="차량의 정차 상태를 확인하지 못했어요. 정차 상태에서 순정 내비 안내를 다시 눌러 주세요.";render();return;}
        if(isDrivingInteractionBlocked()){handler.postDelayed(this,250);return;}
        parkingPermissionPending=false;requestNativeNavigation();
    }};
    private void addNativeNavigation(LinearLayout panel){
        TextView open=button(parkingChecking?"주차 빈자리 확인 중…":nativeNavigation!=null&&nativeNavigation.isBusy()?"순정 내비 연결 중…":"순정 내비로 안내",true,this::requestNativeNavigation);setBusy(open,parkingChecking||nativeNavigation!=null&&nativeNavigation.isBusy());panel.addView(open,lp(-1,-2));gap(panel,6);panel.addView(txt("차량 현재 위치에서 목적지·경유지로 다시 탐색합니다. 카카오 경로선과 달라질 수 있어요.",13,MUTED));if(!navigationStatus.isEmpty()){gap(panel,6);panel.addView(txt(navigationStatus,14,INK));}
    }
    private void addParkingWatchControls(LinearLayout panel){
        if(journey.parking()==null||!sameJourneyPlace(destinationPlace,journey.parking()))return;
        gap(panel,12);panel.addView(txt("주차 안내를 시작하면 1분마다 빈자리를 확인합니다. 현재 주차장이 만차이거나 곧 만차가 되면 더 여유 있는 주차장으로 바꿀지 제안하고, 수락하면 순정 내비 목적지를 바꿉니다.",14,MUTED));
        panel.addView(txt("실시간 정보 제공 지역만 확인 가능 · 최대 3시간 · 앱 또는 허용된 알림에서 중단 가능",12,MUTED));
        String proposal=ParkingWatchService.proposalText(this);if(!proposal.isEmpty()){gap(panel,10);panel.addView(txt("주차장 변경 제안",13,MUTED));panel.addView(txt(proposal,16,INK));gap(panel,6);LinearLayout choices=row();choices.addView(button("변경",true,()->{ParkingWatchService.accept(this);navigationStatus="제안을 수락했어요. 빈자리를 다시 확인한 뒤 순정 내비 목적지를 바꿉니다.";render();}),new LinearLayout.LayoutParams(0,-2,1));space(choices,8);choices.addView(button("유지",false,()->{ParkingWatchService.decline(this);render();}),new LinearLayout.LayoutParams(0,-2,1));panel.addView(choices);}
        String status=ParkingWatchService.status(this);if(!status.isEmpty()&&!status.equals(proposal)){gap(panel,6);panel.addView(txt(status,14,INK));}
        if(ParkingWatchService.active(this)){gap(panel,8);panel.addView(button("자동 주차 확인 중단",false,()->{ParkingWatchService.stop(this);navigationStatus="자동 주차 확인을 중단했어요. 순정 내비 안내는 유지됩니다.";render();}),lp(-1,-2));}
    }
    private String sceneTrack(){if("energetic".equals(musicTaste))return "sea";if("chill".equals(musicTaste)||"instrumental".equals(musicTaste))return "night";return "coast".equals(destinationTheme)?"sea":"city".equals(destinationTheme)?"night":"lake";}
    private void renderLiveRoutes(){
        LinearLayout left=section();addScrollableColumn(left,.35f);panelSpace();
        left.addView(title("AI 코스 찾기",26));left.addView(textAction("목적지로 경로 비교  →",()->{if(!canChangeRoute())return;plannerMode=false;cancelLiveRequest("",false);clearDestinationPlan("");if(journey.target()!=null)destinationPlace=journey.target();render();}),lp(-1,-2));gap(left,16);
        addTimeChoices(left);gap(left,20);addPlannerTripChoices(left);gap(left,20);addSceneryChoices(left,true);gap(left,24);
        boolean busy=liveWaitingForFix||apiRequests.isPending(ApiRequestGate.AI);
        TextView generate=button(liveWaitingForFix?"현재 위치 확인 중…":apiRequests.isPending(ApiRequestGate.AI)?"코스를 만들고 있어요…":"내 주변 코스 찾기",true,this::requestLiveFromLocation);setBusy(generate,busy);left.addView(generate,lp(-1,-2));gap(left,8);
        gap(left,8);
        if(busy){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);left.addView(progress,lp(-1,4));gap(left,12);left.addView(txt(liveMessage,15,INK));left.addView(textAction("코스 생성 취소",()->{cancelLiveRequest("코스 생성을 취소했어요. 다시 요청할 수 있습니다.",true);render();}),lp(-1,-2));}
        else if(liveGeneration>0&&!isLiveSelection()){left.addView(txt(liveMessage,15,INK));gap(left,12);}
        divider(left);gap(left,8);TextView publicDemo=textAction("서울시청 출발 · 데모  ›",this::requestPublicDemo);setBusy(publicDemo,busy);left.addView(publicDemo,lp(-1,-2));left.addView(textAction("전국 코스  ›",this::openPreparedBrowse),lp(-1,-2));
        left.addView(textAction("위치 이용 안내",this::showPlannerInformation),lp(-1,-2));
        if(isLiveSelection()||running){LinearLayout right=card();addMainColumn(right,.65f);renderSelectedRoute(right,isLiveSelection());return;}
        LinearLayout right=col();addScrollableColumn(right,.65f);LinearLayout preview=card();right.addView(preview,lp(-1,-2));renderPlannerPreview(preview,busy);
    }
    private void showPlannerInformation(){
        showInformation("위치 이용 안내","출발지 주변 20km의 한국관광공사 TourAPI 명소를 찾아 AI가 편도 2~3곳, 복귀 코스 2곳을 조합합니다. 카카오 도로 시간에 선택한 장소별 체류 시간을 더합니다. ‘출발지로 돌아오기’를 켜면 실제 복귀 도로 시간도 포함합니다.\n\n선택한 출발 좌표는 서버·TourAPI·카카오에 전달합니다. AI에는 후보 명소와 시간·풍경 취향을 전달하며, 원본 좌표와 영상은 보내지 않습니다.\n\n서울시청 기준 데모는 공개 좌표를 사용합니다. 실제 현재 위치를 바꾸지 않아요. 준비된 코스는 별도의 오프라인 대안이며 이 시간 조건을 재계산하지 않습니다.");
    }
    private void addPlannerTripChoices(LinearLayout parent){
        parent.addView(title("어떤 드라이브인가요?",17));gap(parent,10);LinearLayout choices=row();choices.addView(chip("풍경을 지나가기",plannerStayMinutes==0,()->setPlannerOptions(plannerRoundTrip,0)),new LinearLayout.LayoutParams(0,-2,1));space(choices,8);choices.addView(chip("명소에서 머무르기",plannerStayMinutes>0,()->setPlannerOptions(plannerRoundTrip,15)),new LinearLayout.LayoutParams(0,-2,1));parent.addView(choices);gap(parent,8);
        if(plannerStayMinutes>0){LinearLayout stay=row();for(int minutes:new int[]{15,30}){stay.addView(chip("한 곳 "+minutes+"분",plannerStayMinutes==minutes,()->setPlannerOptions(plannerRoundTrip,minutes)),new LinearLayout.LayoutParams(0,-2,1));space(stay,8);}parent.addView(stay);gap(parent,8);}
        Switch round=new Switch(this);round.setText("출발지로 돌아오기 · 복귀 시간 포함");round.setTextSize(15);round.setMinHeight(d(56));round.setChecked(plannerRoundTrip);round.setOnCheckedChangeListener((v,checked)->{if(canChangeRoute())setPlannerOptions(checked,plannerStayMinutes);});parent.addView(round,lp(-1,-2));parent.addView(txt(plannerStayMinutes==0?"정차 시간을 더하지 않는 경유 코스입니다. 차창 조망은 현장 확인이 필요해요.":"방문 장소별 체류 시간까지 합산합니다.",13,MUTED));
    }
    private void setPlannerOptions(boolean returns,int stay){if(!canChangeRoute())return;plannerRoundTrip=returns;plannerStayMinutes=stay;getPreferences(0).edit().putBoolean("planner_return",returns).putInt("planner_stay",stay).apply();cancelLiveRequest("여정 조건이 바뀌었어요. 코스를 다시 만들어 주세요.",true);render();}
    private String sceneryPreferenceLabel(){
        if("lake".equals(preference))return "호수 · 숲";if("sea".equals(preference))return "바다";if("night".equals(preference))return "도시 · 문화";return "모든 풍경";
    }
    private void summaryRow(LinearLayout parent,String label,String value){
        LinearLayout line=row();line.setGravity(Gravity.TOP);line.addView(txt(label,15,MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView content=txt(value,16,INK);content.setGravity(Gravity.RIGHT);line.addView(content,new LinearLayout.LayoutParams(0,-2,1.7f));parent.addView(line,lp(-1,-2));gap(parent,18);
    }
    private void renderPlannerPreview(LinearLayout panel,boolean busy){
        panel.addView(title(busy?"코스 찾는 중":"선택한 조건",24));gap(panel,24);
        summaryRow(panel,"여유 시간",budget+"분");summaryRow(panel,"풍경",sceneryPreferenceLabel());summaryRow(panel,"출발지",liveOrigin!=null&&busy?liveOrigin.label():liveWaitingForFix?"현재 위치 확인 중":"코스 생성 시 확인");
        summaryRow(panel,"장소별 체류",plannerStayMinutes==0?"체류 없이 경유":plannerStayMinutes+"분");gap(panel,4);panel.addView(txt(plannerRoundTrip?"출발지 복귀 시간 포함":"마지막 장소에서 종료 · 복귀 제외",14,MUTED));
    }
    private void renderPreparedRoutes(){
        LinearLayout left=section();addScrollableColumn(left,.35f);panelSpace();
        left.addView(title("전국 코스",26));gap(left,24);
        addTimeChoices(left);gap(left,16);addSceneryChoices(left,false);gap(left,24);
        left.addView(button("내 위치로 추천",true,this::recommendFromLocation),lp(-1,-2));gap(left,8);
        left.addView(textAction("지역 선택 · "+regionName()+"  ›",this::showRegionPicker),lp(-1,-2));left.addView(textAction("가까운 범위 · "+(radiusKm==0?"자동":radiusKm+"km")+"  ›",this::showRadiusPicker),lp(-1,-2));gap(left,4);left.addView(txt(recommendationSummary(),14,MUTED));gap(left,20);divider(left);gap(left,20);
        List<CourseEngine.Course> displayed=localResult.displayed();LinearLayout listTitle=row();listTitle.addView(title("추천 여정",18),new LinearLayout.LayoutParams(0,-2,1));listTitle.addView(txt(displayed.size()+"개",14,MUTED));left.addView(listTitle);gap(left,16);
        for(CourseEngine.Course candidate:displayed){
            CourseEngine.Match match=localResult.find(candidate.id);if(match==null)continue;boolean selected=candidate==course;
            LinearLayout item=col();item.setPadding(d(16),d(18),d(16),d(18));item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1817191c),bg(selected?SOFT:Color.TRANSPARENT,selected?ACCENT:LINE),null));
            item.addView(txt(candidate.regionName+" · "+candidate.tag+(selected?" · 선택됨":""),14,selected?ACCENT:MUTED));gap(item,8);item.addView(title(candidate.name,20));gap(item,8);item.addView(txt(courseEstimate(match),14,MUTED));
            item.setOnClickListener(v->{if(!canChangeRoute())return;invalidateApi(false,true);course=candidate;spotIndex=0;selectPreparedJourney();notice=candidate.subtitle;render();});item.setFocusable(true);item.setSelected(selected);item.setContentDescription(candidate.name+", 계획용 약 "+match.totalMinutes+"분. "+(selected?"선택됨":"선택"));left.addView(item,lp(-1,-2));gap(left,10);
        }
        if(!hasBudgetCourse())left.addView(txt(emptyMessage(),16,MUTED));gap(left,12);left.addView(textAction("새 코스 만들기로 돌아가기  ›",this::openLivePlanner),lp(-1,-2));left.addView(textAction("예상 시간 안내",()->showInformation("준비된 코스 안내","한국관광공사 TourAPI 장소를 조합한 "+NationwideCatalog.COURSE_COUNT+"개 코스와 기존 샘플 3개 중 조건에 맞는 상위 "+displayed.size()+"개를 표시합니다.\n\n기기 안에서 추천하며 원본 GPS는 서버로 보내지 않습니다. 직선거리 기반 계획용 추정이며 교통·도로 통제·영업시간은 반영하지 않아요. 내 위치 추천은 접근+코스·체류 시간, 지역 둘러보기는 코스·체류 시간입니다. 출발지 복귀는 포함하지 않습니다.")),lp(-1,-2));
        if(!hasBudgetCourse()&&!running){LinearLayout right=col();addScrollableColumn(right,.65f);LinearLayout empty=card();right.addView(empty,lp(-1,-2));empty.addView(title("조건에 맞는 코스가 없습니다",24));gap(empty,16);empty.addView(txt(emptyMessage(),17,MUTED));gap(empty,24);empty.addView(button("전국·지역 둘러보기",true,this::showRegionPicker),lp(-1,-2));gap(empty,8);empty.addView(button("여유 시간 다시 입력",false,this::showBudgetInput),lp(-1,-2));return;}
        LinearLayout right=card();addMainColumn(right,.65f);
        renderSelectedRoute(right,false);
    }
    private void renderSelectedRoute(LinearLayout right,boolean verifiedLive){
        boolean pinnedActions=wide&&ScenicLayoutPolicy.pinRouteActions(windowHeightDp,getResources().getConfiguration().fontScale);
        LinearLayout details=col();ScrollView detailScroll=new ScrollView(this);detailScroll.setVerticalScrollBarEnabled(false);detailScroll.addView(details,new ScrollView.LayoutParams(-1,-2));right.addView(detailScroll,wide?new LinearLayout.LayoutParams(-1,0,1):lp(-1,-2));
        details.addView(txt(verifiedLive&&liveOrigin!=null?liveOrigin.label():"준비된 코스 · "+course.regionName+" · "+course.tag,14,ACCENT));gap(details,8);details.addView(title(course.name,28));gap(details,8);details.addView(txt(course.subtitle,16,MUTED));if(running){gap(details,12);noticeView=txt(notice,14,ACCENT);noticeView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);details.addView(noticeView);}gap(details,24);
        LinearLayout metrics=row();metrics.setGravity(Gravity.TOP);
        if(verifiedLive&&livePlan!=null){addMetric(metrics,(int)Math.ceil(livePlan.totalDurationMin)+"분","총 예상 시간");addMetric(metrics,String.format(Locale.KOREA,"%.1fkm",livePlan.roadDistanceKm),"도로 이동 거리");}
        else{addMetric(metrics,course.minutes+"분","코스 · 체류 추정");addMetric(metrics,course.distance,"계획용 거리");}addMetric(metrics,course.spots.length+"곳","머무를 장소");details.addView(metrics);gap(details,20);
        if(!pinnedActions){addRouteActions(details);gap(details,24);}
        int mapHeight=ScenicLayoutPolicy.mapHeight(windowWidthDp,windowHeightDp,wide);
        if(useKakaoMap){
            try{if(kakaoMap==null)kakaoMap=new KakaoMapView(this);kakaoMap.setBackground(bg(SOFT,0));kakaoMap.setClipToOutline(true);kakaoMap.loadServer(apiBaseUrl);kakaoMap.setScene(course,spotIndex,course.id.equals(roadCourseId)&&(!running||verifiedLive)?roadCoordinates:null);if(freshLocation()&&!(verifiedLive&&liveOrigin!=null&&liveOrigin.publicDemo)){kakaoMap.setLocation(lastLocation,centerNextFix);centerNextFix=false;}else kakaoMap.clearLocation();details.addView(kakaoMap,lp(-1,mapHeight));if(resumed)kakaoMap.onResume();}
            catch(RuntimeException e){details.addView(txt("지도를 열지 못했어요. ‘경로 도식 보기’를 선택하거나 설정에서 연결을 확인해 주세요.",16,MUTED));}
        }else{map=new ScenicMapView(this);map.setRoute(course,spotIndex,running);if(roadCoordinates!=null&&course.id.equals(roadCourseId)&&(!running||verifiedLive))map.setRoadRoute(roadCoordinates,roadDistanceKm,roadDurationMin);details.addView(map,lp(-1,mapHeight));}
        gap(details,8);LinearLayout mapTools=row();mapTools.addView(textAction(useKakaoMap?"경로 도식 보기":"카카오 지도 보기",()->{if(!canChangeRoute())return;useKakaoMap=!useKakaoMap;render();}),new LinearLayout.LayoutParams(0,-2,1));space(mapTools,12);TextView refresh=textAction("지도 새로고침",()->{if(!canChangeRoute())return;if(useKakaoMap&&kakaoMap!=null)kakaoMap.reload();else{if(!verifiedLive)invalidateApi(false,true);render();}});refresh.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);mapTools.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));details.addView(mapTools);
        if(!verifiedLive){TextView road=button(apiRequests.isPending(ApiRequestGate.ROUTE)?"도로 조회 중…":"카카오 도로 조회",false,this::requestRoadRoute);setBusy(road,apiRequests.isPending(ApiRequestGate.ROUTE));details.addView(road,lp(-1,-2));gap(details,8);apiStatusView=txt(apiMessage,14,MUTED);details.addView(apiStatusView);gap(details,12);}
        details.addView(txt(verifiedLive?"도로 이동 + 장소별 "+livePlan.stayMinutes+"분 체류 · "+(livePlan.returnToOrigin?"출발지 복귀 포함":"복귀 제외"):nearbyRequested?"접근 + 코스 · 체류 추정 · 복귀 제외":"코스 · 체류 추정 · 출발지 접근과 복귀 제외",14,MUTED));gap(details,20);divider(details);gap(details,20);
        details.addView(title("방문할 장소",18));gap(details,16);
        for(int i=0;i<course.spots.length;i++){LinearLayout stop=row();stop.setGravity(Gravity.TOP);TextView index=txt(String.format(Locale.KOREA,"%02d",i+1),16,i==spotIndex?ACCENT:MUTED);stop.addView(index,lp(40,-2));LinearLayout label=col();label.addView(title(course.spots[i].title.replace(" 출발",""),17));if(i==spotIndex){gap(label,4);detailView=txt(course.spots[i].description,14,MUTED);label.addView(detailView);}stop.addView(label,new LinearLayout.LayoutParams(0,-2,1));details.addView(stop);gap(details,18);}
        details.addView(textAction("코스 · 예상 시간 자세히 보기  ›",()->showRouteInformation(verifiedLive)),lp(-1,-2));
        gap(details,16);addNativeNavigation(details);
        if(pinnedActions){gap(right,16);divider(right);gap(right,12);addRouteActions(right);}
    }
    private void addRouteActions(LinearLayout parent){
        progressView=title(running?"여정 진행 중 · "+(spotIndex+1)+" / "+course.spots.length+"번째 장소":"여정 준비 완료 · "+course.spots.length+"곳",16);parent.addView(progressView);gap(parent,12);
        LinearLayout actions=row();actions.addView(button("순간 저장",false,this::bookmark),new LinearLayout.LayoutParams(0,-2,1));space(actions,8);actions.addView(button(running?"여정 종료":demo?"데모 드라이브 시작":"GPS 여정 시작",true,this::toggleDrive),new LinearLayout.LayoutParams(0,-2,1.65f));parent.addView(actions);
    }
    private void showRouteInformation(boolean verifiedLive){
        String detail=verifiedLive&&livePlan!=null?"TourAPI 실제 명소 · OpenAI 코스 조합 · 카카오 도로 확인\n\n출발지 접근을 포함한 도로 이동 약 "+(int)Math.ceil(livePlan.roadDurationMin)+"분 + "+course.spots.length+"곳 체류 "+(int)livePlan.visitDurationMin+"분입니다. "+(livePlan.returnToOrigin?"출발지 복귀 시간을 포함합니다.":"출발지 복귀 시간은 제외합니다.")+" 조회 시점의 예상 시간이며 실제 교통·주차·영업 상황에 따라 달라질 수 있어요.":"준비된 장소 조합 · 계획용 추정\n\n"+(nearbyRequested?"현재 위치에서의 접근 + 코스·체류":"코스·체류 (현재 위치에서의 접근 제외)")+" 시간입니다. 출발지 복귀는 포함하지 않습니다. 직선거리 기반 추정이므로 실제 교통·도로 통제·영업시간을 반영하지 않아요.";
        showInformation("코스 정보",detail+"\n\n지도는 여정 확인용이며 실제 길 안내를 제공하지 않습니다.");
    }
    private boolean isCarUiBlocked(){return vehicle!=null&&vehicle.isAutomotive()&&(!vehicleConnected||vehicleRestricted);}
    private void renderDrivingSafety(){
        LinearLayout safe=card();safe.setGravity(Gravity.CENTER);
        if(wide){ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.addView(safe,new ScrollView.LayoutParams(-1,-2));content.addView(sc,lp(-1,-1));}else content.addView(safe,lp(-1,-2));
        safe.addView(title("주행 중 조작이 제한됩니다",28));gap(safe,15);
        safe.addView(txt("차량 상태가 확인되면 경로와 풍경 기록을 살펴볼 수 있어요.",15,MUTED));gap(safe,18);
        if(hasBudgetCourse()||running){safe.addView(title(plannerMode?course.name:destinationRouteName(),23));gap(safe,7);safe.addView(txt(plannerMode?"선택한 드라이브 여정":"도착지 · "+destinationLabel(),17,INK));}else safe.addView(txt("정차 후 목적지를 선택해 주세요.",17,INK));gap(safe,24);
        LinearLayout actions=row();actions.addView(txt("음악은 차량 미디어 버튼으로 조작하세요.",16,MUTED),new LinearLayout.LayoutParams(0,-2,1));space(actions,14);
        if(running)actions.addView(button("시연 종료",false,()->stopDestinationDemo("안내 시연을 종료했어요.")),new LinearLayout.LayoutParams(0,-2,1));else actions.addView(button("차량 연결 확인",false,this::showConnections),new LinearLayout.LayoutParams(0,-2,1));safe.addView(actions,lp(-1,-2));
        gap(safe,20);safe.addView(txt("정차 상태와 필요한 권한이 확인되면 전체 화면으로 돌아갑니다.",12,MUTED));
    }
    private boolean canChangeRoute(){if(isDrivingInteractionBlocked()){toast("정차하고 시연을 종료한 뒤 경로를 바꿔 주세요.");return false;}return true;}
    private void toggleDrive(){
        if(running){running=false;demoDriving=false;handler.removeCallbacks(simulator);player.pause();notice="여정을 종료했습니다.";if(isLiveSelection()&&liveOrigin!=null&&liveOrigin.moved(currentPosition()))cancelLiveRequest("출발 위치가 달라졌어요. 새 코스를 만들어 주세요.",true);render();return;}
        if(!hasBudgetCourse()){toast("먼저 코스를 만들어 선택해 주세요.");return;}
        if(isLiveSelection()&&liveOrigin!=null&&liveOrigin.publicDemo&&!demo){toast("서울시청 기준 코스는 공개 출발지 데모입니다. 실제 GPS 여정으로 시작할 수 없어요.");return;}
        if(!demo&&!freshLocation()){requestGps();toast("최근 현재 위치를 받은 뒤 GPS 여정을 시작해 주세요.");return;}
        invalidateApi(false,!isLiveSelection());liveWaitingForFix=false;running=true;demoDriving=demo;spotIndex=0;
        notice=demo?"14초마다 명소를 이동하는 시뮬레이터입니다. 실제 차속·GPS와 분리됩니다.":"GPS로 명소 접근을 감지합니다. 실제 길 안내는 제공하지 않습니다.";
        if(demo)handler.postDelayed(simulator,14000);else startGps();render();enforceVideoSafety();
    }
    private boolean hasCurrentMusicCourse(){return plannerMode?isLiveSelection()||(preparedBrowse&&localResult!=null&&localResult.find(course.id)!=null):chosenDestinationRoute!=null;}
    private MusicContext currentMusicContext(){
        boolean selected=hasCurrentMusicCourse();
        String title=selected?(plannerMode?course.name:destinationRouteName()):"목적지 선택 전 · 일반 드라이브";
        // Course titles may contain whitespace accepted by the itinerary response.
        title=title==null?"":title.replaceAll("[\\p{Cc}\\p{Z}\\s]+"," ").trim();
        if(title.isEmpty())title="일반 드라이브";
        if(title.length()>120)title=title.substring(0,120).trim();
        return new MusicContext(title,selected?course.scene:destinationTheme,selected?course.minutes:detourMinutes+30,musicTaste);
    }
    private String musicContextKey(MusicContext context){
        return apiBaseUrl+"|"+demo+"|"+preparedBrowse+"|"+preference+"|"+budget+"|"+(hasCurrentMusicCourse()?course.id:"general")+"|"+context.key();
    }
    private String musicThemeLabel(String theme){
        if("coast".equals(theme))return "바다 · 해안";
        if("city".equals(theme))return "도시 풍경";
        if("lake".equals(theme)||"water".equals(theme))return "강 · 호수";
        if("forest".equals(theme))return "숲 · 나무길";
        if("mountain".equals(theme))return "산 · 전망";
        if("countryside".equals(theme))return "들판 · 농촌";
        return "모든 풍경";
    }
    private void cancelMusicRecommendation(){
        if(musicClient!=null){musicClient.cancel();musicClient=null;}
        if(musicJob!=null){musicJob.cancel(true);musicJob=null;}
        musicState.cancel();
    }
    private void requestMusicRecommendation(MusicContext context,String contextKey){
        if(!resumed||tab!=1||isDistractingBlocked())return;
        final long token=musicState.begin();if(token==0)return;
        musicMessage="AI가 현재 드라이브에 맞는 플레이리스트를 고르고 있어요…";
        final ScenicApiClient client;
        try{client=new ScenicApiClient(apiBaseUrl);}catch(IllegalArgumentException e){
            musicMessage="API 서버 주소를 확인한 뒤 다시 시도해 주세요.";
            musicState.complete(token,contextKey,null,musicMessage);return;
        }
        musicClient=client;
        musicJob=apiExecutor.submit(()->{
            MusicPlaylist result=null;String failure=null;
            try{result=MusicPlaylist.parse(client.music(context));}
            catch(ScenicApiClient.ApiException e){failure=e.getMessage();}
            catch(Exception e){failure="플레이리스트 응답을 확인하지 못했어요. 다시 추천해 주세요.";}
            final MusicPlaylist chosen=result;final String error=failure;
            handler.post(()->{
                if(!resumed||isDestroyed()||isFinishing()||tab!=1||isDistractingBlocked())return;
                if(!contextKey.equals(musicContextKey(currentMusicContext())))return;
                if(!musicState.complete(token,contextKey,chosen,error))return;
                musicJob=null;musicClient=null;
                musicMessage=error==null?"추천이 준비됐어요. ‘노래 재생’을 누르면 선택한 플레이리스트가 열립니다.":error;
                // Receiving an AI result never starts an external activity or media.
                render();
            });
        });
    }
    private void renderSound(){
        MusicContext context=currentMusicContext();String key=musicContextKey(context);
        if(musicState.select(key)){cancelMusicRecommendation();musicMessage="";}
        boolean blocked=isDistractingBlocked();musicWasBlocked=blocked;
        if(blocked)cancelMusicRecommendation();else requestMusicRecommendation(context,key);
        MusicPlaylist playlist=musicState.playlist();
        LinearLayout left=col();addScrollableColumn(left,.60f);left.addView(title("풍경에 어울리는 음악",28));gap(left,10);left.addView(txt("지금 가는 길과 나의 취향을 함께 담았어요.",17,MUTED));gap(left,24);LinearLayout selected=card();left.addView(selected,lp(-1,-2));selected.addView(txt("AI 추천 · YouTube Music",15,ACCENT));gap(selected,24);
        if(playlist!=null){selected.addView(title(playlist.title,32));gap(selected,12);selected.addView(txt(playlist.creator,17,MUTED));gap(selected,28);LinearLayout reason=col();reason.setPadding(d(20),d(20),d(20),d(20));reason.setBackground(bg(SOFT,0));reason.addView(title("이 길에 추천하는 이유",17));gap(reason,12);reason.addView(txt(playlist.reason,18,INK));selected.addView(reason,lp(-1,-2));gap(selected,28);}
        else if(blocked){selected.addView(title("정차 후 추천할 수 있습니다",24));gap(selected,12);selected.addView(txt("주행 중에는 차량 미디어 버튼을 사용해 주세요.",16,MUTED));gap(selected,24);}
        else if(musicState.isPending()){selected.addView(title("플레이리스트 추천 중",24));gap(selected,12);gap(selected,8);ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);selected.addView(progress,lp(-1,4));gap(selected,24);}
        else{selected.addView(title("추천을 완료하지 못했습니다",24));gap(selected,12);selected.addView(txt(musicState.error()==null?"다시 시도해 주세요.":musicState.error(),16,MUTED));gap(selected,24);selected.addView(button("음악 추천 다시 시도",true,()->{if(!resumed||isDistractingBlocked())return;cancelMusicRecommendation();musicState.retry();render();}),lp(-1,-2));gap(selected,12);}
        LinearLayout playback=wide?row():col();TextView play=button("노래 재생",true,this::openChosenPlaylist);play.setEnabled(playlist!=null&&!blocked);play.setAlpha(play.isEnabled()?1:.45f);playback.addView(play,wide?new LinearLayout.LayoutParams(0,-2,1.2f):lp(-1,-2));
        if(wide)space(playback,12);else gap(playback,12);TextView external=button("외부 앱에서 열기  ↗",false,this::openChosenPlaylistExternally);external.setEnabled(playlist!=null&&!blocked);external.setAlpha(external.isEnabled()?1:.45f);playback.addView(external,wide?new LinearLayout.LayoutParams(0,-2,1):lp(-1,-2));selected.addView(playback,lp(-1,-2));gap(selected,14);selected.addView(txt("앱 안에서 열고, 작은 음악 창으로 경로와 함께 볼 수 있어요.",15,MUTED));
        if(!musicMessage.isEmpty()&&(playlist!=null||blocked||musicState.isPending())){gap(selected,12);soundStatus=txt(musicMessage,15,MUTED);soundStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);selected.addView(soundStatus);}panelSpace();
        LinearLayout right=col();addScrollableColumn(right,.40f);LinearLayout preferences=card();right.addView(preferences,lp(-1,-2));preferences.addView(title("나의 음악 취향",22));gap(preferences,10);preferences.addView(txt("취향을 고르면 추천이 바뀌어요.",16,MUTED));gap(preferences,20);
        String[] tasteIds={"balanced","acoustic","chill","energetic","instrumental"},tasteNames={"분위기에 맞게","어쿠스틱","차분하게","경쾌하게","가사 없이"};
        for(int start=0;start<tasteIds.length;start+=2){LinearLayout choices=row();for(int i=start;i<Math.min(start+2,tasteIds.length);i++){final String taste=tasteIds[i];if(i>start)space(choices,10);choices.addView(chip(tasteNames[i],musicTaste.equals(taste),()->{if(isDistractingBlocked())return;musicTaste=taste;getPreferences(0).edit().putString("music_taste",taste).apply();cancelMusicRecommendation();render();}),new LinearLayout.LayoutParams(0,-2,1));}preferences.addView(choices,lp(-1,-2));if(start+2<tasteIds.length)gap(preferences,10);}
        gap(preferences,24);divider(preferences);gap(preferences,20);preferences.addView(title("지금 가는 길",17));gap(preferences,10);preferences.addView(txt(hasCurrentMusicCourse()?context.title:"코스를 고르면 풍경도 함께 반영해요.",18,INK));gap(preferences,8);
        String contextLabel=hasCurrentMusicCourse()?(plannerMode?(liveOrigin==null?"준비된 드라이브 코스":liveOrigin.label()):(journey.usesGps()?"현재 위치에서 목적지까지":originLabel())):"선택한 조건";
        preferences.addView(txt(contextLabel,14,MUTED));gap(preferences,16);summaryRow(preferences,"분위기",chosenDestinationRoute!=null&&"fast".equals(chosenDestinationRoute.id)?"일반 드라이브":musicThemeLabel(context.theme));summaryRow(preferences,"시간",context.durationMinutes+"분");gap(right,20);
        LinearLayout ambient=card();right.addView(ambient,lp(-1,-2));ambient.addView(title("기록 시연용 음악",20));gap(ambient,12);
        Switch autoMusic=new Switch(this);autoMusic.setText("시연을 시작하면 자동 재생");autoMusic.setTypeface(ScenicTypography.font(this,400));autoMusic.setTextColor(INK);autoMusic.setTextSize(16);autoMusic.setMinHeight(d(56));autoMusic.setChecked(getPreferences(0).getBoolean("scene_autoplay",false));autoMusic.setOnCheckedChangeListener((v,checked)->{if(!isDistractingBlocked())getPreferences(0).edit().putBoolean("scene_autoplay",checked).apply();});ambient.addView(autoMusic);
        gap(ambient,10);ambient.addView(txt("시연용 자체 생성 음악을 사용합니다.",14,MUTED));gap(ambient,16);ambient.addView(button(player.isPlaying()?"분위기 음악 정지":"분위기 음악 미리 듣기",false,()->{if(isDistractingBlocked())return;if(player.isPlaying())player.pause();else{closeMusicWeb();player.playScene(sceneTrack());}render();}),lp(-1,-2));gap(left,12);left.addView(textAction("음악 연결과 작은 창 이용 안내  ›",()->showInformation("음악 연결 안내","AI가 코스의 풍경과 소요 시간에 맞는 공개 플레이리스트 하나를 추천합니다.\n\n‘노래 재생’은 SCENIC 안에서 공식 음악 웹사이트를 엽니다. 재생을 시작한 뒤 ‘작게 보기’를 누르면 같은 플레이어를 작은 창으로 유지하며 경로를 볼 수 있습니다. ‘재생 종료’를 누르면 플레이어를 닫습니다.\n\n로그인은 ‘외부 앱에서 열기’를 이용해 주세요. 작은 창은 SCENIC 안에서 사용하며, 앱을 나가거나 차량 조작이 제한되면 종료됩니다. 실제 재생 가능 여부는 YouTube의 계정·지역·웹 재생 정책에 따라 달라질 수 있습니다.")),lp(-1,-2));
    }
    private void openChosenPlaylist(){openChosenPlaylist(false);}
    private void openChosenPlaylistExternally(){openChosenPlaylist(true);}
    private void openChosenPlaylist(boolean external){
        if(!resumed||tab!=1||isDistractingBlocked()){toast("음악 화면은 정차 후 열어 주세요.");return;}
        MusicContext context=currentMusicContext();
        if(musicState.select(musicContextKey(context))){cancelMusicRecommendation();render();return;}
        MusicPlaylist playlist=musicState.playlist();if(playlist==null){toast("AI 음악 추천이 준비될 때까지 기다려 주세요.");return;}
        player.pause();
        if(!external&&musicWebDialog!=null&&musicWebDialog.matchesPlaylist(playlist.url)){
            musicWebDialog.expand();musicMessage="열려 있던 음악 플레이어를 크게 표시했습니다.";updateStatus();return;
        }
        closeMusicWeb();
        if(external){
            YouTubeMusicLauncher.Result result=YouTubeMusicLauncher.openPlaylist(this,playlist.url);
            if(result!=YouTubeMusicLauncher.Result.UNAVAILABLE){musicMessage="선택한 플레이리스트를 외부 음악 화면에서 열었습니다.";updateStatus();return;}
        }
        try{
            musicWebDialog=new MusicWebDialog(this,playlist.url,()->{musicWebDialog=null;musicMessage="음악 플레이어를 종료했습니다.";if(resumed)updateStatus();});
            musicWebDialog.show();
            musicMessage="재생 후 ‘작게 보기’를 누르면 경로 화면과 함께 볼 수 있어요.";
        }catch(RuntimeException unavailable){
            closeMusicWeb();musicMessage="음악 웹 화면을 시작하지 못했습니다. 기기의 Android System WebView를 확인해 주세요.";toast(musicMessage);
        }
        updateStatus();
    }
    private void closeMusicWeb(){
        MusicWebDialog previous=musicWebDialog;musicWebDialog=null;if(previous!=null)previous.close();
    }
    private boolean isLegacyCourse(CourseEngine.Course c){return "chuncheon".equals(c.id)||"gangneung".equals(c.id)||"seoul".equals(c.id);}
    private boolean isLiveSelection(){return !preparedBrowse&&livePlan!=null&&livePlan.course!=null&&course==livePlan.course;}
    private boolean hasBudgetCourse(){return plannerMode?(isLiveSelection()||(preparedBrowse&&localResult!=null&&localResult.find(course.id)!=null)):chosenDestinationRoute!=null;}
    private CourseEngine.Position currentPosition(){return lastLocation==null?null:new CourseEngine.Position(lastLocation.getLatitude(),lastLocation.getLongitude(),lastLocation.hasAccuracy(),lastLocation.getAccuracy(),lastLocation.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos());}
    private void refreshLocalRecommendations(boolean chooseTop){
        if(running)return;
        invalidateApi(true,false);candidateRevision++;
        localResult=CourseEngine.recommend(budget,preference,regionId,nearbyRequested,currentPosition(),radiusKm);
        if(nearbyRequested&&freshLocation()){rankedLat=lastLocation.getLatitude();rankedLon=lastLocation.getLongitude();}
        if(!localResult.matches.isEmpty()&&(chooseTop||localResult.find(course.id)==null)){
            CourseEngine.Course selected=localResult.matches.get(0).course;
            if(!selected.id.equals(course.id)){invalidateApi(false,true);course=selected;spotIndex=0;notice=course.subtitle;}
            selectPreparedJourney();
        }else if(localResult.matches.isEmpty())invalidateApi(false,true);
    }
    private void refreshExpiredLocation(){
        if(preparedBrowse&&!running&&nearbyRequested&&localResult!=null&&localResult.state==CourseEngine.State.READY&&!freshLocation()){
            refreshLocalRecommendations(false);notice="현재 위치가 오래되어 근처 추천을 멈췄어요. 위치를 새로 받거나 지역을 선택해 주세요.";render();
        }
    }
    private String regionName(){for(int i=0;i<CourseEngine.REGION_IDS.length;i++)if(CourseEngine.REGION_IDS[i].equals(regionId))return CourseEngine.REGION_NAMES[i];return "전국";}
    private String recommendationSummary(){
        if(localResult==null)return "전국 코스를 준비하고 있어요.";
        if(!nearbyRequested)return regionName()+" 둘러보기 · 내 위치를 사용하지 않음 · 접근 시간 제외";
        if(localResult.state==CourseEngine.State.READY)return "현재 위치에서 직선 "+localResult.radiusKm+"km 이내 · 예상 접근+코스·체류 합계 "+budget+"분 이내 · 복귀 미포함";
        return emptyMessage();
    }
    private String emptyMessage(){
        if(!preparedBrowse)return liveMessage;
        switch(localResult.state){
            case LOCATION_REQUIRED:return "현재 위치가 없어요. 위치 권한과 기기 위치 서비스를 확인하거나 전국·지역 둘러보기를 선택하세요.";
            case LOCATION_STALE:return "오래되거나 유효하지 않은 위치는 사용하지 않아요. ‘내 위치로 추천’으로 새 위치를 받아 주세요.";
            case UNSUPPORTED_ISLAND:return "현재 위치는 배편이 필요할 수 있는 섬이에요. 자동차 접근을 추정하지 않으므로 지역 둘러보기로 확인해 주세요.";
            case OUTSIDE_KOREA:return "수신한 위치가 국내 추천 범위 밖이에요. 에뮬레이터 위치를 확인하거나 국내 지역을 선택하세요.";
            case TIME_TOO_SHORT:return nearbyRequested?"접근 시간과 코스·체류 시간을 합치면 여유 시간을 넘어요. 시간을 늘리거나 다른 지역을 둘러보세요.":"입력한 시간 안에 맞는 코스·체류 조합이 없어요. 시간을 늘리거나 다른 지역을 둘러보세요.";
            case NO_NEARBY:return nearbyRequested?"직선 "+localResult.radiusKm+"km 범위에 등록 코스가 없어요. 범위를 넓히거나 지역을 선택하세요.":"이 지역에서 조건에 맞는 등록 코스가 없어요. 시간이나 지역을 바꿔 주세요.";
            default:return "조건을 바꾸면 다른 코스를 확인할 수 있어요.";
        }
    }
    private String courseEstimate(CourseEngine.Match match){return (nearbyRequested?String.format(Locale.KOREA,"직선 %.1fkm · 접근 약 %d분 + 코스·체류 %d분",match.distanceKm,match.approachMinutes,match.course.minutes):"코스·체류 약 "+match.course.minutes+"분 · 접근 제외")+" · "+match.course.spots.length+"개 장면";}
    private void showRegionPicker(){
        if(!canChangeRoute())return;actionDialogForConnection=false;
        actionDialog=new AlertDialog.Builder(this).setTitle("17 관광권역 · 내 위치 사용 안 함").setAdapter(optionAdapter(CourseEngine.REGION_NAMES),(dialog,which)->{if(!canChangeRoute())return;regionId=CourseEngine.REGION_IDS[which];nearbyRequested=false;refreshOnNextFix=false;refreshLocalRecommendations(true);notice="선택한 지역의 코스입니다. 현재 위치에서의 접근 시간은 포함하지 않았어요.";render();}).setNegativeButton("닫기",null).show();styleDialog(actionDialog);
    }
    private void showRadiusPicker(){
        if(!canChangeRoute())return;actionDialogForConnection=false;
        actionDialog=new AlertDialog.Builder(this).setTitle("근처 범위 · 직선거리 기준").setAdapter(optionAdapter(new String[]{"자동 · 60분까지 20km / 120분까지 50km / 그 이상 100km","20km 이내","50km 이내","100km 이내","현재 조건으로 새로고침"}),(dialog,which)->{if(!canChangeRoute())return;if(which<4)radiusKm=new int[]{0,20,50,100}[which];if(nearbyRequested)recommendFromLocation();else{refreshLocalRecommendations(true);render();}}).setNegativeButton("닫기",null).show();styleDialog(actionDialog);
    }
    private void recommendFromLocation(){
        if(isDrivingInteractionBlocked()){toast("위치 추천은 정차하고 여정을 종료한 뒤 가능합니다.");return;}
        nearbyRequested=true;regionId="all";useKakaoMap=true;mapGpsEnabled=true;centerNextFix=true;
        refreshOnNextFix=!freshLocation();refreshLocalRecommendations(true);
        notice=refreshOnNextFix?"현재 위치를 받고 있어요. 에뮬레이터 위치는 실제 컴퓨터 위치와 다를 수 있어요.":"현재 위치 기준으로 기기에서 추천했어요. 위치는 서버에 보내지 않았습니다.";
        requestGps();render();
    }
    private void cancelLiveRequest(String message,boolean clearPlan){
        liveGeneration++;liveWaitingForFix=false;
        if(liveRequestActive||apiRequests.isPending(ApiRequestGate.AI))invalidateApi(false,false);
        liveRequestActive=false;
        if(clearPlan&&!running){livePlan=null;liveOrigin=null;invalidateApi(false,true);}
        liveMessage=message;
    }
    private void openPreparedBrowse(){
        if(!canChangeRoute())return;cancelLiveRequest("준비된 코스를 둘러보고 있어요.",true);preparedBrowse=true;nearbyRequested=false;refreshOnNextFix=false;regionId="all";refreshLocalRecommendations(true);notice="준비된 장소 조합입니다. 실시간 AI 생성 코스와 구분해 표시해요.";render();
    }
    private void openLivePlanner(){
        if(!canChangeRoute())return;destinationPhotoBrowsing=false;forgetScenicPhoto();destinationPhotoView.pause();cancelLiveRequest("출발지 주변 20km의 실제 명소를 찾아 새 코스를 만들어요.",true);preparedBrowse=false;nearbyRequested=false;refreshOnNextFix=false;notice="내 위치 또는 명시된 공개 데모 출발지로 새 코스를 요청하세요.";render();
    }
    private void requestLiveFromLocation(){
        if(!canChangeRoute()||apiRequests.isPending(ApiRequestGate.AI))return;
        cancelLiveRequest("현재 위치를 확인하고 있어요.",true);preparedBrowse=false;nearbyRequested=false;refreshOnNextFix=false;useKakaoMap=true;mapGpsEnabled=true;centerNextFix=false;
        if(freshLocation()){beginLiveAtCurrentFix();return;}
        liveWaitingForFix=true;liveMessage="최근 위치를 받고 있어요. 위치 권한을 허용해 주세요. 오래된 위치로는 요청하지 않습니다.";final long waitingGeneration=liveGeneration;
        requestGps();render();
        handler.postDelayed(()->{if(liveWaitingForFix&&liveGeneration==waitingGeneration){cancelLiveRequest("현재 위치를 받지 못했어요. 기기 위치 서비스를 확인하거나 서울시청 기준 데모·준비된 코스를 선택하세요.",true);if(resumed)render();}},20000);
    }
    private void beginLiveAtCurrentFix(){
        CourseEngine.Position position=currentPosition();
        if(position==null||!position.fresh()){liveWaitingForFix=false;liveMessage="위치가 오래되었어요. ‘내 위치로 AI 코스 만들기’를 다시 눌러 주세요.";render();return;}
        if(!CourseEngine.inKorea(position.lat,position.lon)||CourseEngine.unsupportedIslandOrigin(position.lat,position.lon)){
            liveWaitingForFix=false;liveMessage="현재 수신 위치는 지원하는 국내 자동차 출발 범위 밖이에요. 서울시청 기준 데모를 직접 선택하거나 준비된 코스를 둘러보세요.";render();return;
        }
        beginLivePlan(LivePlanOrigin.current(position),position);
    }
    private void requestPublicDemo(){
        if(!canChangeRoute()||apiRequests.isPending(ApiRequestGate.AI))return;
        cancelLiveRequest("서울시청 기준 데모를 준비하고 있어요. 실제 현재 위치가 아닙니다.",true);preparedBrowse=false;nearbyRequested=false;refreshOnNextFix=false;useKakaoMap=true;centerNextFix=false;
        beginLivePlan(LivePlanOrigin.seoulCityHall(),null);
    }
    private void beginLivePlan(final LivePlanOrigin origin,final CourseEngine.Position requestedPosition){
        if(!resumed||!canChangeRoute())return;
        liveWaitingForFix=false;
        if(budget<plannerStayMinutes*2){liveMessage="명소 2곳의 체류 시간만 "+(plannerStayMinutes*2)+"분이 필요해요. 시간을 늘리거나 체류 시간을 줄여 주세요.";render();return;}
        final int requestedBudget=budget,requestedStay=plannerStayMinutes;final boolean requestedReturn=plannerRoundTrip;final String requestedPreference=preference;final long requestedGeneration=liveGeneration;
        liveOrigin=origin;liveRequestActive=true;liveMessage=origin.label()+" · 주변 명소를 찾고 AI 조합과 카카오 도로 시간을 확인하고 있어요.";
        runApi(ApiRequestGate.AI,liveMessage,client->{
            if(origin.publicDemo)return client.planAtSeoulCityHall(requestedBudget,requestedPreference,requestedReturn,requestedStay);
            CourseEngine.Position freshAtSend=new CourseEngine.Position(requestedPosition.lat,requestedPosition.lon,requestedPosition.hasAccuracy,requestedPosition.accuracy,requestedPosition.fixNanos,SystemClock.elapsedRealtimeNanos());
            return client.plan(freshAtSend,requestedBudget,requestedPreference,20000,requestedReturn,requestedStay);
        },response->{
            liveRequestActive=false;
            if(preparedBrowse||liveOrigin!=origin||!origin.canApply(requestedGeneration,liveGeneration,currentPosition())){liveMessage="출발 위치나 조건이 달라져 이전 결과를 적용하지 않았어요. 새 코스를 요청해 주세요.";return;}
            LivePlan parsed=LivePlan.parse(response,requestedBudget,origin.lat,origin.lon,20000,requestedReturn,requestedStay);livePlan=parsed;
            if(parsed.course==null){liveMessage=parsed.message;roadCoordinates=null;roadCourseId=null;notice=origin.label();return;}
            course=parsed.course;spotIndex=0;journey.setOrigin(!origin.publicDemo,origin.lat,origin.lon,origin.publicDemo?"서울시청 · 시연 출발지":"현재 위치");journey.beginCourse(course,parsed.returnToOrigin);saveJourney();roadCoordinates=parsed.roadCoordinates;roadDistanceKm=parsed.roadDistanceKm;roadDurationMin=parsed.roadDurationMin;roadCourseId=course.id;
            liveMessage=origin.label()+" · 후보 "+parsed.candidateCount+"곳에서 실제 도로 시간까지 확인한 새 코스입니다.";
            apiMessage="TourAPI 명소 검색 · OpenAI 코스 조합 · 카카오 도로 확인 완료";notice=origin.label()+" · "+(parsed.returnToOrigin?"복귀 시간 포함":"복귀 시간 제외")+" · 장소별 체류 "+parsed.stayMinutes+"분";
        });
    }
    @Override public void onBackPressed(){
        if(running){stopDestinationDemo("안내 시연을 종료했어요.");return;}
        if(destinationPhotoBrowsing&&tab==0){destinationPhotoView.pause();destinationPhotoBrowsing=false;render();return;}
        if(tab!=0){cancelMusicRecommendation();cancelLiveRequest("화면을 이동해 코스 생성을 취소했습니다.",false);switchExperienceTab(0);return;}
        if(destinationPlanning||destinationWaitingForFix){cancelDestinationWork();destinationMessage="경로 비교를 취소했어요.";render();return;}
        if(preparedBrowse){openLivePlanner();return;}
        if(liveWaitingForFix||liveRequestActive){cancelLiveRequest("코스 생성을 취소했습니다.",true);render();return;}
        super.onBackPressed();
    }
    private void setBudgetValue(int minutes){
        budget=minutes;getPreferences(0).edit().putInt("budget_minutes",budget).apply();if(preparedBrowse)refreshLocalRecommendations(true);else cancelLiveRequest("여유 시간이 바뀌었어요. 새 코스를 만들어 주세요.",true);render();
    }
    private void showBudgetInput(){
        if(!canChangeRoute())return;
        LinearLayout form=col();form.setPadding(d(24),d(8),d(24),d(12));form.addView(txt("드라이브에 쓸 수 있는 시간을 분 단위로 입력하세요.",16,MUTED));gap(form,12);
        EditText input=new EditText(this);input.setTypeface(ScenicTypography.font(this,400));input.setTextSize(22+fontBoost);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);input.setHint("예: 75");input.setText(String.valueOf(budget));input.setSelectAllOnFocus(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});form.addView(input,lp(-1,-2));gap(form,8);form.addView(txt("1~720분",14,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("여유 시간 직접 입력").setView(form).setNegativeButton("취소",null).setPositiveButton("적용",null).create();actionDialogForConnection=false;actionDialog=dialog;dialog.show();styleDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!canChangeRoute())return;try{String value=input.getText().toString().trim();if(!value.matches("[0-9]{1,3}"))throw new NumberFormatException();int minutes=Integer.parseInt(value);if(minutes<1||minutes>720)throw new NumberFormatException();dismissActionDialog();setBudgetValue(minutes);}catch(NumberFormatException e){input.setError("1~720 사이의 정수를 입력해 주세요.");}});
        input.requestFocus();if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private boolean validCurrentFix(Location l){return l!=null&&LocationFixPolicy.isDisplayable(l.getLatitude(),l.getLongitude(),l.hasAccuracy(),l.getAccuracy(),l.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos());}
    private boolean freshLocation(){return (!demo||mapGpsEnabled)&&validCurrentFix(lastLocation);}
    private void showCurrentLocation(){recommendFromLocation();}
    private void renderMemories(){
        LinearLayout intro=section();addScrollableColumn(intro,.35f);intro.addView(title("AI 여정·가져온 영상",26));intro.addView(textAction("풍경 기록으로 돌아가기",()->{legacyRecords=false;render();}),lp(-1,-2));gap(intro,16);
        intro.addView(button("내 영상 가져오기",true,this::importVideo),lp(-1,-2));gap(intro,8);intro.addView(button("현재 순간 저장",false,this::bookmark),lp(-1,-2));gap(intro,20);
        if(hasBudgetCourse()||running){intro.addView(txt("연결할 여정",14,MUTED));gap(intro,8);intro.addView(title(course.name,18));}else intro.addView(txt("장면을 저장하려면 먼저 여정을 골라주세요.",15,MUTED));gap(intro,24);divider(intro);gap(intro,16);intro.addView(textAction("보관 안내  ›",()->showInformation("기록 보관 안내","현재 순간 저장은 장소·코스·시간을 남기는 북마크입니다. 영상 녹화는 수행하지 않습니다.\n\n영상은 Android 파일 선택기에서 직접 가져옵니다. 차량 카메라에 접근하지 않으며, 원본 파일은 이동하거나 업로드하지 않습니다. 기기 안에 영상 연결과 기록을 보관해요.\n\n기록을 삭제해도 원본 영상 파일은 삭제되지 않습니다.")),lp(-1,-2));panelSpace();
        LinearLayout list=col();addScrollableColumn(list,.65f);LinearLayout heading=row();heading.addView(title("저장한 기록",22),new LinearLayout.LayoutParams(0,-2,1));heading.addView(txt(memories.length()+"개",15,MUTED));list.addView(heading);gap(list,20);
        if(memories.length()==0){LinearLayout empty=card();empty.addView(title("저장한 기록이 없습니다",24));gap(empty,12);empty.addView(txt(hasBudgetCourse()?"현재 장소를 저장하거나 영상을 가져와 선택한 코스에 연결할 수 있습니다.":"코스를 선택한 뒤 장소를 저장하거나 영상을 연결할 수 있습니다.",17,MUTED));gap(empty,24);empty.addView(button(hasBudgetCourse()?"현재 순간 저장":"여정 찾으러 가기",true,()->{if(hasBudgetCourse())bookmark();else{tab=0;render();}}),lp(-1,-2));list.addView(empty,lp(-1,-2));}
        for(int i=memories.length()-1;i>=0;i--){final int index=i;JSONObject memory=memories.optJSONObject(i);if(memory==null)continue;boolean video=memory.optString("kind").equals("video");LinearLayout item=card();boolean stackedMeta=windowWidthDp<600||getResources().getConfiguration().fontScale>1.3f;LinearLayout meta=stackedMeta?col():row();meta.addView(txt(video?"내 영상":"저장한 장소",14,ACCENT),stackedMeta?lp(-1,-2):new LinearLayout.LayoutParams(0,-2,1));if(stackedMeta)gap(meta,8);meta.addView(txt(memory.optString("date"),14,MUTED));item.addView(meta);gap(item,12);item.addView(title(memory.optString("title","드라이브 기록"),22));gap(item,8);item.addView(txt(memory.optString("course"),16,MUTED));gap(item,12);String detail=memory.optString("source");if(memory.has("durationMs"))detail+=" · "+(memory.optLong("durationMs")/1000)+"초";item.addView(txt(detail,14,MUTED));gap(item,16);divider(item);gap(item,8);LinearLayout actions=row();if(video)actions.addView(textAction("영상 보기  ›",()->playVideo(memory)),new LinearLayout.LayoutParams(0,-2,1));else actions.addView(txt(memory.optString("spot"),15,MUTED),new LinearLayout.LayoutParams(0,-2,1));space(actions,12);actions.addView(textAction("삭제",()->deleteMemory(index)),lp(56,-2));item.addView(actions);list.addView(item,lp(-1,-2));gap(list,12);}
    }
    private void importVideo(){
        if(isDistractingBlocked()){toast("영상 선택은 정차 후 가능합니다.");return;}if(!hasBudgetCourse()&&!running){toast("영상과 연결할 여정을 먼저 선택해 주세요.");return;}openDocument("video/*",VIDEO_PICK);
    }
    private void deleteMemory(int index){
        if(isDistractingBlocked()){toast("삭제는 정차 후 가능합니다.");return;}actionDialogForConnection=false;actionDialog=new AlertDialog.Builder(this).setTitle("이 기록을 삭제할까요?").setMessage("원본 영상 파일은 삭제되지 않습니다.").setPositiveButton("기록 삭제",(dialog,which)->{if(isDistractingBlocked()){toast("정차 후 삭제할 수 있습니다.");return;}memories.remove(index);saveMemories();render();}).setNegativeButton("취소",null).show();styleDialog(actionDialog);
    }
    private void bookmark(){if(!hasBudgetCourse()&&!running){toast("먼저 조건에 맞는 여정을 선택해 주세요.");return;}try{JSONObject m=baseMemory("bookmark",course.spots[spotIndex].title);appendMemory(m);notice="‘"+course.spots[spotIndex].title+"’ 순간을 저장했습니다. 영상 녹화는 수행하지 않습니다.";if(noticeView!=null)noticeView.setText(notice);toast("순간이 저장되었습니다");if(tab==2)render();}catch(Exception e){toast("저장하지 못했습니다.");}}
    private JSONObject baseMemory(String kind,String title)throws JSONException{JSONObject m=new JSONObject();m.put("kind",kind);m.put("title",title);m.put("date",new SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(new Date()));m.put("createdAt",System.currentTimeMillis());m.put("course",course.name);m.put("courseId",course.id);m.put("spot",course.spots[spotIndex].title);m.put("source",isLiveSelection()&&liveOrigin!=null&&liveOrigin.publicDemo?"서울시청 기준 데모 · 실제 현재 위치 아님":demo?"데모 장면":"GPS 여정");if(!demo&&freshLocation()&&!(isLiveSelection()&&liveOrigin!=null&&liveOrigin.publicDemo)){m.put("lat",lastLocation.getLatitude());m.put("lon",lastLocation.getLongitude());m.put("accuracyMeters",lastLocation.getAccuracy());m.put("locationTimestamp",lastLocation.getTime());}return m;}
    private void appendMemory(JSONObject m){memories.put(m);saveMemories();}
    private void saveMemories(){getPreferences(0).edit().putString("memories",memories.toString()).apply();}
    private void openDocument(String type,int request){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(type);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(new Intent(intent).setPackage("com.android.documentsui"),request);}catch(ActivityNotFoundException unavailable){try{startActivityForResult(intent,request);}catch(ActivityNotFoundException e){toast("이 기기에 파일 선택기가 없습니다.");}}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(recapExperience!=null&&recapExperience.handleActivityResult(request,result,data))return;if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        if(request==AUDIO_PICK){if(isDistractingBlocked()){toast("정차 후 다시 선택해 주세요.");return;}boolean ok=player.playUri(uri);notice=ok?"선택한 오디오의 재생 준비를 요청했습니다.":"이 오디오를 재생할 수 없습니다.";updateStatus();return;}
        if(request==VIDEO_PICK){
            if(!hasBudgetCourse()&&!running){toast("영상과 연결할 여정을 먼저 선택해 주세요.");return;}
            if(isDistractingBlocked()){toast("정차 후 다시 선택해 주세요.");return;}
            final JSONObject memory;
            try{memory=baseMemory("video","내 드라이브 영상");memory.put("uri",uri.toString());}catch(JSONException e){toast("기록을 만들지 못했습니다.");return;}
            notice="영상 정보를 읽고 있습니다…";updateStatus();
            new Thread(()->{MediaMetadataRetriever retriever=new MediaMetadataRetriever();String name="내 드라이브 영상";
                try{
                    try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
                    memory.put("title",name);retriever.setDataSource(this,uri);String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(duration!=null)memory.put("durationMs",Long.parseLong(duration));
                    handler.post(()->{if(isDestroyed()||isFinishing())return;if(isDistractingBlocked()){toast("주행 상태로 바뀌어 영상 연결을 취소했습니다.");return;}if(!hasBudgetCourse()&&!running){toast("선택한 여정이 없어 영상 연결을 취소했습니다.");return;}appendMemory(memory);recapExperience.importLegacyVideo(uri,memory.optString("title"),memory.optLong("durationMs"),memory.optLong("createdAt"),journey.id(),journey.title());notice="영상을 여정과 월간 리캡에 연결했습니다. 원본 파일은 이동하지 않습니다.";tab=2;render();});
                }catch(Exception error){handler.post(()->{if(!isDestroyed()&&!isFinishing()){notice="영상 정보를 읽지 못했습니다. 다른 로컬 파일을 선택해 주세요.";updateStatus();toast(notice);}});}finally{try{retriever.release();}catch(Exception ignored){}}
            },"scenic-video-metadata").start();
        }
    }
    private boolean knownGpsMoving(){return (!demo||mapGpsEnabled)&&freshLocation()&&lastLocation.hasSpeed()&&lastLocation.getSpeed()>0.05f;}
    private boolean isDrivingInteractionBlocked(){return running||demoDriving||knownGpsMoving()||isCarUiBlocked();}
    private boolean gpsVideoBlocked(){if(demo)return false;if(lastLocation==null)return true;long age=(SystemClock.elapsedRealtimeNanos()-lastLocation.getElapsedRealtimeNanos())/1000000;return VehicleSafety.isGpsVideoBlocked(lastLocation.hasSpeed(),lastLocation.getSpeed(),age,lastLocation.hasSpeedAccuracy(),lastLocation.getSpeedAccuracyMetersPerSecond());}
    private boolean isDistractingBlocked(){return running||demoDriving||knownGpsMoving()||gpsVideoBlocked()||isCarUiBlocked();}
    private void refreshMusicSafety(){
        if(!resumed||tab!=1)return;
        boolean blocked=isDistractingBlocked();
        if(blocked)cancelMusicRecommendation();
        if(blocked!=musicWasBlocked){render();return;}
        if(musicState.select(musicContextKey(currentMusicContext()))){cancelMusicRecommendation();render();}
    }
    private void enforceVideoSafety(){if(isDistractingBlocked()&&musicWebDialog!=null){closeMusicWeb();musicMessage="주행 상태로 바뀌어 음악 웹 화면을 닫았습니다.";toast(musicMessage);}boolean cancelledLive=isDrivingInteractionBlocked()&&apiRequests.isPending(ApiRequestGate.AI);if(isDrivingInteractionBlocked())invalidateApi(false,false);if(cancelledLive&&resumed){liveMessage="주행 상태로 바뀌어 코스 생성을 취소했습니다. 정차 후 다시 요청해 주세요.";render();}if(((isDrivingInteractionBlocked()&&!actionDialogForConnection)||running||demoDriving||knownGpsMoving()||(vehicleSpeed!=null&&vehicleSpeed>0.1f))&&actionDialog!=null)dismissActionDialog();if(isDistractingBlocked()&&videoDialog!=null){if(videoView!=null)videoView.stopPlayback();videoDialog.dismiss();videoDialog=null;toast("주행 상태에서 영상 재생을 중단했습니다.");}if(isDistractingBlocked()&&tab>=2&&(tab!=5||isDrivingInteractionBlocked())){pauseExperiences();if(running||demoDriving||knownGpsMoving()||(vehicleSpeed!=null&&vehicleSpeed>0.1f)){tab=0;render();}}}
    private void playVideo(JSONObject m){if(isDistractingBlocked()){toast("영상은 정차 상태에서만 재생할 수 있습니다.");return;}player.pause();videoView=new VideoView(this);videoView.setVideoURI(Uri.parse(m.optString("uri")));MediaController controls=new MediaController(this);videoView.setMediaController(controls);videoView.setOnErrorListener((mp,what,extra)->{toast("원본 파일에 접근할 수 없습니다. 다시 가져와 주세요.");if(videoDialog!=null)videoDialog.dismiss();return true;});videoView.setOnPreparedListener(mp->{if(isDistractingBlocked()){enforceVideoSafety();return;}videoView.start();});videoDialog=new AlertDialog.Builder(this).setTitle(m.optString("title")).setView(videoView).setNegativeButton("닫기",null).create();videoDialog.setOnDismissListener(dlg->{if(videoView!=null)videoView.stopPlayback();videoDialog=null;});videoDialog.show();styleDialog(videoDialog);if(videoDialog.getWindow()!=null)videoDialog.getWindow().setLayout(d(Math.min(800,windowWidthDp-48)),d(Math.min(460,windowHeightDp-64)));}
    private void chooseMode(){if(isDrivingInteractionBlocked()){toast("여정을 종료하고 정차한 뒤 모드를 변경하세요.");return;}actionDialogForConnection=false;actionDialog=new AlertDialog.Builder(this).setTitle("위치 소스 선택").setAdapter(optionAdapter(new String[]{"데모 모드 · 샘플 명소 자동 이동","실제 GPS · Android 위치 권한 사용"}),(dialog,which)->{if(isDrivingInteractionBlocked()){toast("정차 후 모드를 바꿔 주세요.");return;}cancelLiveRequest("위치 소스가 바뀌었어요. 출발지를 다시 확인해 새 코스를 만들어 주세요.",true);invalidateApi(false,true);demo=which==0;spotIndex=0;lastLocation=null;refreshOnNextFix=nearbyRequested;if(preparedBrowse)refreshLocalRecommendations(false);if(demo){mapGpsEnabled=false;stopGps();if(kakaoMap!=null)kakaoMap.clearLocation();gpsDetail="GPS 꺼짐";notice="시뮬레이터는 차량 상태와 실제 GPS를 변경하지 않습니다.";}else{requestGps();notice="실제 GPS를 사용합니다. 길 안내·실시간 교통은 제공하지 않습니다.";}render();}).show();styleDialog(actionDialog);}
    private void requestGps(){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)startGps();else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},GPS_PERMISSION);}
    // Stationary users still need recent fixes while a live pipeline takes up to 90 seconds.
    private void startGps(){if((demo&&!mapGpsEnabled)||!resumed)return;if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){gpsDetail="위치 권한 필요";return;}try{boolean active=false;if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0,this);active=true;}if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000,0,this);active=true;}gpsDetail=active?"GPS 수신 대기":"기기 위치 서비스 꺼짐";}catch(Exception e){gpsDetail="위치 서비스 연결 불가";}updateStatus();}
    private void stopGps(){try{if(locationManager!=null)locationManager.removeUpdates(this);}catch(Exception ignored){}}
    public void onLocationChanged(Location l){if(!resumed||(demo&&!mapGpsEnabled)||!validCurrentFix(l))return;lastLocation=l;if(trackingLiveCheckpoints&&!demoDriving)trackCheckpoint(l.getLatitude(),l.getLongitude());if(destinationWaitingForFix){beginDestinationRouting();return;}if(kakaoMap!=null&&!(liveOrigin!=null&&liveOrigin.publicDemo&&!preparedBrowse)){kakaoMap.setLocation(l,centerNextFix);centerNextFix=false;}gpsDetail="GPS ±"+Math.round(l.getAccuracy())+"m";boolean changed=false;
        if(running&&!demo){CourseEngine.Spot current=course.spots[spotIndex];double distance=CourseEngine.distanceMeters(l.getLatitude(),l.getLongitude(),current.lat,current.lon);
            if(l.hasAccuracy()&&l.getAccuracy()<=100&&distance<250){
                if(spotIndex<course.spots.length-1){notice=current.title+" 근처입니다. 위치 오차가 있을 수 있습니다.";spotIndex++;}
                else{running=false;player.pause();notice="마지막 명소 근처에 도착했습니다. 정차 후 기록을 확인해 주세요.";}
                changed=true;
            }
        }
        if(!preparedBrowse&&!running&&liveOrigin!=null&&liveOrigin.moved(currentPosition())){cancelLiveRequest("출발 위치가 1km 이상 달라졌어요. 새 코스를 만들어 주세요.",true);changed=true;}
        if(!preparedBrowse&&liveWaitingForFix&&!running){
            if(isDrivingInteractionBlocked()){cancelLiveRequest("정차한 뒤 코스를 만들어 주세요.",true);changed=true;}else{beginLiveAtCurrentFix();return;}
        }
        if(preparedBrowse&&nearbyRequested&&!running&&!isDrivingInteractionBlocked()&&(refreshOnNextFix||localResult.state==CourseEngine.State.LOCATION_REQUIRED||localResult.state==CourseEngine.State.LOCATION_STALE||Double.isNaN(rankedLat)||CourseEngine.distanceMeters(rankedLat,rankedLon,l.getLatitude(),l.getLongitude())>=1000)){
            refreshOnNextFix=false;refreshLocalRecommendations(false);notice="현재 위치 기준 추천을 갱신했어요. 위치는 기기 안에서만 사용합니다.";changed=true;
        }
        if(changed)render();else updateStatus();enforceVideoSafety();
    }
    public void onProviderEnabled(String p){updateStatus();}public void onProviderDisabled(String p){gpsDetail="위치 제공자 꺼짐";updateStatus();}public void onStatusChanged(String p,int s,Bundle b){}
    public void onRequestPermissionsResult(int req,String[] permissions,int[] results){super.onRequestPermissionsResult(req,permissions,results);if(req==45){parkingPermissionResolved=true;if(resumed&&parkingPermissionPending)resumeParkingPermissionNavigation();}else if(req==GPS_PERMISSION){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){startGps();notice="위치 권한이 연결되었습니다. 현재 위치를 수신하고 있어요.";if(resumed&&resumeDestinationAfterPermission){resumeDestinationAfterPermission=false;requestDestinationRoutes();}}else{gpsDetail="위치 권한 거부";lastLocation=null;if(destinationWaitingForFix||resumeDestinationAfterPermission){resumeDestinationAfterPermission=false;cancelDestinationWork();destinationMessage="위치 권한이 필요해요. 부산역 시연 출발지도 사용할 수 있어요.";}refreshOnNextFix=false;if(preparedBrowse)refreshLocalRecommendations(false);else cancelLiveRequest("위치 권한이 거부되었어요. 서울시청 기준 데모 또는 준비된 코스를 직접 선택할 수 있습니다.",true);notice="위치 권한 없이도 공개 출발지 데모와 준비된 코스를 사용할 수 있습니다.";}render();}else if(req==CAR_PERMISSION){vehicle.stop();vehicle.start();}}
    private void showConnections(){if(running||demoDriving||knownGpsMoving()||(vehicleSpeed!=null&&vehicleSpeed>0.1f)){toast("주행 중 · 정차 후 상세 정보를 확인해 주세요.");return;}String text="위치: "+(journey.usesGps()?gpsDetail:originLabel())+"\n차량: "+vehicleDetail+"\n\nAndroid Car API가 있는 기기에서만 차속과 UX 제한을 읽습니다. AAOS에서 차량 정보를 읽지 못하면 영상 재생을 보수적으로 잠급니다. 일반 Android 데모에서는 실제 정차 여부를 검증하지 않습니다.\n\nPleos NaviHelper로 목적지와 최대 2개 경유지를 순정 내비에 전달합니다. 차량 현재 위치에서 경로를 다시 탐색하며 지도 경로선과 다를 수 있습니다. 차량 카메라와 자동 촬영은 데모입니다. 직접 가져온 영상은 리캡으로 만들 수 있고 공유 미리보기는 기기에만 저장합니다. 음악은 AI가 드라이브에 맞는 플레이리스트를 고르고, 버튼을 누르면 YouTube Music 앱 또는 웹에서 해당 목록을 엽니다. API 서버는 목적지 검색, 같은 도착지의 빠른 길·풍경 길 비교와 카카오 도로 확인을 연결합니다. 카카오 지도는 별도 JavaScript 키로 연결하며 길 안내는 제공하지 않습니다.";if(isCarUiBlocked())text="차량 UX 제한 또는 차속 미확인으로 화면을 잠갔습니다. 정차 상태에서 필요한 차속 권한을 연결해 주세요.";actionDialogForConnection=true;AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("연결 상태 · 프로토타입").setMessage(text).setPositiveButton("확인",null);if(vehicle.isAutomotive()&&checkSelfPermission("android.car.permission.CAR_SPEED")!=PackageManager.PERMISSION_GRANTED)dialog.setNeutralButton("차속 권한 요청",(dlg,w)->requestPermissions(new String[]{"android.car.permission.CAR_SPEED"},CAR_PERMISSION));actionDialog=dialog.show();styleDialog(actionDialog);}
    private void updateStatus(){
        if(statusView==null)return;
        String mode=plannerMode?(liveOrigin!=null?liveOrigin.label():preparedBrowse?"준비된 드라이브 코스":"AI 코스 찾기 · 출발지 선택 전"):(journey.usesGps()?"현재 위치 · "+gpsDetail:originLabel());
        String car=vehicleConnected?(vehicleSpeed==null?"차속 미확인":Math.round(vehicleSpeed)+" km/h"):vehicle!=null&&vehicle.isAutomotive()?"차량 상태 미확인":"일반 Android · 정차 미검증";
        statusView.setText(mode+"   ·   "+(running&&demoDriving?"안내 시연 중":isDistractingBlocked()?"주행 조작 제한":"정차용 화면"));statusView.setContentDescription(mode+". "+car+". "+notice);
        if(soundStatus!=null)soundStatus.setText(musicMessage);if(kakaoMap!=null&&!freshLocation())kakaoMap.clearLocation();if(noticeView!=null)noticeView.setText(notice);
    }
    private void invalidateApi(boolean clearAi,boolean clearRoad){
        if(destinationPlanning||destinationSearching||destinationWaitingForFix||parkingChecking)cancelDestinationWork();
        cancelMusicRecommendation();
        if(liveRequestActive&&apiRequests.isPending(ApiRequestGate.AI)){liveRequestActive=false;liveMessage="코스 생성을 취소했습니다. 다시 요청할 수 있어요.";}
        boolean pending=false;
        for(int i=0;i<apiJobs.length;i++){
            pending|=apiRequests.isPending(i);
            if(apiClients[i]!=null){apiClients[i].cancel();apiClients[i]=null;}
            if(apiJobs[i]!=null){apiJobs[i].cancel(true);apiJobs[i]=null;}
        }
        apiRequests.invalidate();
        if(kakaoMap!=null&&clearRoad)kakaoMap.setScene(course,spotIndex,null);
        if(pending)apiMessage="요청을 취소했습니다. 정차 후 다시 조회해 주세요.";

        if(clearRoad){roadCoordinates=null;roadCourseId=null;if(map!=null)map.clearRoadRoute();}
    }
    private interface ApiWork { JSONObject run(ScenicApiClient client) throws Exception; }
    private interface ApiResult { void accept(JSONObject result) throws Exception; }
    private void runApi(int kind,String loading,ApiWork work,ApiResult result){
        if(!resumed||!canChangeRoute()||apiRequests.isPending(kind))return;
        final ScenicApiClient client;
        try{client=new ScenicApiClient(apiBaseUrl);}catch(IllegalArgumentException e){if(kind==ApiRequestGate.AI){liveRequestActive=false;liveMessage=e.getMessage();render();}toast(e.getMessage());return;}
        final long token=apiRequests.begin(kind);
        apiMessage=loading;render();
        apiClients[kind]=client;
        apiJobs[kind]=apiExecutor.submit(()->{
            JSONObject response=null;String failure=null;
            try{response=work.run(client);}catch(ScenicApiClient.ApiException e){failure=e.getMessage();}
            catch(Exception e){failure="요청을 처리하지 못했습니다. 서버 상태를 확인해 주세요.";}
            final JSONObject payload=response;final String error=failure;
            handler.post(()->{
                if(!apiRequests.finish(kind,token))return;
                apiJobs[kind]=null;apiClients[kind]=null;
                if(!resumed||isDestroyed()||isFinishing())return;
                if(isDrivingInteractionBlocked()){if(kind==ApiRequestGate.AI){liveRequestActive=false;liveMessage="주행 상태로 바뀌어 코스 결과를 적용하지 않았어요. 정차 후 다시 요청해 주세요.";}render();return;}
                if(error!=null){apiMessage=error;if(kind==ApiRequestGate.AI){liveRequestActive=false;liveMessage=error+" 다시 요청하거나 준비된 코스를 둘러보세요.";}render();return;}
                try{result.accept(payload);}catch(Exception e){apiMessage="서버 응답을 표시할 수 없습니다. 서버 버전과 응답을 확인해 주세요.";if(kind==ApiRequestGate.AI){liveRequestActive=false;livePlan=null;liveMessage="도로·시간 검증 응답이 올바르지 않아 코스를 표시하지 않았어요. 다시 요청하거나 준비된 코스를 둘러보세요.";}}
                render();
            });
        });
    }
    private void showApiSettings(){
        if(!canChangeRoute())return;
        LinearLayout form=col();form.setPadding(d(22),d(10),d(22),d(8));
        TextView help=txt("",16,MUTED);help.setText("개발 컴퓨터에서 SCENIC API 서버를 실행해 주세요.\nPleos 에뮬레이터 기본 주소: http://10.0.2.2:4173\n카카오·OpenAI 키는 프로젝트의 .env에 입력합니다.");form.addView(help);
        EditText address=new EditText(this);address.setTypeface(ScenicTypography.font(this,400));address.setSingleLine(true);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setText(apiBaseUrl);form.addView(address);
        TextView status=txt("",15,MUTED);status.setText(apiMessage);form.addView(status);
        actionDialogForConnection=false;
        final AlertDialog dialog=new AlertDialog.Builder(this).setTitle("API 서버 연결").setView(form).setNegativeButton("닫기",null).setNeutralButton("기본 주소",null).setPositiveButton("저장 및 확인",null).create();
        actionDialog=dialog;dialog.show();styleDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{address.setText(EndpointPolicy.normalizeBaseUrl("http://10.0.2.2:4173"));address.setError(null);});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!canChangeRoute())return;
            try{
                String next=EndpointPolicy.normalizeBaseUrl(address.getText().toString());
                if(!next.equals(apiBaseUrl))cancelLiveRequest("연결 서버가 바뀌었어요. 새 코스를 요청해 주세요.",true);invalidateApi(!next.equals(apiBaseUrl),!next.equals(apiBaseUrl));
                if(!next.equals(apiBaseUrl)){if(selectedScenicPhoto!=null)destinationPhotoBrowsing=true;forgetScenicPhoto();if(destinationPhotoView!=null)destinationPhotoView.pause();clearDestinationPlan("서버가 바뀌었어요. 사진과 경로를 다시 확인해 주세요.");}
                apiBaseUrl=next;getPreferences(0).edit().putString("api_base_url",next).apply();
                dismissActionDialog();checkApiStatus();
            }catch(IllegalArgumentException e){address.setError(e.getMessage());}
        });
    }
    private void checkApiStatus(){
        runApi(ApiRequestGate.STATUS,"API 서버 연결 확인 중…",ScenicApiClient::getStatus,response->{
            boolean kakao=response.getJSONObject("routing").getBoolean("configured");
            boolean openai=response.getJSONObject("ai").getBoolean("configured");
            apiMessage="서버 연결됨 · 카카오 키 "+(kakao?"설정됨":"없음")+" · OpenAI 키 "+(openai?"설정됨":"없음")+"\n키 인증은 각 기능을 실행할 때 확인됩니다.";
            toast("API 서버 연결을 확인했습니다.");
        });
    }
    private void requestRoadRoute(){
        if(isLiveSelection()){toast("이 코스는 출발지 접근을 포함해 도로 확인을 마쳤어요.");return;}
        if(!hasBudgetCourse())return;
        final CourseEngine.Course requestedCourse=course;
        runApi(ApiRequestGate.ROUTE,"카카오 도로 경로 조회 중…",client->client.route(requestedCourse),response->{
            if(!"Kakao Mobility".equals(response.getString("source")))throw new JSONException("source");
            JSONArray points=response.getJSONArray("coordinates");
            if(points.length()<2||points.length()>50000)throw new JSONException("coordinates");
            double[][] coordinates=new double[points.length()][2];
            for(int i=0;i<points.length();i++){
                JSONArray point=points.getJSONArray(i);if(point.length()!=2)throw new JSONException("point");
                double lat=point.getDouble(0),lon=point.getDouble(1);
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new JSONException("point");
                coordinates[i][0]=lat;coordinates[i][1]=lon;
            }
            double distance=response.getDouble("distanceKm"),duration=response.getDouble("durationMin");
            if(!Double.isFinite(distance)||!Double.isFinite(duration)||distance<0||duration<0)throw new JSONException("metrics");
            roadCoordinates=coordinates;roadCourseId=requestedCourse.id;roadDistanceKm=distance;roadDurationMin=duration;
            apiMessage="카카오 도로 조회 완료 · 선택한 코스의 도로 경로를 표시합니다.";
        });
    }
    // Destination-first navigation prototype. Public demo coordinates never replace GPS.
    private String destinationLabel(){return destinationPlace==null?"목적지를 선택하세요":destinationPlace.name;}
    private String originLabel(){return journey==null?"부산역 · 시연 출발지":journey.originLabel();}
    private String segmentLabel(){
        if(chosenDestinationRoute==null||chosenDestinationRoute.waypoints.isEmpty())return "목적지로 이어지는 길";
        List<String> titles=new ArrayList<>();for(CourseEngine.Spot stop:chosenDestinationRoute.waypoints)titles.add(stop.title);
        return android.text.TextUtils.join(" → ",titles);
    }
    private String destinationRouteId(){return chosenDestinationRoute==null?null:chosenDestinationRoute.toCourse().id;}
    private String destinationRouteName(){return chosenDestinationRoute==null?"":destinationLabel()+"까지 · "+chosenDestinationRoute.label();}
    private boolean destinationInteractionAllowed(){return resumed&&!isCarUiBlocked()&&!knownGpsMoving()&&(!running||demoDriving);}

    private void initDestinationExperience(){
        try{destinationPlace=DestinationPlace.of("7913306","해운대해수욕장","부산 해운대구 우동",35.1585232170784,129.159854668484);}catch(JSONException impossible){throw new IllegalStateException(impossible);}
        journey=JourneyState.restore(getPreferences(0).getString("journey_v2",""));if(journey.target()==null){journey.beginDestination(destinationPlace);saveJourney();}else destinationPlace=journey.target();
        regionPlace=journey.region();regionPhotoMode=regionPlace!=null&&(journey.scenic()!=null?journey.matchesScenicDestination(journey.scenic()):sameJourneyPlace(destinationPlace,regionPlace)&&getPreferences(0).getBoolean("region_photo_flow",false));
        String savedPhoto=getPreferences(0).getString("selected_photo_v1","");selectedScenicPhoto=ScenicPhotoCollection.restoreSelection(savedPhoto,regionPhotoMode?regionPlace:destinationPlace);
        if(regionPhotoMode&&selectedScenicPhoto!=null&&!journey.matchesScenicDestination(selectedScenicPhoto.place))selectedScenicPhoto=null;
        if(selectedScenicPhoto!=null)destinationTheme=selectedScenicPhoto.scene;
        if(journey.requiresDirectRoute())detourMinutes=0;
        if(!savedPhoto.isEmpty()&&selectedScenicPhoto==null){destinationPhotoBrowsing=true;destinationMessage="이전에 선택한 풍경 사진을 다시 확인해 주세요.";}
        captureDemo=new ScenicCaptureDemo(this,new ScenicCaptureDemo.Host(){
            public boolean canInteract(){return destinationInteractionAllowed();}
            public void onCaptureSaved(ScenicCaptureStore.Record record){destinationMessage="풍경을 저장했어요. ‘리캡’에서 음악이 있는 추억 영상으로 만드세요.";if(resumed&&!isDestroyed())render();}
        });
    }
    private void cancelDestinationWork(){
        cancelParkingCheck();
        if(nativeNavigation!=null)nativeNavigation.pause();
        destinationRevision++;destinationWaitingForFix=false;pendingParkingNavigation=false;
        if(destinationClient!=null){destinationClient.cancel();destinationClient=null;}
        if(destinationJob!=null){destinationJob.cancel(true);destinationJob=null;}
        destinationPlanning=false;destinationSearching=false;
    }
    private void clearDestinationPlan(String message){
        resumeDestinationAfterPermission=false;cancelDestinationWork();cancelMusicRecommendation();destinationPlan=null;chosenDestinationRoute=null;roadCoordinates=null;roadCourseId=null;destinationMessage=message;
    }
    private void renderDestinationRoutes(){
        final boolean photoTrip=regionPhotoMode&&selectedScenicPhoto!=null;
        LinearLayout left=section();addScrollableColumn(left,.35f);panelSpace();
        left.addView(title(photoTrip?"사진 속 풍경으로":"어디로 갈까요?",28));if(journey.region()!=null){gap(left,8);left.addView(txt(journey.region().name+" · 지역 → 풍경 → 맛집 → 주차",14,MUTED));}left.addView(textAction("목적지를 풍경 피드에서 고르기  ↗",()->switchExperienceTab(3)),lp(-1,-2));gap(left,12);
        left.addView(title(photoTrip||journey.requiresDirectRoute()?"목적지":"1   목적지",17));gap(left,8);
        TextView destination=button(destinationPlace==null?"목적지 검색":destinationPlace.name+"  ⌕",false,this::showDestinationSearch);destination.setGravity(Gravity.CENTER_VERTICAL);destination.setTextSize(22);left.addView(destination,lp(-1,-2));
        if(destinationPlace!=null){gap(left,8);left.addView(txt(destinationPlace.address,14,MUTED));}
        gap(left,12);left.addView(button(selectedScenicPhoto==null?"풍경 사진으로 경유지 고르기":"다른 풍경 사진 고르기",false,this::openDestinationPhotos),lp(-1,-2));
        if(selectedScenicPhoto!=null){gap(left,8);left.addView(txt("선택한 풍경 · "+selectedScenicPhoto.place.name,16,INK));left.addView(txt(photoTrip?"선택한 사진의 장소로 갑니다. 정확한 촬영 위치·방향은 확인되지 않았어요.":"원래 목적지 앞에 경유지로 추가합니다. 장소 사진이며 정확한 촬영 지점·차창 조망은 미확인입니다.",14,MUTED));left.addView(textAction("풍경 선택 해제",()->{if(!canChangeRoute())return;forgetScenicPhoto();if(regionPhotoMode&&regionPlace!=null){journey.beginRegion(regionPlace);destinationPlace=regionPlace;destinationPhotoBrowsing=true;}else journey.setScenicWaypoint(null);saveJourney();clearDestinationPlan("풍경 선택을 해제했어요.");render();}),lp(-1,-2));}
        gap(left,12);LinearLayout source=row();source.addView(txt("출발",14,MUTED),lp(42,-2));source.addView(textAction(originLabel()+"  ›",this::showDestinationOrigin),new LinearLayout.LayoutParams(0,-2,1));left.addView(source);gap(left,8);divider(left);gap(left,20);
        if(!journey.requiresDirectRoute()){LinearLayout budgetHeading=row();budgetHeading.addView(title("2   풍경 길에 쓸 여유 시간",17),new LinearLayout.LayoutParams(0,-2,1));budgetHeading.addView(txt(detourMinutes<=0?"빠른 길만":"최대 +"+detourMinutes+"분",16,INK));left.addView(budgetHeading);gap(left,6);left.addView(txt("빠른 길보다 이만큼 더 걸려도 괜찮다면 풍경 길을 함께 보여드려요.",13,MUTED));gap(left,10);
        LinearLayout budgets=row();for(int i=0;i<3;i++){final int value=new int[]{15,30,60}[i];if(i>0)space(budgets,8);budgets.addView(chip("+"+value+"분",detourMinutes==value,()->setDetourMinutes(value)),new LinearLayout.LayoutParams(0,-2,1));}left.addView(budgets);left.addView(textAction("시간 직접 입력  ＋",this::showDetourInput),lp(-1,-2));gap(left,8);
        if(selectedScenicPhoto==null||photoTrip)addDestinationSceneryChoices(left);}else{left.addView(txt("선택한 식당·주차장으로 바로 갑니다.\n다른 관광지를 자동으로 경유하지 않아요.",15,MUTED));}gap(left,20);
        boolean busy=destinationPlanning||destinationWaitingForFix||parkingChecking;
        TextView compare=button(parkingChecking?"주차 빈자리 확인 중…":destinationWaitingForFix?"현재 위치 확인 중…":destinationPlanning?"도로 확인 중…":journey.requiresDirectRoute()?"경로 확인":photoTrip?(detourMinutes<=0?"이 풍경까지 경로 찾기":"이 풍경까지 · 풍경 길 비교"):detourMinutes<=0?"빠른 길 확인":"빠른 길 · 풍경 길 비교",true,this::requestDestinationRoutes);setBusy(compare,busy||destinationPlace==null);left.addView(compare,lp(-1,-2));
        if(busy){gap(left,12);ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);left.addView(progress,lp(-1,3));left.addView(textAction("취소",()->{cancelDestinationWork();destinationMessage="경로 찾기를 취소했어요.";render();}),lp(-1,-2));}
        if(!destinationMessage.isEmpty()){gap(left,12);left.addView(txt(destinationMessage,14,MUTED));}
        if(destinationPlan!=null){gap(left,20);divider(left);gap(left,20);left.addView(title(photoTrip?"선택한 풍경까지":journey.requiresDirectRoute()?"선택한 장소까지":"같은 목적지, 다른 길",19));gap(left,12);for(DestinationRoutePlan.Route option:destinationPlan.routes){left.addView(destinationRouteCard(option),lp(-1,-2));gap(left,10);}}
        LinearLayout right=col();addScrollableColumn(right,.65f);
        if(chosenDestinationRoute==null){renderDestinationWelcome(right,busy);return;}
        renderDestinationMap(right);
    }
    private void selectDestinationScenery(String id){
        if(!canChangeRoute()||destinationTheme.equals(id))return;
        destinationTheme=id;clearDestinationPlan("풍경 조건을 바꿨어요. 경로를 다시 비교해 주세요.");render();
    }
    private void addDestinationSceneryChoices(LinearLayout parent){
        LinearLayout heading=row();heading.addView(title("3   보고 싶은 풍경",17),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(chip("모든 풍경",destinationTheme.equals("all"),()->selectDestinationScenery("all")),lp(112,-2));parent.addView(heading);gap(parent,10);
        String[] ids={"coast","mountain","forest","water","countryside","city"};
        String[] labels={"바다 · 해안","산 · 전망","숲 · 나무길","강 · 호수","들판 · 농촌","도시 풍경"};
        int columns=wide&&windowWidthDp>=1400&&getResources().getConfiguration().fontScale<=1.3f?3:2;
        for(int start=0;start<ids.length;start+=columns){
            LinearLayout choices=row();
            for(int i=start;i<Math.min(start+columns,ids.length);i++){
                final String id=ids[i];if(i>start)space(choices,8);
                choices.addView(chip(labels[i],destinationTheme.equals(id),()->selectDestinationScenery(id)),new LinearLayout.LayoutParams(0,-2,1));
            }
            parent.addView(choices,lp(-1,-2));if(start+columns<ids.length)gap(parent,8);
        }
    }
    /** The server's one-line headline, or the first sentence of the reason for an older server. */
    private static String cardHeadline(DestinationRoutePlan.Route option){
        if(!option.headline.isEmpty())return option.headline;
        int end=option.reason.indexOf(". ");return end>0?option.reason.substring(0,end+1):option.reason;
    }
    /** Only the measured figures ("해안 47km", "산지 59km"), not the place names that the headline already carries. */
    private static String cardFacts(DestinationRoutePlan.Route option){
        java.util.ArrayList<String> facts=new java.util.ArrayList<>();
        for(String label:option.scenicLabels)if(label.contains("km"))facts.add(label);
        return android.text.TextUtils.join(" · ",facts);
    }
    private LinearLayout destinationRouteCard(DestinationRoutePlan.Route option){
        boolean selected=option==chosenDestinationRoute,scenic=!option.isFast();LinearLayout item=col();item.setPadding(d(20),d(20),d(20),d(20));item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182c6656),bg(selected?SOFT:PANEL,selected?ACCENT:LINE),null));
        boolean curated="curated".equals(option.id);TextView kind=txt(option.isFast()?"가장 빠름":curated?"검증된 코스":"풍경 분석",12,option.isFast()?MUTED:0xff1d7a4a);kind.setPadding(d(10),d(5),d(10),d(5));kind.setBackground(bg(option.isFast()?0xffeef0ef:0xffe3f3ea,0));item.addView(kind,lp(-2,-2));gap(item,8);
        LinearLayout heading=row();heading.addView(title(regionPhotoMode&&selectedScenicPhoto!=null&&option.isFast()?"사진 장소로 가는 길":option.label(),21),new LinearLayout.LayoutParams(0,-2,1));heading.addView(title((int)Math.ceil(option.durationMin)+"분",24));item.addView(heading);gap(item,10);
        String extra=scenic&&destinationPlan!=null?" · 빠른 길보다 +"+(int)Math.ceil(Math.max(0,option.durationMin-destinationPlan.fast.durationMin))+"분":"";
        item.addView(txt(String.format(Locale.KOREA,"%.1f km",option.distanceKm)+extra,15,scenic?INK:MUTED));gap(item,8);
        // A card is a glance: one headline and the measured facts. The full explanation is in the selected route's panel.
        item.addView(txt(scenic?cardHeadline(option):"도착 시간을 가장 우선한 길",16,INK));
        String facts=scenic?cardFacts(option):"";
        if(!facts.isEmpty()){gap(item,6);item.addView(txt(facts,14,MUTED));}
        if(!option.checkpoints.isEmpty()){gap(item,6);item.addView(txt("촬영 지점 "+option.checkpoints.size()+"곳 · 기록 "+captureDemo.checkpointRecordCount(option.toCourse(originLabel()).id,option.checkpoints)+"개",13,MUTED));}
        item.setClickable(true);item.setFocusable(true);item.setSelected(selected);item.setContentDescription(option.label()+", "+(int)Math.ceil(option.durationMin)+"분"+(selected?", 선택됨":""));item.setOnClickListener(v->{if(canChangeRoute()){selectDestinationRoute(option);if(selectedScenicPhoto!=null&&!regionPhotoMode&&!"scenic".equals(option.id))destinationMessage="빠른 길을 선택해 선택한 풍경은 경유하지 않습니다.";render();}});return item;
    }
    private void renderDestinationWelcome(LinearLayout right,boolean busy){
        final boolean direct=journey.requiresDirectRoute();
        LinearLayout welcome=card();right.addView(welcome,lp(-1,-2));welcome.addView(txt("SCENIC ROUTE",14,MUTED));gap(welcome,18);boolean photoPick=regionPhotoMode&&selectedScenicPhoto!=null;welcome.addView(title(destinationLabel()+(direct?"까지,\n바로 가는 길":photoPick?"까지,\n경로를 확인해요":"까지,\n어떤 길로 갈까요?"),34));gap(welcome,18);
        if(direct||regionPhotoMode)welcome.addView(txt(direct?"선택한 장소까지 바로 안내합니다.":"사진 속 장소로 가는 길과 주변 먹거리를 준비하세요.",18,INK));
        else{String[] steps={"1   왼쪽에서 여유 시간과 보고 싶은 풍경을 고르세요","2   빠른 길과 풍경 길을 한눈에 비교하세요","3   마음에 드는 길로 순정 내비 안내를 시작하세요"};for(int i=0;i<steps.length;i++){if(i>0)gap(welcome,8);welcome.addView(txt(steps[i],18,INK));}}
        gap(welcome,24);
        LinearLayout trip=row();trip.addView(txt(originLabel(),17,INK),new LinearLayout.LayoutParams(0,-2,1));space(trip,20);trip.addView(txt("→",22,MUTED));space(trip,20);trip.addView(title(destinationLabel(),20),new LinearLayout.LayoutParams(0,-2,1));welcome.addView(trip);gap(welcome,24);divider(welcome);gap(welcome,20);
        welcome.addView(txt(direct?(busy?"선택한 장소까지 자동차로 접근할 수 있는 도로를 확인하고 있어요.":"경로를 확인한 뒤 순정 내비로 안내를 시작할 수 있어요."):regionPhotoMode?"선택한 풍경이 방문 목적지입니다. 정확한 촬영 위치와 시야는 현장에서 확인해 주세요.":busy?"같은 도착지를 유지하며 도로와 추가 시간을 확인하고 있어요.":"도착지는 그대로예요. 가는 길에 만날 풍경만 더해요.",16,MUTED));gap(right,20);
        if(selectedScenicPhoto!=null){right.addView(destinationPhotoView.createSelectionPreview(selectedScenicPhoto,regionPhotoMode),lp(-1,-2));}
        else if(!direct){right.addView(button("목적지 주변 풍경 사진에서 고르기",true,this::openDestinationPhotos),lp(-1,-2));gap(right,12);right.addView(txt("사진을 고르면 해당 장소를 거쳐 원래 목적지로 가는 도로를 찾습니다.",16,MUTED));}
    }
    private void renderDestinationMap(LinearLayout right){
        DestinationRoutePlan.Route route=chosenDestinationRoute;boolean scenic=!route.isFast();
        LinearLayout panel=card();right.addView(panel,lp(-1,-2));LinearLayout heading=row();heading.addView(title(destinationLabel()+"까지",27),new LinearLayout.LayoutParams(0,-2,1));heading.addView(txt(regionPhotoMode&&selectedScenicPhoto!=null&&route.isFast()?"사진 속 장소 방문":route.label(),16,INK));panel.addView(heading);gap(panel,10);panel.addView(txt(originLabel()+" → "+destinationLabel(),15,MUTED));gap(panel,20);
        LinearLayout metrics=row();addMetric(metrics,(int)Math.ceil(route.durationMin)+"분","예상 이동 시간");addMetric(metrics,String.format(Locale.KOREA,"%.1f km",route.distanceKm),"도로 거리");addMetric(metrics,scenic?"+"+(int)Math.ceil(Math.max(0,route.durationMin-destinationPlan.fast.durationMin))+"분":"기준 경로",scenic?"빠른 길 대비":"시간 우선");panel.addView(metrics);gap(panel,12);
        if(scenic){panel.addView(txt(route.reason,13,MUTED));gap(panel,4);panel.addView(txt("지도 분석 출처: © OpenStreetMap contributors · 고도 AWS Terrain Tiles · 코스 한국관광공사 TourAPI",12,MUTED));}
        gap(panel,16);
        if(running&&demoDriving){LinearLayout guidance=col();guidance.setPadding(d(16),d(14),d(16),d(14));guidance.setBackground(bg(SOFT,0));guidance.addView(title("경로 안내 · 시연",18));gap(guidance,8);guidance.addView(txt(navigationDemoStep==0?"선택한 길을 따라 목적지로 출발합니다.":!route.checkpoints.isEmpty()?(navigationDemoStep>=1&&navigationDemoStep<=route.checkpoints.size()?"촬영 지점 · "+route.checkpoints.get(navigationDemoStep-1).name+(captureDemo.autoCaptureEnabled()?" · 자동 기록(시연)":" · 자동 기록 꺼짐"):"목적지에 가까워지고 있어요."):navigationDemoStep==1?segmentLabel()+" 구간 · 빌트인 캠 풍경 저장 시연":"목적지에 가까워지고 있어요.",16,INK));gap(guidance,8);ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(demoTotalSteps());progress.setProgress(Math.min(demoTotalSteps(),navigationDemoStep+1));guidance.addView(progress,lp(-1,4));panel.addView(guidance);gap(panel,14);}
        CourseEngine.Course mapCourse=route.toCourse(originLabel());course=mapCourse;
        int height=Math.max(240,Math.min(370,ScenicLayoutPolicy.mapHeight(windowWidthDp,windowHeightDp,wide)));
        try{if(kakaoMap==null)kakaoMap=new KakaoMapView(this);kakaoMap.setBackground(bg(SOFT,0));kakaoMap.setClipToOutline(true);kakaoMap.loadServer(apiBaseUrl);kakaoMap.setScene(mapCourse,running?Math.min(navigationDemoStep,mapCourse.spots.length-1):0,route.coordinates);if(journey.usesGps()&&freshLocation())kakaoMap.setLocation(lastLocation,false);else kakaoMap.clearLocation();panel.addView(kakaoMap,lp(-1,height));if(resumed)kakaoMap.onResume();}
        catch(RuntimeException unavailable){panel.addView(txt("지도를 표시하지 못했어요. 설정에서 서버 연결을 확인해 주세요.",16,MUTED));}
        panel.addView(textAction("지도 새로고침",()->{if(destinationInteractionAllowed()&&kakaoMap!=null)kakaoMap.reload();}),lp(-1,-2));gap(panel,12);addNativeNavigation(panel);addParkingWatchControls(panel);
        if(journey.scenic()!=null||selectedScenicPhoto!=null){gap(panel,16);panel.addView(button("풍경 주변 맛집과 주차 찾기  →",false,this::openScenicDining),lp(-1,-2));}
        gap(panel,24);divider(panel);gap(panel,20);LinearLayout actions=row();actions.addView(button(running?"시연 종료":"안내 시연 시작",false,this::toggleDestinationDemo),new LinearLayout.LayoutParams(0,-2,1));space(actions,12);actions.addView(button("빌트인 캠 촬영 시연",false,()->captureDemo.showCapture(destinationRouteId(),destinationRouteName(),destinationLabel(),segmentLabel())),new LinearLayout.LayoutParams(0,-2,1));panel.addView(actions);gap(panel,12);panel.addView(txt("빌트인 캠 촬영은 공개 사진을 사용하는 시연입니다.",14,MUTED));
        if(journey.parking()!=null&&journey.restaurant()!=null){gap(panel,20);panel.addView(title("주차 후 방문할 식당",18));gap(panel,8);panel.addView(txt(journey.restaurant().name+" · "+journey.restaurant().address,16,INK));panel.addView(txt("주차장→식당 직선 "+Math.round(CourseEngine.distanceMeters(journey.parking().lat,journey.parking().lon,journey.restaurant().lat,journey.restaurant().lon))+"m · 실제 입구와 도보 경로는 미확인",13,MUTED));gap(panel,8);panel.addView(button("식당 위치 확인",false,()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://place.map.kakao.com/"+journey.restaurant().id)));}catch(Exception unavailable){toast("장소 정보를 열 앱이 없어요.");}}),lp(-1,-2));}
        gap(right,18);if(selectedScenicPhoto!=null){right.addView(destinationPhotoView.createSelectionPreview(selectedScenicPhoto,regionPhotoMode),lp(-1,-2));}else right.addView(captureDemo.createPreview(destinationRouteId(),destinationRouteName(),destinationLabel(),segmentLabel(),chosenDestinationRoute==null?java.util.Collections.<RouteCheckpoint>emptyList():chosenDestinationRoute.checkpoints),lp(-1,-2));
    }
    private void selectDestinationRoute(DestinationRoutePlan.Route route){
        cancelParkingCheck();
        if(nativeNavigation!=null)nativeNavigation.pause();
        cancelMusicRecommendation();chosenDestinationRoute=route;checkpointTracker=new CheckpointTracker(route.checkpoints);demoCheckpointSaves=0;trackingLiveCheckpoints=false;if(selectedScenicPhoto!=null&&!regionPhotoMode){journey.setScenicWaypoint("scenic".equals(route.id)?selectedScenicPhoto.place:null);saveJourney();}course=route.toCourse(originLabel());roadCoordinates=route.coordinates;roadCourseId=course.id;roadDistanceKm=route.distanceKm;roadDurationMin=route.durationMin;spotIndex=0;destinationMessage="";
    }
    private void showDestinationOrigin(){
        if(!canChangeRoute())return;actionDialogForConnection=false;
        actionDialog=new AlertDialog.Builder(this).setTitle("출발지").setAdapter(optionAdapter(new String[]{"현재 위치 사용","부산역 · 시연 출발지","서울시청 · 시연 출발지"}),(dialog,which)->{
            if(!canChangeRoute())return;cancelLiveRequest("출발지가 바뀌었어요. 새 코스를 만들어 주세요.",true);clearDestinationPlan("");boolean useGps=which==0;
            if(useGps){journey.setOrigin(true,journey.originLat(),journey.originLon(),"현재 위치");mapGpsEnabled=true;requestGps();destinationMessage="경로 비교 시 현재 위치를 사용합니다.";}else{journey.setOrigin(false,which==2?37.5665:35.1151,which==2?126.9780:129.0414,which==2?"서울시청 · 시연 출발지":"부산역 · 시연 출발지");mapGpsEnabled=false;stopGps();lastLocation=null;destinationMessage=journey.originLabel()+"를 사용합니다.";}saveJourney();render();
        }).setNegativeButton("닫기",null).show();styleDialog(actionDialog);
    }
    private void setDetourMinutes(int value){if(!canChangeRoute())return;detourMinutes=value;clearDestinationPlan("추가 시간을 바꿨어요. 경로를 다시 비교해 주세요.");render();}
    private void showDetourInput(){
        if(!canChangeRoute())return;LinearLayout form=col();form.setPadding(d(24),d(8),d(24),d(16));form.addView(txt("빠른 길보다 더 쓸 수 있는 시간",16,MUTED));gap(form,10);EditText input=new EditText(this);input.setTypeface(ScenicTypography.font(this,400));input.setTextSize(24);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);input.setText(String.valueOf(detourMinutes));input.setSelectAllOnFocus(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});form.addView(input,lp(-1,-2));gap(form,8);form.addView(txt("0~120분",14,MUTED));
        actionDialogForConnection=false;AlertDialog dialog=new AlertDialog.Builder(this).setTitle("추가 시간").setView(form).setNegativeButton("취소",null).setPositiveButton("적용",null).create();actionDialog=dialog;dialog.show();styleDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!canChangeRoute())return;try{String value=input.getText().toString().trim();if(!value.matches("[0-9]{1,3}"))throw new NumberFormatException();int minutes=Integer.parseInt(value);if(minutes>120)throw new NumberFormatException();dismissActionDialog();setDetourMinutes(minutes);}catch(NumberFormatException invalid){input.setError("0~120 사이의 정수를 입력해 주세요.");}});input.requestFocus();
    }
    private void showDestinationSearch(){showDestinationSearch(false);}
    private void showDestinationSearch(boolean regionalSearch){
        if(!canChangeRoute())return;cancelDestinationWork();LinearLayout form=col();form.setPadding(d(24),d(8),d(24),d(16));EditText input=new EditText(this);input.setTypeface(ScenicTypography.font(this,400));input.setSingleLine(true);input.setTextSize(22);input.setHint(regionalSearch?"강릉, 여수, 남해…":"해운대, 속초해수욕장…");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});form.addView(input,lp(-1,-2));gap(form,10);
        LinearLayout searchResults=col();TextView searchStatus=txt("장소 이름이나 주소로 검색하세요.",15,MUTED);searchResults.addView(searchStatus);ScrollView resultsScroll=new ScrollView(this);resultsScroll.addView(searchResults);form.addView(resultsScroll,lp(-1,Math.max(130,Math.min(330,windowHeightDp-250))));
        actionDialogForConnection=false;AlertDialog dialog=new AlertDialog.Builder(this).setTitle(regionalSearch?"가고 싶은 지역 검색":"목적지 검색").setView(form).setNegativeButton("닫기",null).setPositiveButton("검색",null).create();actionDialog=dialog;dialog.show();styleDialog(dialog);
        dialog.setOnDismissListener(d->{if(destinationSearching)cancelDestinationWork();if(actionDialog==dialog)actionDialog=null;});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String query=input.getText().toString().trim();if(query.isEmpty()){input.setError("목적지를 입력해 주세요.");return;}if(!canChangeRoute())return;cancelDestinationWork();destinationSearching=true;final long revision=destinationRevision;final ScenicApiClient client=new ScenicApiClient(apiBaseUrl);destinationClient=client;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);searchResults.removeAllViews();searchResults.addView(txt("목적지를 찾고 있어요…",16,MUTED));
            destinationJob=apiExecutor.submit(()->{List<DestinationPlace> places=null;String failure=null;try{places=DestinationPlace.parseSearch(client.searchDestinations(query));}catch(ScenicApiClient.ApiException error){failure=error.getMessage();}catch(Exception error){failure="검색 결과를 확인하지 못했어요. 다시 시도해 주세요.";}final List<DestinationPlace> found=places;final String error=failure;
                handler.post(()->{if(!resumed||isDestroyed()||isFinishing()||revision!=destinationRevision||!dialog.isShowing())return;destinationSearching=false;destinationClient=null;destinationJob=null;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);searchResults.removeAllViews();if(error!=null||found==null||found.isEmpty()){searchResults.addView(txt(error==null?"검색 결과가 없습니다. 다른 이름으로 검색해 주세요.":error,16,MUTED));return;}
                    for(DestinationPlace place:found){LinearLayout item=col();item.setPadding(0,d(16),0,d(16));item.addView(title(place.name,20));gap(item,6);item.addView(txt(place.address,15,MUTED));item.setClickable(true);item.setFocusable(true);item.setContentDescription(place.name+", "+place.address);item.setOnClickListener(view->{if(!canChangeRoute())return;if(regionalSearch)openRegion(place);else chooseSearchedDestination(place);});searchResults.addView(item,lp(-1,-2));divider(searchResults);}
                });});});
        input.requestFocus();
    }
    private void requestDestinationRoutes(){requestDestinationRoutes(false);}
    private void requestDestinationRoutes(boolean navigateToParking){
        if(!canChangeRoute()||destinationPlace==null||destinationPlanning)return;
        if(regionPhotoMode&&selectedScenicPhoto==null){openDestinationPhotos();return;}
        clearDestinationPlan("");pendingParkingNavigation=navigateToParking;
        if(journey.usesGps()&&!freshLocation()){
            mapGpsEnabled=true;destinationWaitingForFix=true;final long revision=destinationRevision;destinationMessage="현재 위치를 받고 있어요.";resumeDestinationAfterPermission=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED;requestGps();render();
            handler.postDelayed(()->{if(destinationWaitingForFix&&revision==destinationRevision){cancelDestinationWork();destinationMessage="현재 위치를 받지 못했어요. 출발지에서 부산역 시연을 선택할 수 있어요.";if(resumed)render();}},20000);return;
        }
        beginDestinationRouting();
    }
    private void beginDestinationRouting(){
        if(!resumed||!canChangeRoute()||destinationPlace==null)return;destinationWaitingForFix=false;
        if(journey.usesGps()&&(!freshLocation()||!CourseEngine.inKorea(lastLocation.getLatitude(),lastLocation.getLongitude()))){destinationMessage="국내 위치를 받은 뒤 이용하거나 부산역 시연 출발지를 선택해 주세요.";render();return;}
        final double lat=journey.usesGps()?lastLocation.getLatitude():journey.originLat(),lon=journey.usesGps()?lastLocation.getLongitude():journey.originLon();
        if(journey.usesGps()){journey.setOrigin(true,lat,lon,"현재 위치");saveJourney();}
        final boolean usedGps=journey.usesGps();final DestinationPlace target=destinationPlace;final int extra=journey.requiresDirectRoute()?0:detourMinutes;final int photoExtra=detourMinutes;final String theme=destinationTheme;final ScenicPhotoCollection.Photo requestedPhoto=selectedScenicPhoto;final long revision=destinationRevision;
        final DestinationPlace requestedRegion=regionPhotoMode?regionPlace:null;final boolean photoDestination=requestedRegion!=null&&requestedPhoto!=null;final boolean navigateParking=pendingParkingNavigation;pendingParkingNavigation=false;final boolean parkingDestination=sameJourneyPlace(target,journey.parking());
        destinationPlanning=true;destinationMessage=photoDestination?"사진 속 장소로 가는 도로를 확인하고 있어요. 지역 중심으로 돌아가지 않습니다.":requestedPhoto==null?"목적지까지 실제 도로를 확인하고 있어요.":"선택한 "+requestedPhoto.place.name+"을 거쳐 원래 목적지로 가는 도로와 추가 시간을 확인하고 있어요.";final ScenicApiClient client=new ScenicApiClient(apiBaseUrl);destinationClient=client;render();
        destinationJob=apiExecutor.submit(()->{DestinationRoutePlan plan=null;String failure=null;try{plan=photoDestination?DestinationRoutePlan.parsePhotoDestination(client.photoDestinationRoutes(lat,lon,requestedRegion,theme,requestedPhoto.selectionToken,photoExtra),requestedRegion,requestedPhoto.place,lat,lon,theme,photoExtra):DestinationRoutePlan.parse(client.destinationRoutes(lat,lon,target,extra,theme,requestedPhoto==null?null:requestedPhoto.selectionToken),target,lat,lon,extra,theme,requestedPhoto==null?null:requestedPhoto.place);}catch(ScenicApiClient.ApiException error){failure=error.getMessage();}catch(Exception invalid){failure="경로 응답을 확인하지 못했어요. 다시 비교해 주세요.";}final DestinationRoutePlan result=plan;final String error=failure;
            handler.post(()->{if(!resumed||isDestroyed()||isFinishing()||revision!=destinationRevision)return;destinationPlanning=false;destinationClient=null;destinationJob=null;if(!canChangeRoute())return;
                if(usedGps&&(!freshLocation()||CourseEngine.distanceMeters(lat,lon,lastLocation.getLatitude(),lastLocation.getLongitude())>1000)){destinationMessage="출발 위치가 달라졌어요. 현재 위치로 다시 비교해 주세요.";render();return;}
                if(error!=null||result==null){destinationMessage=error==null?"경로를 찾지 못했어요.":error;render();return;}
                if(parkingDestination){double[] end=result.fast.coordinates[result.fast.coordinates.length-1];if(CourseEngine.distanceMeters(target.lat,target.lon,end[0],end[1])>150){destinationMessage="주차장 150m 이내로 도착하는 도로를 확인하지 못했어요. 맛집·주차에서 다른 주차장을 선택해 주세요.";render();return;}}
                destinationPlace=result.destination;destinationPlan=result;if(result.scenic!=null)selectDestinationRoute(result.scenic);else if(result.curated!=null)selectDestinationRoute(result.curated);else if(requestedPhoto==null||photoDestination)selectDestinationRoute(result.fast);destinationMessage=result.scenic==null&&result.curated==null?result.message:"";render();
                if(navigateParking&&journey.parking()!=null&&journey.parking().id.equals(target.id)&&chosenDestinationRoute!=null)requestNativeNavigation(false);
            });});
    }
    private void toggleDestinationDemo(){
        if(running){stopDestinationDemo("안내 시연을 종료했어요.");return;}
        if(chosenDestinationRoute==null||!destinationInteractionAllowed())return;cancelDestinationWork();cancelMusicRecommendation();running=true;demoDriving=true;navigationDemoStep=0;navigationDemoSaved=false;checkpointTracker=new CheckpointTracker(routeCheckpoints());demoCheckpointSaves=0;trackingLiveCheckpoints=false;destinationMessage="실제 주행과 분리된 안내 시연입니다.";render();handler.postDelayed(destinationDemoTicker,7000);
    }
    private void stopDestinationDemo(String message){running=false;demoDriving=false;handler.removeCallbacks(destinationDemoTicker);handler.removeCallbacks(simulator);player.pause();destinationMessage=message;if(resumed)render();}
    private final Runnable destinationDemoTicker=new Runnable(){public void run(){
        if(!resumed||!running||!demoDriving||chosenDestinationRoute==null)return;
        if(isCarUiBlocked()||knownGpsMoving()){stopDestinationDemo("정차 후 시연을 다시 시작해 주세요.");return;}
        navigationDemoStep++;
        List<RouteCheckpoint> checkpoints=routeCheckpoints();
        if(!checkpoints.isEmpty()){
            // The timeline arrives at one checkpoint per step, as the car would along the road.
            if(navigationDemoStep>=1&&navigationDemoStep<=checkpoints.size()){RouteCheckpoint reached=checkpoints.get(navigationDemoStep-1);trackCheckpoint(reached.lat,reached.lon);if(reached.isStretch())trackCheckpoint(reached.endLat,reached.endLon);}
        }else if(navigationDemoStep==1&&!navigationDemoSaved){navigationDemoSaved=captureDemo.saveDemoCapture(destinationRouteId(),destinationRouteName(),destinationLabel(),segmentLabel())!=null;}
        if(navigationDemoStep>=demoTotalSteps()){
            String done=!checkpoints.isEmpty()?(demoCheckpointSaves>0?"시연 완료 · 촬영 지점 "+demoCheckpointSaves+"곳을 자동 기록했어요. ‘풍경 기록’에서 확인하세요.":captureDemo.autoCaptureEnabled()?"시연 완료 · 이미 기록한 지점이라 새로 저장하지 않았어요.":"시연 완료 · 자동 기록이 꺼져 있어 저장하지 않았어요. 켜고 다시 시연해 보세요.")
                :navigationDemoSaved?"시연 완료 · 저장한 풍경은 ‘풍경 기록’에서 확인하세요.":"안내 시연을 마쳤어요.";
            stopDestinationDemo(done);return;}
        render();handler.postDelayed(this,7000);
    }};
    private void renderScenicRecords(){if(legacyRecords){renderMemories();return;}LinearLayout all=col();all.addView(textAction("AI 여정 북마크·가져온 영상  →",()->{legacyRecords=true;render();}),lp(-1,-2));gap(all,10);View gallery=captureDemo.createView(destinationRouteId(),destinationRouteName(),destinationLabel(),segmentLabel(),wide,true);all.addView(gallery,wide?new LinearLayout.LayoutParams(-1,0,1):lp(-1,-2));content.addView(all,wide?new LinearLayout.LayoutParams(-1,-1):lp(-1,-2));}

    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private static boolean sameJourneyPlace(DestinationPlace a,DestinationPlace b){return a!=null&&b!=null&&a.id.equals(b.id)&&a.name.equals(b.name)&&Math.abs(a.lat-b.lat)<0.0000001&&Math.abs(a.lon-b.lon)<0.0000001;}
}

