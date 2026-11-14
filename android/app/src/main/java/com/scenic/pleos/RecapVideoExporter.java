package com.scenic.pleos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Offline 9:16 photo and imported-video recap encoder. Call export on a worker, never on the UI thread.
 * The output contains H.264 video AND a newly synthesized AAC soundtrack. It does
 * not record a camera, microphone, another driver, or any licensed music service.
 */
public final class RecapVideoExporter {
    public static final int WIDTH = 360, HEIGHT = 640, FPS = 12, SECONDS = 12;
    private static final int PHOTO_SECONDS = 9;
    private static final int SAMPLE_RATE = 44100;
    private static final long CODEC_TIMEOUT_MS = 15000;
    private static final long EXPORT_TIMEOUT_MS = 180000;
    private static final String VIDEO_MIME = "video/avc", AUDIO_MIME = "audio/mp4a-latm";

    public interface CancelCheck { boolean isCancelled(); }
    public interface Progress { void update(int percent); }

    public static final class Frame {
        public final String assetPath, title, credit, videoUri;
        public final long startMs,endMs;
        public Frame(String assetPath, String title, String credit) {
            this.assetPath = assetPath;this.videoUri=null;this.startMs=0;this.endMs=0;
            this.title = clean(title, 70);
            this.credit = clean(credit, 160);
        }
        public Frame(String uri,long startMs,long endMs,String title){this.assetPath=null;this.videoUri=uri;this.startMs=startMs;this.endMs=endMs;this.title=clean(title,70);this.credit="사용자가 가져온 영상";}
        public boolean personal(){return videoUri!=null;}
    }

    private RecapVideoExporter() { }

