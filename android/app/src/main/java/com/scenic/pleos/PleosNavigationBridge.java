package com.scenic.pleos;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.constants.WaypointIndex;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.NavigationScreenRequest;
import ai.pleos.playground.navi.data.RequestWaypointInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest;
import ai.pleos.playground.navi.data.WaypointInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/** Explicit, stationary-user handoff to the documented Pleos NaviHelper SDK. */
public final class PleosNavigationBridge {
    public interface Host {
        boolean canInteract();
        void onNavigationStatus(String message);
    }
    private static final String SERVICE="ai.pleos.playground.service.navi";
    private static final String ACTION="ai.pleos.playground.intent.action.NAVI_START";
    private static final String TAG="ScenicNativeNav";
    private final Activity activity;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private NaviHelper helper;
    private NaviHelperEventListener listener;
    private NavigationHandoffPlan plan;
    private boolean busy,destroyed,sent,sawRouteEvent,destinationConfirmed,waypointsConfirmed;
    private int generation;
    private String message="선택한 목적지와 경유지를 순정 내비로 보낼 수 있어요.";
    private final Runnable timeout=()->finish("순정 내비의 경로 설정 결과를 확인하지 못했어요. 내비 화면에서 목적지·경유지와 네트워크 상태를 확인해 주세요.");

