package com.scenic.pleos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started inventory watch continues while the Pleos navigation screen is foreground. */
public final class ParkingWatchService extends Service {
    private static final String PREFS="parking_watch",STOP="com.scenic.pleos.STOP_PARKING_WATCH",CHANNEL="scenic_parking_watch";
    private static final String ACCEPT="com.scenic.pleos.ACCEPT_PARKING_PROPOSAL",DECLINE="com.scenic.pleos.DECLINE_PARKING_PROPOSAL",PROPOSAL_CHANNEL="scenic_parking_proposal";
    private static final int NOTIFICATION=72,PROPOSAL_NOTIFICATION=73;
    private ParkingReroutePolicy.Proposal proposal;
    private final Set<String> declined=new HashSet<>();
    private ParkingReroutePolicy.Consent consent;
    private static final long INTERVAL=60000,MAX_DURATION=3*60*60*1000L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Set<String> visited=new HashSet<>();
    private NaviHelper helper;
    private NaviHelperEventListener listener;
    private volatile ScenicApiClient client;
    private DestinationPlace target,anchor,pending;
    private ParkingAvailability pendingAvailability,pendingCurrent;
    private String base,journeyId,message="주차장 빈자리를 확인합니다.";
    private long started,lastReroute,ownedAt;
    private boolean connected,owned,loading,replacing,stopping,sawRouteEvent;
    private int generation,changes;
    private final Runnable tick=this::poll;
    private final Runnable routeTimeout=()->stop("순정 내비 목적지를 확인하지 못해 자동 주차 확인을 중단했어요.");

