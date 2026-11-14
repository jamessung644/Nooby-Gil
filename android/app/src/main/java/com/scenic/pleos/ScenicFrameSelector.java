package com.scenic.pleos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Local image-quality heuristics for selecting stills from sampled video frames. */
public final class ScenicFrameSelector {
    private static final int MAX_FRAMES=6;
    private ScenicFrameSelector(){}

    public static final class Frame {
        public final long timestampMs,fingerprint;
        public final double quality;
        public final boolean usable;
        private final double[] colors;
        private Frame(long timestampMs,long fingerprint,double quality,boolean usable,double[] colors){
            this.timestampMs=timestampMs;this.fingerprint=fingerprint;this.quality=quality;this.usable=usable;this.colors=colors;
        }
    }

    /** ARGB pixels, row-major. Alpha is ignored; callers normally supply a scaled video frame. */
    public static Frame analyze(int[] argb,int width,int height,long timestampMs){
        if(argb==null||width<3||height<3||(long)width*height>argb.length||timestampMs<0)
            throw new IllegalArgumentException("Invalid sampled frame");
        // Bound work for full-resolution inputs without allocating a second full-resolution image.
        int cols=Math.min(width,96),rows=Math.min(height,72);
        double[] light=new double[cols*rows],colors=new double[48];
        int[] counts=new int[16];
        double sum=0,squares=0,saturation=0;int dark=0,bright=0;
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++){
            int px=(int)((long)x*(width-1)/(cols-1)),py=(int)((long)y*(height-1)/(rows-1));
            int pixel=argb[py*width+px];
            double r=((pixel>>>16)&255)/255.0,g=((pixel>>>8)&255)/255.0,b=(pixel&255)/255.0;
            double value=.2126*r+.7152*g+.0722*b;light[y*cols+x]=value;sum+=value;squares+=value*value;
            if(value<.07)dark++;if(value>.96)bright++;
            saturation+=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));
            int cell=Math.min(3,y*4/rows)*4+Math.min(3,x*4/cols);counts[cell]++;
            colors[cell*3]+=r;colors[cell*3+1]+=g;colors[cell*3+2]+=b;
        }
        for(int i=0;i<16;i++)for(int c=0;c<3;c++)colors[i*3+c]/=Math.max(1,counts[i]);
        double count=light.length,mean=sum/count,contrast=Math.sqrt(Math.max(0,squares/count-mean*mean));
        double laplacian=0,edges=0;int interior=0;
        for(int y=1;y<rows-1;y++)for(int x=1;x<cols-1;x++){
            int i=y*cols+x;double center=light[i];
            laplacian+=Math.abs(4*center-light[i-1]-light[i+1]-light[i-cols]-light[i+cols]);
            edges+=Math.abs(light[i+1]-light[i-1])+Math.abs(light[i+cols]-light[i-cols]);interior++;
        }
        laplacian/=interior;edges/=interior;
        double exposure=clamp(1-Math.abs(mean-.50)*1.55);
        double clipping=clamp(1-(dark/count)*.75-(bright/count)*.85);
        double detail=clamp(Math.sqrt(laplacian/.20));
        // Strong, spatially incoherent high-frequency noise should not win on sharpness alone.
        double noise=laplacian>.30&&laplacian>edges*1.75?.14:0;
        double quality=clamp(.30*exposure+.20*clipping+.19*clamp(contrast/.24)+.23*detail
            +.08*clamp(saturation/count/.40)-noise);
        boolean usable=mean>.055&&mean<.975&&dark/count<.92&&bright/count<.93&&contrast>.022&&quality>.24;
        long fingerprint=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){
            double left=cellLight(light,cols,rows,x,y),right=cellLight(light,cols,rows,x+1,y);
            if(left>right)fingerprint|=1L<<(y*8+x);
        }
        return new Frame(timestampMs,fingerprint,quality,usable,colors);
    }

    /** Quality first, with temporal spacing and nearby look-alike suppression; playback order is chronological. */
    public static List<Frame> select(List<Frame> candidates,int maxFrames,long minSpacingMs){
        if(minSpacingMs<0)throw new IllegalArgumentException("Negative frame spacing");
        if(candidates==null||maxFrames<=0)return Collections.emptyList();
        int limit=Math.min(MAX_FRAMES,maxFrames);ArrayList<Frame> ranked=new ArrayList<>();
        for(Frame frame:candidates)if(frame!=null&&frame.usable)ranked.add(frame);
        Collections.sort(ranked,(a,b)->{int order=Double.compare(b.quality,a.quality);return order!=0?order:Long.compare(a.timestampMs,b.timestampMs);});
        ArrayList<Frame> selected=new ArrayList<>();
        long duplicateWindow=Math.max(30000,minSpacingMs>Long.MAX_VALUE/3?Long.MAX_VALUE:minSpacingMs*3);
        for(Frame frame:ranked){
            boolean duplicate=false;
            for(Frame previous:selected){
                long distance=frame.timestampMs>=previous.timestampMs?frame.timestampMs-previous.timestampMs:previous.timestampMs-frame.timestampMs;
                if(distance<minSpacingMs||(distance<=duplicateWindow&&sameScene(frame,previous))){duplicate=true;break;}
            }
            if(!duplicate){selected.add(frame);if(selected.size()==limit)break;}
        }
        Collections.sort(selected,Comparator.comparingLong(frame->frame.timestampMs));
        return Collections.unmodifiableList(selected);
    }

    private static boolean sameScene(Frame left,Frame right){
        double difference=0;for(int i=0;i<left.colors.length;i++)difference+=Math.abs(left.colors[i]-right.colors[i]);
        difference/=left.colors.length;
        return difference<.045||(Long.bitCount(left.fingerprint^right.fingerprint)<=10&&difference<.10);
    }
    private static double cellLight(double[] light,int cols,int rows,int cellX,int cellY){
        int left=cellX*cols/9,right=Math.max(left+1,(cellX+1)*cols/9),top=cellY*rows/8,bottom=Math.max(top+1,(cellY+1)*rows/8);
        double sum=0;int count=0;
        for(int y=top;y<Math.min(rows,bottom);y++)for(int x=left;x<Math.min(cols,right);x++){sum+=light[y*cols+x];count++;}
        // Inputs smaller than the 9x8 hash grid use the closest available pixel.
        return count==0?light[Math.min(rows-1,top)*cols+Math.min(cols-1,left)]:sum/count;
    }
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
}
