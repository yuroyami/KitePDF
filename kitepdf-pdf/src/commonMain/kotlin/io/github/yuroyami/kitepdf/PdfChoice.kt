package io.github.yuroyami.kitepdf

/** One `/Opt` entry, retaining its original index and both strings (ISO 32000-1, 12.7.4.4). */
public data class PdfChoiceOption(
    /** Index in the file's `/Opt` array, including gaps left by damaged entries. */
    public val index: Int,
    /** The value stored in `/V` and supplied to scripts. */
    public val exportValue: String,
    /** The text shown to the reader. */
    public val label: String,
)

/**
 * A choice field's complete value (ISO 32000-1, 12.7.4.4). [indices] identifies options, so
 * equal labels or exports remain distinct. An editable combo can instead hold [freeText].
 * [unresolvedValues] preserves values from a damaged source file that match no option; new
 * edits cannot introduce them. Empty indices with no text means no selection, which differs
 * from selecting an option whose export string is empty. Lists are immutable snapshots.
 */
public class PdfChoiceSelection(
    indices: List<Int>,
    public val freeText: String? = null,
    unresolvedValues: List<String> = emptyList(),
) {
    public val indices: List<Int> = choiceSnapshot(indices)
    public val unresolvedValues: List<String> = choiceSnapshot(unresolvedValues)

    override fun equals(other: Any?): Boolean = other is PdfChoiceSelection &&
        indices == other.indices && freeText == other.freeText && unresolvedValues == other.unresolvedValues

    override fun hashCode(): Int = 31 * (31 * indices.hashCode() + (freeText?.hashCode() ?: 0)) + unresolvedValues.hashCode()

    override fun toString(): String = "PdfChoiceSelection(indices=$indices, freeText=$freeText, unresolvedValues=$unresolvedValues)"
}

/** A read-only interface alone does not stop a caller casting an ArrayList back to MutableList. */
internal fun <T> choiceSnapshot(values: List<T>): List<T> = object : AbstractList<T>() {
    private val snapshot = values.toList()
    override val size: Int get() = snapshot.size
    override fun get(index: Int): T = snapshot[index]
}
