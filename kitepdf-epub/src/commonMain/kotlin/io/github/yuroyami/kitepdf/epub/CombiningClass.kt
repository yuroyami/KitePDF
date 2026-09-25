package io.github.yuroyami.kitepdf.epub

/**
 * The order a shaper puts combining marks in before GSUB runs (#211): each run of marks
 * sorts by canonical combining class (Unicode 17, 3.11), with the classes HarfBuzz modifies
 * so that fonts see the order they were built for. Hebrew points follow the SBL Hebrew order,
 * Arabic shadda goes before the other marks, Thai sara u and uu go before phinthu, and the
 * Tibetan vowel sign u goes before the sign i.
 *
 * The classes cover the Basic Multilingual Plane. A character outside it has class 0 and
 * keeps its place.
 */
internal object CombiningClass {

    /** Sorts each run of marks in [items] by [modified] class, keeping the order of equal classes. */
    fun <T> reorder(items: MutableList<T>, codePoint: (T) -> Int) {
        var i = 0
        while (i < items.size) {
            if (modified(codePoint(items[i])) == 0) { i++; continue }
            var end = i + 1
            while (end < items.size && modified(codePoint(items[end])) != 0) end++
            if (end - i > 1) {
                val sorted = items.subList(i, end).sortedBy { modified(codePoint(it)) }
                for (k in sorted.indices) items[i + k] = sorted[k]
            }
            i = end
        }
    }

    /** The combining class of [cp] as HarfBuzz modifies it, 0 for a character that is not a mark. */
    fun modified(cp: Int): Int {
        when (cp) {
            // Tai Tham sakot and Tibetan padma go after the other marks, Tibetan tsa-phru before the vowel signs.
            0x1A60, 0x0FC6 -> return 254
            0x0F39 -> return 127
        }
        val ccc = canonical(cp)
        return when (ccc) {
            in 10..26 -> HEBREW[ccc - 10]
            in 27..35 -> ARABIC[ccc - 27]
            84 -> 4
            91 -> 5
            103 -> 3
            130 -> 132
            132 -> 131
            else -> ccc
        }
    }

    /** Hebrew points 10 to 26, permuted into the SBL Hebrew order as HarfBuzz does. */
    private val HEBREW = intArrayOf(22, 15, 16, 17, 23, 18, 19, 20, 21, 14, 24, 12, 25, 13, 10, 11, 26)

    /** Arabic classes 27 to 35, with shadda moved before the other marks as HarfBuzz does. */
    private val ARABIC = intArrayOf(28, 29, 30, 31, 32, 33, 27, 34, 35)

