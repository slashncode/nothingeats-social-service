package io.github.slashncode.nothingeats.social.app

import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun event(
    type: String,
    subject: String,
    data: JsonObject,
) = CloudEvent(
    id = UUID.randomUUID().toString(),
    source = "/test",
    type = type,
    subject = subject,
    time = "2026-11-04T12:00:00Z",
    data = data,
)

private fun Producer<String, ByteArray>.publish(
    topic: String,
    event: CloudEvent,
) {
    send(ProducerRecord(topic, event.subject, EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray())).get()
}

private fun placed(userId: String) =
    event(
        "nothingeats.ordering.order.placed.v1",
        UUID.randomUUID().toString(),
        buildJsonObject {
            put("orderId", UUID.randomUUID().toString())
            put("userId", userId)
            put("placedAt", "2026-11-04T11:59:00Z")
            putJsonObject("restaurant") { put("name", "Phantom Pizza") }
            put("lines", buildJsonArray { add(buildJsonObject { put("quantity", 2) }) })
            put("totalCents", 2379)
        },
    )

private fun reward(
    userId: String,
    xp: Int,
) = event(
    "nothingeats.loyalty.reward.earned.v1",
    userId,
    buildJsonObject {
        put("userId", userId)
        putJsonObject("xp") {
            put("gained", xp)
            put("level", 1)
            put("previousLevel", 1)
        }
    },
)

/** Orders and rewards in; the feed, its live stream and the leaderboard out — through the real composition root. */
class SocialFlowTest :
    FunSpec({
        test("the feed fills and streams live, the leaderboard counts players who joined, deletion erases them") {
            KafkaTestCluster.createTopics("ordering.order.v1", "loyalty.reward.v1", "identity.user.v1", "social-service.dlq")
            val tokens = TestTokens()
            val port = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    valkey = ValkeyTestServer.config(),
                    lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config, tokens.jwtAuth)
            service.migrations.migrate()
            service.lifecycle.start()
            val http = HttpClient.newHttpClient()
            val base = "http://localhost:$port/api/social/v1"
            val ghost = "ghost-${UUID.randomUUID()}"
            val token = tokens.accessToken(userId = ghost)

            fun call(
                method: String,
                path: String,
                body: String? = null,
            ): HttpResponse<String> =
                http.send(
                    HttpRequest
                        .newBuilder(URI("$base$path"))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                        .build(),
                    BodyHandlers.ofString(),
                )

            fun json(response: HttpResponse<String>) = Json.parseToJsonElement(response.body()).jsonObject

            try {
                // The live feed, anonymous: no token.
                val lines = java.util.concurrent.LinkedBlockingQueue<String>()
                val stream: CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> =
                    http.sendAsync(HttpRequest.newBuilder(URI("$base/feed/stream")).GET().build(), BodyHandlers.ofLines())
                stream.thenAccept { response -> Thread { response.body().forEach { lines.put(it) } }.start() }
                eventually(10.seconds) { lines.contains("event: ready") shouldBe true }

                call("PUT", "/me/leaderboard", """{"join":true}""").statusCode() shouldBe 200
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish("ordering.order.v1", placed(ghost))
                    producer.publish("loyalty.reward.v1", reward(ghost, 55))
                    producer.publish("loyalty.reward.v1", reward("someone-else", 999))
                }

                eventually(30.seconds) {
                    val item =
                        json(call("GET", "/feed"))
                            .getValue("items")
                            .jsonArray
                            .single()
                            .jsonObject
                    item.getValue("restaurantName").jsonPrimitive.content shouldBe "Phantom Pizza"
                    item.keys.contains("userId") shouldBe false
                }
                val streamed = generateSequence { lines.poll(10, TimeUnit.SECONDS) }.first { it.startsWith("data: {\"id\"") }
                streamed shouldContain "\"kind\":\"ORDER\""

                eventually(20.seconds) {
                    val board = json(call("GET", "/leaderboard?lang=en"))
                    val top =
                        board
                            .getValue("top")
                            .jsonArray
                            .single()
                            .jsonObject
                    top.getValue("xp").jsonPrimitive.content shouldBe "55"
                    top.getValue("rank").jsonPrimitive.content shouldBe "1"
                }
                json(call("GET", "/me")).getValue("rank").jsonPrimitive.content shouldBe "1"

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "identity.user.v1",
                        event("nothingeats.identity.user.deleted.v1", ghost, buildJsonObject { put("userId", ghost) }),
                    )
                }
                eventually(20.seconds) {
                    service.players.find(ghost) shouldBe null
                    json(call("GET", "/feed")).getValue("items").jsonArray.size shouldBe 0
                    json(call("GET", "/leaderboard")).getValue("top").jsonArray.size shouldBe 0
                }
                stream.cancel(true)
            } finally {
                service.lifecycle.stop()
            }
        }

        test("refuses to serve before migrations ran") {
            val config =
                ServiceConfig(
                    database = PostgresTestDatabases.create(),
                    valkey = ValkeyTestServer.config(),
                    lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config, TestTokens().jwtAuth)

            shouldThrow<IllegalStateException> { service.lifecycle.start() }.message shouldContain "'migrate' mode"
        }
    })
