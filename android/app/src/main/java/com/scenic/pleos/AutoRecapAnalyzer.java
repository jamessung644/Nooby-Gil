package com.scenic.pleos;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local visual-quality selection. Call analyze on a worker; originals are read only. */
public final class AutoRecapAnalyzer {
    private static final int VERSION=1,MAX_SAMPLES=24,MAX_SCENES=3;
    private static final long CLIP_MS=4000;
    private static final String PREFS="scenic_auto_recap",DIRECTORY="recap-moments";

    public static final class Scene {
        public final long timestampMs,startMs,endMs;
        public final double quality;
        public final String photoPath;
        private Scene(long timestampMs,long startMs,long endMs,double quality,String photoPath){
            this.timestampMs=timestampMs;this.startMs=startMs;this.endMs=endMs;
            this.quality=quality;this.photoPath=photoPath;
        }
    }

    public static final class Result {
        public final String videoId;
        public final List<Scene> scenes;
        private Result(String videoId,List<Scene> scenes){
            this.videoId=videoId;this.scenes=Collections.unmodifiableList(new ArrayList<>(scenes));
        }
    }

    private AutoRecapAnalyzer(){}

    /** An empty cached result means no suitable scene; null means analysis is still needed. */
    public static Result load(Context context,PersonalVideoStore.Record record){
        if(!valid(record)||context==null)return null;
        String value=preferences(context).getString(record.id,"");
        if(value.isEmpty()||value.length()>16384)return null;
        try{
            JSONObject object=new JSONObject(value);
            if(object.getInt("version")!=VERSION||!record.id.equals(object.getString("videoId"))
                    ||object.getLong("durationMs")!=record.durationMs)return null;
            JSONArray rows=object.getJSONArray("scenes");
            if(rows.length()>MAX_SCENES)return null;
            File directory=new File(context.getFilesDir(),DIRECTORY);
            ArrayList<Scene> scenes=new ArrayList<>();Set<Long> times=new HashSet<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                long time=row.getLong("timestampMs"),start=row.getLong("startMs"),end=row.getLong("endMs");
                double quality=row.getDouble("quality");
                if(time<0||time>=record.durationMs||start<0||end>record.durationMs||end-start<1000
                        ||end-start>CLIP_MS||time<start||time>end||!Double.isFinite(quality)
                        ||quality<0||quality>1||!times.add(time))return null;
                String filename=filename(record.id,time);
                if(!filename.equals(row.getString("file")))return null;
                File photo=inside(directory,filename);
                if(!photo.isFile()||photo.length()==0)return null;
                scenes.add(new Scene(time,start,end,quality,photo.getAbsolutePath()));
            }
            return new Result(record.id,scenes);
        }catch(Exception malformed){return null;}
    }

    /** Samples at most 24 frames, retaining up to three well-exposed, sharp, different moments. */
    public static Result analyze(Context context,PersonalVideoStore.Record record,
            RecapVideoExporter.CancelCheck cancel) throws Exception {
        if(context==null||!valid(record))throw new IllegalArgumentException("유효한 여행 영상이 필요해요.");
        check(cancel);Result cached=load(context,record);if(cached!=null)return cached;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(context,Uri.parse(record.uri));
            long actual=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long duration=Math.min(record.durationMs,actual);
            if(duration<1000)throw new IOException("영상 장면을 읽을 수 없어요.");
            int count=(int)Math.min(MAX_SAMPLES,Math.max(1,(duration+1499)/1500));
            ArrayList<ScenicFrameSelector.Frame> frames=new ArrayList<>();
            for(int i=0;i<count;i++){
                check(cancel);long time=Math.min(duration-1,(long)((i+.5)*duration/count));
                Bitmap bitmap=null;
                try{
                    bitmap=retriever.getScaledFrameAtTime(time*1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,256,256);
                    if(bitmap==null)continue;
                    int width=bitmap.getWidth(),height=bitmap.getHeight();
                    int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
                    frames.add(ScenicFrameSelector.analyze(pixels,width,height,time));
                }finally{if(bitmap!=null)bitmap.recycle();}
            }
            check(cancel);
            if(frames.isEmpty())throw new IOException("영상 장면을 읽을 수 없어요.");
            List<ScenicFrameSelector.Frame> selected=ScenicFrameSelector.select(frames,MAX_SCENES,3000);
            File directory=new File(context.getFilesDir(),DIRECTORY);
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("추억 사진을 저장할 수 없어요.");
            ArrayList<Scene> scenes=new ArrayList<>();
            for(ScenicFrameSelector.Frame frame:selected){
                check(cancel);Bitmap image=null;
                File output=inside(directory,filename(record.id,frame.timestampMs));
                File temporary=inside(directory,filename(record.id,frame.timestampMs)+".part");
                try{
                    image=retriever.getScaledFrameAtTime(frame.timestampMs*1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,720,720);
                    if(image==null)throw new IOException("선별한 장면을 저장할 수 없어요.");
                    try(FileOutputStream stream=new FileOutputStream(temporary)){
                        if(!image.compress(Bitmap.CompressFormat.JPEG,90,stream))
                            throw new IOException("추억 사진을 저장할 수 없어요.");
                    }
                    check(cancel);
                    if(!temporary.renameTo(output)){
                        if(output.exists()&&!output.delete())throw new IOException("추억 사진을 저장할 수 없어요.");
                        if(!temporary.renameTo(output))throw new IOException("추억 사진을 저장할 수 없어요.");
                    }
                    long clip=Math.min(CLIP_MS,duration);
                    long start=Math.max(0,Math.min(frame.timestampMs-clip/2,duration-clip));
                    scenes.add(new Scene(frame.timestampMs,start,start+clip,frame.quality,output.getAbsolutePath()));
                }finally{if(image!=null)image.recycle();temporary.delete();}
            }
            check(cancel);Result result=new Result(record.id,scenes);
            JSONObject object=new JSONObject();object.put("version",VERSION);object.put("videoId",record.id);
            object.put("durationMs",record.durationMs);JSONArray rows=new JSONArray();
            for(Scene scene:scenes){
                JSONObject row=new JSONObject();row.put("timestampMs",scene.timestampMs);
                row.put("startMs",scene.startMs);row.put("endMs",scene.endMs);row.put("quality",scene.quality);
                row.put("file",filename(record.id,scene.timestampMs));rows.put(row);
            }
            object.put("scenes",rows);check(cancel);
            if(!preferences(context).edit().putString(record.id,object.toString()).commit())
                throw new IOException("추억 정보를 저장할 수 없어요.");
            return result;
        }finally{try{retriever.release();}catch(Exception ignored){}}
    }

    private static SharedPreferences preferences(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static boolean valid(PersonalVideoStore.Record record){
        return record!=null&&record.id!=null&&record.id.matches("[a-f0-9]{64}")&&record.uri!=null
            &&record.uri.startsWith("content://")&&record.durationMs>=1000&&record.durationMs<=3600000;
    }
    private static String filename(String id,long time){return id+"-"+time+".jpg";}
    private static File inside(File directory,String name) throws IOException {
        File result=new File(directory,name);
        if(!result.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("Invalid recap photo path");
        return result;
    }
    private static void check(RecapVideoExporter.CancelCheck cancel){
        if(Thread.currentThread().isInterrupted()||(cancel!=null&&cancel.isCancelled()))throw new CancellationException();
    }
}
