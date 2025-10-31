/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @ParameterizedTest(name = "Generate EN hash + mnemonic {index} (data={0})")
    @MethodSource("englishHashData")
    void genHashEnglishWords(String data, String words) {
        List<String> mnemonic = mcu.hashAndMnemonic(hexStringToByteArray(data), Locale.ENGLISH);

        assertEquals(words, String.join(" ", mnemonic));
    }

    @ParameterizedTest(name = "Generate FR hash + mnemonic {index} (data={0})")
    @MethodSource("frenchHashData")
    void genHashFrenchWords(String data, String words) {
        List<String> mnemonic = mcu.hashAndMnemonic(hexStringToByteArray(data), Locale.FRENCH);

        assertEquals(words, String.join(" ", mnemonic));
    }

    /**
     * Check that hashAndMnemonic() falls back to english.
     */
    @Test
    void genHashUnsupportedLocale() {
        Object[] zero = englishHashData().iterator().next().get();

        String data = (String) zero[0];
        String words = (String) zero[1];

        List<String> mnemonic = mcu.hashAndMnemonic(hexStringToByteArray(data), Locale.GERMAN);

        assertEquals(words, String.join(" ", mnemonic));
    }

    /**
     * Check that hashAndMnemonic() defaults to english.
     */
    @Test
    void genHashDefaultToEnglish() {
        Object[] zero = englishHashData().iterator().next().get();

        String data = (String) zero[0];
        String words = (String) zero[1];

        List<String> mnemonic = mcu.hashAndMnemonic(hexStringToByteArray(data), null);

        assertEquals(words, String.join(" ", mnemonic));
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


    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static Collection<Arguments> englishHashData() {
        return Arrays.asList(
                Arguments.of("00000000000000000000000000000000", "dance debate divide upon border turn fury suit into problem cruel express walnut oyster text mail kick summer flee fetch raw invite ten"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "march tragic paper ethics vendor vague leader warrior open extend slush library turn dose rule slow gadget doll hill catch ceiling burst alert"),
                Arguments.of("80808080808080808080808080808080", "excess rich turkey sword pull police theory plate crunch kite cancel olive pottery bright virus climb clay artwork call predict candy arch gold"),
                Arguments.of("ffffffffffffffffffffffffffffffff", "food cry govern sail govern afraid duty cram civil seat tuition grow key daring negative shove diagram chef alley town neglect crawl blur"),
                Arguments.of("000000000000000000000000000000000000000000000000", "outside love recycle hope century hunt tissue nation labor list open bachelor seven mango among snow token absorb body road render speed clever"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "grid donor pulse pact shield head kick always repair funny trap hold gasp tail cook recycle naive rookie talent coral sing super casual"),
                Arguments.of("808080808080808080808080808080808080808080808080", "valley sail talent occur leisure before dose modify rice stem melt tilt prepare wish marriage disease access keen have uncle clerk federal weather"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffff", "dwarf cook time cliff atom ranch kick mutual sign method ridge shoe visa fold summer uncle doctor airport pool across head devote bitter"),
                Arguments.of("0000000000000000000000000000000000000000000000000000000000000000", "grid duck problem valid cloth roof rate wealth merit insane toe divorce maximum medal between sword crisp orient appear rate speak question pig"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "bless degree base toe expose engage sword flush expire title crew radio decide hair duty useless juice damp warfare access thing expose episode"),
                Arguments.of("8080808080808080808080808080808080808080808080808080808080808080", "runway pull april crawl latin habit army unit tumble muffin maze head draw enemy side settle royal hope hill odor angry august indoor"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "question rack talk bus change quit walnut matter foam fantasy valley dilemma cliff observe torch below unusual tool shoulder seek model evolve access"),
                Arguments.of("9e885d952ad362caeb4efe34a8e91bd2", "beef consider atom fossil below gym purity unit ghost follow wet water kiss ocean limb decade awkward field cancel crazy hammer nominee transfer"),
                Arguments.of("6610b25967cdcca9d59875f5cb50b0ea75433311869e930b", "endless romance grow brief walnut lesson burden float shallow lottery verb amused abstract embrace aim scissors fade vibrant web foil latin swallow finger"),
                Arguments.of("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c", "able sunset cigar file immune ship use parent pull vast address scorpion science later split submit clever beyond rack awake bean trip book"),
                Arguments.of("c0ba5a8e914111210f2bd131f3d5e08d", "also finish lawsuit soda canvas invest earth must hybrid pen bulk poem screen flush excess accuse angry bleak candy stairs elder lemon extend"),
                Arguments.of("6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3", "hamster spin aisle crazy order wood dove caught orange fiber card degree feature height solution scrap they survey artwork slender ice crunch review"),
                Arguments.of("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863", "praise isolate sniff track force favorite edge salon innocent old capital body license room icon boost mass almost trouble legal empty cram betray"),
                Arguments.of("23db8160a31d3e0dca3688ed941adbf3", "magic jacket jewel spring notable wagon sure outdoor lucky logic source sphere ripple order kiwi bus lawsuit code decade curtain intact destroy noble"),
                Arguments.of("8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0", "claim easily spider become better similar hamster mesh cloth penalty elephant build punch blur text plate false steel smoke fun patient lucky wife"),
                Arguments.of("066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad", "pear evoke hen grid obey shadow typical fault like total toward mountain fame screen news dentist genius seed anger universe race better start"),
                Arguments.of("f30f8c1da665478f49b001d94c5fc452", "cannon convince click wish example need slab replace quiz good coin omit glove convince interest ostrich proof chase ridge approve coffee judge connect"),
                Arguments.of("c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05", "message utility stick portion purity arctic tennis luggage dish blur crush report sound crater hockey crunch zone trumpet vibrant hair bind diesel silent"),
                Arguments.of("f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f", "have raw charge vicious urban skin merry mango refuse reduce setup type acid dynamic section trip nuclear envelope mercy casino range peasant ten"));
    }

    private static Collection<Arguments> frenchHashData() {
        return Arrays.asList(
                Arguments.of("00000000000000000000000000000000", "contact corpus décorer train baril thérapie évolutif sécréter griller olivier coder élucider varier montagne songeur janvier hiberner sédatif épuisant enrichir paresse grogner solitude"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "jouissif talent mouton écrémer tuyau trivial imbiber vecteur meuble éluder renfort ineptie thérapie déjeuner poivre renard exact défensif fragile bronzer buffle blague adroit"),
                Arguments.of("80808080808080808080808080808080", "effacer pieuvre théorie siècle orageux nuire sosie nommer cogner honneur botte mérite observer bermuda usuel casque caribou ancien bondir océan boucle amateur féconder"),
                Arguments.of("ffffffffffffffffffffffffffffffff", "essieu coiffer féodal pondérer féodal actif détacher citoyen capter progrès terrible filleul heureux copain maillon ramasser cultiver calepin aérer système maintien clameur balancer"),
                Arguments.of("000000000000000000000000000000000000000000000000", "moderne invasion paternel frivole burin gambader strict machine housse innocent meuble armature punitif jeunesse agrafer réserve sucre aboutir bambin plaisir pensif rivière carreau"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "fidèle déglutir orbite moqueur quiétude fonderie hiberner agencer perdrix évidence tambour frelon exigence sincère chiffre paternel lunaire pliage sinistre chlorure rédiger sélectif brique"),
                Arguments.of("808080808080808080808080808080808080808080808080", "tronc pondérer sinistre meilleur imprimer atrium déjeuner ligue pierre salive laisser spiral octroyer vital joyeux débrider abriter hermine fluide tonique carotte englober verdure"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffff", "détester chiffre station casier anodin panorama hiberner lueur réanimer lavabo pinceau ragondin usure essayer sédatif tonique dédale admirer nuptial académie fonderie culminer avide"),
                Arguments.of("0000000000000000000000000000000000000000000000000000000000000000", "fidèle descente olivier trombone caviar plexus papyrus vénérer lanterne graine subtil décrire kayak lactose automne siècle cloche minimal alourdir papyrus rituel outrager neutron"),
                Arguments.of("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "axiome crabe assaut subtil éloge double siècle espadon éligible studieux cligner palper costume flairer détacher tricoter harmonie consonne vassal abriter soudure éloge dynastie"),
                Arguments.of("8080808080808080808080808080808080808080808080808080808080808080", "policier orageux alvéole clameur hygiène fixer amour tortue tétine louer kimono fonderie dénouer dossier réactif pulsar poète frivole fragile membre ajouter apaiser gloire"),
                Arguments.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "outrager palmarès siphon blanchir cabanon ovation varier juvénile espiègle encoche tronc damier casier médecin sursaut attraper toxine supplier rallonge prospère ligoter écureuil abriter"),
                Arguments.of("9e885d952ad362caeb4efe34a8e91bd2", "atome chercher anodin éteindre attraper fissure ornement tortue exulter essence vétéran veinard homard mélange ingérer cortège ardoise entier botte claquer flasque marathon talonner"),
                Arguments.of("6610b25967cdcca9d59875f5cb50b0ea75433311869e930b", "dosage pleurer filleul berline varier incolore bizarre ériger puzzle intrigue typique agrume aboyer domaine adjuger prétexte émotion union vérin esquiver hygiène sergent éolien"),
                Arguments.of("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c", "abdiquer séjour canular entraver germe racine treuil multiple orageux tunnel acerbe prévoir présence hydromel rotor scinder carreau autruche palmarès aquarium asticot taureau banquier"),
                Arguments.of("c0ba5a8e914111210f2bd131f3d5e08d", "affubler épaissir illicite résultat boulon grimper devoir lucratif gazelle narrer biscuit novateur prison espadon effacer absence ajouter axial boucle sabler discuter imputer éluder"),
                Arguments.of("6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3", "flatteur rosier adopter claquer mimique vivipare déloger brume migrer entasser brasier crabe engager formuler retracer prince soucieux séparer ancien relief gazon cogner phrase"),
                Arguments.of("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863", "occuper gruger requin tablier estime enfouir digital position goutte mercredi boussole bambin inexact plomb géant barbier jugement affaire témoin implorer donner citoyen augurer"),
                Arguments.of("23db8160a31d3e0dca3688ed941adbf3", "jaguar guerrier habitude rubis marqueur valve semence missile inviter insulter rigide rompre pivoter mimique honteux blanchir illicite cerise cortège comédie grenat cubique manteau"),
                Arguments.of("8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0", "capuche diable rondin atelier aurore recruter flatteur largeur caviar natation divertir biopsie oreille balancer songeur nommer emporter salade renvoi éventail musicien inviter vilain"),
                Arguments.of("066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad", "nageur écume fougère fidèle maximal pupitre titre enfermer infusion symbole synapse logique emprise prison mammouth crémeux exposer propre aimable totem palace aurore sacoche"),
                Arguments.of("f30f8c1da665478f49b001d94c5fc452", "boueux chien carton vital éduquer maigre rejouer période oxyde fédérer cesser messager faucon chien griffure miracle opale cadre pinceau altesse cerner haricot chéquier"),
                Arguments.of("c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05", "larme triomphe samedi nutritif ornement ambigu sombre invoquer débutant balancer cohésion permuter rideau clairon freiner cogner zeste teneur union flairer avenir cyanure recevoir"),
                Arguments.of("f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f", "fluide paresse cadeau unique trèfle régulier lapin jeunesse pavoiser patience punaise titane absurde détourer prologue taureau matière durcir lanceur brillant papaye nappe solitude"));
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
