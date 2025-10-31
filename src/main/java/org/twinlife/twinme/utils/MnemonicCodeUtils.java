/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.android.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Utility class to generate mnemonic phrases. The word list is read from bip39_wordlist.txt. For now English and French are supported.
 *
 * <p>
 * Additional word lists are available in the <a href="https://github.com/bitcoin/bips/tree/master/bip-0039">bips repo</a>
 * </p>
 * <p>
 * Adapted from the <a href="https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/crypto/MnemonicCode.java">bitcoinj project</a>
 * </p>
 */
public class MnemonicCodeUtils {
    private static final String LOG_TAG = "MnemonicCodeUtils";
    private static final boolean DEBUG = false;

    private final Map<Locale, List<String>> mWordLists = new HashMap<>();

    @NonNull
    private final Context mContext;

    public MnemonicCodeUtils(@NonNull Context context) {
        this.mContext = context;
    }

    /**
     * XOR the input data, reducing its size to 8 bytes, and generate a word list.
     *
     * @param data   the data to convert. Must contain exactly 32 bytes.
     * @param locale the language of the words. English will be used as a fallback if locale is null, or we don't have a word list for this language.
     * @return a list of 5 words generated from the XORed input, or an empty list if data doesn't contain 32 bytes.
     */
    @NonNull
    public List<String> xorAndMnemonic(@NonNull byte[] data, @Nullable Locale locale) {
        if (DEBUG) {
            Log.d(LOG_TAG, "xorAndMnemonic data= " + Arrays.toString(data) + " locale= " + locale);
        }

        if (data.length != 32) {
            Log.e(LOG_TAG, "data must contain exactly 32 bytes, got " + data.length + " bytes");
            return Collections.emptyList();
        }

        List<String> wordList = getWordList(locale);

        if (wordList.isEmpty()) {
            return Collections.emptyList();
        }

        return getWords(xorBytes(data), wordList);
    }

    private byte[] xorBytes(byte[] data) {
        byte[] result = new byte[8];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 8; j++) {
                result[j] = (byte) (result[j] ^ data[i * 8 + j]);
            }
        }
        return result;
    }

    /**
     * Hash the input data and generate a word list.
     *
     * @param data   the data to convert.
     * @param locale the language of the words. English will be used as a fallback if we don't have a word list for this language.
     * @return a list of 23 words generated from the hash, or an empty list if data is empty.
     */
    @NonNull
    public List<String> hashAndMnemonic(@NonNull byte[] data, @Nullable Locale locale) {
        if (DEBUG) {
            Log.d(LOG_TAG, "toMnemonic data= " + Arrays.toString(data) + " locale= " + locale);
        }

        if (data.length == 0) {
            Log.e(LOG_TAG, "data is empty");
            return Collections.emptyList();
        }

        List<String> wordList = getWordList(locale);

        if (wordList.isEmpty()) {
            return Collections.emptyList();
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Log.e(LOG_TAG, "Could not get MessageDigest instance", e);
            return Collections.emptyList();
        }

        byte[] hash = md.digest(data);

        return getWords(hash, wordList);
    }

    @NonNull
    private List<String> getWords(@NonNull byte[] data, @NonNull List<String> wordList) {
        boolean[] dataBits = bytesToBits(data);

        // We take these bits and split them into
        // groups of 11 bits. Each group encodes number from 0-2047
        // which is a position in a wordlist.  We convert numbers into
        // words and use joined words as mnemonic sentence.

        List<String> words = new ArrayList<>();
        int nWords = dataBits.length / 11;
        for (int i = 0; i < nWords; ++i) {
            int index = 0;
            for (int j = 0; j < 11; ++j) {
                index <<= 1;
                if (dataBits[(i * 11) + j]) index |= 0x1;
            }
            words.add(wordList.get(index));
        }
        return words;
    }

    @NonNull
    private boolean[] bytesToBits(@NonNull byte[] data) {
        boolean[] bits = new boolean[data.length * 8];
        for (int i = 0; i < data.length; ++i)
            for (int j = 0; j < 8; ++j)
                bits[(i * 8) + j] = (data[i] & 0xff & (1 << (7 - j))) != 0;
        return bits;
    }

    private synchronized List<String> getWordList(@Nullable Locale locale) {
        if (locale == null) {
            locale = Locale.ENGLISH;
        }

        List<String> wordList = mWordLists.get(locale);

        if (wordList == null) {
            wordList = loadWordList(locale);
            mWordLists.put(locale, wordList);
        }

        return wordList;
    }

    @NonNull
    private List<String> loadWordList(@NonNull Locale locale) {
        List<String> words = new ArrayList<>();

        Configuration configuration = new Configuration(mContext.getResources().getConfiguration());
        configuration.setLocale(locale);

        try (InputStream inputStream = mContext.createConfigurationContext(configuration).getResources().openRawResource(R.raw.bip39_wordlist);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            while (reader.ready()) {
                words.add(reader.readLine());
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not load word list for locale " + locale, e);
        }

        return words;
    }
}
