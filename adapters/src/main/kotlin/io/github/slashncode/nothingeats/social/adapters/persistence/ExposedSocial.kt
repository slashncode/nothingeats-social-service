package io.github.slashncode.nothingeats.social.adapters.persistence

import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.social.application.Feed
import io.github.slashncode.nothingeats.social.application.Players
import io.github.slashncode.nothingeats.social.application.Transactions
import io.github.slashncode.nothingeats.social.domain.FeedItem
import io.github.slashncode.nothingeats.social.domain.FeedKind
import io.github.slashncode.nothingeats.social.domain.Player
import io.github.slashncode.nothingeats.social.domain.Pseudonym
import io.github.slashncode.nothingeats.social.domain.Text
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

internal object FeedItemsTable : Table("feed_items") {
    val id = uuid("id")
    val userId = text("user_id")
    val kind = text("kind")
    val at = timestamp("at")
    val restaurantName = text("restaurant_name").nullable()
    val items = integer("items").nullable()
    val moneyNotSpentCents = long("money_not_spent_cents").nullable()
    val level = integer("level").nullable()
    val badgeDe = text("badge_de").nullable()
    val badgeEn = text("badge_en").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object PlayersTable : Table("players") {
    val userId = text("user_id")
    val adjective = integer("adjective")
    val animal = integer("animal")
    val number = integer("number")
    val onLeaderboard = bool("on_leaderboard")

    override val primaryKey = PrimaryKey(userId)
}

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedFeed(
    private val db: PostgresDatabase,
) : Feed {
    override suspend fun add(
        item: FeedItem,
        userId: String,
        keep: Int,
    ) {
        db.transaction {
            FeedItemsTable.insert {
                it[id] = Uuid.parse(item.id)
                it[this.userId] = userId
                it[kind] = item.kind.name
                it[at] = item.at
                it[restaurantName] = item.restaurantName
                it[items] = item.items
                it[moneyNotSpentCents] = item.moneyNotSpentCents
                it[level] = item.level
                it[badgeDe] = item.badge?.de
                it[badgeEn] = item.badge?.en
            }
            // A ticker, not an archive: everything older than the newest [keep] goes.
            val cutoff =
                FeedItemsTable
                    .selectAll()
                    .orderBy(FeedItemsTable.at to SortOrder.DESC)
                    .limit(1)
                    .offset((keep - 1).toLong())
                    .singleOrNull()
                    ?.get(FeedItemsTable.at)
            if (cutoff != null) FeedItemsTable.deleteWhere { FeedItemsTable.at less cutoff }
        }
    }

    override suspend fun latest(limit: Int): List<FeedItem> =
        db.transaction(readOnly = true) {
            FeedItemsTable
                .selectAll()
                .orderBy(FeedItemsTable.at to SortOrder.DESC)
                .limit(limit)
                .map { it.toItem() }
        }

    override suspend fun deleteByUser(userId: String): Int =
        db.transaction { FeedItemsTable.deleteWhere { FeedItemsTable.userId eq userId } }

    private fun ResultRow.toItem() =
        FeedItem(
            id = this[FeedItemsTable.id].toString(),
            kind = FeedKind.valueOf(this[FeedItemsTable.kind]),
            at = this[FeedItemsTable.at],
            restaurantName = this[FeedItemsTable.restaurantName],
            items = this[FeedItemsTable.items],
            moneyNotSpentCents = this[FeedItemsTable.moneyNotSpentCents],
            level = this[FeedItemsTable.level],
            badge = this[FeedItemsTable.badgeDe]?.let { de -> Text(de, this[FeedItemsTable.badgeEn] ?: de) },
        )
}

class ExposedPlayers(
    private val db: PostgresDatabase,
) : Players {
    override suspend fun find(userId: String): Player? =
        db.transaction(readOnly = true) {
            PlayersTable
                .selectAll()
                .where { PlayersTable.userId eq userId }
                .singleOrNull()
                ?.toPlayer()
        }

    override suspend fun findAll(userIds: Collection<String>): Map<String, Player> {
        if (userIds.isEmpty()) return emptyMap()
        return db.transaction(readOnly = true) {
            PlayersTable
                .selectAll()
                .where { PlayersTable.userId inList userIds }
                .map { it.toPlayer() }
                .associateBy { it.userId }
        }
    }

    override suspend fun save(player: Player) {
        db.transaction {
            PlayersTable.upsert(PlayersTable.userId) {
                it[userId] = player.userId
                it[adjective] = player.pseudonym.adjective
                it[animal] = player.pseudonym.animal
                it[number] = player.pseudonym.number
                it[onLeaderboard] = player.onLeaderboard
            }
        }
    }

    override suspend fun delete(userId: String): Int = db.transaction { PlayersTable.deleteWhere { PlayersTable.userId eq userId } }

    private fun ResultRow.toPlayer() =
        Player(
            userId = this[PlayersTable.userId],
            pseudonym = Pseudonym(this[PlayersTable.adjective], this[PlayersTable.animal], this[PlayersTable.number]),
            onLeaderboard = this[PlayersTable.onLeaderboard],
        )
}
