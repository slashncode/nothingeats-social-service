package io.github.slashncode.nothingeats.social.application

import io.github.slashncode.nothingeats.social.domain.FeedItem
import io.github.slashncode.nothingeats.social.domain.Player

interface Feed {
    /** Stores [item] (with its user, only to erase it later) and keeps the newest [keep] items. */
    suspend fun add(
        item: FeedItem,
        userId: String,
        keep: Int,
    )

    /** Newest first. */
    suspend fun latest(limit: Int): List<FeedItem>

    suspend fun deleteByUser(userId: String): Int
}

/** Every open feed stream gets new items, whichever pod consumed the event. */
interface FeedBroadcast {
    suspend fun publish(item: FeedItem)
}

interface Players {
    suspend fun find(userId: String): Player?

    suspend fun findAll(userIds: Collection<String>): Map<String, Player>

    suspend fun save(player: Player)

    suspend fun delete(userId: String): Int
}

data class Score(
    val userId: String,
    val xp: Long,
    /** 1-based. */
    val rank: Int,
)

/** XP per ISO week ("2026-W45"), only of players on the leaderboard. */
interface Leaderboard {
    suspend fun add(
        week: String,
        userId: String,
        xp: Long,
    )

    suspend fun top(
        week: String,
        count: Int,
    ): List<Score>

    suspend fun scoreOf(
        week: String,
        userId: String,
    ): Score?

    /** Leaves the given weeks' boards. */
    suspend fun remove(
        weeks: Collection<String>,
        userId: String,
    )
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
