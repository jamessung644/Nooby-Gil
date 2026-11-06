package com.scenic.pleos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Destination identity, provider provenance, detour bounds and privacy at the HTTP boundary. */
public final class DestinationRoutesTest {
    private static int checks;
    private static final double LAT=35.1151,LON=129.0414;
    private static final ArrayDeque<Connection> connections=new ArrayDeque<>();
    private interface Change {void apply(JSONObject object)throws Exception;}
    private interface Call {void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static DestinationPlace destination()throws Exception{return DestinationPlace.of("public-haeundae","해운대해수욕장","부산 해운대구",35.1587,129.1604);}
    private static JSONObject place()throws Exception{return new JSONObject("{\"id\":\"public-haeundae\",\"name\":\"해운대해수욕장\",\"address\":\"부산 해운대구\",\"lat\":35.1587,\"lon\":129.1604}");}
    private static JSONObject response()throws Exception {
        JSONObject fast=new JSONObject().put("id","fast").put("title","해운대까지 빠른 길").put("reason","같은 목적지까지 빠르게 이동해요.")
                .put("distanceKm",19.5).put("durationMin",31.25).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.1587,129.1604]]"))
                .put("scenicLabels",new JSONArray()).put("waypoints",new JSONArray()).put("source","Kakao Mobility");
        JSONObject scenic=new JSONObject(fast.toString()).put("id","scenic").put("title","해운대까지 풍경 길").put("durationMin",50.5).put("distanceKm",27.4)
                .put("scenicLabels",new JSONArray().put("바다"))
                .put("coordinates",new JSONArray("[[35.1151,129.0414],[35.1300,129.1000],[35.1587,129.1604]]"))
                .put("waypoints",new JSONArray().put(new JSONObject().put("id","12345").put("name","해안 통과 지점")
                        .put("lat",35.13).put("lon",129.10).put("source","한국관광공사 TourAPI")));
        return new JSONObject().put("origin",new JSONObject().put("lat",LAT).put("lon",LON)).put("destination",place())
                .put("routes",new JSONArray().put(fast).put(scenic)).put("scenicStatus","ok").put("message","같은 목적지로 가는 두 경로를 확인했어요.");
    }
    private static JSONObject route(JSONObject json,int i)throws Exception{return json.getJSONArray("routes").getJSONObject(i);}
    private static void reject(Change change,String label)throws Exception {
        JSONObject json=response();change.apply(json);
        try{DestinationRoutePlan.parse(json,destination(),LAT,LON,30,"coast");throw new AssertionError(label);}
        catch(JSONException expected){checks++;}
    }
    private static void rejectSearch(Change change,String label)throws Exception {
        JSONObject json=new JSONObject().put("source","Kakao Local").put("places",new JSONArray().put(place()));change.apply(json);
        try{DestinationPlace.parseSearch(json);throw new AssertionError(label);}catch(JSONException expected){checks++;}
    }
    private static void rejectRequest(Call call,String label)throws Exception {
        try{call.run();throw new AssertionError(label);}catch(ScenicApiClient.ApiException expected){checks++;}
    }
    private static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final byte[] body;boolean disconnected;
        Connection(String body)throws Exception {super(new URL("http://localhost"));this.body=body.getBytes(StandardCharsets.UTF_8);}
        void endpoint(URL value){url=value;}
        public void connect(){}
        public boolean usingProxy(){return false;}
        public void disconnect(){disconnected=true;}
        public int getResponseCode(){return 200;}
        public String getContentType(){return "application/json";}
        public long getContentLengthLong(){return body.length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        public OutputStream getOutputStream(){return output;}
    }
    private static Connection enqueue(String body)throws Exception {Connection connection=new Connection(body);connections.add(connection);return connection;}

    public static void main(String[] args)throws Exception {
        DestinationPlace publicPlace=destination();
        check(publicPlace.source.equals(DestinationPlace.PUBLIC_SOURCE),"Public example never claims a Kakao Local search occurred");
        List<DestinationPlace> searched=DestinationPlace.parseSearch(new JSONObject().put("source","Kakao Local").put("places",new JSONArray().put(place())));
        check(searched.size()==1&&searched.get(0).source.equals("Kakao Local")&&searched.get(0).address.equals("부산 해운대구"),"Search keeps provider provenance and address");
        check(DestinationPlace.parseSearch(new JSONObject("{\"source\":\"Kakao Local\",\"places\":[]}")).isEmpty(),"Empty destination search is legitimate");
        rejectSearch(j->j.put("source","Demo"),"Do not promote demo search data to live source");
        rejectSearch(j->j.getJSONArray("places").put(place()),"Reject duplicate place identities");
        rejectSearch(j->j.getJSONArray("places").getJSONObject(0).put("lat","35.1"),"Reject coerced coordinates");
        rejectSearch(j->j.getJSONArray("places").getJSONObject(0).put("id","bad id"),"Reject unsafe identifiers");
        rejectSearch(j->j.getJSONArray("places").getJSONObject(0).put("name","\n"),"Reject empty/control-text names");
        rejectSearch(j->j.getJSONArray("places").getJSONObject(0).put("lon",-129),"Require Korean destination coordinates");
        rejectSearch(j->{JSONArray items=new JSONArray();for(int i=0;i<16;i++)items.put(place().put("id","p"+i));j.put("places",items);},"Bound search result memory");
        DestinationRoutePlan plan=DestinationRoutePlan.parse(response(),publicPlace,LAT,LON,30,"coast");
        check(plan.destination==publicPlace&&plan.routes.size()==2&&plan.fast.id.equals("fast")&&plan.scenic.id.equals("scenic"),"Preserve selected destination and ordered alternatives");
        check(plan.fast.durationMin==31.25&&plan.scenic.durationMin==50.5,"Preserve exact provider durations for comparison");
        CourseEngine.Course fast=plan.fast.toCourse("부산역 · 공개 예시 출발지"),scenic=plan.scenic.toCourse();
        check(fast.spots.length==2&&scenic.spots.length==3,"A destination trip has origin and destination with only the scenic pass-through added");
        check(fast.spots[0].title.contains("공개 예시")&&fast.spots[0].lat==LAT,"Map origin label cannot masquerade as current GPS");
        check(scenic.spots[2].title.equals(publicPlace.name)&&scenic.spots[2].lon==publicPlace.lon,"The selected destination remains final after the scenic waypoint");
        check(fast.minutes==32&&scenic.minutes==51&&scenic.scene.equals("coast"),"Music/map compatibility preserves destination theme and drive time without stop allowances");
        check(fast.id.length()<=64&&scenic.id.length()<=64&&!fast.id.equals(scenic.id)&&fast.id.equals(plan.fast.toCourse().id),"Stable alternative identities remain within the map payload limit");
        Set<String> themedCourseIds=new HashSet<>(),musicKeys=new HashSet<>();
        for(String theme:new String[]{"all","coast","forest","mountain","water","countryside","city"}){
            DestinationRoutePlan themed=DestinationRoutePlan.parse(response(),publicPlace,LAT,LON,30,theme);
            CourseEngine.Course course=themed.scenic.toCourse();
            MusicContext music=new MusicContext(course.name,course.scene,course.minutes);
            check(themed.theme.equals(theme)&&course.scene.equals(theme)&&music.theme.equals(theme),"Destination scenery reaches music without merging distinct preferences: "+theme);
            CourseEngine.Course fastCourse=themed.fast.toCourse();
            MusicContext fastMusic=new MusicContext(fastCourse.name,fastCourse.scene,fastCourse.minutes);
            check(fastCourse.scene.equals("all")&&fastMusic.theme.equals("all"),"Fast alternative does not claim the selected scenic preference even when a scenic alternative exists: "+theme);
            check(themedCourseIds.add(course.id)&&musicKeys.add(music.key()),"Distinct scenery retains separate course and music identities: "+theme);
        }
        for(String theme:new String[]{null,"","sea","lake","night","Mountain","water ","unknown"}){
            try{DestinationRoutePlan.parse(response(),publicPlace,LAT,LON,30,theme);throw new AssertionError("Reject non-canonical destination theme");}
            catch(JSONException expected){checks++;}
        }
        JSONObject otherWaypoint=response();route(otherWaypoint,1).getJSONArray("waypoints").getJSONObject(0).put("name","다른 해안 통과 지점").put("lat",35.1301);
        DestinationRoutePlan revised=DestinationRoutePlan.parse(otherWaypoint,publicPlace,LAT,LON,30,"coast");
        check(!revised.scenic.toCourse().id.equals(scenic.id)&&revised.fast.toCourse().id.equals(fast.id),"Changed scenic pass-through gets its own moment identity while fast identity stays stable");
        check(scenic.spots[1].description.contains("통과")&&scenic.spots[1].description.contains("TourAPI"),"Waypoint provenance and no-visit semantics stay visible");
        check(plan.fast.source.equals("Kakao Mobility")&&plan.scenic.coordinates.length==3,"Only actual road-provider provenance accepted");
        for(String status:new String[]{"no_candidates","no_feasible_route","provider_unavailable"}){
            JSONObject json=response().put("routes",new JSONArray().put(route(response(),0))).put("scenicStatus",status);
            for(String theme:new String[]{"all","coast","forest","mountain","water","countryside","city"}){
                DestinationRoutePlan onlyFast=DestinationRoutePlan.parse(json,publicPlace,LAT,LON,30,theme);
                check(onlyFast.fast!=null&&onlyFast.scenic==null&&onlyFast.scenicStatus.equals(status),"Keep verified fast route when scenic alternative is unavailable");
                CourseEngine.Course fastOnlyCourse=onlyFast.fast.toCourse();
                MusicContext fastOnlyMusic=new MusicContext(fastOnlyCourse.name,fastOnlyCourse.scene,fastOnlyCourse.minutes);
                check(onlyFast.theme.equals(theme)&&fastOnlyCourse.scene.equals("all")&&fastOnlyMusic.theme.equals("all"),"Unavailable scenery remains a request preference and never becomes fast-route music scenery: "+status+" / "+theme);
                check(fastOnlyCourse.id.equals(DestinationRoutePlan.parse(response(),publicPlace,LAT,LON,30,theme).fast.toCourse().id),"Fast route identity is stable regardless of scenic availability: "+theme);
            }
        }
        JSONObject disabled=response().put("routes",new JSONArray().put(route(response(),0))).put("scenicStatus","disabled");
        check(DestinationRoutePlan.parse(disabled,publicPlace,LAT,LON,0,"all").scenic==null,"Zero extra time explicitly disables scenic search");
        check(DestinationRoutePlan.parse(disabled,publicPlace,LAT,LON,0,"mountain").fast.toCourse().scene.equals("all"),"Disabled scenic search cannot label the fast route with the requested scenery");
        JSONObject boundary=response();route(boundary,1).put("durationMin",61.25);
        check(DestinationRoutePlan.parse(boundary,publicPlace,LAT,LON,30,"coast").scenic.durationMin==61.25,"Exact detour budget boundary accepted");
        JSONObject secondsBoundary=response();route(secondsBoundary,0).put("durationMin",244.0/60);route(secondsBoundary,1).put("durationMin",2044.0/60);
        check(DestinationRoutePlan.parse(secondsBoundary,publicPlace,LAT,LON,30,"coast").scenic!=null,"Whole-second budget boundary survives floating-point division");
        reject(j->route(j,1).put("durationMin",61.250001),"Any time beyond the user detour limit is rejected before rounding");
        reject(j->j.getJSONObject("destination").put("id","other"),"Reject rerouting to a different destination identity");
        reject(j->j.getJSONObject("destination").put("name","다른 목적지"),"Reject changing destination name under the same id");
        reject(j->j.getJSONObject("destination").put("lat",35.16),"Reject changing destination coordinates");
        reject(j->j.getJSONObject("origin").put("lat",35.12),"Reject a route computed from a different origin");
        reject(j->j.put("scenicStatus","unknown"),"Reject unknown response status");
        reject(j->j.put("scenicStatus","no_candidates"),"Reject unavailable scenic status paired with a scenic route");
        reject(j->j.put("routes",new JSONArray().put(route(j,1)).put(route(j,0))),"Require fast baseline before scenic comparison");
        reject(j->j.put("routes",new JSONArray()),"A response cannot succeed without the fast route");
        reject(j->j.getJSONArray("routes").put(route(j,0)),"Bound route alternative count");
        reject(j->route(j,0).put("source","offline"),"Reject unverified road geometry");
        reject(j->route(j,0).put("distanceKm","19.5"),"Reject number coercion");
        reject(j->route(j,0).put("distanceKm",1501),"Bound route distance");
        reject(j->route(j,0).put("durationMin",721),"Bound time supported by music and route display");
        reject(j->route(j,0).put("durationMin",0),"Reject zero travel time");
        reject(j->route(j,0).put("coordinates",new JSONArray("[[35.1151,129.0414]]")),"Reject one-point geometry");
        reject(j->route(j,0).put("coordinates",new JSONArray("[[35.3,129.04],[35.1587,129.1604]]")),"Reject missing requested origin approach");
        reject(j->route(j,0).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.3,129.16]]")),"Reject geometry ending at another place");
        reject(j->route(j,0).put("coordinates",new JSONArray("[[35.1151,129.0414,0],[35.1587,129.1604]]")),"Reject malformed coordinate tuples");
        reject(j->route(j,0).put("coordinates",new JSONArray("[[129.0414,35.1151],[129.1604,35.1587]]")),"Reject reversed coordinates");
        reject(j->route(j,1).put("waypoints",new JSONArray()),"Scenic route needs a verified pass-through");
        reject(j->route(j,0).put("waypoints",route(j,1).getJSONArray("waypoints")),"Fast route cannot silently include sightseeing stops");
        reject(j->route(j,1).getJSONArray("waypoints").getJSONObject(0).put("lat",35.5),"Reject scenic waypoint absent from road geometry");
        reject(j->route(j,1).getJSONArray("waypoints").getJSONObject(0).put("source","AI"),"Reject invented waypoint provenance");
        reject(j->route(j,1).put("scenicLabels",new JSONArray().put("바다").put("바다")),"Reject repeated scenery labels");
        reject(j->route(j,1).put("title","bad\nlabel"),"Reject control characters in display text");
        JSONObject sparse=response();route(sparse,1).put("coordinates",new JSONArray("[[35.1151,129.0414],[35.13,129.09],[35.13,129.11],[35.1587,129.1604]]"));
        check(DestinationRoutePlan.parse(sparse,publicPlace,LAT,LON,30,"coast").scenic!=null,"Pass-through lies on a segment even when no vertex is nearby");

        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("http")&&!protocol.equals("https")?null:new URLStreamHandler(){
            protected java.net.URLConnection openConnection(URL url){Connection next=connections.remove();next.endpoint(url);return next;}
        });
        ScenicApiClient client=new ScenicApiClient("http://10.0.2.2:4173");
        Connection search=enqueue("{\"source\":\"Kakao Local\",\"places\":[]}");client.searchDestinations("  해운대 & 해변  ");
        check(search.getRequestMethod().equals("GET")&&search.getURL().getPath().equals("/api/destinations"),"Destination search uses text-only GET");
        check(URLDecoder.decode(search.getURL().getQuery(),"UTF-8").equals("q=해운대 & 해변"),"Encode query delimiters without adding parameters");
        check(search.output.size()==0&&search.getRequestProperty("Authorization")==null,"Search transmits no GPS body or provider credential");
        Connection request=enqueue(response().toString());client.destinationRoutes(LAT,LON,publicPlace,30,"coast");
        JSONObject body=new JSONObject(new String(request.output.toByteArray(),StandardCharsets.UTF_8));
        check(request.getRequestMethod().equals("POST")&&request.getURL().getPath().equals("/api/destination-routes"),"Route comparison uses explicit endpoint");
        check(body.length()==4&&body.getJSONObject("origin").length()==2&&body.getJSONObject("destination").length()==4,"Send only explicit origin, chosen destination, extra time and preference");
        check(body.getJSONObject("origin").getDouble("lat")==LAT&&body.getJSONObject("destination").getString("id").equals(publicPlace.id),"Preserve explicitly supplied public origin and destination identity");
        check(body.getInt("extraMinutes")==30&&body.getString("theme").equals("coast"),"Keep detour time separate from a total-trip budget");
        check(request.getReadTimeout()==70000&&!request.getInstanceFollowRedirects()&&!request.getUseCaches()&&request.disconnected,"Provider comparison allows the server 50-second deadline and cleanup margin");
        for(String theme:new String[]{"all","coast","forest","mountain","water","countryside","city"}){
            Connection themedRequest=enqueue(response().toString());
            client.destinationRoutes(LAT,LON,publicPlace,30,theme);
            JSONObject payload=new JSONObject(new String(themedRequest.output.toByteArray(),StandardCharsets.UTF_8));
            check(payload.getString("theme").equals(theme),"Transmit selected destination theme unchanged: "+theme);
        }
        rejectRequest(()->client.searchDestinations(" "),"Reject empty query locally");
        rejectRequest(()->client.searchDestinations("secret\nquery"),"Reject control query locally");
        rejectRequest(()->client.searchDestinations(new String(new char[81]).replace('\0','a')),"Bound search query");
        rejectRequest(()->client.destinationRoutes(Double.NaN,LON,publicPlace,30,"all"),"Reject invalid origin before connecting");
        rejectRequest(()->client.destinationRoutes(LAT,LON,null,30,"all"),"Require chosen destination");
        rejectRequest(()->client.destinationRoutes(LAT,LON,publicPlace,-1,"all"),"Reject negative detour budget");
        rejectRequest(()->client.destinationRoutes(LAT,LON,publicPlace,121,"all"),"Bound detour budget");
        for(String theme:new String[]{null,"","sea","lake","night","Mountain","water ","unknown"})
            rejectRequest(()->client.destinationRoutes(LAT,LON,publicPlace,30,theme),"Require backend canonical theme before connecting");
        check(connections.isEmpty(),"No hidden retries or secondary requests");
        System.out.println("DestinationRoutesTest: "+checks+" checks passed");
    }
}
