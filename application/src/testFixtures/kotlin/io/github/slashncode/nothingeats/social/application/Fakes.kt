package io.github.slashncode.nothingeats.social.application

import io.github.slashncode.nothingeats.social.domain.FeedItem
import io.github.slashncode.nothingeats.social.domain.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryFeed : Feed {
    val items = CopyOnWriteArrayList<Pair<FeedItem, String>>()

    override suspend fun add(
        item: FeedItem,
        userId: String,
        keep: Int,
    ) {
        items += item to userId
        while (items.size > keep) items.removeAt(0)
    }

    override suspend fun latest(limit: Int) = items.map { it.first }.sortedByDescending { it.at }.take(limit)

    override suspend fun deleteByUser(userId: String): Int {
        val mine = items.filter { it.second == userId }
        items.removeAll(mine.toSet())
        return mine.size
    }
}

class RecordingBroadcast : FeedBroadcast {
    val published = CopyOnWriteArrayList<FeedItem>()

    override suspend fun publish(item: FeedItem) {
        published += item
    }
}

class InMemoryPlayers : Players {
    val all = ConcurrentHashMap<String, Player>()

    override suspend fun find(userId: String) = all[userId]

    override suspend fun findAll(userIds: Collection<String>) = userIds.mapNotNull { all[it] }.associateBy { it.userId }

    override suspend fun save(player: Player) {
        all[player.userId] = player
    }

    override suspend fun delete(userId: String) = if (all.remove(userId) != null) 1 else 0
}

class InMemoryLeaderboard : Leaderboard {
    val weeks = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    override suspend fun add(
        week: String,
        userId: String,
        xp: Long,
    ) {
        weeks.getOrPut(week) { ConcurrentHashMap() }.merge(userId, xp, Long::plus)
    }

    override suspend fun top(
        week: String,
        count: Int,
    ) = weeks[week]
        .orEmpty()
        .entries
        .sortedByDescending { it.value }
        .take(count)
        .mapIndexed { index, entry -> Score(entry.key, entry.value, index + 1) }

    override suspend fun scoreOf(
        week: String,
        userId: String,
    ) = top(week, Int.MAX_VALUE).firstOrNull { it.userId == userId }

    override suspend fun remove(
        weeks: Collection<String>,
        userId: String,
    ) {
        weeks.forEach { this.weeks[it]?.remove(userId) }
    }
}

class DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
