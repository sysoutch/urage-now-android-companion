package com.uragestudio.companion;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

final class AudioWaveformExtractor {
    private static final int TARGET_BARS = 96;

    float[] extract(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = findAudioTrack(extractor);
            if (track < 0) return new float[0];
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            return decode(extractor, decoder);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            extractor.release();
        }
    }

    private int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        return -1;
    }

    private float[] decode(MediaExtractor extractor, MediaCodec decoder) {
        List<Float> windows = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        while (!outputDone) {
            if (!inputDone) inputDone = queueInput(extractor, decoder);
            int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
            if (outputIndex >= 0) {
                ByteBuffer buffer = decoder.getOutputBuffer(outputIndex);
                if (buffer != null && info.size > 0) addWindows(buffer, info.offset, info.size, windows);
                decoder.releaseOutputBuffer(outputIndex, false);
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            }
        }
        return normalize(resample(windows, TARGET_BARS));
    }

    private boolean queueInput(MediaExtractor extractor, MediaCodec decoder) {
        int inputIndex = decoder.dequeueInputBuffer(10_000);
        if (inputIndex < 0) return false;
        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
        if (size < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        decoder.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), extractor.getSampleFlags());
        extractor.advance();
        return false;
    }

    private void addWindows(ByteBuffer source, int offset, int size, List<Float> values) {
        ByteBuffer pcm = source.duplicate().order(ByteOrder.nativeOrder());
        pcm.position(offset);
        pcm.limit(offset + size);
        ShortBuffer samples = pcm.slice().order(ByteOrder.nativeOrder()).asShortBuffer();
        int window = Math.max(1, samples.remaining() / 8);
        while (samples.hasRemaining()) {
            long total = 0;
            int count = 0;
            while (samples.hasRemaining() && count++ < window) total += Math.abs((int) samples.get());
            values.add(count == 0 ? 0 : total / (float) count);
        }
    }

    private float[] resample(List<Float> source, int targetSize) {
        if (source.isEmpty()) return new float[0];
        float[] result = new float[Math.min(targetSize, source.size())];
        for (int index = 0; index < result.length; index++) {
            int from = index * source.size() / result.length;
            int to = Math.max(from + 1, (index + 1) * source.size() / result.length);
            float peak = 0;
            for (int sample = from; sample < to; sample++) peak = Math.max(peak, source.get(sample));
            result[index] = peak;
        }
        return result;
    }

    private float[] normalize(float[] values) {
        float peak = 1;
        for (float value : values) peak = Math.max(peak, value);
        for (int index = 0; index < values.length; index++) values[index] = values[index] / peak;
        return values;
    }
}
