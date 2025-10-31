/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class AudioTrack {

    private static final String LOG_TAG = "AudioTrack";

    final static int BYTES_IN_FLOAT = Float.SIZE / Byte.SIZE;

    private int mNbLines = 0;
    private String mFileName;
    private byte[] mBytes;

    public AudioTrack() {

    }

    public byte[] getBytes() {

        return mBytes;
    }

    public void initTrack(@NonNull String fileName, int nbLines) {

        mFileName = fileName;
        mNbLines = nbLines;

        String dataFilePath = fileName.substring(0, fileName.lastIndexOf('.'));
        dataFilePath = dataFilePath + ".dat";
        File dataFile = new File(dataFilePath);
        if (dataFile.exists()) {
            int size = (int) dataFile.length();
            mBytes = new byte[size];
            try (FileInputStream inputStream = new FileInputStream(dataFile)) {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                bufferedInputStream.read(mBytes, 0, mBytes.length);
            } catch (IOException exception) {
                exception.printStackTrace();
                mBytes = null;
            }
        } else {
            drawTrack();
        }
    }

    private void drawTrack() {

        MediaExtractor mediaExtractor = new MediaExtractor();
        try {
            mediaExtractor.setDataSource(mFileName);
            MediaFormat mediaFormat = null;
            int numTracks = mediaExtractor.getTrackCount();
            for (int i = 0; i < numTracks; i++) {
                mediaFormat = mediaExtractor.getTrackFormat(i);
                String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
                if (mimeType != null && mimeType.startsWith("audio/")) {
                    mediaExtractor.selectTrack(i);
                    break;
                }
            }

            if (mediaFormat == null) {
                return;
            }

            long duration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
            int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);

            if (mimeType == null) {
                return;
            }

            MediaCodec mediaCodec = MediaCodec.createDecoderByType(mimeType);

            if (duration == 0) {
                return;
            }

            mediaCodec.configure(mediaFormat, null, null, 0);
            mediaCodec.start();

            ByteBuffer[] codecInputBuffers = mediaCodec.getInputBuffers();
            ByteBuffer[] codecOutputBuffers = mediaCodec.getOutputBuffers();

            long kTimeOutUs = 5000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputBufferIndex;
            boolean endOfInputeFile = false;

            short maxAmplitude = 0;
            short maxSample = 0;
            int countSample = 0;

            float durationInSeconds = duration / (float) TimeUnit.SECONDS.toMicros(1);
            float nbSampleInFile = durationInSeconds * sampleRate;
            int samplesPerLine = (int) nbSampleInFile / mNbLines;
            short[] maxSampleLines = new short[mNbLines];
            int indexLine = 0;

            while (!endOfInputeFile) {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(kTimeOutUs);
                if (inputBufferIndex >= 0) {
                    int size = mediaExtractor.readSampleData(codecInputBuffers[inputBufferIndex], 0);
                    if (size < 0) {
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        endOfInputeFile = true;
                    } else {
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, size, mediaExtractor.getSampleTime(), 0);
                        mediaExtractor.advance();
                    }
                }

                outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, kTimeOutUs);
                if (outputBufferIndex >= 0) {
                    ByteBuffer byteBuffer = codecOutputBuffers[outputBufferIndex];
                    ShortBuffer samples = byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                    int length = samples.remaining() / channelCount;
                    for (int i = 0; i < length; i++) {
                        countSample++;
                        maxAmplitude = (short) Math.max(maxAmplitude, Math.abs(samples.get(i * channelCount)));
                        maxSample = (short) Math.max(maxSample, Math.abs(samples.get(i * channelCount)));

                        if (countSample > samplesPerLine) {
                            if (indexLine > maxSampleLines.length) {
                                break;
                            }
                            maxSampleLines[indexLine] = maxSample;
                            maxSample = 0;
                            countSample = 0;
                            indexLine++;
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    codecOutputBuffers = mediaCodec.getOutputBuffers();
                }
            }

            mediaCodec.stop();
            mediaCodec.release();
            mediaExtractor.release();

            mBytes = new byte[mNbLines];
            float[] values = new float[mNbLines];
            for (int i = 0; i < maxSampleLines.length; ++i) {
                values[i] = maxSampleLines[i] / (float) maxAmplitude;
            }

            mBytes = toByteArray(values);

            String dataFilePath = mFileName.substring(0, mFileName.lastIndexOf('.'));
            dataFilePath = dataFilePath + ".dat";
            File dataFile = new File(dataFilePath);

            try (FileOutputStream fileOutputStream = new FileOutputStream(dataFile)) {
                fileOutputStream.write(mBytes);
            } catch (Exception ex) {
                mBytes = null;
            }

        } catch (IOException e) {
            mBytes = null;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Exception: ", e);
            mBytes = null;
        }
    }

    @NonNull
    private byte[] toByteArray(@NonNull float[] floatArray) {
        ByteBuffer buffer = ByteBuffer.allocate(floatArray.length * BYTES_IN_FLOAT);
        buffer.asFloatBuffer().put(floatArray);
        return buffer.array();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AudioTrack that = (AudioTrack) o;
        return mNbLines == that.mNbLines && Objects.equals(mFileName, that.mFileName) && mBytes.length == that.mBytes.length;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNbLines, mFileName, mBytes.length);
    }
}
