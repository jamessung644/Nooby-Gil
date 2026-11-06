package com.scenic.pleos;

public final class ApiRequestGateTest {
    private static int checks;
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;}
    public static void main(String[] args){
        ApiRequestGate gate=new ApiRequestGate();
        long first=gate.begin(ApiRequestGate.AI);
        long second=gate.begin(ApiRequestGate.AI);
        check(!gate.finish(ApiRequestGate.AI,first),"older AI reply cannot win");
        check(gate.isPending(ApiRequestGate.AI),"old reply cannot clear loading");
        long route=gate.begin(ApiRequestGate.ROUTE);
        check(gate.finish(ApiRequestGate.AI,second),"latest AI reply accepted");
        check(gate.finish(ApiRequestGate.ROUTE,route),"independent route reply accepted");
        check(!gate.finish(ApiRequestGate.ROUTE,route),"duplicate reply ignored");
        long status=gate.begin(ApiRequestGate.STATUS);
        long late=gate.begin(ApiRequestGate.ROUTE);
        gate.invalidate();
        check(!gate.finish(ApiRequestGate.STATUS,status),"pause invalidates status");
        check(!gate.finish(ApiRequestGate.ROUTE,late),"course change invalidates route");
        check(!gate.isPending(ApiRequestGate.ROUTE),"invalidating clears loading");
        check(gate.finish(ApiRequestGate.AI,gate.begin(ApiRequestGate.AI)),"new session accepts new work");
        System.out.println("ApiRequestGate: "+checks+" checks passed");
    }
}
