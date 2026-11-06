package com.scenic.pleos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps photo evidence, destination identity, selection tokens and URL policy honest. */
public final class ScenicPhotoCollectionTest {
    private static int checks;
    private static final String TOKEN="eyJ2IjoxfQ.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private interface Change {void apply(JSONObject value)throws Exception;}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static DestinationPlace destination()throws Exception{return DestinationPlace.of("dest","부산역","부산",35.1151,129.0414);}
    private static JSONObject place(String id,String name,double lat,double lon)throws Exception{return new JSONObject().put("id",id).put("name",name).put("address","부산").put("lat",lat).put("lon",lon);}
    private static JSONObject response()throws Exception{
        JSONObject photo=new JSONObject().put("id","tour-photo-12345").put("place",place("tour-12345","바다 전망대",35.13,129.05))
                .put("imageUrl","https://tong.visitkorea.or.kr/cms/resource/11/123_image2_1.jpg")
                .put("thumbnailUrl","https://tong.visitkorea.or.kr/cms/resource/11/123_image3_1.jpg")
                .put("sourceName",ScenicPhotoCollection.SOURCE).put("sourceUrl",ScenicPhotoCollection.SOURCE_URL).put("license",ScenicPhotoCollection.LICENSE)
                .put("distanceMeters",Math.round(CourseEngine.distanceMeters(35.1151,129.0414,35.13,129.05)))
                .put("visualScore",85).put("reason","넓은 수평선과 해안 풍경을 사진에서 확인했어요.").put("scene","coast").put("selectionToken",TOKEN);
        return new JSONObject().put("destination",place("dest","부산역",35.1151,129.0414)).put("radiusMeters",10000)
                .put("source",ScenicPhotoCollection.SOURCE).put("ranking",new JSONObject().put("mode","vision").put("provider","OpenAI").put("message","사진 시각 분석 결과"))
                .put("photos",new JSONArray().put(photo)).put("message","사진의 촬영 방향은 확인되지 않았어요.");
    }
    private static JSONObject photo(JSONObject value)throws Exception{return value.getJSONArray("photos").getJSONObject(0);}
    private static void reject(Change change,String label)throws Exception{
        JSONObject value=response();change.apply(value);try{ScenicPhotoCollection.parse(value,destination(),10000);throw new AssertionError(label);}catch(JSONException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        DestinationPlace destination=destination();ScenicPhotoCollection parsed=ScenicPhotoCollection.parse(response(),destination,10000);
        check(parsed.destination==destination&&parsed.photos.size()==1,"Retain the exact chosen destination object");
        check(parsed.photos.get(0).place.id.equals("tour-12345")&&parsed.photos.get(0).visualScore==85,"Keep actual POI and numeric vision score");
        check(parsed.rankingProvider.equals("OpenAI")&&parsed.photos.get(0).selectionToken.equals(TOKEN),"Provider and opaque token survive parsing");
        String serialized=ScenicPhotoCollection.serializeSelection(parsed.photos.get(0),destination);
        ScenicPhotoCollection.Photo restored=ScenicPhotoCollection.restoreSelection(serialized,destination);
        check(restored!=null&&restored.selectionToken.equals(TOKEN)&&restored.place.id.equals("tour-12345")&&restored.visualScore==85,"Persist exact choice and opaque token across restart");
        check(ScenicPhotoCollection.restoreSelection(serialized,DestinationPlace.of("other",destination.name,"",destination.lat,destination.lon))==null,"Selection cannot be restored for another destination");
        check(ScenicPhotoCollection.restoreSelection(serialized,DestinationPlace.of(destination.id,"다른 이름","",destination.lat,destination.lon))==null,"Stored selection binds destination name");
        check(ScenicPhotoCollection.restoreSelection(serialized,DestinationPlace.of(destination.id,destination.name,"",destination.lat+.001,destination.lon))==null,"Stored selection binds destination coordinates");
        check(ScenicPhotoCollection.restoreSelection(serialized+"garbage",destination)==null&&ScenicPhotoCollection.restoreSelection("{}",destination)==null,"Corrupt saved selection cannot become a different automatic choice");
        check(ScenicPhotoCollection.serializeSelection(null,destination).isEmpty(),"No serialization for absent selection");
        check(ScenicPhotoCollection.serializeSelection(parsed.photos.get(0),DestinationPlace.of("other",destination.name,"",destination.lat,destination.lon)).isEmpty(),"Cannot rebind an in-memory selected photograph to another destination");
        try{parsed.photos.clear();throw new AssertionError("Mutable photos");}catch(UnsupportedOperationException expected){checks++;}
        JSONObject fallback=response();fallback.getJSONObject("ranking").put("mode","distance").put("provider",JSONObject.NULL);photo(fallback).put("visualScore",JSONObject.NULL).put("scene","all");
        check(ScenicPhotoCollection.parse(fallback,destination,10000).rankingProvider==null,"Fallback cannot claim an AI provider");
        check(ScenicPhotoCollection.parse(fallback.put("photos",new JSONArray()),destination,10000).photos.isEmpty(),"No photographs is a legitimate empty result");
        for(int radius:new int[]{5000,10000,20000})check(ScenicPhotoCollection.parse(response().put("radiusMeters",radius),destination,radius).radiusMeters==radius,"Supported radius "+radius);
        reject(v->v.getJSONObject("destination").put("id","changed"),"Different destination id");
        reject(v->v.getJSONObject("destination").put("name","다른 목적지"),"Different destination name");
        reject(v->v.getJSONObject("destination").put("lat",35.1152),"Different destination coordinate");
        reject(v->v.put("radiusMeters",5000),"Response radius must match request");
        reject(v->v.put("radiusMeters","10000"),"No number coercion");
        reject(v->v.put("source","Gleo AI"),"Unknown source");
        reject(v->v.getJSONObject("ranking").put("mode","made-up"),"Unknown ranking mode");
        reject(v->v.getJSONObject("ranking").put("provider","Gleo AI"),"No false provider name");
        reject(v->v.getJSONObject("ranking").put("provider",JSONObject.NULL),"Vision needs actual provider");
        reject(v->v.getJSONObject("ranking").put("mode","distance"),"Distance cannot claim OpenAI");
        reject(v->v.getJSONObject("ranking").remove("provider"),"Provider null must be explicit");
        reject(v->photo(v).put("distanceMeters",0),"Distance cannot silently refer to another destination");
        reject(v->photo(v).getJSONObject("place").put("lat",37.5),"POI outside requested radius");
        reject(v->photo(v).put("visualScore",101),"Bound visual score");
        reject(v->photo(v).put("visualScore",85.5),"Require integer visual score");
        reject(v->photo(v).put("visualScore","85"),"No score coercion");
        reject(v->photo(v).remove("visualScore"),"Explicit null rather than omitted score");
        reject(v->photo(v).put("scene","sea"),"Canonical scene enum only");
        reject(v->photo(v).put("id","tour-photo-999"),"Photo id must bind to its POI");
        reject(v->photo(v).put("sourceUrl","https://evil.example/license"),"Public source is pinned");
        reject(v->photo(v).put("license","All rights reserved"),"Only eligible public license");
        reject(v->photo(v).put("reason","control\ntext"),"No hidden control text");
        reject(v->photo(v).put("selectionToken","invalid"),"Only shaped server-issued tokens");
        reject(v->v.getJSONArray("photos").put(photo(v)),"No duplicate photo/POI");
        reject(v->{JSONArray rows=v.getJSONArray("photos");for(int i=0;i<12;i++)rows.put(new JSONObject(photo(v).toString()).put("id","tour-photo-"+(999+i)).put("place",place("tour-"+(999+i),"바다",35.13,129.05)));},"Bound collection memory");
        for(String url:new String[]{"http://tong.visitkorea.or.kr/cms/resource/a.jpg","https://tong.visitkorea.or.kr.evil.example/cms/resource/a.jpg","https://tong.visitkorea.or.kr:444/cms/resource/a.jpg","https://user@tong.visitkorea.or.kr/cms/resource/a.jpg","https://tong.visitkorea.or.kr/cms/resource/../private.jpg","https://tong.visitkorea.or.kr/cms/resource/%2e%2e/a.jpg","https://tong.visitkorea.or.kr/cms/resource/a.jpg?redirect=evil","https://tong.visitkorea.or.kr/cms/resource/a.jpg#fragment","https://tong.visitkorea.or.kr/other/a.jpg","file:///cms/resource/a.jpg"}){
            check(!ScenicPhotoCollection.safeImageUrl(url),"Reject unsafe image URL");reject(v->photo(v).put("thumbnailUrl",url),"Reject unsafe thumbnail");
        }
        check(ScenicPhotoCollection.safeImageUrl("https://tong.visitkorea.or.kr/cms/resource/11/test_01.jpg"),"Allow official HTTPS JPEG");
        for(String type:new String[]{"image/jpeg","image/jpg","IMAGE/JPG; charset=binary","image/png","image/webp"})check(ScenicPhotoCollection.safeImageContentType(type),"Accept official image MIME "+type);
        for(String type:new String[]{null,"","text/html","image/jpeg-redirect","application/octet-stream"})check(!ScenicPhotoCollection.safeImageContentType(type),"Reject non-image MIME");
        for(String token:new String[]{"",TOKEN+"?",TOKEN+".","prefix."+TOKEN,new String(new char[4097]).replace('\0','a')})check(!ScenicPhotoCollection.validSelectionToken(token),"Reject malformed/oversized token");
        System.out.println("ScenicPhotoCollectionTest: "+checks+" checks passed");
    }
}
