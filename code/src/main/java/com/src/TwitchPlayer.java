package com.src;

import org.bytedeco.javacv.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;

import javax.sound.sampled.*;
import java.nio.ShortBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Usage:
 *  Host mode (streaming):
 *    java -cp ... com.src.TwitchPlayer host <source> <rtmpUrl>
 *    where <source> = "webcam" OR path/to/local/video.mp4
 *    example: host webcam rtmp://localhost/live/stream1
 *    example: host /home/user/video.mp4 rtmp://localhost/live/stream1
 *
 *  Viewer mode (play):
 *    java -cp ... com.src.TwitchPlayer viewer <streamUrl>
 *    example: viewer rtmp://localhost/live/stream1
 */
public class TwitchPlayer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage:\n  host <source> <rtmpUrl>\n  viewer <streamUrl>");
            System.exit(1);
        }

        String mode = args[0];

        // enable ffmpeg logs (helpful for debugging)
        FFmpegLogCallback.set();

        try {
            if ("host".equalsIgnoreCase(mode)) {
                if (args.length < 3) {
                    System.err.println("Host usage: host <source> <rtmpUrl>");
                    System.exit(1);
                }
                String source = args[1];
                String rtmpUrl = args[2];
                streamHost(source, rtmpUrl);
            } else if ("viewer".equalsIgnoreCase(mode)) {
                String streamUrl = args[1];
                viewStream(streamUrl);
            } else {
                System.err.println("Unknown mode: " + mode);
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Host: read from webcam or file and push to RTMP ---
    static void streamHost(String source, String rtmpUrl) {
        FrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;

        try {
            // choose grabber
            if ("webcam".equalsIgnoreCase(source)) {
                // index 0 default webcam
                grabber = new OpenCVFrameGrabber(0);
                // optionally set resolution:
                // ((OpenCVFrameGrabber) grabber).setImageWidth(1280);
                // ((OpenCVFrameGrabber) grabber).setImageHeight(720);
            } else {
                // local file or other FFmpeg-readable source
                grabber = new FFmpegFrameGrabber(source);
            }

            grabber.start();

            // get video properties
            int width = grabber.getImageWidth() > 0 ? grabber.getImageWidth() : 640;
            int height = grabber.getImageHeight() > 0 ? grabber.getImageHeight() : 480;
            double frameRate = grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 25;
            int audioChannels = grabber.getAudioChannels();
            int sampleRate = grabber.getSampleRate();

            System.out.printf("Source started: %dx%d @ %.2f fps, audioChannels=%d, sampleRate=%d%n",
                    width, height, frameRate, audioChannels, sampleRate);

            // create recorder for RTMP
            // rtmpUrl example: rtmp://localhost/live/stream1
            recorder = new FFmpegFrameRecorder(rtmpUrl, width, height, audioChannels);
            recorder.setFormat("flv");
            recorder.setInterleaved(true);

            // video settings
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setVideoOption("preset", "veryfast");
            recorder.setVideoOption("tune", "zerolatency");
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(frameRate);
            recorder.setGopSize((int) frameRate * 2);

            // audio settings if present
            if (audioChannels > 0) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setAudioChannels(audioChannels);
                recorder.setSampleRate(sampleRate > 0 ? sampleRate : 44100);
                recorder.setAudioOption("crf", "0");
                recorder.setAudioQuality(0);
            }

            recorder.start();
            System.out.println("Started recorder -> " + rtmpUrl);

            Frame frame;
            long startTs = System.currentTimeMillis();
            while ((frame = grabber.grab()) != null) {
                // forward frames (video or audio) directly
                recorder.record(frame);
                // optional: throttle for webcam (if no frame timestamp)
                // Thread.sleep((long) (1000.0 / frameRate));
            }

            System.out.println("Source finished (EOF).");
        } catch (Exception ex) {
            System.err.println("Error in host streaming:");
            ex.printStackTrace();
        } finally {
            try {
                if (recorder != null) {
                    recorder.stop();
                    recorder.release();
                }
            } catch (Exception ignore) {}
            try {
                if (grabber != null) {
                    grabber.stop();
                    grabber.release();
                }
            } catch (Exception ignore) {}
        }
    }

    // --- Viewer: grab stream and show in window + play audio ---
    static void viewStream(String streamUrl) {
        FFmpegFrameGrabber grabber = null;
        CanvasFrame canvas = null;
        SourceDataLine audioLine = null;

        while (true) { // reconnect loop — exits only on explicit break
            try {
                grabber = new FFmpegFrameGrabber(streamUrl);
                // sometimes helps to allow protocols
                grabber.setOption("protocol_whitelist", "file,http,https,tcp,tls,rtmp");
                grabber.start();

                double gamma = grabber.getGamma() > 0 ? grabber.getGamma() : 1.0;
                canvas = new CanvasFrame("Viewer - " + streamUrl, CanvasFrame.getDefaultGamma() / gamma);
                canvas.setDefaultCloseOperation(javax.swing.JFrame.DISPOSE_ON_CLOSE);

                int audioChannels = grabber.getAudioChannels();
                int sampleRate = grabber.getSampleRate();
                if (audioChannels > 0 && sampleRate > 0) {
                    AudioFormat audioFormat = new AudioFormat((float) sampleRate, 16, audioChannels, true, false);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    audioLine = (SourceDataLine) AudioSystem.getLine(info);
                    audioLine.open(audioFormat);
                    audioLine.start();
                }

                Frame frame;
                while (canvas.isVisible() && (frame = grabber.grab()) != null) {
                    if (frame.image != null) {
                        canvas.showImage(frame);
                    }
                    if (frame.samples != null && audioLine != null) {
                        for (Object s : frame.samples) {
                            if (s instanceof ShortBuffer) {
                                ShortBuffer sb = (ShortBuffer) s;
                                sb.rewind();
                                int samples = sb.remaining();
                                byte[] audioBytes = new byte[samples * 2];
                                for (int i = 0; i < samples; i++) {
                                    short val = sb.get();
                                    audioBytes[2 * i] = (byte) (val & 0xff);
                                    audioBytes[2 * i + 1] = (byte) ((val >> 8) & 0xff);
                                }
                                audioLine.write(audioBytes, 0, audioBytes.length);
                            } else if (s instanceof java.nio.ByteBuffer) {
                                java.nio.ByteBuffer bb = (java.nio.ByteBuffer) s;
                                byte[] buf = new byte[bb.remaining()];
                                bb.get(buf);
                                audioLine.write(buf, 0, buf.length);
                            }
                        }
                    }
                }

                // normal exit from inner loop: either window closed or EOF
                System.out.println("Viewer finished reading (window closed or EOF).");
                break;
            } catch (Exception ex) {
                System.err.println("Viewer error: " + ex.getMessage());
                ex.printStackTrace();
                // try to reconnect after short delay
                try {
                    if (canvas != null) canvas.dispose();
                    if (audioLine != null) {
                        audioLine.drain(); audioLine.stop(); audioLine.close();
                    }
                    if (grabber != null) { grabber.stop(); grabber.release(); }
                } catch (Exception ignore) {}
                System.out.println("Reconnecting in 3 seconds...");
                try { TimeUnit.SECONDS.sleep(3); } catch (InterruptedException ignored) {}
                // continue loop to reconnect
            } finally {
                try { if (canvas != null) canvas.dispose(); } catch (Exception ignore) {}
                try { if (audioLine != null) { audioLine.drain(); audioLine.stop(); audioLine.close(); } } catch (Exception ignore) {}
                try { if (grabber != null) { grabber.stop(); grabber.release(); } } catch (Exception ignore) {}
            }
        }
    }
}
