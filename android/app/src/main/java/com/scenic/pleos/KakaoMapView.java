package com.scenic.pleos;

import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Server-hosted Kakao map. Native location is supplied by the Activity, never acquired here. */
@SuppressWarnings("deprecation")
public final class KakaoMapView extends WebView {
    private final KakaoMapPayload payload=new KakaoMapPayload();
    private volatile String mapUrl;
    private String baseUrl,lastSentScript;
    private boolean pageReady,failed,paused;
    private volatile boolean released;

    public KakaoMapView(Context context){
        super(context);
        setBackgroundColor(0xfff5f5f5);setOverScrollMode(OVER_SCROLL_NEVER);
        setContentDescription("카카오 지도. 현재 위치는 Android 위치 권한으로 수신한 경우에만 표시합니다.");
        WebSettings settings=getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        // No JavascriptInterface or browser permission path exposes native APIs to the page.
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
        });
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                return released||!KakaoMapPayload.allowsNavigation(mapUrl,request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){
                return released||!KakaoMapPayload.allowsNavigation(mapUrl,url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                // This also rejects main-frame POST navigation, which shouldOverrideUrlLoading omits.
                if(request.isForMainFrame()&&(released||!"GET".equals(request.getMethod())||!KakaoMapPayload.allowsNavigation(mapUrl,request.getUrl().toString())))
                    return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));
                if(!released&&!request.isForMainFrame()&&"GET".equals(request.getMethod())){
                    String secure=KakaoMapTransport.upgradeProviderUrl(request.getUrl().toString());
                    if(secure!=null){
                        try{
                            KakaoMapTransport.Resource resource=KakaoMapTransport.fetch(secure,request.getRequestHeaders());
                            return new WebResourceResponse(resource.mime,resource.encoding,resource.status,"OK",resource.headers,new ByteArrayInputStream(resource.body));
                        }catch(IOException|RuntimeException failure){
                            // No raw provider error or URL (which may contain a public SDK key) escapes.
                            return new WebResourceResponse("text/plain","UTF-8",502,"Bad Gateway",null,new ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return null;
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){
                // A cancelled previous server load can still deliver a late lifecycle callback.
                if(released||!KakaoMapPayload.allowsNavigation(mapUrl,url))return;
                pageReady=false;lastSentScript=null;
            }
            @Override public void onPageFinished(WebView view,String url){
                if(released||failed||!KakaoMapPayload.allowsNavigation(mapUrl,url)||!KakaoMapPayload.allowsNavigation(mapUrl,getUrl()))return;
                pageReady=true;sendScene();
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                Log.w("ScenicMap","network code="+error.getErrorCode()+" host="+request.getUrl().getHost());
                if(request.isForMainFrame()&&KakaoMapPayload.allowsNavigation(mapUrl,request.getUrl().toString()))showConnectionError();
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                Log.w("ScenicMap","http status="+response.getStatusCode()+" host="+request.getUrl().getHost());
                if(request.isForMainFrame()&&KakaoMapPayload.allowsNavigation(mapUrl,request.getUrl().toString()))showConnectionError();
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){
                handler.cancel();
                String host=error.getUrl()==null?null:Uri.parse(error.getUrl()).getHost();
                Log.w("ScenicMap","ssl code="+error.getPrimaryError()+" host="+host);
                if(KakaoMapPayload.allowsNavigation(mapUrl,error.getUrl()))showConnectionError();
            }
        });
    }

    /** Retains an existing page when the normalized server origin has not changed. */
    public void loadServer(String address){
        if(released)return;
        String normalized=EndpointPolicy.normalizeBaseUrl(address);
        if(normalized.equals(baseUrl))return;
        baseUrl=normalized;mapUrl=normalized+"/kakao-map.html";reload();
    }
    public void setScene(CourseEngine.Course course,int active,double[][] roads){
        if(released)return;payload.setScene(course,active,roads);sendScene();
    }
    public void setLocation(Location location,boolean center){
        if(released)return;
        if(location==null){clearLocation();return;}
        payload.setLocation(location.getLatitude(),location.getLongitude(),location.hasAccuracy()?Double.valueOf(location.getAccuracy()):null,center);sendScene();
    }
    public void clearLocation(){if(!released&&payload.clearLocation())sendScene();}
    public void focusRoute(){if(!released){payload.focusRoute();sendScene();}}

    @Override public void reload(){
        if(released||mapUrl==null)return;
        pageReady=false;failed=false;lastSentScript=null;stopLoading();super.loadUrl(mapUrl);
    }
    @Override public void onPause(){if(!released){paused=true;super.onPause();}}
    @Override public void onResume(){if(!released){paused=false;super.onResume();sendScene();}}
    private void sendScene(){
        if(released||paused||!pageReady||failed||!KakaoMapPayload.allowsNavigation(mapUrl,getUrl()))return;
        String script=payload.script();
        if(script.equals(lastSentScript))return;
        evaluateJavascript(script,null);lastSentScript=script;
    }
    private void showConnectionError(){
        if(released||failed||mapUrl==null)return;
        failed=true;pageReady=false;stopLoading();
        String html="<!doctype html><html lang='ko'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                +"<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                +"<style>html,body{margin:0;height:100%;background:#f5f5f5;color:#17191c;font-family:sans-serif}body{display:flex;align-items:center;justify-content:center}main{max-width:520px;padding:28px}h2{font-size:20px;line-height:1.5;margin:0 0 12px}p{font-size:16px;line-height:1.7;color:#656970;margin:0}</style>"
                +"<main><h2>지도 서버에 연결하지 못했어요</h2><p>API 서버 실행 상태와 주소를 확인한 뒤 지도 새로고침을 눌러 주세요.</p></main></html>";
        // Trusted static fallback, with the configured origin and no location or remote error details.
        super.loadDataWithBaseURL(mapUrl,html,"text/html","UTF-8",mapUrl);
    }
    public void release(){
        if(released)return;
        released=true;pageReady=false;payload.clearLocation();stopLoading();super.onPause();
        ViewParent parent=getParent();if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(this);
        setWebChromeClient(null);setWebViewClient(null);removeAllViews();super.destroy();
    }
    @Override public void destroy(){release();}
}

