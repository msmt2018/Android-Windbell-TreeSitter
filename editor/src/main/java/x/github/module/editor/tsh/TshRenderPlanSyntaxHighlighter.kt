package x.github.module.editor.tsh

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditorDocument
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnostic
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticSeverity
import com.zerostudio.idea.treesitter.tsh.api.TshDiagnosticStage
import com.zerostudio.idea.treesitter.tsh.api.TshDocumentId
import com.zerostudio.idea.treesitter.tsh.api.TshRenderInstruction
import com.zerostudio.idea.treesitter.tsh.api.TshRenderPlan
import com.zerostudio.idea.treesitter.tsh.api.TshResult
import com.zerostudio.idea.treesitter.tsh.api.TshCoordinateSystem
import java.lang.reflect.Array as ReflectArray

/**
 * Connects a versioned TSH RenderPlan to the EditorView's real LineStyler/SyntaxHighlighter
 * drawing path. It creates only Android spans understood by StyledTextPainter and never owns
 * the EditorView, document, buffer, parser, or syntax tree.
 *
 * [currentDocumentVersion] must read the same monotonically increasing version used by the
 * PieceTree snapshot provider attached to this document (normally getTextBuffer().getVersionId()).
 */
class TshRenderPlanSyntaxHighlighter(
    private val documentId: TshDocumentId,
    private val currentDocumentVersion: () -> Long
) : SyntaxHighlighter {

    private data class RenderSnapshot(
        val plan: TshRenderPlan,
        val index: InstructionIntervalIndex
    )

    private val stateLock = Any()

    @Volatile
    private var host: SyntaxHighlighter.Host? = null

    @Volatile
    private var snapshot: RenderSnapshot? = null

    @Volatile
    private var editInProgress = false

    @Volatile
    var diagnostics: List<TshDiagnostic> = emptyList()
        private set

    /** Installs a RenderPlan only when its document identity and version are current. */
    fun updateRenderPlan(plan: TshRenderPlan): TshResult<Unit> {
        if (plan.revisions.documentId != documentId) {
            return failure("TSH-EDITOR-RENDER-001", "RenderPlan belongs to another document")
        }
        val version = try {
            currentDocumentVersion()
        } catch (error: Exception) {
            return failure("TSH-EDITOR-RENDER-002", "Cannot read the current document version: ${error.message}", error)
        } catch (error: LinkageError) {
            return failure("TSH-EDITOR-RENDER-003", "Document version lookup failed with a linkage error", error)
        }
        if (version < 0L || plan.revisions.documentVersion != version) {
            return failure(
                "TSH-EDITOR-RENDER-004",
                "RenderPlan document version ${plan.revisions.documentVersion} is stale; current version is $version"
            )
        }

        val currentLength = try {
            host?.buffer?.length
        } catch (error: Exception) {
            return failure("TSH-EDITOR-RENDER-005", "Cannot inspect the host buffer length: ${error.message}", error)
        }
        val stableInstructions = ArrayList<TshRenderInstruction>(plan.instructions.size)
        for (instruction in plan.instructions) {
            val start = instruction.range.start
            val end = instruction.range.end
            if (start.coordinateSystem != TshCoordinateSystem.UTF16_CODE_UNIT ||
                end.coordinateSystem != TshCoordinateSystem.UTF16_CODE_UNIT ||
                start.offset < 0L || end.offset <= start.offset ||
                end.offset > Int.MAX_VALUE.toLong() ||
                (currentLength != null && end.offset > currentLength.toLong()) ||
                (instruction.range.documentVersion != null && instruction.range.documentVersion != version)
            ) {
                return failure(
                    "TSH-EDITOR-RENDER-006",
                    "RenderPlan contains an invalid, non-UTF-16, or out-of-document range"
                )
            }
            stableInstructions += instruction.copy(
                style = instruction.style.copy(
                    fontFamilies = instruction.style.fontFamilies.toList(),
                    decorations = instruction.style.decorations.toSet()
                ),
                sourceMatchIds = instruction.sourceMatchIds.toSet()
            )
        }

        val stablePlan = plan.copy(instructions = stableInstructions.toList())
        val unsupported = collectUnsupportedStyleDiagnostics(stablePlan.instructions)
        val intervalIndex = InstructionIntervalIndex(stablePlan.instructions)
        val published = synchronized(stateLock) {
            // An edit may begin while the interval index is being built on a worker. Never
            // publish a plan across that transaction, even if its version check raced the edit.
            if (editInProgress || currentDocumentVersion() != version) {
                false
            } else {
                diagnostics = unsupported
                snapshot = RenderSnapshot(stablePlan, intervalIndex)
                true
            }
        }
        if (!published) {
            return failure(
                "TSH-EDITOR-RENDER-008",
                "Document changed or an edit transaction began while the RenderPlan was being installed"
            )
        }
        host?.invalidateStyles()
        return TshResult.Success(Unit, unsupported)
    }

    /** Drops the current plan; the caller must submit a plan for the new document version. */
    fun clearRenderPlan() {
        synchronized(stateLock) {
            snapshot = null
            diagnostics = emptyList()
        }
        host?.invalidateStyles()
    }

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        host.invalidateStyles()
    }

    override fun onDetached() {
        synchronized(stateLock) {
            snapshot = null
            diagnostics = emptyList()
            editInProgress = false
            host = null
        }
    }

    override fun onTextReplaced(buffer: EditorBuffer) {
        synchronized(stateLock) {
            snapshot = null
            diagnostics = emptyList()
            editInProgress = false
        }
        host?.invalidateStyles()
    }

    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        synchronized(stateLock) {
            snapshot = null
            diagnostics = emptyList()
            editInProgress = true
        }
    }

    override fun onEditFinished() {
        val changed = synchronized(stateLock) {
            val wasEditing = editInProgress
            editInProgress = false
            wasEditing
        }
        if (changed) host?.invalidateStyles()
    }

    override fun isStyleReady(
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Boolean = currentSnapshot() != null

    override fun styleLine(
        text: String,
        bufferLine: Int,
        lineStartOffset: Int,
        start: Int,
        end: Int
    ): Spanned? {
        if (start >= end || text.isEmpty()) return null
        val current = currentSnapshot() ?: return null
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        if (safeStart >= safeEnd) return null

        val absoluteStart = lineStartOffset.toLong() + safeStart.toLong()
        val absoluteEnd = lineStartOffset.toLong() + safeEnd.toLong()
        val matches = current.index.query(absoluteStart, absoluteEnd)
        if (matches.isEmpty()) return null

        // StyledTextPainter selects the first span of each type. Put the most specific
        // overlay first so overlapping instructions resolve deterministically.
        val ordered = matches.sortedWith(
            compareByDescending<TshRenderInstruction> { it.zIndex }
                .thenByDescending { it.priority }
                .thenByDescending { it.range.start.offset }
                .thenBy { it.range.end.offset }
        )
        val spans = ArrayList<TshLineSpan>()
        for (instruction in ordered) {
            val clippedStart = maxOf(absoluteStart, instruction.range.start.offset)
            val clippedEnd = minOf(absoluteEnd, instruction.range.end.offset)
            if (clippedEnd <= clippedStart) continue
            val localStart = (clippedStart - lineStartOffset.toLong()).toInt()
            val localEnd = (clippedEnd - lineStartOffset.toLong()).toInt()
            appendSupportedSpans(instruction, localStart, localEnd, spans)
        }
        return if (spans.isEmpty()) null else TshLineSpanned(text, spans)
    }

    private fun currentSnapshot(): RenderSnapshot? {
        val current = synchronized(stateLock) {
            if (editInProgress) null else snapshot
        } ?: return null
        val version = try {
            currentDocumentVersion()
        } catch (_: Exception) {
            return null
        } catch (_: LinkageError) {
            return null
        }
        return synchronized(stateLock) {
            if (!editInProgress && snapshot === current &&
                version == current.plan.revisions.documentVersion
            ) {
                current
            } else {
                if (snapshot === current && version != current.plan.revisions.documentVersion) {
                    snapshot = null
                    diagnostics = emptyList()
                }
                null
            }
        }
    }

    private fun collectUnsupportedStyleDiagnostics(
        instructions: List<TshRenderInstruction>
    ): List<TshDiagnostic> {
        val unsupported = LinkedHashSet<String>()
        instructions.forEach { instruction ->
            val style = instruction.style
            if (style.fontFamilies.isNotEmpty()) unsupported += "fontFamilies"
            if (style.fontSize != null) unsupported += "fontSize"
            if (style.strikeThrough != null || "strike" in style.decorations ||
                "strikethrough" in style.decorations
            ) unsupported += "strikeThrough"
        }
        return unsupported.map { feature ->
            TshDiagnostic(
                code = "TSH-EDITOR-RENDER-007",
                severity = TshDiagnosticSeverity.WARNING,
                stage = TshDiagnosticStage.RENDER,
                message = "EditorView StyledTextPainter does not currently draw the TSH '$feature' style property"
            )
        }
    }

    private fun appendSupportedSpans(
        instruction: TshRenderInstruction,
        start: Int,
        end: Int,
        output: MutableList<TshLineSpan>
    ) {
        if (end <= start) return
        val style = instruction.style
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        style.foregroundArgb?.let {
            output += TshLineSpan(ForegroundColorSpan(it.toInt()), start, end, flags)
        }
        style.backgroundArgb?.let {
            output += TshLineSpan(BackgroundColorSpan(it.toInt()), start, end, flags)
        }

        val bold = style.fontWeight?.let { it >= 600 } ?: ("bold" in style.decorations)
        val italic = style.italic ?: ("italic" in style.decorations)
        val typefaceStyle = (if (bold) Typeface.BOLD else Typeface.NORMAL) or
            (if (italic) Typeface.ITALIC else Typeface.NORMAL)
        if (typefaceStyle != Typeface.NORMAL) {
            output += TshLineSpan(StyleSpan(typefaceStyle), start, end, flags)
        }
        val underline = style.underline ?: ("underline" in style.decorations)
        if (underline) output += TshLineSpan(UnderlineSpan(), start, end, flags)
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) =
        TshResult.Failure(listOf(TshDiagnostic(
            code = code,
            severity = TshDiagnosticSeverity.ERROR,
            stage = TshDiagnosticStage.RENDER,
            message = message,
            cause = cause
        )))
}

