package io.github.yuroyami.kitepdf

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An encrypted file whose catalog and page tree sit in an object stream opens: the stream is decrypted as
 * a whole before its filters run (ISO 32000-1, 7.5.7, 7.6.2, #793). The files are
 * audit/fixtures/encrypted-object-streams/enc_objstm_r4.pdf and enc_objstm_r6.pdf, written by pikepdf
 * with an empty user password.
 */
class EncryptedObjectStreamTest {

    private val aes128 = Base64.decode("JVBERi0xLjYKJb/3ov4KMSAwIG9iago8PCAvUGFnZXMgMyAwIFIgL1R5cGUgL0NhdGFsb2cgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL09ialN0bSAvTGVuZ3RoIDIyNCAvRmlsdGVyIC9GbGF0ZURlY29kZSAvTiA0IC9GaXJzdCAyMSA+PgpzdHJlYW0K3fLYceWhLzUpqbeB77RrEelaVN8IrQ71hE4JTOLOTKQJh0eU+wnzBTmbVjU0ZnNPeveL5L3JZtLHi0ynEfwn2QcLmpP4lvyNXgdlA/v5PNSbdHwIuBoVoOiTyXoYBYWwP0Tq58FH4JIfJNbYr2YFdAMybLp6uwwwsIAuj7g8w22+qikOLZq4gjRAcgBBsW7YSah0Klv8Vvf3X3O0Mnf+bwCCjallPmsbk67GzALmY91Kqor95EZavPVHJ0GBXJ7cGg5XCwnu+eTOqKmRIbvfZYR/vE2ql4VD1FhmjsFh5KgKZW5kc3RyZWFtCmVuZG9iago3IDAgb2JqCjw8IC9MZW5ndGggODAgL0ZpbHRlciAvRmxhdGVEZWNvZGUgPj4Kc3RyZWFtCqW5N9pjZJBptyG1uhWg1/FRqBGOPdwtR6Z3CTNCnY+ZRPKkV5TX7gBzdmX+NJ/UDzf9LmOSSHt36eqbvncSzBUDOAbizhXyZ10LXd7n2ESpCmVuZHN0cmVhbQplbmRvYmoKOCAwIG9iago8PCAvQ0YgPDwgL1N0ZENGIDw8IC9BdXRoRXZlbnQgL0RvY09wZW4gL0NGTSAvQUVTVjIgL0xlbmd0aCAxNiA+PiA+PiAvRmlsdGVyIC9TdGFuZGFyZCAvTGVuZ3RoIDEyOCAvTyA8NTY2ZmE4NzNlZTMzYzc5N2NkM2I5MDRmZGFkZjgxNGFmYTM0ZGY5YTM4ZjZlZDQxYjk4NGUyYzZkYTJhYTZmNT4gL09FIDw+IC9QIC0xMDI4IC9SIDQgL1N0bUYgL1N0ZENGIC9TdHJGIC9TdGRDRiAvVSA8MDNiOTExNDliNjhmOGE0MWM4MGE0MGVmNDliNzc1ZTgwMDIxNDQ2OTkwYjllNDExNDA3MWE0ZDkxMDQ5ODRjMT4gL1VFIDw+IC9WIDQgPj4KZW5kb2JqCjkgMCBvYmoKPDwgL1R5cGUgL1hSZWYgL0xlbmd0aCA0MiAvRmlsdGVyIC9GbGF0ZURlY29kZSAvRGVjb2RlUGFybXMgPDwgL0NvbHVtbnMgNCAvUHJlZGljdG9yIDEyID4+IC9XIFsgMSAyIDEgXSAvSW5mbyA2IDAgUiAvUm9vdCAxIDAgUiAvU2l6ZSAxMCAvSUQgWzxkYzBlYWFjNDUzZmQ0YjczNTY4MjFkOWUyZmNmNGU2ZT48ZGMwZWFhYzQ1M2ZkNGI3MzU2ODIxZDllMmZjZjRlNmU+XSAvRW5jcnlwdCA4IDAgUiA+PgpzdHJlYW0KeJxjYgACJkYGfgYmBgZDEOsQiMXAiET8Z2z+y8TAOB0owWjDAABIVgRxCmVuZHN0cmVhbQplbmRvYmoKc3RhcnR4cmVmCjg1NgolJUVPRgo=")
    private val aes256 = Base64.decode("JVBERi0xLjcKJb/3ov4KMSAwIG9iago8PCAvRXh0ZW5zaW9ucyA8PCAvQURCRSA8PCAvQmFzZVZlcnNpb24gLzEuNyAvRXh0ZW5zaW9uTGV2ZWwgOCA+PiA+PiAvUGFnZXMgMyAwIFIgL1R5cGUgL0NhdGFsb2cgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL09ialN0bSAvTGVuZ3RoIDIyNCAvRmlsdGVyIC9GbGF0ZURlY29kZSAvTiA0IC9GaXJzdCAyMSA+PgpzdHJlYW0K70r3KGPvB7GkZFqeArLu5S+cYdEpykkWF6YheltqjEPRhWnr+ZtVhK4+zIupvVa53RSwhNjDOBnKWlYM6//GwZBiuV8m+fdeJkUbDDslCBgd00syPh9xD9WiHct0leyE6jDgBML6ndbD4eUeH9NVNRi9D3OQPltUAj/kCju4k7xF/krdiqZN7JApVvoKI4J767E7NMIjL9F+LFgCjtOLkmDCimdCfJby9hEeIGbJ/CNCDGbIv2g/WuvoJkY2HTOKCFUFJvhsBXsxXqz67maNmXYxN1EZUdBQ8TaeE5FpK5UKZW5kc3RyZWFtCmVuZG9iago3IDAgb2JqCjw8IC9MZW5ndGggODAgL0ZpbHRlciAvRmxhdGVEZWNvZGUgPj4Kc3RyZWFtCkahyndX0gLbmbY4UEHYQU/FHSez/BxOem442wM9u4JB7I7qg8GuMitOFnyS8sedKOSJ7KKHWPZK7cnBDWfVDB5s4KWbz7DMV5LaN1SGCvX+CmVuZHN0cmVhbQplbmRvYmoKOCAwIG9iago8PCAvQ0YgPDwgL1N0ZENGIDw8IC9BdXRoRXZlbnQgL0RvY09wZW4gL0NGTSAvQUVTVjMgL0xlbmd0aCAzMiA+PiA+PiAvRmlsdGVyIC9TdGFuZGFyZCAvTGVuZ3RoIDI1NiAvTyA8NDVjZTQ3YzkzOGI4N2NkYzExMjQwODQxMGIyYjVjZTVjYTk3NTI3NGY4NDRlZTAwNjkzZGE1YjljYmY1OGY1MDYwMjQ5MGY0NjhlYjNhNTUyYzQ5OWUwNTJjZWM0N2I0PiAvT0UgPDhjZTRiNzc4OGY1MDM1ZTg3ODFhM2YyNGVmOGUzNDgwNGJmMjRlZDNkNWQ0ZmU0Y2EwZjczZmZjZDRiMzhhODE+IC9QIC0xMDI4IC9QZXJtcyA8MTBkNzczMThmNTFmNDlkOTNhNzA1OTIyMzQzMjcxZWY+IC9SIDYgL1N0bUYgL1N0ZENGIC9TdHJGIC9TdGRDRiAvVSA8YzUwMWVmYmY2YzAyZmRjNDFmZDVmYTZjZDZlZDhhNjgyZGJkNjVkMzA4NjkzNTI2ODlhMjZjMDQ3NWM1MjE1MGZmNTY4YzkzNGVmZDllODczMzgwMDMzNWE0ZTZmMDA0PiAvVUUgPGE2NmY1ZThhMjJhNmU3ODY0ZmViN2Y4YWFkNTgxNjFlMmVhNThjYmMxMTRlMmY4MWRlZDM4MWU2MTAzMjg0YTY+IC9WIDUgPj4KZW5kb2JqCjkgMCBvYmoKPDwgL1R5cGUgL1hSZWYgL0xlbmd0aCA0MiAvRmlsdGVyIC9GbGF0ZURlY29kZSAvRGVjb2RlUGFybXMgPDwgL0NvbHVtbnMgNCAvUHJlZGljdG9yIDEyID4+IC9XIFsgMSAyIDEgXSAvSW5mbyA2IDAgUiAvUm9vdCAxIDAgUiAvU2l6ZSAxMCAvSUQgWzxkYzBlYWFjNDUzZmQ0YjczNTY4MjFkOWUyZmNmNGU2ZT48ZGMwZWFhYzQ1M2ZkNGI3MzU2ODIxZDllMmZjZjRlNmU+XSAvRW5jcnlwdCA4IDAgUiA+PgpzdHJlYW0KeJxjYgACJkYGfgYmBoZiEKsBxGJgRCL+Mx79y8TAOB0owaTGAABMjwSeCmVuZHN0cmVhbQplbmRvYmoKc3RhcnR4cmVmCjExNTYKJSVFT0YK")

    @Test
    fun anEncryptedFileWithObjectStreamsOpens() {
        for ((name, bytes) in listOf("AES-128" to aes128, "AES-256" to aes256)) {
            val doc = PdfDocument.open(bytes, "")
            assertEquals(1, doc.pageCount, name)
            assertTrue("Hello objstm" in doc.pages[0].extractText(), "$name: ${doc.pages[0].extractText()}")
        }
    }

    @Test
    fun repairReadsTheMembersOfAnEncryptedObjectStream() {
        // A damaged startxref sends the file to repair, which ran before the key existed.
        val needle = "startxref".encodeToByteArray()
        val at = (aes128.size - needle.size downTo 0).first { i -> needle.indices.all { aes128[i + it] == needle[it] } }
        val broken = aes128.copyOf(at) + "startxref\n999999\n%%EOF\n".encodeToByteArray()
        val doc = PdfDocument.open(broken, "")
        assertTrue("Hello objstm" in doc.pages[0].extractText(), doc.pages[0].extractText())
    }

    /** The R6 /Encrypt of enc_objstm_r6.pdf with /StmF and /StrF as given, over a page whose content is in clear. */
    private fun v5(stmF: String, strF: String): ByteArray {
        val encrypt = "<< /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /Filter /Standard /Length 256 " +
            "/O <45ce47c938b87cdc112408410b2b5ce5ca975274f844ee00693da5b9cbf58f50602490f468eb3a552c499e052cec47b4> " +
            "/OE <8ce4b7788f5035e8781a3f24ef8e34804bf24ed3d5d4fe4ca0f73ffcd4b38a81> /P -1028 /Perms <10d77318f51f49d93a705922343271ef> /R 6 " +
            "/StmF /$stmF /StrF /$strF /U <c501efbf6c02fdc41fd5fa6cd6ed8a682dbd65d30869352689a26c0475c52150ff568c934efd9e8733800335a4e6f004> " +
            "/UE <a66f5e8a22a6e7864feb7f8aad58161e2ea58cbc114e2f81ded381e6103284a6> /V 5 >>"
        val content = "BT /F1 24 Tf 20 100 Td <436c6561722073747265616d2074657874> Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            encrypt,
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 6 /Root 1 0 R /Encrypt 5 0 R /ID [<dc0eaac453fd4b7356821d9e2fcf4e6e><dc0eaac453fd4b7356821d9e2fcf4e6e>] >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun anAes256FileWithIdentityStreamsReadsThemInClear() {
        // The content is stored in clear, and was AES-256 decrypted into noise (#796).
        for ((stmF, strF) in listOf("Identity" to "StdCF", "Identity" to "Identity")) {
            val doc = PdfDocument.open(v5(stmF, strF), "")
            assertTrue(doc.isAuthenticated)
            assertTrue("Clear stream text" in doc.pages[0].extractText(), "$stmF $strF: ${doc.pages[0].extractText()}")
        }
    }
}
