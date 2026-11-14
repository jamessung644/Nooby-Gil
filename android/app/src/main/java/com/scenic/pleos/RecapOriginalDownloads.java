package com.scenic.pleos;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies an explicitly requested original to the gallery without changing its source. */
public final class RecapOriginalDownloads {
    public interface Callback { void onFinished(boolean success,String message); }
    private static final long MAX_BYTES=512L*1024*1024;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean saving=new AtomicBoolean();
    private volatile boolean destroyed;

    public RecapOriginalDownloads(Activity activity){
        if(activity==null)throw new IllegalArgumentException("Activity is required");
        Context context=activity.getApplicationContext();
        resolver=context.getContentResolver();
        prefs=context.getSharedPreferences("scenic_original_downloads",Context.MODE_PRIVATE);
    }

    public boolean saving(){return saving.get();}

    public void save(PersonalVideoStore.Record record,Callback callback){
        if(destroyed)return;
        if(record==null||record.id==null||!record.id.matches("[a-f0-9]{64}")
                ||record.uri==null||!record.uri.startsWith("content://")){
            finish(callback,false,"원본 영상을 찾을 수 없어요.");return;
        }
        if(Build.VERSION.SDK_INT<29){finish(callback,false,"이 기기에서는 원본 영상의 앨범 저장을 지원하지 않아요.");return;}
        if(!saving.compareAndSet(false,true)){finish(callback,false,"원본 영상을 저장하고 있어요.");return;}
        try{worker.execute(()->copy(record,callback));}
        catch(RejectedExecutionException stopped){saving.set(false);finish(callback,false,"원본 저장을 다시 시도해 주세요.");}
    }

    public void destroy(){destroyed=true;worker.shutdownNow();saving.set(false);}

    private void copy(PersonalVideoStore.Record record,Callback callback){
        Uri target=null;boolean published=false,success=false;String message=null;
        try{
            check();String saved=prefs.getString(record.id,"");
            if(saved!=null&&!saved.isEmpty()&&exists(Uri.parse(saved))){success=true;message="이미 앨범에 저장한 원본이에요.";return;}
            if(saved!=null&&!saved.isEmpty())prefs.edit().remove(record.id).apply();
            Uri source=Uri.parse(record.uri);String filename=sourceName(source,record.title);
            String mime=resolver.getType(source);if(mime==null||!mime.startsWith("video/"))mime=videoMime(filename);
            if(filename.lastIndexOf('.')<1)filename+=mime.equals("video/webm")?".webm":mime.equals("video/quicktime")?".mov":mime.equals("video/x-matroska")?".mkv":".mp4";
            ContentValues values=new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,filename);
            values.put(MediaStore.Video.Media.MIME_TYPE,mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/누비길/원본");
            values.put(MediaStore.Video.Media.IS_PENDING,1);
            if(!"import".equals(record.dateSource)&&record.createdAt>0)values.put(MediaStore.Video.Media.DATE_TAKEN,record.createdAt);
            // Open the source before creating a gallery row, so a lost source grant leaves no row.
            try(InputStream input=resolver.openInputStream(source)){
                if(input==null)throw new IOException("Source unavailable");check();
                target=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
                if(target==null)throw new IOException("Gallery unavailable");
                long total=0;
                try(OutputStream output=resolver.openOutputStream(target)){
                    if(output==null)throw new IOException("Gallery output unavailable");
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1){check();total+=count;if(total>MAX_BYTES)throw new IOException("Video exceeds supported size");output.write(buffer,0,count);}
                    if(total==0)throw new IOException("Empty source");
                }
            }
            check();values.clear();values.put(MediaStore.Video.Media.IS_PENDING,0);
            if(resolver.update(target,values,null,null)!=1)throw new IOException("Gallery publish failed");
            published=true;prefs.edit().putString(record.id,target.toString()).apply();
            success=true;message="앨범의 누비길/원본 폴더에 저장했어요.";
        }catch(Exception error){
            if(!published&&target!=null)try{resolver.delete(target,null,null);}catch(RuntimeException ignored){}
            if(!(error instanceof CancellationException)&&!destroyed)message="원본을 저장하지 못했어요. 영상 연결을 확인해 주세요.";
        }finally{saving.set(false);if(message!=null)finish(callback,success,message);}
    }

    private boolean exists(Uri uri){
        if(!"content".equals(uri.getScheme())||!MediaStore.AUTHORITY.equals(uri.getAuthority()))return false;
        try(Cursor cursor=resolver.query(uri,new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.IS_PENDING},null,null,null)){
            return cursor!=null&&cursor.moveToFirst()&&cursor.getLong(1)>0&&cursor.getInt(2)==0;
        }catch(RuntimeException unavailable){return false;}
    }
    private String sourceName(Uri source,String fallback){
        String name=fallback;
        try(Cursor cursor=resolver.query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
        }catch(RuntimeException ignored){}
        name=name==null?"":name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim().replaceAll("^\\.+","");
        if(name.isEmpty())name="여행 원본";
        if(name.length()>140){int dot=name.lastIndexOf('.');String suffix=dot>0&&name.length()-dot<=8?name.substring(dot):"";name=name.substring(0,140-suffix.length())+suffix;}
        return name;
    }
    private static String videoMime(String name){String lower=name.toLowerCase(Locale.ROOT);return lower.endsWith(".webm")?"video/webm":lower.endsWith(".mov")?"video/quicktime":lower.endsWith(".mkv")?"video/x-matroska":"video/mp4";}
    private void check(){if(destroyed||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private void finish(Callback callback,boolean success,String message){main.post(()->{if(!destroyed&&callback!=null)callback.onFinished(success,message);});}
}
