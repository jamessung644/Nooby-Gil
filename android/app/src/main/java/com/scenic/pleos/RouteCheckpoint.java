package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A place on a road where a clip is worth recording: a chosen place, or the start of a stretch of
 * coast, water or mountain. The server decides where they are; the app only follows them.
 */
public final class RouteCheckpoint {
    public static final int MAX_PER_ROUTE=6;
    public final String id,kind,name;
    public final double lat,lon,endLat,endLon,km;

    RouteCheckpoint(String id,String kind,String name,double lat,double lon,double endLat,double endLon,double km) {
        this.id=id;this.kind=kind;this.name=name;this.lat=lat;this.lon=lon;this.endLat=endLat;this.endLon=endLon;this.km=km;
    }
    public static RouteCheckpoint of(String id,String kind,String name,double lat,double lon,double endLat,double endLon,double km) {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||!validKind(kind)||name==null||name.trim().isEmpty()||name.length()>80
                ||!CourseEngine.inKorea(lat,lon)||!CourseEngine.inKorea(endLat,endLon)||!(km>=0&&km<=500))
            throw new IllegalArgumentException("Invalid checkpoint");
        return new RouteCheckpoint(id,kind,name,lat,lon,endLat,endLon,km);
    }
    static boolean validKind(String kind){return "place".equals(kind)||"coast".equals(kind)||"water".equals(kind)||"mountain".equals(kind);}
    /** A stretch has a separate end; a place is a single point. */
    public boolean isStretch(){return km>0;}

    /** An absent list is allowed, a malformed one is not. */
    static List<RouteCheckpoint> parseAll(JSONObject route)throws JSONException {
        if(!route.has("checkpoints"))return Collections.emptyList();
        JSONArray raw=route.getJSONArray("checkpoints");
        if(raw.length()>MAX_PER_ROUTE)throw new JSONException("Too many checkpoints");
        List<RouteCheckpoint> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<raw.length();i++) {
            JSONObject item=raw.getJSONObject(i);
            try {
                RouteCheckpoint checkpoint=of(DestinationPlace.text(item,"id",100,false),DestinationPlace.text(item,"kind",16,false),DestinationPlace.text(item,"name",80,false),
                        DestinationPlace.number(item.get("lat"),33,38.65),DestinationPlace.number(item.get("lon"),124.5,131.95),
                        DestinationPlace.number(item.get("endLat"),33,38.65),DestinationPlace.number(item.get("endLon"),124.5,131.95),
                        DestinationPlace.number(item.get("km"),0,500));
                if(!ids.add(checkpoint.id))throw new JSONException("Duplicate checkpoint");
                result.add(checkpoint);
            } catch(IllegalArgumentException invalid){throw new JSONException("Invalid checkpoint");}
        }
        return Collections.unmodifiableList(result);
    }
}
