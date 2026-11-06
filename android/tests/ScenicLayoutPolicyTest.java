package com.scenic.pleos;

/** Safe-area breakpoints cover short landscape windows and resized panels. */
public final class ScenicLayoutPolicyTest {
    private static int checks;
    private static void expect(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    public static void main(String[] args){
        expect(ScenicLayoutPolicy.useTwoColumns(900,420),"short landscape uses two columns at both boundaries");
        expect(ScenicLayoutPolicy.useTwoColumns(1200,599),"landscape below previous 600dp cutoff stays split");
        expect(!ScenicLayoutPolicy.useTwoColumns(899,720),"narrow available width stacks content");
        expect(!ScenicLayoutPolicy.useTwoColumns(1200,419),"very short windows stack into a scrollable body");
        expect(!ScenicLayoutPolicy.useTwoColumns(0,0),"unmeasured viewport is safe");
        expect(!ScenicLayoutPolicy.pinRouteActions(420,1f),"compact route actions scroll with the result");
        expect(ScenicLayoutPolicy.pinRouteActions(560,1f),"roomy route keeps primary action in view");
        expect(!ScenicLayoutPolicy.pinRouteActions(720,1.5f),"large font keeps route actions in the scroll");
        expect(!ScenicLayoutPolicy.pinRouteActions(720,2f),"maximum font cannot overflow a fixed footer");
        expect(ScenicLayoutPolicy.mapHeight(900,420,true)==240,"short viewport retains usable scrollable map");
        expect(ScenicLayoutPolicy.mapHeight(2000,1600,true)==540,"large map height is bounded");
        expect(ScenicLayoutPolicy.mapHeight(320,700,false)==240,"narrow layout retains usable map");
        expect(ScenicLayoutPolicy.mapHeight(800,700,false)==360,"stacked map does not dominate scroll");
        expect(ScenicLayoutPolicy.previewHeight(420)==160,"compact preview minimum");
        expect(ScenicLayoutPolicy.previewHeight(1500)==280,"large preview maximum");
        System.out.println("ScenicLayoutPolicyTest: "+checks+" checks passed");
    }
}
