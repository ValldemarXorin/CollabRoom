package com.src;

import org.bytedeco.javacv.*;
import org.bytedeco.ffmpeg.global.avcodec;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.nio.ByteOrder;
import org.bytedeco.ffmpeg.global.avutil;


public class LocalCaptureAndRecord {
    private static final int FRAME_RATE = 30;               // 30 fps
    private static final int VIDEO_WIDTH = 1280;            // 720p-ish (1280x720)
    private static final int VIDEO_HEIGHT = 720;
    private static final int AUDIO_CHANNELS = 1;            // можно 2, если микрофон стерео
    private static final int AUDIO_SAMPLE_RATE = 48000;     // 48 kHz — стандарт для видео/WebRTC
    private static final String OUTPUT_FILE = "output.flv";

    public static void main(String[] args) throws Exception {
        BlockingQueue<short[]> audioQueue = new ArrayBlockingQueue<>(50);

        // 1) start audio capture thread
        Thread audioThread = new Thread(() -> {
            try {
                captureAudioToQueue(audioQueue);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Audio-Capture");
        audioThread.setDaemon(true);
        audioThread.start();

        // 2) start video capture
        FrameGrabber grabber = FrameGrabber.createDefault(0); // device 0
        grabber.setImageWidth(VIDEO_WIDTH);
        grabber.setImageHeight(VIDEO_HEIGHT);
        grabber.start();

        // 3) preview window
        CanvasFrame canvas = new CanvasFrame("Preview", CanvasFrame.getDefaultGamma() / grabber.getGamma());
        canvas.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE);
        canvas.setCanvasSize(VIDEO_WIDTH, VIDEO_HEIGHT);

        // 4) recorder (h264 + aac inside FLV)
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(OUTPUT_FILE, VIDEO_WIDTH, VIDEO_HEIGHT, AUDIO_CHANNELS);
        recorder.setFormat("flv");

// Видео: увеличить битрейт и правильный pixel format
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setVideoBitrate(2_000_000); // 2 Mbps — хорошая отправная точка для 720p
        recorder.setFrameRate(FRAME_RATE);
        recorder.setGopSize(FRAME_RATE * 2);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P); // важно для совместимости
// улучшить компромисс скорость/качество: если CPU позволяет — используем medium or faster
        recorder.setVideoOption("preset", "fast"); // лучше качество, чуть медленнее
        recorder.setVideoOption("tune", "zerolatency"); // если нужна низкая задержка

// Аудио: 48kHz, AAC, достаточный битрейт
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        recorder.setSampleRate(AUDIO_SAMPLE_RATE);
        recorder.setAudioChannels(AUDIO_CHANNELS);
        recorder.setAudioBitrate(128_000); // 128 kbps — хорошее качество для голосовой/видеоконф

        recorder.start();

        Frame grabbedFrame;
        long startTime = System.currentTimeMillis();
        long frameIndex = 0;

        while (canvas.isVisible() && (grabbedFrame = grabber.grab()) != null) {
            // show local preview
            canvas.showImage(grabbedFrame);

            // record video frame
            recorder.record(grabbedFrame);

            // drain audio queue (non-blocking)
            short[] aud;
            while ((aud = audioQueue.poll()) != null) {
                ShortBuffer sb = ShortBuffer.wrap(aud);
                recorder.recordSamples(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, sb);
            }

            // keep approximate frame rate
            frameIndex++;
            long expectedTime = startTime + frameIndex * 1000 / FRAME_RATE;
            long wait = expectedTime - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
        }

        // cleanup
        recorder.stop();
        grabber.stop();
        canvas.dispose();
    }

    private static void captureAudioToQueue(BlockingQueue<short[]> queue) throws LineUnavailableException {
        AudioFormat format = new AudioFormat((float) AUDIO_SAMPLE_RATE, 16, AUDIO_CHANNELS, true, false); // false -> little-endian
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("Audio line not supported: " + format);
            return;
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        byte[] buffer = new byte[4096]; // можно увеличить до 8192 если теряются сэмплы

        try {
            while (!Thread.currentThread().isInterrupted()) {
                int n = line.read(buffer, 0, buffer.length);
                if (n > 0) {
                    // ОБЯЗАТЕЛЬНО указать порядок байтов — LITTLE_ENDIAN
                    ByteBuffer bb = ByteBuffer.wrap(buffer, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                    ShortBuffer sb = bb.asShortBuffer();
                    short[] shorts = new short[sb.remaining()];
                    sb.get(shorts);
                    // используем offer с таймаутом, чтобы не застрять при заполненной очереди
                    queue.offer(shorts);
                }
            }
        } finally {
            line.stop();
            line.close();
        }
    }

}