    public PleosNavigationBridge(Activity activity,Host host){
        if(activity==null||host==null)throw new IllegalArgumentException("Activity and host are required");
        this.activity=activity;this.host=host;
    }
    public boolean isBusy(){return busy;}
    public String status(){return message;}
    public boolean isAvailable(){
        try{return activity.getPackageManager().resolveService(new Intent(ACTION).setPackage(SERVICE),0)!=null;}
        catch(RuntimeException unavailable){return false;}
    }
    /** The SDK uses vehicle current location. It accepts stops, not Kakao geometry or a simulated origin. */
    public void request(DestinationPlace destination,List<DestinationPlace> waypoints){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->request(destination,waypoints));return;}
        if(destroyed)return;
        if(!host.canInteract()){update("정차한 뒤 순정 내비 안내를 시작해 주세요.");return;}
        if(busy){update("순정 내비에서 경로를 확인하고 있어요. 잠시 기다려 주세요.");return;}
        final NavigationHandoffPlan next;
        try{next=new NavigationHandoffPlan(destination,waypoints);}catch(IllegalArgumentException invalid){update(invalid.getMessage());return;}
        if(!isAvailable()){update("이 기기에서 Pleos 순정 내비 서비스를 찾지 못했어요. Pleos 연결 상태를 확인해 주세요.");return;}
        for(String permission:new String[]{"pleos.car.permission.NAVI_ROUTE","pleos.car.permission.NAVI_ROUTE_SEARCH","pleos.car.permission.NAVI_CUSTOM_MAP"}){
            if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){update("순정 내비 사용 권한이 연결되지 않았어요. Pleos 앱 권한과 SDK 설정을 확인해 주세요.");return;}
        }
        release();plan=next;busy=true;sent=false;sawRouteEvent=false;destinationConfirmed=false;waypointsConfirmed=false;
        final int token=++generation;
        update("순정 내비에 연결하고 있어요…");
        main.postDelayed(timeout,45000);
        try{
            helper=new NaviHelper(activity.getApplicationContext());
            listener=new NaviHelperEventListener(){
                @Override public void onRouteStarted(RouteStartInfo info){main.post(()->{
                    if(!current(token)||!sent)return;sawRouteEvent=true;update("순정 내비가 경로를 탐색하고 있어요. 목적지와 경유지를 확인합니다.");verifyRoute(token);
                });}
                @Override public void onRoutingCompleted(){main.post(()->{
                    if(!current(token)||!sent)return;sawRouteEvent=true;verifyRoute(token);
                });}
                @Override public void onDestinationChanged(){main.post(()->{if(current(token)&&sent&&sawRouteEvent)verifyRoute(token);});}
                @Override public void onDestinationInfo(DestinationInfo info){main.post(()->{
                    if(!current(token)||!sent||!sawRouteEvent||info==null)return;
                    destinationConfirmed=NavigationHandoffPlan.matches(plan.destination,info.getLatitude(),info.getLongitude())
                            ||(info.getDisplayLatitude()!=null&&info.getDisplayLongitude()!=null&&NavigationHandoffPlan.matches(plan.destination,info.getDisplayLatitude(),info.getDisplayLongitude()));
                    completeIfConfirmed();
                });}
                @Override public void onWayPointInfo(List<WaypointInfo> points){main.post(()->{
                    if(!current(token)||!sent||!sawRouteEvent||points==null)return;
                    waypointsConfirmed=points.size()==plan.waypoints.size();
                    if(waypointsConfirmed)for(int i=0;i<points.size();i++){
                        WaypointInfo point=points.get(i);
                        if(point==null||!NavigationHandoffPlan.matches(plan.waypoints.get(i),point.getLatitude(),point.getLongitude())){waypointsConfirmed=false;break;}
                    }
                    completeIfConfirmed();
                });}
                @Override public void onError(NaviErrorCode error){main.post(()->{if(current(token)&&sent)finish(errorMessage(error));});}
                @Override public void onRouteCancelled(){main.post(()->{if(current(token)&&sent)finish("순정 내비의 경로 탐색이 취소됐어요.");});}
            };
            helper.initialize(()->{main.post(()->connected(token));return Unit.INSTANCE;},error->{main.post(()->{
                if(current(token))finish("순정 내비 서비스에 연결하지 못했어요. Pleos 내비 앱과 SDK 버전을 확인해 주세요.");
            });return Unit.INSTANCE;});
        }catch(SecurityException denied){finish("순정 내비 사용 권한을 확인해 주세요.");}
        catch(RuntimeException|LinkageError unavailable){finish("이 기기의 순정 내비 SDK를 사용할 수 없어요. Pleos 연결 상태와 버전을 확인해 주세요.");}
    }
    private void connected(int token){
        if(!current(token))return;
        if(!host.canInteract()){finish("화면 또는 주행 상태가 바뀌어 순정 내비 요청을 중단했어요. 정차 후 다시 눌러 주세요.");return;}
        try{
            helper.addListener(listener);
            // IDs from Kakao/TourAPI are not Navi POI IDs. Send coordinates and labels with empty native IDs.
            DestinationPlace destination=plan.destination;
            RouteInfo target=new RouteInfo(destination.lat,destination.lon,null,null,destination.name,"",destination.address,"",RouteOption.RECOMMENDED,"",false);
            ArrayList<RequestWaypointInfo> points=new ArrayList<>();
            for(int i=0;i<plan.waypoints.size();i++){
                DestinationPlace point=plan.waypoints.get(i);
                points.add(new RequestWaypointInfo(point.lat,point.lon,point.name,i==0?WaypointIndex.FIRST:WaypointIndex.SECOND,"",point.address,"",""));
            }
            sent=true;
            if(points.isEmpty())helper.requestRoute(target);
            else helper.requestRouteWithWaypoints(new RouteWithWaypointsRequest(target,points));
            update("순정 내비에 경로를 요청했어요. 차량의 현재 위치에서 다시 탐색하며, 내비에서 최종 경로를 확인해 주세요.");
            if(host.canInteract())helper.requestNavigationScreen(NavigationScreenRequest.Foreground.INSTANCE);
            // Some Navigation versions publish route results only after the foreground screen loads.
        }catch(SecurityException denied){finish("순정 내비 사용 권한을 확인해 주세요.");}
        catch(RuntimeException|LinkageError unsupported){finish("순정 내비가 경로 요청을 처리하지 못했어요. 목적지·경유지와 연결 상태를 확인해 주세요.");}
    }
    private void verifyRoute(int token){
        if(!current(token)||helper==null)return;
        try{helper.getDestinationInfo();helper.getWaypointInfo();}
        catch(RuntimeException|LinkageError unavailable){finish("순정 내비에 요청했지만 최종 경로를 확인하지 못했어요. 내비 화면에서 확인해 주세요.");}
    }
    private void completeIfConfirmed(){
        if(!busy||!destinationConfirmed||!waypointsConfirmed)return;
        int count=plan.waypoints.size();
        finish(count==0?"순정 내비에서 목적지 설정을 확인했어요.":"순정 내비에서 목적지와 경유지 "+count+"곳의 설정을 확인했어요.");
    }
    private boolean current(int token){return !destroyed&&busy&&token==generation;}
    private void finish(String value){
        if(destroyed)return;busy=false;generation++;main.removeCallbacks(timeout);release();update(value);
    }
    private void update(String value){
        message=value==null?"순정 내비 상태를 확인해 주세요.":value;
        // The log contains state only, never coordinates, names, or tokens.
        Log.i(TAG,message);
        if(!destroyed)host.onNavigationStatus(message);
    }
    /** Cancels a connection not yet handed off; it never cancels an existing Navigation route. */
    public void pause(){if(busy&&!sent)finish("화면을 나가 순정 내비 연결을 중단했어요. 정차 후 다시 눌러 주세요.");}
    public void destroy(){destroyed=true;busy=false;generation++;main.removeCallbacksAndMessages(null);release();}
    private void release(){
        NaviHelper old=helper;NaviHelperEventListener oldListener=listener;helper=null;listener=null;
        if(old!=null){try{if(oldListener!=null)old.removeListener(oldListener);}catch(RuntimeException ignored){}
            try{old.release();}catch(RuntimeException ignored){}}
    }
    private static String errorMessage(NaviErrorCode error){
        if(error==null)return "순정 내비에서 경로를 찾지 못했어요.";
        switch(error){
            case CAUSE_LOW_BATTERY:return "차량 배터리가 부족해 순정 내비 경로를 설정하지 못했어요.";
            case CAUSE_SEARCH_ROUTE_OVER_DISTANCE:return "순정 내비의 탐색 가능 거리를 초과했어요.";
            case CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE:return "목적지가 차량의 현재 위치와 너무 가까워요.";
            default:return "순정 내비에서 경로를 찾지 못했어요. 목적지와 네트워크 상태를 확인해 주세요.";
        }
    }
}
