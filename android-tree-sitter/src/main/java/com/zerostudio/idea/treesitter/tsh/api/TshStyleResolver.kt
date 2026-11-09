package com.zerostudio.idea.treesitter.tsh.api

sealed interface TshScopeSelectorSegment {
    data class Exact(val scopeId: String) : TshScopeSelectorSegment {
        init { require(scopeId.isNotBlank()) }
    }
    data object Wildcard : TshScopeSelectorSegment
}

enum class TshScopeSelectorMode { EXACT_PATH, SUFFIX_PATH }

data class TshScopeSelector(
    val segments: List<TshScopeSelectorSegment>,
    val mode: TshScopeSelectorMode = TshScopeSelectorMode.SUFFIX_PATH
) {
    init { require(segments.isNotEmpty()) }

    val specificity: Int get() = segments.count { it is TshScopeSelectorSegment.Exact }

    fun matches(path: TshScopePath): Boolean {
        if (mode == TshScopeSelectorMode.EXACT_PATH && segments.size != path.segments.size) return false
        if (segments.size > path.segments.size) return false
        val start = if (mode == TshScopeSelectorMode.SUFFIX_PATH) path.segments.size - segments.size else 0
        return segments.indices.all { index ->
            when (val selector = segments[index]) {
                is TshScopeSelectorSegment.Exact -> path.segments[start + index].id == selector.scopeId
                TshScopeSelectorSegment.Wildcard -> true
            }
        }
    }
}

data class TshStyleRule(
    val id: TshRuleId,
    val selector: TshScopeSelector,
    val style: TshResolvedStyle,
    val priority: Int = 0,
    val zIndex: Int = 0
)

object TshStyleResolver {
    fun resolve(
        matches: TshMatchSet,
        revisions: TshRenderRevisions,
        planId: TshRenderPlanId,
        rules: Collection<TshStyleRule>,
        requiredCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY
    ): TshResult<TshRenderPlan> {
        if (!matches.isCurrentFor(
                revisions.documentId,
                revisions.documentVersion,
                revisions.treeSitterRevision,
                revisions.regexRevision,
                revisions.configRevision
            )) {
            return failure("TSH-STYLE-001", "MatchSet revisions do not match the requested RenderPlan revisions")
        }
        if (rules.map { it.id }.distinct().size != rules.size) {
            return failure("TSH-STYLE-002", "Style rule ids must be unique within one resolution")
        }

        val orderedRules = rules.sortedWith(
            compareBy<TshStyleRule> { it.priority }
                .thenBy { it.selector.specificity }
                .thenBy { it.id.value }
        )
        val instructions = ArrayList<TshRenderInstruction>()
        for (match in matches.matches) {
            for (assignment in match.scopeAssignments) {
                if (assignment.range.start.coordinateSystem != TshCoordinateSystem.UTF16_CODE_UNIT ||
                    (assignment.range.documentVersion != null &&
                        assignment.range.documentVersion != match.documentVersion)) {
                    return failure(
                        "TSH-STYLE-003",
                        "Scope assignment range is not canonical or belongs to another document version"
                    )
                }
                val applicable = orderedRules.filter { it.selector.matches(assignment.path) }
                if (applicable.isEmpty()) continue
                var resolved = TshResolvedStyle()
                applicable.forEach { rule -> resolved = merge(resolved, rule.style) }
                instructions += TshRenderInstruction(
                    range = assignment.range,
                    style = resolved,
                    zIndex = applicable.maxOf { it.zIndex },
                    priority = applicable.maxOf { it.priority },
                    sourceMatchIds = setOf(match.id)
                )
            }
        }

        val stable = instructions.sortedWith(
            compareBy<TshRenderInstruction> { it.range.start.offset }
                .thenByDescending { it.range.end.offset }
                .thenBy { it.zIndex }
                .thenBy { it.priority }
        )
        return TshResult.Success(
            TshRenderPlan(planId, revisions, stable, requiredCapabilities),
            matches.diagnostics
        )
    }

    private fun merge(base: TshResolvedStyle, overlay: TshResolvedStyle) = TshResolvedStyle(
        foregroundArgb = overlay.foregroundArgb ?: base.foregroundArgb,
        backgroundArgb = overlay.backgroundArgb ?: base.backgroundArgb,
        fontFamilies = overlay.fontFamilies.ifEmpty { base.fontFamilies },
        fontSize = overlay.fontSize ?: base.fontSize,
        fontWeight = overlay.fontWeight ?: base.fontWeight,
        italic = overlay.italic ?: base.italic,
        underline = overlay.underline ?: base.underline,
        strikeThrough = overlay.strikeThrough ?: base.strikeThrough,
        decorations = base.decorations + overlay.decorations
    )

    private fun failure(code: String, message: String) = TshResult.Failure(listOf(TshDiagnostic(
        code = code,
        severity = TshDiagnosticSeverity.ERROR,
        stage = TshDiagnosticStage.RENDER,
        message = message
    )))
}
