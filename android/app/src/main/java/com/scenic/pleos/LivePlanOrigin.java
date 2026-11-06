package com.scenic.pleos;

/** Keeps a user-chosen public demo origin distinct from an actual Android location. */
public final class LivePlanOrigin {
    public final double lat,lon;
    public final boolean publicDemo;
    private LivePlanOrigin(double lat,double lon,boolean publicDemo){this.lat=lat;this.lon=lon;this.publicDemo=publicDemo;}
    public static LivePlanOrigin current(CourseEngine.Position position){
        if(position==null||!position.fresh()||!CourseEngine.inKorea(position.lat,position.lon)||CourseEngine.unsupportedIslandOrigin(position.lat,position.lon))throw new IllegalArgumentException("Recent supported position required");
        return new LivePlanOrigin(position.lat,position.lon,false);
    }
    public static LivePlanOrigin seoulCityHall(){return new LivePlanOrigin(37.5665,126.9780,true);}
    public boolean moved(CourseEngine.Position latest){return !publicDemo&&latest!=null&&latest.fresh()&&CourseEngine.distanceMeters(lat,lon,latest.lat,latest.lon)>=1000;}
    public boolean canApply(long requestedGeneration,long currentGeneration,CourseEngine.Position latest){
        return requestedGeneration==currentGeneration&&(publicDemo||(latest!=null&&latest.fresh()&&!moved(latest)));
    }
    public String label(){return publicDemo?"서울시청 기준 데모 · 실제 현재 위치 아님":"요청 당시 현재 위치 기준";}
}
