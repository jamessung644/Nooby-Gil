package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Parked-only destination photo browsing. Images never imply verified road visibility. */
public final class DestinationPhotoView {
    public interface Host {
        boolean canInteract();
        String serverBaseUrl();
        void onPhotoSelected(ScenicPhotoCollection.Photo photo);
        void onSkip();
    }
    private static final int INK=0xff172923,MUTED=0xff63736b,GREEN=0xff315e4d,LINE=0xffd8e2da,SOFT=0xffeaf0e9;
    private static final int MAX_IMAGE_BYTES=4*1024*1024;
    private final Activity activity;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService requests=Executors.newSingleThreadExecutor(),images=Executors.newFixedThreadPool(3);
    private final List<View> controls=new ArrayList<>();
    private final List<Future<?>> imageJobs=new ArrayList<>();
    private final Set<HttpURLConnection> connections=Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private final Set<String> failedImages=new HashSet<>();
    private final Map<String,List<ImageTarget>> waitingImages=new HashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    private DestinationPlace destination;
    private ScenicPhotoCollection collection;
    private LinearLayout body;
    private boolean active,destroyed,wide,loading;
    private int radius=10000,generation;
    private String error="",loadedBase="";
    private Future<?> requestJob;
    private ScenicApiClient api;
    private AlertDialog dialog;
    private TextView safety,skipButton;

    public DestinationPhotoView(Activity activity,Host host){this.activity=activity;this.host=host;}

    public View createView(DestinationPlace chosen,boolean wide){
        if(chosen==null)throw new IllegalArgumentException("A chosen destination is required");
        boolean changed=destination==null||!sameDestination(destination,chosen)||!host.serverBaseUrl().equals(loadedBase);
        pause();this.destination=chosen;this.wide=wide;active=true;
        if(changed){radius=10000;collection=null;error="";failedImages.clear();}
        body=column();body.setPadding(dp(12),dp(12),dp(12),dp(30));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(body);
        render();main.post(safetyWatch);
        if(collection==null&&error.isEmpty())refresh();
        return scroll;
    }

    /** Explicit refresh also renews the server's expiring scenic selection tokens. */
    public void refresh(){
        if(!allowed()||destination==null)return;
        cancelRequests();failedImages.clear();loading=true;error="";
        final int token=generation;final DestinationPlace expected=destination;final int expectedRadius=radius;
        final String base=host.serverBaseUrl();loadedBase=base;render();
        try{api=new ScenicApiClient(base);}catch(IllegalArgumentException invalid){loading=false;error="서버 연결 주소를 확인한 뒤 다시 불러와 주세요.";render();return;}
        final ScenicApiClient client=api;
        requestJob=requests.submit(()->{
            try{
                ScenicPhotoCollection result=ScenicPhotoCollection.parse(client.scenicPhotos(expected,expectedRadius),expected,expectedRadius);
                main.post(()->{if(!current(token))return;loading=false;api=null;requestJob=null;collection=result;render();});
            }catch(Exception failure){
                final String message=failure instanceof ScenicApiClient.ApiException?failure.getMessage():"사진 정보를 확인하지 못했어요. 잠시 후 다시 불러와 주세요.";
                main.post(()->{if(!current(token))return;loading=false;api=null;requestJob=null;collection=null;error=message;render();});
            }
        });
    }

