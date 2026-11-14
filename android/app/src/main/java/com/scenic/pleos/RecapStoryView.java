package com.scenic.pleos;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-height story playback, kept separate from the scrollable memory library. */
public final class RecapStoryView extends FrameLayout {
    public static final class Photo {
        public final Bitmap bitmap;
        public final String description,credit;
        public Photo(Bitmap bitmap,String description,String credit){this.bitmap=bitmap;this.description=description==null?"":description;this.credit=credit==null?"":credit;}
    }
    public interface Host {
        boolean canInteract();
        void onBack();
        void onSaveVideo();
        void onSavePhoto(int photoIndex);
        void onRetryMovie();
    }
    private static final long PHOTO_MS=5000;
    private final Activity activity;
    private final Host host;
    private final boolean wide;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LinearLayout metadata,mediaColumn,segments,saveActions;
    private final FrameLayout mediaArea,portrait;
    private final ImageView image;
    private final TextView titleView,dateView,labelView,stateView,photoTitleView,emptyView,videoSave,photoSave,retry,back;
    private final View previousTouch,nextTouch;
    private final ArrayList<ProgressBar> progressBars=new ArrayList<>();
    private List<Photo> photos=Collections.emptyList();
    private File movie;
    private String storyId="",displayedKey="",playbackError="";
    private int index,videoPosition;
    private long photoElapsed,lastTick;
    private boolean active,destroyed,userNavigated,prepared,saving,generating,failed;
    private VideoView player;

