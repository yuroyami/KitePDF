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
    fun certificate(cn: String, keys: KeyPair, issuer: Identity?, ca: Boolean, serial: Long, notAfter: String = "360101000000Z", ocspSigning: Boolean = false): Identity {
        val subjectName = name(cn)
        val ski = sha("SHA-1", keys.public.encoded)
        val signerKeys = issuer?.keys ?: keys
        val signerIsEc = signerKeys.private.algorithm == "EC"
        val sigAlg = if (signerIsEc) algorithm(ECDSA_SHA256) else algorithm(SHA256_RSA, NULL)
        val extensions = mutableListOf(
            seq(oid("2.5.29.19"), Asn1.encode(0x01, byteArrayOf(-1)), octets(if (ca) seq(Asn1.encode(0x01, byteArrayOf(-1))) else seq())),
            seq(oid("2.5.29.14"), octets(octets(ski))),
        )
        if (ocspSigning) extensions += seq(oid("2.5.29.37"), octets(seq(oid("1.3.6.1.5.5.7.3.9"))))
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
        /** The revocation field of the SignedData, [1]: CRLs, and OCSP responses as other formats. */
        val revocation: List<ByteArray> = emptyList(),
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
            *(if (o.revocation.isEmpty()) emptyArray() else arrayOf(tagged(0xA1, *o.revocation.toTypedArray()))),
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

    /** The bits of [identity]'s public key, which an OCSP response hashes. */
    fun keyBits(identity: Identity): ByteArray = Asn1.read(identity.keys.public.encoded, 0)!!.children[1].bitString()!!

    /** A v2 CRL that [issuer] signs, listing [revoked] serial numbers as revoked on [revokedAt] (RFC 5280, 5.1). */
    fun crl(issuer: Identity, revoked: List<BigInteger>, revokedAt: String = "260901000000Z", signer: Identity = issuer): ByteArray {
        val entries = revoked.map { seq(int(it), utcTime(revokedAt)) }
        val tbs = seq(
            int(1),
            signer.signatureAlgorithm,
            issuer.name,
            utcTime("260920000000Z"),
            utcTime("261020000000Z"),
            *(if (entries.isEmpty()) emptyArray() else arrayOf(seq(*entries.toTypedArray()))),
        )
        return seq(tbs, signer.signatureAlgorithm, bits(signer.sign(tbs)))
    }

    /** One status of an OCSP response: good, or revoked at a time. */
    class OcspStatus(val cert: Identity, val issuer: Identity, val revokedAt: String? = null)

    /**
     * An OCSP response that [responder] signs about [statuses] (RFC 6960, 4.2.1), naming the
     * responder by its name or by the SHA-1 of its key, and carrying [certs].
     */
    fun ocsp(responder: Identity, statuses: List<OcspStatus>, byKey: Boolean = false, certs: List<Identity> = emptyList()): ByteArray {
        val singles = statuses.map { s ->
            val id = seq(algorithm(SHA1, NULL), octets(sha("SHA-1", s.cert.issuerName)), octets(sha("SHA-1", keyBits(s.issuer))), int(s.cert.serial))
            val status = s.revokedAt?.let { tagged(0xA1, generalizedTime(it)) } ?: byteArrayOf(0x80.toByte(), 0)
            seq(id, status, generalizedTime("20260925000000Z"))
        }
        val responderId = if (byKey) tagged(0xA2, octets(sha("SHA-1", keyBits(responder)))) else tagged(0xA1, responder.name)
        val data = seq(responderId, generalizedTime("20260925000000Z"), seq(*singles.toTypedArray()))
        val basic = seq(
            data,
            responder.signatureAlgorithm,
            bits(responder.sign(data)),
            *(if (certs.isEmpty()) emptyArray() else arrayOf(tagged(0xA0, seq(*certs.map { it.cert }.toTypedArray())))),
        )
        return seq(Asn1.encode(0x0A, byteArrayOf(0)), tagged(0xA0, seq(oid("1.3.6.1.5.5.7.48.1.1"), octets(basic))))
    }

    /** Options for [signedPdf]. */
    class PdfOptions(
        val subFilter: String = "adbe.pkcs7.detached",
        val type: String = "Sig",
        val extraEntries: String = "",
        val holeSize: Int = 16384,
        val dssCerts: List<ByteArray> = emptyList(),
        val dssCrls: List<ByteArray> = emptyList(),
        val dssOcsps: List<ByteArray> = emptyList(),
        /** The key whose string holds the signature. Anything but `Contents` leaves `/Contents <00>` as a decoy. */
        val holeKey: String = "Contents",
        /** The first signed byte: above 0 leaves the start of the file unsigned. */
        val rangeStart: Int = 0,
        /** The DocMDP level of a certification signature, or null for an approval signature. */
        val certification: Int? = null,
        /** The `/Lock` dictionary of the signature field, or null. */
        val lock: String? = null,
        /** The value of a text field `Name` on the page, or null for a form with the signature field alone. */
        val textField: String? = null,
    )

    /** The object numbers of a [signedPdf], for revisions that change them. */
    class Numbers(val content: Int, val textField: Int, val next: Int) {
        val catalog = 1
        val page = 3
        val signatureField = 4
        val signature = 5
    }

    /** The object numbers that [signedPdf] gives for [o]. */
    fun numbers(o: PdfOptions = PdfOptions()): Numbers {
        val dss = o.dssCerts.size + o.dssCrls.size + o.dssOcsps.size
        val content = if (dss > 0) 7 + dss else 6
        return Numbers(content = content, textField = content + 1, next = content + if (o.textField != null) 2 else 1)
    }

    /**
     * A one-page PDF with one signature field, signed by [sign], which gets the signed bytes and
     * returns the value of `/Contents`. The byte range skips exactly the `/Contents` string.
     */
    fun signedPdf(o: PdfOptions = PdfOptions(), sign: (ByteArray) -> ByteArray): ByteArray {
        val placeholder = "[0 0000000000 0000000000 0000000000]"
        val dss = o.dssCerts + o.dssCrls + o.dssOcsps
        val n = numbers(o)
        val fields = "4 0 R" + (if (o.textField != null) " ${n.textField} 0 R" else "")
        // A level of 0 writes the DocMDP reference without /P, which means level 2.
        val docMdp = o.certification?.let { level ->
            val p = if (level == 0) "" else "/P $level "
            "/Reference [<< /Type /SigRef /TransformMethod /DocMDP /TransformParams << /Type /TransformParams $p/V /1.2 >> >>] "
        } ?: ""
        val objects = mutableListOf<ByteArray>()
        fun add(body: String) {
            objects += body.toByteArray(Charsets.ISO_8859_1)
        }
        add(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [$fields] /SigFlags 3 >>" + (if (dss.isNotEmpty()) " /DSS 6 0 R" else "") +
                (if (o.certification != null) " /Perms << /DocMDP 5 0 R >>" else "") + " >>",
        )
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [$fields] /Contents ${n.content} 0 R >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 5 0 R" + (o.lock?.let { " /Lock $it" } ?: "") + " >>")
        add(
            "<< /Type /${o.type} /Filter /Adobe.PPKLite /SubFilter /${o.subFilter} /Name (KitePDF Test) /Reason (Testing) " +
                "/Location (Here) /M (D:20260928120000Z) ${o.extraEntries} $docMdp/ByteRange $placeholder " +
                (if (o.holeKey != "Contents") "/Contents <00> " else "") + "/${o.holeKey} <${"0".repeat(o.holeSize * 2)}> >>",
        )
        if (dss.isNotEmpty()) {
            // The streams follow the DSS dictionary, object 6, in the order of these three arrays.
            var next = 7
            fun refs(items: List<ByteArray>) = items.joinToString(" ") { "${next++} 0 R" }
            add("<< /Certs [${refs(o.dssCerts)}] /CRLs [${refs(o.dssCrls)}] /OCSPs [${refs(o.dssOcsps)}] >>")
            for (der in dss) objects += "<< /Length ${der.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + der + "\nendstream".toByteArray(Charsets.ISO_8859_1)
        }
        add(stream("0 0 1 rg 20 20 50 50 re f"))
        if (o.textField != null) add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V (${o.textField}) >>")
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = ArrayList<Int>()
        for ((i, body) in objects.withIndex()) {
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
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

    /** The body of a stream object that holds [data]. */
    fun stream(data: String): String = "<< /Length ${data.length} >>\nstream\n$data\nendstream"

    /**
     * [pdf] with an incremental update appended, as an editor saving after signing writes: the
     * objects of [objects] by number, new or replacing, and [trailer] entries in the new trailer.
     */
    fun update(pdf: ByteArray, objects: Map<Int, String>, trailer: String = ""): ByteArray {
        val text = String(pdf, Charsets.ISO_8859_1)
        val prev = text.substring(text.lastIndexOf("startxref") + "startxref".length).trim().lines().first().trim()
        val size = maxOf(Regex("/Size (\\d+)").findAll(text).last().groupValues[1].toInt(), objects.keys.max() + 1)
        val out = java.io.ByteArrayOutputStream()
        out.write(pdf)
        val offsets = objects.toSortedMap().map { (number, body) ->
            val at = out.size()
            out.write("$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            number to at
        }
        val xref = out.size()
        out.write("xref\n".toByteArray(Charsets.ISO_8859_1))
        for ((number, at) in offsets) out.write("$number 1\n%010d 00000 n \n".format(at).toByteArray(Charsets.ISO_8859_1))
        out.write("trailer\n<< /Size $size /Root 1 0 R $trailer /Prev $prev >>\nstartxref\n$xref\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /**
     * [update] with a cross-reference stream in place of a table, as most writers since PDF 1.5
     * save (ISO 32000-1, 7.5.8). The stream is an object of the update itself.
     */
    fun updateWithXrefStream(pdf: ByteArray, objects: Map<Int, String>): ByteArray {
        val text = String(pdf, Charsets.ISO_8859_1)
        val prev = text.substring(text.lastIndexOf("startxref") + "startxref".length).trim().lines().first().trim()
        val xrefNumber = maxOf(Regex("/Size (\\d+)").findAll(text).last().groupValues[1].toInt(), objects.keys.max() + 1)
        val out = java.io.ByteArrayOutputStream()
        out.write(pdf)
        val offsets = objects.toSortedMap().map { (number, body) ->
            val at = out.size()
            out.write("$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            number to at
        }
        val xrefAt = out.size()
        val entries = offsets + (xrefNumber to xrefAt)
        // Each entry: type 1, a four-byte offset, a two-byte generation.
        val data = java.io.ByteArrayOutputStream()
        for ((_, at) in entries) data.write(byteArrayOf(1, (at ushr 24).toByte(), (at ushr 16).toByte(), (at ushr 8).toByte(), at.toByte(), 0, 0))
        val index = entries.joinToString(" ") { "${it.first} 1" }
        out.write("$xrefNumber 0 obj\n<< /Type /XRef /Size ${xrefNumber + 1} /Root 1 0 R /Prev $prev /W [1 4 2] /Index [$index] /Length ${data.size()} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data.toByteArray())
        out.write("\nendstream\nendobj\nstartxref\n$xrefAt\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** [pdf] with an incremental update appended: a new /Info dictionary, as an editor saving after signing writes. */
    fun appendRevision(pdf: ByteArray): ByteArray {
        val size = Regex("/Size (\\d+)").findAll(String(pdf, Charsets.ISO_8859_1)).last().groupValues[1].toInt()
        return update(pdf, mapOf(size to "<< /Title (Changed after signing) >>"), trailer = "/Info $size 0 R")
    }

    /** [bytes] as PEM, such as a certificate or a PKCS #8 private key. */
    fun pem(label: String, bytes: ByteArray): String =
        "-----BEGIN $label-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) + "\n-----END $label-----\n"

    /** The PSS parameters that an empty parameter set stands for: SHA-1 throughout and a salt of 20 bytes. */
    val PSS_DEFAULTS = PSSParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, 20, 1)

    /** A PSS parameter set with SHA-256 throughout and a salt of 32 bytes. */
    val PSS_SHA256 = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
}