private class InstructionIntervalIndex(instructions: List<TshRenderInstruction>) {
    private val items = instructions.sortedWith(
        compareBy<TshRenderInstruction> { it.range.start.offset }
            .thenBy { it.range.end.offset }
    )
    private val maxEnd = LongArray(if (items.isEmpty()) 1 else items.size * 4)

    init {
        if (items.isNotEmpty()) build(1, 0, items.size)
    }

    fun query(start: Long, end: Long): List<TshRenderInstruction> {
        if (items.isEmpty() || end <= start) return emptyList()
        val result = ArrayList<TshRenderInstruction>()
        collect(1, 0, items.size, start, end, result)
        return result
    }

    private fun build(node: Int, left: Int, right: Int): Long {
        if (right - left == 1) {
            return items[left].range.end.offset.also { maxEnd[node] = it }
        }
        val middle = (left + right) ushr 1
        val value = maxOf(
            build(node shl 1, left, middle),
            build((node shl 1) or 1, middle, right)
        )
        maxEnd[node] = value
        return value
    }

    private fun collect(
        node: Int,
        left: Int,
        right: Int,
        start: Long,
        end: Long,
        output: MutableList<TshRenderInstruction>
    ) {
        if (maxEnd[node] <= start || items[left].range.start.offset >= end) return
        if (right - left == 1) {
            val item = items[left]
            if (item.range.start.offset < end && item.range.end.offset > start) output += item
            return
        }
        val middle = (left + right) ushr 1
        collect(node shl 1, left, middle, start, end, output)
        collect((node shl 1) or 1, middle, right, start, end, output)
    }
}

