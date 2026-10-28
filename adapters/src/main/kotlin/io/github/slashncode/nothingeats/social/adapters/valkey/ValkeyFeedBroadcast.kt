package io.github.slashncode.nothingeats.social.adapters.valkey

import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.github.slashncode.nothingeats.social.application.FeedBroadcast
import io.github.slashncode.nothingeats.social.domain.FeedItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A feed item as the stream and `GET /feed` show it; texts in both languages, the browser picks. */
@Serializable
data class FeedItemJson(
    val id: String,
    val kind: String,
    val at: String,
    val restaurantName: String? = null,
    val items: Int? = null,
    val moneyNotSpentCents: Long? = null,
    val level: Int? = null,
    val badge: BadgeNameJson? = null,
)

@Serializable
data class BadgeNameJson(
    val de: String,
    val en: String,
)

fun FeedItem.json() =
    FeedItemJson(
        id = id,
        kind = kind.name,
        at = at.toString(),
        restaurantName = restaurantName,
        items = items,
        moneyNotSpentCents = moneyNotSpentCents,
        level = level,
        badge = badge?.let { BadgeNameJson(it.de, it.en) },
    )

internal val FeedJson = Json { explicitNulls = false }

/** Every pod subscribes to the feed channel and forwards to the streams it holds. */
class ValkeyFeedBroadcast(
    private val pubSub: ValkeyPubSub,
) : FeedBroadcast {
    override suspend fun publish(item: FeedItem) {
        pubSub.publish(CHANNEL, FeedJson.encodeToString(FeedItemJson.serializer(), item.json()))
    }

    companion object {
        const val CHANNEL = "social:feed"
    }
}