    public static boolean start(Context context,String base,JourneyState journey,List<String> excluded){
        if(journey.parking()==null)return false;
        try{context.startForegroundService(new Intent(context,ParkingWatchService.class).putExtra("base",base).putExtra("journey",journey.serialize()).putStringArrayListExtra("excluded",new ArrayList<>(excluded)));return true;}
        catch(RuntimeException unavailable){return false;}
    }
    public static void stop(Context context){context.stopService(new Intent(context,ParkingWatchService.class));}
    public static String status(Context context){return context.getSharedPreferences(PREFS,0).getString("message","");}
    /** One-line suggestion shown in the app banner; empty when nothing is waiting for the driver. */
    public static String proposalText(Context context){return context.getSharedPreferences(PREFS,0).getString("proposalText","");}
    public static void accept(Context context){try{context.startService(new Intent(context,ParkingWatchService.class).setAction(ACCEPT));}catch(RuntimeException unavailable){}}
    public static void decline(Context context){try{context.startService(new Intent(context,ParkingWatchService.class).setAction(DECLINE));}catch(RuntimeException unavailable){}}
    static String proposalMessage(ParkingReroutePolicy.Proposal value,DestinationPlace current){
        String free=value.alternativeAvailability.availableSpaces==null?"":" · 빈자리 "+value.alternativeAvailability.availableSpaces+"면";
        String state=ParkingReroutePolicy.Proposal.FULL.equals(value.level)?current.name+"이 만차예요.":current.name+"이 곧 만차예요"+(value.current.availableSpaces==null?"":"(남은 자리 "+value.current.availableSpaces+"면)")+".";
        return state+" "+value.alternative.name+free+"으로 바꿀까요?";
    }
    public static boolean active(Context context){return context.getSharedPreferences(PREFS,0).getBoolean("active",false);}
    public static void stopIfJourneyChanged(Context context,JourneyState journey){
        SharedPreferences prefs=context.getSharedPreferences(PREFS,0);
        if(prefs.getBoolean("active",false)&&(!journey.id().equals(prefs.getString("journeyId",""))||journey.parking()==null||!journey.parking().id.equals(prefs.getString("targetId",""))))stop(context);
    }
    /** Consume only a confirmed replacement belonging to this unchanged journey. */
    public static DestinationPlace replacement(Context context,JourneyState journey){
        SharedPreferences prefs=context.getSharedPreferences(PREFS,0);
        if(journey.parking()==null||!journey.id().equals(prefs.getString("journeyId",""))||!journey.parking().id.equals(prefs.getString("appTargetId","")))return null;
        try{DestinationPlace value=DestinationPlace.read(new JSONObject(prefs.getString("replacement","")),"확인된 주차 대체 안내");prefs.edit().remove("replacement").putString("appTargetId",value.id).apply();return value.id.equals(journey.parking().id)?null:value;}
        catch(Exception unavailable){return null;}
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&(ACCEPT.equals(intent.getAction())||DECLINE.equals(intent.getAction()))){
            if(target==null||stopping){stopSelf();return START_NOT_STICKY;}
            if(ACCEPT.equals(intent.getAction()))acceptProposal();else declineProposal();
            return START_NOT_STICKY;
        }
        if(intent==null||STOP.equals(intent.getAction())){stop("자동 주차 확인을 중단했어요.");return START_NOT_STICKY;}
        try{
            reset();JourneyState trip=JourneyState.restore(intent.getStringExtra("journey"));target=trip.parking();if(target==null)throw new IllegalArgumentException();
            anchor=trip.restaurant()!=null?trip.restaurant():target;base=EndpointPolicy.normalizeBaseUrl(intent.getStringExtra("base"));journeyId=trip.id();
            started=SystemClock.elapsedRealtime();visited.add(target.id);
            List<String> excluded=intent.getStringArrayListExtra("excluded");if(excluded!=null&&excluded.size()<=100)for(String id:excluded)if(id!=null&&id.matches("[0-9]{1,100}"))visited.add(id);
            getSharedPreferences(PREFS,0).edit().clear().putBoolean("active",true).putString("journeyId",journeyId).putString("targetId",target.id).putString("appTargetId",target.id).apply();
            NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"주차 빈자리 확인",NotificationManager.IMPORTANCE_LOW));manager.createNotificationChannel(new NotificationChannel(PROPOSAL_CHANNEL,"주차장 변경 제안",NotificationManager.IMPORTANCE_HIGH));
            startForeground(NOTIFICATION,notification("안내 중 1분마다 확인 · 만차이거나 곧 만차가 되면 변경을 제안해요"));
            connect();main.postDelayed(routeTimeout,90000);
        }catch(Exception|LinkageError unavailable){stop("자동 주차 확인을 시작하지 못했어요. 정차 후 주차 목록을 새로고침해 주세요.");}
        return START_NOT_STICKY;
    }
    private void connect(){
        final int token=generation;helper=new NaviHelper(getApplicationContext());
        listener=new NaviHelperEventListener(){
            @Override public void onRouteStarted(RouteStartInfo info){main.post(()->{if(valid(token)){sawRouteEvent=true;readDestination();}});}
            @Override public void onRoutingCompleted(){main.post(()->{if(valid(token)){sawRouteEvent=true;readDestination();}});}
            @Override public void onDestinationChanged(){main.post(()->{if(valid(token))readDestination();});}
            @Override public void onDestinationInfo(DestinationInfo info){main.post(()->{if(valid(token))destination(info);});}
            @Override public void onRouteCancelled(){main.post(()->{if(valid(token)&&owned&&!replacing)stop("내비 안내가 종료되어 자동 주차 확인을 마쳤어요.");});}
            @Override public void onDestinationArrived(DestinationArrivedInfo info){main.post(()->{if(valid(token))stop("목적지 도착으로 자동 주차 확인을 마쳤어요.");});}
            @Override public void onError(NaviErrorCode error){main.post(()->{if(valid(token)&&replacing)stop("대체 주차장 내비 응답을 확인하지 못했어요. 현재 내비 목적지를 확인해 주세요.");});}
        };
        helper.initialize(()->{main.post(()->{if(valid(token)){connected=true;helper.addListener(listener);main.postDelayed(()->{if(valid(token))readDestination();},5000);}});return Unit.INSTANCE;},error->{main.post(()->{if(valid(token))stop("순정 내비 연결 실패로 자동 주차 확인을 중단했어요.");});return Unit.INSTANCE;});
    }
    private boolean valid(int token){return !stopping&&token==generation;}
    private boolean matches(DestinationPlace place,DestinationInfo info){return place!=null&&info!=null&&info.getName()!=null&&place.name.replaceAll("\\s+","").equals(info.getName().replaceAll("\\s+",""))&&(NavigationHandoffPlan.matches(place,info.getLatitude(),info.getLongitude())||(info.getDisplayLatitude()!=null&&info.getDisplayLongitude()!=null&&NavigationHandoffPlan.matches(place,info.getDisplayLatitude(),info.getDisplayLongitude())));}
    private void readDestination(){if(!stopping&&connected)try{helper.getDestinationInfo();}catch(RuntimeException error){stop("순정 내비 상태를 확인하지 못해 자동 변경을 중단했어요.");}}
    private void destination(DestinationInfo info){
        if(stopping||info==null||!sawRouteEvent)return;
        if(replacing){
            if(matches(pending,info)){
                target=pending;pending=null;pendingAvailability=pendingCurrent=null;replacing=false;owned=true;ownedAt=SystemClock.elapsedRealtime();lastReroute=ownedAt;changes++;visited.add(target.id);main.removeCallbacks(routeTimeout);
                try{getSharedPreferences(PREFS,0).edit().putString("replacement",json(target).toString()).putString("targetId",target.id).apply();}catch(Exception ignored){}
                consent=null;update("제안을 수락해 "+target.name+"으로 안내를 변경했어요.");schedule();
            }
            return;
        }
        if(matches(target,info)){
            ownedAt=SystemClock.elapsedRealtime();boolean first=!owned;owned=true;main.removeCallbacks(routeTimeout);
            if(pending!=null){
                if(consent==null||pendingAvailability==null||!pendingAvailability.isAvailable(SystemClock.elapsedRealtime())){pending=null;pendingAvailability=pendingCurrent=null;consent=null;update("제안한 주차장의 빈자리를 다시 확인하지 못해 변경하지 않았어요.");schedule();return;}
                replace();
            }else if(first){update("주차 빈자리 확인 중 · 만차이거나 곧 만차가 되면 변경을 제안해요");schedule();}
        }else if(owned){stop("순정 내비 목적지가 바뀌어 자동 주차 확인을 중단했어요.");}
    }
    private void schedule(){main.removeCallbacks(tick);if(!stopping)main.postDelayed(tick,INTERVAL);}
    private void poll(){
        if(stopping)return;
        if(SystemClock.elapsedRealtime()-started>=MAX_DURATION){stop("3시간 확인을 마쳤어요. 필요한 경우 주차 안내를 다시 시작해 주세요.");return;}
        if(!owned||replacing||loading){schedule();return;}
        readDestination();loading=true;final int token=generation;final DestinationPlace expected=target;final Set<String> excluded=new HashSet<>(visited);final ParkingReroutePolicy.Consent requestedConsent=consent;
        executor.submit(()->{
            ParkingReroutePolicy.Proposal decision=null;String failure=null;final String preferred=requestedConsent==null?null:requestedConsent.alternative.id;
            try{
                ScenicApiClient request=new ScenicApiClient(base);client=request;
                decision=ParkingReroutePolicy.propose(request.nearbyParking(anchor,expected.id),expected,excluded,SystemClock.elapsedRealtime(),preferred);
                if(decision.alternative!=null){
                    DestinationRoutePlan road=DestinationRoutePlan.parse(request.destinationRoutes(expected.lat,expected.lon,decision.alternative,0,"all"),decision.alternative,expected.lat,expected.lon,0,"all");
                    double[] end=road.fast.coordinates[road.fast.coordinates.length-1];
                    if(CourseEngine.distanceMeters(end[0],end[1],decision.alternative.lat,decision.alternative.lon)>150)throw new IllegalArgumentException();
                }
            }catch(Exception unavailable){failure="빈자리 또는 대체 도로를 확인하지 못했어요. 현재 안내를 유지합니다.";}
            finally{client=null;}
            final ParkingReroutePolicy.Proposal result=decision;final String error=failure;
            main.post(()->{
                if(!valid(token)||!ParkingReroutePolicy.samePlace(target,expected))return;loading=false;
                // An in-flight poll predating acceptance cannot satisfy the promised recheck.
                if(consent!=null&&consent.requiresRecheck(requestedConsent)){poll();return;}
                if(error!=null){if(consent!=null){consent=null;update("빈자리를 다시 확인하지 못해 변경하지 않았어요. 현재 안내를 유지합니다.");}else update(error);schedule();return;}
                long now=SystemClock.elapsedRealtime();
                if(result.level==null){
                    clearProposal();consent=null;
                    update(result.current.isAvailable(now)?target.name+" · 빈자리 "+result.current.availableSpaces+"면 확인":result.current.isSnapshot()&&result.current.isFresh(now)?target.name+" · 공식 조회 확인":"현재 빈자리 미확인 · 기존 안내 유지");schedule();return;
                }
                String state=ParkingReroutePolicy.Proposal.FULL.equals(result.level)?"만차예요":"곧 만차예요";
                if(result.alternative==null){String unavailable=consent!=null?"수락한 주차장의 빈자리를 다시 확인하지 못해 변경하지 않았어요. 현재 안내를 유지합니다.":"목적지가 "+state+". 주변에서 더 여유 있는 주차장을 찾지 못했어요.";clearProposal();consent=null;update(unavailable);schedule();return;}
                if(consent!=null){
                    if(!consent.permits(result,requestedConsent)){consent=null;showProposal(result);schedule();return;}
                    if(changes>=3||lastReroute>0&&now-lastReroute<120000){consent=null;update("잦은 목적지 변경을 막기 위해 현재 안내를 유지합니다. 정차 후 주차 목록을 확인해 주세요.");schedule();return;}
                    pending=result.alternative;pendingAvailability=result.alternativeAvailability;pendingCurrent=result.current;clearProposal();
                    // Re-read the actual native destination after network/road work; never overwrite another route.
                    readDestination();main.postDelayed(()->{if(valid(token)&&pending!=null&&!replacing){pending=null;pendingAvailability=pendingCurrent=null;consent=null;update("현재 내비 목적지를 재확인하지 못해 변경하지 않았어요.");schedule();}},10000);
                    return;
                }
                if(declined.contains(result.key())){clearProposal();update("목적지가 "+state+" · 제안을 거절하셔서 현재 안내를 유지합니다.");schedule();return;}
                showProposal(result);schedule();            });
        });
    }
    private void replace(){
        if(stopping||pending==null||!owned||SystemClock.elapsedRealtime()-ownedAt>10000)return;
        replacing=true;sawRouteEvent=false;main.removeCallbacks(tick);main.removeCallbacks(routeTimeout);main.postDelayed(routeTimeout,45000);
        try{helper.requestRoute(new RouteInfo(pending.lat,pending.lon,null,null,pending.name,"",pending.address,"",RouteOption.RECOMMENDED,"",false));update(pending.name+" 빈자리 확인 · 수락하신 대로 변경 안내를 요청 중");}
        catch(RuntimeException|LinkageError unavailable){stop("대체 주차장 안내를 요청하지 못했어요. 정차 후 주차 목록을 확인해 주세요.");}
    }
    private android.view.View overlay;
    /** Draws the suggestion over the native navigation screen when the user allowed "display over other apps". */
    private void showOverlay(String text){
        hideOverlay();
        if(!android.provider.Settings.canDrawOverlays(this))return;
        try{
            android.view.WindowManager windows=getSystemService(android.view.WindowManager.class);float d=getResources().getDisplayMetrics().density;int pad=(int)(20*d);
            android.widget.LinearLayout card=new android.widget.LinearLayout(this);card.setOrientation(android.widget.LinearLayout.VERTICAL);card.setPadding(pad,pad,pad,pad);
            android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(ScenicDesign.HERO);background.setCornerRadius(16*d);card.setBackground(background);
            android.widget.TextView title=new android.widget.TextView(this);title.setText("SCENIC · 주차장 변경 제안");title.setTextColor(ScenicDesign.HERO_MUTED);title.setTextSize(18);card.addView(title);
            android.widget.TextView body=new android.widget.TextView(this);body.setText(text);body.setTextColor(0xFFFFFFFF);body.setTextSize(26);body.setPadding(0,(int)(8*d),0,(int)(14*d));card.addView(body);
            android.widget.LinearLayout choices=new android.widget.LinearLayout(this);choices.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.Button yes=new android.widget.Button(this),no=new android.widget.Button(this);yes.setText("변경");no.setText("유지");yes.setTextSize(24);no.setTextSize(24);
            yes.setOnClickListener(v->acceptProposal());no.setOnClickListener(v->declineProposal());
            android.widget.LinearLayout.LayoutParams half=new android.widget.LinearLayout.LayoutParams(0,android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,1);half.setMargins(0,0,(int)(8*d),0);choices.addView(yes,half);choices.addView(no,new android.widget.LinearLayout.LayoutParams(0,android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,1));card.addView(choices);
            android.view.WindowManager.LayoutParams params=new android.view.WindowManager.LayoutParams((int)(640*d),android.view.WindowManager.LayoutParams.WRAP_CONTENT,android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT);
            // Right side of the map keeps the native turn-by-turn card and the 2D button visible.
            params.gravity=android.view.Gravity.TOP|android.view.Gravity.END;params.x=(int)(40*d);params.y=(int)(230*d);
            windows.addView(card,params);overlay=card;
        }catch(RuntimeException unavailable){overlay=null;Log.w("ScenicParkingWatch","overlay unavailable: "+unavailable);}
    }
    private void hideOverlay(){
        android.view.View view=overlay;overlay=null;
        if(view!=null)try{getSystemService(android.view.WindowManager.class).removeView(view);}catch(RuntimeException ignored){}
    }
    private void showProposal(ParkingReroutePolicy.Proposal value){
        if(proposal!=null&&proposal.key().equals(value.key())){proposal=value;return;}
        proposal=value;String text=proposalMessage(value,target);
        getSharedPreferences(PREFS,0).edit().putString("proposalText",text).apply();update(text);showOverlay(text);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent yes=PendingIntent.getService(this,2,new Intent(this,ParkingWatchService.class).setAction(ACCEPT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent no=PendingIntent.getService(this,3,new Intent(this,ParkingWatchService.class).setAction(DECLINE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(PROPOSAL_NOTIFICATION,new Notification.Builder(this,PROPOSAL_CHANNEL).setSmallIcon(R.drawable.ic_scenic).setContentTitle("SCENIC · 주차장 변경 제안").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null,"변경",yes).build()).addAction(new Notification.Action.Builder(null,"유지",no).build()).build());
    }
    private void clearProposal(){
        proposal=null;hideOverlay();getSharedPreferences(PREFS,0).edit().remove("proposalText").apply();
        try{getSystemService(NotificationManager.class).cancel(PROPOSAL_NOTIFICATION);}catch(RuntimeException ignored){}
    }
    private void acceptProposal(){
        if(proposal==null||proposal.alternative==null||replacing)return;
        consent=new ParkingReroutePolicy.Consent(proposal);clearProposal();update("수락하셨어요. 빈자리를 다시 확인한 뒤 안내를 변경합니다.");
        main.removeCallbacks(tick);poll();
    }
    private void declineProposal(){
        if(proposal==null)return;
        declined.add(proposal.key());clearProposal();update("제안을 거절했어요. 현재 안내를 유지합니다.");
    }    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ParkingWatchService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_scenic).setContentTitle("SCENIC · 주차 빈자리 확인").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"확인 중단",stop).build()).build();
    }
    private void update(String value){message=value;getSharedPreferences(PREFS,0).edit().putString("message",value).apply();Log.i("ScenicParkingWatch",value);if(!stopping)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(value));}
    private void stop(String value){if(stopping)return;clearProposal();update(value);stopping=true;getSharedPreferences(PREFS,0).edit().putBoolean("active",false).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private void reset(){generation++;clearProposal();main.removeCallbacksAndMessages(null);ScenicApiClient old=client;if(old!=null)old.cancel();client=null;if(helper!=null){try{if(listener!=null)helper.removeListener(listener);helper.release();}catch(RuntimeException ignored){}}helper=null;listener=null;visited.clear();declined.clear();proposal=null;consent=null;connected=owned=loading=replacing=stopping=sawRouteEvent=false;pending=null;pendingAvailability=pendingCurrent=null;changes=0;lastReroute=0;}
    @Override public void onTimeout(int startId,int type){stop("시스템의 백그라운드 확인 시간이 끝났어요.");}
    @Override public void onDestroy(){boolean externallyStopped=!stopping;stopping=true;reset();stopping=true;SharedPreferences.Editor prefs=getSharedPreferences(PREFS,0).edit().putBoolean("active",false);if(externallyStopped)prefs.putString("message","자동 주차 확인을 중단했어요.");prefs.apply();executor.shutdownNow();super.onDestroy();}
    private static JSONObject json(DestinationPlace p)throws Exception{return new JSONObject().put("id",p.id).put("name",p.name).put("address",p.address).put("lat",p.lat).put("lon",p.lon);}
}
