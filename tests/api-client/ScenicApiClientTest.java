package com.scenic.pleos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the real client against a controlled HTTP boundary, never a server or paid API. */
public final class ScenicApiClientTest {
    private static final Deque<FakeConnection> responses = new ConcurrentLinkedDeque<>();
    private static int checks;
    private interface Call { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static final class ClosedInput extends ByteArrayInputStream {
        boolean closed;
        ClosedInput(byte[] data) { super(data); }
        @Override public void close() { closed = true; }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ClosedInput input;
        volatile boolean disconnected;
        boolean outputClosed, blockResponse;
        int errorStreamReads, inputStreamReads;
        final CountDownLatch responseStarted = new CountDownLatch(1), connectionClosed = new CountDownLatch(1);
        String contentType = "application/json; charset=utf-8";
        long declaredLength = -1;
        IOException failure;
        IOException outputFailure;
        FakeConnection(int status, byte[] body) throws Exception {
            super(new URL("http://localhost")); responseCode = status; input = new ClosedInput(body);
        }
        void setEndpoint(URL endpoint) { url = endpoint; }
        @Override public void connect() {}
        @Override public void disconnect() { disconnected = true; connectionClosed.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() throws IOException {
            if (blockResponse) {
                responseStarted.countDown();
                // Model a blocking socket read: Future.cancel(true) cannot release it.
                boolean interrupted = false;
                for (;;) try { connectionClosed.await(); break; }
                catch (InterruptedException expected) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (failure != null) throw failure;
            return responseCode;
        }
        @Override public String getContentType() { return contentType; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public InputStream getInputStream() { inputStreamReads++; return input; }
        @Override public InputStream getErrorStream() { errorStreamReads++; return responseCode >= 400 ? input : null; }
        @Override public OutputStream getOutputStream() throws IOException {
            if (outputFailure != null) throw outputFailure;
            return new OutputStream() {
                @Override public void write(int b) { output.write(b); }
                @Override public void close() { outputClosed = true; }
            };
        }
    }
    private static FakeConnection respond(int status, String body) throws Exception {
        FakeConnection connection = new FakeConnection(status, body.getBytes(StandardCharsets.UTF_8));
        responses.add(connection); return connection;
    }
    private static byte[] paddedJson(int length) {
        byte[] body=new byte[length];Arrays.fill(body,(byte)' ');
        byte[] object="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(object,0,body,0,object.length);return body;
    }
    private static void failsSafely(Call call, FakeConnection connection) throws Exception {
        try { call.run(); throw new AssertionError("Expected safe API failure"); }
        catch (ScenicApiClient.ApiException expected) {
            check(expected.getMessage() != null && !expected.getMessage().contains("secret"), "No raw response or exception may leak");
            check(expected.getCause() == null, "Do not retain sensitive causes");
        }
        if (connection != null) check(connection.disconnected, "Disconnect failed request");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            @Override public URLStreamHandler createURLStreamHandler(String protocol) {
                if (!protocol.equals("http") && !protocol.equals("https")) return null;
                return new URLStreamHandler() {
                    @Override protected java.net.URLConnection openConnection(URL url) {
                        FakeConnection next = responses.removeFirst(); next.setEndpoint(url); return next;
                    }
                };
            }
        });
        ScenicApiClient client = new ScenicApiClient("http://10.0.2.2:4173/");
        FakeConnection status = respond(200, "{\"ai\":{\"configured\":true,\"label\":\"OpenAI · 서버 연결 준비\"},\"routing\":{\"configured\":false,\"label\":\"Kakao Mobility REST 키 필요\"}}");
        check(client.getStatus().getJSONObject("ai").getBoolean("configured"), "Return parsed status");
        check(status.getURL().toString().equals("http://10.0.2.2:4173/api/status"), "Status endpoint");
        check(status.getRequestMethod().equals("GET"), "Status method");
        check(status.getConnectTimeout() == 5000 && status.getReadTimeout() == 20000, "Bound network wait");
        check(!status.getInstanceFollowRedirects(), "Do not follow redirected requests");
        check(!status.getUseCaches(), "Do not cache provider status");
        check(status.getRequestProperty("Authorization") == null, "No key-bearing auth header");
        check(status.disconnected && status.input.closed, "Close successful response");

        String[] preferences = {"lake", "sea", "night"};
        String[] themes = {"lake", "coast", "city"};
        for (int i = 0; i < preferences.length; i++) {
            FakeConnection recommendation = respond(200, "{\"source\":\"OpenAI\",\"recommendations\":[{\"routeId\":\"seoul\",\"reason\":\"도시의 야경\"}],\"model\":\"example\",\"generatedAt\":\"2026-11-04T00:00:00Z\"}");
            check(client.recommend(120, preferences[i]).getString("source").equals("OpenAI"), "Return recommendation response");
            check(recommendation.getURL().getPath().equals("/api/android/recommend"), "Native catalog endpoint");
            check(recommendation.getRequestMethod().equals("POST") && recommendation.outputClosed, "Close JSON request stream");
            JSONObject payload = new JSONObject(new String(recommendation.output.toByteArray(), StandardCharsets.UTF_8));
            JSONArray ids = payload.getJSONArray("routeIds");
            check(ids.length() > 0 && ids.length() <= 12, "Cap candidates at 12, not entire nationwide catalog");
            for (int j=0; j<ids.length(); j++) check(CourseEngine.byId(ids.getString(j)) != null, "Use known native catalog IDs");
            JSONObject prefs = payload.getJSONObject("preferences");
            check(prefs.getString("theme").equals(themes[i]) && prefs.getInt("maxMinutes") == 120 && prefs.getString("region").equals("all") && prefs.getString("query").isEmpty(), "Map scenery and preserve budget");
            check(payload.length() == 2 && prefs.length() == 4, "Only documented recommendation fields");
        }
        FakeConnection filtered = respond(200, "{\"source\":\"OpenAI\",\"recommendations\":[]}");
        List<String> candidateIds = Arrays.asList(CourseEngine.ranked(120, "all").get(0).id, CourseEngine.ranked(120, "all").get(1).id);
        client.recommend(120, "all", candidateIds);
        JSONObject filteredPayload = new JSONObject(new String(filtered.output.toByteArray(), StandardCharsets.UTF_8));
        check(filteredPayload.getJSONArray("routeIds").length() == 2 && filteredPayload.getJSONArray("routeIds").getString(0).equals(candidateIds.get(0)), "Send only local candidate IDs in chosen order");
        check(!filteredPayload.toString().contains("latitude") && !filteredPayload.toString().contains("longitude") && !filteredPayload.has("position"), "Never send raw current GPS");
        failsSafely(() -> client.recommend(120, "all", new ArrayList<String>()), null);
        failsSafely(() -> client.recommend(120, "all", Arrays.asList("unknown-course")), null);
        failsSafely(() -> client.recommend(120, "all", Arrays.asList(candidateIds.get(0), candidateIds.get(0))), null);
        List<String> tooMany = new ArrayList<>(); for (int i=0; i<13; i++) tooMany.add(CourseEngine.COURSES[i].id);
        failsSafely(() -> client.recommend(720, "all", tooMany), null);
        CourseEngine.Course routeCourse = CourseEngine.byId("seoul");
        check(routeCourse != null, "Preserve saved legacy course ID");
        CourseEngine.Position freshOrigin = new CourseEngine.Position(37.5,126.98,true,10,100_000_000_000L,101_000_000_000L);
        FakeConnection live = respond(200, "{\"status\":\"no_candidates\"}");
        client.plan(freshOrigin,90,"sea",20000);
        JSONObject liveBody = new JSONObject(new String(live.output.toByteArray(),StandardCharsets.UTF_8));
        check(live.getURL().getPath().equals("/api/android/plan")&&live.getRequestMethod().equals("POST"),"Explicit live plan endpoint");
        check(live.getReadTimeout()==90000&&live.getConnectTimeout()==5000,"Live pipeline gets bounded 90-second response window");
        check(liveBody.length()==3&&liveBody.getJSONObject("origin").length()==2&&liveBody.getJSONObject("origin").getDouble("lat")==37.5,"Send only explicit origin coordinates");
        check(liveBody.getJSONObject("preferences").length()==2&&liveBody.getJSONObject("preferences").getString("theme").equals("coast")&&liveBody.getInt("radiusMeters")==20000,"Map native theme and preserve planned radius");
        FakeConnection publicOrigin = respond(200, "{\"status\":\"no_candidates\"}");
        client.planAtSeoulCityHall(90,"all");
        JSONObject publicBody = new JSONObject(new String(publicOrigin.output.toByteArray(),StandardCharsets.UTF_8));
        check(publicBody.getJSONObject("origin").getDouble("lat")==37.5665&&publicBody.getJSONObject("origin").getDouble("lon")==126.9780,"Explicit public demo uses fixed City Hall origin independently of Android GPS");
        CourseEngine.Position staleOrigin = new CourseEngine.Position(37.5,126.98,true,10,1,40_000_000_000L);
        failsSafely(() -> client.plan(staleOrigin,90,"all",20000),null);
        failsSafely(() -> client.plan(null,90,"all",20000),null);
        failsSafely(() -> client.plan(new CourseEngine.Position(37,-122,true,10,100,101),90,"all",20000),null);
        failsSafely(() -> client.plan(freshOrigin,0,"all",20000),null);
        failsSafely(() -> client.plan(freshOrigin,90,"secret",20000),null);
        failsSafely(() -> client.plan(freshOrigin,90,"all",1),null);
        FakeConnection music = respond(200, "{\"source\":\"OpenAI\"}");
        check(client.music(new MusicContext("호수 드라이브", "lake", 90)).getString("source").equals("OpenAI"), "Return music response for strict playlist parsing");
        JSONObject musicBody = new JSONObject(new String(music.output.toByteArray(),StandardCharsets.UTF_8));
        JSONObject musicContext = musicBody.getJSONObject("context");
        check(music.getURL().getPath().equals("/api/android/music")&&music.getRequestMethod().equals("POST"),"Music uses explicit POST endpoint");
        check(musicBody.length()==1&&musicContext.length()==3,"Music payload only contains coarse title, theme and duration");
        check(musicContext.getString("title").equals("호수 드라이브")&&musicContext.getString("theme").equals("lake")&&musicContext.getInt("durationMinutes")==90,"Music context preserved");
        check(music.getReadTimeout()==30000&&music.getConnectTimeout()==5000,"Music timeout exceeds the server's 20-second recommendation window");
        check(!music.getInstanceFollowRedirects()&&!music.getUseCaches()&&music.outputClosed&&music.input.closed&&music.disconnected,"Music transport closes streams and does not redirect or cache");
        check(music.getRequestProperty("Authorization")==null,"No provider credential in music request");
        for(String[] mapping:new String[][]{{"sea","coast"},{"night","city"},{"all","all"},{"coast","coast"},{"forest","forest"},{"mountain","mountain"},{"water","water"},{"countryside","countryside"},{"city","city"}}){
            FakeConnection themedMusic=respond(200,"{}");client.music(new MusicContext("일반 드라이브",mapping[0],1));
            JSONObject coarse=new JSONObject(new String(themedMusic.output.toByteArray(),StandardCharsets.UTF_8)).getJSONObject("context");
            check(coarse.getString("theme").equals(mapping[1]),"Map native scenery for music");
        }
        failsSafely(()->client.music(null),null);
        FakeConnection musicUnavailable=respond(503,"{\"error\":\"secret-provider-key\"}");
        failsSafely(()->client.music(new MusicContext("일반 드라이브","all",90)),musicUnavailable);
        FakeConnection musicTimeout=respond(200,"{}");musicTimeout.failure=new SocketTimeoutException("secret");
        failsSafely(()->client.music(new MusicContext("일반 드라이브","all",90)),musicTimeout);
        FakeConnection route = respond(200, "{\"coordinates\":[[37.51,126.99],[37.52,127.01]],\"distanceKm\":2.1,\"durationMin\":5,\"source\":\"Kakao Mobility\",\"fetchedAt\":\"2026-11-04T00:00:00Z\"}");
        check(client.route(routeCourse).getDouble("distanceKm") == 2.1, "Return road route");
        JSONObject routePayload = new JSONObject(new String(route.output.toByteArray(), StandardCharsets.UTF_8));
        JSONArray stops = routePayload.getJSONArray("stops");
        check(route.getURL().getPath().equals("/api/route") && stops.length() == 4, "Send route stops");
        check(stops.getJSONObject(0).getDouble("lat") == 37.5100 && stops.getJSONObject(0).getDouble("lon") == 126.9959, "Latitude/longitude order");
        check(routePayload.length() == 1 && stops.getJSONObject(0).length() == 2, "Do not forward location metadata");
        failsSafely(() -> client.recommend(0, "lake"), null);
        failsSafely(() -> client.recommend(721, "lake"), null);
        failsSafely(() -> client.recommend(60, "secret"), null);
        failsSafely(() -> client.route(null), null);
        CourseEngine.Course invalid = new CourseEngine.Course("invalid", "", "", "", "", "", 60,
                new CourseEngine.Spot("", "", Double.NaN, 127), new CourseEngine.Spot("", "", 37, 127));
        failsSafely(() -> client.route(invalid), null);

        FakeConnection redirect = respond(302, "{\"error\":\"secret\"}");
        failsSafely(() -> client.getStatus(), redirect);
        FakeConnection unavailable = respond(503, "{\"error\":\"secret-key-upstream\"}");
        failsSafely(() -> client.getStatus(), unavailable);
        check(unavailable.input.closed, "Close error response");
        FakeConnection gatewayTimeout = respond(504, "{\"error\":\"secret-provider-timeout\"}");
        try { client.route(routeCourse); throw new AssertionError("Expected route timeout failure"); }
        catch (ScenicApiClient.ApiException expected) {
            check("서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.".equals(expected.getMessage()), "HTTP 504 explains a temporary service delay without exposing upstream details");
            check(expected.getCause() == null, "Gateway timeout keeps no upstream exception cause");
        }
        check(gatewayTimeout.input.closed && gatewayTimeout.disconnected, "Close gateway timeout response and connection");
        FakeConnection malformed = respond(200, "secret invalid JSON");
        failsSafely(() -> client.getStatus(), malformed);
        check(malformed.input.closed, "Close malformed response");
        FakeConnection trailing = respond(200, "{} secret");
        failsSafely(() -> client.getStatus(), trailing);
        FakeConnection wrongShape = respond(200, "[]");
        failsSafely(() -> client.getStatus(), wrongShape);
        FakeConnection wrongType = respond(200, "{}"); wrongType.contentType = "text/html";
        failsSafely(() -> client.getStatus(), wrongType);
        FakeConnection oversized = new FakeConnection(200, new byte[2 * 1024 * 1024 + 1]); responses.add(oversized);
        failsSafely(() -> client.getStatus(), oversized);
        check(oversized.input.closed, "Close oversized response");
        FakeConnection hugeDeclared = respond(200, "{}"); hugeDeclared.declaredLength = 3 * 1024 * 1024;
        failsSafely(() -> client.getStatus(), hugeDeclared);
        DestinationPlace destination=DestinationPlace.of("public-haeundae","해운대해수욕장","부산 해운대구",35.1587,129.1604);
        byte[] destinationBoundary=paddedJson(6*1024*1024);
        FakeConnection largeDestination=new FakeConnection(200,destinationBoundary);largeDestination.declaredLength=destinationBoundary.length;responses.add(largeDestination);
        check(client.destinationRoutes(35.1151,129.0414,destination,30,"coast").getBoolean("ok"),"Destination comparison accepts a successful JSON response exactly at 6 MiB");
        check(largeDestination.input.closed&&largeDestination.disconnected,"Close the larger destination response after parsing");
        FakeConnection streamedDestination=new FakeConnection(200,destinationBoundary);responses.add(streamedDestination);
        check(client.destinationRoutes(35.1151,129.0414,destination,30,"coast").getBoolean("ok"),"Unknown-length destination response also accepts exactly 6 MiB");
        FakeConnection destinationTooLarge=new FakeConnection(200,paddedJson(6*1024*1024+1));responses.add(destinationTooLarge);
        failsSafely(()->client.destinationRoutes(35.1151,129.0414,destination,30,"coast"),destinationTooLarge);
        check(destinationTooLarge.input.closed,"Close streamed destination response when it exceeds 6 MiB");
        FakeConnection destinationDeclaredTooLarge=respond(200,"{}");destinationDeclaredTooLarge.declaredLength=6*1024*1024+1;
        failsSafely(()->client.destinationRoutes(35.1151,129.0414,destination,30,"coast"),destinationDeclaredTooLarge);
        check(destinationDeclaredTooLarge.inputStreamReads==0,"Reject an oversized declared destination response before reading its body");
        FakeConnection ordinaryRoute=new FakeConnection(200,paddedJson(2*1024*1024+1));responses.add(ordinaryRoute);
        failsSafely(()->client.route(routeCourse),ordinaryRoute);
        check(ordinaryRoute.input.closed,"Ordinary route endpoint retains its streamed 2 MiB cap");
        FakeConnection searchTooLarge=respond(200,"{}");searchTooLarge.declaredLength=2*1024*1024+1;
        failsSafely(()->client.searchDestinations("해운대"),searchTooLarge);
        check(searchTooLarge.inputStreamReads==0,"Destination search retains its declared 2 MiB cap");
        FakeConnection failedDestination=new FakeConnection(503,destinationBoundary);responses.add(failedDestination);
        failsSafely(()->client.destinationRoutes(35.1151,129.0414,destination,30,"coast"),failedDestination);
        check(failedDestination.inputStreamReads==0&&failedDestination.input.available()==destinationBoundary.length&&failedDestination.input.closed,"Large unsuccessful destination response is never parsed or consumed");
        FakeConnection timeout = respond(200, "{}"); timeout.failure = new SocketTimeoutException("secret-url");
        failsSafely(() -> client.getStatus(), timeout);
        FakeConnection writeFailure = respond(200, "{}"); writeFailure.outputFailure = new IOException("secret-write-error");
        failsSafely(() -> client.recommend(60, "lake"), writeFailure);
        StringBuilder deep = new StringBuilder("{\"value\":");
        for (int i = 0; i < 70; i++) deep.append('[');
        deep.append('0');
        for (int i = 0; i < 70; i++) deep.append(']');
        deep.append('}');
        FakeConnection tooDeep = respond(200, deep.toString());
        failsSafely(() -> client.getStatus(), tooDeep);
        FakeConnection nonJsonQuoting = respond(200, "{'value': 'secret'}");
        failsSafely(() -> client.getStatus(), nonJsonQuoting);
        String[] permissiveJson = {"{ai:{configured:true}}", "{\"ai\"={\"configured\"=>true}}", "{}\u0000secret", "{\"number\":01}", "{\"number\":1.}", "{\"a\":true,}", "{\"a\":[1,]}"};
        for (String body : permissiveJson) {
            FakeConnection nonJson = respond(200, body);
            failsSafely(() -> client.getStatus(), nonJson);
        }
        FakeConnection escapedText = respond(200, "{\"text\":\"brace [ and quoted \\\"text\\\" and slash /\",\"number\":-1.25e+2,\"values\":[true,false,null]}");
        check(client.getStatus().getDouble("number") == -125, "Accept standard JSON strings, numbers and literals");
        // A cancelled executor task must not start another HTTP request when it reaches the client.
        FakeConnection interrupted = respond(200, "{}");
        Thread.currentThread().interrupt();
        try { failsSafely(() -> client.getStatus(), null); }
        finally { Thread.interrupted(); }
        check(responses.peekFirst() == interrupted, "Interrupted request never opens a connection");
        responses.removeFirst();
        ScenicApiClient cancelledBeforeStart = new ScenicApiClient("http://10.0.2.2:4173");
        cancelledBeforeStart.cancel();
        FakeConnection neverOpened = respond(200, "{}");
        failsSafely(cancelledBeforeStart::getStatus, null);
        check(responses.removeFirst() == neverOpened && !neverOpened.disconnected, "Cancelled queued owner never starts HTTP");

        ExecutorService workers = Executors.newFixedThreadPool(2);
        ScenicApiClient firstOwner = new ScenicApiClient("http://10.0.2.2:4173"), secondOwner = new ScenicApiClient("http://10.0.2.2:4173");
        FakeConnection firstPending = respond(200, "{}"), secondPending = respond(200, "{}");
        firstPending.blockResponse = true; secondPending.blockResponse = true;
        CountDownLatch tasksExited = new CountDownLatch(2);
        try {
            Future<?> firstTask = workers.submit(() -> { try { firstOwner.getStatus(); throw new AssertionError("Cancelled response applied"); } catch (ScenicApiClient.ApiException expected) {} finally { tasksExited.countDown(); } });
            Future<?> secondTask = workers.submit(() -> { try { secondOwner.getStatus(); throw new AssertionError("Cancelled response applied"); } catch (ScenicApiClient.ApiException expected) {} finally { tasksExited.countDown(); } });
            check(firstPending.responseStarted.await(2, TimeUnit.SECONDS) && secondPending.responseStarted.await(2, TimeUnit.SECONDS), "Both API workers entered their blocking response reads");
            firstTask.cancel(true); secondTask.cancel(true);
            firstOwner.cancel(); secondOwner.cancel();
            check(tasksExited.await(2, TimeUnit.SECONDS), "Cancelling active clients releases both worker sockets");
            FakeConnection retry = respond(200, "{\"ready\":true}");
            check(workers.submit(() -> new ScenicApiClient("http://10.0.2.2:4173").getStatus()).get(2, TimeUnit.SECONDS).getBoolean("ready"), "New request runs immediately after two cancellations");
            check(firstPending.disconnected && secondPending.disconnected && retry.disconnected, "Cancelled and replacement connections are closed");
            check(firstPending.errorStreamReads == 0 && secondPending.errorStreamReads == 0, "Cancelled reads do not fetch an error response during cleanup");
        } finally { firstOwner.cancel(); secondOwner.cancel(); workers.shutdownNow(); }
        check(responses.isEmpty(), "No hidden retry or upstream requests");
        System.out.println("ScenicApiClientTest: " + checks + " checks passed");
    }
}
