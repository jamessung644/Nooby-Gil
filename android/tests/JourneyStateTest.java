package com.scenic.pleos;

public final class JourneyStateTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        JourneyState state=new JourneyState();
        DestinationPlace beach=DestinationPlace.of("beach","해운대","부산",35.1585,129.1598);
        state.beginDestination(beach);String firstId=state.id();
        state.setOrigin(false,37.5665,126.9780,"서울시청 · 시연 출발지");
        CourseEngine.Course course=new CourseEngine.Course("seoul","서울 드라이브","예시","night","도시","5km",60,"seoul","서울",37.5665,126.9780,new CourseEngine.Spot[]{new CourseEngine.Spot("덕수궁","서울 중구",37.5658,126.9751),new CourseEngine.Spot("경복궁","서울 종로구",37.5796,126.9770)});
        state.beginCourse(course,true);String tripId=state.id();
        check(!firstId.equals(tripId),"New course starts a new trip");check(state.nearbyAnchor().name.equals("경복궁"),"AI stops replace old Busan search anchor");check(state.returnsToOrigin(),"Return choice retained");
        DestinationPlace food=DestinationPlace.of("food","서울 식당","서울",37.578,126.978),parking=DestinationPlace.of("park","서울 주차장","서울",37.577,126.978);
        state.visitRestaurant(food);state.visitParking(parking,food);
        check(state.originLat()==37.5665&&state.originLon()==126.9780,"Parking keeps Seoul origin");check(!state.usesGps(),"Explicit demo source stays a demo");check(state.restaurant().id.equals("food"),"Restaurant survives parking selection");check(state.target().id.equals("park"),"Parking is driving endpoint");check(state.nearbyAnchor().id.equals("food"),"Parking does not become the restaurant search center");check(state.id().equals(tripId),"Food and parking remain in the same trip");
        JourneyState restored=JourneyState.restore(state.serialize());check(restored.id().equals(tripId)&&restored.restaurant().id.equals("food"),"Trip and restaurant survive restart");check(restored.stops().size()==2,"Original itinerary survives food detour");
        restored.setOrigin(true,37.567,126.98,"현재 위치");restored.visitParking(parking,food);check(restored.usesGps(),"Parking never silently replaces GPS origin");check(restored.originLat()==37.567,"GPS reference survives stop change");
        restored.selectNearbyAnchor(restored.stops().get(0));check(restored.nearbyAnchor().name.equals("덕수궁")&&restored.restaurant()==null,"User may choose another itinerary search anchor");
        check(JourneyState.restore("bad-json").target()==null,"Malformed persistence fails safely");
        JourneyState photoTrip=new JourneyState();photoTrip.setOrigin(false,37.5665,126.9780,"서울시청 · 시연 출발지");photoTrip.beginDestination(beach);String photoTripId=photoTrip.id();photoTrip.setScenicWaypoint(food);
        check(photoTrip.target()==beach&&photoTrip.nearbyAnchor()==beach,"Photo stop never replaces searched destination");
        check(photoTrip.stops().size()==2&&photoTrip.stops().get(0)==food&&photoTrip.stops().get(1)==beach,"Scenery is visited before destination");
        check(photoTrip.id().equals(photoTripId)&&photoTrip.originLat()==37.5665,"Photo keeps trip identity and origin");
        JourneyState photoRestored=JourneyState.restore(photoTrip.serialize());check(photoRestored.stops().get(0).id.equals("food")&&photoRestored.target().id.equals("beach"),"Photo visit and destination survive restart");
        photoTrip.setScenicWaypoint(null);check(photoTrip.stops().size()==1&&photoTrip.target()==beach,"Removing photo keeps destination");
        System.out.println("JourneyStateTest: "+checks+" checks passed");
    }
}
