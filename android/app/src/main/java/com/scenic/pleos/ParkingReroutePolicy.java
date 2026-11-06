package com.scenic.pleos;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Only a fresh full observation can trigger an automatic change to another fresh available lot. */
public final class ParkingReroutePolicy {
    public static final class Decision {
        public final ParkingAvailability current,alternativeAvailability;
        public final DestinationPlace alternative;
        private Decision(ParkingAvailability current,DestinationPlace alternative,ParkingAvailability availability){this.current=current;this.alternative=alternative;alternativeAvailability=availability;}
    }
    public static Decision evaluate(JSONObject response,DestinationPlace target,Set<String> excluded,long now)throws JSONException {
        if(target==null||!"Kakao Local".equals(response.getString("source"))||!"parking".equals(response.getString("kind")))throw new JSONException("Invalid parking lookup");
        JSONArray rows=response.getJSONArray("places");if(rows.length()>10)throw new JSONException("Too many parking places");
        JSONObject focus=response.optJSONObject("focus");
        if(focus==null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(target.id.equals(row.optString("id")))focus=row;}
        ParkingAvailability current=ParkingAvailability.unknown(now);
        if(focus!=null){DestinationPlace found=place(focus);if(!samePlace(target,found))throw new JSONException("Parking focus changed");current=ParkingAvailability.read(focus,now);}
        if(!current.isFull(now))return new Decision(current,null,null);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);DestinationPlace candidate=place(row);ParkingAvailability availability=ParkingAvailability.read(row,now);
            if(!candidate.id.equals(target.id)&&!excluded.contains(candidate.id)&&availability.isAvailable(now))return new Decision(current,candidate,availability);
        }
        return new Decision(current,null,null);
    }
    private static DestinationPlace place(JSONObject row)throws JSONException {
        DestinationPlace place=DestinationPlace.read(row,"Kakao Local");
        if(!place.id.matches("[0-9]{1,100}"))throw new JSONException("Invalid parking ID");
        DestinationPlace.number(row.get("distanceMeters"),0,3000);return place;
    }
    public static boolean samePlace(DestinationPlace a,DestinationPlace b){return a!=null&&b!=null&&a.id.equals(b.id)&&a.name.equals(b.name)&&CourseEngine.distanceMeters(a.lat,a.lon,b.lat,b.lon)<2;}
}
