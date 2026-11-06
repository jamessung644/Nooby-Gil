package com.scenic.pleos;

/** Run with plain javac/java; no Android runtime or network is required. */
public final class RouteProjectionTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void near(double actual,double expected,String label){check(Math.abs(actual-expected)<0.00001,label+": expected "+expected+", got "+actual);}
    private static void rejected(Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,label);}
    private static void inside(double[] point,double left,double top,double right,double bottom,String label){
        check(Double.isFinite(point[0])&&Double.isFinite(point[1])&&point[0]>=left-0.00001&&point[0]<=right+0.00001&&point[1]>=top-0.00001&&point[1]<=bottom+0.00001,label);
    }
    public static void main(String[] args){
        // A one-degree square on the equator should be centered with equal axis lengths.
        // This catches swapped latitude/longitude, reversed north, and stretched axes.
        double[][] square={{-0.5,-0.5},{0.5,0.5}};
        RouteProjection equator=RouteProjection.fit(square,null,20,30,220,130);
        double[] sw=equator.project(-0.5,-0.5),ne=equator.project(0.5,0.5);
        near(sw[0],70,"west respects equal scale and horizontal centering");
        near(sw[1],130,"south is below north");
        near(ne[0],170,"east is right of west");
        near(ne[1],30,"north is at the top padding");

        // At latitude 60, one degree longitude spans half of one degree latitude.
        // Omitting latitude correction or fitting axes separately breaks these literals.
        RouteProjection north=RouteProjection.fit(new double[][]{{59.5,-0.5},{60.5,0.5}},null,0,0,200,200);
        near(north.project(59.5,-0.5)[0],50,"longitude contracts at high latitude");
        near(north.project(60.5,0.5)[0],150,"longitude retains physical aspect ratio");

        // The middle point is a road detour outside the endpoint bounds; a stop is farther north.
        double[][] detour={{0,0},{0,4},{1,1}};
        double[][] stops={{2,0}};
        RouteProjection complete=RouteProjection.fit(detour,stops,30,40,330,240);
        for(double[] point:detour)inside(complete.project(point[0],point[1]),30,40,330,240,"every road vertex stays in the padded frame");
        inside(complete.project(2,0),30,40,330,240,"course stops are part of the frame");
        near(complete.project(0,4)[0],330,"interior detour determines the eastern edge");

        RouteProjection vertical=RouteProjection.fit(new double[][]{{37,127},{38,127}},null,10,20,210,120);
        near(vertical.project(37,127)[0],110,"vertical route is horizontally centered");
        near(vertical.project(38,127)[1],20,"vertical route fits height without dividing by zero");
        RouteProjection horizontal=RouteProjection.fit(new double[][]{{37,127},{37,128}},null,10,20,210,120);
        near(horizontal.project(37,127)[1],70,"horizontal route is vertically centered");
        near(horizontal.project(37,128)[0],210,"horizontal route fits width without dividing by zero");
        RouteProjection duplicate=RouteProjection.fit(new double[][]{{37,127},{37,127}},null,10,20,210,120);
        near(duplicate.project(37,127)[0],110,"coincident points center horizontally");
        near(duplicate.project(37,127)[1],70,"coincident points center vertically");
        RouteProjection pole=RouteProjection.fit(new double[][]{{90,0},{90,1}},null,10,20,210,120);
        inside(pole.project(90,1),10,20,210,120,"polar coordinates stay finite");
        RouteProjection tiny=RouteProjection.fit(new double[][]{{0,0},{Double.MIN_VALUE,Double.MIN_VALUE}},null,10,20,210,120);
        inside(tiny.project(0,0),10,20,210,120,"nearly coincident coordinates cannot overflow the scale");

        // Caller-owned network arrays must not mutate an already accepted route.
        double[][] input={{37,127},{38,128}};
        double[][] copy=RouteProjection.copyValidatedRoute(input);
        input[0][0]=0;input[1]=new double[]{0,0};
        near(copy[0][0],37,"route copies nested coordinate data");
        near(copy[1][1],128,"route copies the outer coordinate array");

        double[][][] invalid={null,new double[0][],{{37,127}},{{37,127},null},{{37,127},{38}},{{37,127},{Double.NaN,128}},{{37,127},{38,Double.POSITIVE_INFINITY}},{{37,127},{91,128}},{{37,127},{38,-181}}};
        for(final double[][] coordinates:invalid)rejected(new Runnable(){public void run(){RouteProjection.copyValidatedRoute(coordinates);}},"malformed road geometry is rejected");
        rejected(new Runnable(){public void run(){RouteProjection.fit(new double[][]{{37,127}},null,0,0,200,100);}},"projection cannot bypass the two-point route minimum");
        rejected(new Runnable(){public void run(){RouteProjection.fit(square,new double[][]{{Double.NaN,0}},0,0,200,100);}},"invalid stop cannot corrupt route bounds");
        rejected(new Runnable(){public void run(){RouteProjection.fit(square,null,10,20,10,120);}},"empty viewport is rejected");
        rejected(new Runnable(){public void run(){RouteProjection.fit(square,null,10,20,210,Double.NaN);}},"non-finite viewport is rejected");
        System.out.println("RouteProjectionTest: "+checks+" checks passed");
    }
}
