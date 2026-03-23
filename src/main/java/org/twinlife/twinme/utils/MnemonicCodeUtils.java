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

    @NonNull
    public List<String> getSuggestions(@NonNull String prefix) {
        return getSuggestions(prefix, null);
    }

    @NonNull
    public List<String> getSuggestions(@NonNull String prefix, @Nullable Locale locale) {
        List<String> wordList = getWordList(locale);
        List<String> suggestions = new ArrayList<>();

        prefix = prefix.trim().toLowerCase();
        if (prefix.isEmpty()) {
            return suggestions;
        }

        for (String word : wordList) {
            if (word.startsWith(prefix)) {
                suggestions.add(word);
            } else if (word.compareTo(prefix) > 0) {
                break;
            }
        }

        return suggestions;
    }

    /**
     * Convert mnemonic word list to original entropy value.
     */
    @Nullable
    public byte[] toEntropy(@NonNull List<String> words) {
        if (words.size() % 3 > 0) {
            Log.e(LOG_TAG, "Word list size must be multiple of three words. list size = " + words.size());
            return null;
        }

        if (words.isEmpty()) {
            Log.e(LOG_TAG, "Word list is empty.");
            return null;
        }


        List<String> wordList = getWordList(Locale.ENGLISH);

        // Look up all the words in the list and construct the
        // concatenation of the original entropy and the checksum.
        //
        int concatLenBits = words.size() * 11;
        boolean[] concatBits = new boolean[concatLenBits];
        int wordIndex = 0;
        for (String word : words) {
            // Find the words index in the wordlist.
            int ndx = Collections.binarySearch(wordList, word.toLowerCase());
            if (ndx < 0) {
                Log.e(LOG_TAG, "Unknown word: " + word);
                return null;
            }


            // Set the next 11 bits to the value of the index.
            for (int ii = 0; ii < 11; ++ii) {
                concatBits[(wordIndex * 11) + ii] = (ndx & (1 << (10 - ii))) != 0;
            }
            ++wordIndex;
        }

        int checksumLengthBits = concatLenBits / 33;
        int entropyLengthBits = concatLenBits - checksumLengthBits;

        // Extract original entropy as bytes.
        byte[] entropy = new byte[entropyLengthBits / 8];
        for (int ii = 0; ii < entropy.length; ++ii) {
            for (int jj = 0; jj < 8; ++jj) {
                if (concatBits[(ii * 8) + jj]) {
                    entropy[ii] |= (byte) (1 << (7 - jj));
                }
            }
        }

        // Take the digest of the entropy.
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(entropy);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); //Can't happen
        }

        boolean[] hashBits = bytesToBits(hash);

        // Check all the checksum bits.
        for (int i = 0; i < checksumLengthBits; ++i) {
            if (concatBits[entropyLengthBits + i] != hashBits[i]) {
                Log.e(LOG_TAG, "Invalid checksum");
                return null;
            }
        }

        return entropy;
    }

    /**
     * Convert entropy data to mnemonic word list.
     *
     * @param entropy entropy bits, length must be a multiple of 32 bits
     */
    @Nullable
    public ArrayList<String> toMnemonic(@NonNull byte[] entropy) {
        if (entropy.length % 4 != 0) {
            Log.e(LOG_TAG, "entropy length not multiple of 32 bits.");
            return null;
        }

        if (entropy.length == 0) {
            Log.e(LOG_TAG, "entropy is empty.");
            return null;
        }

        // We take initial entropy of ENT bits and compute its
        // checksum by taking first ENT / 32 bits of its SHA256 hash.

        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(entropy);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); //Can't happen
        }
        boolean[] hashBits = bytesToBits(hash);

        boolean[] entropyBits = bytesToBits(entropy);
        int checksumLengthBits = entropyBits.length / 32;

        // We append these bits to the end of the initial entropy.
        boolean[] concatBits = new boolean[entropyBits.length + checksumLengthBits];
        System.arraycopy(entropyBits, 0, concatBits, 0, entropyBits.length);
        System.arraycopy(hashBits, 0, concatBits, entropyBits.length, checksumLengthBits);

        // Next we take these concatenated bits and split them into
        // groups of 11 bits. Each group encodes number from 0-2047
        // which is a position in a wordlist.  We convert numbers into
        // words and use joined words as mnemonic sentence.

        ArrayList<String> words = new ArrayList<>();
        int nWords = concatBits.length / 11;
        for (int i = 0; i < nWords; ++i) {
            int index = 0;
            for (int j = 0; j < 11; ++j) {
                index <<= 1;
                if (concatBits[(i * 11) + j])
                    index |= 0x1;
            }
            words.add(getWordList(Locale.ENGLISH).get(index));
        }

        return words;
    }

    /*
      Private methods
     */

    @NonNull
    private byte[] xorBytes(@NonNull byte[] data) {
        byte[] result = new byte[8];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 8; j++) {
                result[j] = (byte) (result[j] ^ data[i * 8 + j]);
            }
        }
        return result;
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
