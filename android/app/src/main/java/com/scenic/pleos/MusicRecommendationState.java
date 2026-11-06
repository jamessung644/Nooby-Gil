package com.scenic.pleos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread state: one request per context, bounded session cache, no navigation side effects. */
public final class MusicRecommendationState {
    private final Map<String,MusicPlaylist> cache=new LinkedHashMap<String,MusicPlaylist>(12,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,MusicPlaylist> entry){return size()>12;}
    };
    private String contextKey;
    private long sequence,pending;
    private boolean attempted;
    private MusicPlaylist playlist;
    private String error;
    public boolean select(String key){
        if(key.equals(contextKey))return false;
        cancel();contextKey=key;playlist=cache.get(key);error=null;attempted=playlist!=null;return true;
    }
    public long begin(){if(contextKey==null||pending!=0||attempted)return 0;attempted=true;pending=++sequence;error=null;return pending;}
    public boolean complete(long token,String key,MusicPlaylist result,String failure){
        if(token==0||pending!=token||!key.equals(contextKey))return false;
        pending=0;playlist=result;error=failure;
        if(result!=null)cache.put(contextKey,result);
        return true;
    }
    public void cancel(){if(pending!=0)attempted=false;pending=0;sequence++;}
    public void retry(){cancel();if(contextKey!=null)cache.remove(contextKey);playlist=null;error=null;attempted=false;}
    public boolean isPending(){return pending!=0;}
    public MusicPlaylist playlist(){return playlist;}
    public String error(){return error;}
}
