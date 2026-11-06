package com.scenic.pleos;

/** Main-thread request ownership; late replies never replace a newer screen. */
public final class ApiRequestGate {
    public static final int STATUS=0, AI=1, ROUTE=2;
    private final long[] pending=new long[3];
    private long sequence;
    public long begin(int kind){long token=++sequence;pending[kind]=token;return token;}
    public boolean finish(int kind,long token){
        if(token==0||pending[kind]!=token)return false;
        pending[kind]=0;return true;
    }
    public boolean isPending(int kind){return pending[kind]!=0;}
    public void invalidate(){for(int i=0;i<pending.length;i++)pending[i]=0;}
}