    private void render(){
        if(body==null||destroyed)return;body.removeAllViews();controls.clear();
        body.addView(text("DESTINATION · SCENIC PHOTOS",12,GREEN,true));gap(body,10);
        body.addView(text(destination.name+" 근처, 마음에 드는 풍경",wide?30:25,INK,true));gap(body,10);
        LinearLayout fixed=column();fixed.setPadding(dp(18),dp(15),dp(18),dp(15));fixed.setBackground(background(SOFT,0));
        fixed.addView(text("최종 목적지  ·  "+destination.name,19,GREEN,true));gap(fixed,5);
        fixed.addView(text("사진 속 장소를 경유해도 마지막 도착지는 그대로예요.",14,INK,false));
        if(!destination.address.isEmpty()){gap(fixed,5);fixed.addView(text(destination.address,13,MUTED,false));}
        body.addView(fixed,size(-1,-2));gap(body,16);
        LinearLayout actions=wide?row():column();
        LinearLayout ranges=row();for(int km:new int[]{5,10,20}){TextView option=button(km+"km",radius==km*1000,()->{radius=km*1000;collection=null;refresh();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,0,dp(6),0);ranges.addView(option,p);}
        if(wide)actions.addView(ranges,new LinearLayout.LayoutParams(0,-2,1));else actions.addView(ranges,size(-1,-2));
        TextView retry=button("사진 새로 불러오기",false,this::refresh),skip=button("사진 없이 빠른 길",false,host::onSkip);
        skipButton=skip;skip.setOnClickListener(v->{if(allowed()){cancelRequests();host.onSkip();}});
        if(wide){space(actions,10);actions.addView(retry);space(actions,10);actions.addView(skip);}else{gap(actions,8);actions.addView(retry,size(-1,-2));gap(actions,8);actions.addView(skip,size(-1,-2));}
        body.addView(actions,size(-1,-2));gap(body,8);
        body.addView(text("목적지에서 직선거리 "+radius/1000+"km 이내 · 실제 도로 우회시간은 사진을 고른 다음 확인해요.",13,MUTED,false));gap(body,10);
        safety=text("",14,GREEN,true);body.addView(safety);gap(body,10);
        if(loading){ProgressBar progress=new ProgressBar(activity);body.addView(progress,size(42,42));gap(body,12);body.addView(text("목적지 주변의 공개 사진과 분석 결과를 불러오고 있어요…",17,INK,true));gap(body,20);}
        if(!error.isEmpty()){body.addView(text(error,17,INK,true));gap(body,12);body.addView(text("다시 불러오거나, 사진 없이 빠른 길을 조회할 수 있어요.",14,MUTED,false));}
        if(collection!=null&&!loading){
            body.addView(text("vision".equals(collection.rankingMode)?"OpenAI · 사진 시각 분석":"목적지 가까운 순 · AI 사진 분석 미사용",16,GREEN,true));gap(body,6);
            body.addView(text(collection.rankingMessage,14,MUTED,false));
            if(!collection.message.isEmpty()){gap(body,6);body.addView(text(collection.message,14,MUTED,false));}gap(body,16);
            if(collection.photos.isEmpty()){body.addView(text("이 범위에서 사용할 수 있는 풍경 사진이 없어요.",21,INK,true));gap(body,10);body.addView(text("탐색 범위를 넓히거나 사진 없이 빠른 길을 조회해 보세요.",15,MUTED,false));}
            else for(int index=0;index<collection.photos.size();){
                LinearLayout line=wide?row():column();line.setGravity(Gravity.TOP);
                for(int col=0;col<(wide?2:1)&&index<collection.photos.size();col++,index++){
                    if(col>0)space(line,16);line.addView(card(collection.photos.get(index)),wide?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));
                }
                body.addView(line,size(-1,-2));gap(body,18);
            }
        }
        updateSafety();
    }

