/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.runners.Parameterized;
import org.twinlife.twinme.utils.MnemonicCodeUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Adapted from the <a href="https://github.com/bitcoinj/bitcoinj/blob/master/core/src/test/java/org/bitcoinj/crypto/MnemonicCodeVectorsTest.java">bitcoinj project</a>
 */
class MnemonicCodeUtilsTest {

    private final MnemonicCodeUtils mcu;
    private final MessageDigest md;

    public MnemonicCodeUtilsTest() throws NoSuchAlgorithmException {
        mcu = new MnemonicCodeUtils(InstrumentationRegistry.getInstrumentation().getTargetContext());
        md = MessageDigest.getInstance("SHA-256");
    }

    @BeforeEach
    public void setup() {

    }

    @ParameterizedTest(name = "Generate EN XOR + mnemonic {index} (data={0})")
    @MethodSource("englishXorData")
    void genXorEnglishWords(String data, String words) {
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        List<String> mnemonic = mcu.xorAndMnemonic(hash, Locale.ENGLISH);

        assertEquals(words, String.join(" ", mnemonic));
    }

    @ParameterizedTest(name = "Generate FR XOR + mnemonic {index} (data={0})")
    @MethodSource("frenchXorData")
    @Disabled("we only support english for now")
    void genXorFrenchWords(String data, String words) {
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        List<String> mnemonic = mcu.xorAndMnemonic(hash, Locale.FRENCH);

        assertEquals(words, String.join(" ", mnemonic));

    }

    /**
     * Check that xorAndMnemonic() falls back to english.
     */
    @Test
    void genXorUnsupportedLocale() {
        Object[] zero = englishXorData().iterator().next().get();

        String data = (String) zero[0];
        String words = (String) zero[1];

        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        List<String> mnemonic = mcu.xorAndMnemonic(hash, Locale.GERMAN);

        assertEquals(words, String.join(" ", mnemonic));
    }

    /**
     * Check that xorAndMnemonic() defaults to english.
     */
    @Test
    void genXorDefaultToEnglish() {
        Object[] zero = englishXorData().iterator().next().get();

        String data = (String) zero[0];
        String words = (String) zero[1];

        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        List<String> mnemonic = mcu.xorAndMnemonic(hash, null);

        assertEquals(words, String.join(" ", mnemonic));
    }

    @ParameterizedTest(name = "Generate mnemonic {index} (data={0})")
    @MethodSource("data")
    void toMnemonic(String data, String mnemonic) {
        byte[] bytes = hexStringToByteArray(data);

        List<String> words = mcu.toMnemonic(bytes);

        assertEquals(mnemonic, String.join(" ", words));
    }

    @ParameterizedTest(name = "Get suggestions {index} (prefix={0})")
    @MethodSource("suggestions")
    void getSuggestions(String prefix, String expectedSuggestions) {
        List<String> suggestions = mcu.getSuggestions(prefix);

        assertEquals(expectedSuggestions, String.join(" ", suggestions));
    }

    @ParameterizedTest(name = "Words to entropy {index} (data={0})")
    @MethodSource("data")
    void toEntropy(String data, String mnemonic) {
        byte[] expected = hexStringToByteArray(data);

        byte[] actual = mcu.toEntropy(Arrays.asList(mnemonic.split(" ")));

        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "Entropy to words {index} (data={0})")
    @MethodSource("data")
    void toWordList(String data, String expected) {
        byte[] d = hexStringToByteArray(data);

        List<String> actual = mcu.toMnemonic(d);

        assertEquals(expected, String.join(" ", actual));
    }



    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    @Parameterized.Parameters(name = "Vector set {index} (data={0}, mnemonic={1})")
    public static Collection<Arguments> suggestions() {
        return Arrays.asList(
                Arguments.of("abs", "absent absorb abstract absurd"),
                Arguments.of("d", "dad damage damp dance danger daring dash daughter dawn day deal debate debris decade december decide decline decorate decrease deer defense define defy degree delay deliver demand demise denial dentist deny depart depend deposit depth deputy derive describe desert design desk despair destroy detail detect develop device devote diagram dial diamond diary dice diesel diet differ digital dignity dilemma dinner dinosaur direct dirt disagree discover disease dish dismiss disorder display distance divert divide divorce dizzy doctor document dog doll dolphin domain donate donkey donor door dose double dove draft dragon drama drastic draw dream dress drift drill drink drip drive drop drum dry duck dumb dune during dust dutch duty dwarf dynamic"),
                Arguments.of("man", "man manage mandate mango mansion manual"),
                Arguments.of("nar", "narrow"),
                Arguments.of("tx", ""),
                Arguments.of("", "")
        );
    }

