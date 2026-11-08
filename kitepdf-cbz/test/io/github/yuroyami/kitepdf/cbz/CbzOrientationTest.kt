package io.github.yuroyami.kitepdf.cbz

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** A comic page shot as a turned photo takes its upright size, as it draws upright (#826). */
class CbzOrientationTest {

    /** A 40 x 20 JPEG whose EXIF orientation 6 turns it a quarter, upright 20 x 40. */
    private val turned = Base64.decode("/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAYAAAAAAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAAUACgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8X6K/OX/hfPxX/wChq/8AKH4b/wDlPR/wvn4r/wDQ1f8AlD8N/wDynr+pv+Lcb6b3/R0/oq/+Jv4uf/SOP+jz/ivh9D7/AKNv9JT/AMQ/wv8A/pxn9IX/AATz/wCavf8Acgf+7rX6UV8Xf8G0nhrRP2j/APhtT/hc9l/wmX/CG/8ADOX/AAjf+k3fh3+zf+Ei/wCF8f2x/wAipPof2z7Z/Yelf8f/ANq+z/Zf9F8jz7nzv6mf+GPP2c/+id/+Xd46/wDmnr/kn/ab8P4z6DP04PG36LPi1VwvEPiD4Xf8Q2/1gzjw6nVzbg7Gf67eEXAPiLlX9j5hxLR4RzvEfV8k4uy3C5h9d4ey/wBlmtDHUMN9bwlOhjsT/wAeH7U/6HPib+0o+nj47fTV8C894E4U8LPGj/iGH+q2QeLOZ8QZF4gYD/iHPg34eeE2d/2/lXB/DHHfDmF+tcR8CZvjcq/s7ivNfb5LictxOL+o46tistwf4b0V+5H/AAx5+zn/ANE7/wDLu8df/NPRX8G/8TJ8C/8AQq4s/wDCHJ//AJ+n+fX/ABRS+lP/ANF99H//AMSrxF/+lUf5N9FFFf8AuOH+mB/Yp/waa/8AN/f/AHav/wC/HV/YpRRX/kC/6Ud/yvX+nN/3rN/7B59H0/vzwT/5Njwz/wB1n/1oM2Ciiiv8AT9UP//Z")

    @Test
    fun a_page_takes_the_upright_size_of_its_photo() {
        val doc = CbzDocument.open(CbzFixtures.storedZip(listOf("p1.jpg" to turned)))
        val page = doc.pages[0]
        assertEquals(20.0 to 40.0, page.displayWidth to page.displayHeight)
    }
}
