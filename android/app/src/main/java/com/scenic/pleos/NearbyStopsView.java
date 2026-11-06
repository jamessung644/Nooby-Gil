package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Kakao place discovery plus timestamped provider inventory; unknown is never inferred as available. */
public final class NearbyStopsView {
    public interface Host {
        boolean canInteract();
        String serverBaseUrl();
        DestinationPlace anchor();
        List<DestinationPlace> anchors();
        DestinationPlace savedRestaurant();
        void chooseAnchor(DestinationPlace place);
        void navigate(DestinationPlace place,DestinationPlace restaurant,boolean parking);
        default String[] foodHints(){return new String[0];}
        default void selectRestaurant(DestinationPlace place){}
        default void navigateToParking(DestinationPlace place,DestinationPlace restaurant){navigate(place,restaurant,true);}
    }
    private static final int INK=0xff172925,MUTED=0xff65736e,LINE=0xffdbe3df,PANEL=0xffffffff,SOFT=0xffedf4ef,ACCENT=0xff24745a;
    private final Activity activity;
    private final Host host;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final List<Place> restaurants=new ArrayList<>(),parkings=new ArrayList<>();
    private final List<View> controls=new ArrayList<>();
    private final Set<String> excludedParkingIds=new HashSet<>();
    private volatile HttpURLConnection connection;
    private Future<?> job;
    private LinearLayout root;
    private boolean wide,active,destroyed,loading;
    private long revision;
    private long lastParkingRefresh;
    private String anchorKey="",baseUrl="",error="",pendingKind="restaurant",foodQuery="";
    private String navigationMessage="";
    private boolean restaurantsLoaded,parkingLoaded;
    private DestinationPlace anchor;
    private Place selectedRestaurant;
    private final ParkingEvidenceStore evidence;
    private AlertDialog dialog;
    private TextView safetyLabel;
    private ScrollView leftScroll,rightScroll;

    public NearbyStopsView(Activity activity,Host host){this.activity=activity;this.host=host;final SharedPreferences prefs=activity.getSharedPreferences("scenic_parking_evidence",0);evidence=new ParkingEvidenceStore(new ParkingEvidenceStore.Persistence(){public String read(){return prefs.getString("observations","{}");}public void write(String value){prefs.edit().putString("observations",value).apply();}});}

    public View createView(boolean wide){
        this.wide=wide;active=true;handler.removeCallbacks(safetyWatch);
        DestinationPlace next=host.anchor();
        String nextBase;
        try{nextBase=EndpointPolicy.normalizeBaseUrl(host.serverBaseUrl());}catch(IllegalArgumentException invalid){nextBase="";}
        String key=next==null?"none":next.id+"|"+next.lat+"|"+next.lon;
        if(!key.equals(anchorKey)||!nextBase.equals(baseUrl)){
            cancel();anchor=next;anchorKey=key;baseUrl=nextBase;restaurants.clear();parkings.clear();selectedRestaurant=null;
            restaurantsLoaded=false;parkingLoaded=false;error="";navigationMessage="";pendingKind="restaurant";foodQuery="";lastParkingRefresh=0;excludedParkingIds.clear();
            DestinationPlace saved=host.savedRestaurant();if(saved!=null)selectedRestaurant=new Place(saved,0);
        }
        DestinationPlace saved=host.savedRestaurant();
        if((saved==null&&selectedRestaurant!=null)||(saved!=null&&(selectedRestaurant==null||!samePlace(saved,selectedRestaurant.destination)))){
            cancel();selectedRestaurant=saved==null?null:new Place(saved,0);parkings.clear();parkingLoaded=false;error="";navigationMessage="";excludedParkingIds.clear();
        }
        root=column();render();handler.post(safetyWatch);
        if(anchor!=null&&!baseUrl.isEmpty()&&!loading&&error.isEmpty())handler.post(()->{if(active&&!destroyed&&!loading&&host.canInteract()){if(selectedRestaurant!=null&&!parkingLoaded)load("parking");else if(!restaurantsLoaded)load("restaurant");}});
        return root;
    }
    public void pause(){active=false;handler.removeCallbacks(safetyWatch);cancel();if(dialog!=null){dialog.dismiss();dialog=null;}}
    public void destroy(){destroyed=true;pause();executor.shutdownNow();handler.removeCallbacksAndMessages(null);}
    public List<String> excludedParkingIds(){return new ArrayList<>(excludedParkingIds);}
    /** Local visit evidence only; never changes the provider's inventory counts. */
    public void avoidParking(DestinationPlace blocked){if(blocked==null||!allowed())return;excludedParkingIds.add(blocked.id);if(rightScroll!=null)rightScroll.scrollTo(0,0);load("parking");navigationMessage=blocked.name+"을 만차·입차 불가로 직접 제외했어요. 다른 주차장을 선택해 주세요.";render();}

