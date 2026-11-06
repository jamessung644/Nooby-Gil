package com.scenic.pleos;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Live Kakao place discovery. Parking inventory and restaurant reviews are not inferred. */
public final class NearbyStopsView {
    public interface Host {
        boolean canInteract();
        String serverBaseUrl();
        DestinationPlace anchor();
        void navigate(DestinationPlace place);
    }
    private static final int INK=0xff172925,MUTED=0xff65736e,LINE=0xffdbe3df,PANEL=0xffffffff,SOFT=0xffedf4ef,ACCENT=0xff24745a;
    private final Activity activity;
    private final Host host;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final List<Place> restaurants=new ArrayList<>(),parkings=new ArrayList<>();
    private volatile HttpURLConnection connection;
    private Future<?> job;
    private LinearLayout root;
    private boolean wide,active,destroyed,loading;
    private long revision;
    private String anchorKey="",baseUrl="",error="",pendingKind="restaurant";
    private boolean restaurantsLoaded,parkingLoaded;
    private DestinationPlace anchor;
    private Place selectedRestaurant;

    public NearbyStopsView(Activity activity,Host host){this.activity=activity;this.host=host;}

    public View createView(boolean wide){
        this.wide=wide;active=true;
        DestinationPlace next=host.anchor();
        String nextBase;
        try{nextBase=EndpointPolicy.normalizeBaseUrl(host.serverBaseUrl());}catch(IllegalArgumentException invalid){nextBase="";}
        String key=next==null?"none":next.id+"|"+next.lat+"|"+next.lon;
        if(!key.equals(anchorKey)||!nextBase.equals(baseUrl)){
            cancel();anchor=next;anchorKey=key;baseUrl=nextBase;restaurants.clear();parkings.clear();selectedRestaurant=null;
            restaurantsLoaded=false;parkingLoaded=false;error="";pendingKind="restaurant";
        }
        root=column();render();
        if(anchor!=null&&!baseUrl.isEmpty()&&!restaurantsLoaded&&!loading&&error.isEmpty())handler.post(()->{if(active&&!destroyed&&!loading&&!restaurantsLoaded)load("restaurant");});
        return root;
    }
    public void pause(){active=false;cancel();}
    public void destroy(){destroyed=true;pause();executor.shutdownNow();handler.removeCallbacksAndMessages(null);}

