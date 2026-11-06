package com.scenic.pleos;

import org.json.JSONException;
import org.json.JSONObject;

/** Music service response and coarse request-context boundaries. No network or Android UI. */
public final class MusicPlaylistTest {
    private static int checks;
    private interface Change { void apply(JSONObject value) throws Exception; }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static String repeat(char value,int count){return new String(new char[count]).replace('\0',value);}
    static JSONObject response()throws Exception{
        String id="PL_0123456789abc-DEFG";
        return new JSONObject().put("source","OpenAI").put("model","gpt-test")
                .put("generatedAt","2026-11-05T00:15:00.123Z").put("reason","호숫길의 차분한 풍경과 잘 어울려요.")
                .put("playlist",new JSONObject().put("id",id).put("title","잔잔한 드라이브 음악")
                        .put("creator","공식 음악 채널").put("url","https://music.youtube.com/playlist?list="+id));
    }
    private static void reject(Change change,String label)throws Exception{
        JSONObject json=response();change.apply(json);
        try{MusicPlaylist.parse(json);throw new AssertionError(label);}catch(JSONException expected){checks++;}
    }
    private static void rejectContext(String title,String theme,int duration,String label){
        try{new MusicContext(title,theme,duration);throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        JSONObject json=response();MusicPlaylist playlist=MusicPlaylist.parse(json);
        check(playlist.id.equals(json.getJSONObject("playlist").getString("id")),"Preserve selected playlist ID");
        check(playlist.title.equals("잔잔한 드라이브 음악")&&playlist.creator.equals("공식 음악 채널"),"Preserve playlist metadata");
        check(playlist.reason.equals(json.getString("reason"))&&playlist.model.equals("gpt-test"),"Preserve AI rationale and model provenance");
        check(playlist.generatedAt.equals("2026-11-05T00:15:00.123Z"),"Preserve valid generated timestamp");
        check(playlist.url.equals("https://music.youtube.com/playlist?list="+playlist.id),"Only canonical playlist destination");
        try{MusicPlaylist.parse(null);throw new AssertionError("null response");}catch(JSONException expected){checks++;}
        for(String source:new String[]{"OpenAI ","offline","local","", "openai"})reject(j->j.put("source",source),"Reject unsupported provenance");
        reject(j->j.put("playlist","not an object"),"Reject non-object playlist");
        reject(j->j.remove("playlist"),"Require playlist");
        for(String field:new String[]{"source","model","generatedAt","reason"}){
            reject(j->j.remove(field),"Require response text "+field);
            reject(j->j.put(field,JSONObject.NULL),"Reject null response text "+field);
            reject(j->j.put(field,123),"Reject coerced response text "+field);
            reject(j->j.put(field,"line\nline"),"Reject response control character "+field);
            reject(j->j.put(field," value "),"Reject padded response text "+field);
            reject(j->j.put(field,""),"Reject empty response text "+field);
        }
        for(String field:new String[]{"id","title","creator","url"}){
            reject(j->j.getJSONObject("playlist").remove(field),"Require playlist text "+field);
            reject(j->j.getJSONObject("playlist").put(field,JSONObject.NULL),"Reject null playlist text "+field);
            reject(j->j.getJSONObject("playlist").put(field,false),"Reject coerced playlist text "+field);
            reject(j->j.getJSONObject("playlist").put(field,"value\u0000"),"Reject playlist control character "+field);
            reject(j->j.getJSONObject("playlist").put(field," value "),"Reject padded playlist text "+field);
            reject(j->j.getJSONObject("playlist").put(field,""),"Reject empty playlist text "+field);
        }
        for(String timestamp:new String[]{"yesterday","2026-11-05","2026-13-05T00:00:00Z","2026-11-05T00:00:00","2026-11-05T25:00:00Z"})
            reject(j->j.put("generatedAt",timestamp),"Reject invalid instant");
        reject(j->j.put("model",repeat('m',201)),"Bound model text");
        reject(j->j.put("reason",repeat('r',281)),"Bound rationale text");
        reject(j->j.getJSONObject("playlist").put("title",repeat('t',201)),"Bound playlist title");
        reject(j->j.getJSONObject("playlist").put("creator",repeat('c',121)),"Bound creator text");
        for(String id:new String[]{"RD0123456789abcdef","PL0123456789abcde","PL"+repeat('a',101),
                "PL0123456789abcdef&x=1","PL0123456789abcdef/evil","PL0123456789abcdef%2F","PL0123456789abcdef#part","PL0123456789abcdef한"}){
            reject(j->{j.getJSONObject("playlist").put("id",id).put("url","https://music.youtube.com/playlist?list="+id);},"Reject malformed playlist ID");
        }
        String url=playlist.url;
        for(String invalid:new String[]{url+"&autoplay=1",url+"#part",url.replace("https://","http://"),
                url.replace("music.youtube.com","music.youtube.com.evil.example"),url.replace("music.youtube.com","evil@music.youtube.com"),
                url.replace("playlist?","search?"),url.replace("music.youtube.com","music.youtube.com:443"),
                url.replace("list=PL","list=%50%4C"),"https://music.youtube.com/",url+"&list=PL0123456789abcdef",
                "javascript:alert(1)","intent://playlist#Intent;end","https://music.youtube.com/playlist?list=PL0123456789abcdef"})
            reject(j->j.getJSONObject("playlist").put("url",invalid),"Reject changed or injectable URL");
        for(int suffixLength:new int[]{16,100}){
            JSONObject boundary=response();String id="PL"+repeat('a',suffixLength);
            boundary.getJSONObject("playlist").put("id",id).put("url","https://music.youtube.com/playlist?list="+id);
            check(MusicPlaylist.parse(boundary).id.equals(id),"Accept playlist ID length boundary");
        }
        JSONObject literal=response().put("reason","풍경 <b>그대로</b> & text");
        literal.getJSONObject("playlist").put("title","<script>alert(1)</script>");
        check(MusicPlaylist.parse(literal).title.equals("<script>alert(1)</script>"),"Metadata remains literal text for native TextView");
        check(MusicPlaylist.parse(literal).reason.equals("풍경 <b>그대로</b> & text"),"Rationale remains literal text");
        JSONObject textLimits=response().put("model",repeat('m',200)).put("reason",repeat('r',280));
        textLimits.getJSONObject("playlist").put("title",repeat('t',200)).put("creator",repeat('c',120));
        check(MusicPlaylist.parse(textLimits).title.length()==200,"Accept maximum text lengths");

        for(String theme:new String[]{"all","lake","coast","forest","mountain","water","countryside","city"}){
            MusicContext context=new MusicContext("일반 드라이브",theme,90);
            check(context.theme.equals(theme)&&context.durationMinutes==90,"Preserve supported coarse context");
        }
        check(new MusicContext("해안","sea",60).theme.equals("coast"),"Map prepared sea theme");
        check(new MusicContext("도시","night",60).theme.equals("city"),"Map prepared night theme");
        check(new MusicContext("  호숫길  ","lake",1).title.equals("호숫길"),"Normalize title whitespace");
        check(new MusicContext(repeat('t',120),"all",720).title.length()==120,"Accept context boundary values");
        check(new MusicContext("호숫길","sea",90).key().equals(new MusicContext(" 호숫길 ","coast",90).key()),"Equivalent normalized contexts share cache key");
        check(!new MusicContext("길","lake",90).key().equals(new MusicContext("길","lake",91).key()),"Duration changes cache key");
        check(!new MusicContext("길","lake",90).key().equals(new MusicContext("길","city",90).key()),"Theme changes cache key");
        check(!new MusicContext("길","lake",90).key().equals(new MusicContext("다른 길","lake",90).key()),"Title changes cache key");
        for(String title:new String[]{null,""," \t ",repeat('a',121),"line\nline","x\u0000y","x\u007fy"})
            rejectContext(title,"all",90,"Reject invalid coarse title");
        for(String theme:new String[]{null,"","Lake"," lake ","jazz","forest&lat=37.5"})
            rejectContext("길",theme,90,"Reject unsupported theme");
        for(int duration:new int[]{Integer.MIN_VALUE,-1,0,721,Integer.MAX_VALUE})
            rejectContext("길","all",duration,"Reject out-of-range music duration");
        System.out.println("MusicPlaylistTest: "+checks+" checks passed");
    }
}
