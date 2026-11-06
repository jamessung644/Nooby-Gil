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
        check(state.originLat()==37.5665&&state.originLon()==126.9780,"Parking keeps Seoul origin");check(!state.usesGps(),"Explicit demo source stays a demo");check(state.restaurant().id.equals("food"),"Restaurant survives parking selection");check(state.target().id.equals("park"),"Parking is driving endpoint");check(state.nearbyAnchor().name.equals("경복궁"),"Restaurant search keeps the chosen attraction anchor");check(state.id().equals(tripId),"Food and parking remain in the same trip");
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
        JourneyState regional=new JourneyState();regional.setOrigin(true,37.5665,126.9780,"현재 위치");regional.beginRegion(beach);String regionalId=regional.id();
        check(regional.region()==beach&&regional.scenic()==null,"Region starts photo discovery without inventing an attraction");
        regional.chooseScenicDestination(food);
        check(regional.target()==food&&regional.scenic()==food&&regional.nearbyAnchor()==food,"Selected photo becomes destination and restaurant search center");
        check(regional.region()==beach&&regional.id().equals(regionalId),"Photo selection preserves original region and trip identity");
        check(regional.stops().size()==1&&regional.stops().get(0)==food,"Region is context, not an unchosen waypoint");
        DestinationPlace lunch=DestinationPlace.of("lunch","점심 식당","서울",37.577,126.977);
        regional.selectRestaurant(lunch);
        check(regional.target()==food&&regional.restaurant()==lunch&&regional.parking()==null,"Selecting lunch automatically prepares parking without changing driving destination");
        check(regional.nearbyAnchor()==food&&regional.region()==beach&&regional.scenic()==food,"Food selection preserves attraction and original region");
        JourneyState beforeParking=JourneyState.restore(regional.serialize());
        check(beforeParking.restaurant().id.equals("lunch")&&beforeParking.target().id.equals("food"),"Pending lunch selection survives restart without becoming target");
        check(beforeParking.matchesScenicDestination(food),"Matching restored photo agrees with target and scenic attraction");
        check(!beforeParking.requiresDirectRoute(),"Pending restaurant choice does not change scenic routing policy");
        check(!beforeParking.matchesScenicDestination(beach),"Region center cannot masquerade as selected photograph");
        check(!beforeParking.matchesScenicDestination(DestinationPlace.of(food.id,food.name,food.address,food.lat+0.001,food.lon)),"Same photograph ID with different coordinates is rejected after partial persistence");
        regional.visitParking(parking,lunch);
        check(regional.target()==parking&&regional.region()==beach&&regional.scenic()==food,"Explicit parking CTA changes only the driving endpoint");
        check(!regional.matchesScenicDestination(food),"Retained scenic context is not an active photo destination after parking choice");
        check(regional.originLat()==37.5665&&regional.usesGps()&&regional.id().equals(regionalId),"Parking keeps origin source and trip identity");
        JourneyState allRestored=JourneyState.restore(regional.serialize());
        check(allRestored.region().id.equals("beach")&&allRestored.scenic().id.equals("food")&&allRestored.restaurant().id.equals("lunch")&&allRestored.parking().id.equals("park"),"Region attraction restaurant and parking survive restart separately");
        check(allRestored.nearbyAnchor().id.equals("food"),"Restart keeps restaurant search near the attraction");
        check(allRestored.requiresDirectRoute(),"Restored parking route stays direct without new scenic detours");
        allRestored.selectRestaurant(lunch);check(allRestored.parking()==null&&allRestored.target().id.equals("park"),"Reselecting lunch cancels parking selection without silently sending navigation");
        allRestored.chooseScenicDestination(parking);check(allRestored.restaurant()==null&&allRestored.parking()==null&&allRestored.region().id.equals("beach"),"New attraction clears stale lunch and parking but preserves region");
        allRestored.beginDestination(beach);check(allRestored.region()==null&&allRestored.scenic()==null,"Legacy destination starts a separate trip without stale region");
        regional.beginCourse(course,false);check(regional.region()==null&&regional.scenic()==null,"AI course clears unrelated regional context");
        org.json.JSONObject legacy=new org.json.JSONObject(state.serialize());legacy.put("version",1);legacy.remove("region");legacy.remove("scenic");
        JourneyState migrated=JourneyState.restore(legacy.toString());check(migrated.target().id.equals("park")&&migrated.region()==null&&migrated.restaurant().id.equals("food"),"Version 1 trips migrate without discarding saved endpoint or inventing regional context");
        System.out.println("JourneyStateTest: "+checks+" checks passed");
    }
}