/** Payload state is independent of WebView so the JSON boundary can be verified offline. */
final class KakaoMapPayload {
    private JSONObject scene=new JSONObject();
    private String geometry="",focus="route";
    private long focusRevision;
    private JSONObject location;

    KakaoMapPayload(){setScene(null,0,null);}
    void setScene(CourseEngine.Course course,int active,double[][] roads){
        try{
            JSONArray stops=new JSONArray(),road=new JSONArray();
            if(course!=null&&course.spots!=null)for(CourseEngine.Spot stop:course.spots){
                if(stop==null||!validCoordinate(stop.lat,stop.lon)){stops=new JSONArray();break;}
                stops.put(new JSONObject().put("title",stop.title==null?"":stop.title).put("lat",stop.lat).put("lon",stop.lon));
            }
            if(roads!=null&&roads.length>=2&&roads.length<=50000){
                for(double[] point:roads){
                    if(point==null||point.length!=2||!validCoordinate(point[0],point[1])){road=new JSONArray();break;}
                    road.put(new JSONArray().put(point[0]).put(point[1]));
                }
            }
            String courseId=course==null||course.id==null?"":course.id;
            String signature=courseId+"|"+stops.toString()+"|"+road.toString();
            if(!signature.equals(geometry)){geometry=signature;focus="route";focusRevision++;}
            scene=new JSONObject().put("courseId",courseId).put("title",course==null||course.name==null?"":course.name)
                    .put("stops",stops).put("roadCoordinates",road).put("active",Math.max(0,Math.min(active,stops.length()-1)));
        }catch(JSONException impossible){throw new IllegalArgumentException("지도 좌표를 확인해 주세요.");}
    }
    void setLocation(double latitude,double longitude,Double accuracy,boolean center){
        if(!validCoordinate(latitude,longitude)){clearLocation();return;}
        try{
            Object knownAccuracy=accuracy!=null&&Double.isFinite(accuracy)&&accuracy>=0?accuracy:JSONObject.NULL;
            location=new JSONObject().put("lat",latitude).put("lon",longitude).put("accuracy",knownAccuracy);
            if(center){focus="location";focusRevision++;}
        }catch(JSONException impossible){clearLocation();}
    }
    boolean clearLocation(){
        boolean changed=location!=null||"location".equals(focus);
        location=null;if("location".equals(focus))focusRoute();
        return changed;
    }
    void focusRoute(){focus="route";focusRevision++;}
    String json(){
        try{return scene.put("location",location==null?JSONObject.NULL:location).put("focus",focus).put("focusRevision",focusRevision).toString();}
        catch(JSONException impossible){throw new IllegalStateException("지도 상태를 만들지 못했습니다.");}
    }
    String script(){
        String quoted=JSONObject.quote(json()).replace("\u2028","\\u2028").replace("\u2029","\\u2029");
        return "(function(){if(window.scenicMap&&typeof window.scenicMap.setScene==='function'){window.scenicMap.setScene(JSON.parse("+quoted+"));}})();";
    }
    static boolean allowsNavigation(String expected,String candidate){return expected!=null&&expected.equals(candidate);}
    private static boolean validCoordinate(double latitude,double longitude){
        return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180;
    }
}