    /**
     * This method defines and supplies the parameters (test vectors) to be used in the testing of {@link MnemonicCodeUtils}.
     *
     * @return A list of groups of test vectors
     */
    @Parameterized.Parameters(name = "Vector set {index} (data={0}, mnemonic={1})")
    public static Collection<Arguments> data() {
        return Arrays.asList(
                /*
                 * The following vectors are from https://github.com/trezor/python-mnemonic/blob/master/vectors.json
                 */
                Arguments.of(
                        "00000000000000000000000000000000",
                        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"),
                Arguments.of(
                        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
                        "legal winner thank year wave sausage worth useful legal winner thank yellow"),
                Arguments.of(
                        "80808080808080808080808080808080",
                        "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"),
                Arguments.of(
                        "ffffffffffffffffffffffffffffffff",
                        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"),
                Arguments.of(
                        "000000000000000000000000000000000000000000000000",
                        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon agent"),
                Arguments.of(
                        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
                        "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal will"),
                Arguments.of(
                        "808080808080808080808080808080808080808080808080",
                        "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter always"),
                Arguments.of(
                        "ffffffffffffffffffffffffffffffffffffffffffffffff",
                        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo when"),
                Arguments.of(
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"),
                Arguments.of(
                        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
                        "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title"),
                Arguments.of(
                        "8080808080808080808080808080808080808080808080808080808080808080",
                        "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless"),
                Arguments.of(
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"),
                Arguments.of(
                        "9e885d952ad362caeb4efe34a8e91bd2",
                        "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"),
                Arguments.of(
                        "6610b25967cdcca9d59875f5cb50b0ea75433311869e930b",
                        "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow fog"),
                Arguments.of(
                        "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c",
                        "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length"),
                Arguments.of(
                        "c0ba5a8e914111210f2bd131f3d5e08d",
                        "scheme spot photo card baby mountain device kick cradle pact join borrow"),
                Arguments.of(
                        "6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3",
                        "horn tenant knee talent sponsor spell gate clip pulse soap slush warm silver nephew swap uncle crack brave"),
                Arguments.of(
                        "9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863",
                        "panda eyebrow bullet gorilla call smoke muffin taste mesh discover soft ostrich alcohol speed nation flash devote level hobby quick inner drive ghost inside"),
                Arguments.of(
                        "23db8160a31d3e0dca3688ed941adbf3",
                        "cat swing flag economy stadium alone churn speed unique patch report train"),
                Arguments.of(
                        "8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0",
                        "light rule cinnamon wrap drastic word pride squirrel upgrade then income fatal apart sustain crack supply proud access"),
                Arguments.of(
                        "066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad",
                        "all hour make first leader extend hole alien behind guard gospel lava path output census museum junior mass reopen famous sing advance salt reform"),
                Arguments.of(
                        "f30f8c1da665478f49b001d94c5fc452",
                        "vessel ladder alter error federal sibling chat ability sun glass valve picture"),
                Arguments.of(
                        "c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05",
                        "scissors invite lock maple supreme raw rapid void congress muscle digital elegant little brisk hair mango congress clump"),
                Arguments.of(
                        "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f",
                        "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold")
        );
    }

    private static Collection<Arguments> englishXorData() {
        return Arrays.asList(
                Arguments.of("00000000000000000000000000000000", "skate true elder pattern pride"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "issue family oyster dad glove"),
                Arguments.of("80808080808080808080808080808080", "circle forest fruit penalty build"),
                Arguments.of("ffffffffffffffffffffffffffffffff", "dinner tape assume galaxy aspect"),
                Arguments.of("000000000000000000000000000000000000000000000000", "this circle domain dose menu"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "dash today loop follow private"),
                Arguments.of("808080808080808080808080808080808080808080808080", "crater prize tooth wink purse"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffff", "reunion convince pizza benefit pattern"),
                Arguments.of("0000000000000000000000000000000000000000000000000000000000000000", "enough name green session squirrel"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "budget food cereal inflict payment"),
                Arguments.of("8080808080808080808080808080808080808080808080808080808080808080", "ghost shadow viable mercy exchange"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "party fix earn own used"),
                Arguments.of("9e885d952ad362caeb4efe34a8e91bd2", "solar cradle student vault drill"),
                Arguments.of("6610b25967cdcca9d59875f5cb50b0ea75433311869e930b", "situate rough runway drum exotic"),
                Arguments.of("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c", "blame stamp trouble bicycle rule"),
                Arguments.of("c0ba5a8e914111210f2bd131f3d5e08d", "permit cause finger rural print"),
                Arguments.of("6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3", "pair diamond moment thought dragon"),
                Arguments.of("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863", "sentence agree tape maze hub"),
                Arguments.of("23db8160a31d3e0dca3688ed941adbf3", "job real clog soda stuff"),
                Arguments.of("8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0", "ozone vast daring crater amount"),
                Arguments.of("066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad", "make unusual minute shaft install"),
                Arguments.of("f30f8c1da665478f49b001d94c5fc452", "strategy few clog ladder comic"),
                Arguments.of("c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05", "hundred arm install spoil impose"),
                Arguments.of("f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f", "baby ability best behind blind")
        );
    }

    private static Collection<Arguments> frenchXorData() {
        return Arrays.asList(
                Arguments.of("00000000000000000000000000000000", "refuge tenaille discuter muter offenser"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "grutier émulsion montagne congeler faucon"),
                Arguments.of("80808080808080808080808080808080", "caporal estomac évaluer natation biopsie"),
                Arguments.of("ffffffffffffffffffffffffffffffff", "danger sismique annexer exaucer angle"),
                Arguments.of("000000000000000000000000000000000000000000000000", "souffle caporal défrayer déjeuner lampe"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "copie sublime intime essence oisillon"),
                Arguments.of("808080808080808080808080808080808080808080808080", "clairon olfactif surface virus ortie"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffff", "phoque chien noirceur audace muter"),
                Arguments.of("0000000000000000000000000000000000000000000000000000000000000000", "draper lundi fictif pulpe ruiner"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "binaire essieu bustier gomme myrtille"),
                Arguments.of("8080808080808080808080808080808080808080808080808080808080808080", "exulter pupitre unifier lanceur effectif"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "murène épine deviner monnaie triage"),
                Arguments.of("9e885d952ad362caeb4efe34a8e91bd2", "retenir cirque scélérat turbine dépenser"),
                Arguments.of("6610b25967cdcca9d59875f5cb50b0ea75433311869e930b", "réflexe plumage policier dérober élégant"),
                Arguments.of("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c", "avoine saboter témoin avaler poivre"),
                Arguments.of("c0ba5a8e914111210f2bd131f3d5e08d", "navire brusque éolien pollen offrir"),
                Arguments.of("6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3", "mortier cupide limite soulever demeurer"),
                Arguments.of("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863", "public adhésif sismique kimono furieux"),
                Arguments.of("23db8160a31d3e0dca3688ed941adbf3", "hachoir parole cavalier résultat scénario"),
                Arguments.of("8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0", "monument tunnel copain clairon agréable"),
                Arguments.of("066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad", "jaune toxine lexique purifier gravir"),
                Arguments.of("f30f8c1da665478f49b001d94c5fc452", "sauvage enseigne cavalier hublot charbon"),
                Arguments.of("c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05", "galaxie amidon gravir rotule geyser"),
                Arguments.of("f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f", "arlequin abandon audible attentif badge"));
    }
}