    public RecapStoryView(Activity activity,Host host,boolean wide){
        super(activity);this.activity=activity;this.host=host;this.wide=wide;
        setBackgroundColor(ScenicDesign.BACKGROUND);
        LinearLayout layout=new LinearLayout(activity);layout.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER_VERTICAL);addView(layout,new FrameLayout.LayoutParams(-1,-1));
        metadata=new LinearLayout(activity);metadata.setOrientation(LinearLayout.VERTICAL);metadata.setPadding(dp(wide?40:8),dp(wide?24:4),dp(wide?24:8),dp(wide?24:8));
        back=smallButton("‹ 리캡",()->{if(allowed())host.onBack();});back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);metadata.addView(back,new LinearLayout.LayoutParams(-2,dp(48)));
        labelView=text("",18,ScenicDesign.ACCENT,true);titleView=text("",wide?32:23,ScenicDesign.INK,true);dateView=text("",18,ScenicDesign.MUTED,false);stateView=text("",14,ScenicDesign.MUTED,false);
        titleView.setMaxLines(wide?4:2);titleView.setEllipsize(TextUtils.TruncateAt.END);dateView.setMaxLines(3);
        if(wide){space(metadata,32);metadata.addView(labelView);space(metadata,12);metadata.addView(titleView);space(metadata,16);metadata.addView(dateView);metadata.addView(new View(activity),new LinearLayout.LayoutParams(1,0,1));}
        else{LinearLayout heading=new LinearLayout(activity);heading.setOrientation(LinearLayout.HORIZONTAL);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(titleView,new LinearLayout.LayoutParams(0,-2,1));dateView.setTextSize(ScenicTypography.size(14));dateView.setMaxLines(1);heading.addView(dateView,new LinearLayout.LayoutParams(-2,-2));metadata.addView(heading);labelView.setVisibility(GONE);}
        space(metadata,12);metadata.addView(stateView);retry=smallButton("영상 다시 준비",()->{if(allowed())host.onRetryMovie();});metadata.addView(retry,new LinearLayout.LayoutParams(-2,dp(48)));
        layout.addView(metadata,wide?new LinearLayout.LayoutParams(0,-1,.85f):new LinearLayout.LayoutParams(-1,-2));
        mediaColumn=new LinearLayout(activity);mediaColumn.setOrientation(LinearLayout.VERTICAL);mediaColumn.setPadding(dp(8),dp(4),dp(8),dp(4));
        layout.addView(mediaColumn,wide?new LinearLayout.LayoutParams(0,-1,1.15f):new LinearLayout.LayoutParams(-1,0,1));
        if(wide){layout.removeView(metadata);layout.addView(metadata,new LinearLayout.LayoutParams(0,-1,.85f));}
        mediaArea=new FrameLayout(activity);mediaColumn.addView(mediaArea,new LinearLayout.LayoutParams(-1,0,1));
        portrait=new FrameLayout(activity);portrait.setBackground(background(0xff101820,0,12));portrait.setClipToOutline(true);mediaArea.addView(portrait,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);portrait.addView(image,new FrameLayout.LayoutParams(-1,-1));
        emptyView=text("",18,ScenicDesign.ON_HERO,false);emptyView.setGravity(Gravity.CENTER);portrait.addView(emptyView,new FrameLayout.LayoutParams(-1,-1));
        segments=new LinearLayout(activity);segments.setOrientation(LinearLayout.HORIZONTAL);segments.setPadding(dp(10),dp(10),dp(10),0);FrameLayout.LayoutParams segmentParams=new FrameLayout.LayoutParams(-1,dp(18),Gravity.TOP);portrait.addView(segments,segmentParams);
        photoTitleView=text("",23,ScenicDesign.ON_HERO,true);photoTitleView.setMaxLines(2);photoTitleView.setEllipsize(TextUtils.TruncateAt.END);photoTitleView.setPadding(dp(20),dp(42),dp(20),dp(24));
        GradientDrawable titleShade=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00101820,0xe6101820});photoTitleView.setBackground(titleShade);FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);portrait.addView(photoTitleView,titleParams);
        previousTouch=touchZone("이전 장면",()->advance(-1,true));nextTouch=touchZone("다음 장면",()->advance(1,true));mediaArea.addView(previousTouch,new FrameLayout.LayoutParams(1,-1,Gravity.START));mediaArea.addView(nextTouch,new FrameLayout.LayoutParams(1,-1,Gravity.END));
        saveActions=new LinearLayout(activity);saveActions.setOrientation(LinearLayout.HORIZONTAL);saveActions.setGravity(Gravity.CENTER);videoSave=smallButton("영상 저장",()->{if(allowed()&&!saving)host.onSaveVideo();});photoSave=smallButton("사진 저장",()->{int selected=photoIndex();if(allowed()&&!saving&&selected>=0)host.onSavePhoto(selected);});saveActions.addView(videoSave,new LinearLayout.LayoutParams(-2,dp(48)));LinearLayout.LayoutParams savePhotoParams=new LinearLayout.LayoutParams(-2,dp(48));savePhotoParams.setMargins(dp(8),0,0,0);saveActions.addView(photoSave,savePhotoParams);mediaColumn.addView(saveActions,new LinearLayout.LayoutParams(-1,dp(48)));
        mediaArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)fitPortrait();});
        refreshControls();
    }

    public void bind(String storyId,String title,String dateLabel,String memoryLabel,List<Photo> photos,File movie,int generationProgress,boolean generating,boolean failed,boolean saving,String status){
        if(destroyed)return;
        String nextId=storyId==null?"":storyId;boolean newStory=!this.storyId.equals(nextId),hadMovie=this.movie!=null;
        int previousPhoto=photoIndex();String oldKey=itemKey();
        this.storyId=nextId;this.movie=movie!=null&&movie.isFile()?movie:null;this.photos=photos==null?Collections.emptyList():new ArrayList<>(photos);this.generating=generating;this.failed=failed;this.saving=saving;
        if(newStory){userNavigated=false;index=0;photoElapsed=0;videoPosition=0;playbackError="";displayedKey="";stopVideo(false);}
        else if(!hadMovie&&this.movie!=null){index=userNavigated&&previousPhoto>=0?previousPhoto+1:0;}
        else if(hadMovie&&this.movie==null){index=Math.max(0,previousPhoto);}
        index=Math.max(0,Math.min(index,Math.max(0,itemCount()-1)));
        titleView.setText(title);dateView.setText(dateLabel);labelView.setText(memoryLabel);
        photoTitleView.setText(title);
        String state=status==null?"":status;if(state.isEmpty()&&generating)state="영상 준비 중 · "+generationProgress+"%";stateView.setText(state);stateView.setVisibility(state.isEmpty()?GONE:VISIBLE);
        retry.setVisibility(failed&&this.movie==null?VISIBLE:GONE);
        rebuildSegments();boolean changed=newStory||!oldKey.equals(itemKey());showCurrent(changed);refreshControls();
    }
    public void resume(){if(destroyed)return;boolean wasActive=active;active=true;lastTick=SystemClock.uptimeMillis();handler.removeCallbacks(tick);showCurrent(!wasActive);handler.post(tick);}
    public void pause(){active=false;handler.removeCallbacks(tick);stopVideo(true);}
    public void destroy(){if(destroyed)return;pause();destroyed=true;photos=Collections.emptyList();image.setImageDrawable(null);removeAllViews();}

    private void fitPortrait(){int width=mediaArea.getWidth(),height=mediaArea.getHeight();if(width<=0||height<=0)return;int filmWidth=Math.min(width,Math.round(height*9f/16f));int filmHeight=Math.min(height,Math.round(filmWidth*16f/9f));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(filmWidth,filmHeight,Gravity.CENTER);portrait.setLayoutParams(p);previousTouch.setLayoutParams(new FrameLayout.LayoutParams(Math.max(1,width/2),-1,Gravity.START));nextTouch.setLayoutParams(new FrameLayout.LayoutParams(Math.max(1,width-width/2),-1,Gravity.END));fitVideo();}
    private void fitVideo(){if(player==null||portrait.getWidth()<=0||portrait.getHeight()<=0)return;player.setLayoutParams(new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));}
    private int itemCount(){return photos.size()+(movie==null?0:1);}
    private int photoIndex(){return movie!=null?index-1:index;}
    private String itemKey(){if(itemCount()==0)return "empty";if(movie!=null&&index==0)return "movie:"+movie.getAbsolutePath();int p=photoIndex();if(p<0||p>=photos.size())return "empty";Photo photo=photos.get(p);return "photo:"+System.identityHashCode(photo.bitmap)+":"+photo.description;}
    private void showCurrent(boolean restart){
        if(destroyed)return;String key=itemKey();boolean changed=!displayedKey.equals(key);if(!changed&&!restart){refreshControls();updateProgress();return;}
        if(changed){videoPosition=0;photoElapsed=0;playbackError="";}stopVideo(false);displayedKey=key;
        boolean movieItem=movie!=null&&index==0;int p=photoIndex();Photo photo=p>=0&&p<photos.size()?photos.get(p):null;
        image.setVisibility(movieItem?GONE:VISIBLE);image.setImageBitmap(photo==null?null:photo.bitmap);image.setContentDescription(photo==null?"여행 리캡":photo.description);
        photoTitleView.setVisibility(movieItem||photo==null?GONE:VISIBLE);
        emptyView.setText(itemCount()==0?(generating?"장면을 준비하고 있어요":"저장된 장면이 없어요"):"");emptyView.setVisibility(itemCount()==0?VISIBLE:GONE);
        if(movieItem&&active&&host.canInteract())startVideo();
        lastTick=SystemClock.uptimeMillis();refreshControls();updateProgress();
    }
    private void startVideo(){
        if(!allowed()||movie==null||index!=0||player!=null||!playbackError.isEmpty())return;
        VideoView next=new VideoView(activity);player=next;prepared=false;portrait.addView(next,1,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        next.setContentDescription("자동 재생 중인 여행 리캡");next.setOnPreparedListener(mp->{if(player!=next||!allowed()){if(player==next)stopVideo(true);return;}prepared=true;mp.setLooping(false);if(videoPosition>0)next.seekTo(videoPosition);next.start();fitVideo();});
        next.setOnCompletionListener(mp->{if(player==next&&allowed())advance(1,false);});
        next.setOnErrorListener((mp,a,b)->{if(player==next){stopVideo(false);playbackError="영상을 재생할 수 없어요";emptyView.setText(playbackError);emptyView.setVisibility(VISIBLE);}return true;});
        next.setVideoURI(Uri.fromFile(movie));
    }
    private void stopVideo(boolean remember){VideoView old=player;player=null;prepared=false;if(old==null)return;if(remember)try{videoPosition=Math.max(0,old.getCurrentPosition());}catch(RuntimeException ignored){}try{old.stopPlayback();}catch(RuntimeException ignored){}portrait.removeView(old);}
    private void advance(int direction,boolean user){if(!allowed()||itemCount()==0)return;if(user)userNavigated=true;index=(index+direction+itemCount())%itemCount();videoPosition=0;photoElapsed=0;showCurrent(true);}
    private final Runnable tick=new Runnable(){public void run(){if(!active||destroyed)return;long now=SystemClock.uptimeMillis(),delta=Math.min(500,Math.max(0,now-lastTick));lastTick=now;if(!host.canInteract()){stopVideo(true);refreshControls();handler.postDelayed(this,100);return;}if(movie!=null&&index==0){if(player==null&&!playbackError.isEmpty()){}else if(player==null)startVideo();}else if(itemCount()>0){photoElapsed+=delta;if(photoElapsed>=PHOTO_MS)advance(1,false);}updateProgress();refreshControls();handler.postDelayed(this,100);}};
    private void rebuildSegments(){if(progressBars.size()==itemCount())return;segments.removeAllViews();progressBars.clear();for(int i=0;i<itemCount();i++){ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgressTintList(ColorStateList.valueOf(0xffffffff));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x60ffffff));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(3),1);if(i>0)p.setMargins(dp(4),0,0,0);segments.addView(bar,p);progressBars.add(bar);}}
    private void updateProgress(){double current=photoElapsed/(double)PHOTO_MS;if(movie!=null&&index==0){current=0;if(player!=null&&prepared)try{int duration=player.getDuration();if(duration>0)current=player.getCurrentPosition()/(double)duration;}catch(RuntimeException ignored){}}for(int i=0;i<progressBars.size();i++)progressBars.get(i).setProgress(i<index?1000:i>index?0:(int)Math.round(Math.max(0,Math.min(1,current))*1000));}
    private void refreshControls(){boolean enabled=allowed();back.setEnabled(enabled);retry.setEnabled(enabled&&!saving);previousTouch.setEnabled(enabled);nextTouch.setEnabled(enabled);videoSave.setVisibility(movie==null?GONE:VISIBLE);videoSave.setEnabled(enabled&&!saving);int selected=photoIndex();boolean canSavePhoto=selected>=0&&selected<photos.size()&&photos.get(selected).credit.isEmpty();photoSave.setVisibility(canSavePhoto?VISIBLE:GONE);photoSave.setEnabled(enabled&&!saving);videoSave.setAlpha(videoSave.isEnabled()?1:.45f);photoSave.setAlpha(photoSave.isEnabled()?1:.45f);}
    private boolean allowed(){return active&&!destroyed&&host.canInteract();}
    private View touchZone(String label,Runnable action){View touch=new View(activity);touch.setBackgroundColor(0x00000000);touch.setClickable(true);touch.setFocusable(true);touch.setContentDescription(label);touch.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(Button.class.getName());}});touch.setOnClickListener(v->action.run());return touch;}
    private TextView smallButton(String label,Runnable action){TextView button=text(label,14,ScenicDesign.INK,true);button.setGravity(Gravity.CENTER);button.setPadding(dp(12),dp(8),dp(12),dp(8));button.setMinHeight(dp(48));button.setMinWidth(dp(56));button.setBackground(new RippleDrawable(ColorStateList.valueOf(ScenicDesign.RIPPLE),background(ScenicDesign.SURFACE,ScenicDesign.LINE,8),null));button.setClickable(true);button.setFocusable(true);button.setContentDescription(label);button.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(Button.class.getName());}});button.setOnClickListener(v->action.run());return button;}
    private TextView text(String value,int sp,int color,boolean bold){TextView text=new TextView(activity);text.setText(value);text.setTextSize(ScenicTypography.size(sp));text.setTextColor(color);text.setTypeface(ScenicTypography.font(activity,bold?600:400));text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;}
    private GradientDrawable background(int color,int border,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(border!=0)shape.setStroke(dp(1),border);return shape;}
    private void space(LinearLayout parent,int height){parent.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
