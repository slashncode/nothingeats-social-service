package io.github.slashncode.nothingeats.social.domain

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts `de`, `en-GB` or an Accept-Language header; anything unknown is German. */
        fun parse(raw: String?): Language {
            val first =
                raw
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.take(2)
            return entries.firstOrNull { it.tag == first } ?: DE
        }
    }
}

data class Text(
    val de: String,
    val en: String,
) {
    fun of(language: Language) =
        when (language) {
            Language.DE -> de
            Language.EN -> en
        }
}

enum class FeedKind {
    /** Someone ordered nothing somewhere. */
    ORDER,

    /** Someone reached a level. */
    LEVEL,

    /** Someone earned a gold badge. */
    BADGE,
}

/**
 * One line of the live feed. Anonymous by construction: no name, no place, no account; only what
 * happened and where it was ordered from. (The feed keeps the user id internally, to erase it.)
 */
data class FeedItem(
    val id: String,
    val kind: FeedKind,
    val at: Instant,
    val restaurantName: String? = null,
    val items: Int? = null,
    val moneyNotSpentCents: Long? = null,
    val level: Int? = null,
    val badge: Text? = null,
)

/**
 * The name a player shows on the leaderboard: "Satter Seehund 42" / "Full Seal 42", never derived
 * from the account. Chosen at random when someone joins; the indexes keep it in both languages.
 */
data class Pseudonym(
    val adjective: Int,
    val animal: Int,
    val number: Int,
) {
    init {
        require(adjective in ADJECTIVES.indices && animal in ANIMALS.indices && number in 1..MAX_NUMBER) { "not a pseudonym" }
    }

    fun of(language: Language) = "${ADJECTIVES[adjective].of(language)} ${ANIMALS[animal].of(language)} $number"

    companion object {
        const val MAX_NUMBER = 99

        // Masculine animals only, so the German adjective endings fit every pair.
        val ADJECTIVES =
            listOf(
                Text("Satter", "Full"),
                Text("Hungriger", "Hungry"),
                Text("Leichter", "Light"),
                Text("Schneller", "Quick"),
                Text("Stiller", "Quiet"),
                Text("Kluger", "Clever"),
                Text("Tapferer", "Brave"),
                Text("Gelassener", "Calm"),
                Text("Neugieriger", "Curious"),
                Text("Sparsamer", "Thrifty"),
                Text("Wacher", "Alert"),
                Text("Fröhlicher", "Cheerful"),
            )
        val ANIMALS =
            listOf(
                Text("Seehund", "Seal"),
                Text("Hering", "Herring"),
                Text("Kranich", "Crane"),
                Text("Igel", "Hedgehog"),
                Text("Fuchs", "Fox"),
                Text("Otter", "Otter"),
                Text("Biber", "Beaver"),
                Text("Dachs", "Badger"),
                Text("Pinguin", "Penguin"),
                Text("Wal", "Whale"),
                Text("Hummer", "Lobster"),
                Text("Kormoran", "Cormorant"),
            )
    }
}

/** A customer as the social context knows them: a pseudonym and whether they show on the leaderboard. */
data class Player(
    val userId: String,
    val pseudonym: Pseudonym,
    val onLeaderboard: Boolean,
)

/** Leaderboards run per ISO week in the shop's time zone, Monday to Sunday. */
object Weeks {
    fun of(
        time: Instant,
        zone: ZoneId,
    ): String {
        val date = time.atZone(zone).toLocalDate()
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}
