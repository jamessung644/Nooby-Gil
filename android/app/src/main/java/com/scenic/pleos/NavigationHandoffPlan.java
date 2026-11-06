package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validates the complete requested trip; never silently drops unsupported stops. */
final class NavigationHandoffPlan {
    static final int MAX_WAYPOINTS=2;
    final DestinationPlace destination;
    final List<DestinationPlace> waypoints;

    NavigationHandoffPlan(DestinationPlace destination,List<DestinationPlace> waypoints) {
        if(destination==null)throw new IllegalArgumentException("먼저 목적지나 코스를 선택해 주세요.");
        if(waypoints==null)throw new IllegalArgumentException("경유지 목록을 확인해 주세요.");
        if(waypoints.size()>MAX_WAYPOINTS)throw new IllegalArgumentException("순정 내비에는 경유지 2곳과 최종 목적지까지 전달할 수 있어요. 방문지를 줄이거나 구간별로 안내를 시작해 주세요.");
        validate(destination);
        ArrayList<DestinationPlace> copy=new ArrayList<>();
        for(DestinationPlace point:waypoints){validate(point);copy.add(point);}
        this.destination=destination;this.waypoints=Collections.unmodifiableList(copy);
    }
    private static void validate(DestinationPlace point){
        if(point==null||point.name==null||point.name.trim().isEmpty()||!Double.isFinite(point.lat)||!Double.isFinite(point.lon)
                ||point.lat<33||point.lat>38.65||point.lon<124.5||point.lon>131.95)throw new IllegalArgumentException("국내 목적지와 경유지 좌표를 확인해 주세요.");
    }
    /** Allows a road-entry snap, but not an unrelated nearby neighbourhood. */
    static boolean matches(DestinationPlace expected,double latitude,double longitude){
        if(expected==null||!Double.isFinite(latitude)||!Double.isFinite(longitude))return false;
        double lat=Math.toRadians(latitude-expected.lat),lon=Math.toRadians(longitude-expected.lon);
        double h=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(expected.lat))*Math.cos(Math.toRadians(latitude))*Math.sin(lon/2)*Math.sin(lon/2);
        return 12742000*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)))<=200;
    }
}
