package io.github.slashncode.nothingeats.social.adapters.messaging

import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.social.application.EarnedReward
import io.github.slashncode.nothingeats.social.application.ForgetPlayer
import io.github.slashncode.nothingeats.social.application.GoldBadge
import io.github.slashncode.nothingeats.social.application.PlacedOrder
import io.github.slashncode.nothingeats.social.application.PostActivity
import io.github.slashncode.nothingeats.social.domain.Text
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

// Tolerant readers of other services' contracts: only the fields the feed and the board need.

@Serializable
internal data class PlacedRestaurant(
    val name: String,
)

@Serializable
internal data class PlacedLine(
    val quantity: Int,
)

@Serializable
internal data class OrderPlaced(
    val userId: String,
    val placedAt: String,
    val restaurant: PlacedRestaurant,
    val lines: List<PlacedLine>,
    val totalCents: Long,
)

/** `ordering.order.v1` (ordering-service docs/events.md): every placed order is a line in the feed. */
class OrderEventsHandler(
    private val post: PostActivity,
) : EventHandler {
    override val topic = "ordering.order.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != PLACED) return
        val order = event.decode(OrderPlaced.serializer())
        post.order(
            PlacedOrder(
                order.userId,
                order.restaurant.name,
                order.lines.sumOf { it.quantity },
                order.totalCents,
                Instant.parse(order.placedAt),
            ),
        )
    }

    private companion object {
        const val PLACED = "nothingeats.ordering.order.placed.v1"
    }
}

@Serializable
internal data class RewardXp(
    val gained: Long,
    val level: Int,
    val previousLevel: Int,
)

@Serializable
internal data class RewardBadgeName(
    val de: String,
    val en: String,
)

@Serializable
internal data class RewardBadge(
    val tier: String,
    val name: RewardBadgeName,
)

@Serializable
internal data class RewardEarned(
    val userId: String,
    val xp: RewardXp,
    val badges: List<RewardBadge> = emptyList(),
)

/** `loyalty.reward.v1` (loyalty-service docs/events.md): XP for the board, levels and gold badges for the feed. */
class RewardEventsHandler(
    private val post: PostActivity,
) : EventHandler {
    override val topic = "loyalty.reward.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != EARNED) return
        val reward = event.decode(RewardEarned.serializer())
        post.reward(
            EarnedReward(
                userId = reward.userId,
                xpGained = reward.xp.gained,
                level = reward.xp.level,
                previousLevel = reward.xp.previousLevel,
                goldBadges = reward.badges.filter { it.tier == GOLD }.map { GoldBadge(Text(it.name.de, it.name.en)) },
            ),
        )
    }

    private companion object {
        const val EARNED = "nothingeats.loyalty.reward.earned.v1"
        const val GOLD = "GOLD"
    }
}

@Serializable
internal data class UserDeleted(
    val userId: String,
)

/** `user.deleted` (record 030): the player, their feed lines and their places on the boards go. */
class IdentityEventsHandler(
    private val forget: ForgetPlayer,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        if (event.type != DELETED) return
        log.info("Account deleted: {} social rows erased", forget(event.decode(UserDeleted.serializer()).userId))
    }

    private companion object {
        const val DELETED = "nothingeats.identity.user.deleted.v1"
    }
}