    /**
     * Writes a complete, independently playable MP4, or removes only this call's
     * partial files on failure. A nonempty existing output is never overwritten.
     * Accepts attributed bundled photos and user-selected content URI video clips.
     */
    public static File export(Context context, List<Frame> frames, String mood,
            String caption, File output, CancelCheck cancel, Progress progress) throws Exception {
        if (context == null || output == null) throw new IllegalArgumentException("Context and output are required");
        if (frames == null || frames.isEmpty() || frames.size() > 8)
            throw new IllegalArgumentException("Choose one to eight valid scenes");
        ArrayList<Frame> selected = new ArrayList<>(frames);
        for (Frame frame : selected) {
            if (frame == null || (frame.personal()?(!frame.videoUri.startsWith("content://")||frame.startMs<0||frame.endMs-frame.startMs<1000||frame.endMs-frame.startMs>8000):creditFor(frame.assetPath) == null))
                throw new IllegalArgumentException("Choose valid imported clips or attributed sample photographs");
        }
        if (output.exists() && output.length() != 0) throw new IOException("An export already exists at this path");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Cannot create export directory");
        String token = ".scenic-recap-" + UUID.randomUUID();
        File video = new File(parent, token + ".video.mp4");
        File audio = new File(parent, token + ".audio.m4a");
        File complete = new File(parent, token + ".complete.mp4");
        Guard guard = new Guard(cancel, progress);
        ArrayList<Bitmap> images = new ArrayList<>();
        ArrayList<MediaMetadataRetriever> clips=new ArrayList<>();
        final double duration=durationSeconds(selected);
        try {
            guard.check();
            for (Frame frame : selected) {
                if(frame.personal()){MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(context,Uri.parse(frame.videoUri));long length=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(frame.endMs>length)throw new IOException("Selected clip exceeds source duration");clips.add(retriever);images.add(null);}catch(Exception e){try{retriever.release();}catch(Exception ignored){}throw e;}continue;}
                clips.add(null);
                try (InputStream stream = context.getAssets().open(frame.assetPath)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    options.inSampleSize = 2;
                    Bitmap image = BitmapFactory.decodeStream(stream, null, options);
                    if (image == null) throw new IOException("A sample photograph could not be decoded");
                    images.add(image);
                }
            }
            guard.progress(1);
            encodeVideo(selected, images, clips, cleanCaption(caption), video, guard,duration);
            encodeAudio(mood, audio, guard,duration);
            combine(video, audio, complete, guard,duration);
            verifyTracks(complete,duration);
            guard.check();
            if (output.exists() && (output.length() != 0 || !output.delete())) throw new IOException("Cannot replace existing export file");
            if (!complete.renameTo(output)) throw new IOException("Cannot finish export file");
            guard.progress(100);
            return output;
        } finally {
            for (Bitmap image : images) if(image!=null)image.recycle();
            for(MediaMetadataRetriever clip:clips)if(clip!=null)try{clip.release();}catch(Exception ignored){}
            // These names were created exclusively for this export, not supplied by a user.
            video.delete();
            audio.delete();
            complete.delete();
        }
    }

    private static void encodeVideo(List<Frame> frames, List<Bitmap> images,List<MediaMetadataRetriever> clips,
            String caption, File output, Guard guard,double duration) throws Exception {
        Encoder encoder = createVideoEncoder(output);
        Bitmap canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        int[] pixels = new int[WIDTH * HEIGHT];
        byte[] yuv = new byte[WIDTH * HEIGHT * 3 / 2];
        try {
            int totalFrames=(int)Math.ceil(duration*FPS);
            for (int index = 0; index < totalFrames; index++) {
                guard.check();
                double seconds = index / (double) FPS;
                render(canvas, frames, images, clips,caption, seconds);
                canvasBitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                convertToYuv(pixels, yuv);
                int input = encoder.input(guard);
                int size;
                if (encoder.color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    Image image = encoder.codec.getInputImage(input);
                    if (image == null) throw new IOException("The video encoder did not provide a writable YUV image");
                    try { writeImage(image, yuv); } finally { image.close(); }
                    size = yuv.length;
                } else {
                    ByteBuffer buffer = encoder.codec.getInputBuffer(input);
                    if (buffer == null || buffer.capacity() < yuv.length) throw new IOException("Video input buffer is too small");
                    buffer.clear();
                    if (encoder.color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        int plane = WIDTH * HEIGHT;
                        buffer.put(yuv, 0, plane);
                        for (int p = 0; p < plane / 4; p++) {
                            buffer.put(yuv[plane + p]);
                            buffer.put(yuv[plane + plane / 4 + p]);
                        }
                    } else { buffer.put(yuv); }
                    size = yuv.length;
                }
                encoder.codec.queueInputBuffer(input, 0, size, index * 1000000L / FPS, 0);
                encoder.drain(false, guard);
                guard.progress(2 + (index + 1) * 70 / totalFrames);
            }
            encoder.end((long)(duration * 1000000L), guard);
            encoder.finish();
        } finally {
            canvasBitmap.recycle();
            encoder.close();
        }
    }

    private static Encoder createVideoEncoder(File output) throws IOException {
        final ArrayList<CodecChoice> choices = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!VIDEO_MIME.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    if (caps.getVideoCapabilities() != null && !caps.getVideoCapabilities().isSizeSupported(WIDTH, HEIGHT)) continue;
                    int color = chooseColor(caps.colorFormats);
                    if (color == 0) continue;
                    boolean software = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly()
                            : info.getName().startsWith("OMX.google.") || info.getName().startsWith("c2.android.");
                    choices.add(new CodecChoice(info.getName(), color, software));
                } catch (RuntimeException ignored) { }
            }
        }
        Collections.sort(choices, Comparator.comparingInt(choice -> choice.software ? 0 : 1));
        for (CodecChoice choice : choices) {
            MediaCodec codec = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, WIDTH, HEIGHT);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.color);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1400000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                codec = MediaCodec.createByCodecName(choice.name);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                return new Encoder(codec, new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), choice.color);
            } catch (Exception ignored) {
                if (codec != null) { try { codec.release(); } catch (RuntimeException unused) { } }
                output.delete();
            }
        }
        throw new IOException("This device has no available H.264 encoder for a portrait recap");
    }

    private static int chooseColor(int[] colors) {
        int[] preferred = {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible};
        for (int wanted : preferred) for (int color : colors) if (wanted == color) return wanted;
        return 0;
    }

    private static final class CodecChoice {
        final String name;
        final int color;
        final boolean software;
        CodecChoice(String name, int color, boolean software) { this.name = name; this.color = color; this.software = software; }
    }

    private static void encodeAudio(String mood, File output, Guard guard,double duration) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(AUDIO_MIME);
        Encoder encoder = null;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            encoder = new Encoder(codec, new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), 0);
            final int count = (int)Math.ceil(SAMPLE_RATE * duration);
            int sample = 0;
            double[] notes = notesFor(mood);
            while (sample < count) {
                guard.check();
                int input = encoder.input(guard);
                ByteBuffer buffer = codec.getInputBuffer(input);
                if (buffer == null) throw new IOException("Audio input buffer is unavailable");
                buffer.clear();
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                int amount = Math.min(1024, Math.min(count - sample, buffer.remaining() / 2));
                if (amount == 0) throw new IOException("Audio input buffer is too small");
                for (int i = 0; i < amount; i++) buffer.putShort(synthSample(sample + i, notes, mood,duration));
                codec.queueInputBuffer(input, 0, amount * 2, sample * 1000000L / SAMPLE_RATE, 0);
                sample += amount;
                encoder.drain(false, guard);
                guard.progress(72 + sample * 19 / count);
            }
            encoder.end((long)(duration * 1000000L), guard);
            encoder.finish();
        } finally {
            if (encoder != null) encoder.close();
            else { try { codec.release(); } catch (RuntimeException ignored) { } }
        }
    }

    /** Every PCM sample is generated here: no captured or externally sourced music. */
    private static short synthSample(int sample, double[] notes, String mood) {return synthSample(sample,notes,mood,SECONDS);}
    private static short synthSample(int sample, double[] notes, String mood,double duration) {
        double t = sample / (double) SAMPLE_RATE;
        double pad = 0, twoPi = Math.PI * 2;
        for (int voice = 0; voice < notes.length; voice++) {
            double breath = 0.78 + 0.22 * Math.sin(twoPi * t / 6.0 + voice * 0.7);
            pad += Math.sin(twoPi * notes[voice] * t) * breath;
        }
        double beat = ("bright".equals(mood) || "sea".equals(mood)) ? 1.5 : 3.0;
        int step = (int) (t / beat);
        double age = t - step * beat;
        double bellEnvelope = (1.0 - Math.exp(-age * 16)) * Math.exp(-age * 1.8);
        double frequency = notes[(step + 2) % notes.length] * 2;
        double bell = (Math.sin(twoPi * frequency * t) + 0.1 * Math.sin(twoPi * frequency * 2 * t)) * bellEnvelope;
        double fade = Math.max(0, Math.min(1, Math.min(t / 0.45, (duration - t) / 0.9)));
        double value = (pad * 0.047 + bell * 0.09) * fade;
        return (short) Math.round(Math.max(-0.8, Math.min(0.8, value)) * 32767);
    }

    private static double[] notesFor(String mood) {
        if ("night".equals(mood)) return new double[] {110, 164.814, 195.998, 246.942};
        if ("bright".equals(mood) || "sea".equals(mood)) return new double[] {146.832, 220, 293.665, 329.628};
        return new double[] {130.813, 164.814, 195.998, 293.665};
    }

    private static final class Encoder {
        final MediaCodec codec;
        final int color;
        MediaMuxer muxer;
        boolean muxerStarted, ended, codecReleased;
        int track = -1, samples;
        long timestampOffset;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Encoder(MediaCodec codec, MediaMuxer muxer, int color) { this.codec = codec; this.muxer = muxer; this.color = color; }

        int input(Guard guard) throws IOException {
            long since = SystemClock.elapsedRealtime();
            while (true) {
                guard.check();
                int index = codec.dequeueInputBuffer(10000);
                if (index >= 0) return index;
                drain(false, guard);
                if (SystemClock.elapsedRealtime() - since > CODEC_TIMEOUT_MS) throw new IOException("Encoder input timed out");
            }
        }

        void drain(boolean waitForEnd, Guard guard) throws IOException {
            long lastOutput = SystemClock.elapsedRealtime();
            while (!ended) {
                guard.check();
                int index = codec.dequeueOutputBuffer(info, waitForEnd ? 10000 : 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!waitForEnd) return;
                    if (SystemClock.elapsedRealtime() - lastOutput > CODEC_TIMEOUT_MS) throw new IOException("Encoder output timed out");
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("Encoder changed its format twice");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    lastOutput = SystemClock.elapsedRealtime();
                } else if (index >= 0) {
                    try {
                        ByteBuffer data = codec.getOutputBuffer(index);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0) {
                            if (data == null || !muxerStarted) throw new IOException("Encoder output arrived without a track");
                            if (samples == 0) timestampOffset = Math.max(0, -info.presentationTimeUs);
                            info.presentationTimeUs += timestampOffset;
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            muxer.writeSampleData(track, data, info);
                            samples++;
                        }
                        ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(index, false); }
                    lastOutput = SystemClock.elapsedRealtime();
                }
            }
        }

        void end(long timeUs, Guard guard) throws IOException {
            int index = input(guard);
            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true, guard);
        }

        void finish() throws IOException {
            if (!ended || !muxerStarted || samples == 0) throw new IOException("Encoder produced an incomplete track");
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;
            codec.stop();
            codec.release();
            codecReleased = true;
        }

        void close() {
            if (!codecReleased) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                try { codec.release(); } catch (RuntimeException ignored) { }
                codecReleased = true;
            }
            if (muxer != null) {
                if (muxerStarted) { try { muxer.stop(); } catch (RuntimeException ignored) { } }
                try { muxer.release(); } catch (RuntimeException ignored) { }
                muxer = null;
            }
        }
    }

    private static void combine(File video, File audio, File output, Guard guard,double duration) throws IOException {
        MediaExtractor[] extractors = {new MediaExtractor(), new MediaExtractor()};
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractors[0].setDataSource(video.getAbsolutePath());
            extractors[1].setDataSource(audio.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] tracks = new int[2];
            for (int i = 0; i < 2; i++) {
                if (extractors[i].getTrackCount() != 1) throw new IOException("Unexpected temporary media tracks");
                tracks[i] = muxer.addTrack(extractors[i].getTrackFormat(0));
                extractors[i].selectTrack(0);
            }
            muxer.start();
            started = true;
            ByteBuffer data = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                guard.check();
                long videoTime = extractors[0].getSampleTime(), audioTime = extractors[1].getSampleTime();
                if (videoTime < 0 && audioTime < 0) break;
                int next = audioTime < 0 || (videoTime >= 0 && videoTime <= audioTime) ? 0 : 1;
                MediaExtractor source = extractors[next];
                long size = source.getSampleSize();
                if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid encoded sample size");
                if (size > data.capacity()) data = ByteBuffer.allocateDirect((int) size);
                data.clear();
                int count = source.readSampleData(data, 0);
                if (count < 0) throw new IOException("An encoded sample is missing");
                info.set(0, count, source.getSampleTime(), source.getSampleFlags());
                muxer.writeSampleData(tracks[next], data, info);
                source.advance();
                guard.progress(91 + (int) Math.min(8, info.presentationTimeUs * 8 / (duration * 1000000L)));
            }
            muxer.stop();
            started = false;
        } finally {
            for (MediaExtractor extractor : extractors) extractor.release();
            if (muxer != null) {
                if (started) { try { muxer.stop(); } catch (RuntimeException ignored) { } }
                muxer.release();
            }
        }
    }

    private static void verifyTracks(File output,double expectedDuration) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        boolean video = false, audio = false;
        try {
            extractor.setDataSource(output.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (VIDEO_MIME.equals(mime)) {
                    video = format.getInteger(MediaFormat.KEY_WIDTH) == WIDTH && format.getInteger(MediaFormat.KEY_HEIGHT) == HEIGHT;
                } else if (AUDIO_MIME.equals(mime)) { audio = true; }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    long duration = format.getLong(MediaFormat.KEY_DURATION);
                    if (duration < expectedDuration*1000000L-500000L || duration > expectedDuration*1000000L+1000000L) throw new IOException("The recap track has an invalid duration");
                }
            }
            if (!video || !audio) throw new IOException("A complete video and soundtrack are required");
        } finally { extractor.release(); }
    }

    private static void convertToYuv(int[] pixels, byte[] target) {
        int plane = WIDTH * HEIGHT;
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                int rgb = pixels[row * WIDTH + column];
                int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
                target[row * WIDTH + column] = (byte) clamp(((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16);
            }
        }
        for (int row = 0; row < HEIGHT; row += 2) {
            for (int column = 0; column < WIDTH; column += 2) {
                int red = 0, green = 0, blue = 0;
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                    int rgb = pixels[(row + y) * WIDTH + column + x];
                    red += (rgb >> 16) & 255; green += (rgb >> 8) & 255; blue += rgb & 255;
                }
                red /= 4; green /= 4; blue /= 4;
                int position = row / 2 * (WIDTH / 2) + column / 2;
                target[plane + position] = (byte) clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128);
                target[plane + plane / 4 + position] = (byte) clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128);
            }
        }
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static void writeImage(Image image, byte[] yuv) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length != 3)
            throw new IOException("The encoder supplied an unsupported image layout");
        Image.Plane[] planes = image.getPlanes();
        int luma = WIDTH * HEIGHT;
        writePlane(planes[0], yuv, 0, WIDTH, HEIGHT);
        writePlane(planes[1], yuv, luma, WIDTH / 2, HEIGHT / 2);
        writePlane(planes[2], yuv, luma + luma / 4, WIDTH / 2, HEIGHT / 2);
    }

    private static void writePlane(Image.Plane plane, byte[] bytes, int offset, int width, int height) throws IOException {
        ByteBuffer buffer = plane.getBuffer();
        int start = buffer.position(), stride = plane.getRowStride(), step = plane.getPixelStride();
        if (start + (height - 1) * stride + (width - 1) * step >= buffer.limit())
            throw new IOException("The encoder image plane is too small");
        for (int row = 0; row < height; row++) {
            if (step == 1) {
                buffer.position(start + row * stride);
                buffer.put(bytes, offset + row * width, width);
            } else {
                for (int column = 0; column < width; column++)
                    buffer.put(start + row * stride + column * step, bytes[offset + row * width + column]);
            }
        }
    }

    public static double durationSeconds(List<Frame> frames){
        boolean personal=false,samples=false;double content=0;
        for(Frame frame:frames){personal|=frame.personal();samples|=!frame.personal();content+=frame.personal()?(frame.endMs-frame.startMs)/1000.0:3;}
        return personal?content+(samples?3:0):SECONDS;
    }
    private static void render(Canvas canvas, List<Frame> frames, List<Bitmap> images,List<MediaMetadataRetriever> clips,
            String caption, double seconds) throws IOException {
        boolean personal=false,samples=false;for(Frame f:frames){personal|=f.personal();samples|=!f.personal();}
        double content=durationSeconds(frames)-(samples?3:0);
        if (samples&&seconds >= content) { renderCredits(canvas, frames); return; }
        canvas.drawColor(Color.BLACK);
        int index=0;double age=seconds,span=personal?(frames.get(0).personal()?(frames.get(0).endMs-frames.get(0).startMs)/1000.0:3):PHOTO_SECONDS/(double)frames.size();
        while(index<frames.size()-1&&age>=span){age-=span;index++;Frame next=frames.get(index);span=personal?(next.personal()?(next.endMs-next.startMs)/1000.0:3):PHOTO_SECONDS/(double)frames.size();}
        double fraction=age/span;Frame selected=frames.get(index);
        if(selected.personal()){
            long at=Math.min(selected.endMs-1,selected.startMs+(long)(age*1000));
            Bitmap decoded=clips.get(index).getScaledFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST,WIDTH,HEIGHT);
            if(decoded==null)throw new IOException("A selected video frame could not be decoded");
            try{drawPhoto(canvas,decoded,1f,255);}finally{decoded.recycle();}
        }else{int alpha=age<0.35?(int)(255*age/0.35):255;drawPhoto(canvas,images.get(index),(float)(1+0.07*fraction),alpha);}
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 430, 0, HEIGHT, 0x00000000, 0xd9000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 430, WIDTH, HEIGHT, shade);
        String title=selected.title.isEmpty()?"기억하고 싶은 풍경":selected.title;
        if(!selected.personal()&&!title.contains("샘플"))title+=" · 샘플";
        Paint titlePaint=textPaint(26,Color.WHITE,true);titlePaint.setShadowLayer(2,0,1,0x80000000);
        drawLines(canvas,title,24,552,312,titlePaint,32,2);
    }

    private static void drawPhoto(Canvas canvas, Bitmap bitmap, float zoom, int alpha) {
        float scale = Math.max(WIDTH / (float) bitmap.getWidth(), HEIGHT / (float) bitmap.getHeight()) * zoom;
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        canvas.drawBitmap(bitmap, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new RectF((WIDTH - width) / 2, (HEIGHT - height) / 2, (WIDTH + width) / 2, (HEIGHT + height) / 2), paint);
    }

    private static void renderCredits(Canvas canvas, List<Frame> frames) {
        canvas.drawColor(0xfff4f1ea);
        canvas.drawText("사진 출처",24,47,textPaint(24,0xff202828,true));
        float y=84;
        Map<String, Credit> used = new LinkedHashMap<>();
        for (Frame frame : frames) if(!frame.personal())used.put(frame.assetPath, creditFor(frame.assetPath));
        for (Credit credit : used.values()) {
            y = drawLines(canvas, credit.title + " / " + credit.creator, 24, y, 312,
                    textPaint(10, 0xff202828, true), 13, 2);
            y += 4;
            y = drawLines(canvas, credit.license+" · 화면비 편집", 24, y, 312, textPaint(9, 0xff202828, false), 12, 2);
            y = drawLines(canvas, credit.licenseUrl, 24, y + 2, 312, textPaint(8, 0xff46504e, false), 10, 2);
            y = drawLines(canvas, credit.source, 24, y + 2, 312, textPaint(8, 0xff46504e, false), 10, 3);
            y += 17;
        }
    }

    private static Paint textPaint(float size, int color, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        return paint;
    }

    private static float drawLines(Canvas canvas, String value, float x, float y, float width,
            Paint paint, float lineHeight, int maxLines) {
        String text = value == null ? "" : value;
        int offset = 0;
        for (int line = 0; line < maxLines && offset < text.length(); line++) {
            int count = paint.breakText(text, offset, text.length(), true, width, null);
            if (count <= 0) break;
            if (offset + count < text.length() && Character.isHighSurrogate(text.charAt(offset + count - 1))) count--;
            if (count <= 0) break;
            String part = text.substring(offset, offset + count);
            if (line == maxLines - 1 && offset + count < text.length()) {
                while (!part.isEmpty() && paint.measureText(part + "…") > width) part = part.substring(0, part.offsetByCodePoints(part.length(), -1));
                part += "…";
            }
            canvas.drawText(part, x, y, paint);
            offset += count;
            while (offset < text.length() && text.charAt(offset) == ' ') offset++;
            y += lineHeight;
        }
        return y;
    }

    private static String clean(String value, int maxCodePoints) {
        if (value == null) return "";
        String text = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (text.codePointCount(0, text.length()) > maxCodePoints) text = text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
        return text;
    }

    private static String cleanCaption(String caption) {
        if (caption == null) return "";
        String[] parts = caption.replace("\r", "").split("\\n", 2);
        if (parts.length == 2) return clean(parts[0], 70) + "\n" + clean(parts[1], 120);
        return clean(caption, 120);
    }

    private static final class Credit {
        final String title, creator, license, licenseUrl, source;
        Credit(String title, String creator, String license, String licenseUrl, String source) {
            this.title = title; this.creator = creator; this.license = license; this.licenseUrl = licenseUrl; this.source = source;
        }
    }

    /** Values transcribed from scenic-demo/ATTRIBUTION.txt, retained in the video. */
    private static Credit creditFor(String path) {
        if ("scenic-demo/haeundae.jpg".equals(path)) return new Credit("Haeundae Beach", "부산광역시 / Busan Metropolitan City",
                "Korea Open Government License Type 1 (attribution)", "https://www.kogl.or.kr/info/licenseType1.do",
                "https://commons.wikimedia.org/wiki/File:Busan_City_Haeundae_Beach.jpg");
        if ("scenic-demo/gwangalli.jpg".equals(path)) return new Credit("Gwangalli Beach", "Masterhatch",
                "Creative Commons Attribution-ShareAlike 4.0 International", "https://creativecommons.org/licenses/by-sa/4.0/",
                "https://commons.wikimedia.org/wiki/File:Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg");
        if ("scenic-demo/coast.jpg".equals(path)) return new Credit("Cheongsapo harbour", "Andrewssi2",
                "Creative Commons Attribution-ShareAlike 3.0 Unported", "https://creativecommons.org/licenses/by-sa/3.0/",
                "https://commons.wikimedia.org/wiki/File:Cheongsapo.jpg");
        return null;
    }

    private static final class Guard {
        final CancelCheck cancel;
        final Progress callback;
        final long started = SystemClock.elapsedRealtime();
        int last = -1;
        Guard(CancelCheck cancel, Progress callback) { this.cancel = cancel; this.callback = callback; }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || (cancel != null && cancel.isCancelled()))
                throw new CancellationException("Recap export cancelled");
            if (SystemClock.elapsedRealtime() - started > EXPORT_TIMEOUT_MS) throw new IOException("Recap export timed out");
        }
        void progress(int percent) {
            if (percent <= last) return;
            last = percent;
            if (callback != null) callback.update(percent);
        }
    }
}
