package com.scenic.pleos;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Destination-bound public photographs. Place coordinates are not camera coordinates. */
public final class ScenicPhotoCollection {
    public static final String SOURCE="한국관광공사 TourAPI";
    public static final String LICENSE="공공누리 제1유형 (출처표시)";
    public static final String SOURCE_URL="https://www.data.go.kr/data/15101578/openapi.do";
    public final DestinationPlace destination;
    public final int radiusMeters;
    public final String source,rankingMode,rankingProvider,rankingMessage,message;
    public final List<Photo> photos;

    public static final class Photo {
        public final String id,imageUrl,thumbnailUrl,sourceName,sourceUrl,license,reason,scene,selectionToken,rankingMode;
        public final DestinationPlace place;
        private final DestinationPlace requestedDestination;
        public final double distanceMeters;
        public final Double visualScore;
        private Photo(JSONObject value,DestinationPlace destination,int radius,String mode)throws JSONException {
            rankingMode=mode;requestedDestination=destination;
            id=text(value,"id",120);place=DestinationPlace.read(value.getJSONObject("place"),SOURCE);
            if(!id.matches("tour-photo-[0-9]+")||!place.id.matches("tour-[0-9]+")||!id.substring(11).equals(place.id.substring(5)))throw invalid();
            imageUrl=text(value,"imageUrl",2048);thumbnailUrl=text(value,"thumbnailUrl",2048);
            if(!safeImageUrl(imageUrl)||!safeImageUrl(thumbnailUrl))throw invalid();
            sourceName=text(value,"sourceName",80);sourceUrl=text(value,"sourceUrl",2048);license=text(value,"license",160);
            if(!SOURCE.equals(sourceName)||!SOURCE_URL.equals(sourceUrl)||!LICENSE.equals(license))throw invalid();
            distanceMeters=DestinationPlace.number(value.get("distanceMeters"),0,radius+25);
            if(distanceMeters!=Math.rint(distanceMeters)||!value.has("visualScore"))throw invalid();
            double actual=CourseEngine.distanceMeters(destination.lat,destination.lon,place.lat,place.lon);
            if(actual>radius+25||Math.abs(actual-distanceMeters)>100)throw invalid();
            visualScore=value.isNull("visualScore")?null:DestinationPlace.number(value.get("visualScore"),0,100);
            if(visualScore!=null&&visualScore!=Math.rint(visualScore))throw invalid();
            if("distance".equals(mode)&&visualScore!=null)throw invalid();
            reason=text(value,"reason",600);scene=text(value,"scene",24);
            if(!DestinationRoutePlan.validTheme(scene)||("distance".equals(mode)&&!"all".equals(scene)))throw invalid();
            selectionToken=text(value,"selectionToken",4096);
            if(!validSelectionToken(selectionToken))throw invalid();
        }
    }

    private ScenicPhotoCollection(DestinationPlace destination,int radius,String mode,String provider,String rankingMessage,String message,List<Photo> photos){
        this.destination=destination;radiusMeters=radius;source=SOURCE;rankingMode=mode;rankingProvider=provider;
        this.rankingMessage=rankingMessage;this.message=message;this.photos=Collections.unmodifiableList(photos);
    }

    public static ScenicPhotoCollection parse(JSONObject response,DestinationPlace expected,int expectedRadius)throws JSONException {
        if(response==null||expected==null||!validRadius(expectedRadius))throw invalid();
        DestinationPlace destination=DestinationPlace.read(response.getJSONObject("destination"),expected.source);
        if(!destination.id.equals(expected.id)||!destination.name.equals(expected.name)
                ||!same(destination.lat,expected.lat)||!same(destination.lon,expected.lon))throw invalid();
        if(DestinationPlace.number(response.get("radiusMeters"),5000,20000)!=expectedRadius||!SOURCE.equals(text(response,"source",80)))throw invalid();
        JSONObject ranking=response.getJSONObject("ranking");String mode=text(ranking,"mode",20);
        if(!"vision".equals(mode)&&!"distance".equals(mode))throw invalid();
        if(!ranking.has("provider"))throw invalid();
        String provider=ranking.isNull("provider")?null:text(ranking,"provider",40);
        if(("vision".equals(mode)&&!"OpenAI".equals(provider))||("distance".equals(mode)&&provider!=null))throw invalid();
        String rankingMessage=text(ranking,"message",600),message=DestinationPlace.text(response,"message",600,true);
        JSONArray rows=response.getJSONArray("photos");if(rows.length()>12)throw invalid();
        List<Photo> photos=new ArrayList<>();Set<String> ids=new HashSet<>();Set<String> places=new HashSet<>();
        for(int i=0;i<rows.length();i++){Photo photo=new Photo(rows.getJSONObject(i),destination,expectedRadius,mode);if(!ids.add(photo.id)||!places.add(photo.place.id))throw invalid();photos.add(photo);}
        return new ScenicPhotoCollection(expected,expectedRadius,mode,provider,rankingMessage,message,photos);
    }

