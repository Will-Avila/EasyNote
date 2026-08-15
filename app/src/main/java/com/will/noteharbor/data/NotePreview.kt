package com.will.noteharbor.data

object NotePreview {
    private const val MAX_LINES = 1

    fun text(body: String): String {
        val lines = body.lineSequence().toList()
        if (lines.size <= MAX_LINES) return body
        return lines.take(MAX_LINES).joinToString("\n").dropLastWhile { it == ' ' } + " …"
    }

}
