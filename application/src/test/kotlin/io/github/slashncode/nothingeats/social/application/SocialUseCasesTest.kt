package io.github.slashncode.nothingeats.social.application

import io.github.slashncode.nothingeats.social.domain.FeedKind
import io.github.slashncode.nothingeats.social.domain.Language
import io.github.slashncode.nothingeats.social.domain.Text
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

private class Social {
    val feed = InMemoryFeed()
    val broadcast = RecordingBroadcast()
    val players = InMemoryPlayers()
    val leaderboard = InMemoryLeaderboard()
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC)
    val post = PostActivity(feed, broadcast, players, leaderboard, clock)
    val standing = ManageStanding(players, leaderboard, clock, Random(3))
    val board = ShowLeaderboard(players, leaderboard, clock)

    fun reward(
        userId: String,
        xp: Long,
        level: Int = 1,
        previousLevel: Int = 1,
        gold: List<String> = emptyList(),
    ) = EarnedReward(userId, xp, level, previousLevel, gold.map { GoldBadge(Text(it, it)) })
}

class SocialUseCasesTest :
    FunSpec({
        test("orders, levels from 3 on and gold badges go to the feed and out to the streams, anonymously") {
            val social = Social()

            social.post.order(PlacedOrder("u-1", "Phantom Pizza", 2, 2_379, Instant.parse("2026-11-04T11:59:00Z")))
            social.post.reward(social.reward("u-1", 80, level = 2, previousLevel = 1))
            social.post.reward(social.reward("u-1", 90, level = 3, previousLevel = 2, gold = listOf("Weltenbummler")))

            social.broadcast.published.map { it.kind } shouldContainExactly listOf(FeedKind.ORDER, FeedKind.LEVEL, FeedKind.BADGE)
            ShowFeed(social.feed)(10).first().kind shouldBe FeedKind.LEVEL
            social.feed.items
                .first()
                .first.restaurantName shouldBe "Phantom Pizza"
        }

        test("only players who joined collect XP on this week's board, under their pseudonym") {
            val social = Social()
            social.post.reward(social.reward("u-1", 50))
            social.standing.join("u-1")
            social.standing.join("u-2")
            social.post.reward(social.reward("u-1", 60))
            social.post.reward(social.reward("u-2", 90))

            val board = social.board()
            board.week shouldBe "2026-W45"
            board.top.map { it.player.userId to it.xp } shouldContainExactly listOf("u-2" to 90L, "u-1" to 60L)
            social.standing.view("u-1").rank shouldBe 2
            board.top
                .first()
                .player.pseudonym
                .of(Language.DE)
                .split(" ")
                .size shouldBe 3
        }

        test("leaving takes a player off the board at once; joining again keeps the pseudonym") {
            val social = Social()
            val pseudonym =
                social.standing
                    .join("u-1")
                    .player!!
                    .pseudonym
            social.post.reward(social.reward("u-1", 60))

            social.standing
                .leave("u-1")
                .rank
                .shouldBeNull()
            social.board().top shouldBe emptyList()
            social.standing
                .join("u-1")
                .player!!
                .pseudonym shouldBe pseudonym
        }

        test("a deleted account leaves no trace: player, feed lines, board") {
            val social = Social()
            social.standing.join("u-1")
            social.post.order(PlacedOrder("u-1", "Phantom Pizza", 2, 2_379, Instant.parse("2026-11-04T11:59:00Z")))
            social.post.reward(social.reward("u-1", 60))

            ForgetPlayer(social.players, social.feed, social.leaderboard, DirectTransactions(), social.clock)("u-1") shouldBe 2

            social.feed.items shouldBe emptyList()
            social.board().top shouldBe emptyList()
            social.players.find("u-1").shouldBeNull()
        }
    })
