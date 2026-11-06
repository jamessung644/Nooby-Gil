package com.scenic.pleos;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Offline checks for the native-to-map data boundary; no WebView, GPS, or network is used. */
public final class KakaoMapPayloadTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static JSONObject snapshot(KakaoMapPayload payload)throws Exception{return new JSONObject(payload.json());}
    private static long revision(KakaoMapPayload payload)throws Exception{return snapshot(payload).getLong("focusRevision");}
    private static CourseEngine.Course course(String title,CourseEngine.Spot[] stops){
        return new CourseEngine.Course("fixture",title,"description","lake","tag","2 km",10,stops);
    }
    public static void main(String[] args)throws Exception{
        KakaoMapPayload payload=new KakaoMapPayload();
        double[][] roads={{37.8617,127.6764},{37.8721,127.7075}};
        payload.setScene(CourseEngine.COURSES[0],99,roads);
        JSONObject initial=snapshot(payload);
        check("chuncheon".equals(initial.optString("courseId")),"selected course reaches the map instead of an empty/default payload");
        check(initial.getInt("active")==3,"active index is clamped to the last stop");
        check(initial.getJSONArray("stops").getJSONObject(0).getDouble("lat")==37.8617,"stop latitude retains latitude/longitude order");
        check(initial.getJSONArray("roadCoordinates").getJSONArray(1).getDouble(1)==127.7075,"road longitude retains latitude/longitude order");
        check(initial.isNull("location"),"an absent GPS fix is not fabricated");

        // Changed active stop must not reset a camera the user has moved.
        long first=revision(payload);
        payload.setScene(CourseEngine.COURSES[0],-1,new double[][]{{37.8617,127.6764},{37.8721,127.7075}});
        check(revision(payload)==first&&snapshot(payload).getInt("active")==0,"equivalent geometry only changes the active stop");
        payload.setLocation(37.5,127.1,12.5,false);
        check(revision(payload)==first&&"route".equals(snapshot(payload).getString("focus")),"ordinary GPS updates preserve camera ownership");
        check(snapshot(payload).getJSONObject("location").getDouble("accuracy")==12.5,"reported GPS accuracy is preserved");
        payload.setLocation(37.6,127.2,null,true);
        long centered=revision(payload);
        check(centered>first&&"location".equals(snapshot(payload).getString("focus")),"explicit current-location action requests a new focus");
        check(snapshot(payload).getJSONObject("location").isNull("accuracy"),"unknown accuracy remains unknown");
        payload.setLocation(37.61,127.21,-1.0,false);
        check(revision(payload)==centered&&snapshot(payload).getJSONObject("location").isNull("accuracy"),"invalid accuracy is omitted without recentering a valid position");
        payload.focusRoute();
        long routeFocus=revision(payload);
        check(routeFocus>centered&&"route".equals(snapshot(payload).getString("focus")),"route focus is an explicit repeatable camera action");
        payload.focusRoute();
        check(revision(payload)>routeFocus,"a second route-focus tap is not swallowed");
        payload.setLocation(37.5,127.1,2.0,true);
        long beforeClear=revision(payload);
        payload.clearLocation();
        check(snapshot(payload).isNull("location")&&"route".equals(snapshot(payload).getString("focus"))&&revision(payload)>beforeClear,"clearing a focused GPS fix removes it and returns to route focus");
        payload.setLocation(Double.NaN,127,1.0,true);
        check(snapshot(payload).isNull("location"),"non-finite GPS coordinates cannot reach the map");
        payload.setLocation(37,181,1.0,true);
        check(snapshot(payload).isNull("location"),"out-of-range GPS coordinates cannot reach the map");

        long beforeRoad=revision(payload);
        payload.setScene(CourseEngine.COURSES[0],0,new double[][]{{37.8,127.6},{37.9,127.7}});
        check(revision(payload)>beforeRoad&&"route".equals(snapshot(payload).getString("focus")),"new road geometry refits the route");
        long beforeCourse=revision(payload);
        payload.setScene(CourseEngine.COURSES[1],0,null);
        check(revision(payload)>beforeCourse&&snapshot(payload).getJSONArray("roadCoordinates").length()==0,"changing courses clears old geometry and refits");

        // Mutating caller-owned arrays must never silently change an already displayed scene.
        CourseEngine.Spot[] stops={new CourseEngine.Spot("A","",37,127),new CourseEngine.Spot("B","",38,128)};
        double[][] mutable={{37,127},{38,128}};
        CourseEngine.Course fixture=course("Snapshot",stops);
        payload.setScene(fixture,0,mutable);
        mutable[0][0]=0;mutable[1]=new double[]{1,1};stops[0]=new CourseEngine.Spot("Changed","",0,0);
        JSONObject retained=snapshot(payload);
        check(retained.getJSONArray("roadCoordinates").getJSONArray(0).getDouble(0)==37&&retained.getJSONArray("roadCoordinates").getJSONArray(1).getDouble(1)==128,"road geometry is a deep snapshot");
        check("A".equals(retained.getJSONArray("stops").getJSONObject(0).getString("title")),"course stops are a snapshot");
        long beforeStops=revision(payload);
        payload.setScene(fixture,0,new double[][]{{37,127},{38,128}});
        check(revision(payload)>beforeStops,"changed stop geometry refits even when the course ID is unchanged");

        double[][][] invalidRoads={null,new double[0][],{{37,127}},{{37,127},null},{{37,127},{38}},{{37,127},{Double.POSITIVE_INFINITY,128}},{{37,127},{38,Double.NaN}},{{37,127},{91,128}},{{37,127},{38,-181}},new double[50001][2]};
        for(double[][] invalid:invalidRoads){payload.setScene(CourseEngine.COURSES[0],0,invalid);check(snapshot(payload).getJSONArray("roadCoordinates").length()==0,"malformed or excessive geometry is discarded as a whole");}

        String expectedUrl="http://10.0.2.2:4173/kakao-map.html";
        check(KakaoMapPayload.allowsNavigation(expectedUrl,expectedUrl),"the configured map document is allowed");
        String[] external={null,expectedUrl+"?next=x",expectedUrl+"#x","http://10.0.2.2:4173/","http://10.0.2.2:4173/kakao-map.html/","https://evil.example/kakao-map.html","javascript:alert(1)","file:///tmp/map.html"};
        for(String candidate:external)check(!KakaoMapPayload.allowsNavigation(expectedUrl,candidate),"nonexact navigation is blocked");
        check(!KakaoMapPayload.allowsNavigation(null,null),"unset document cannot authorize a null navigation");

        // Decode the generated JavaScript argument independently to catch broken quoting.
        String unusual="지점 \"A\" \\ </script>\n"+'\u2028'+'\u2029'+"');window.injected=true;//";
        payload.setScene(course(unusual,new CourseEngine.Spot[]{new CourseEngine.Spot(unusual,"",37,127),new CourseEngine.Spot("끝","",38,128)}),0,null);
        String script=payload.script();
        int parse=script.indexOf("JSON.parse(");
        check(parse>=0,"script passes a quoted data string through JSON parsing");
        Object argument=new JSONTokener(script.substring(parse+"JSON.parse(".length())).nextValue();
        check(argument instanceof String,"script embeds JSON as a quoted string argument");
        JSONObject decoded=new JSONObject((String)argument);
        check(unusual.equals(decoded.getString("title"))&&unusual.equals(decoded.getJSONArray("stops").getJSONObject(0).getString("title")),"quotes, slashes and line separators round-trip as data");
        check(script.indexOf('\u2028')<0&&script.indexOf('\u2029')<0,"JavaScript source has escaped Unicode line separators");
        System.out.println("KakaoMapPayloadTest: "+checks+" checks passed");
    }
}
