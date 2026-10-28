package io.github.slashncode.nothingeats.social.app

import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import io.github.slashncode.nothingeats.social.adapters.http.SocialRoutes
import io.github.slashncode.nothingeats.social.adapters.http.SseExtension
import io.github.slashncode.nothingeats.social.adapters.messaging.IdentityEventsHandler
import io.github.slashncode.nothingeats.social.adapters.messaging.OrderEventsHandler
import io.github.slashncode.nothingeats.social.adapters.messaging.RewardEventsHandler
import io.github.slashncode.nothingeats.social.adapters.persistence.ExposedFeed
import io.github.slashncode.nothingeats.social.adapters.persistence.ExposedPlayers
import io.github.slashncode.nothingeats.social.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.social.adapters.valkey.ValkeyFeedBroadcast
import io.github.slashncode.nothingeats.social.adapters.valkey.ValkeyLeaderboard
import io.github.slashncode.nothingeats.social.application.Feed
import io.github.slashncode.nothingeats.social.application.FeedBroadcast
import io.github.slashncode.nothingeats.social.application.ForgetPlayer
import io.github.slashncode.nothingeats.social.application.Leaderboard
import io.github.slashncode.nothingeats.social.application.ManageStanding
import io.github.slashncode.nothingeats.social.application.Players
import io.github.slashncode.nothingeats.social.application.PostActivity
import io.github.slashncode.nothingeats.social.application.ShowFeed
import io.github.slashncode.nothingeats.social.application.ShowLeaderboard
import io.github.slashncode.nothingeats.social.application.Transactions
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes, event handlers and cron tasks are collected with
 * `@IntoSet`. [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val players: Players

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @IntoSet
    @Provides
    fun sse(): HttpExtension = SseExtension()

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Valkey: leaderboards (sorted sets) and the feed's fan-out between pods (pub/sub)

    @ServiceScope
    @Provides
    fun valkey(): Valkey = Valkey(config.valkey)

    /** A readiness check: without Valkey there is no leaderboard and no live feed. */
    @IntoSet
    @Provides
    fun valkeyHealth(valkey: Valkey): HealthCheck = valkey

    @IntoSet
    @Provides
    fun valkeyLifecycle(valkey: Valkey): LifecycleComponent = valkey

    @ServiceScope
    @Provides
    fun valkeyPubSub(): ValkeyPubSub = ValkeyPubSub(config.valkey, setOf(ValkeyFeedBroadcast.CHANNEL))

    @IntoSet
    @Provides
    fun valkeyPubSubLifecycle(pubSub: ValkeyPubSub): LifecycleComponent = pubSub

    // Messaging (consumer only: social publishes nothing)

    @ServiceScope
    @Provides
    fun eventConsumer(
        handlers: Set<EventHandler>,
        postgres: PostgresDatabase,
    ): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "social-service"),
            handlers = handlers,
            db = postgres,
            consumerFactory = { KafkaClients.consumer(config.kafka, "social-service") },
            deadLetters = KafkaClients.producer(config.kafka, "social-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Social

    @Provides
    fun feed(postgres: PostgresDatabase): Feed = ExposedFeed(postgres)

    @Provides
    fun players(postgres: PostgresDatabase): Players = ExposedPlayers(postgres)

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @Provides
    fun leaderboard(valkey: Valkey): Leaderboard = ValkeyLeaderboard(valkey)

    @Provides
    fun feedBroadcast(pubSub: ValkeyPubSub): FeedBroadcast = ValkeyFeedBroadcast(pubSub)

    @Provides
    fun postActivity(
        feed: Feed,
        broadcast: FeedBroadcast,
        players: Players,
        leaderboard: Leaderboard,
        clock: Clock,
    ): PostActivity = PostActivity(feed, broadcast, players, leaderboard, clock)

    @IntoSet
    @Provides
    fun socialRoutes(
        feed: Feed,
        players: Players,
        leaderboard: Leaderboard,
        pubSub: ValkeyPubSub,
        clock: Clock,
    ): HttpRoutes =
        SocialRoutes(ShowFeed(feed), ShowLeaderboard(players, leaderboard, clock), ManageStanding(players, leaderboard, clock), pubSub)

    @IntoSet
    @Provides
    fun orderEvents(post: PostActivity): EventHandler = OrderEventsHandler(post)

    @IntoSet
    @Provides
    fun rewardEvents(post: PostActivity): EventHandler = RewardEventsHandler(post)

    @IntoSet
    @Provides
    fun identityEvents(
        players: Players,
        feed: Feed,
        leaderboard: Leaderboard,
        transactions: Transactions,
        clock: Clock,
    ): EventHandler = IdentityEventsHandler(ForgetPlayer(players, feed, leaderboard, transactions, clock))
}
