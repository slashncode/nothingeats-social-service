package io.github.slashncode.nothingeats.social.adapters.valkey

import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.social.application.Leaderboard
import io.github.slashncode.nothingeats.social.application.Score
import io.lettuce.core.ZRange
import kotlinx.coroutines.future.await
import java.time.Duration

/**
 * One sorted set per week (`social:leaderboard:2026-W45`), member = user id, score = XP. Sorted
 * sets do the ranking; each board expires a few weeks after it was last written.
 */
class ValkeyLeaderboard(
    private val valkey: Valkey,
    private val keep: Duration = Duration.ofDays(KEEP_DAYS),
) : Leaderboard {
    private fun key(week: String) = "$PREFIX$week"

    override suspend fun add(
        week: String,
        userId: String,
        xp: Long,
    ) {
        val commands = valkey.commands()
        commands.zincrby(key(week), xp.toDouble(), userId).await()
        commands.expire(key(week), keep.seconds).await()
    }

    override suspend fun top(
        week: String,
        count: Int,
    ): List<Score> =
        valkey
            .commands()
            .zrangeWithScores(key(week), ZRange.byIndex(0, (count - 1).toLong()).rev())
            .await()
            .mapIndexed { index, entry -> Score(entry.value, entry.score.toLong(), index + 1) }

    override suspend fun scoreOf(
        week: String,
        userId: String,
    ): Score? {
        val commands = valkey.commands()
        val score = commands.zscore(key(week), userId).await() ?: return null
        val rank = commands.zrevrank(key(week), userId).await() ?: return null
        return Score(userId, score.toLong(), rank.toInt() + 1)
    }

    override suspend fun remove(
        weeks: Collection<String>,
        userId: String,
    ) {
        val commands = valkey.commands()
        weeks.forEach { commands.zrem(key(it), userId).await() }
    }

    private companion object {
        const val PREFIX = "social:leaderboard:"
        const val KEEP_DAYS = 42L
    }
}
