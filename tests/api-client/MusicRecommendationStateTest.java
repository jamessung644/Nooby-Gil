package com.scenic.pleos;

/** Request ownership and session cache tests, independent from Activity rendering. */
public final class MusicRecommendationStateTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static String key(String title){return new MusicContext(title,"lake",90).key();}
    public static void main(String[] args)throws Exception{
        MusicPlaylist first=MusicPlaylist.parse(MusicPlaylistTest.response());
        MusicPlaylist second=MusicPlaylist.parse(MusicPlaylistTest.response().put("reason","다른 코스의 추천 이유"));
        String a=key("호숫길"),b=key("숲길");
        MusicRecommendationState state=new MusicRecommendationState();
        check(state.begin()==0&&!state.isPending(),"No request without selected context");
        check(state.select(a),"First context selection changes state");
        long a1=state.begin();check(a1!=0&&state.isPending(),"First render starts one request");
        check(!state.select(a)&&state.begin()==0,"Repeated render does not reset or duplicate pending request");
        check(!state.complete(a1,b,second,null)&&state.isPending(),"Wrong context cannot complete owned token");
        check(!state.complete(0,a,first,null)&&state.isPending(),"Zero token cannot complete request");
        check(state.complete(a1,a,first,null),"Current response accepted");
        check(state.playlist()==first&&!state.isPending()&&state.error()==null,"Accepted response becomes chosen playlist");
        check(!state.complete(a1,a,second,null)&&state.playlist()==first,"Duplicate response cannot overwrite selection");
        check(state.begin()==0&&!state.select(a),"Completed context does not automatically call API on render");
        check(state.select(b)&&state.playlist()==null,"New context clears old playlist immediately");
        long b1=state.begin();check(b1>a1,"New request gets distinct token");
        check(state.complete(b1,b,second,null)&&state.playlist()==second,"Second context result accepted");
        check(state.select(a)&&state.playlist()==first&&state.begin()==0,"Returning context reuses cached playlist without request");
        check(state.select(b)&&state.playlist()==second&&state.begin()==0,"Cache retains both context selections");

        state.retry();long failed=state.begin();
        check(failed!=0&&state.playlist()==null&&state.error()==null,"Explicit retry clears result and starts new request");
        check(state.complete(failed,b,null,"서버 지연"),"Failure applies only to its active request");
        check(state.error().equals("서버 지연")&&state.playlist()==null&&!state.isPending(),"Failure exposes error without fake recommendation");
        check(!state.select(b)&&state.begin()==0,"Failed render does not create retry loop");
        state.retry();long retried=state.begin();
        check(retried>failed&&state.error()==null,"User retry clears error with fresh request token");
        check(!state.complete(failed,b,first,null)&&state.isPending(),"Old failed request cannot win against retry");
        check(state.complete(retried,b,second,null),"Retried response can complete");

        state.select(key("세 번째"));long old=state.begin();
        state.select(key("네 번째"));long current=state.begin();
        check(!state.complete(old,key("세 번째"),first,null)&&state.isPending()&&state.playlist()==null,"Context change discards late success");
        check(!state.complete(old,key("세 번째"),null,"오래된 오류")&&state.error()==null,"Context change discards late failure");
        check(state.complete(current,key("네 번째"),second,null),"Current context still accepts own response");
        state.select(key("세 번째"));check(state.playlist()==null,"Ignored old result was not inserted into cache");
        long beforeCancel=state.begin();state.cancel();
        check(!state.isPending()&&!state.complete(beforeCancel,key("세 번째"),first,null),"Cancellation rejects response while inactive");
        long afterCancel=state.begin();
        check(afterCancel>beforeCancel&&state.isPending(),"Resume can make a fresh request after cancellation");
        check(!state.complete(beforeCancel,key("세 번째"),first,null)&&state.isPending(),"Cancelled response cannot replace same-key request on resume");
        check(!state.complete(beforeCancel,key("세 번째"),null,"이전 오류")&&state.error()==null,"Cancelled failure cannot replace same-key request");
        check(state.complete(afterCancel,key("세 번째"),second,null),"Fresh same-key request completes");
        state.cancel();check(state.playlist()==second&&state.begin()==0,"Lifecycle cancellation preserves completed cache without refetch");

        state.select(key("다섯 번째"));long departed=state.begin();
        state.select(key("여섯 번째"));state.begin();
        state.select(key("다섯 번째"));long returned=state.begin();
        check(returned>departed&&!state.complete(departed,key("다섯 번째"),first,null),"Leaving and returning same key never revives old token");
        check(state.complete(returned,key("다섯 번째"),second,null),"New request after same-key return wins");

        MusicRecommendationState bounded=new MusicRecommendationState();
        for(int i=0;i<12;i++){String k=key("cached "+i);bounded.select(k);bounded.complete(bounded.begin(),k,first,null);}
        bounded.select(key("cached 0"));check(bounded.playlist()==first,"Read oldest entry before capacity expansion");
        String newest=key("cached 12");bounded.select(newest);bounded.complete(bounded.begin(),newest,second,null);
        bounded.select(key("cached 0"));check(bounded.playlist()==first&&bounded.begin()==0,"Recently used cached context survives eviction");
        bounded.select(key("cached 1"));check(bounded.playlist()==null&&bounded.begin()!=0,"Least recently used entry is evicted from bounded session cache");
        System.out.println("MusicRecommendationStateTest: "+checks+" checks passed");
    }
}
