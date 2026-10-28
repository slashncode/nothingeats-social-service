package io.github.slashncode.nothingeats.social.adapters.http

import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.github.slashncode.nothingeats.social.adapters.valkey.FeedItemJson
import io.github.slashncode.nothingeats.social.adapters.valkey.ValkeyFeedBroadcast
import io.github.slashncode.nothingeats.social.adapters.valkey.json
import io.github.slashncode.nothingeats.social.application.ManageStanding
import io.github.slashncode.nothingeats.social.application.MyStanding
import io.github.slashncode.nothingeats.social.application.ShowFeed
import io.github.slashncode.nothingeats.social.application.ShowLeaderboard
import io.github.slashncode.nothingeats.social.domain.Language
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.acceptLanguage
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class FeedResponse(
    val items: List<FeedItemJson>,
)

@Serializable
data class StandingResponse(
    val rank: Int,
    val pseudonym: String,
    val xp: Long,
)

@Serializable
data class LeaderboardResponse(
    val week: String,
    val top: List<StandingResponse>,
)

@Serializable
data class MeResponse(
    val onLeaderboard: Boolean,
    /** Null until the customer joins the leaderboard once. */
    val pseudonym: String?,
    val week: String,
    val xp: Long,
    val rank: Int?,
)

@Serializable
data class LeaderboardRequest(
    val join: Boolean,
)

/** The server-sent events plugin, installed on the platform's Ktor application. */
class SseExtension : HttpExtension {
    override fun Application.install() {
        install(SSE)
    }
}

/**
 * `/api/social/v1`: the anonymous feed (also live, for everyone) and this week's leaderboard are
 * public; a customer's own place and joining or leaving the board need a session. Pseudonyms come
 * in `?lang=` or Accept-Language.
 */
class SocialRoutes(
    private val feed: ShowFeed,
    private val leaderboard: ShowLeaderboard,
    private val standing: ManageStanding,
    private val pubSub: ValkeyPubSub,
    private val heartbeat: Duration = 15.seconds,
) : HttpRoutes {
    override fun Route.register() {
        route("/api/social/v1") {
            public {
                get("/feed") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_FEED
                    call.respond(FeedResponse(feed(limit).map { it.json() }))
                }
                sse("/feed/stream") {
                    val items =
                        pubSub.messages
                            .onSubscription { send(ServerSentEvent(event = "ready", data = "{}", retry = RETRY_MILLIS)) }
                            .filter { it.channel == ValkeyFeedBroadcast.CHANNEL }
                            .map { ServerSentEvent(event = "feed.item", data = it.message) }
                    val pings =
                        flow {
                            while (true) {
                                delay(heartbeat)
                                emit(ServerSentEvent(comments = "ping"))
                            }
                        }
                    merge(items, pings).collect { send(it) }
                }
                get("/leaderboard") {
                    val language = call.language()
                    val view = leaderboard()
                    call.respond(
                        LeaderboardResponse(
                            view.week,
                            view.top.map { StandingResponse(it.rank, it.player.pseudonym.of(language), it.xp) },
                        ),
                    )
                }
            }
            authenticated {
                get("/me") { call.respond(standing.view(call.user.id).response(call.language())) }
                put("/me/leaderboard") {
                    val join = call.receive<LeaderboardRequest>().join
                    val updated = if (join) standing.join(call.user.id) else standing.leave(call.user.id)
                    call.respond(updated.response(call.language()))
                }
            }
        }
    }

    private fun ApplicationCall.language(): Language {
        val language = Language.parse(request.queryParameters["lang"] ?: request.acceptLanguage())
        response.header(HttpHeaders.ContentLanguage, language.tag)
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        return language
    }

    private fun MyStanding.response(language: Language) =
        MeResponse(player?.onLeaderboard == true, player?.pseudonym?.of(language), week, xp, rank)

    private companion object {
        const val DEFAULT_FEED = 20
        const val RETRY_MILLIS = 3_000L
    }
}
