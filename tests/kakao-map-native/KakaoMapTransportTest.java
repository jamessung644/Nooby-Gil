package com.scenic.pleos;

/** Exercises provider allowlisting and redirect resolution without opening a connection. */
public final class KakaoMapTransportTest {
    private static int checks;
    private static void equal(String expected,String actual,String message){
        checks++;if(expected==null?actual!=null:!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);
    }
    public static void main(String[] args){
        String[][] upgrades={
            {"http://dapi.kakao.com/v2/maps/sdk.js?autoload=false","https://dapi.kakao.com/v2/maps/sdk.js?autoload=false"},
            {"HTTP://T1.DAUMCDN.NET:80/map/tile.png","https://t1.daumcdn.net/map/tile.png"},
            {"http://a.b.kakaocdn.net/tiles/1","https://a.b.kakaocdn.net/tiles/1"},
            {"http://t1.daumcdn.net/map%2Ftile.png?x=a%2Bb&y=%2F","https://t1.daumcdn.net/map%2Ftile.png?x=a%2Bb&y=%2F"},
            {"http://dapi.kakao.com:80/","https://dapi.kakao.com/"},
            {"http://t1.kakaocdn.net","https://t1.kakaocdn.net"}
        };
        for(String[] entry:upgrades)equal(entry[1],KakaoMapTransport.upgradeProviderUrl(entry[0]),"known provider HTTP resources upgrade without altering encoded path/query");

        String[] blocked={
            null,"","https://dapi.kakao.com/maps.js","//dapi.kakao.com/maps.js","/maps.js",
            "ftp://dapi.kakao.com/maps.js","javascript:alert(1)","file:///tmp/maps.js",
            "http://daumcdn.net/a","http://kakaocdn.net/a","http://sub.dapi.kakao.com/a",
            "http://dapi.kakao.com.evil.example/a","http://t1.daumcdn.net.evil.example/a","http://notdaumcdn.net/a",
            "http://t1.kakaocdn.net@evil.example/a","http://secret@dapi.kakao.com/a",
            "http://dapi.kakao.com:443/a","http://dapi.kakao.com:8080/a","http://dapi.kakao.com:0/a",
            "http://dapi.kakao.com/a#part","http://dapi.kakao.com/a#",
            "http://%64api.kakao.com/a","http://dapi.kakao.com./a","http://한글.daumcdn.net/a",
            "http://bad_host.daumcdn.net/a"," http://dapi.kakao.com/a","http://dapi.kakao.com/a\n",
            "http://dapi.kakao.com/a\u0000b","http://dapi.kakao.com/a?x=%ZZ","http://127.0.0.1/a"
        };
        for(String value:blocked)equal(null,KakaoMapTransport.upgradeProviderUrl(value),"untrusted or non-upgradeable resources stay outside the native transport");

        String base="https://t1.daumcdn.net/maps/v1/tile.png?old=1";
        String[][] redirects={
            {base,"../tile%2F2.png?x=%2B","https://t1.daumcdn.net/maps/tile%2F2.png?x=%2B"},
            {base,"/map%2Ftile.png?x=a%2Bb","https://t1.daumcdn.net/map%2Ftile.png?x=a%2Bb"},
            {base,"?new=2","https://t1.daumcdn.net/maps/v1/tile.png?new=2"},
            {base,"https://T2.KAKAOCDN.NET:443/assets/a.png","https://t2.kakaocdn.net/assets/a.png"},
            {"https://DAPI.KAKAO.COM:443/v2/maps/sdk.js","/v2/maps/sdk.js?autoload=false","https://dapi.kakao.com/v2/maps/sdk.js?autoload=false"},
            {base,"//t2.daumcdn.net/assets/a.png","https://t2.daumcdn.net/assets/a.png"}
        };
        for(String[] entry:redirects)equal(entry[2],KakaoMapTransport.resolveRedirect(entry[0],entry[1]),"redirects remain on allowed HTTPS providers and preserve URL encoding");
        String[] unsafeTargets={
            null,"http://t2.daumcdn.net/a","https://evil.example/a","https://daumcdn.net/a",
            "https://t2.daumcdn.net.evil.example/a","https://secret@t2.kakaocdn.net/a",
            "https://127.0.0.1/a","https://localhost/a","https://t2.kakaocdn.net:444/a",
            "https://t2.kakaocdn.net./a","https://%74%32.kakaocdn.net/a","https://한글.kakaocdn.net/a",
            "https://t2.kakaocdn.net/a#fragment","#fragment","/tile.png\n","https://t2.kakaocdn.net/a?x=%ZZ"
        };
        for(String target:unsafeTargets)equal(null,KakaoMapTransport.resolveRedirect(base,target),"redirect targets cannot downgrade or escape the provider boundary");
        String[] unsafeCurrent={null,"http://t1.daumcdn.net/a","https://evil.example/a","https://secret@dapi.kakao.com/a","https://t1.daumcdn.net:444/a","https://t1.daumcdn.net/a#fragment"};
        for(String current:unsafeCurrent)equal(null,KakaoMapTransport.resolveRedirect(current,"https://dapi.kakao.com/maps.js"),"redirect source must already be an allowed HTTPS provider");
        System.out.println("KakaoMapTransportTest: "+checks+" checks passed");
    }
}
