package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.brotli.Brotli
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-47: the pure-Kotlin Brotli decoder against streams produced by the
 * reference `brotli` CLI. The compressed vectors are embedded as Base64;
 * the ORIGINAL inputs are regenerated in Kotlin below (same paragraph
 * constant / same LCG), so each test is a true reference round-trip:
 * decode(reference-compressed(x)) == x.
 *
 * The quality levels are chosen to hit distinct stream features: q11 uses
 * the static dictionary, word transforms and context maps; q9/q5 exercise
 * plain LZ + block splitting; incompressible input forces uncompressed
 * meta-blocks; the UTF-8 case exercises the UTF-8 literal context mode.
 */
class BrotliTest {

    private val para = (
        "The quick brown fox jumps over the lazy dog. This is a test of the " +
            "emergency broadcasting system, and it should compress rather well " +
            "because the words are common English words from the dictionary. "
        )

    /** Same 64-bit LCG as the vector generator; high bits are decent noise. */
    private fun lcgBytes(n: Int, seed: Long): ByteArray {
        var x = seed
        return ByteArray(n) {
            x = x * 6364136223846793005L + 1442695040888963407L
            (x ushr 33).toByte()
        }
    }

    private fun decodeBase64(s: String): ByteArray {
        val out = ByteArray(s.length / 4 * 3)
        var o = 0
        var buf = 0
        var bits = 0
        var pad = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                '=' -> { pad++; 0 }
                else -> error("bad base64")
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits == 24) {
                out[o++] = (buf ushr 16).toByte()
                out[o++] = (buf ushr 8).toByte()
                out[o++] = buf.toByte()
                buf = 0
                bits = 0
            }
        }
        return if (pad == 0) out else out.copyOf(o - pad)
    }

    @Test
    fun dictionary_and_transforms_q11_small_text() {
        val expected = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        assertContentEquals(expected, Brotli.decode(decodeBase64(FOX_Q11)))
    }

    @Test
    fun repeated_text_q11() {
        val expected = para.repeat(50).encodeToByteArray()
        assertContentEquals(expected, Brotli.decode(decodeBase64(PARA50_Q11)))
    }

    @Test
    fun repeated_text_q9() {
        val expected = para.repeat(200).encodeToByteArray()
        assertContentEquals(expected, Brotli.decode(decodeBase64(PARA200_Q9)))
    }

    @Test
    fun incompressible_input_uses_uncompressed_metablocks() {
        val expected = lcgBytes(4096, 42L)
        // 4101 compressed > 4096 raw: the encoder fell back to stored bytes.
        assertContentEquals(expected, Brotli.decode(decodeBase64(RANDOM4K_Q5)))
    }

    @Test
    fun mixed_text_and_binary_q11() {
        val text = para.repeat(3).encodeToByteArray()
        val expected = text + lcgBytes(600, 7L) + text
        assertContentEquals(expected, Brotli.decode(decodeBase64(MIXED_Q11)))
    }

    @Test
    fun utf8_context_mode_q11() {
        val expected = (
            "Vögel zwitschern früh am Morgen über die Dächer. " +
                "Les œufs frais coûtent très cher à Paris. " +
                "日本語のテキストも含まれています。"
            ).repeat(8).encodeToByteArray()
        assertContentEquals(expected, Brotli.decode(decodeBase64(UTF8_Q11)))
    }

    @Test
    fun empty_stream() {
        assertContentEquals(ByteArray(0), Brotli.decode(decodeBase64(EMPTY_Q11)))
    }

    @Test
    fun output_cap_guards_against_bombs() {
        val e = assertFailsWith<IllegalStateException> {
            Brotli.decode(decodeBase64(PARA50_Q11), maxOutputBytes = 1000)
        }
        assertTrue("exceeds" in (e.message ?: ""), "cap error is explicit: ${e.message}")
    }

    @Test
    fun truncated_input_fails_cleanly() {
        val full = decodeBase64(PARA50_Q11)
        assertFailsWith<IllegalStateException> {
            Brotli.decode(full.copyOf(full.size / 2))
        }
    }

    /* ─── Reference-compressed vectors (brotli CLI) ──────────────────────── */

    private val FOX_Q11 =
        "IagABFRoZSBxdWljayBicm93biBmb3gganVtcHMgb3ZlciB0aGUgbGF6eSBkb2cD"

    private val PARA50_Q11 =
        "4cgzAWKkXG1HCyrI5lDZ0zbwSxQCrRl6spA7+2xCUSRSQKTkwm3szIZpxxFlToLIpijKFxZ2wChL/xHuhAgqN2Wu0JXDguK+5ngx" +
            "1w+5n6wvEK3FOLh9RzudEARASfRvj9j/rQOABg=="

    private val PARA200_Q9 =
        "4jwTgEBf2z7VDYmWDAX1RB3vngP4/XQhd2aSWRcJVUYj9Pba7k58Lpsq5oUPq4H5lrHqCRa97JefDd1Wf2CsR/4/ZpXUJDnTrXXm" +
            "GXuvcI0tCiDt7tSxZM4muZDNOaIgTHATM6KAy1bgJHcFFlCQK9BKAdz+NiSAEA=="

    private val RANDOM4K_Q5 =
        "Ufw/BHZS0h9WDNmWvr2IFmJyLDWCWwEIKbw2F3B93qa8reIipNAKyD2uaXEyAgm3V4WtK0YfDjex+z2vvYfkTOvHJQpVM+tgrJnT" +
            "BIhVZPSQxl/mYlKPz7jJzZ2+YiIWA0/cgngU0nkJOSTEKEOzJQabbO5J9W7eIFwyxaL8VEFrtoNEBwKBxVeFcSKOKmi8OeF78o8d" +
            "JjDGMmuNzAFHE3x0UQdRXJjwEx5XMLKu4MMsrk0VdQVzapx78sGFi6uYwQ4cDcigfACK+f3V9LBx2N+Y47nspYSqQvj7CRtdY+Ct" +
            "nLgtREXSMbeAlHJ/wf+pRnYb0CXIrKAgZzXRk5VTt9PJ1mXODmhSkztYFzkXB/LCrdSmlp6SMfxhCbXM3bnooWBp4uveqaD58BIr" +
            "JwI1/BztjS2E1eSMF8RfcSwq9PmaSoWQYxGRJ/VOF5pX43SW/XmFzQwphM7DmNxOxuXeYgoWviaJTBibpQzMg0yffpXm7WGxqn0/" +
            "HH1vpAwEqpVSRP0PtAjVKbiNk6Rl4pPuz6mCRZBHb/g/hk+aU8950+laajKuJmYNHXEgo8q8DM6cZdN+/b1tXsP9FiKcPWe78VP6" +
            "inqThWSrsGpfdig7rsv6I5w34kkZlHZdYqKx3Csj6QR/fq5XjF8axn4VjKcvKQYfFm4ST30aFOAFSo4/jNu9nmCsUfSZskrOu2NQ" +
            "Jxd3XiZsmDTavNUjX3YWVzImXhQjw5AXPcd1g4Vn4D9T4ucFD8S6SmeTfi4IpwxgQuyQDTVp2iY0ZC0C+mT3fHvSH6RqD7RyL4Cd" +
            "m1hPKe8e1DNbQ5xvestI8dpxr6/gHYCwh0epkXY1cK/Fga5I7U6fpYCREfSrPsLHUgVY1OnV7xQSe3lT0OLxvJFPyZQJYzQfg7hI" +
            "2TjmCYC1zQuqItcaLb5okj82FON1h48ucWOyaGwpr3LsTo4yGLKECxMM5QZDjjtXq6C5p9Vy8jjutYNULLNOyV8WSJqNMKm2Qoe/" +
            "VXsKuRZL1t3TOnFwyaz/zCeND1V5WnbD2kWtKoXktxlU3Ll8L/xyRmEDkTSQTqlgcoqY7v4EYR864BGDj852FY/GFb+xFXCCY63t" +
            "98tpM6r9qEy0BBWdQaCfC2eGLDQy4PEOoH8vM4Qucj7tgula3BkzhDjK3QyqADvm5TssZcX/6vbj7XgPshJi+SlQCMOpbPkvBQPY" +
            "dsxORecKoCln6pcRBjzhelF9hQXofpyyIXdGiYsmMrTP3vIAJAlFXvAA0RRQD3lsyXfZckHmYHjiFXHwWyuU4gcxssHZ31wO8JRr" +
            "7zi4JvzU5WnzQWln+lfyg/amrHmjSgQry0h2OjD/4UdjcU9QoOTXTBpDgBHWWs2s2uhTKpLQ3LyrDU5YpEa6efxz7Hhfh7uIZ1v5" +
            "jRN57gqO0llGtyFX+Rf5n9DU5QjchXVv1SDzdLxca16kD6LceHl/lKcN43+eR2r/ZuzeuyIL5cF6eA7rWyHE3PLrRozjrTD0v5If" +
            "pf7ZJYYAgH0/Y1hjnfTz9ypUnjehqnwIHRSqgo1+1gyv8SWudq6+RHEw8DjTauUP60cXsMjYsd8JB5gnHawGVvNMJCJbJxROFiOO" +
            "TwxCt4KyOEtTux+Hx9y6AyIK03fqhc3Dj7XNZz/M8h+wED6yTV+7EQ8X/nHmZ30bfu+XuT0VjcjOGyq1qIX9RXiMHkrPQqp0vBWN" +
            "SDJ1xHCInJ0bFD3wK9vd9kzydWFNUFXD8cm0U06yasG3oAN6LYWe6sLQWBnyrzCSb8WMiLBqMc9g7Cgna+GwLCbJ8s7Y70HDLzbX" +
            "2ZKZyw+I92o6DvUUxGThxPIga0c7KxQ8howtGBBOtRcfyRUSq9Pr0zGvHPCmK9Jxhmu+v/51YK3QucgAOwMztuz8J7nUjISkJXp5" +
            "7x8Sc3Xsjyy4YTcrEZNfK6WVOFRmvxQ16Kmbh0lfdRNAHfSKhBgxAkz1GD7wQcvViTBqREKO2Vzh+cGJsc3F0lnx8vpXG/cm8Cmg" +
            "0r8m1hmFSLL+py7UTyXER1PEjiZ+uvbDSsPNoCrZECM7ljqfUNUcWReFSaWoGUIm0FcG6DI3rYGwSAan6+ShLhRkccmWlFfeCDXR" +
            "6MkqWrxxonBzWrhe95ctS12k+np24qaQOwTJ6onyz+qKMHSr8Rw17DsogT5qqxAAhY3yGkRCnHdaZeVxcu78Y6tS5gvpkGoSQo/1" +
            "Cuti/Rh07/qrYY/7+cNy0RUT0FCg/PTlhi1NYAEVvd4sIQMDR6y/ez2FDiQokYtn6VRzGxGWAnXhXwVp9EgRL1iVLkiy97h7iUKS" +
            "3CkQ7oTNFoK/Hl7r25leegZPcZhKtITqoCII8oYzc6p6hAFygtBBv4Wb3XjCZ/ryVTCpAcVwJiifs5j9EogmSqH1VMBDEFHP9Lmf" +
            "JeI+HRYMhIcbsO9+0jIG/nliwg3ud7xlCrUaZICjdhH7JM97Y+qVnMxXYqdOsPFSLe3GaEtTb7pG183GlI6M2ulFhrsNrhtyh4zu" +
            "ooxxsQWmQWtzcflMQOY9uv8L7ua/mwAPKG2MzAdKaEifi+ObAkhrMlJ6SQgHEBQFXOeLkPjXrFbc5iFwzBSQ289lH01J7NuPga15" +
            "I39brccCe5UnE1BpBW9Q6HEh4Ptlrdf9Xgz6xqW9LsmzNgN463kw8sdOBb2IIeEw61IbOhrPSqmPTP4PinQftL0WppSWtNAahHMY" +
            "wJCGlpE7FKA3RC1bvH/2xKkGsVGq/x55ygKoTmlTOyW7Dbsb4BeALn1m9ebL14LugNaHZUNGogUZ4U0R834kHFO3CDOjFpZmAdvU" +
            "jH4ADBPYawJ+8zwKXbk+Nbnbl9CeJwkWpgrJMj1e9sHAKFD1UDcrkmBAuQrbnt+VbCjo5M39xZalYUaR+6OnUU7Yvv5oyjT3I3mX" +
            "Y1OAJdKdGF9lr2D2c7QDPUaykpl8YfIS5ldFnK5T5gAd0F5rjFT3B5ICyGx5FNSFvzZn0e8RP5KDcU0w4Z+CP0bOg+wv+3P0Q5NB" +
            "woqsNDQ6lh/V2WuFPGb2XLrRAS0UMPANIP+icbbHG4zfQXJwJkoYOkk1959u8y0lvMNlYoErOu+dosxiRz2XxvIpU78Yzaz/DZjD" +
            "Cxz+/3i1cNJuHcTKeXoxtRmAeCGM+mKcrzJt7kjngOwDgOwNq+DuGB/ngxJbL8IeHghURqTA1eYeGp6UvZsgqRVKS0h5dh/wApQj" +
            "kSr2d5OGxZnlHFzaebCIFXKhkKzOMlLyD21xoK4NXlUKaK0KN0bNVYDyOGo8wlHM6RPxJ9xFyZ7Dw8gwZeBd/D8DNNkIBti6HjqM" +
            "yxnfAHzq1k2vhkvoDePQNKaQcE/kJ2BRdrB6L3qdwWC76LXdmWSRu0q75SadpONwVT5C7wmu3RKwxjUepo40Eh/Xi4jRSJl2Ny1s" +
            "4mgJrfn+uRzDSuTWqSWb/+gJjsNPMFU1Awb5NAGzX4ua4n8oUTysPW+tvi3SSoSoMQYeRp0neIxG0Epfx08pTpr08vW9ctQTgxlN" +
            "SrH6n1739P3lVGrpXVF09w8/tcS7K5xoSYo1IGrRk3WiNYUhg/z//zkjOUvjt2eb/Ij8T+RGBxxNyjxgKDf9J8D3ORkDvn1q8xDd" +
            "Jbu6Rizjjm/fgccwEwOZzGx/908axJ5zwCLf08oC/FJKV89W8Ump3gL/GPs4rtmkzp+EWQdsvCo83SHOTjadd8L3un//xb9pyBlo" +
            "1fS2eGzo8dP8W5Uy+u2yzIGgZge4NxgYzC6/485S6lrPRwouoKHJMOl3RRC2Mv+ooTsVpzzWcHNjlbp7N9KGNTlgCr8Ai5iI32Yv" +
            "9nC302hjSpfIlMBc1AiBGNqDHguVxbnnu3eOp0FkF7L1CjpCntv7tJDm7tzQFvBbZhevOZ4erIzCkA9IhQcSZ/A/1Tss01rTh5md" +
            "AR38hvA4KjOGqwaOAT9cm4wZF7kffjTJ3jIurpaQhDTpoD0LbzySkLB/+UtPsuQQd6ro+GxIg8RRg8RKCHKdsC8C6WJl0w9u2Pk/" +
            "eOhnEkO4nomEo+7IrBOUuu861CCK372dAJLzHEISQJXyib8iLdpmApNlZq3n0u1mnHC9QSPxrADpt8k21nx3Qfaj7IqR/E8XFKDc" +
            "eSTCGanvvpxWrTkkvSTR2LJS0/Gztzo0biYE1X1mFhUcpm4byfkzwIiP3nkEuuag/8SH2qF6ULOFQldwm5dgTb4KEFjTmXtf+1RO" +
            "G4QpSLR9K1JwpS7Fmoz+C+bHFniex5FnEXZTBxd/EbMf6GBWaMP+S7BahcDyMOWbIATwt9px6wdNXq6s67tQ6lHYOsfocDV7h/aN" +
            "kth9GSB0Ooth79N9NUoUBdDG2XX0yXFza9x31vODa8gcR8n9BX772KTJbSw0c1noB363rPH5QyNwjOr3YwgCyLFRIl45a7cUH7M6" +
            "pSHPBvE+U49Pjj3Rtyp9Sk8p+XdFgOI8HebLgvrsNjMnRu1kG17wKM7/74xoVpRSNu3xm7Z1BdtWqORIght7aMvrXw6hcOyAHXP1" +
            "Tzrct0Y4sPFobKCdwws9gpMVwiuETbGfSVBEzPyWCNVebBztvRVnjagrGLEkTL9Jv0t31N1WZIvnZ5OCE2hBROB5qv/wUAmmCkBk" +
            "IYfgVoLAHl/EA0ROT57qp3J6ffg24YyZm4c2KpHEHVrwO25wcliorXDJdBDZ63+A7dXj2HLKez7y52QN0K9yigJBJnEn1lupT9cJ" +
            "98hoxHU33rZQoWFBdN361js84MMgW7rq7shVuTNAsTnK4LArXwcZigbgRfVleIRtlb/MhEjj1fhba3iLzWhKECr3t1E4h/tRzdDp" +
            "Q8hK6PXset+xFaU7EklhpJ4Ylgy+Xmqp1kXTmsUbJxZnreB7tBOBneflkvomEgnD++IhaGCsDEe7twDaVP/B5KS+809teAE8/UjB" +
            "9noTInqepEjiRCyXxTPUlxmEkjM4PQYEfe80/MEeYi7SUWGa67GKGJ+5IJwkBZmZXOLw2sDxM4L3GKdyol/R/mEX/OHzRPnJcVLH" +
            "9xBe3v79Y6xscvBhZtJjhBXd2xM/kAqGuOCGkKBhsbK8QSwAuthXwgmIVlZZE+5tepZMainUYXWDt36yUMXSmK5T+AA49cgSzv4j" +
            "UVH/BAbKOCFD3RX3zBnoRYfcwtKvKCRBLWK6ak7jjLhGteIlifEOGhaqIXJOGZO/hfVhOHqUsk9GHJUBdg0myar7YpLcUmFh0cuR" +
            "7jC1QdBv3hi/Jlp6kR9HKErFwvjbtctKUSb4FSG6qDsmVQcgHeDP2J1bU1B1yUT4aC+05DwkoNW840PJn9OaIBr94cQ7Ts2cTo61" +
            "wcAy8ZvETul9fYXofoR7Hm3WwyfUPPseXnOMGHHe3YnrZlAwYYDeRoRo9qdMP1StZow1yHEVbXOA8NX/Cq9em+DowN+FW6qk6Xps" +
            "lZ1uaX0p8MQo/HPuv1WATEvFwvfnfibvZNkZCl1C33nBdgdhOVWsWOJdDEzfYMsBGkAD"

    private val MIXED_Q11 =
        "sag3ACADcFuTos0O2Lvd2gJJokjq8EX19yb4bmN0RkMsAleqqQiACVnU5g6ehDzShRBl2Bp5exGaOlhkRAgjPZUO5rErRYTyw6aY" +
            "zO1X7iehfYPpGlkICBShkXM+IY6ATd/AsmJ/RES+BSJ9AaakK5BL1B13kBZBx73iPxfags0idmY9R82tMcfLvHIYPBQsnVNLvwxC" +
            "YtpLw0JlaVd9OFztmcHC5puCMxbXnajVFs/iKnJxkMg58Tfl9soHQ0UkAeKVBpC+Ij1hTXCTWzyDQfcyXZGeF032DSIS1s+7feA/" +
            "co6Lemj8Yt9DbySkarKPoicJLRUubclRxppovy33ogSqYB66ryeT2jzvBllHkjH3D2WLbQVra3PSNntCoaFPNBAi8Lp7XbJJlkTY" +
            "RvcP7Yr07F+xtsaq5YJb/LR6Zdc6EEK0MjCmvkMMpNg1+tbxorHbQFXnd/q/eEOS+sY1f4hVO9QfGczIZVwXenIZ+TXjHslDoGCe" +
            "3v4L6uyDp+G8iKNs/2ZbK9oIJ3bCcuR4x7WUoz6T5cFqvpcoYNmpRKRVAVwIlHu3lSoKoUlu5iD3Kqd8bJyGApNXpJA4eWWD5x8o" +
            "uCilM/3Lfd4xFRdsG5wacpww7oFVU9IfmPNAYNapf0Vb53ZyU40AzJyhjUmuiVwNuokG5/XW+eicULGevMYP96NW2Z8OkdVMKsdK" +
            "Clv7RMI9FKq/4nPaf13nJaP7+wqsT2ow/Um5mbghBoa9FOdh7rvasCnllPqBoZK2x2v0LM+bONNyTAmGqaDR8CKWn7vCHex2a3It" +
            "u/La9hPUz4Jmla39suEET/O89v9dFMFtqlbdZXoMXDWKAVgZXrYlSEX1BGXLOzG18qMeAVFEiCPBEyPT8Zx/ss5E4OkM9DbHw+/R" +
            "wZ3DreEGb92Y75CSoaiZbGrymVcj1B2/dqkzjKbv290rv38B"

    private val UTF8_Q11 =
        "sXglQO44cPc14vUtJd0IlW7aprInFEZhsfgfC3b7TG8blGqwdg7z+ncvGQzKZDNISoxKPgXC5BinCw44iF2D0uiD8pXAkD1GYcu9" +
            "nBQmvFep7HKgBZHEMsyMbqUUFojZQPAloJXmcqDGwPjyERmRTGG4VV4NS0UlGQyXA0HqArfjVcVW9cqlvBGUf5qKz6jocU4dRYUA"

    private val EMPTY_Q11 =
        "oQE="
}
