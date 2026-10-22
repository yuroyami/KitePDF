package io.github.yuroyami.kitepdf.core.render

/**
 * The identity of immutable image content, without retaining its pixels or its document.
 *
 * Create one root per document session, then use [child] for a resource address and any
 * content revision. Equal child paths under that root compare equal even after the image
 * was decoded again. Independently created roots never compare equal. Keep a resource's
 * identity only while its pixels, masks and colour interpretation are unchanged (#371).
 *
 * An identity stores only its private namespace and string path. It does not keep an image,
 * a document, a decoder or a callback alive. Supply it with [KiteImageData.withIdentity].
 */
public class KiteImageIdentity private constructor(
    private val namespace: Any,
    private val path: List<String>,
) {
    /** Starts a new identity namespace, independent of every other document or image. */
    public constructor() : this(Any(), emptyList())

    /**
     * A resource or revision below this identity. Segment boundaries matter: `child("a/b")`
     * differs from `child("a").child("b")`. Neither call retains the producer of [value].
     */
    public fun child(value: String): KiteImageIdentity = KiteImageIdentity(namespace, path + "source:$value")

    /** Internal pixel variants cannot collide with a caller's resource names. */
    internal fun rendering(intent: KiteRenderingIntent, blackPointCompensation: Boolean): KiteImageIdentity =
        KiteImageIdentity(namespace, path + "intent:${intent.ordinal}:$blackPointCompensation")

    /** The pixels a reading theme gives the image, under [theme], a key of that theme (#458). */
    internal fun themed(theme: String): KiteImageIdentity = KiteImageIdentity(namespace, path + "theme:$theme")

    override fun equals(other: Any?): Boolean =
        other is KiteImageIdentity && namespace === other.namespace && path == other.path

    override fun hashCode(): Int = 31 * namespace.hashCode() + path.hashCode()
}
