package com.brbrs.vinci.util

import com.brbrs.vinci.network.LinkedItemSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * A pointer from one logged interaction to a card/task/note already linked to the same
 * contact (see [com.brbrs.vinci.network.ContactLinksRepository]). Deliberately just the
 * pointer, not a copy of the title/snippet -- those are re-resolved from the contact's live
 * linked-items list each time the interaction is shown, so this never goes stale if the item
 * is renamed or unlinked on the other app's side.
 */
data class InteractionLinkedItemRef(
    val source: LinkedItemSource,
    val itemId: String,
)

/** Serializes to the compact JSON stored in [com.brbrs.vinci.data.CallLogEntity.linkedExternalItems]. */
fun serializeInteractionLinkedItems(refs: List<InteractionLinkedItemRef>): String {
    val arr = JSONArray()
    refs.forEach { ref ->
        arr.put(JSONObject().apply {
            put("source", ref.source.name)
            put("itemId", ref.itemId)
        })
    }
    return arr.toString()
}

/** Parses the JSON stored in [com.brbrs.vinci.data.CallLogEntity.linkedExternalItems] back into a list. Never throws. */
fun parseInteractionLinkedItems(json: String): List<InteractionLinkedItemRef> {
    if (json.isBlank() || json == "[]") return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val source = runCatching { LinkedItemSource.valueOf(obj.optString("source", "")) }.getOrNull()
            val itemId = obj.optString("itemId", "")
            if (source == null || itemId.isBlank()) null else InteractionLinkedItemRef(source, itemId)
        }
    } catch (e: Exception) {
        emptyList()
    }
}
