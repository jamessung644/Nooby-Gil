package com.scenic.pleos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Synchronous backend transport. Call on a background executor, never the UI thread. */
public final class ScenicApiClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DESTINATION_RESPONSE_BYTES = 6 * 1024 * 1024;
    private final String baseUrl;
    private volatile boolean cancelled;
    private HttpURLConnection activeConnection;

    /** Contains a safe message and HTTP status, never an upstream body or exception cause. */
    public static final class ApiException extends IOException {
        /** Zero for local validation, transport and response-format failures. */
        public final int statusCode;
        ApiException(String message) { this(message, 0); }
        ApiException(String message, int statusCode) { super(message); this.statusCode = statusCode; }
    }

    public ScenicApiClient(String baseUrl) {
        this.baseUrl = EndpointPolicy.normalizeBaseUrl(baseUrl);
    }

    /** Cancels this request owner permanently, including a blocking Android socket read. */
    public void cancel() {
        HttpURLConnection connection;
        synchronized (this) { cancelled = true; connection = activeConnection; }
        // Interrupting a Future alone does not close HttpURLConnection's socket.
        if (connection != null) connection.disconnect();
    }

    private void requireActive() throws ApiException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new ApiException("요청을 취소했습니다. 다시 시도해 주세요.");
    }

    public JSONObject getStatus() throws ApiException {
        return request("GET", "/api/status", null);
    }

    /** Fresh public inventory around the selected meal/parking anchor, including a focused lot. */
    public JSONObject nearbyParking(DestinationPlace anchor,String focusId)throws ApiException {
        if(anchor==null||focusId==null||!focusId.matches("[0-9]{1,100}"))throw new ApiException("주차 확인 장소를 다시 선택해 주세요.");
        return request("GET","/api/nearby?kind=parking&lat="+anchor.lat+"&lon="+anchor.lon+"&focusId="+focusId,null,30000);
    }

    /** Text-only destination search; this sends neither an origin nor device GPS. */
    public JSONObject searchDestinations(String query)throws ApiException {
        if(query==null||query.trim().isEmpty()||query.trim().length()>80||MusicContext.hasControl(query))
            throw new ApiException("목적지 이름을 1~80자로 입력해 주세요.");
        try{return request("GET","/api/destinations?q="+URLEncoder.encode(query.trim(),"UTF-8"),null);}
        catch(java.io.UnsupportedEncodingException impossible){throw new ApiException("목적지 검색 요청을 만들지 못했습니다.");}
    }

    /** Call only for a user-chosen origin, including an explicitly labeled public example. */
    public JSONObject destinationRoutes(double originLat,double originLon,DestinationPlace destination,
                                       int extraMinutes,String theme)throws ApiException {
        return destinationRoutes(originLat,originLon,destination,extraMinutes,theme,null);
    }

    /** The server-issued selection is bound to this exact destination and scenic place. */
    public JSONObject destinationRoutes(double originLat,double originLon,DestinationPlace destination,
                                       int extraMinutes,String theme,String scenicSelection)throws ApiException {
        return destinationRoutes(originLat,originLon,destination,extraMinutes,theme,scenicSelection,false);
    }
    public JSONObject photoDestinationRoutes(double originLat,double originLon,DestinationPlace region,String theme,String selection)throws ApiException {
        return photoDestinationRoutes(originLat,originLon,region,theme,selection,0);
    }
    /** With extra minutes the server also offers a course road and a scenic road to the same photo place. */
    public JSONObject photoDestinationRoutes(double originLat,double originLon,DestinationPlace region,String theme,String selection,int extraMinutes)throws ApiException {
        if(selection==null)throw new ApiException("방문할 풍경 사진을 먼저 선택해 주세요.");
        return destinationRoutes(originLat,originLon,region,extraMinutes,theme,selection,true);
    }
    private JSONObject destinationRoutes(double originLat,double originLon,DestinationPlace destination,
                                       int extraMinutes,String theme,String scenicSelection,boolean photoDestination)throws ApiException {
        if(!CourseEngine.inKorea(originLat,originLon)||CourseEngine.unsupportedIslandOrigin(originLat,originLon))
            throw new ApiException("국내에서 자동차로 이동할 출발 위치를 확인해 주세요.");
        if(destination==null)throw new ApiException("도착할 목적지를 먼저 선택해 주세요.");
        if(extraMinutes<0||extraMinutes>120)throw new ApiException("풍경 길에 더 쓸 시간은 0~120분으로 선택해 주세요.");
        if(!DestinationRoutePlan.validTheme(theme))throw new ApiException("전체, 바다, 숲길, 산길, 호수·강변, 시골길, 도심 중 풍경을 선택해 주세요.");
        if(scenicSelection!=null&&!ScenicPhotoCollection.validSelectionToken(scenicSelection))throw new ApiException("사진 선택이 유효하지 않아요. 목적지 주변 사진을 새로 불러와 주세요.");
        try{
            JSONObject body=new JSONObject().put("origin",new JSONObject().put("lat",originLat).put("lon",originLon))
                    .put("destination",new JSONObject().put("id",destination.id).put("name",destination.name)
                            .put("lat",destination.lat).put("lon",destination.lon))
                    .put("extraMinutes",extraMinutes).put("theme",theme);
            if(scenicSelection!=null)body.put("scenicSelection",scenicSelection);
            if(photoDestination)body.put("mode","photo-destination");
            return request("POST","/api/destination-routes",body,70000);
        }catch(JSONException e){throw new ApiException("목적지 경로 요청을 만들지 못했습니다. 조건을 다시 확인해 주세요.");}
    }

    /** Requests public photos around a chosen destination; never sends current GPS. */
    public JSONObject scenicPhotos(DestinationPlace destination,int radiusMeters)throws ApiException {
        if(destination==null)throw new ApiException("사진을 찾아볼 목적지를 먼저 선택해 주세요.");
        if(!ScenicPhotoCollection.validRadius(radiusMeters))throw new ApiException("사진 탐색 범위를 5·10·20km 중에서 선택해 주세요.");
        try{return request("POST","/api/scenic-photos",new JSONObject()
                .put("destination",new JSONObject().put("id",destination.id).put("name",destination.name)
                        .put("address",destination.address).put("lat",destination.lat).put("lon",destination.lon))
                .put("radiusMeters",radiusMeters),90000);
        }catch(JSONException error){throw new ApiException("사진 검색 요청을 만들지 못했어요. 목적지를 다시 확인해 주세요.");}
    }

    /** AI chooses an actual playlist; this request contains no GPS or route-stop coordinates. */
    public JSONObject music(MusicContext context) throws ApiException {
        if(context==null)throw new ApiException("음악을 추천할 드라이브 조건을 다시 확인해 주세요.");
        try{
            JSONObject coarseContext=new JSONObject().put("title",context.title).put("theme",context.theme).put("durationMinutes",context.durationMinutes);
            if(!"balanced".equals(context.preference))coarseContext.put("preference",context.preference);
            return request("POST","/api/android/music",new JSONObject().put("context",coarseContext),30000);
        }catch(JSONException e){throw new ApiException("음악 추천 요청을 만들지 못했습니다. 다시 시도해 주세요.");}
    }

    public JSONObject recommend(int budget, String preference) throws ApiException {
        if (budget < 1 || budget > 720) throw new ApiException("추천 시간은 1~720분으로 선택해 주세요.");
        List<String> candidateIds = new ArrayList<>();
        for (CourseEngine.Course course : CourseEngine.ranked(budget, preference)) candidateIds.add(course.id);
        return recommend(budget, preference, candidateIds);
    }

    /** Sends only locally selected catalog IDs, never the device's current GPS. */
    public JSONObject recommend(int budget, String preference, List<String> candidateIds) throws ApiException {
        if (budget < 1 || budget > 720) throw new ApiException("추천 시간은 1~720분으로 선택해 주세요.");
        String theme;
        if ("sea".equals(preference)) theme = "coast";
        else if ("night".equals(preference)) theme = "city";
        else if ("lake".equals(preference) || "coast".equals(preference)
                || "city".equals(preference) || "forest".equals(preference) || "all".equals(preference)) theme = preference;
        else throw new ApiException("추천할 풍경 취향을 선택해 주세요.");
        if (candidateIds == null || candidateIds.isEmpty() || candidateIds.size() > CourseEngine.DISPLAY_LIMIT)
            throw new ApiException("기기에서 고른 코스 1~12개를 먼저 확인해 주세요.");
        Set<String> uniqueIds = new HashSet<>();
        for (String id : candidateIds) {
            CourseEngine.Course course = CourseEngine.byId(id);
            if (course == null || course.minutes > budget || !uniqueIds.add(id))
                throw new ApiException("현재 조건에 맞는 코스를 다시 선택해 주세요.");
        }
        try {
            JSONArray routeIds = new JSONArray();
            for (String id : candidateIds) routeIds.put(id);
            JSONObject preferences = new JSONObject().put("region", "all").put("theme", theme)
                    .put("maxMinutes", budget).put("query", "");
            return request("POST", "/api/android/recommend", new JSONObject()
                    .put("routeIds", routeIds).put("preferences", preferences));
        } catch (JSONException e) {
            throw new ApiException("추천 요청을 만들지 못했습니다. 조건을 다시 선택해 주세요.");
        }
    }

    /** Called only after the user explicitly requests an itinerary from a recent device fix. */
    public JSONObject plan(CourseEngine.Position origin,int budget,String preference,int radiusMeters) throws ApiException {
        if(origin==null||!origin.fresh())throw new ApiException("최근 위치를 받은 뒤 다시 코스를 만들어 주세요.");
        return planAt(origin.lat,origin.lon,budget,preference,radiusMeters,false,15,false);
    }
    public JSONObject plan(CourseEngine.Position origin,int budget,String preference,int radiusMeters,boolean returnToOrigin,int stayMinutes) throws ApiException {
        if(origin==null||!origin.fresh())throw new ApiException("최근 위치를 받은 뒤 다시 코스를 만들어 주세요.");
        return planAt(origin.lat,origin.lon,budget,preference,radiusMeters,returnToOrigin,stayMinutes,true);
    }

    /** A clearly labeled public demonstration origin; this never changes the Android GPS. */
    public JSONObject planAtSeoulCityHall(int budget,String preference) throws ApiException {
        return planAt(37.5665,126.9780,budget,preference,20000,false,15,false);
    }
    public JSONObject planAtSeoulCityHall(int budget,String preference,boolean returnToOrigin,int stayMinutes) throws ApiException {
        return planAt(37.5665,126.9780,budget,preference,20000,returnToOrigin,stayMinutes,true);
    }

    private JSONObject planAt(double lat,double lon,int budget,String preference,int radiusMeters,boolean returnToOrigin,int stayMinutes,boolean includeOptions) throws ApiException {
        if(!CourseEngine.inKorea(lat,lon)||CourseEngine.unsupportedIslandOrigin(lat,lon))throw new ApiException("이 출발 위치에서는 코스를 만들 수 없어요. 국내 지역의 준비된 코스를 둘러보세요.");
        if(budget<1||budget>720)throw new ApiException("추천 시간은 1~720분으로 선택해 주세요.");
        if(radiusMeters!=20000)throw new ApiException("주변 탐색 범위를 다시 확인해 주세요.");
        if(stayMinutes!=0&&stayMinutes!=15&&stayMinutes!=30)throw new ApiException("장소별 체류시간은 0·15·30분으로 선택해 주세요.");
        String theme;
        if("sea".equals(preference))theme="coast";
        else if("night".equals(preference))theme="city";
        else if("all".equals(preference)||"lake".equals(preference)||"forest".equals(preference)||"coast".equals(preference)||"city".equals(preference))theme=preference;
        else throw new ApiException("추천할 풍경 취향을 선택해 주세요.");
        try{
            JSONObject preferences=new JSONObject().put("maxMinutes",budget).put("theme",theme);
            if(includeOptions)preferences.put("returnToOrigin",returnToOrigin).put("stayMinutes",stayMinutes);
            JSONObject body=new JSONObject().put("origin",new JSONObject().put("lat",lat).put("lon",lon))
                .put("preferences",preferences).put("radiusMeters",radiusMeters);
            return request("POST","/api/android/plan",body,90000);
        }catch(JSONException e){throw new ApiException("코스 생성 요청을 만들지 못했습니다. 조건을 다시 선택해 주세요.");}
    }

    public JSONObject route(CourseEngine.Course course) throws ApiException {
        if (course == null || course.spots == null || course.spots.length < 2 || course.spots.length > 7)
            throw new ApiException("도로 경로를 확인할 코스를 선택해 주세요.");
        try {
            JSONArray stops = new JSONArray();
            for (CourseEngine.Spot spot : course.spots) {
                if (spot == null || !Double.isFinite(spot.lat) || !Double.isFinite(spot.lon)
                        || Math.abs(spot.lat) > 90 || Math.abs(spot.lon) > 180)
                    throw new ApiException("코스 좌표가 올바르지 않습니다. 다른 코스를 선택해 주세요.");
                stops.put(new JSONObject().put("lat", spot.lat).put("lon", spot.lon));
            }
            return request("POST", "/api/route", new JSONObject().put("stops", stops));
        } catch (JSONException e) {
            throw new ApiException("도로 경로 요청을 만들지 못했습니다. 코스를 다시 선택해 주세요.");
        }
    }

    private JSONObject request(String method,String path,JSONObject body) throws ApiException {return request(method,path,body,20000);}
    private JSONObject request(String method,String path,JSONObject body,int readTimeout) throws ApiException {
        HttpURLConnection connection = null;
        int status = -1;
        try {
            requireActive();
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            synchronized (this) { requireActive(); activeConnection = connection; }
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            // No API key is stored in or transmitted by this app.
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                requireActive();
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            requireActive();
            status = connection.getResponseCode();
            requireActive();
            if (status < 200 || status >= 300) throw httpError(status);
            // A comparison carries two road geometries. Other endpoints retain their existing cap.
            int responseLimit = "/api/destination-routes".equals(path) ? MAX_DESTINATION_RESPONSE_BYTES : MAX_RESPONSE_BYTES;
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals("application/json"))
                throw malformed();
            if (connection.getContentLengthLong() > responseLimit)
                throw new ApiException("서버 응답이 너무 큽니다. 다른 코스를 선택하거나 잠시 후 다시 시도해 주세요.");
            String text;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int total = 0, count;
                while ((count = input.read(chunk)) != -1) {
                    requireActive();
                    total += count;
                    if (total > responseLimit)
                        throw new ApiException("서버 응답이 너무 큽니다. 다른 코스를 선택하거나 잠시 후 다시 시도해 주세요.");
                    buffer.write(chunk, 0, count);
                }
                text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            requireActive();
            if (!StrictJson.isValid(text)) throw malformed();
            JSONTokener parser = new JSONTokener(text);
            Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw malformed();
            return (JSONObject) parsed;
        } catch (ApiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ApiException("서버 연결이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
        } catch (JSONException e) {
            throw malformed();
        } catch (IOException | SecurityException e) {
            throw new ApiException("서버에 연결할 수 없습니다. 서버 실행 상태와 연결 주소를 확인해 주세요.");
        } finally {
            if (connection != null) {
                // Android's getErrorStream() can initiate getResponse(). Never call it for
                // a cancelled request that has not actually received an HTTP error.
                InputStream error = status >= 400 && !cancelled ? connection.getErrorStream() : null;
                if (error != null) try { error.close(); } catch (IOException ignored) {}
                connection.disconnect();
            }
            synchronized (this) { if (activeConnection == connection) activeConnection = null; }
        }
    }

    private static ApiException httpError(int status) {
        if (status >= 300 && status < 400) return new ApiException("서버가 다른 주소로 이동했습니다. 연결 주소를 직접 확인해 주세요.", status);
        if (status == 429) return new ApiException("요청이 많습니다. 잠시 후 다시 시도해 주세요.", status);
        if (status == 503) return new ApiException("서비스를 사용할 준비가 되지 않았습니다. 서버의 API 키 설정을 확인해 주세요.", status);
        if (status == 504) return new ApiException("서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.", status);
        if (status == 401 || status == 403) return new ApiException("서버 연결 권한을 확인해 주세요.", status);
        if (status == 404) return new ApiException("서버에서 앱 API를 찾지 못했습니다. 서버 버전과 주소를 확인해 주세요.", status);
        if (status == 409) return new ApiException("사진 선택이 만료되었거나 목적지가 바뀌었어요. 목적지 주변 사진을 새로 불러와 다시 선택해 주세요.", status);
        if (status == 422) return new ApiException("선택한 장소까지의 자동차 경로를 확인하지 못했어요. 출발지 또는 다른 풍경·장소를 선택해 주세요.", status);
        if (status == 400) return new ApiException("선택한 조건으로 요청을 처리할 수 없습니다. 시간이나 코스를 바꿔 주세요.", status);
        return new ApiException("서비스 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.", status);
    }

    private static ApiException malformed() {
        return new ApiException("서버 응답 형식이 올바르지 않습니다. 서버 상태를 확인해 주세요.");
    }

}
