package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared trip context. Changing a lunch/parking stop never changes the chosen origin. */
public final class JourneyState {
    private String id=UUID.randomUUID().toString(),title="새 드라이브",originLabel="부산역 · 시연 출발지";
    private double originLat=35.1151,originLon=129.0414;
    private boolean gps,roundTrip;
    private DestinationPlace target,primary,restaurant,parking,region,scenic;
    private final List<DestinationPlace> stops=new ArrayList<>();
    public String id(){return id;}
    public String title(){return title;}
    public boolean usesGps(){return gps;}
    public boolean returnsToOrigin(){return roundTrip;}
    public double originLat(){return originLat;}
    public double originLon(){return originLon;}
    public String originLabel(){return gps?"현재 위치":originLabel;}
    public DestinationPlace target(){return target;}
    public DestinationPlace restaurant(){return restaurant;}
    public DestinationPlace parking(){return parking;}
    public DestinationPlace region(){return region;}
    public DestinationPlace scenic(){return scenic;}
    /** A restored photo must agree with both saved attraction and navigation target. */
    public boolean matchesScenicDestination(DestinationPlace place){return region!=null&&samePlace(place,scenic)&&samePlace(place,target);}
    public boolean requiresDirectRoute(){return samePlace(target,parking)||samePlace(target,restaurant);}
    private static boolean samePlace(DestinationPlace a,DestinationPlace b){return a!=null&&b!=null&&a.id.equals(b.id)&&a.name.equals(b.name)&&Math.abs(a.lat-b.lat)<0.0000001&&Math.abs(a.lon-b.lon)<0.0000001;}
    public DestinationPlace nearbyAnchor(){return primary;}
    public List<DestinationPlace> stops(){return Collections.unmodifiableList(new ArrayList<>(stops));}
    public void setOrigin(boolean useGps,double lat,double lon,String label){
        if(!CourseEngine.inKorea(lat,lon)||label==null||label.trim().isEmpty())throw new IllegalArgumentException("Valid origin required");
        gps=useGps;originLat=lat;originLon=lon;originLabel=label;
    }
    public void beginDestination(DestinationPlace place){
        if(place==null)throw new IllegalArgumentException("Destination required");
        id=UUID.randomUUID().toString();title=place.name+" 드라이브";target=primary=place;restaurant=parking=region=scenic=null;roundTrip=false;stops.clear();stops.add(place);
    }
    /** The region is search context. A later photo selection is the driving destination. */
    public void beginRegion(DestinationPlace place){beginDestination(place);region=place;}
    public void chooseScenicDestination(DestinationPlace place){
        if(place==null||region==null)throw new IllegalStateException("Choose a region and scenic place first");
        scenic=target=primary=place;restaurant=parking=null;roundTrip=false;title=region.name+" · "+place.name;
        stops.clear();stops.add(place);
    }
    /** Selecting lunch prepares parking search without requesting a destination change. */
    public void selectRestaurant(DestinationPlace place){if(place==null)throw new IllegalArgumentException("Restaurant required");restaurant=place;parking=null;}
    public void beginCourse(CourseEngine.Course course,boolean returnToOrigin){
        if(course==null||course.spots.length==0)throw new IllegalArgumentException("Course required");
        List<DestinationPlace> next=new ArrayList<>();
        try{for(int i=0;i<course.spots.length;i++){CourseEngine.Spot spot=course.spots[i];next.add(DestinationPlace.of("trip_stop_"+i,spot.title,spot.description,spot.lat,spot.lon));}}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid course stops",invalid);}
        id=UUID.randomUUID().toString();title=course.name;stops.clear();stops.addAll(next);target=primary=next.get(next.size()-1);restaurant=parking=region=scenic=null;roundTrip=returnToOrigin;
    }
    public void selectNearbyAnchor(DestinationPlace place){if(place==null)throw new IllegalArgumentException("Anchor required");primary=place;restaurant=parking=null;}
    /** A chosen photograph adds a visit before the original destination; it never replaces it. */
    public void setScenicWaypoint(DestinationPlace waypoint){
        if(target==null)throw new IllegalStateException("Choose a destination first");
        stops.clear();if(waypoint!=null)stops.add(waypoint);stops.add(target);
    }
    public void visitRestaurant(DestinationPlace place){if(place==null)throw new IllegalArgumentException("Restaurant required");restaurant=target=place;parking=null;}
    /** Driving endpoint only: preserved scenic context is not an automatic waypoint. */
    public void visitParking(DestinationPlace place,DestinationPlace food){if(place==null)throw new IllegalArgumentException("Parking required");restaurant=food;parking=target=place;}
    public String serialize(){
        try{JSONObject o=new JSONObject().put("version",2).put("id",id).put("title",title).put("gps",gps).put("originLat",originLat).put("originLon",originLon).put("originLabel",originLabel).put("roundTrip",roundTrip);
            o.put("region",json(region)).put("scenic",json(scenic)).put("target",json(target)).put("primary",json(primary)).put("restaurant",json(restaurant)).put("parking",json(parking));JSONArray rows=new JSONArray();for(DestinationPlace stop:stops)rows.put(json(stop));return o.put("stops",rows).toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public static JourneyState restore(String value){
        JourneyState state=new JourneyState();if(value==null||value.isEmpty())return state;
        try{JSONObject o=new JSONObject(value);int version=o.getInt("version");if(version!=1&&version!=2)throw new IllegalArgumentException();state.id=UUID.fromString(o.getString("id")).toString();state.title=DestinationPlace.text(o,"title",260,false);state.setOrigin(o.getBoolean("gps"),o.getDouble("originLat"),o.getDouble("originLon"),DestinationPlace.text(o,"originLabel",200,false));state.roundTrip=o.getBoolean("roundTrip");state.target=place(o,"target");state.primary=place(o,"primary");state.restaurant=place(o,"restaurant");state.parking=place(o,"parking");if(version==2){state.region=place(o,"region");state.scenic=place(o,"scenic");if(state.scenic!=null&&state.region==null)throw new IllegalArgumentException();}JSONArray rows=o.getJSONArray("stops");if(rows.length()>20)throw new IllegalArgumentException();for(int i=0;i<rows.length();i++)state.stops.add(DestinationPlace.read(rows.getJSONObject(i),"저장된 여정"));return state;}
        catch(Exception malformed){return new JourneyState();}
    }
    private static Object json(DestinationPlace p)throws Exception{return p==null?JSONObject.NULL:new JSONObject().put("id",p.id).put("name",p.name).put("address",p.address).put("lat",p.lat).put("lon",p.lon);}
    private static DestinationPlace place(JSONObject o,String key)throws Exception{return o.isNull(key)?null:DestinationPlace.read(o.getJSONObject(key),"저장된 여정");}
}
