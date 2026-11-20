// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max


/**
 * Unicode character data tables for the SafeRE regular expression library.
 * 
 * 
 * This class contains Perl character classes, POSIX character classes, case folding data, and
 * provides access to Unicode script and category groups. Category, script, block, and binary
 * property tables are generated from a maintainer-selected JDK and checked into the repository.
 * Case folding data and ASCII Perl/POSIX classes are static tables ported from RE2's `unicode_casefold.cc` and `perl_groups.cc`.
 * 
 * 
 * Each character class is represented as an `int[][]` where each element is a `{lo, hi}` pair representing an inclusive range of Unicode code points.
 */
// This class is package-private and all arrays are generated Unicode data tables used for
// performance-critical character class lookups. They are not exposed to external callers.
internal object UnicodeTables {
    // Perl character classes
    val PERL_DIGIT: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x30, 0x39))

    val PERL_SPACE: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0x09, 0x0D), intArrayOf(0x20, 0x20),
    )

    val PERL_WORD: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0x30, 0x39), intArrayOf(0x41, 0x5A), intArrayOf(0x5F, 0x5F), intArrayOf(0x61, 0x7A),
    )

    // JDK horizontal whitespace (\h): [ \t\xA0\u1680\u180E\u2000-\u200A\u202F\u205F\u3000]
    val HORIZ_SPACE: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0x09, 0x09),
        intArrayOf(0x20, 0x20),
        intArrayOf(0xA0, 0xA0),
        intArrayOf(0x1680, 0x1680),
        intArrayOf(0x180E, 0x180E),
        intArrayOf(0x2000, 0x200A),
        intArrayOf(0x202F, 0x202F),
        intArrayOf(0x205F, 0x205F),
        intArrayOf(0x3000, 0x3000),
    )

    // JDK vertical whitespace (\v): [\n\x0B\f\r\x85\u2028\u2029]
    val VERT_SPACE: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0x0A, 0x0D), intArrayOf(0x85, 0x85), intArrayOf(0x2028, 0x2029),
    )

    val PERL_GROUPS: Map<String, Array<IntArray>> = mapOf(
        "\\d" to PERL_DIGIT,
        "\\s" to PERL_SPACE,
        "\\w" to PERL_WORD,
        "\\h" to HORIZ_SPACE,
        "\\v" to VERT_SPACE
    )

    // POSIX character classes
    val POSIX_ALNUM: Array<IntArray> =
        arrayOf<IntArray>(intArrayOf(0x30, 0x39), intArrayOf(0x41, 0x5A), intArrayOf(0x61, 0x7A))
    val POSIX_ALPHA: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x41, 0x5A), intArrayOf(0x61, 0x7A))
    val POSIX_ASCII: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x00, 0x7F))
    val POSIX_BLANK: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x09, 0x09), intArrayOf(0x20, 0x20))
    val POSIX_CNTRL: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x00, 0x1F), intArrayOf(0x7F, 0x7F))
    val POSIX_DIGIT: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x30, 0x39))
    val POSIX_GRAPH: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x21, 0x7E))
    val POSIX_LOWER: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x61, 0x7A))
    val POSIX_PRINT: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x20, 0x7E))
    val POSIX_PUNCT: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0x21, 0x2F), intArrayOf(0x3A, 0x40), intArrayOf(0x5B, 0x60), intArrayOf(0x7B, 0x7E)
    )
    val POSIX_SPACE: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x09, 0x0D), intArrayOf(0x20, 0x20))
    val POSIX_UPPER: Array<IntArray> = arrayOf<IntArray>(intArrayOf(0x41, 0x5A))
    val POSIX_XDIGIT: Array<IntArray> =
        arrayOf<IntArray>(intArrayOf(0x30, 0x39), intArrayOf(0x41, 0x46), intArrayOf(0x61, 0x66))

    /**
     * POSIX character class names for use with the `\p{...}` property syntax (e.g., `\p{Lower}`). These are the 13 POSIX classes defined in the JDK's `java.util.regex.Pattern` documentation. In the default (non-UNICODE_CHARACTER_CLASS) mode, they
     * match ASCII-only ranges.
     */
    val POSIX_PROPERTY_GROUPS: Map<String, Array<IntArray>> = mapOf(
        "Lower" to POSIX_LOWER,
        "Upper" to POSIX_UPPER,
        "ASCII" to POSIX_ASCII,
        "Alpha" to POSIX_ALPHA,
        "Digit" to POSIX_DIGIT,
        "Alnum" to POSIX_ALNUM,
        "Punct" to POSIX_PUNCT,
        "Graph" to POSIX_GRAPH,
        "Print" to POSIX_PRINT,
        "Blank" to POSIX_BLANK,
        "Cntrl" to POSIX_CNTRL,
        "XDigit" to POSIX_XDIGIT,
        "Space" to POSIX_SPACE
    )

    fun unicodePosixPropertyGroups(): Map<String, Array<IntArray>> {
        return UnicodePosixHolder.UNICODE_POSIX_PROPERTY_GROUPS
    }

    // Case folding sentinel values
    const val EVEN_ODD: Int = 1
    val ODD_EVEN: Int = -1
    val EVEN_ODD_SKIP: Int = 1 shl 30
    val ODD_EVEN_SKIP: Int = (1 shl 30) + 1

    // CASE_FOLD and TO_LOWER are static tables updated to Unicode 17.0 (CaseFolding.txt).
    //
    // Like generated Unicode property tables, these tables are safe to keep ahead of the running
    // JDK's Unicode version. If a fold entry references a code point that the JDK doesn't yet
    // recognize, the fold arithmetic still works (it's just integer addition), but the code point
    // will be classified as UNASSIGNED by runtime JDK-specific java* properties. Unicode's stability
    // policy guarantees that once a code point is assigned, its identity never changes.
    //
    // A canary test (UnicodeJdkConsistencyTest.caseFoldCoversAllJdkPairs) detects when a newer
    // JDK adds case pairs that are not yet in this table.
    val CASE_FOLD: Array<IntArray> = initCaseFold()

    private fun initCaseFold(): Array<IntArray> {
        return arrayOf<IntArray>(
            intArrayOf(65, 90, 32),
            intArrayOf(97, 106, -32),
            intArrayOf(107, 107, 8383),
            intArrayOf(108, 114, -32),
            intArrayOf(115, 115, 268),
            intArrayOf(116, 122, -32),
            intArrayOf(181, 181, 743),
            intArrayOf(192, 214, 32),
            intArrayOf(216, 222, 32),
            intArrayOf(223, 223, 7615),
            intArrayOf(224, 228, -32),
            intArrayOf(229, 229, 8262),
            intArrayOf(230, 246, -32),
            intArrayOf(248, 254, -32),
            intArrayOf(255, 255, 121),
            intArrayOf(256, 303, EVEN_ODD),
            intArrayOf(306, 311, EVEN_ODD),
            intArrayOf(313, 328, ODD_EVEN),
            intArrayOf(330, 375, EVEN_ODD),
            intArrayOf(376, 376, -121),
            intArrayOf(377, 382, ODD_EVEN),
            intArrayOf(383, 383, -300),
            intArrayOf(384, 384, 195),
            intArrayOf(385, 385, 210),
            intArrayOf(386, 389, EVEN_ODD),
            intArrayOf(390, 390, 206),
            intArrayOf(391, 392, ODD_EVEN),
            intArrayOf(393, 394, 205),
            intArrayOf(395, 396, ODD_EVEN),
            intArrayOf(398, 398, 79),
            intArrayOf(399, 399, 202),
            intArrayOf(400, 400, 203),
            intArrayOf(401, 402, ODD_EVEN),
            intArrayOf(403, 403, 205),
            intArrayOf(404, 404, 207),
            intArrayOf(405, 405, 97),
            intArrayOf(406, 406, 211),
            intArrayOf(407, 407, 209),
            intArrayOf(408, 409, EVEN_ODD),
            intArrayOf(410, 410, 163),
            intArrayOf(411, 411, 42561),
            intArrayOf(412, 412, 211),
            intArrayOf(413, 413, 213),
            intArrayOf(414, 414, 130),
            intArrayOf(415, 415, 214),
            intArrayOf(416, 421, EVEN_ODD),
            intArrayOf(422, 422, 218),
            intArrayOf(423, 424, ODD_EVEN),
            intArrayOf(425, 425, 218),
            intArrayOf(428, 429, EVEN_ODD),
            intArrayOf(430, 430, 218),
            intArrayOf(431, 432, ODD_EVEN),
            intArrayOf(433, 434, 217),
            intArrayOf(435, 438, ODD_EVEN),
            intArrayOf(439, 439, 219),
            intArrayOf(440, 441, EVEN_ODD),
            intArrayOf(444, 445, EVEN_ODD),
            intArrayOf(447, 447, 56),
            intArrayOf(452, 452, EVEN_ODD),
            intArrayOf(453, 453, ODD_EVEN),
            intArrayOf(454, 454, -2),
            intArrayOf(455, 455, ODD_EVEN),
            intArrayOf(456, 456, EVEN_ODD),
            intArrayOf(457, 457, -2),
            intArrayOf(458, 458, EVEN_ODD),
            intArrayOf(459, 459, ODD_EVEN),
            intArrayOf(460, 460, -2),
            intArrayOf(461, 476, ODD_EVEN),
            intArrayOf(477, 477, -79),
            intArrayOf(478, 495, EVEN_ODD),
            intArrayOf(497, 497, ODD_EVEN),
            intArrayOf(498, 498, EVEN_ODD),
            intArrayOf(499, 499, -2),
            intArrayOf(500, 501, EVEN_ODD),
            intArrayOf(502, 502, -97),
            intArrayOf(503, 503, -56),
            intArrayOf(504, 543, EVEN_ODD),
            intArrayOf(544, 544, -130),
            intArrayOf(546, 563, EVEN_ODD),
            intArrayOf(570, 570, 10795),
            intArrayOf(571, 572, ODD_EVEN),
            intArrayOf(573, 573, -163),
            intArrayOf(574, 574, 10792),
            intArrayOf(575, 576, 10815),
            intArrayOf(577, 578, ODD_EVEN),
            intArrayOf(579, 579, -195),
            intArrayOf(580, 580, 69),
            intArrayOf(581, 581, 71),
            intArrayOf(582, 591, EVEN_ODD),
            intArrayOf(592, 592, 10783),
            intArrayOf(593, 593, 10780),
            intArrayOf(594, 594, 10782),
            intArrayOf(595, 595, -210),
            intArrayOf(596, 596, -206),
            intArrayOf(598, 599, -205),
            intArrayOf(601, 601, -202),
            intArrayOf(603, 603, -203),
            intArrayOf(604, 604, 42319),
            intArrayOf(608, 608, -205),
            intArrayOf(609, 609, 42315),
            intArrayOf(611, 611, -207),
            intArrayOf(612, 612, 42343),
            intArrayOf(613, 613, 42280),
            intArrayOf(614, 614, 42308),
            intArrayOf(616, 616, -209),
            intArrayOf(617, 617, -211),
            intArrayOf(618, 618, 42308),
            intArrayOf(619, 619, 10743),
            intArrayOf(620, 620, 42305),
            intArrayOf(623, 623, -211),
            intArrayOf(625, 625, 10749),
            intArrayOf(626, 626, -213),
            intArrayOf(629, 629, -214),
            intArrayOf(637, 637, 10727),
            intArrayOf(640, 640, -218),
            intArrayOf(642, 642, 42307),
            intArrayOf(643, 643, -218),
            intArrayOf(647, 647, 42282),
            intArrayOf(648, 648, -218),
            intArrayOf(649, 649, -69),
            intArrayOf(650, 651, -217),
            intArrayOf(652, 652, -71),
            intArrayOf(658, 658, -219),
            intArrayOf(669, 669, 42261),
            intArrayOf(670, 670, 42258),
            intArrayOf(837, 837, 84),
            intArrayOf(880, 883, EVEN_ODD),
            intArrayOf(886, 887, EVEN_ODD),
            intArrayOf(891, 893, 130),
            intArrayOf(895, 895, 116),
            intArrayOf(902, 902, 38),
            intArrayOf(904, 906, 37),
            intArrayOf(908, 908, 64),
            intArrayOf(910, 911, 63),
            intArrayOf(912, 912, 7235),
            intArrayOf(913, 929, 32),
            intArrayOf(931, 931, 31),
            intArrayOf(932, 939, 32),
            intArrayOf(940, 940, -38),
            intArrayOf(941, 943, -37),
            intArrayOf(944, 944, 7219),
            intArrayOf(945, 945, -32),
            intArrayOf(946, 946, 30),
            intArrayOf(947, 948, -32),
            intArrayOf(949, 949, 64),
            intArrayOf(950, 951, -32),
            intArrayOf(952, 952, 25),
            intArrayOf(953, 953, 7173),
            intArrayOf(954, 954, 54),
            intArrayOf(955, 955, -32),
            intArrayOf(956, 956, -775),
            intArrayOf(957, 959, -32),
            intArrayOf(960, 960, 22),
            intArrayOf(961, 961, 48),
            intArrayOf(962, 962, EVEN_ODD),
            intArrayOf(963, 965, -32),
            intArrayOf(966, 966, 15),
            intArrayOf(967, 968, -32),
            intArrayOf(969, 969, 7517),
            intArrayOf(970, 971, -32),
            intArrayOf(972, 972, -64),
            intArrayOf(973, 974, -63),
            intArrayOf(975, 975, 8),
            intArrayOf(976, 976, -62),
            intArrayOf(977, 977, 35),
            intArrayOf(981, 981, -47),
            intArrayOf(982, 982, -54),
            intArrayOf(983, 983, -8),
            intArrayOf(984, 1007, EVEN_ODD),
            intArrayOf(1008, 1008, -86),
            intArrayOf(1009, 1009, -80),
            intArrayOf(1010, 1010, 7),
            intArrayOf(1011, 1011, -116),
            intArrayOf(1012, 1012, -92),
            intArrayOf(1013, 1013, -96),
            intArrayOf(1015, 1016, ODD_EVEN),
            intArrayOf(1017, 1017, -7),
            intArrayOf(1018, 1019, EVEN_ODD),
            intArrayOf(1021, 1023, -130),
            intArrayOf(1024, 1039, 80),
            intArrayOf(1040, 1071, 32),
            intArrayOf(1072, 1073, -32),
            intArrayOf(1074, 1074, 6222),
            intArrayOf(1075, 1075, -32),
            intArrayOf(1076, 1076, 6221),
            intArrayOf(1077, 1085, -32),
            intArrayOf(1086, 1086, 6212),
            intArrayOf(1087, 1088, -32),
            intArrayOf(1089, 1090, 6210),
            intArrayOf(1091, 1097, -32),
            intArrayOf(1098, 1098, 6204),
            intArrayOf(1099, 1103, -32),
            intArrayOf(1104, 1119, -80),
            intArrayOf(1120, 1122, EVEN_ODD),
            intArrayOf(1123, 1123, 6180),
            intArrayOf(1124, 1153, EVEN_ODD),
            intArrayOf(1162, 1215, EVEN_ODD),
            intArrayOf(1216, 1216, 15),
            intArrayOf(1217, 1230, ODD_EVEN),
            intArrayOf(1231, 1231, -15),
            intArrayOf(1232, 1327, EVEN_ODD),
            intArrayOf(1329, 1366, 48),
            intArrayOf(1377, 1414, -48),
            intArrayOf(4256, 4293, 7264),
            intArrayOf(4295, 4295, 7264),
            intArrayOf(4301, 4301, 7264),
            intArrayOf(4304, 4346, 3008),
            intArrayOf(4349, 4351, 3008),
            intArrayOf(5024, 5103, 38864),
            intArrayOf(5104, 5109, 8),
            intArrayOf(5112, 5117, -8),
            intArrayOf(7296, 7296, -6254),
            intArrayOf(7297, 7297, -6253),
            intArrayOf(7298, 7298, -6244),
            intArrayOf(7299, 7299, -6242),
            intArrayOf(7300, 7300, EVEN_ODD),
            intArrayOf(7301, 7301, -6243),
            intArrayOf(7302, 7302, -6236),
            intArrayOf(7303, 7303, -6181),
            intArrayOf(7304, 7304, 35266),
            intArrayOf(7305, 7306, ODD_EVEN),
            intArrayOf(7312, 7354, -3008),
            intArrayOf(7357, 7359, -3008),
            intArrayOf(7545, 7545, 35332),
            intArrayOf(7549, 7549, 3814),
            intArrayOf(7566, 7566, 35384),
            intArrayOf(7680, 7776, EVEN_ODD),
            intArrayOf(7777, 7777, 58),
            intArrayOf(7778, 7829, EVEN_ODD),
            intArrayOf(7835, 7835, -59),
            intArrayOf(7838, 7838, -7615),
            intArrayOf(7840, 7935, EVEN_ODD),
            intArrayOf(7936, 7943, 8),
            intArrayOf(7944, 7951, -8),
            intArrayOf(7952, 7957, 8),
            intArrayOf(7960, 7965, -8),
            intArrayOf(7968, 7975, 8),
            intArrayOf(7976, 7983, -8),
            intArrayOf(7984, 7991, 8),
            intArrayOf(7992, 7999, -8),
            intArrayOf(8000, 8005, 8),
            intArrayOf(8008, 8013, -8),
            intArrayOf(8017, 8017, 8),
            intArrayOf(8019, 8019, 8),
            intArrayOf(8021, 8021, 8),
            intArrayOf(8023, 8023, 8),
            intArrayOf(8025, 8025, -8),
            intArrayOf(8027, 8027, -8),
            intArrayOf(8029, 8029, -8),
            intArrayOf(8031, 8031, -8),
            intArrayOf(8032, 8039, 8),
            intArrayOf(8040, 8047, -8),
            intArrayOf(8048, 8049, 74),
            intArrayOf(8050, 8053, 86),
            intArrayOf(8054, 8055, 100),
            intArrayOf(8056, 8057, 128),
            intArrayOf(8058, 8059, 112),
            intArrayOf(8060, 8061, 126),
            intArrayOf(8064, 8071, 8),
            intArrayOf(8072, 8079, -8),
            intArrayOf(8080, 8087, 8),
            intArrayOf(8088, 8095, -8),
            intArrayOf(8096, 8103, 8),
            intArrayOf(8104, 8111, -8),
            intArrayOf(8112, 8113, 8),
            intArrayOf(8115, 8115, 9),
            intArrayOf(8120, 8121, -8),
            intArrayOf(8122, 8123, -74),
            intArrayOf(8124, 8124, -9),
            intArrayOf(8126, 8126, -7289),
            intArrayOf(8131, 8131, 9),
            intArrayOf(8136, 8139, -86),
            intArrayOf(8140, 8140, -9),
            intArrayOf(8144, 8145, 8),
            intArrayOf(8147, 8147, -7235),
            intArrayOf(8152, 8153, -8),
            intArrayOf(8154, 8155, -100),
            intArrayOf(8160, 8161, 8),
            intArrayOf(8163, 8163, -7219),
            intArrayOf(8165, 8165, 7),
            intArrayOf(8168, 8169, -8),
            intArrayOf(8170, 8171, -112),
            intArrayOf(8172, 8172, -7),
            intArrayOf(8179, 8179, 9),
            intArrayOf(8184, 8185, -128),
            intArrayOf(8186, 8187, -126),
            intArrayOf(8188, 8188, -9),
            intArrayOf(8486, 8486, -7549),
            intArrayOf(8490, 8490, -8415),
            intArrayOf(8491, 8491, -8294),
            intArrayOf(8498, 8498, 28),
            intArrayOf(8526, 8526, -28),
            intArrayOf(8544, 8559, 16),
            intArrayOf(8560, 8575, -16),
            intArrayOf(8579, 8580, ODD_EVEN),
            intArrayOf(9398, 9423, 26),
            intArrayOf(9424, 9449, -26),
            intArrayOf(11264, 11311, 48),
            intArrayOf(11312, 11359, -48),
            intArrayOf(11360, 11361, EVEN_ODD),
            intArrayOf(11362, 11362, -10743),
            intArrayOf(11363, 11363, -3814),
            intArrayOf(11364, 11364, -10727),
            intArrayOf(11365, 11365, -10795),
            intArrayOf(11366, 11366, -10792),
            intArrayOf(11367, 11372, ODD_EVEN),
            intArrayOf(11373, 11373, -10780),
            intArrayOf(11374, 11374, -10749),
            intArrayOf(11375, 11375, -10783),
            intArrayOf(11376, 11376, -10782),
            intArrayOf(11378, 11379, EVEN_ODD),
            intArrayOf(11381, 11382, ODD_EVEN),
            intArrayOf(11390, 11391, -10815),
            intArrayOf(11392, 11491, EVEN_ODD),
            intArrayOf(11499, 11502, ODD_EVEN),
            intArrayOf(11506, 11507, EVEN_ODD),
            intArrayOf(11520, 11557, -7264),
            intArrayOf(11559, 11559, -7264),
            intArrayOf(11565, 11565, -7264),
            intArrayOf(42560, 42570, EVEN_ODD),
            intArrayOf(42571, 42571, -35267),
            intArrayOf(42572, 42605, EVEN_ODD),
            intArrayOf(42624, 42651, EVEN_ODD),
            intArrayOf(42786, 42799, EVEN_ODD),
            intArrayOf(42802, 42863, EVEN_ODD),
            intArrayOf(42873, 42876, ODD_EVEN),
            intArrayOf(42877, 42877, -35332),
            intArrayOf(42878, 42887, EVEN_ODD),
            intArrayOf(42891, 42892, ODD_EVEN),
            intArrayOf(42893, 42893, -42280),
            intArrayOf(42896, 42899, EVEN_ODD),
            intArrayOf(42900, 42900, 48),
            intArrayOf(42902, 42921, EVEN_ODD),
            intArrayOf(42922, 42922, -42308),
            intArrayOf(42923, 42923, -42319),
            intArrayOf(42924, 42924, -42315),
            intArrayOf(42925, 42925, -42305),
            intArrayOf(42926, 42926, -42308),
            intArrayOf(42928, 42928, -42258),
            intArrayOf(42929, 42929, -42282),
            intArrayOf(42930, 42930, -42261),
            intArrayOf(42931, 42931, 928),
            intArrayOf(42932, 42947, EVEN_ODD),
            intArrayOf(42948, 42948, -48),
            intArrayOf(42949, 42949, -42307),
            intArrayOf(42950, 42950, -35384),
            intArrayOf(42951, 42954, ODD_EVEN),
            intArrayOf(42955, 42955, -42343),
            intArrayOf(42956, 42971, EVEN_ODD),
            intArrayOf(42972, 42972, -42561),
            intArrayOf(42997, 42998, ODD_EVEN),
            intArrayOf(43859, 43859, -928),
            intArrayOf(43888, 43967, -38864),
            intArrayOf(64261, 64262, ODD_EVEN),
            intArrayOf(65313, 65338, 32),
            intArrayOf(65345, 65370, -32),
            intArrayOf(66560, 66599, 40),
            intArrayOf(66600, 66639, -40),
            intArrayOf(66736, 66771, 40),
            intArrayOf(66776, 66811, -40),
            intArrayOf(66928, 66938, 39),
            intArrayOf(66940, 66954, 39),
            intArrayOf(66956, 66962, 39),
            intArrayOf(66964, 66965, 39),
            intArrayOf(66967, 66977, -39),
            intArrayOf(66979, 66993, -39),
            intArrayOf(66995, 67001, -39),
            intArrayOf(67003, 67004, -39),
            intArrayOf(68736, 68786, 64),
            intArrayOf(68800, 68850, -64),
            intArrayOf(68944, 68965, 32),
            intArrayOf(68976, 68997, -32),
            intArrayOf(71840, 71871, 32),
            intArrayOf(71872, 71903, -32),
            intArrayOf(93760, 93791, 32),
            intArrayOf(93792, 93823, -32),
            intArrayOf(93856, 93880, 27),
            intArrayOf(93883, 93907, -27),
            intArrayOf(125184, 125217, 34),
            intArrayOf(125218, 125251, -34),
        )
    }

    val TO_LOWER: Array<IntArray> = initToLower()

    private fun initToLower(): Array<IntArray> {
        return arrayOf<IntArray>(
            intArrayOf(65, 90, 32),
            intArrayOf(181, 181, 775),
            intArrayOf(192, 214, 32),
            intArrayOf(216, 222, 32),
            intArrayOf(256, 302, EVEN_ODD_SKIP),
            intArrayOf(306, 310, EVEN_ODD_SKIP),
            intArrayOf(313, 327, ODD_EVEN_SKIP),
            intArrayOf(330, 374, EVEN_ODD_SKIP),
            intArrayOf(376, 376, -121),
            intArrayOf(377, 381, ODD_EVEN_SKIP),
            intArrayOf(383, 383, -268),
            intArrayOf(385, 385, 210),
            intArrayOf(386, 388, EVEN_ODD_SKIP),
            intArrayOf(390, 390, 206),
            intArrayOf(391, 391, ODD_EVEN),
            intArrayOf(393, 394, 205),
            intArrayOf(395, 395, ODD_EVEN),
            intArrayOf(398, 398, 79),
            intArrayOf(399, 399, 202),
            intArrayOf(400, 400, 203),
            intArrayOf(401, 401, ODD_EVEN),
            intArrayOf(403, 403, 205),
            intArrayOf(404, 404, 207),
            intArrayOf(406, 406, 211),
            intArrayOf(407, 407, 209),
            intArrayOf(408, 408, EVEN_ODD),
            intArrayOf(412, 412, 211),
            intArrayOf(413, 413, 213),
            intArrayOf(415, 415, 214),
            intArrayOf(416, 420, EVEN_ODD_SKIP),
            intArrayOf(422, 422, 218),
            intArrayOf(423, 423, ODD_EVEN),
            intArrayOf(425, 425, 218),
            intArrayOf(428, 428, EVEN_ODD),
            intArrayOf(430, 430, 218),
            intArrayOf(431, 431, ODD_EVEN),
            intArrayOf(433, 434, 217),
            intArrayOf(435, 437, ODD_EVEN_SKIP),
            intArrayOf(439, 439, 219),
            intArrayOf(440, 440, EVEN_ODD),
            intArrayOf(444, 444, EVEN_ODD),
            intArrayOf(452, 452, 2),
            intArrayOf(453, 453, ODD_EVEN),
            intArrayOf(455, 455, 2),
            intArrayOf(456, 456, EVEN_ODD),
            intArrayOf(458, 458, 2),
            intArrayOf(459, 475, ODD_EVEN_SKIP),
            intArrayOf(478, 494, EVEN_ODD_SKIP),
            intArrayOf(497, 497, 2),
            intArrayOf(498, 500, EVEN_ODD_SKIP),
            intArrayOf(502, 502, -97),
            intArrayOf(503, 503, -56),
            intArrayOf(504, 542, EVEN_ODD_SKIP),
            intArrayOf(544, 544, -130),
            intArrayOf(546, 562, EVEN_ODD_SKIP),
            intArrayOf(570, 570, 10795),
            intArrayOf(571, 571, ODD_EVEN),
            intArrayOf(573, 573, -163),
            intArrayOf(574, 574, 10792),
            intArrayOf(577, 577, ODD_EVEN),
            intArrayOf(579, 579, -195),
            intArrayOf(580, 580, 69),
            intArrayOf(581, 581, 71),
            intArrayOf(582, 590, EVEN_ODD_SKIP),
            intArrayOf(837, 837, 116),
            intArrayOf(880, 882, EVEN_ODD_SKIP),
            intArrayOf(886, 886, EVEN_ODD),
            intArrayOf(895, 895, 116),
            intArrayOf(902, 902, 38),
            intArrayOf(904, 906, 37),
            intArrayOf(908, 908, 64),
            intArrayOf(910, 911, 63),
            intArrayOf(913, 929, 32),
            intArrayOf(931, 939, 32),
            intArrayOf(962, 962, EVEN_ODD),
            intArrayOf(975, 975, 8),
            intArrayOf(976, 976, -30),
            intArrayOf(977, 977, -25),
            intArrayOf(981, 981, -15),
            intArrayOf(982, 982, -22),
            intArrayOf(984, 1006, EVEN_ODD_SKIP),
            intArrayOf(1008, 1008, -54),
            intArrayOf(1009, 1009, -48),
            intArrayOf(1012, 1012, -60),
            intArrayOf(1013, 1013, -64),
            intArrayOf(1015, 1015, ODD_EVEN),
            intArrayOf(1017, 1017, -7),
            intArrayOf(1018, 1018, EVEN_ODD),
            intArrayOf(1021, 1023, -130),
            intArrayOf(1024, 1039, 80),
            intArrayOf(1040, 1071, 32),
            intArrayOf(1120, 1152, EVEN_ODD_SKIP),
            intArrayOf(1162, 1214, EVEN_ODD_SKIP),
            intArrayOf(1216, 1216, 15),
            intArrayOf(1217, 1229, ODD_EVEN_SKIP),
            intArrayOf(1232, 1326, EVEN_ODD_SKIP),
            intArrayOf(1329, 1366, 48),
            intArrayOf(4256, 4293, 7264),
            intArrayOf(4295, 4295, 7264),
            intArrayOf(4301, 4301, 7264),
            intArrayOf(5112, 5117, -8),
            intArrayOf(7296, 7296, -6222),
            intArrayOf(7297, 7297, -6221),
            intArrayOf(7298, 7298, -6212),
            intArrayOf(7299, 7300, -6210),
            intArrayOf(7301, 7301, -6211),
            intArrayOf(7302, 7302, -6204),
            intArrayOf(7303, 7303, -6180),
            intArrayOf(7304, 7304, 35267),
            intArrayOf(7305, 7305, 1),
            intArrayOf(7312, 7354, -3008),
            intArrayOf(7357, 7359, -3008),
            intArrayOf(7680, 7828, EVEN_ODD_SKIP),
            intArrayOf(7835, 7835, -58),
            intArrayOf(7838, 7838, -7615),
            intArrayOf(7840, 7934, EVEN_ODD_SKIP),
            intArrayOf(7944, 7951, -8),
            intArrayOf(7960, 7965, -8),
            intArrayOf(7976, 7983, -8),
            intArrayOf(7992, 7999, -8),
            intArrayOf(8008, 8013, -8),
            intArrayOf(8025, 8025, -8),
            intArrayOf(8027, 8027, -8),
            intArrayOf(8029, 8029, -8),
            intArrayOf(8031, 8031, -8),
            intArrayOf(8040, 8047, -8),
            intArrayOf(8072, 8079, -8),
            intArrayOf(8088, 8095, -8),
            intArrayOf(8104, 8111, -8),
            intArrayOf(8120, 8121, -8),
            intArrayOf(8122, 8123, -74),
            intArrayOf(8124, 8124, -9),
            intArrayOf(8126, 8126, -7173),
            intArrayOf(8136, 8139, -86),
            intArrayOf(8140, 8140, -9),
            intArrayOf(8147, 8147, -7235),
            intArrayOf(8152, 8153, -8),
            intArrayOf(8154, 8155, -100),
            intArrayOf(8163, 8163, -7219),
            intArrayOf(8168, 8169, -8),
            intArrayOf(8170, 8171, -112),
            intArrayOf(8172, 8172, -7),
            intArrayOf(8184, 8185, -128),
            intArrayOf(8186, 8187, -126),
            intArrayOf(8188, 8188, -9),
            intArrayOf(8486, 8486, -7517),
            intArrayOf(8490, 8490, -8383),
            intArrayOf(8491, 8491, -8262),
            intArrayOf(8498, 8498, 28),
            intArrayOf(8544, 8559, 16),
            intArrayOf(8579, 8579, ODD_EVEN),
            intArrayOf(9398, 9423, 26),
            intArrayOf(11264, 11311, 48),
            intArrayOf(11360, 11360, EVEN_ODD),
            intArrayOf(11362, 11362, -10743),
            intArrayOf(11363, 11363, -3814),
            intArrayOf(11364, 11364, -10727),
            intArrayOf(11367, 11371, ODD_EVEN_SKIP),
            intArrayOf(11373, 11373, -10780),
            intArrayOf(11374, 11374, -10749),
            intArrayOf(11375, 11375, -10783),
            intArrayOf(11376, 11376, -10782),
            intArrayOf(11378, 11378, EVEN_ODD),
            intArrayOf(11381, 11381, ODD_EVEN),
            intArrayOf(11390, 11391, -10815),
            intArrayOf(11392, 11490, EVEN_ODD_SKIP),
            intArrayOf(11499, 11501, ODD_EVEN_SKIP),
            intArrayOf(11506, 11506, EVEN_ODD),
            intArrayOf(42560, 42604, EVEN_ODD_SKIP),
            intArrayOf(42624, 42650, EVEN_ODD_SKIP),
            intArrayOf(42786, 42798, EVEN_ODD_SKIP),
            intArrayOf(42802, 42862, EVEN_ODD_SKIP),
            intArrayOf(42873, 42875, ODD_EVEN_SKIP),
            intArrayOf(42877, 42877, -35332),
            intArrayOf(42878, 42886, EVEN_ODD_SKIP),
            intArrayOf(42891, 42891, ODD_EVEN),
            intArrayOf(42893, 42893, -42280),
            intArrayOf(42896, 42898, EVEN_ODD_SKIP),
            intArrayOf(42902, 42920, EVEN_ODD_SKIP),
            intArrayOf(42922, 42922, -42308),
            intArrayOf(42923, 42923, -42319),
            intArrayOf(42924, 42924, -42315),
            intArrayOf(42925, 42925, -42305),
            intArrayOf(42926, 42926, -42308),
            intArrayOf(42928, 42928, -42258),
            intArrayOf(42929, 42929, -42282),
            intArrayOf(42930, 42930, -42261),
            intArrayOf(42931, 42931, 928),
            intArrayOf(42932, 42946, EVEN_ODD_SKIP),
            intArrayOf(42948, 42948, -48),
            intArrayOf(42949, 42949, -42307),
            intArrayOf(42950, 42950, -35384),
            intArrayOf(42951, 42953, ODD_EVEN_SKIP),
            intArrayOf(42955, 42955, -42343),
            intArrayOf(42956, 42970, EVEN_ODD_SKIP),
            intArrayOf(42972, 42972, -42561),
            intArrayOf(42997, 42997, ODD_EVEN),
            intArrayOf(43888, 43967, -38864),
            intArrayOf(64261, 64261, ODD_EVEN),
            intArrayOf(65313, 65338, 32),
            intArrayOf(66560, 66599, 40),
            intArrayOf(66736, 66771, 40),
            intArrayOf(66928, 66938, 39),
            intArrayOf(66940, 66954, 39),
            intArrayOf(66956, 66962, 39),
            intArrayOf(66964, 66965, 39),
            intArrayOf(68736, 68786, 64),
            intArrayOf(68944, 68965, 32),
            intArrayOf(71840, 71871, 32),
            intArrayOf(93760, 93791, 32),
            intArrayOf(93856, 93880, 27),
            intArrayOf(125184, 125217, 34),
        )
    }

    // Unicode script and category groups generated from the maintainer JDK. See UnicodeGroups for
    // details.
    val UNICODE_GROUPS: Map<String, Array<IntArray>> = UnicodeGroups.groups()

    // ---------------------------------------------------------------------------
    // Unicode Perl character classes (for UNICODE_CHARACTER_CLASS flag)
    // ---------------------------------------------------------------------------
    //
    // These define Unicode-aware \d, \s, \w for use when the UNICODE_CHARACTER_CLASS
    // flag is enabled. \h and \v are fixed JDK-defined sets, so they remain the same
    // as in PERL_GROUPS.
    /**
     * Unicode `\d`: Unicode category Nd (Number, Decimal Digit). JDK maps `\d` under
     * UNICODE_CHARACTER_CLASS to `\p{IsDigit}` which is the Nd general category.
     */
    fun unicodeDigit(): Array<IntArray>? {
        return UNICODE_GROUPS.get("Nd")
    }

    /**
     * Unicode `\s`: Unicode White_Space property. This includes ASCII whitespace plus Unicode
     * space separators (Zs), line/paragraph separators (Zl, Zp), and certain control characters.
     * Matches JDK's `\s` under UNICODE_CHARACTER_CLASS.
     * 
     * 
     * The generated binary property table is shared with `\p{IsWhite_Space}`. Its control
     * characters are HT, LF, VT, FF, CR, and NEL; FS, GS, RS, and US are excluded. A canary test
     * verifies consistency with the JDK.
     */
    fun unicodeSpace(): Array<IntArray>? {
        return UnicodeProperties.lookupBinaryProperty("White_Space")
    }

    /**
     * Unicode `\w`: Alphabetic + Mark + Digit + Letter_Number + Connector_Punctuation (Pc) +
     * Join_Control. JDK defines `\w` under UNICODE_CHARACTER_CLASS as `[\p{Alpha}\p{gc=Mn}\p{gc=Me}\p{gc=Mc}\p{Digit}\p{gc=Pc}\p{IsJoin_Control}]`.
     * 
     * 
     * Built by merging the runtime-generated Alphabetic binary property, M (Mark), Nd (Decimal
     * Number), Nl (Letter Number), Pc (Connector Punctuation) tables, plus the two Join_Control
     * characters (U+200C, U+200D).
     */
    fun unicodeWord(): Array<IntArray> {
        return UnicodePerlHolder.UNICODE_WORD
    }

    /** The two Join_Control characters: U+200C (ZWNJ) and U+200D (ZWJ).  */
    private val JOIN_CONTROL = arrayOf<IntArray>(intArrayOf(0x200C, 0x200D))

    /**
     * Map of Unicode-aware Perl shorthand classes, paralleling [.PERL_GROUPS]. Used when [ ][ParseFlags.UNICODE_CHAR_CLASS] is active.
     */
    fun unicodePerlGroups(): Map<String, Array<IntArray>> {
        return UnicodePerlHolder.UNICODE_PERL_GROUPS
    }

    /**
     * Merges multiple sorted range tables into a single sorted, non-overlapping range table. Each
     * input table is an array of `{lo, hi}` pairs sorted by lo. The output merges adjacent and
     * overlapping ranges.
     */
    fun mergeRangeTables(vararg tables: Array<IntArray>?): Array<IntArray> {
        val tables = tables.filterNotNull().toTypedArray()
        // Count total ranges.
        var total = 0
        for (t in tables) {
            total += t.size
        }
        // Collect all ranges.
        val all = arrayOfNulls<IntArray>(total) as Array<IntArray>
        var idx = 0
        for (t in tables) {
            t.copyInto(all, idx)
            idx += t.size
        }
        // Sort by lo, then by hi.
        all.sortWith(compareBy({ it[0] }, { it[1] }))
        // Merge overlapping/adjacent ranges.
        val merged = arrayOfNulls<IntArray>(total)
        var count = 0
        for (range in all) {
            if (count > 0 && range[0] <= merged[count - 1]!![1] + 1) {
                // Extend existing range.
                merged[count - 1]!![1] = max(merged[count - 1]!![1], range[1])
            } else {
                merged[count++] = intArrayOf(range[0], range[1])
            }
        }
        return Array(count) { merged[it]!! }
    }

    private object UnicodePosixHolder {
        val UNICODE_ALPHA: Array<IntArray>? = UnicodeProperties.lookupBinaryProperty("Alphabetic")
        val UNICODE_DIGIT: Array<IntArray>? = UnicodeProperties.lookupBinaryProperty("Digit")
        val UNICODE_ALNUM: Array<IntArray> = mergeRangeTables(UNICODE_ALPHA!!, UNICODE_DIGIT!!)
        val UNICODE_PUNCT: Array<IntArray>? = UnicodeProperties.lookupBinaryProperty("Punctuation")
        val UNICODE_CNTRL: Array<IntArray>? = UnicodeProperties.lookupBinaryProperty("Control")

        // Blank: tab plus space separators.
        val UNICODE_BLANK: Array<IntArray> = mergeRangeTables(
            arrayOf<IntArray>(intArrayOf('\t'.code, '\t'.code)),
            UnicodeProperties.lookupCategory("Zs")!!
        )
        val UNICODE_XDIGIT: Array<IntArray> =
            mergeRangeTables(UNICODE_DIGIT!!, UnicodeProperties.lookupBinaryProperty("Hex_Digit")!!)

        // Graph: assigned code points other than controls, surrogates, and white space, i.e. every
        // general category except Cc, Cs, Cn, Zs, Zl, and Zp.
        val UNICODE_GRAPH: Array<IntArray> = mergeRangeTables(
            UnicodeProperties.lookupCategory("L")!!,
            UnicodeProperties.lookupCategory("M")!!,
            UnicodeProperties.lookupCategory("N")!!,
            UnicodeProperties.lookupCategory("P")!!,
            UnicodeProperties.lookupCategory("S")!!,
            UnicodeProperties.lookupCategory("Cf")!!,
            UnicodeProperties.lookupCategory("Co")!!
        )

        // Print: Graph plus the blanks that aren't controls, which are the space separators.
        val UNICODE_PRINT: Array<IntArray> = mergeRangeTables(UNICODE_GRAPH, UnicodeProperties.lookupCategory("Zs")!!)

        val UNICODE_POSIX_PROPERTY_GROUPS: Map<String, Array<IntArray>> =
            mapOf(
                "Lower" to UnicodeProperties.lookupBinaryProperty("Lowercase")!!,
                "Upper" to UnicodeProperties.lookupBinaryProperty("Uppercase")!!,
                "ASCII" to POSIX_ASCII,
                "Alpha" to UNICODE_ALPHA!!,
                "Digit" to UNICODE_DIGIT!!,
                "Alnum" to UNICODE_ALNUM,
                "Punct" to UNICODE_PUNCT!!,
                "Graph" to UNICODE_GRAPH,
                "Print" to UNICODE_PRINT,
                "Blank" to UNICODE_BLANK,
                "Cntrl" to UNICODE_CNTRL!!,
                "XDigit" to UNICODE_XDIGIT,
                "Space" to unicodeSpace()!!
            )
    }

    // Lazy holder for unicode Perl groups — merging range tables is non-trivial, so cache the result.
    private object UnicodePerlHolder {
        val UNICODE_WORD: Array<IntArray> = mergeRangeTables(
            UnicodeProperties.lookupBinaryProperty("Alphabetic")!!,
            UNICODE_GROUPS.get("M")!!,
            UNICODE_GROUPS.get("Nd")!!,
            UNICODE_GROUPS.get("Nl")!!,
            UNICODE_GROUPS.get("Pc")!!,
            JOIN_CONTROL
        )

        val UNICODE_PERL_GROUPS: Map<String, Array<IntArray>> = mapOf(
            "\\d" to unicodeDigit()!!,
            "\\s" to unicodeSpace()!!,
            "\\w" to UNICODE_WORD,
            "\\h" to HORIZ_SPACE,
            "\\v" to VERT_SPACE
        )
    }
}
