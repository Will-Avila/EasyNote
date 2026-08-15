package com.will.noteharbor.data

/** Derived progress shown for checklist notes without changing the persisted model. */
data class ChecklistProgress(
    val completed: Int,
    val total: Int,
    val percent: Int,
) {
    val label: String
        get() = "$completed de $total concluídos"

    companion object {
        fun of(items: List<ChecklistItem>): ChecklistProgress {
            val total = items.size
            val completed = items.count { it.completed }
            val percent = if (total == 0) 0 else (completed * 100) / total
            return ChecklistProgress(completed, total, percent)
        }
    }
}
