/*
 *  Copyright (c) 2021-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class AudioTrack {

    private static final String LOG_TAG = "AudioTrack";

    private final static int BYTES_IN_FLOAT = Float.SIZE / Byte.SIZE;
    private final static char SEPARATOR = '\n';

    private int mNbLines = 0;
    private String mFileName;
    private byte[] mBytes;

    public AudioTrack() {

    }

    public byte[] getBytes() {

        return mBytes;
    }

    public void initTrack(@NonNull String fileName, int nbLines, Context context) {

        mFileName = fileName;
        mNbLines = nbLines;

        String dataFilePath = fileName.substring(0, fileName.lastIndexOf('.'));
        dataFilePath = dataFilePath + ".dat";
        File dataFile = new File(dataFilePath);
        if (dataFile.exists()) {
            mBytes = readTrackFromFile(dataFilePath);
            if (mBytes == null) {
                File fileManager = new File(dataFilePath);
                fileManager.delete();
                drawTrack(context);
            }
        } else {
            drawTrack(context);
        }
    }

    private void drawTrack(Context context) {

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

            long kTimeOutUs = 5000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean endOfInputFile = false;

            short maxAmplitude = 0;
            short maxSample = 0;
            int countSample = 0;

            float durationInSeconds = duration / (float) TimeUnit.SECONDS.toMicros(1);
            float nbSampleInFile = durationInSeconds * sampleRate;
            int samplesPerLine = (int) nbSampleInFile / mNbLines;
            short[] maxSampleLines = new short[mNbLines];
            int indexLine = 0;
            while (!endOfInputFile) {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(kTimeOutUs);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        int size = mediaExtractor.readSampleData(inputBuffer, 0);
                        if (size < 0) {
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, size, mediaExtractor.getSampleTime(), 0);
                            mediaExtractor.advance();
                        }
                    }
                }

                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, kTimeOutUs);
                if (outputBufferIndex >= 0) {
                    ByteBuffer byteBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                    if (byteBuffer != null && info.size > 0) {
                        byteBuffer.position(info.offset);
                        byteBuffer.limit(info.offset + info.size);

                        ShortBuffer samples = byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int length = samples.remaining() / channelCount;

                        for (int i = 0; i < length; i++) {
                            countSample++;
                            short sample = (short) Math.abs(samples.get(i * channelCount));

                            maxAmplitude = (short) Math.max(maxAmplitude, sample);
                            maxSample = (short) Math.max(maxSample, sample);

                            if (countSample > samplesPerLine) {
                                if (indexLine >= maxSampleLines.length) {
                                    break;
                                }
                                maxSampleLines[indexLine] = maxSample;
                                maxSample = 0;
                                countSample = 0;
                                indexLine++;
                            }
                        }
                    }

                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        endOfInputFile = true;
                    }
                }
            }

            if (countSample > 0 && maxSampleLines.length < mNbLines) {
                maxSampleLines[indexLine] = maxSample;
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

            String version= "v" + getVersionName(context) + SEPARATOR;
            try (FileOutputStream fileOutputStream = new FileOutputStream(dataFile)) {
                fileOutputStream.write(version.getBytes(StandardCharsets.UTF_8));
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


    private byte[] readTrackFromFile(String dataFilePath) {

        File dataFile = new File(dataFilePath);

        int size = (int) dataFile.length();
        byte[] bytes = new byte[size];
        try (FileInputStream inputStream = new FileInputStream(dataFile)) {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
            bufferedInputStream.read(bytes, 0, bytes.length);
        } catch (IOException exception) {
            bytes = null;
        }

        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try (FileInputStream fileInputStream = new FileInputStream(dataFile)) {

            BufferedInputStream reader = new BufferedInputStream(fileInputStream);
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();

            int readByte;
            boolean findSeparator = false;

            while ((readByte = reader.read()) != -1) {
                if (readByte == SEPARATOR) {
                    findSeparator = true;
                    break;
                }
                headerBuffer.write(readByte);
            }

            if (!findSeparator) {
                return null;
            }

            String headerLine = new String(headerBuffer.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!headerLine.startsWith("v")) {
                return null;
            }

            ByteArrayOutputStream dataBuffer = new ByteArrayOutputStream();
            while ((readByte = reader.read()) != -1) {
                dataBuffer.write(readByte);
            }

            return dataBuffer.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private String getVersionName(Context context) {

        PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException lException) {
            return "";
        }

        if (packageInfo != null) {
            return packageInfo.versionName;
        }

        return "";
    }


}
