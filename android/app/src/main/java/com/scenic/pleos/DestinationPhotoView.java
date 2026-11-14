package com.scenic.pleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
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
    private static final int INK=ScenicDesign.INK,MUTED=ScenicDesign.MUTED,ACCENT=ScenicDesign.ACCENT,LINE=ScenicDesign.LINE,SOFT=ScenicDesign.SOFT;
    private static final int MAX_IMAGE_BYTES=4*1024*1024;
    private final Activity activity;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService requests=Executors.newSingleThreadExecutor(),images=Executors.newFixedThreadPool(3);
    private final List<View> controls=new ArrayList<>();
    private final List<Future<?>> imageJobs=new ArrayList<>();
    private final Set<HttpURLConnection> connections=Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private final Set<String> failedImages=new HashSet<>();
    private final Set<String> displayedImages=new HashSet<>();
    private final Map<String,List<ImageTarget>> waitingImages=new HashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    private DestinationPlace destination;
    private ScenicPhotoCollection collection;
    private LinearLayout body;
    private boolean active,destroyed,loading,regionMode,settingsExpanded;
    private int radius=10000,generation,cardColumns=1;
    private String error="",loadedBase="";
    private Future<?> requestJob;
    private ScenicApiClient api;
    private AlertDialog dialog;
    private TextView safety,skipButton;

    public DestinationPhotoView(Activity activity,Host host){this.activity=activity;this.host=host;}

    public View createView(DestinationPlace chosen,boolean wide){
        return createView(chosen,wide,false);
    }
    public View createView(DestinationPlace chosen,boolean wide,boolean regionMode){
        if(chosen==null)throw new IllegalArgumentException("A chosen destination is required");
        boolean changed=destination==null||!sameDestination(destination,chosen)||!host.serverBaseUrl().equals(loadedBase)||this.regionMode!=regionMode;
        pause();this.destination=chosen;this.regionMode=regionMode;active=true;
        if(changed){radius=10000;collection=null;error="";failedImages.clear();}
        cardColumns=photoColumns(activity.getResources().getConfiguration().screenWidthDp-48);
        body=column();body.setPadding(0,0,0,dp(32));final LinearLayout viewBody=body;
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(body);
        scroll.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            if(body!=viewBody||right<=left||right-left==oldRight-oldLeft)return;
            int columns=photoColumns(Math.round((right-left)/activity.getResources().getDisplayMetrics().density));
            if(columns!=cardColumns){cardColumns=columns;viewBody.post(()->{if(body==viewBody&&active)render();});}
        });
        render();main.post(safetyWatch);
        if(collection==null&&error.isEmpty())refresh();
        return scroll;
    }
    private int photoColumns(int widthDp){int minimum=Math.round(360*Math.max(1f,activity.getResources().getConfiguration().fontScale));return Math.max(1,Math.min(2,(widthDp+24)/(minimum+24)));}

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
        if(body==null||destroyed)return;body.removeAllViews();controls.clear();displayedImages.clear();
        boolean roomy=cardColumns>1;
        body.addView(text(regionMode?"방문할 풍경 선택":"경유할 풍경 선택",16,ACCENT,true));gap(body,8);
        LinearLayout headingRow=roomy?row():column();
        TextView destinationTitle=text(destination.name,36,INK,true);
        headingRow.addView(destinationTitle,roomy?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));
        TextView retry=quietButton("새로고침",this::refresh);
        if(roomy){space(headingRow,16);headingRow.addView(retry);}else{gap(headingRow,8);headingRow.addView(retry);}
        body.addView(headingRow,size(-1,-2));gap(body,16);
        TextView skip=quietButton(regionMode?"지역 중심으로 가기":"사진 없이 빠른 길",host::onSkip);
        skipButton=skip;skip.setOnClickListener(v->{if(allowed()){cancelRequests();host.onSkip();}});
        safety=text("",16,ACCENT,true);safety.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(safety);
        if(loading){
            LinearLayout state=column();state.setPadding(dp(24),dp(24),dp(24),dp(24));state.setBackground(background(SOFT,0));
            ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);state.addView(progress,size(-1,4));gap(state,16);
            state.addView(text("주변 풍경을 찾고 있어요",24,INK,true));body.addView(state,size(-1,-2));gap(body,24);
        }
        if(!error.isEmpty()){
            LinearLayout state=column();state.setPadding(dp(24),dp(24),dp(24),dp(24));state.setBackground(background(SOFT,0));
            state.addView(text(error,18,INK,true));gap(state,10);
            state.addView(text(regionMode?"사진을 새로 불러오거나 지역 중심으로 가는 길을 확인해 보세요.":"사진을 새로 불러오거나 사진 없이 빠른 길을 확인해 보세요.",15,MUTED,false));body.addView(state,size(-1,-2));
        }
        if(collection!=null&&!loading){
            body.addView(text("추천 풍경 · "+collection.photos.size()+"곳",26,INK,true));gap(body,8);
            body.addView(text(("vision".equals(collection.rankingMode)?"AI 사진 분석 순":"가까운 순 · AI 분석 미사용")+"  ·  직선 "+radius/1000+" km",15,MUTED,false));gap(body,24);
            if(collection.photos.isEmpty()){body.addView(text("아직 찾은 풍경 사진이 없어요",22,INK,true));gap(body,10);body.addView(text(regionMode?"탐색 범위를 넓히거나 다른 드라이브 지역을 골라보세요.":"탐색 범위를 넓히거나 사진 없이 빠른 길을 확인해 보세요.",17,MUTED,false));}
            else for(int index=0;index<collection.photos.size();){
                LinearLayout line=row();line.setGravity(Gravity.TOP);
                for(int col=0;col<cardColumns;col++){
                    if(col>0)space(line,24);
                    if(index<collection.photos.size())line.addView(card(collection.photos.get(index++)),new LinearLayout.LayoutParams(0,-2,1));
                    else line.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
                }
                body.addView(line,size(-1,-2));gap(body,24);
            }
        }
        body.addView(skip,size(-1,-2));gap(body,24);
        addSearchDetails();
        updateSafety();
    }

    private void addSearchDetails(){
        LinearLayout details=column();details.setPadding(dp(20),dp(12),dp(20),dp(12));details.setBackground(background(SOFT,0));
        TextView toggle=quietButton(settingsExpanded?"탐색 범위 접기":"탐색 범위 변경",()->{settingsExpanded=!settingsExpanded;render();});
        toggle.setContentDescription("탐색 범위 · "+(settingsExpanded?"펼쳐짐":"접힘"));details.addView(toggle,size(-1,-2));
        if(settingsExpanded){
            gap(details,16);details.addView(text("탐색 범위",17,INK,true));gap(details,8);LinearLayout ranges=row();
            for(int km:new int[]{5,10,20}){if(ranges.getChildCount()>0)space(ranges,8);boolean selected=radius==km*1000;TextView range=button(km+" km",selected,()->{radius=km*1000;collection=null;refresh();});range.setSelected(selected);range.setContentDescription("탐색 범위 "+km+"킬로미터"+(selected?" · 선택됨":""));ranges.addView(range,new LinearLayout.LayoutParams(0,-2,1));}
            details.addView(ranges,size(-1,-2));
        }
        body.addView(details,size(-1,-2));
    }

    private View card(ScenicPhotoCollection.Photo photo){
        LinearLayout card=column();card.setBackground(background(ScenicDesign.SURFACE,LINE));card.setClipToOutline(true);
        card.addView(photoFrame(photo),size(-1,cardColumns>1?304:272));
        LinearLayout details=column();details.setPadding(dp(20),dp(20),dp(20),dp(20));
        String metadata=String.format(Locale.KOREA,"직선 %.1f km",photo.distanceMeters/1000);
        details.addView(text(metadata,16,ACCENT,true));gap(details,8);details.addView(text(photo.place.name,28,INK,true));gap(details,20);
        details.addView(button(regionMode?"이 풍경 보러 가기":"경유지로 선택",true,()->choose(photo)),size(-1,-2));gap(details,8);
        details.addView(quietButton("사진 정보·출처",()->showSource(photo)),size(-1,-2));gap(details,8);
        details.addView(text(photo.sourceName+" · "+photo.license,12,MUTED,false));card.addView(details,size(-1,-2));return card;
    }

    private View photoFrame(ScenicPhotoCollection.Photo photo){
        FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(SOFT);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(photo.place.name+" 공개 장소 사진");frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView state=text("사진을 불러오는 중…",14,MUTED,true);state.setGravity(Gravity.CENTER);state.setPadding(dp(16),dp(12),dp(16),dp(12));frame.addView(state,new FrameLayout.LayoutParams(-1,-1));
        requestImage(photo.imageUrl,photo.thumbnailUrl,new ImageTarget(image,state));return frame;
    }

    /** A small selected-place summary; it never starts a new request while the browser is paused. */
    public View createSelectionPreview(ScenicPhotoCollection.Photo photo){
        return createSelectionPreview(photo,false);
    }
    public View createSelectionPreview(ScenicPhotoCollection.Photo photo,boolean regionMode){
        LinearLayout preview=column();preview.setPadding(dp(16),dp(16),dp(16),dp(16));preview.setBackground(background(SOFT,0));
        Bitmap bitmap=cache.get(photo.imageUrl);ImageView image=new ImageView(activity);View gutter=new View(activity);
        if(bitmap!=null){image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(bitmap);image.setContentDescription(photo.place.name+" 선택한 공개 사진");image.setBackground(background(SOFT,0));image.setClipToOutline(true);preview.addView(image,size(-1,180));preview.addView(gutter,size(1,16));}
        LinearLayout copy=column();copy.addView(text(regionMode?"풍경 목적지":"풍경 경유지",14,ACCENT,true));gap(copy,8);copy.addView(text(photo.place.name,24,INK,true));gap(copy,8);
        if(bitmap==null){copy.addView(text("사진 미리보기 없음",14,MUTED,false));gap(copy,8);}
        copy.addView(text(photo.sourceName+" · "+photo.license,14,MUTED,false));preview.addView(copy,size(-1,-2));
        if(bitmap!=null)preview.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            int threshold=Math.round(dp(480)*Math.max(1f,activity.getResources().getConfiguration().fontScale));boolean horizontal=right-left>=threshold;
            int orientation=horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL;if(preview.getOrientation()==orientation)return;
            preview.setOrientation(orientation);preview.setGravity(Gravity.CENTER_VERTICAL);image.setLayoutParams(horizontal?size(138,104):size(-1,180));
            gutter.setLayoutParams(horizontal?size(16,1):size(1,16));copy.setLayoutParams(horizontal?new LinearLayout.LayoutParams(0,-2,1):size(-1,-2));
        });return preview;
    }

    private void choose(ScenicPhotoCollection.Photo photo){
        if(!allowed()||loading)return;
        if(!displayedImages.contains(photo.imageUrl)){
            dialog=new AlertDialog.Builder(activity).setTitle("사진을 아직 확인하지 못했어요")
                    .setMessage(photo.place.name+(regionMode?"의 관광 장소를 목적지로 선택할 수 있어요.":"의 장소 좌표를 경유지로 선택할 수 있어요.")+" 실제 풍경은 사진으로 확인하지 못한 상태입니다.")
                    .setPositiveButton("장소 기준으로 선택",(d,w)->{if(allowed())host.onPhotoSelected(photo);})
                    .setNegativeButton("취소",null).show();
        }else host.onPhotoSelected(photo);
    }

    private void showRankingInfo(){
        if(!allowed()||collection==null)return;
        dialog=new AlertDialog.Builder(activity).setTitle("풍경 추천 기준")
                .setMessage(collection.rankingMessage+(collection.message.isEmpty()?"":"\n\n"+collection.message))
                .setPositiveButton("확인",null).show();
    }
    private void showSource(ScenicPhotoCollection.Photo photo){
        if(!allowed())return;
        dialog=new AlertDialog.Builder(activity).setTitle(photo.place.name+" · 사진 정보")
                .setMessage((photo.place.address.isEmpty()?"":photo.place.address+"\n\n")+photo.reason+(photo.visualScore==null?"":"\n사진 분석 "+photo.visualScore.intValue()+"점")+"\n\n"+photo.sourceName+"\n"+photo.license+"\n\n"+photo.sourceUrl+"\n\n사진은 관광 장소를 보여 줍니다. 촬영 지점·방향·시기 및 주행 중 조망은 확인되지 않았어요.")
                .setPositiveButton("원본 사진 열기",(d,w)->openExternal(photo.imageUrl))
                .setNeutralButton("출처 열기",(d,w)->openExternal(photo.sourceUrl)).setNegativeButton("닫기",null).show();
    }
    private void openExternal(String url){if(!allowed())return;try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception unavailable){Toast.makeText(activity,"링크를 열 수 있는 앱이 없어요.",Toast.LENGTH_SHORT).show();}}

    private static final class ImageTarget {final ImageView image;final TextView state;ImageTarget(ImageView image,TextView state){this.image=image;this.state=state;}}
    private void requestImage(String url,String thumbnail,ImageTarget target){
        Bitmap existing=cache.get(url);if(existing!=null){target.image.setImageBitmap(existing);target.state.setVisibility(View.GONE);displayedImages.add(url);return;}
        if(!allowed()){target.state.setText("정차 후 사진을 다시 불러와 주세요.");return;}
        if(failedImages.contains(url)){target.state.setText("사진을 불러오지 못했어요.\n장소 정보는 확인할 수 있어요.");return;}
        List<ImageTarget> targets=waitingImages.get(url);if(targets!=null){targets.add(target);return;}
        targets=new ArrayList<>();targets.add(target);waitingImages.put(url,targets);final int token=generation;
        imageJobs.add(images.submit(()->{Bitmap loaded=null;try{loaded=downloadImage(url);}catch(Exception ignored){}
            // Large in-car cards use the original image, decoded to a bounded size.
            // A same-photo thumbnail remains useful when the original exceeds the transfer limit.
            if(loaded==null&&!Thread.currentThread().isInterrupted()&&!url.equals(thumbnail))try{loaded=downloadImage(thumbnail);}catch(Exception ignored){}
            final Bitmap result=loaded;
            main.post(()->{if(!current(token)){if(result!=null)result.recycle();return;}List<ImageTarget> waiting=waitingImages.remove(url);
                if(result!=null)cache.put(url,result);else failedImages.add(url);
                if(waiting!=null)for(ImageTarget item:waiting){if(result!=null){item.image.setImageBitmap(result);item.state.setVisibility(View.GONE);displayedImages.add(url);}else item.state.setText("사진을 불러오지 못했어요.\n장소 정보는 확인할 수 있어요.");}
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
    private void updateSafety(){boolean safe=allowed();for(View control:controls){control.setEnabled(safe&&(!loading||control==skipButton));control.setAlpha(control.isEnabled()?1:.45f);}if(safety!=null){String message=safe?"":"주행 중에는 사진을 고를 수 없어요. 정차 후 계속해 주세요.";if(!message.contentEquals(safety.getText()))safety.setText(message);safety.setVisibility(safe?View.GONE:View.VISIBLE);}}
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
    private GradientDrawable background(int color,int stroke){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(ScenicDesign.CARD_RADIUS));if(stroke!=0)value.setStroke(dp(1),stroke);return value;}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextSize(ScenicTypography.size(size));view.setTextColor(color);view.setTypeface(ScenicTypography.font(activity,bold?600:400));view.setIncludeFontPadding(false);view.setLineSpacing(dp(3),1);view.setAccessibilityHeading(bold&&size>=22);return view;}
    private TextView quietButton(String label,Runnable action){TextView view=button(label,false,action);view.setTextColor(ACCENT);GradientDrawable surface=background(ScenicDesign.SURFACE,0);surface.setCornerRadius(dp(ScenicDesign.CONTROL_RADIUS));view.setBackground(new RippleDrawable(ColorStateList.valueOf(ScenicDesign.RIPPLE),surface,null));return view;}
    private TextView button(String label,boolean primary,Runnable action){TextView view=text(label,primary?20:17,primary?ScenicDesign.ON_ACCENT:INK,true);view.setPadding(dp(16),dp(14),dp(16),dp(14));view.setGravity(Gravity.CENTER);view.setMinHeight(dp(primary?64:ScenicDesign.TOUCH_HEIGHT));GradientDrawable surface=background(primary?ACCENT:ScenicDesign.SURFACE,primary?0:LINE);surface.setCornerRadius(dp(ScenicDesign.CONTROL_RADIUS));view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x28ffffff:ScenicDesign.RIPPLE),surface,null));view.setFocusable(true);view.setContentDescription(label);view.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});view.setOnClickListener(v->{if(allowed()&&!loading)action.run();});controls.add(view);return view;}
}