/** Strict provider-CDN policy used only for upgrading map subresources to verified HTTPS. */
final class KakaoMapTransport {
    private static final int MAX_BYTES=8*1024*1024,TIMEOUT_MS=15000,MAX_REDIRECTS=3;
    private KakaoMapTransport(){}

    static String upgradeProviderUrl(String value){
        URI uri=providerUri(value,"http");
        return uri==null?null:httpsUrl(uri);
    }
    static String resolveRedirect(String current,String location){
        URI base=providerUri(current,"https");
        if(base==null||location==null||location.isEmpty())return null;
        try{
            URI resolved;
            // URI.resolve otherwise loses the current filename for a query-only reference.
            if(location.startsWith("?"))resolved=new URI("https://"+base.getRawAuthority()+path(base)+location);
            else resolved=base.resolve(new URI(location));
            URI target=providerUri(resolved.toString(),"https");
            return target==null?null:httpsUrl(target);
        }catch(URISyntaxException|IllegalArgumentException invalid){return null;}
    }
    private static URI providerUri(String value,String scheme){
        if(value==null||value.length()>16384)return null;
        for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i))||Character.isISOControl(value.charAt(i)))return null;
        try{
            URI uri=new URI(value);String host=uri.getHost();
            if(!scheme.equalsIgnoreCase(uri.getScheme())||host==null||uri.isOpaque()||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)return null;
            host=host.toLowerCase(Locale.ROOT);
            if(!host.equals("dapi.kakao.com")&&!host.endsWith(".daumcdn.net")&&!host.endsWith(".kakaocdn.net"))return null;
            if(host.length()>253)return null;
            for(String label:host.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return null;
            int port=uri.getPort(),standard="https".equals(scheme)?443:80;
            if(port!=-1&&port!=standard)return null;
            if(!(host+(port==-1?"":":"+port)).equalsIgnoreCase(uri.getRawAuthority()))return null;
            return uri;
        }catch(URISyntaxException invalid){return null;}
    }
    private static String path(URI uri){String path=uri.getRawPath();return path==null?"":path;}
    private static String httpsUrl(URI uri){
        return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+path(uri)+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
    }

    static final class Resource {
        final byte[] body;final String mime,encoding;final int status;final Map<String,String> headers;
        Resource(byte[] body,String mime,String encoding,int status,Map<String,String> headers){this.body=body;this.mime=mime;this.encoding=encoding;this.status=status;this.headers=headers;}
    }
    /** A narrowly scoped HTTPS GET, with system TLS/hostname checks and no cookie/auth forwarding. */
    static Resource fetch(String address,Map<String,String> requestHeaders)throws IOException{
        URI initial=providerUri(address,"https");
        if(initial==null)throw new IOException("Unapproved map resource");
        String current=httpsUrl(initial);long deadline=System.nanoTime()+TIMEOUT_MS*1000000L;
        for(int redirects=0;;redirects++){
            HttpsURLConnection connection=(HttpsURLConnection)new URL(current).openConnection();
            try{
                connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod("GET");
                connection.setConnectTimeout(remaining(deadline));connection.setReadTimeout(remaining(deadline));
                if(requestHeaders!=null)for(Map.Entry<String,String> entry:requestHeaders.entrySet()){
                    String name=entry.getKey(),value=entry.getValue();
                    if(name!=null&&(name.equalsIgnoreCase("Accept")||name.equalsIgnoreCase("User-Agent")||name.equalsIgnoreCase("Referer"))&&safeHeader(value))
                        connection.setRequestProperty(name,value);
                }
                connection.connect();connection.setReadTimeout(remaining(deadline));
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    if(redirects>=MAX_REDIRECTS)throw new IOException("Too many map redirects");
                    String next=resolveRedirect(current,connection.getHeaderField("Location"));
                    if(next==null)throw new IOException("Unapproved map redirect");
                    current=next;continue;
                }
                if(status<200||status>=300||connection.getContentLengthLong()>MAX_BYTES)throw new IOException("Invalid map resource response");
                String contentType=connection.getContentType(),mime="application/octet-stream",encoding="UTF-8";
                if(contentType!=null){
                    String[] pieces=contentType.split(";");
                    String candidate=pieces[0].trim();
                    if(candidate.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))mime=candidate;
                    for(int i=1;i<pieces.length;i++){
                        String part=pieces[i].trim();
                        if(part.toLowerCase(Locale.ROOT).startsWith("charset=")){
                            String charset=part.substring(8).trim().replace("\"","");
                            try{if(Charset.isSupported(charset))encoding=charset;}catch(IllegalArgumentException ignored){}
                        }
                    }
                }
                String compression=connection.getHeaderField("Content-Encoding");
                InputStream raw=connection.getInputStream();
                if(compression!=null&&!compression.isEmpty()&&!"identity".equalsIgnoreCase(compression)&&!"gzip".equalsIgnoreCase(compression)){
                    raw.close();throw new IOException("Unsupported map encoding");
                }
                byte[] body;
                try(InputStream input="gzip".equalsIgnoreCase(compression)?new GZIPInputStream(raw):raw;ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int count,total=0;
                    while(true){
                        connection.setReadTimeout(remaining(deadline));count=input.read(chunk);if(count==-1)break;
                        total+=count;if(total>MAX_BYTES)throw new IOException("Map resource too large");
                        output.write(chunk,0,count);
                    }
                    body=output.toByteArray();
                }
                Map<String,String> responseHeaders=new HashMap<>();
                for(String name:new String[]{"Cache-Control","Expires","ETag","Last-Modified","Vary","Access-Control-Allow-Origin"}){
                    String value=connection.getHeaderField(name);if(safeHeader(value))responseHeaders.put(name,value);
                }
                return new Resource(body,mime,encoding,status,responseHeaders);
            }finally{connection.disconnect();}
        }
    }
    private static int remaining(long deadline)throws IOException{
        long milliseconds=(deadline-System.nanoTime())/1000000L;
        if(milliseconds<=0)throw new IOException("Map resource timed out");
        return (int)Math.min(TIMEOUT_MS,milliseconds);
    }
    private static boolean safeHeader(String value){
        if(value==null||value.length()>8192)return false;
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;
        return true;
    }
}
