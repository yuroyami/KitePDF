package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.missingAsNull

/**
 * Default colour spaces (ISO 32000-1, 8.6.5.6). When content selects a device colour
 * space, the /ColorSpace dictionary of the current resource dictionary may hold a
 * DefaultGray, DefaultRGB or DefaultCMYK entry. A usable entry replaces the device space
 * of its family, so the colours of a calibrated document are read as calibrated ones.
 *
 * The clause reads the current resource dictionary only, so a form XObject with its own
 * resources does not take the defaults of the page. MuPDF lets a form inherit them.
 *
 * Only a space that the file names as a device family is replaced. An ICC profile that
 * converts like DeviceRGB resolves to [KiteColorSpace.DeviceRGB] too, but it is not a
 * device space, so each caller checks the PDF object, not the resolved space.
 *
 * A CMYK output intent (ISO 32000-1, 14.11.5) is the DefaultCMYK of every resource
 * dictionary that names no usable one of its own, as in MuPDF (#312).
 */
internal object DefaultColorSpaces {

    /** The resource names of the three defaults. */
    val KEYS: Set<String> = setOf("DefaultGray", "DefaultRGB", "DefaultCMYK")

    /** The CMYK output intent of a document: its [source] object, `[/ICCBased profile]`, and the [space] it resolves to. */
    class OutputIntent(val source: PdfArray, val space: KiteColorSpace)

    /** [spaces] with the output intent [intent] as DefaultCMYK, unless they hold a usable DefaultCMYK already. */
    fun withOutputIntent(spaces: Map<String, KiteColorSpace>, intent: OutputIntent?): Map<String, KiteColorSpace> {
        if (intent == null) return spaces
        if (spaces["DefaultCMYK"]?.let { isUsable(it, KiteColorSpace.DeviceCMYK) } == true) return spaces
        return spaces + ("DefaultCMYK" to intent.space)
    }

    /** True when every default in [spaces] comes from the output intent [intent], which is the same on every page. */
    fun onlyOutputIntent(spaces: Map<String, KiteColorSpace>, intent: OutputIntent?): Boolean =
        intent != null && spaces["DefaultCMYK"] === intent.space && "DefaultGray" !in spaces && "DefaultRGB" !in spaces

    /**
     * [device] replaced by its default in [colorSpaces], the resolved /ColorSpace entries of
     * the current resource dictionary. Any other space comes back as it is.
     */
    fun substitute(device: KiteColorSpace, colorSpaces: Map<String, KiteColorSpace>): KiteColorSpace {
        val space = keyOf(device)?.let { colorSpaces[it] } ?: return device
        return if (isUsable(space, device)) space else device
    }

    /** The device family that [obj] names, as a name or as an array that holds only the name. */
    fun deviceFamily(obj: PdfObject?, refs: IndirectResolver): KiteColorSpace? {
        val name = when (val resolved = missingAsNull { obj?.resolve(refs) }) {
            is PdfName -> resolved.value
            is PdfArray -> (resolved.singleOrNull() as? PdfName)?.value
            else -> null
        }
        return when (name) {
            "DeviceGray", "G" -> KiteColorSpace.DeviceGray
            "DeviceRGB", "RGB" -> KiteColorSpace.DeviceRGB
            "DeviceCMYK", "CMYK" -> KiteColorSpace.DeviceCMYK
            else -> null
        }
    }

    /**
     * The space for an image whose /ColorSpace entry is [obj], or null when no default
     * applies. An indexed image converts through its base, so a device base takes the
     * default too, as in MuPDF.
     */
    fun imageSpace(obj: PdfObject?, colorSpaces: Map<String, KiteColorSpace>, refs: IndirectResolver): KiteColorSpace? {
        if (KEYS.none { it in colorSpaces }) return null
        deviceFamily(obj, refs)?.let { device -> return substitute(device, colorSpaces).takeIf { it !== device } }
        val arr = missingAsNull { obj?.resolve(refs) } as? PdfArray ?: return null
        val tag = (arr.firstOrNull() as? PdfName)?.value
        if (tag != "Indexed" && tag != "I") return null
        val device = deviceFamily(arr.getOrNull(1), refs) ?: return null
        val base = substitute(device, colorSpaces).takeIf { it !== device } ?: return null
        val indexed = runCatching { KiteColorSpace.resolve(arr, refs) }.getOrNull() as? KiteColorSpace.Indexed ?: return null
        return KiteColorSpace.Indexed(base, indexed.hival, indexed.palette)
    }

    /**
     * The shading [value] with its /ColorSpace replaced by the default from [resources] when
     * it names a device family, so the shading parses in the default space. The values of
     * its function and its /Background are then read in that space as well.
     */
    fun shading(
        value: PdfObject?,
        resources: PdfDictionary?,
        colorSpaces: Map<String, KiteColorSpace>,
        refs: IndirectResolver,
        intent: OutputIntent? = null,
    ): PdfObject? {
        if (KEYS.none { it in colorSpaces }) return value
        val resolved = missingAsNull { value?.resolve(refs) }
        val dict = when (resolved) {
            is PdfDictionary -> resolved
            is PdfStream -> resolved.dict
            else -> return value
        }
        val device = deviceFamily(dict["ColorSpace"], refs) ?: return value
        if (substitute(device, colorSpaces) === device) return value
        val key = keyOf(device) ?: return value
        val source = if (key == "DefaultCMYK" && colorSpaces[key] === intent?.space) intent?.source
            else runCatching { resources?.getDict("ColorSpace", refs)?.get(key) }.getOrNull()
        if (source == null) return value
        val replaced = PdfDictionary(dict.map + ("ColorSpace" to source))
        return if (resolved is PdfStream) PdfStream(replaced, resolved.rawBytes) else replaced
    }

    /** The shading pattern [value] with its shading passed through [shading]. A tiling pattern comes back as it is. */
    fun pattern(
        value: PdfObject?,
        resources: PdfDictionary?,
        colorSpaces: Map<String, KiteColorSpace>,
        refs: IndirectResolver,
        intent: OutputIntent? = null,
    ): PdfObject? {
        if (KEYS.none { it in colorSpaces }) return value
        val dict = missingAsNull { value?.resolve(refs) } as? PdfDictionary ?: return value
        val entry = dict["Shading"] ?: return value
        val replaced = shading(entry, resources, colorSpaces, refs, intent)
        return if (replaced === entry) value else PdfDictionary(dict.map + ("Shading" to (replaced ?: entry)))
    }

    private fun keyOf(device: KiteColorSpace): String? = when (device) {
        KiteColorSpace.DeviceGray -> "DefaultGray"
        KiteColorSpace.DeviceRGB -> "DefaultRGB"
        KiteColorSpace.DeviceCMYK -> "DefaultCMYK"
        else -> null
    }

    // 8.6.5.6: any space with the same number of components, except Lab, Indexed and Pattern.
    fun isUsable(space: KiteColorSpace, device: KiteColorSpace): Boolean =
        space.componentCount == device.componentCount &&
            space !is KiteColorSpace.Lab && space !is KiteColorSpace.Indexed && space !is KiteColorSpace.Unsupported
}