private data class TshLineSpan(
    val value: Any,
    val start: Int,
    val end: Int,
    val flags: Int
)

/** Immutable, zero-text-copy Spanned view; only the small style-span list is allocated per line. */
private class TshLineSpanned(
    private val text: String,
    private val spans: List<TshLineSpan>
) : Spanned {
    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        text.subSequence(startIndex, endIndex)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        val found = spans.filter { span ->
            type.isInstance(span.value) &&
                if (from == to) span.start <= from && span.end >= to
                else span.start < to && span.end > from
        }
        val result = ReflectArray.newInstance(type, found.size) as Array<T>
        found.forEachIndexed { index, span -> result[index] = span.value as T }
        return result
    }

    override fun getSpanStart(tag: Any?): Int = spans.firstOrNull { it.value === tag }?.start ?: -1

    override fun getSpanEnd(tag: Any?): Int = spans.firstOrNull { it.value === tag }?.end ?: -1

    override fun getSpanFlags(tag: Any?): Int = spans.firstOrNull { it.value === tag }?.flags ?: 0

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit.coerceIn(start.coerceAtLeast(0), length)
        for (span in spans) {
            if (type == null || type.isInstance(span.value)) {
                if (span.start > start && span.start < next) next = span.start
                if (span.end > start && span.end < next) next = span.end
            }
        }
        return next
    }
}
