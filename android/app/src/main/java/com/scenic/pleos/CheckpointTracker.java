package com.scenic.pleos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Follows positions along one route and says when a clip should start and stop. It holds no camera,
 * clock or UI: the host feeds it fixes and acts on what it returns. Each checkpoint fires once per
 * tracker, so a GPS fix bouncing around the start of a stretch cannot record it twice.
 */
public final class CheckpointTracker {
    public static final double ARRIVE_METERS=150;
    /** A stretch that is never finished (the road turned away) is closed after this distance from its start. */
    public static final double ABANDON_METERS=2000;
    public enum Kind {START,END}
    public static final class Event {
        public final Kind kind;public final RouteCheckpoint checkpoint;
        Event(Kind kind,RouteCheckpoint checkpoint){this.kind=kind;this.checkpoint=checkpoint;}
    }
    private final List<RouteCheckpoint> checkpoints;
    private final Set<String> started=new HashSet<>();
    private RouteCheckpoint active;
    private double travelled;

    public CheckpointTracker(List<RouteCheckpoint> checkpoints){this.checkpoints=new ArrayList<>(checkpoints);}
    public RouteCheckpoint active(){return active;}
    public int startedCount(){return started.size();}

    /** Feed one position. Returns zero, one or two events (a place starts and ends at once). */
    public List<Event> update(double lat,double lon) {
        List<Event> events=new ArrayList<>();
        if(!CourseEngine.inKorea(lat,lon))return events;
        if(active!=null) {
            double toEnd=CourseEngine.distanceMeters(lat,lon,active.endLat,active.endLon);
            double fromStart=CourseEngine.distanceMeters(lat,lon,active.lat,active.lon);
            // The stretch is over once the road reaches its end, or has clearly left it.
            if(toEnd<=ARRIVE_METERS||fromStart>Math.max(ABANDON_METERS,active.km*1000*1.5)){events.add(new Event(Kind.END,active));active=null;}
        }
        if(active==null) {
            for(RouteCheckpoint checkpoint:checkpoints) {
                if(started.contains(checkpoint.id))continue;
                if(CourseEngine.distanceMeters(lat,lon,checkpoint.lat,checkpoint.lon)>ARRIVE_METERS)continue;
                started.add(checkpoint.id);events.add(new Event(Kind.START,checkpoint));
                if(checkpoint.isStretch())active=checkpoint;else events.add(new Event(Kind.END,checkpoint));
                break;
            }
        }
        return events;
    }
    /** Closes a stretch that was still open when the trip ended. */
    public Event finish(){if(active==null)return null;Event end=new Event(Kind.END,active);active=null;return end;}
}
