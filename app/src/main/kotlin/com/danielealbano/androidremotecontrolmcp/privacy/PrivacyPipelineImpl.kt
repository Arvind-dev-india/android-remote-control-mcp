package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PrivacyPipeline]: runs structural + deterministic detection, and (when a model-backed
 * category is enabled) one batched model pass, merges by priority, filters by enabled category, and
 * renders via the [Redactor]. Fails closed (throws [McpToolException.PrivacyModeUnavailable]) when the
 * model is required but unavailable or inference fails.
 */
@Singleton
class PrivacyPipelineImpl
    @Inject
    constructor(
        private val manager: PrivacyModeManager,
        private val deterministicEngine: DeterministicEngine,
        private val nerEngine: NerEngine,
        private val contextExtractor: ContextExtractor,
        private val redactor: Redactor,
    ) : PrivacyPipeline {
        override suspend fun processText(
            text: String,
            context: DetectionContext,
        ): String = processTexts(listOf(TextItem(text, context))).first()

        override suspend fun processTexts(items: List<TextItem>): List<String> {
            val config = manager.currentConfig()
            if (!config.enabled) return items.map { it.text }
            val modelNeeded = config.modelRequired()
            if (modelNeeded && manager.status.value !is PrivacyModeStatus.Ready) {
                throw McpToolException.PrivacyModeUnavailable(
                    "Privacy mode is enabled but the on-device detection model is not available",
                )
            }
            val deterministic = items.map { deterministicEngine.detect(it.text, it.context) }
            val modelByIndex = if (modelNeeded) runModel(items) else emptyMap()
            return items.mapIndexed { index, item ->
                val merged = DeterministicEngine.mergeOverlaps(deterministic[index] + modelByIndex[index].orEmpty())
                val filtered = merged.filter { config.isCategoryEnabled(it.category) }
                redactor.apply(item.text, filtered, config)
            }
        }

        private suspend fun runModel(items: List<TextItem>): Map<Int, List<PiiDetection>> {
            val segments =
                items.mapIndexedNotNull { index, item ->
                    if (item.text.isBlank()) null else NerSegment(index.toString(), item.context.contextText(), item.text)
                }
            if (segments.isEmpty()) return emptyMap()
            val results =
                try {
                    nerEngine.detect(segments)
                } catch (e: PrivacyModelException) {
                    throw McpToolException.PrivacyModeUnavailable(e.message ?: "model inference failed", e)
                }
            return segments.associate { it.key.toInt() to results[it.key].orEmpty() }
        }

        override suspend fun processTree(result: MultiWindowResult): ProcessedTree {
            val config = manager.currentConfig()
            if (!config.enabled) return ProcessedTree(result, emptyList())

            val items = mutableListOf<TextItem>()
            val refs = mutableListOf<FieldRef>()
            for (window in result.windows) {
                val nearestLabels = contextExtractor.computeNearestLabels(window.tree)
                collectFields(window.tree, nearestLabels, items, refs)
            }

            val redacted = processTexts(items)
            val textByNode = HashMap<String, String>()
            val descByNode = HashMap<String, String>()
            redacted.forEachIndexed { index, value ->
                val ref = refs[index]
                if (ref.isText) textByNode[ref.nodeId] = value else descByNode[ref.nodeId] = value
            }

            val flaggedBounds = mutableListOf<BoundsData>()
            val newWindows = result.windows.map { it.copy(tree = rebuild(it.tree, textByNode, descByNode, flaggedBounds)) }
            return ProcessedTree(result.copy(windows = newWindows), flaggedBounds)
        }

        private fun collectFields(
            node: AccessibilityNodeData,
            nearestLabels: Map<String, String>,
            items: MutableList<TextItem>,
            refs: MutableList<FieldRef>,
        ) {
            val context = contextExtractor.extract(node, nearestLabels[node.id])
            node.text?.takeIf { it.isNotBlank() }?.let {
                items += TextItem(it, context)
                refs += FieldRef(node.id, isText = true)
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                items += TextItem(it, context)
                refs += FieldRef(node.id, isText = false)
            }
            node.children.forEach { collectFields(it, nearestLabels, items, refs) }
        }

        private fun rebuild(
            node: AccessibilityNodeData,
            textByNode: Map<String, String>,
            descByNode: Map<String, String>,
            flaggedBounds: MutableList<BoundsData>,
        ): AccessibilityNodeData {
            val newText = textByNode[node.id] ?: node.text
            val newDesc = descByNode[node.id] ?: node.contentDescription
            if (newText != node.text || newDesc != node.contentDescription) {
                flaggedBounds += node.bounds
            }
            return node.copy(
                text = newText,
                contentDescription = newDesc,
                children = node.children.map { rebuild(it, textByNode, descByNode, flaggedBounds) },
            )
        }

        private data class FieldRef(val nodeId: String, val isText: Boolean)
    }
