package io.github.yuroyami.kitepdf.crypto.sig

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/**
 * Certificates, CMS messages and signed PDFs for the signature tests, built by hand in DER and
 * signed with the JDK's own providers, so KitePDF's checks meet an independent signer.
 */
internal object SigFixtures {

    const val SHA256 = "2.16.840.1.101.3.4.2.1"
    const val SHA384 = "2.16.840.1.101.3.4.2.2"
    const val SHA1 = "1.3.14.3.2.26"
    const val RSA = "1.2.840.113549.1.1.1"
    const val SHA256_RSA = "1.2.840.113549.1.1.11"
    const val RSA_PSS = "1.2.840.113549.1.1.10"
    const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val ECDSA_SHA384 = "1.2.840.10045.4.3.3"
    const val DATA = "1.2.840.113549.1.7.1"

    fun rsaKeys(bits: Int = 2048): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    fun ecKeys(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun concat(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + p }

    fun seq(vararg parts: ByteArray): ByteArray = Asn1.encode(0x30, concat(parts.toList()))

    /** A DER SET OF: the elements sorted by their encodings. */
    fun set(vararg parts: ByteArray): ByteArray = Asn1.encode(0x31, concat(parts.sortedWith(::compareDer)))

    private fun compareDer(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    fun tagged(tag: Int, vararg parts: ByteArray): ByteArray = Asn1.encode(tag, concat(parts.toList()))

    fun int(v: BigInteger): ByteArray = Asn1.encode(0x02, v.toByteArray())

    fun int(v: Long): ByteArray = int(BigInteger.valueOf(v))

    fun octets(b: ByteArray): ByteArray = Asn1.encode(0x04, b)

    fun bits(b: ByteArray): ByteArray = Asn1.encode(0x03, byteArrayOf(0) + b)

    fun oid(dotted: String): ByteArray = Asn1.oid(dotted)

    val NULL: ByteArray = byteArrayOf(0x05, 0x00)

    fun algorithm(oid: String, vararg params: ByteArray): ByteArray = seq(oid(oid), *params)

    fun utf8(s: String): ByteArray = Asn1.encode(0x0C, s.encodeToByteArray())

    fun utcTime(s: String): ByteArray = Asn1.encode(0x17, s.encodeToByteArray())

    fun generalizedTime(s: String): ByteArray = Asn1.encode(0x18, s.encodeToByteArray())

    fun name(cn: String, org: String = "KitePDF Tests"): ByteArray = seq(
        set(seq(oid("2.5.4.6"), Asn1.encode(0x13, "US".encodeToByteArray()))),
        set(seq(oid("2.5.4.10"), utf8(org))),
        set(seq(oid("2.5.4.3"), utf8(cn))),
    )

    fun sha(algorithm: String, data: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(data)

    /** A key pair with its certificate, and how it signs. */
    class Identity(val keys: KeyPair, val cert: ByteArray, val name: ByteArray, val issuerName: ByteArray, val serial: BigInteger, val ski: ByteArray) {
        val isEc: Boolean get() = keys.private.algorithm == "EC"

        /** The JCA name and the AlgorithmIdentifier of this identity's default signature. */
        val jcaAlgorithm: String get() = if (isEc) "SHA256withECDSA" else "SHA256withRSA"
        val signatureAlgorithm: ByteArray get() = if (isEc) algorithm(ECDSA_SHA256) else algorithm(SHA256_RSA, NULL)

        fun sign(data: ByteArray, jca: String = jcaAlgorithm, pss: PSSParameterSpec? = null): ByteArray =
            Signature.getInstance(jca).run {
                initSign(keys.private)
                if (pss != null) setParameter(pss)
                update(data)
                sign()
            }
    }

    /** A v3 certificate for [keys], named [cn], signed by [issuer] or by itself when [issuer] is null. */
    fun certificate(cn: String, keys: KeyPair, issuer: Identity?, ca: Boolean, serial: Long, notAfter: String = "360101000000Z"): Identity {
        val subjectName = name(cn)
        val ski = sha("SHA-1", keys.public.encoded)
        val signerKeys = issuer?.keys ?: keys
        val signerIsEc = signerKeys.private.algorithm == "EC"
        val sigAlg = if (signerIsEc) algorithm(ECDSA_SHA256) else algorithm(SHA256_RSA, NULL)
        val extensions = mutableListOf(
            seq(oid("2.5.29.19"), Asn1.encode(0x01, byteArrayOf(-1)), octets(if (ca) seq(Asn1.encode(0x01, byteArrayOf(-1))) else seq())),
            seq(oid("2.5.29.14"), octets(octets(ski))),
        )
        val tbs = seq(
            tagged(0xA0, int(2)),
            int(serial),
            sigAlg,
            issuer?.name ?: subjectName,
            seq(utcTime("250101000000Z"), utcTime(notAfter)),
            subjectName,
            keys.public.encoded,
            tagged(0xA3, seq(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance(if (signerIsEc) "SHA256withECDSA" else "SHA256withRSA").run {
            initSign(signerKeys.private)
            update(tbs)
            sign()
        }
        return Identity(keys, seq(tbs, sigAlg, bits(signature)), subjectName, issuer?.name ?: subjectName, BigInteger.valueOf(serial), ski)
    }

    /** Options for [cms]. */
    class CmsOptions(
        val digestOid: String = SHA256,
        val digestJca: String = "SHA-256",
        val signedAttributes: Boolean = true,
        val bySubjectKeyId: Boolean = false,
        val extraAttributes: List<ByteArray> = emptyList(),
        val contentType: String = DATA,
        val signingTime: String = "260928120000Z",
        /** The signature algorithm: null signs with rsaEncryption or ECDSA by the identity's key. */
        val pss: PSSParameterSpec? = null,
        val ecJca: String = "SHA256withECDSA",
        val ecOid: String = ECDSA_SHA256,
        /** Changes the signature after signing. */
        val corruptSignature: Boolean = false,
    )

    /**
     * A CMS SignedData by [signer] over [content]. [encapsulate] puts the content inside, as
     * `adbe.pkcs7.sha1` and timestamps do; otherwise it is detached, as `adbe.pkcs7.detached` is.
     */
    fun cms(signer: Identity, certs: List<Identity>, content: ByteArray, encapsulate: Boolean = false, o: CmsOptions = CmsOptions()): ByteArray {
        val attributes = if (o.signedAttributes) set(
            seq(oid("1.2.840.113549.1.9.3"), set(oid(o.contentType))),
            seq(oid("1.2.840.113549.1.9.5"), set(utcTime(o.signingTime))),
            seq(oid("1.2.840.113549.1.9.4"), set(octets(sha(o.digestJca, content)))),
            *o.extraAttributes.toTypedArray(),
        ) else null
        val covered = attributes ?: content
        val (sigAlg, signature) = when {
            o.pss != null -> {
                val params = if (o.pss === PSS_DEFAULTS) seq() else seq(
                    tagged(0xA0, algorithm(SHA256)),
                    tagged(0xA1, algorithm("1.2.840.113549.1.1.8", algorithm(SHA256))),
                    tagged(0xA2, int(o.pss.saltLength.toLong())),
                )
                algorithm(RSA_PSS, params) to signer.sign(covered, "RSASSA-PSS", o.pss)
            }
            signer.isEc -> algorithm(o.ecOid) to signer.sign(covered, o.ecJca)
            else -> algorithm(RSA, NULL) to signer.sign(covered, o.digestJca.replace("-", "") + "withRSA")
        }
        val finalSignature = if (o.corruptSignature) signature.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else signature
        val sid = if (o.bySubjectKeyId) Asn1.encode(0x80, signer.ski) else seq(signer.issuerName, int(signer.serial))
        val signerInfo = seq(
            int(if (o.bySubjectKeyId) 3 else 1),
            sid,
            algorithm(o.digestOid),
            *(attributes?.let { arrayOf(byteArrayOf(0xA0.toByte()) + it.copyOfRange(1, it.size)) } ?: emptyArray()),
            sigAlg,
            octets(finalSignature),
        )
        val encapsulated = if (encapsulate) seq(oid(o.contentType), tagged(0xA0, octets(content))) else seq(oid(o.contentType))
        val signedData = seq(
            int(1),
            set(algorithm(o.digestOid)),
            encapsulated,
            *(if (certs.isEmpty()) emptyArray() else arrayOf(tagged(0xA0, *certs.map { it.cert }.toTypedArray()))),
            set(signerInfo),
        )
        return seq(oid("1.2.840.113549.1.7.2"), tagged(0xA0, signedData))
    }

    /** The ESS signing-certificate-v2 attribute naming [cert] by its SHA-256 hash (RFC 5035). */
    fun essV2(cert: ByteArray): ByteArray =
        seq(oid("1.2.840.113549.1.9.16.2.47"), set(seq(seq(seq(octets(sha("SHA-256", cert)))))))

    /** A TSTInfo whose imprint is the SHA-256 of [data] (RFC 3161, 2.4.2). */
    fun tstInfo(data: ByteArray, genTime: String = "20260928120000Z"): ByteArray = seq(
        int(1),
        oid("1.2.3.4.1"),
        seq(algorithm(SHA256), octets(sha("SHA-256", data))),
        int(42),
        generalizedTime(genTime),
    )

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    /** Options for [signedPdf]. */
    class PdfOptions(
        val subFilter: String = "adbe.pkcs7.detached",
        val type: String = "Sig",
        val extraEntries: String = "",
        val holeSize: Int = 16384,
        val dssCerts: List<ByteArray> = emptyList(),
        /** The key whose string holds the signature. Anything but `Contents` leaves `/Contents <00>` as a decoy. */
        val holeKey: String = "Contents",
        /** The first signed byte: above 0 leaves the start of the file unsigned. */
        val rangeStart: Int = 0,
    )

    /**
     * A one-page PDF with one signature field, signed by [sign], which gets the signed bytes and
     * returns the value of `/Contents`. The byte range skips exactly the `/Contents` string.
     */
    fun signedPdf(o: PdfOptions = PdfOptions(), sign: (ByteArray) -> ByteArray): ByteArray {
        val placeholder = "[0 0000000000 0000000000 0000000000]"
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /SigFlags 3 >>" + (if (o.dssCerts.isNotEmpty()) " /DSS 6 0 R" else "") + " >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 5 0 R >>",
            "<< /Type /${o.type} /Filter /Adobe.PPKLite /SubFilter /${o.subFilter} /Name (KitePDF Test) /Reason (Testing) " +
                "/Location (Here) /M (D:20260928120000Z) ${o.extraEntries} /ByteRange $placeholder " +
                (if (o.holeKey != "Contents") "/Contents <00> " else "") + "/${o.holeKey} <${"0".repeat(o.holeSize * 2)}> >>",
        )
        if (o.dssCerts.isNotEmpty()) {
            objects += "<< /Certs [${o.dssCerts.indices.joinToString(" ") { "${7 + it} 0 R" }}] >>"
        }
        val streams = o.dssCerts.map { it }
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = ArrayList<Int>()
        for ((i, body) in objects.withIndex()) {
            offsets += out.size()
            out.write("${i + 1} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        for ((i, der) in streams.withIndex()) {
            offsets += out.size()
            out.write("${objects.size + i + 1} 0 obj\n<< /Length ${der.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(der)
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val count = offsets.size + 1
        out.write("xref\n0 $count\n0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (off in offsets) out.write("%010d 00000 n \n".format(off).toByteArray(Charsets.ISO_8859_1))
        out.write("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        val pdf = out.toByteArray()
        val text = String(pdf, Charsets.ISO_8859_1)
        val a = text.indexOf("/${o.holeKey} <" + "0".repeat(16)) + "/${o.holeKey} ".length
        val b = text.indexOf('>', a) + 1
        val range = "[${o.rangeStart} ${a - o.rangeStart} $b ${pdf.size - b}"
        val rangeAt = text.indexOf(placeholder)
        (range + " ".repeat(placeholder.length - range.length - 1) + "]").toByteArray(Charsets.ISO_8859_1).copyInto(pdf, rangeAt)
        val signed = pdf.copyOfRange(o.rangeStart, a) + pdf.copyOfRange(b, pdf.size)
        val value = hex(sign(signed))
        require(value.length <= o.holeSize * 2) { "the signature does not fit its hole" }
        value.toByteArray(Charsets.ISO_8859_1).copyInto(pdf, a + 1)
        return pdf
    }

    /** [pdf] with an incremental update appended: a new /Info dictionary, as an editor saving after signing writes. */
    fun appendRevision(pdf: ByteArray): ByteArray {
        val text = String(pdf, Charsets.ISO_8859_1)
        val prev = text.substring(text.lastIndexOf("startxref") + "startxref".length).trim().lines().first().trim()
        val size = Regex("/Size (\\d+)").findAll(text).last().groupValues[1].toInt()
        val out = java.io.ByteArrayOutputStream()
        out.write(pdf)
        val objectAt = out.size()
        out.write("$size 0 obj\n<< /Title (Changed after signing) >>\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        val xref = out.size()
        out.write("xref\n$size 1\n%010d 00000 n \ntrailer\n<< /Size ${size + 1} /Root 1 0 R /Info $size 0 R /Prev $prev >>\nstartxref\n$xref\n%%EOF\n".format(objectAt).toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** [bytes] as PEM, such as a certificate or a PKCS #8 private key. */
    fun pem(label: String, bytes: ByteArray): String =
        "-----BEGIN $label-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) + "\n-----END $label-----\n"

    /** The PSS parameters that an empty parameter set stands for: SHA-1 throughout and a salt of 20 bytes. */
    val PSS_DEFAULTS = PSSParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, 20, 1)

    /** A PSS parameter set with SHA-256 throughout and a salt of 32 bytes. */
    val PSS_SHA256 = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
}
