package com.scenic.pleos;

import java.time.Instant;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict server response boundary: only an exact canonical playlist URL can be opened. */
public final class MusicPlaylist {
    public final String id,title,creator,url,reason,model,generatedAt;
    private MusicPlaylist(String id,String title,String creator,String url,String reason,String model,String generatedAt){
        this.id=id;this.title=title;this.creator=creator;this.url=url;this.reason=reason;this.model=model;this.generatedAt=generatedAt;
    }
    public static MusicPlaylist parse(JSONObject json)throws JSONException{
        if(json==null||!"OpenAI".equals(text(json,"source",20)))throw invalid();
        String model=text(json,"model",200),created=text(json,"generatedAt",40),reason=text(json,"reason",280);
        try{Instant.parse(created);}catch(RuntimeException e){throw invalid();}
        JSONObject item=json.getJSONObject("playlist");
        String id=text(item,"id",102),title=text(item,"title",200),creator=text(item,"creator",120),url=text(item,"url",160);
        if(!id.matches("PL[A-Za-z0-9_-]{16,100}")||!url.equals("https://music.youtube.com/playlist?list="+id))throw invalid();
        return new MusicPlaylist(id,title,creator,url,reason,model,created);
    }
    private static String text(JSONObject json,String key,int max)throws JSONException{
        Object raw=json.get(key);if(!(raw instanceof String))throw invalid();String value=(String)raw;
        if(value.isEmpty()||!value.equals(value.trim())||value.length()>max||MusicContext.hasControl(value))throw invalid();return value;
    }
    private static JSONException invalid(){return new JSONException("Invalid music recommendation response");}
}