    private void cancel(){revision++;loading=false;if(job!=null){job.cancel(true);job=null;}HttpURLConnection old=connection;connection=null;if(old!=null)old.disconnect();}
    private boolean allowed(){if(!active||destroyed||!host.canInteract()){Toast.makeText(activity,"정차 후 장소를 선택해 주세요.",Toast.LENGTH_SHORT).show();return false;}return true;}
    private void load(String kind){load(kind,null);}
    private void load(String kind,Place navigationChoice){
        if(!allowed())return;
        DestinationPlace point="parking".equals(kind)&&selectedRestaurant!=null?selectedRestaurant.destination:anchor;
        if(point==null||baseUrl.isEmpty()){error="경로에서 목적지와 API 서버를 먼저 확인해 주세요.";render();return;}
        cancel();pendingKind=kind;loading=true;error="";navigationMessage=navigationChoice==null?"":"안내 전에 빈자리를 다시 확인하고 있어요…";final long token=revision;
        if("parking".equals(kind))lastParkingRefresh=SystemClock.elapsedRealtime();
        final String query="restaurant".equals(kind)?foodQuery:"";
        final String endpoint=baseUrl+"/api/nearby?kind="+kind+"&lat="+point.lat+"&lon="+point.lon+(query.isEmpty()?"":"&query="+encode(query))+(navigationChoice==null?"":"&focusId="+encode(navigationChoice.destination.id));
        render();
        job=executor.submit(()->{
            PlacesResponse results=null;String failure=null;
            try{results=request(endpoint,kind);}catch(Exception problem){failure=problem instanceof PlaceFailure?problem.getMessage():"주변 장소를 불러오지 못했어요. 서버 연결을 확인하고 다시 시도해 주세요.";}
            final PlacesResponse found=results;final String message=failure;
            handler.post(()->{
                if(destroyed||!active||token!=revision)return;
                loading=false;job=null;
                if(!host.canInteract()){error="정차 후 장소를 다시 조회해 주세요.";render();return;}
                error=message==null?"":message;
                if(message==null){if("restaurant".equals(kind)){restaurants.clear();restaurants.addAll(found.places);restaurantsLoaded=true;}else{parkings.clear();parkings.addAll(found.places);parkingLoaded=true;lastParkingRefresh=SystemClock.elapsedRealtime();}}
                if(message!=null)navigationMessage="";
                render();
                if(message==null&&navigationChoice!=null)finishParkingCheck(navigationChoice,found);
            });
        });
    }
    private PlacesResponse request(String endpoint,String kind)throws Exception{
        HttpURLConnection current=(HttpURLConnection)new URL(endpoint).openConnection();connection=current;
        current.setConnectTimeout(10000);current.setReadTimeout(30000);current.setInstanceFollowRedirects(false);current.setRequestProperty("Accept","application/json");
        try{
            int status=current.getResponseCode();
            if(status>=300&&status<400)throw new PlaceFailure("서버 주소가 다른 곳으로 이동했어요. API 서버 주소를 확인해 주세요.");
            InputStream stream=status>=400?current.getErrorStream():current.getInputStream();
            if(stream==null)throw new PlaceFailure("서버가 응답하지 않았어요. 다시 시도해 주세요.");
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
            try(InputStream input=stream){int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(output.size()+count>262144)throw new PlaceFailure("장소 응답이 너무 커서 표시하지 못했어요.");output.write(buffer,0,count);}}
            JSONObject data=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            if(status!=200){String message=data.optString("error","");if(message.length()>0&&message.length()<240&&!message.contains("http")&&!message.contains("sk-"))throw new PlaceFailure(message);throw new PlaceFailure("장소 서비스를 사용할 수 없어요. API 연결을 확인해 주세요.");}
            if(!"Kakao Local".equals(data.getString("source"))||!kind.equals(data.getString("kind")))throw new PlaceFailure("장소 응답을 확인하지 못했어요.");
            JSONArray rows=data.getJSONArray("places");if(rows.length()>10)throw new PlaceFailure("장소 응답을 확인하지 못했어요.");
            List<Place> result=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                result.add(parsePlace(rows.getJSONObject(i)));
            }
            Place focus="parking".equals(kind)&&data.optJSONObject("focus")!=null?parsePlace(data.getJSONObject("focus")):null;
            return new PlacesResponse(result,focus);
        }finally{current.disconnect();if(connection==current)connection=null;}
    }
    private Place parsePlace(JSONObject row)throws Exception{
        DestinationPlace destination=DestinationPlace.read(row,"Kakao Local");
        Object rawDistance=row.get("distanceMeters");if(!(rawDistance instanceof Number))throw new PlaceFailure("장소 응답을 확인하지 못했어요.");double distance=((Number)rawDistance).doubleValue();if(!Double.isFinite(distance)||distance<0||distance>3000||distance!=Math.floor(distance))throw new PlaceFailure("장소 응답을 확인하지 못했어요.");
        String category=row.has("category")?DestinationPlace.text(row,"category",180,true):"",phone=row.has("phone")?DestinationPlace.text(row,"phone",40,true):"";
        if(!destination.id.matches("[0-9]{1,100}")||!phone.matches("[0-9+() -]*"))throw new PlaceFailure("장소 응답을 확인하지 못했어요.");
        return new Place(destination,(int)distance,category,phone,ParkingAvailability.read(row,SystemClock.elapsedRealtime()));
    }

    private void render(){
        if(root==null)return;final int leftY=leftScroll==null?0:leftScroll.getScrollY(),rightY=rightScroll==null?0:rightScroll.getScrollY();leftScroll=null;rightScroll=null;root.removeAllViews();controls.clear();
        LinearLayout header=row();header.addView(text("한 끼부터 주차까지",28,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        header.addView(badge("Kakao Local · 실제 검색"));root.addView(header,size(-1,-2));gap(root,8);
        root.addView(text(anchor==null?"먼저 경로에서 목적지를 선택해 주세요.":anchor.name+" 주변 3km · "+(foodQuery.isEmpty()?"거리순 음식점":foodQuery+" · Kakao 검색 관련도순")+" · 평점 순위가 아닙니다",14,MUTED,false));gap(root,8);
        safetyLabel=text("",13,MUTED,false);root.addView(safetyLabel);gap(root,10);
        if(host.anchors().size()>1){root.addView(action("여정의 다른 장소 주변 찾기",false,this::chooseAnchor),size(-1,-2));gap(root,10);}
        if(baseUrl.isEmpty()){root.addView(text("설정에서 API 서버 주소를 확인해 주세요.",17,INK,false));return;}
        if(anchor==null)return;
        LinearLayout columns=new LinearLayout(activity);columns.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);columns.setGravity(Gravity.TOP);
        LinearLayout left=column(),right=column();
        if(wide){leftScroll=scroll(left);rightScroll=scroll(right);columns.addView(leftScroll,new LinearLayout.LayoutParams(0,-1,.46f));View spacer=new View(activity);columns.addView(spacer,size(22,1));columns.addView(rightScroll,new LinearLayout.LayoutParams(0,-1,.54f));root.addView(columns,new LinearLayout.LayoutParams(-1,0,1));}
        else{columns.addView(left,size(-1,-2));gap(columns,18);columns.addView(right,size(-1,-2));leftScroll=scroll(columns);root.addView(leftScroll,new LinearLayout.LayoutParams(-1,0,1));}
        LinearLayout listHeading=row();listHeading.addView(text("01  근처 음식점",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));listHeading.addView(action("새로고침",false,()->load("restaurant")),size(112,-2));left.addView(listHeading);gap(left,12);
        String[] hints=host.foodHints();if(hints!=null&&hints.length>0){left.addView(text("원하는 음식으로 좁혀보기",14,MUTED,false));gap(left,6);LinearLayout filters=row();filters.addView(action("전체",foodQuery.isEmpty(),()->chooseFood("")),new LinearLayout.LayoutParams(0,-2,1));int shown=0;for(String hint:hints){if(hint==null||hint.trim().isEmpty()||hint.length()>40||shown++>=3)continue;final String value=hint.trim();filters.addView(action(value,foodQuery.equals(value),()->chooseFood(value)),new LinearLayout.LayoutParams(0,-2,1));}left.addView(filters);gap(left,12);}
        if(loading&&"restaurant".equals(pendingKind))loading(left,"근처 음식점을 찾고 있어요…");
        if(!error.isEmpty()&&"restaurant".equals(pendingKind))failure(left);
        if(restaurantsLoaded&&restaurants.isEmpty())left.addView(text(foodQuery.isEmpty()?"반경 3km에서 음식점을 찾지 못했어요. 다른 장소 주변을 살펴보세요.":"반경 3km에서 '"+foodQuery+"' 검색 결과가 없어요. 전체 음식점 또는 다른 음식을 선택해 주세요.",16,MUTED,false));
        for(Place place:restaurants){
            boolean selected=selectedRestaurant!=null&&selectedRestaurant.destination.id.equals(place.destination.id);
            LinearLayout card=card(selected?SOFT:PANEL);card.addView(badge((selected?"선택한 음식점 · ":"")+distance(place.distance)));
            gap(card,9);card.addView(text(place.destination.name,21,INK,true));gap(card,8);card.addView(text(place.destination.address,14,MUTED,false));gap(card,13);
            if(!place.category.isEmpty()){card.addView(text(place.category,13,MUTED,false));gap(card,6);}if(!place.phone.isEmpty()){card.addView(text("전화 · "+place.phone,13,MUTED,false));gap(card,6);}
            card.addView(action(selected?"선택됨 · 주차 다시 찾기":"이 식당 선택 · 주차 자동 찾기",selected,()->selectRestaurant(place)),size(-1,-2));left.addView(card,size(-1,-2));gap(left,12);
        }
        LinearLayout selection=card(PANEL);right.addView(selection,size(-1,-2));selection.addView(text("02  도착하기 전에",19,INK,true));gap(selection,14);
        if(selectedRestaurant==null){
            selection.addView(text("마음에 드는 식당을 고르면\n주차까지 이어서 확인해요.",25,INK,true));gap(selection,14);
            selection.addView(text("식당 선택 즉시 주변 주차장을 조회합니다.\n안내는 주차장 버튼을 눌렀을 때 시작해요.",16,MUTED,false));gap(selection,18);
            selection.addView(action("목적지 주변 주차장 먼저 찾기",true,()->load("parking")),size(-1,-2));
        }else{
            selection.addView(text(selectedRestaurant.destination.name,25,INK,true));gap(selection,8);selection.addView(text(selectedRestaurant.destination.address,15,MUTED,false));gap(selection,18);
            ParkingEvidenceStore.Evidence observed=evidence.get(selectedRestaurant.destination.id);String decision=observed==null?"unknown":observed.decision(System.currentTimeMillis());
            selection.addView(badge("식당 자체 주차 · "+("available".equals(decision)?"가능으로 기록":"unavailable".equals(decision)?"불가로 기록":"확인 필요")));gap(selection,8);
            selection.addView(text(observed==null?"확인된 근거가 없어요. 장소 정보·리뷰 또는 식당에 확인한 내용을 기록할 수 있어요.":observed.source+" · "+new SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(new Date(observed.checkedAt))+"\n"+observed.note+(observed.recent(System.currentTimeMillis())?"":"\n30일이 지났거나 확인 시점이 유효하지 않아 재확인이 필요해요."),14,MUTED,false));gap(selection,8);
            selection.addView(text("내가 남긴 확인 메모이며 현재 빈자리·영업 여부를 보장하지 않습니다.",12,MUTED,false));gap(selection,12);
            selection.addView(action("주차 확인 내용 기록",false,this::editEvidence),size(-1,-2));gap(selection,8);
            selection.addView(action("이 식당으로 경로 보기",true,()->navigate(selectedRestaurant,false)),size(-1,-2));gap(selection,8);
            selection.addView(action("주차장 다시 조회",!"available".equals(decision),()->load("parking")),size(-1,-2));gap(selection,8);
            selection.addView(action("장소 정보 · 리뷰 열기",false,()->openDetails(selectedRestaurant)),size(-1,-2));
        }
        gap(right,18);
        if(loading&&"parking".equals(pendingKind))loading(right,navigationMessage.isEmpty()?"선택한 장소 주변 주차장을 찾고 있어요…":navigationMessage);
        else if(!navigationMessage.isEmpty()){right.addView(text(navigationMessage,16,INK,true));gap(right,12);}
        if(!error.isEmpty()&&"parking".equals(pendingKind))failure(right);
        if(parkingLoaded){
            right.addView(text("03  빈자리 우선 · 주변 주차장",19,INK,true));gap(right,6);
            right.addView(text((selectedRestaurant==null?anchor.name:selectedRestaurant.destination.name)+" 주변 · 최신 빈자리 확인 → 미확인 → 만차 순\n45초마다 갱신 · 안내 직전에 다시 확인합니다",13,MUTED,false));gap(right,12);
            if(parkings.isEmpty())right.addView(text("반경 3km에서 등록된 주차장을 찾지 못했어요. 식당에 주차 가능 여부를 확인해 주세요.",16,MUTED,false));
            final long now=SystemClock.elapsedRealtime();parkings.sort((a,b)->Integer.compare(parkingPriority(a,now),parkingPriority(b,now)));
            if(!parkings.isEmpty()&&firstAvailable(parkings,now)==null){right.addView(text("현재 빈자리가 확인된 곳이 없어요. 미확인 주차장은 이용 가능 여부를 확인한 뒤 선택해 주세요.",14,MUTED,false));gap(right,12);}
            for(int index=0;index<parkings.size();index++){
                Place place=parkings.get(index);boolean excluded=excludedParkingIds.contains(place.destination.id),recommended=index==0&&!excluded&&place.availability.isAvailable(now);
                LinearLayout card=card(recommended?SOFT:PANEL);card.addView(badge(parkingLabel(place,recommended)));gap(card,8);
                card.addView(text(place.destination.name,20,INK,true));gap(card,7);card.addView(text(place.destination.address,14,MUTED,false));gap(card,7);
                if(!place.phone.isEmpty()){card.addView(text("전화 · "+place.phone,13,MUTED,false));gap(card,6);}
                card.addView(text(inventoryDescription(place,now),16,INK,true));gap(card,7);
                if(!place.availability.source.isEmpty()){card.addView(text(inventorySource(place),12,MUTED,false));gap(card,7);}
                card.addView(text(publicParkingLabel(place)?"공영·공공 표기 · 요금·운영시간·입차 조건은 별도 확인":"시설 전용일 수 있음 · 이용조건 확인 필요",13,MUTED,false));gap(card,7);
                if(excluded){card.addView(text("직접 만차·입차 불가로 제외한 곳입니다.",14,INK,true));}
                else{card.addView(text(place.availability.isSnapshot()?"제공처 갱신 시각이 없어 주행 중 자동 변경은 보류합니다. 안내 전 재조회하며 도착 전 잔여면은 달라질 수 있어요.":"도착 전 잔여면은 달라질 수 있어요. 만차가 확인되면 빈자리 있는 다른 주차장을 찾습니다.",13,MUTED,false));gap(card,13);
                    card.addView(action(place.availability.isFull(now)?"빈자리 있는 다른 주차장 찾기":recommended?"빈자리 확인 · 추천 주차장 안내":place.availability.isAvailable(now)?"이 주차장까지 안내":"빈자리 미확인 · 위치로 안내",true,()->navigate(place,true)),size(-1,-2));}
                gap(card,7);
                card.addView(action("주차장 정보 열기",false,()->openDetails(place)),size(-1,-2));right.addView(card,size(-1,-2));gap(right,10);
                if(!place.availability.sourceUrl.isEmpty()){card.addView(action("실시간 정보 출처",false,()->openInventorySource(place)),size(-1,-2));}
            }
        }
        updateSafety();
        final ScrollView restoreLeft=leftScroll,restoreRight=rightScroll;if(restoreLeft!=null)restoreLeft.post(()->restoreLeft.scrollTo(0,leftY));if(restoreRight!=null)restoreRight.post(()->restoreRight.scrollTo(0,rightY));
    }
    private void chooseFood(String query){if(!allowed())return;foodQuery=query;restaurants.clear();restaurantsLoaded=false;load("restaurant");}
    private void selectRestaurant(Place place){if(!allowed())return;cancel();if(selectedRestaurant==null||!samePlace(place.destination,selectedRestaurant.destination))excludedParkingIds.clear();selectedRestaurant=place;host.selectRestaurant(place.destination);parkings.clear();parkingLoaded=false;error="";navigationMessage="";pendingKind="parking";if(rightScroll!=null)rightScroll.scrollTo(0,0);load("parking");}
    private void navigate(Place place,boolean parking){if(!allowed()||place==null)return;if(parking){if(!excludedParkingIds.contains(place.destination.id))load("parking",place);}else host.navigate(place.destination,selectedRestaurant==null?null:selectedRestaurant.destination,false);}
    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static boolean samePlace(DestinationPlace a,DestinationPlace b){return a.id.equals(b.id)&&a.lat==b.lat&&a.lon==b.lon;}
    private static boolean publicParkingLabel(Place place){return place.destination.name.matches(".*(?:공영|공공|시영|구영|군영).*");}
    private String parkingLabel(Place place,boolean recommended){return (excludedParkingIds.contains(place.destination.id)?"직접 제외 · ":recommended?"빈자리 확인 · 우선 추천 · ":place.availability.isFull(SystemClock.elapsedRealtime())?"만차 · ":publicParkingLabel(place)?"공영·공공 표기 · ":"시설 이용조건 확인 · ")+distance(place.distance);}
    private int parkingPriority(Place place,long now){return excludedParkingIds.contains(place.destination.id)?3:place.availability.priority(now);}
    private Place firstAvailable(List<Place> places,long now){for(Place place:places)if(!excludedParkingIds.contains(place.destination.id)&&place.availability.isAvailable(now))return place;return null;}
    private String inventoryDescription(Place place,long now){ParkingAvailability state=place.availability;String prefix=state.isSnapshot()?"공식 조회 · ":"";if(state.isAvailable(now))return prefix+"빈자리 "+state.availableSpaces+"대"+(state.totalSpaces==null?"":" / 전체 "+state.totalSpaces+"면");if(state.isFull(now))return prefix+"만차 · 빈자리 0대";return "빈자리 미확인\n"+state.unavailableReason(now);}
    private String inventorySource(Place place){ParkingAvailability state=place.availability;long time=state.isSnapshot()?state.retrievedAtMillis:state.observedAtMillis;if(time<=0)return state.source;SimpleDateFormat format=new SimpleDateFormat("MM.dd HH:mm",Locale.KOREA);format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));return state.source+" · "+format.format(new Date(time))+(state.isSnapshot()?" 조회 (한국시간)\n제공처 갱신 시각 미제공":" 기준 (한국시간)");}
    private void finishParkingCheck(Place requested,PlacesResponse response){
        if(!allowed())return;Place fresh=response.focus;
        if(fresh!=null&&!requested.destination.id.equals(fresh.destination.id)){navigationMessage="선택한 주차장 응답이 일치하지 않아요. 다시 선택해 주세요.";render();return;}
        if(fresh==null)for(Place place:response.places)if(place.destination.id.equals(requested.destination.id)){fresh=place;break;}
        if(fresh==null||!samePlace(fresh.destination,requested.destination)){navigationMessage="선택한 주차장의 현재 위치·정보를 다시 확인하지 못했어요. 목록에서 다시 선택해 주세요.";render();return;}
        if(excludedParkingIds.contains(fresh.destination.id)){navigationMessage="직접 제외한 주차장은 안내하지 않아요. 다른 주차장을 선택해 주세요.";render();return;}
        long now=SystemClock.elapsedRealtime();
        if(fresh.availability.isFull(now)){
            Place alternative=null;for(Place place:response.places)if(!place.destination.id.equals(fresh.destination.id)&&!excludedParkingIds.contains(place.destination.id)&&place.availability.isAvailable(now)){alternative=place;break;}
            if(alternative==null){navigationMessage=fresh.destination.name+"은 만차예요. 주변에서 빈자리가 확인된 다른 주차장을 찾지 못했어요. 새로 조회하거나 미확인 주차장 정보를 확인해 주세요.";render();return;}
            navigationMessage=fresh.destination.name+"이 만차여서 빈자리 "+alternative.availability.availableSpaces+"대인 "+alternative.destination.name+"으로 안내합니다.";
            Toast.makeText(activity,navigationMessage,Toast.LENGTH_LONG).show();navigateParkingNow(alternative);return;
        }
        if(fresh.availability.isAvailable(now)){navigationMessage="빈자리를 다시 확인했어요. "+fresh.destination.name+"으로 안내합니다.";navigateParkingNow(fresh);return;}
        final Place unverified=fresh;navigationMessage="빈자리 여부를 확인하지 못했어요. 위치 안내를 계속할지 선택해 주세요.";render();
        dialog=new AlertDialog.Builder(activity).setTitle("빈자리 미확인")
                .setMessage(fresh.destination.name+"\n\n"+fresh.availability.unavailableReason(now)+"\n주차 가능 여부를 보장할 수 없어요. 등록된 주차장 위치로 안내할까요?")
                .setPositiveButton("위치로 안내",(d,w)->{if(allowed()&&!excludedParkingIds.contains(unverified.destination.id))navigateParkingNow(unverified);})
                .setNegativeButton("다른 곳 보기",null).create();dialog.setOnDismissListener(d->{dialog=null;});dialog.show();
    }
    private void navigateParkingNow(Place place){if(!allowed())return;host.navigateToParking(place.destination,selectedRestaurant==null?null:selectedRestaurant.destination);}
    private void openInventorySource(Place place){if(!allowed()||place.availability.sourceUrl.isEmpty())return;try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(place.availability.sourceUrl)));}catch(Exception unavailable){Toast.makeText(activity,"출처 페이지를 열 수 있는 브라우저가 없어요.",Toast.LENGTH_LONG).show();}}
    private void chooseAnchor(){
        if(!allowed())return;final List<DestinationPlace> places=host.anchors();String[] labels=new String[places.size()];for(int i=0;i<places.size();i++)labels[i]=places.get(i).name;
        dialog=new AlertDialog.Builder(activity).setTitle("어느 장소 주변을 찾을까요?").setItems(labels,(d,i)->{if(allowed())host.chooseAnchor(places.get(i));}).setNegativeButton("닫기",null).show();
    }
    private void editEvidence(){
        if(!allowed()||selectedRestaurant==null)return;final String id=selectedRestaurant.destination.id;ParkingEvidenceStore.Evidence old=evidence.get(id);LinearLayout form=column();form.setPadding(dp(22),dp(12),dp(22),dp(12));form.addView(text("직접 확인한 내용만 기록해 주세요. 저장 시각과 출처가 함께 남습니다.",14,MUTED,false));gap(form,10);
        RadioGroup status=new RadioGroup(activity);String[] labels={"자체 주차 가능","자체 주차 불가","확인 필요"},values={"available","unavailable","unknown"};for(int i=0;i<labels.length;i++){RadioButton radio=new RadioButton(activity);radio.setId(100+i);radio.setText(labels[i]);radio.setMinHeight(dp(48));status.addView(radio);if(old==null&&i==2||old!=null&&old.status.equals(values[i]))status.check(100+i);}form.addView(status);
        Spinner source=new Spinner(activity);String[] sources={"직접 방문 확인","식당에 전화 확인","장소 정보·리뷰에서 확인"};source.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,sources));if(old!=null)for(int i=0;i<sources.length;i++)if(sources[i].equals(old.source))source.setSelection(i);form.addView(source,size(-1,52));
        EditText note=new EditText(activity);note.setTextSize(16);note.setMinLines(2);note.setMaxLines(4);note.setHint("예: 가게 뒤 주차장 이용 가능, 사장님 전화 확인");note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});if(old!=null)note.setText(old.note);form.addView(note,size(-1,-2));
        AlertDialog editor=new AlertDialog.Builder(activity).setTitle("내 주차 확인 메모").setView(form).setPositiveButton("저장",null).setNegativeButton("취소",null).setNeutralButton("기록 삭제",(d,w)->{if(allowed()){evidence.remove(id);render();}}).create();dialog=editor;editor.show();editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!allowed())return;if(note.getText().toString().trim().isEmpty()){note.setError("확인한 근거를 적어 주세요.");return;}evidence.save(id,values[Math.max(0,Math.min(2,status.getCheckedRadioButtonId()-100))],sources[source.getSelectedItemPosition()],note.getText().toString(),System.currentTimeMillis());editor.dismiss();render();});
    }
    private void openDetails(Place place){
        if(!allowed()||place==null)return;
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://place.map.kakao.com/"+place.destination.id)));}
        catch(Exception unavailable){Toast.makeText(activity,"장소 정보를 열 수 있는 브라우저가 없어요.",Toast.LENGTH_LONG).show();}
    }
    private final Runnable safetyWatch=new Runnable(){public void run(){if(!active||destroyed)return;if(!host.canInteract()){if(loading){cancel();error="정차 후 다시 조회해 주세요.";navigationMessage="";render();}if(dialog!=null){dialog.dismiss();dialog=null;}}else if(parkingLoaded&&!loading&&dialog==null&&SystemClock.elapsedRealtime()-lastParkingRefresh>=45000)load("parking");updateSafety();handler.postDelayed(this,400);}};
    private void updateSafety(){boolean safe=active&&!destroyed&&host.canInteract();for(View control:controls){control.setEnabled(safe);control.setAlpha(safe?1:.45f);}if(safetyLabel!=null)safetyLabel.setText(safe?"식당을 고르면 주차 자동 조회 · 안내 시작은 별도 선택":"주행 중 장소 선택이 잠겨 있어요. 정차 후 계속할 수 있어요.");}
    private void failure(LinearLayout parent){LinearLayout panel=card(SOFT);panel.addView(text(error,16,INK,false));gap(panel,10);panel.addView(action("다시 시도",true,()->load(pendingKind)),size(-1,-2));parent.addView(panel);gap(parent,12);}
    private void loading(LinearLayout parent,String label){ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setIndeterminate(true);parent.addView(bar,size(-1,4));gap(parent,10);parent.addView(text(label,16,MUTED,false));gap(parent,16);}
    private String distance(int meters){return meters<1000?meters+"m":String.format(Locale.KOREA,"%.1fkm",meters/1000.0);}
    private int dp(float v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout card(int color){LinearLayout v=column();v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(background(color,LINE));return v;}
    private ScrollView scroll(View child){ScrollView s=new ScrollView(activity);s.setFillViewport(true);s.setVerticalScrollBarEnabled(false);s.addView(child,new ScrollView.LayoutParams(-1,-2));return s;}
    private void gap(LinearLayout v,int height){v.addView(new View(activity),size(1,height));}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private GradientDrawable background(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(activity);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setTypeface(ScenicTypography.font(activity,bold?600:400));t.setIncludeFontPadding(false);t.setLineSpacing(dp(4),1);return t;}
    private TextView badge(String label){TextView t=text(label,13,ACCENT,true);t.setPadding(dp(10),dp(7),dp(10),dp(7));t.setBackground(background(SOFT,0));return t;}
    private TextView action(String label,boolean primary,Runnable click){TextView t=text(label,16,primary?PANEL:INK,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(14),dp(12),dp(14),dp(12));t.setMinHeight(dp(56));t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2224745a),background(primary?ACCENT:PANEL,primary?0:LINE),null));t.setFocusable(true);t.setClickable(true);t.setContentDescription(label);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});t.setOnClickListener(v->{if(allowed())click.run();});controls.add(t);return t;}
    private static final class Place {final DestinationPlace destination;final int distance;final String category,phone;final ParkingAvailability availability;Place(DestinationPlace destination,int distance){this(destination,distance,"","",ParkingAvailability.unknown(SystemClock.elapsedRealtime()));}Place(DestinationPlace destination,int distance,String category,String phone,ParkingAvailability availability){this.destination=destination;this.distance=distance;this.category=category;this.phone=phone;this.availability=availability;}}
    private static final class PlacesResponse {final List<Place> places;final Place focus;PlacesResponse(List<Place> places,Place focus){this.places=places;this.focus=focus;}}
    private static final class PlaceFailure extends Exception {PlaceFailure(String message){super(message);}}
}
