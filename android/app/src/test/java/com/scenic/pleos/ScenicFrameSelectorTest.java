package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ScenicFrameSelectorTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ScenicFrameSelector.Frame frame(int[] pixels,long at){return ScenicFrameSelector.analyze(pixels,64,48,at);}
    private static int[] scene(int palette){
        int[] pixels=new int[64*48];
        for(int y=0;y<48;y++)for(int x=0;x<64;x++){
            int r,g,b;
            if(palette==0){r=40+x*2;g=105+y*2;b=y<23?210:100+x;}
            else if(palette==1){r=30+x;g=95+x*2;b=35+y;}
            else if(palette==2){r=150+x;g=45+y*2;b=30+x;}
            else{r=60+x*2;g=40+y*2;b=140+x;}
            // Mountain/foliage boundaries provide edge detail while preserving coherent colors.
            if(y>20+(x/8)%3*3&&y<37){r/=2;g=g*3/4;b/=2;}
            if((x/5+y/7)%4==0){r=Math.min(255,r+24);g=Math.min(255,g+24);b=Math.min(255,b+24);}
            pixels[y*64+x]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return pixels;
    }
    private static int[] solid(int value){int[] data=new int[64*48];Arrays.fill(data,0xff000000|(value<<16)|(value<<8)|value);return data;}
    private static int[] blurred(int[] original){
        int[] result=original.clone();
        for(int y=0;y<48;y++)for(int x=0;x<64;x++){
            int r=0,g=0,b=0,count=0;
            for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){
                int py=y+dy,px=x+dx;if(py<0||py>=48||px<0||px>=64)continue;
                int pixel=original[py*64+px];r+=(pixel>>>16)&255;g+=(pixel>>>8)&255;b+=pixel&255;count++;
            }
            result[y*64+x]=0xff000000|((r/count)<<16)|((g/count)<<8)|(b/count);
        }
        return result;
    }
    public static void main(String[] args){
        ScenicFrameSelector.Frame good=frame(scene(0),5000),soft=frame(blurred(scene(0)),6000);
        check(good.usable,"Coherent daylight scenery should be eligible");
        check(good.quality>soft.quality,"Sharp scenery should outrank a heavily blurred version");
        check(!frame(solid(0),0).usable,"Black frames should not become memories");
        check(!frame(solid(255),0).usable,"Overexposed white frames should not become memories");
        check(!frame(solid(140),0).usable,"Featureless frames should be excluded");
        check(good.quality>=0&&good.quality<=1,"Quality must be bounded");
        List<ScenicFrameSelector.Frame> near=ScenicFrameSelector.select(Arrays.asList(good,frame(scene(0),7000),frame(scene(1),11000)),6,1000);
        check(near.size()==2,"Nearby duplicate scenery should be suppressed");
        check(near.get(0).timestampMs==5000&&near.get(1).timestampMs==11000,"Chosen scenes should follow trip chronology");
        List<ScenicFrameSelector.Frame> spaced=ScenicFrameSelector.select(Arrays.asList(good,frame(scene(1),5400)),6,1000);
        check(spaced.size()==1,"Visually distinct frames still need temporal spacing");
        List<ScenicFrameSelector.Frame> repeated=ScenicFrameSelector.select(Arrays.asList(good,frame(scene(0),65000)),6,1000);
        check(repeated.size()==2,"A similar view later in the trip should remain eligible");
        ArrayList<ScenicFrameSelector.Frame> many=new ArrayList<>();
        for(int i=0;i<10;i++)many.add(frame(scene(i%4),i*65000L));
        List<ScenicFrameSelector.Frame> capped=ScenicFrameSelector.select(many,99,0);
        check(capped.size()==6,"A story must never grow beyond six scenes");
        check(ScenicFrameSelector.select(Arrays.asList(frame(solid(0),0),null),6,0).isEmpty(),"No unusable fallback should be passed off as a highlight");
        check(ScenicFrameSelector.select(many,0,0).isEmpty(),"Zero requested frames should return none");
        boolean invalid=false;try{ScenicFrameSelector.analyze(new int[4],64,48,0);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"Invalid pixel buffers should be rejected");
        invalid=false;try{ScenicFrameSelector.select(many,6,-1);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"Negative spacing should be rejected");
        int[] transparent=scene(0);for(int i=0;i<transparent.length;i++)transparent[i]&=0x00ffffff;
        check(frame(transparent,1).quality==good.quality,"Video pixel alpha should not affect quality");
        System.out.println("ScenicFrameSelectorTest: "+checks+" checks passed");
    }
}