    private void cancel(){revision++;loading=false;if(job!=null){job.cancel(true);job=null;}HttpURLConnection old=connection;connection=null;if(old!=null)old.disconnect();}
    private boolean allowed(){if(!active||destroyed||!host.canInteract()){Toast.makeText(activity,"정차 후 장소를 선택해 주세요.",Toast.LENGTH_SHORT).show();return false;}return true;}
    private void load(String kind){
        if(!allowed())return;
        DestinationPlace point="parking".equals(kind)&&selectedRestaurant!=null?selectedRestaurant.destination:anchor;
        if(point==null||baseUrl.isEmpty()){error="경로에서 목적지와 API 서버를 먼저 확인해 주세요.";render();return;}
        cancel();pendingKind=kind;loading=true;error="";final long token=revision;
        final String endpoint=baseUrl+"/api/nearby?kind="+kind+"&lat="+point.lat+"&lon="+point.lon;
        render();
        job=executor.submit(()->{
            List<Place> results=null;String failure=null;
            try{results=request(endpoint,kind);}catch(Exception problem){failure=problem instanceof PlaceFailure?problem.getMessage():"주변 장소를 불러오지 못했어요. 서버 연결을 확인하고 다시 시도해 주세요.";}
            final List<Place> found=results;final String message=failure;
            handler.post(()->{
                if(destroyed||!active||token!=revision)return;
                loading=false;job=null;
                if(!host.canInteract()){error="정차 후 장소를 다시 조회해 주세요.";return;}
                error=message==null?"":message;
                if(message==null){if("restaurant".equals(kind)){restaurants.clear();restaurants.addAll(found);restaurantsLoaded=true;}else{parkings.clear();parkings.addAll(found);parkingLoaded=true;}}
                render();
            });
        });
    }
    private List<Place> request(String endpoint,String kind)throws Exception{
        HttpURLConnection current=(HttpURLConnection)new URL(endpoint).openConnection();connection=current;
        current.setConnectTimeout(10000);current.setReadTimeout(16000);current.setInstanceFollowRedirects(false);current.setRequestProperty("Accept","application/json");
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
                JSONObject row=rows.getJSONObject(i);DestinationPlace destination=DestinationPlace.read(row,"Kakao Local");
                int distance=row.getInt("distanceMeters");if(distance<0||distance>3000||!"unknown".equals(row.getString("parkingStatus")))throw new PlaceFailure("장소 응답을 확인하지 못했어요.");
                result.add(new Place(destination,distance));
            }
            return result;
        }finally{current.disconnect();if(connection==current)connection=null;}
    }

    private void render(){
        if(root==null)return;root.removeAllViews();
        LinearLayout header=row();header.addView(text("한 끼부터 주차까지",28,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        header.addView(badge("Kakao Local · 실제 검색"));root.addView(header,size(-1,-2));gap(root,8);
        root.addView(text(anchor==null?"먼저 경로에서 목적지를 선택해 주세요.":anchor.name+" 주변 3km · 거리순 음식점 · 맛집 평점 순위가 아닙니다",14,MUTED,false));gap(root,18);
        if(baseUrl.isEmpty()){root.addView(text("설정에서 API 서버 주소를 확인해 주세요.",17,INK,false));return;}
        if(anchor==null)return;
        LinearLayout columns=new LinearLayout(activity);columns.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);columns.setGravity(Gravity.TOP);
        LinearLayout left=column(),right=column();
        if(wide){columns.addView(scroll(left),new LinearLayout.LayoutParams(0,-1,.46f));View spacer=new View(activity);columns.addView(spacer,size(22,1));columns.addView(scroll(right),new LinearLayout.LayoutParams(0,-1,.54f));root.addView(columns,new LinearLayout.LayoutParams(-1,0,1));}
        else{columns.addView(left,size(-1,-2));gap(columns,18);columns.addView(right,size(-1,-2));root.addView(scroll(columns),new LinearLayout.LayoutParams(-1,0,1));}
        LinearLayout listHeading=row();listHeading.addView(text("01  근처 음식점",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));listHeading.addView(action("새로고침",false,()->load("restaurant")),size(112,-2));left.addView(listHeading);gap(left,12);
        if(loading&&"restaurant".equals(pendingKind))loading(left,"근처 음식점을 찾고 있어요…");
        if(!error.isEmpty()&&"restaurant".equals(pendingKind))failure(left);
        if(restaurantsLoaded&&restaurants.isEmpty())left.addView(text("반경 3km에서 음식점을 찾지 못했어요. 다른 목적지 주변을 살펴보세요.",16,MUTED,false));
        for(Place place:restaurants){
            boolean selected=selectedRestaurant!=null&&selectedRestaurant.destination.id.equals(place.destination.id);
            LinearLayout card=card(selected?SOFT:PANEL);card.addView(badge((selected?"선택한 음식점 · ":"")+distance(place.distance)));
            gap(card,9);card.addView(text(place.destination.name,21,INK,true));gap(card,8);card.addView(text(place.destination.address,14,MUTED,false));gap(card,13);
            card.addView(action(selected?"선택됨 · 주차 확인하기":"이 음식점 선택",selected,()->{if(!allowed())return;cancel();selectedRestaurant=place;parkings.clear();parkingLoaded=false;error="";pendingKind="parking";render();}),size(-1,-2));left.addView(card,size(-1,-2));gap(left,12);
        }
        LinearLayout selection=card(PANEL);right.addView(selection,size(-1,-2));selection.addView(text("02  도착하기 전에",19,INK,true));gap(selection,14);
        if(selectedRestaurant==null){
            selection.addView(text("마음에 드는 식당을 고르면\n주차까지 이어서 확인해요.",25,INK,true));gap(selection,14);
            selection.addView(text("식당 자체 주차가 불확실할 때는\n주변 주차장을 한 번에 찾을 수 있어요.",16,MUTED,false));gap(selection,18);
            selection.addView(action("목적지 주변 주차장 먼저 찾기",true,()->load("parking")),size(-1,-2));
        }else{
            selection.addView(text(selectedRestaurant.destination.name,25,INK,true));gap(selection,8);selection.addView(text(selectedRestaurant.destination.address,15,MUTED,false));gap(selection,18);
            selection.addView(badge("식당 내 주차 · 확인 필요"));gap(selection,8);
            selection.addView(text("주차 리뷰와 실시간 빈자리는 아직 연결되지 않았어요. 장소 정보에서 확인하거나 가까운 주차장을 찾아보세요.",14,MUTED,false));gap(selection,18);
            selection.addView(action("이 식당으로 경로 보기",true,()->navigate(selectedRestaurant)),size(-1,-2));gap(selection,8);
            selection.addView(action("주변 주차장 찾기",false,()->load("parking")),size(-1,-2));gap(selection,8);
            selection.addView(action("장소 정보 · 리뷰 열기",false,()->openDetails(selectedRestaurant)),size(-1,-2));
        }
        gap(right,18);
        if(loading&&"parking".equals(pendingKind))loading(right,"선택한 장소 주변 주차장을 찾고 있어요…");
        if(!error.isEmpty()&&"parking".equals(pendingKind))failure(right);
        if(parkingLoaded){
            right.addView(text("03  가까운 주차장",19,INK,true));gap(right,6);
            right.addView(text((selectedRestaurant==null?anchor.name:selectedRestaurant.destination.name)+" 기준 직선거리 · 빈자리·요금·운영시간 미확인",13,MUTED,false));gap(right,12);
            if(parkings.isEmpty())right.addView(text("반경 3km에서 등록된 주차장을 찾지 못했어요. 식당에 주차 가능 여부를 확인해 주세요.",16,MUTED,false));
            for(Place place:parkings){LinearLayout card=card(PANEL);card.addView(badge(distance(place.distance)));gap(card,8);card.addView(text(place.destination.name,20,INK,true));gap(card,7);card.addView(text(place.destination.address,14,MUTED,false));gap(card,13);card.addView(action("이 주차장으로 경로 보기",true,()->navigate(place)),size(-1,-2));right.addView(card,size(-1,-2));gap(right,10);}
        }
    }
    private void navigate(Place place){if(!allowed()||place==null)return;host.navigate(place.destination);}
    private void openDetails(Place place){
        if(!allowed()||place==null)return;
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://place.map.kakao.com/"+place.destination.id)));}
        catch(Exception unavailable){Toast.makeText(activity,"장소 정보를 열 수 있는 브라우저가 없어요.",Toast.LENGTH_LONG).show();}
    }
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
    private TextView action(String label,boolean primary,Runnable click){TextView t=text(label,16,primary?PANEL:INK,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(14),dp(12),dp(14),dp(12));t.setMinHeight(dp(56));t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2224745a),background(primary?ACCENT:PANEL,primary?0:LINE),null));t.setFocusable(true);t.setClickable(true);t.setContentDescription(label);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});t.setOnClickListener(v->{if(allowed())click.run();});return t;}
    private static final class Place {final DestinationPlace destination;final int distance;Place(DestinationPlace destination,int distance){this.destination=destination;this.distance=distance;}}
    private static final class PlaceFailure extends Exception {PlaceFailure(String message){super(message);}}
}
