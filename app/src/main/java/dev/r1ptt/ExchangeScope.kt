package dev.r1ptt

/** A callback belongs to both a connection and an exchange, never whichever happens to be current. */
class ExchangeScope {
    data class Token(val connection: Long, val generation: Long)
    private var generation = 0L
    private var connection = 0L
    private var active = false

    @Synchronized fun begin(connection: Long): Token {
        this.connection = connection
        active = true
        generation++
        return Token(connection, generation)
    }
    @Synchronized fun snapshot(connection: Long): Token = Token(connection, generation)
    @Synchronized fun accepts(token: Token): Boolean = active && token == Token(connection, generation)
    @Synchronized fun end() { active = false; generation++ }
}
