package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A chosen endpoint, distinct from sightseeing stops and from the device location. */
public final class DestinationPlace {
    public static final String SEARCH_SOURCE = "Kakao Local";
    public static final String PUBLIC_SOURCE = "공개 예시 목적지";
    public final String id, name, address, source;
    public final double lat, lon;

    private DestinationPlace(String id,String name,String address,double lat,double lon,String source) {
        this.id=id;this.name=name;this.address=address;this.lat=lat;this.lon=lon;this.source=source;
    }

    /** Creates an explicitly chosen public example; it does not imply a search was performed. */
    public static DestinationPlace of(String id,String name,String address,double lat,double lon)throws JSONException {
        return read(new JSONObject().put("id",id).put("name",name).put("address",address)
                .put("lat",lat).put("lon",lon),PUBLIC_SOURCE);
    }

    public static List<DestinationPlace> parseSearch(JSONObject response)throws JSONException {
        if(response==null||!SEARCH_SOURCE.equals(text(response,"source",40,false)))throw invalid();
        JSONArray places=response.getJSONArray("places");
        if(places.length()>15)throw invalid();
        List<DestinationPlace> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<places.length();i++){
            DestinationPlace place=read(places.getJSONObject(i),SEARCH_SOURCE);
            if(!ids.add(place.id))throw invalid();
            result.add(place);
        }
        return Collections.unmodifiableList(result);
    }

    static DestinationPlace read(JSONObject object,String source)throws JSONException {
        String id=text(object,"id",100,false),name=text(object,"name",120,false);
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw invalid();
        String address=object.has("address")?text(object,"address",300,true):"";
        double lat=number(object.get("lat"),33,38.65),lon=number(object.get("lon"),124.5,131.95);
        return new DestinationPlace(id,name,address,lat,lon,source);
    }

    static String text(JSONObject object,String key,int max,boolean allowEmpty)throws JSONException {
        Object raw=object.get(key);if(!(raw instanceof String))throw invalid();
        String value=((String)raw).trim();
        if((!allowEmpty&&value.isEmpty())||value.length()>max)throw invalid();
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw invalid();
        return value;
    }

    static double number(Object raw,double min,double max)throws JSONException {
        if(!(raw instanceof Number))throw invalid();
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<min||value>max)throw invalid();
        return value;
    }

    static JSONException invalid(){return new JSONException("Invalid destination response");}
}