    /** The canonical combining class of [cp], from UnicodeData.txt, field 3. */
    fun canonical(cp: Int): Int {
        val table = ranges
        var lo = 0
        var hi = table.size / 3 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < table[mid * 3] -> hi = mid - 1
                cp > table[mid * 3 + 1] -> lo = mid + 1
                else -> return table[mid * 3 + 2]
            }
        }
        return 0
    }

    /** The first character, last character and class of each run of [CLASSES]. */
    private val ranges: IntArray by lazy {
        IntArray(CLASSES.length / 10 * 3) { k ->
            val record = k / 3 * 10
            when (k % 3) {
                0 -> CLASSES.substring(record, record + 4).toInt(16)
                1 -> CLASSES.substring(record + 4, record + 8).toInt(16)
                else -> CLASSES.substring(record + 8, record + 10).toInt(16)
            }
        }
    }

    /**
     * Every character of the Basic Multilingual Plane with a combining class other than 0, in
     * runs of one class: the first and last character in four hex digits each, and the class in
     * two. Generated from UnicodeData.txt of Unicode 17.
     */
    private const val CLASSES =
        "03000314E603150315E803160319DC031A031AE8031B031BD8031C0320DC03210322CA03230326DC03270328CA03290333DC03340338010339033CDC" +
        "033D0344E603450345F003460346E603470349DC034A034CE6034D034EDC03500352E603530356DC03570357E603580358E80359035ADC035B035BE6" +
        "035C035CE9035D035EEA035F035FE903600361EA03620362E90363036FE604830487E605910591DC05920595E605960596DC05970599E6059A059ADE" +
        "059B059BDC059C05A1E605A205A7DC05A805A9E605AA05AADC05AB05ACE605AD05ADDE05AE05AEE405AF05AFE605B005B00A05B105B10B05B205B20C" +
        "05B305B30D05B405B40E05B505B50F05B605B61005B705B71105B805B81205B905BA1305BB05BB1405BC05BC1505BD05BD1605BF05BF1705C105C118" +
        "05C205C21905C405C4E605C505C5DC05C705C71206100617E6061806181E061906191F061A061A20064B064B1B064C064C1C064D064D1D064E064E1E" +
        "064F064F1F06500650200651065121065206522206530654E606550656DC0657065BE6065C065CDC065D065EE6065F065FDC067006702306D606DCE6" +
        "06DF06E2E606E306E3DC06E406E4E606E706E8E606EA06EADC06EB06ECE606ED06EDDC071107112407300730E607310731DC07320733E607340734DC" +
        "07350736E607370739DC073A073AE6073B073CDC073D073DE6073E073EDC073F0741E607420742DC07430743E607440744DC07450745E607460746DC" +
        "07470747E607480748DC0749074AE607EB07F1E607F207F2DC07F307F3E607FD07FDDC08160819E6081B0823E608250827E60829082DE60859085BDC" +
        "08970898E60899089BDC089C089FE608CA08CEE608CF08D3DC08D408E1E608E308E3DC08E408E5E608E608E6DC08E708E8E608E908E9DC08EA08ECE6" +
        "08ED08EFDC08F008F01B08F108F11C08F208F21D08F308F5E608F608F6DC08F708F8E608F908FADC08FB08FFE6093C093C07094D094D0909510951E6" +
        "09520952DC09530954E609BC09BC0709CD09CD0909FE09FEE60A3C0A3C070A4D0A4D090ABC0ABC070ACD0ACD090B3C0B3C070B4D0B4D090BCD0BCD09" +
        "0C3C0C3C070C4D0C4D090C550C55540C560C565B0CBC0CBC070CCD0CCD090D3B0D3C090D4D0D4D090DCA0DCA090E380E39670E3A0E3A090E480E4B6B" +
        "0EB80EB9760EBA0EBA090EC80ECB7A0F180F19DC0F350F35DC0F370F37DC0F390F39D80F710F71810F720F72820F740F74840F7A0F7D820F800F8082" +
        "0F820F83E60F840F84090F860F87E60FC60FC6DC10371037071039103A09108D108DDC135D135FE61714171509173417340917D217D20917DD17DDE6" +
        "18A918A9E419391939DE193A193AE6193B193BDC1A171A17E61A181A18DC1A601A60091A751A7CE61A7F1A7FDC1AB01AB4E61AB51ABADC1ABB1ABCE6" +
        "1ABD1ABDDC1ABF1AC0DC1AC11AC2E61AC31AC4DC1AC51AC9E61ACA1ACADC1ACB1ADCE61ADD1ADDDC1AE01AE5E61AE61AE6DC1AE71AEAE61AEB1AEBEA" +
        "1B341B34071B441B44091B6B1B6BE61B6C1B6CDC1B6D1B73E61BAA1BAB091BE61BE6071BF21BF3091C371C37071CD01CD2E61CD41CD4011CD51CD9DC" +
        "1CDA1CDBE61CDC1CDFDC1CE01CE0E61CE21CE8011CED1CEDDC1CF41CF4E61CF81CF9E61DC01DC1E61DC21DC2DC1DC31DC9E61DCA1DCADC1DCB1DCCE6" +
        "1DCD1DCDEA1DCE1DCED61DCF1DCFDC1DD01DD0CA1DD11DF5E61DF61DF6E81DF71DF8E41DF91DF9DC1DFA1DFADA1DFB1DFBE61DFC1DFCE91DFD1DFDDC" +
        "1DFE1DFEE61DFF1DFFDC20D020D1E620D220D30120D420D7E620D820DA0120DB20DCE620E120E1E620E520E60120E720E7E620E820E8DC20E920E9E6" +
        "20EA20EB0120EC20EFDC20F020F0E62CEF2CF1E62D7F2D7F092DE02DFFE6302A302ADA302B302BE4302C302CE8302D302DDE302E302FE03099309A08" +
        "A66FA66FE6A674A67DE6A69EA69FE6A6F0A6F1E6A806A80609A82CA82C09A8C4A8C409A8E0A8F1E6A92BA92DDCA953A95309A9B3A9B307A9C0A9C009" +
        "AAB0AAB0E6AAB2AAB3E6AAB4AAB4DCAAB7AAB8E6AABEAABFE6AAC1AAC1E6AAF6AAF609ABEDABED09FB1EFB1E1AFE20FE26E6FE27FE2DDCFE2EFE2FE6"
}
