package com.scenic.pleos;

/** Android-free, north-up local projection for displaying route geometry without map tiles. */
public final class RouteProjection {
    private final double centerLatitude,centerLongitude,longitudeScale,scale,centerX,centerY;

    private RouteProjection(double centerLatitude,double centerLongitude,double longitudeScale,double scale,double centerX,double centerY){
        this.centerLatitude=centerLatitude;this.centerLongitude=centerLongitude;this.longitudeScale=longitudeScale;
        this.scale=scale;this.centerX=centerX;this.centerY=centerY;
    }

    /** Fits every road vertex and course stop in the supplied inset viewport, preserving aspect ratio. */
    public static RouteProjection fit(double[][] route,double[][] stops,double left,double top,double right,double bottom){
        validatePoints(route,2);
        if(stops!=null)validatePoints(stops,0);
        double width=right-left,height=bottom-top;
        if(!Double.isFinite(left)||!Double.isFinite(top)||!Double.isFinite(right)||!Double.isFinite(bottom)
                ||!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0)
            throw new IllegalArgumentException("Route viewport must be finite and have positive area");
        double minLat=route[0][0],maxLat=minLat,minLon=route[0][1],maxLon=minLon;
        for(double[][] points:new double[][][]{route,stops}){
            if(points==null)continue;
            for(double[] point:points){
                minLat=Math.min(minLat,point[0]);maxLat=Math.max(maxLat,point[0]);
                minLon=Math.min(minLon,point[1]);maxLon=Math.max(maxLon,point[1]);
            }
        }
        double meanLatitude=(minLat+maxLat)/2;
        // Longitude degrees shorten toward the poles. The floor keeps exact polar inputs finite.
        double longitudeScale=Math.max(0.000001,Math.cos(Math.toRadians(meanLatitude)));
        double spanX=(maxLon-minLon)*longitudeScale,spanY=maxLat-minLat;
        // Sub-millimetre spans should center safely instead of producing an infinite scale.
        double scale=Math.min(width/Math.max(spanX,0.000000001),height/Math.max(spanY,0.000000001));
        return new RouteProjection(meanLatitude,(minLon+maxLon)/2,longitudeScale,scale,left+width/2,top+height/2);
    }

    /** Defensively retains only valid [latitude, longitude] pairs; malformed routes fail as a whole. */
    public static double[][] copyValidatedRoute(double[][] coordinates){
        validatePoints(coordinates,2);
        double[][] copy=new double[coordinates.length][2];
        for(int i=0;i<coordinates.length;i++){copy[i][0]=coordinates[i][0];copy[i][1]=coordinates[i][1];}
        return copy;
    }

    public double[] project(double latitude,double longitude){
        validateCoordinate(latitude,longitude);
        return new double[]{centerX+(longitude-centerLongitude)*longitudeScale*scale,centerY-(latitude-centerLatitude)*scale};
    }

    private static void validatePoints(double[][] points,int minimum){
        if(points==null||points.length<minimum)throw new IllegalArgumentException("A road route requires at least two coordinates");
        for(double[] point:points){
            if(point==null||point.length!=2)throw new IllegalArgumentException("Coordinates must be [latitude, longitude] pairs");
            validateCoordinate(point[0],point[1]);
        }
    }

    private static void validateCoordinate(double latitude,double longitude){
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude < -90||latitude > 90||longitude < -180||longitude > 180)
            throw new IllegalArgumentException("Coordinates must be finite and within latitude/longitude bounds");
    }
}
