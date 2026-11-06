package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public final class NavigationHandoffPlanTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){checks++;try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        DestinationPlace end=DestinationPlace.of("end","서울시청","서울",37.5665,126.9780);
        DestinationPlace first=DestinationPlace.of("one","경유지 1","서울",37.5700,126.9800);
        DestinationPlace second=DestinationPlace.of("two","경유지 2","서울",37.5800,126.9900);
        NavigationHandoffPlan direct=new NavigationHandoffPlan(end,Collections.emptyList());
        check(direct.destination==end&&direct.waypoints.isEmpty(),"direct destination preserved");
        ArrayList<DestinationPlace> input=new ArrayList<>(Arrays.asList(first,second));
        NavigationHandoffPlan plan=new NavigationHandoffPlan(end,input);
        input.clear();
        check(plan.waypoints.size()==2,"caller cannot erase requested stops");
        check(plan.waypoints.get(0)==first&&plan.waypoints.get(1)==second,"stop order preserved");
        rejects(()->new NavigationHandoffPlan(end,Arrays.asList(first,second,first)),"unsupported stops must not be silently truncated");
        rejects(()->new NavigationHandoffPlan(null,Collections.emptyList()),"destination required");
        rejects(()->new NavigationHandoffPlan(end,null),"waypoint list required");
        rejects(()->new NavigationHandoffPlan(end,Arrays.asList(first,null)),"null stop rejected");
        check(NavigationHandoffPlan.matches(end,37.5665,126.9780),"exact callback target matches");
        check(NavigationHandoffPlan.matches(end,37.5670,126.9780),"nearby road entrance snap accepted");
        check(!NavigationHandoffPlan.matches(end,37.58,126.9780),"another neighbourhood rejected");
        check(!NavigationHandoffPlan.matches(end,35.1587,129.1604),"stale Busan destination rejected");
        check(!NavigationHandoffPlan.matches(end,Double.NaN,126.9780),"invalid callback rejected");
        check(!NavigationHandoffPlan.matches(end,37.5665,Double.POSITIVE_INFINITY),"infinite callback rejected");
        check(!NavigationHandoffPlan.matches(null,37.5665,126.9780),"missing expected destination rejected");
        try{plan.waypoints.clear();throw new AssertionError("caller changed immutable trip");}catch(UnsupportedOperationException expected){checks++;}
        System.out.println("NavigationHandoffPlanTest: "+checks+" checks passed");
    }
}
