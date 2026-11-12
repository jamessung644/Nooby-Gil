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
        return evaluate(response,target,excluded,now,false);
    }
    public static Decision evaluateForAutomaticReroute(JSONObject response,DestinationPlace target,Set<String> excluded,long now)throws JSONException {
        return evaluate(response,target,excluded,now,true);
    }
    private static Decision evaluate(JSONObject response,DestinationPlace target,Set<String> excluded,long now,boolean requireObservation)throws JSONException {
        if(target==null||!"Kakao Local".equals(response.getString("source"))||!"parking".equals(response.getString("kind")))throw new JSONException("Invalid parking lookup");
        JSONArray rows=response.getJSONArray("places");if(rows.length()>10)throw new JSONException("Too many parking places");
        JSONObject focus=response.optJSONObject("focus");
        if(focus==null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(target.id.equals(row.optString("id")))focus=row;}
        ParkingAvailability current=ParkingAvailability.unknown(now);
        if(focus!=null){DestinationPlace found=place(focus);if(!samePlace(target,found))throw new JSONException("Parking focus changed");current=ParkingAvailability.read(focus,now);}
        if(!current.isFull(now)||requireObservation&&!current.hasVerifiedObservation(now))return new Decision(current,null,null);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);DestinationPlace candidate=place(row);ParkingAvailability availability=ParkingAvailability.read(row,now);
            if(!candidate.id.equals(target.id)&&!excluded.contains(candidate.id)&&availability.isAvailable(now)&&(!requireObservation||availability.hasVerifiedObservation(now)))return new Decision(current,candidate,availability);
        }
        return new Decision(current,null,null);
    }
    /** A suggestion shown to the driver; it never changes the destination by itself. */
    public static final class Proposal {
        public static final String FULL="FULL",LOW="LOW";
        /** FULL, LOW, or null when the current lot is fine. */
        public final String level;
        public final ParkingAvailability current,alternativeAvailability;
        public final DestinationPlace alternative;
        private Proposal(String level,ParkingAvailability current,DestinationPlace alternative,ParkingAvailability availability){this.level=level;this.current=current;this.alternative=alternative;alternativeAvailability=availability;}
        public String key(){return alternative==null||level==null?"":alternative.id+"|"+level;}
    }
    /**
     * Proposes a switch when the current lot is full or nearly full and a clearly roomier one is nearby.
     * Fresh official snapshots count as evidence here because the driver still decides.
     * preferredId lets an accepted suggestion keep its lot if that lot is still a valid choice.
     */
    public static Proposal propose(JSONObject response,DestinationPlace target,Set<String> excluded,long now,String preferredId)throws JSONException {
        if(target==null||!"Kakao Local".equals(response.getString("source"))||!"parking".equals(response.getString("kind")))throw new JSONException("Invalid parking lookup");
        JSONArray rows=response.getJSONArray("places");if(rows.length()>10)throw new JSONException("Too many parking places");
        JSONObject focus=response.optJSONObject("focus");
        if(focus==null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(target.id.equals(row.optString("id")))focus=row;}
        ParkingAvailability current=ParkingAvailability.unknown(now);
        if(focus!=null){DestinationPlace found=place(focus);if(!samePlace(target,found))throw new JSONException("Parking focus changed");current=ParkingAvailability.read(focus,now);}
        String level=current.isFull(now)?Proposal.FULL:current.isLow(now)?Proposal.LOW:null;
        if(level==null)return new Proposal(null,current,null,null);
        DestinationPlace best=null;ParkingAvailability bestAvailability=null;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);DestinationPlace candidate=place(row);ParkingAvailability availability=ParkingAvailability.read(row,now);
            if(candidate.id.equals(target.id)||excluded.contains(candidate.id)||!availability.isAvailable(now)||availability.isLow(now))continue;
            if(Proposal.LOW.equals(level)&&availability.availableSpaces<=current.availableSpaces)continue;
            if(candidate.id.equals(preferredId)){best=candidate;bestAvailability=availability;break;}
            if(best==null){best=candidate;bestAvailability=availability;}
        }
        return new Proposal(level,current,best,bestAvailability);
    }
    private static DestinationPlace place(JSONObject row)throws JSONException {
        DestinationPlace place=DestinationPlace.read(row,"Kakao Local");
        if(!place.id.matches("[0-9]{1,100}"))throw new JSONException("Invalid parking ID");
        DestinationPlace.number(row.get("distanceMeters"),0,3000);return place;
    }
    public static boolean samePlace(DestinationPlace a,DestinationPlace b){return a!=null&&b!=null&&a.id.equals(b.id)&&a.name.equals(b.name)&&CourseEngine.distanceMeters(a.lat,a.lon,b.lat,b.lon)<2;}
}