    private View card(ScenicPhotoCollection.Photo photo){
        LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(18));card.setBackground(background(0xffffffff,LINE));
        card.addView(photoFrame(photo,wide?235:230),size(-1,wide?235:230));gap(card,14);
        card.addView(text(photo.place.name,23,INK,true));gap(card,7);
        card.addView(text(String.format(Locale.KOREA,"목적지에서 직선 %.1fkm",photo.distanceMeters/1000),14,GREEN,true));
        if(!photo.place.address.isEmpty()){gap(card,5);card.addView(text(photo.place.address,13,MUTED,false));}gap(card,12);
        card.addView(text(photo.visualScore==null?"거리 기반 안내":"OpenAI 사진 분석 · "+photo.visualScore.intValue()+"/100",13,GREEN,true));gap(card,6);
        card.addView(text(photo.reason,15,INK,false));gap(card,10);
        card.addView(text("장소 위치는 사진 촬영 지점과 다를 수 있어요. 촬영 방향·시기와 실제 차창 조망은 미확인입니다.",12,MUTED,false));gap(card,7);
        card.addView(text(photo.sourceName+" · "+photo.license,12,MUTED,false));gap(card,12);
        card.addView(button("원본 사진 · 출처 보기",false,()->showSource(photo)),size(-1,-2));gap(card,8);
        card.addView(button("이 풍경을 경유지로",true,()->choose(photo)),size(-1,-2));return card;
    }

    private View photoFrame(ScenicPhotoCollection.Photo photo,int height){
        FrameLayout frame=new FrameLayout(activity);frame.setBackground(background(SOFT,0));frame.setClipToOutline(true);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(photo.place.name+" 공개 장소 사진");frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView state=text("사진을 불러오는 중…",14,MUTED,true);state.setGravity(Gravity.CENTER);state.setPadding(dp(16),dp(12),dp(16),dp(12));frame.addView(state,new FrameLayout.LayoutParams(-1,-1));
        requestImage(photo.thumbnailUrl,new ImageTarget(image,state));return frame;
    }

    /** A small selected-place summary; it never starts a new request while the browser is paused. */
    public View createSelectionPreview(ScenicPhotoCollection.Photo photo){
        LinearLayout preview=row();preview.setPadding(dp(14),dp(14),dp(14),dp(14));preview.setBackground(background(SOFT,0));
        Bitmap bitmap=cache.get(photo.thumbnailUrl);if(bitmap!=null){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(bitmap);image.setContentDescription(photo.place.name+" 선택한 공개 사진");preview.addView(image,size(138,92));space(preview,14);}
        LinearLayout copy=column();copy.addView(text("선택한 풍경 · "+photo.place.name,17,GREEN,true));gap(copy,5);
        copy.addView(text(bitmap==null?"사진 미리보기 없음 · 선택한 장소 기준으로 경로를 확인해요.":"최종 목적지는 유지하고 이 장소를 경유해요.",13,MUTED,false));gap(copy,5);
        copy.addView(text(photo.sourceName+" · "+photo.license,11,MUTED,false));preview.addView(copy,new LinearLayout.LayoutParams(0,-2,1));return preview;
    }

    private void choose(ScenicPhotoCollection.Photo photo){
        if(!allowed()||loading)return;
        if(cache.get(photo.thumbnailUrl)==null){
            dialog=new AlertDialog.Builder(activity).setTitle("사진을 아직 확인하지 못했어요")
                    .setMessage(photo.place.name+"의 장소 좌표를 경유지로 선택할 수 있어요. 실제 풍경은 사진으로 확인하지 못한 상태입니다.")
                    .setPositiveButton("장소 기준으로 선택",(d,w)->{if(allowed())host.onPhotoSelected(photo);})
                    .setNegativeButton("취소",null).show();
        }else host.onPhotoSelected(photo);
    }

    private void showSource(ScenicPhotoCollection.Photo photo){
        if(!allowed())return;
        dialog=new AlertDialog.Builder(activity).setTitle(photo.place.name+" · 사진 정보")
                .setMessage(photo.sourceName+"\n"+photo.license+"\n\n"+photo.sourceUrl+"\n\n사진은 관광 장소를 보여 줍니다. 촬영 지점·방향·시기 및 주행 중 조망은 확인되지 않았어요.")
                .setPositiveButton("원본 사진 열기",(d,w)->openExternal(photo.imageUrl))
                .setNeutralButton("출처 열기",(d,w)->openExternal(photo.sourceUrl)).setNegativeButton("닫기",null).show();
    }
    private void openExternal(String url){if(!allowed())return;try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception unavailable){Toast.makeText(activity,"링크를 열 수 있는 앱이 없어요.",Toast.LENGTH_SHORT).show();}}

    private static final class ImageTarget {final ImageView image;final TextView state;ImageTarget(ImageView image,TextView state){this.image=image;this.state=state;}}
    private void requestImage(String url,ImageTarget target){
        Bitmap existing=cache.get(url);if(existing!=null){target.image.setImageBitmap(existing);target.state.setVisibility(View.GONE);return;}
        if(!allowed()){target.state.setText("정차 후 사진을 다시 불러와 주세요.");return;}
        if(failedImages.contains(url)){target.state.setText("사진을 불러오지 못했어요.\n장소 정보는 확인할 수 있어요.");return;}
        List<ImageTarget> targets=waitingImages.get(url);if(targets!=null){targets.add(target);return;}
        targets=new ArrayList<>();targets.add(target);waitingImages.put(url,targets);final int token=generation;
        imageJobs.add(images.submit(()->{Bitmap loaded=null;try{loaded=downloadImage(url);}catch(Exception ignored){}final Bitmap result=loaded;
            main.post(()->{if(!current(token)){if(result!=null)result.recycle();return;}List<ImageTarget> waiting=waitingImages.remove(url);
                if(result!=null)cache.put(url,result);else failedImages.add(url);
                if(waiting!=null)for(ImageTarget item:waiting){if(result!=null){item.image.setImageBitmap(result);item.state.setVisibility(View.GONE);}else item.state.setText("사진을 불러오지 못했어요.\n장소 정보는 확인할 수 있어요.");}
            });
        }));
    }

    private Bitmap downloadImage(String url)throws Exception {
        if(!ScenicPhotoCollection.safeImageUrl(url)||Thread.currentThread().isInterrupted())return null;
        final long deadline=System.nanoTime()+20000000000L;
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connections.add(connection);
        try{
            connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("Accept","image/jpeg,image/png,image/webp");
            if(Thread.currentThread().isInterrupted()||connection.getResponseCode()!=200)return null;
            if(!ScenicPhotoCollection.safeImageContentType(connection.getContentType()))return null;
            if(connection.getContentLengthLong()>MAX_IMAGE_BYTES)return null;
            byte[] data;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[8192];int n,total=0;while((n=input.read(chunk))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return null;total+=n;if(total>MAX_IMAGE_BYTES)return null;bytes.write(chunk,0,n);}data=bytes.toByteArray();
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>40000000L)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inPreferredConfig=Bitmap.Config.RGB_565;
            while(bounds.outWidth/options.inSampleSize>960||bounds.outHeight/options.inSampleSize>960)options.inSampleSize*=2;
            return BitmapFactory.decodeByteArray(data,0,data.length,options);
        }finally{connections.remove(connection);connection.disconnect();}
    }

    private final Runnable safetyWatch=new Runnable(){public void run(){
        if(!active||destroyed)return;
        if(!host.canInteract()){
            if(loading||!waitingImages.isEmpty()){cancelRequests();error=collection==null?"정차 후 사진을 다시 불러와 주세요.":"";render();}
            closeDialog();
        }
        updateSafety();main.postDelayed(this,400);
    }};
    private void updateSafety(){boolean safe=allowed();for(View control:controls){control.setEnabled(safe&&(!loading||control==skipButton));control.setAlpha(control.isEnabled()?1:.45f);}if(safety!=null)safety.setText(safe?"정차 중에 사진을 고를 수 있어요.":"주행 중에는 사진 선택을 사용할 수 없어요. 정차 후 계속해 주세요.");}
    public void pause(){active=false;main.removeCallbacks(safetyWatch);cancelRequests();closeDialog();}
    public void destroy(){if(destroyed)return;pause();destroyed=true;requests.shutdownNow();images.shutdownNow();cache.evictAll();body=null;}
    private void cancelRequests(){generation++;if(api!=null){api.cancel();api=null;}if(requestJob!=null){requestJob.cancel(true);requestJob=null;}for(Future<?> future:imageJobs)future.cancel(true);imageJobs.clear();synchronized(connections){for(HttpURLConnection connection:connections)connection.disconnect();connections.clear();}waitingImages.clear();loading=false;}
    private void closeDialog(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    private boolean current(int token){return token==generation&&active&&!destroyed&&!activity.isFinishing();}
    private boolean allowed(){return active&&!destroyed&&!activity.isFinishing()&&host.canInteract();}
    private static boolean sameDestination(DestinationPlace a,DestinationPlace b){return a.id.equals(b.id)&&a.name.equals(b.name)&&a.lat==b.lat&&a.lon==b.lon;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout value=new LinearLayout(activity);value.setOrientation(LinearLayout.VERTICAL);return value;}
    private LinearLayout row(){LinearLayout value=column();value.setOrientation(LinearLayout.HORIZONTAL);value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private LinearLayout.LayoutParams size(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private void gap(LinearLayout view,int value){view.addView(new View(activity),size(1,value));}
    private void space(LinearLayout view,int value){view.addView(new View(activity),size(value,1));}
    private GradientDrawable background(int color,int stroke){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(14));if(stroke!=0)value.setStroke(dp(1),stroke);return value;}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(ScenicTypography.font(activity,bold?600:400));view.setIncludeFontPadding(false);view.setLineSpacing(dp(3),1);return view;}
    private TextView button(String label,boolean primary,Runnable action){TextView view=text(label,15,primary?0xffffffff:INK,true);view.setPadding(dp(16),dp(14),dp(16),dp(14));view.setGravity(Gravity.CENTER);view.setMinHeight(dp(54));view.setBackground(background(primary?GREEN:0xffffffff,primary?0:LINE));view.setFocusable(true);view.setOnClickListener(v->{if(allowed()&&!loading)action.run();});controls.add(view);return view;}
}
