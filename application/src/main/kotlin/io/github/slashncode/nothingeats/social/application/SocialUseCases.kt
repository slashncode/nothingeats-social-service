package io.github.slashncode.nothingeats.social.application

import io.github.slashncode.nothingeats.social.domain.FeedItem
import io.github.slashncode.nothingeats.social.domain.FeedKind
import io.github.slashncode.nothingeats.social.domain.Player
import io.github.slashncode.nothingeats.social.domain.Pseudonym
import io.github.slashncode.nothingeats.social.domain.Text
import io.github.slashncode.nothingeats.social.domain.Weeks
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/** The shop's time zone: leaderboard weeks start on Monday in Hamburg. */
val SHOP_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** How many items the feed keeps; it is a ticker, not an archive. */
const val FEED_SIZE = 100

/** What a placed order says about itself, as far as the feed is concerned. */
data class PlacedOrder(
    val userId: String,
    val restaurantName: String,
    val items: Int,
    val moneyNotSpentCents: Long,
    val placedAt: Instant,
)

data class GoldBadge(
    val name: Text,
)

/** What a reward says about itself, as far as the feed and the leaderboard are concerned. */
data class EarnedReward(
    val userId: String,
    val xpGained: Long,
    val level: Int,
    val previousLevel: Int,
    val goldBadges: List<GoldBadge>,
)

/**
 * Feed and leaderboard from other contexts' events. The feed's item is stored and broadcast in
 * the consumer's transaction (a repeated event is stopped by the inbox before it gets here).
 */
class PostActivity(
    private val feed: Feed,
    private val broadcast: FeedBroadcast,
    private val players: Players,
    private val leaderboard: Leaderboard,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun order(order: PlacedOrder) {
        val item =
            FeedItem(
                id = UUID.randomUUID().toString(),
                kind = FeedKind.ORDER,
                at = order.placedAt,
                restaurantName = order.restaurantName,
                items = order.items,
                moneyNotSpentCents = order.moneyNotSpentCents,
            )
        feed.add(item, order.userId, FEED_SIZE)
        broadcast.publish(item)
    }

    suspend fun reward(reward: EarnedReward) {
        val now = clock.instant()
        // Only players who joined the leaderboard collect XP on it.
        if (reward.xpGained > 0 && players.find(reward.userId)?.onLeaderboard == true) {
            leaderboard.add(Weeks.of(now, SHOP_ZONE), reward.userId, reward.xpGained)
        }
        val items =
            buildList {
                if (reward.level > reward.previousLevel && reward.level >= FIRST_LEVEL_WORTH_TELLING) {
                    add(FeedItem(UUID.randomUUID().toString(), FeedKind.LEVEL, now, level = reward.level))
                }
                reward.goldBadges.forEach { add(FeedItem(UUID.randomUUID().toString(), FeedKind.BADGE, now, badge = it.name)) }
            }
        items.forEach {
            feed.add(it, reward.userId, FEED_SIZE)
            broadcast.publish(it)
        }
    }

    private companion object {
        const val FIRST_LEVEL_WORTH_TELLING = 3
    }
}

class ShowFeed(
    private val feed: Feed,
) {
    suspend operator fun invoke(limit: Int): List<FeedItem> = feed.latest(limit.coerceIn(1, MAX_LIMIT))

    private companion object {
        const val MAX_LIMIT = 50
    }
}

data class Standing(
    val player: Player,
    val xp: Long,
    val rank: Int,
)

data class LeaderboardView(
    val week: String,
    val top: List<Standing>,
)

class ShowLeaderboard(
    private val players: Players,
    private val leaderboard: Leaderboard,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(count: Int = TOP): LeaderboardView {
        val week = Weeks.of(clock.instant(), SHOP_ZONE)
        val scores = leaderboard.top(week, count.coerceIn(1, MAX_TOP))
        val known = players.findAll(scores.map { it.userId })
        return LeaderboardView(
            week,
            scores.mapNotNull { score -> known[score.userId]?.takeIf { it.onLeaderboard }?.let { Standing(it, score.xp, score.rank) } },
        )
    }

    private companion object {
        const val TOP = 10
        const val MAX_TOP = 50
    }
}

data class MyStanding(
    val player: Player?,
    val week: String,
    val xp: Long,
    val rank: Int?,
)

/**
 * The signed-in customer's place: joining picks a random pseudonym (kept when leaving and joining
 * again), leaving takes them off this week's board at once.
 */
class ManageStanding(
    private val players: Players,
    private val leaderboard: Leaderboard,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    suspend fun view(userId: String): MyStanding {
        val week = Weeks.of(clock.instant(), SHOP_ZONE)
        val player = players.find(userId)
        val score = player?.takeIf { it.onLeaderboard }?.let { leaderboard.scoreOf(week, userId) }
        return MyStanding(player, week, score?.xp ?: 0, score?.rank)
    }

    suspend fun join(userId: String): MyStanding {
        val existing = players.find(userId)
        players.save(existing?.copy(onLeaderboard = true) ?: Player(userId, randomPseudonym(), onLeaderboard = true))
        return view(userId)
    }

    suspend fun leave(userId: String): MyStanding {
        players.find(userId)?.let { players.save(it.copy(onLeaderboard = false)) }
        leaderboard.remove(listOf(Weeks.of(clock.instant(), SHOP_ZONE)), userId)
        return view(userId)
    }

    private fun randomPseudonym() =
        Pseudonym(
            random.nextInt(Pseudonym.ADJECTIVES.size),
            random.nextInt(Pseudonym.ANIMALS.size),
            random.nextInt(1, Pseudonym.MAX_NUMBER + 1),
        )
}

/** `user.deleted`: the player, their feed lines and their place on recent boards go. */
class ForgetPlayer(
    private val players: Players,
    private val feed: Feed,
    private val leaderboard: Leaderboard,
    private val transactions: Transactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(userId: String): Int {
        val now = clock.instant()
        val weeks = (0L..KEPT_WEEKS).map { Weeks.of(now.minus(it * DAYS_PER_WEEK, ChronoUnit.DAYS), SHOP_ZONE) }
        leaderboard.remove(weeks, userId)
        return transactions.run { players.delete(userId) + feed.deleteByUser(userId) }
    }

    private companion object {
        /** Boards are kept this many weeks (the adapter expires older ones). */
        const val KEPT_WEEKS = 6L
        const val DAYS_PER_WEEK = 7L
    }
}
