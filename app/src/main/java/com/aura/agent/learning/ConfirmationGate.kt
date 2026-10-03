package com.aura.agent.learning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * دروازه تأیید کاربر برای ذخیره اطلاعات مهم.
 *
 * هیچ داده مهمی بدون تأیید صریح کاربر ذخیره نمی‌شود.
 * UI باید به [pendingConfirmation] گوش دهد و dialog نمایش دهد.
 */
class ConfirmationGate {

    data class PendingItem(
        val id: String,
        val category: Category,
        val summary: String,
        val rawContent: String,
        val suggestedKey: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Category {
        MEMORY_FACT,
        FEEDBACK,
        KNOWLEDGE,
        OTHER
    }

    enum class Decision {
        APPROVE,
        EDIT_AND_APPROVE,
        REJECT
    }

    private val _pendingConfirmation = MutableStateFlow<PendingItem?>(null)
    val pendingConfirmation: StateFlow<PendingItem?> = _pendingConfirmation.asStateFlow()

    private var onDecision: ((PendingItem, Decision, String?) -> Unit)? = null

    fun propose(
        item: PendingItem,
        onResult: (PendingItem, Decision, String?) -> Unit
    ) {
        onDecision = onResult
        _pendingConfirmation.value = item
    }

    fun resolve(decision: Decision, editedContent: String? = null) {
        val item = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        onDecision?.invoke(item, decision, editedContent)
        onDecision = null
    }

    fun cancel() {
        _pendingConfirmation.value = null
        onDecision = null
    }

    fun hasPending(): Boolean = _pendingConfirmation.value != null
}