    public static boolean validRadius(int radius){return radius==5000||radius==10000||radius==20000;}

    /** Persists the exact selection and endpoint binding; the server still enforces token expiry. */
    public static String serializeSelection(Photo photo,DestinationPlace destination){
        if(photo==null||destination==null||!photo.requestedDestination.id.equals(destination.id)
                ||!photo.requestedDestination.name.equals(destination.name)||!same(photo.requestedDestination.lat,destination.lat)
                ||!same(photo.requestedDestination.lon,destination.lon))return "";
        try{
            JSONObject selected=new JSONObject().put("id",photo.id).put("place",placeJson(photo.place))
                    .put("imageUrl",photo.imageUrl).put("thumbnailUrl",photo.thumbnailUrl).put("sourceName",photo.sourceName)
                    .put("sourceUrl",photo.sourceUrl).put("license",photo.license).put("distanceMeters",photo.distanceMeters)
                    .put("visualScore",photo.visualScore==null?JSONObject.NULL:photo.visualScore).put("reason",photo.reason)
                    .put("scene",photo.scene).put("selectionToken",photo.selectionToken);
            JSONObject value=new JSONObject().put("destination",placeJson(destination)).put("radiusMeters",20000).put("source",SOURCE)
                    .put("ranking",new JSONObject().put("mode",photo.rankingMode).put("provider","vision".equals(photo.rankingMode)?"OpenAI":JSONObject.NULL).put("message","저장한 사진 선택"))
                    .put("photos",new JSONArray().put(selected)).put("message","");
            parse(value,destination,20000);return value.toString();
        }catch(JSONException invalid){return "";}
    }
    public static Photo restoreSelection(String serialized,DestinationPlace expected){
        if(serialized==null||serialized.length()>32768||expected==null||!StrictJson.isValid(serialized))return null;
        try{ScenicPhotoCollection value=parse(new JSONObject(serialized),expected,20000);return value.photos.size()==1?value.photos.get(0):null;}
        catch(Exception invalid){return null;}
    }
    private static JSONObject placeJson(DestinationPlace place)throws JSONException{return new JSONObject().put("id",place.id).put("name",place.name).put("address",place.address).put("lat",place.lat).put("lon",place.lon);}
    public static boolean validSelectionToken(String token){return token!=null&&token.length()<=4096&&token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}");}
    public static boolean safeImageContentType(String value){String type=value==null?"":value.toLowerCase(java.util.Locale.US).split(";",2)[0].trim();return "image/jpeg".equals(type)||"image/jpg".equals(type)||"image/png".equals(type)||"image/webp".equals(type);}
    /** Exact official photo host/path; no credentials, query, fragments, ports or redirects. */
    public static boolean safeImageUrl(String value){
        try{URI uri=new URI(value);String path=uri.getRawPath();return "https".equals(uri.getScheme())&&"tong.visitkorea.or.kr".equals(uri.getHost())
                &&uri.getRawUserInfo()==null&&uri.getPort()==-1&&uri.getRawQuery()==null&&uri.getRawFragment()==null
                &&path!=null&&path.matches("(?i)/cms/(?:resource|resource_photo)/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:jpe?g|png|webp)");}
        catch(Exception error){return false;}
    }
    private static boolean same(double a,double b){return Math.abs(a-b)<=0.0000001;}
    private static String text(JSONObject object,String key,int max)throws JSONException{return DestinationPlace.text(object,key,max,false);}
    private static JSONException invalid(){return new JSONException("Invalid destination photo response");}
}
