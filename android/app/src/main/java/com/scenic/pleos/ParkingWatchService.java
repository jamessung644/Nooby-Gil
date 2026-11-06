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
    private static final int NOTIFICATION=72;
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
        if(intent==null||STOP.equals(intent.getAction())){stop("자동 주차 확인을 중단했어요.");return START_NOT_STICKY;}
        try{
            reset();JourneyState trip=JourneyState.restore(intent.getStringExtra("journey"));target=trip.parking();if(target==null)throw new IllegalArgumentException();
            anchor=trip.restaurant()!=null?trip.restaurant():target;base=EndpointPolicy.normalizeBaseUrl(intent.getStringExtra("base"));journeyId=trip.id();
            started=SystemClock.elapsedRealtime();visited.add(target.id);
            List<String> excluded=intent.getStringArrayListExtra("excluded");if(excluded!=null&&excluded.size()<=100)for(String id:excluded)if(id!=null&&id.matches("[0-9]{1,100}"))visited.add(id);
            getSharedPreferences(PREFS,0).edit().clear().putBoolean("active",true).putString("journeyId",journeyId).putString("targetId",target.id).putString("appTargetId",target.id).apply();
            NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"주차 빈자리 확인",NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION,notification("안내 중 1분마다 확인 · 만차 시 빈자리 있는 곳으로 변경"));
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
                update("만차로 확인되어 "+target.name+"으로 안내를 변경했어요.");schedule();
            }
            return;
        }
        if(matches(target,info)){
            ownedAt=SystemClock.elapsedRealtime();boolean first=!owned;owned=true;main.removeCallbacks(routeTimeout);
            if(pending!=null){
                if(pendingAvailability==null||pendingCurrent==null||!pendingCurrent.isFull(SystemClock.elapsedRealtime())||!pendingAvailability.isAvailable(SystemClock.elapsedRealtime())){pending=null;pendingAvailability=pendingCurrent=null;schedule();return;}
                replace();
            }else if(first){update("주차 빈자리 확인 중 · 만차 시 주변 빈자리로 자동 변경");schedule();}
        }else if(owned){stop("순정 내비 목적지가 바뀌어 자동 주차 확인을 중단했어요.");}
    }
    private void schedule(){main.removeCallbacks(tick);if(!stopping)main.postDelayed(tick,INTERVAL);}
    private void poll(){
        if(stopping)return;
        if(SystemClock.elapsedRealtime()-started>=MAX_DURATION){stop("3시간 확인을 마쳤어요. 필요한 경우 주차 안내를 다시 시작해 주세요.");return;}
        if(!owned||replacing||loading){schedule();return;}
        readDestination();loading=true;final int token=generation;final DestinationPlace expected=target;final Set<String> excluded=new HashSet<>(visited);
        executor.submit(()->{
            ParkingReroutePolicy.Decision decision=null;String failure=null;
            try{
                ScenicApiClient request=new ScenicApiClient(base);client=request;
                decision=ParkingReroutePolicy.evaluate(request.nearbyParking(anchor,expected.id),expected,excluded,SystemClock.elapsedRealtime());
                if(decision.alternative!=null){
                    DestinationRoutePlan road=DestinationRoutePlan.parse(request.destinationRoutes(expected.lat,expected.lon,decision.alternative,0,"all"),decision.alternative,expected.lat,expected.lon,0,"all");
                    double[] end=road.fast.coordinates[road.fast.coordinates.length-1];
                    if(CourseEngine.distanceMeters(end[0],end[1],decision.alternative.lat,decision.alternative.lon)>150)throw new IllegalArgumentException();
                }
            }catch(Exception unavailable){failure="빈자리 또는 대체 도로를 확인하지 못했어요. 현재 안내를 유지합니다.";}
            finally{client=null;}
            final ParkingReroutePolicy.Decision result=decision;final String error=failure;
            main.post(()->{
                if(!valid(token)||!ParkingReroutePolicy.samePlace(target,expected))return;loading=false;
                if(error!=null){update(error);schedule();return;}
                long now=SystemClock.elapsedRealtime();
                if(!result.current.isFull(now)){update(result.current.isAvailable(now)?target.name+" · 빈자리 "+result.current.availableSpaces+"면 확인":"현재 빈자리 미확인 · 기존 안내 유지");schedule();return;}
                if(result.alternative==null){update("목적지가 만차예요. 주변에서 빈자리가 확인된 대체 주차장을 찾지 못했어요.");schedule();return;}
                if(changes>=3||lastReroute>0&&SystemClock.elapsedRealtime()-lastReroute<120000){update("잦은 목적지 변경을 막기 위해 현재 안내를 유지합니다. 정차 후 주차 목록을 확인해 주세요.");schedule();return;}
                pending=result.alternative;pendingAvailability=result.alternativeAvailability;pendingCurrent=result.current;
                // Re-read actual native destination after network/road work; never overwrite another route.
                readDestination();main.postDelayed(()->{if(valid(token)&&pending!=null&&!replacing){pending=null;pendingAvailability=pendingCurrent=null;update("현재 내비 목적지를 재확인하지 못해 자동 변경하지 않았어요.");schedule();}},10000);
            });
        });
    }
    private void replace(){
        if(stopping||pending==null||!owned||SystemClock.elapsedRealtime()-ownedAt>10000)return;
        replacing=true;sawRouteEvent=false;main.removeCallbacks(tick);main.removeCallbacks(routeTimeout);main.postDelayed(routeTimeout,45000);
        try{helper.requestRoute(new RouteInfo(pending.lat,pending.lon,null,null,pending.name,"",pending.address,"",RouteOption.RECOMMENDED,"",false));update("목적지 만차 · "+pending.name+" 빈자리 확인 후 대체 안내 요청 중");}
        catch(RuntimeException|LinkageError unavailable){stop("대체 주차장 안내를 요청하지 못했어요. 정차 후 주차 목록을 확인해 주세요.");}
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ParkingWatchService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_scenic).setContentTitle("SCENIC · 주차 빈자리 확인").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"확인 중단",stop).build()).build();
    }
    private void update(String value){message=value;getSharedPreferences(PREFS,0).edit().putString("message",value).apply();Log.i("ScenicParkingWatch",value);if(!stopping)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(value));}
    private void stop(String value){if(stopping)return;update(value);stopping=true;getSharedPreferences(PREFS,0).edit().putBoolean("active",false).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private void reset(){generation++;main.removeCallbacksAndMessages(null);ScenicApiClient old=client;if(old!=null)old.cancel();client=null;if(helper!=null){try{if(listener!=null)helper.removeListener(listener);helper.release();}catch(RuntimeException ignored){}}helper=null;listener=null;visited.clear();connected=owned=loading=replacing=stopping=sawRouteEvent=false;pending=null;pendingAvailability=pendingCurrent=null;changes=0;lastReroute=0;}
    @Override public void onTimeout(int startId,int type){stop("시스템의 백그라운드 확인 시간이 끝났어요.");}
    @Override public void onDestroy(){stopping=true;reset();stopping=true;getSharedPreferences(PREFS,0).edit().putBoolean("active",false).apply();executor.shutdownNow();super.onDestroy();}
    private static JSONObject json(DestinationPlace p)throws Exception{return new JSONObject().put("id",p.id).put("name",p.name).put("address",p.address).put("lat",p.lat).put("lon",p.lon);}
}
