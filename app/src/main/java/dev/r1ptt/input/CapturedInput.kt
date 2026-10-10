package dev.r1ptt.input

enum class InputRoute { CHAT, DRAFT, DISCARDED }

/** A press keeps its original destination. Losing a draft can never turn it into an AI question. */
class CapturedInput<T>(val target: T?) {
    fun route(current: T?, active: Boolean): InputRoute = when {
        target == null -> InputRoute.CHAT
        target === current && active -> InputRoute.DRAFT
        else -> InputRoute.DISCARDED
    }
}
