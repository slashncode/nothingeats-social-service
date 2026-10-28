package io.github.slashncode.nothingeats.social.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId

class SocialDomainTest :
    FunSpec({
        test("a pseudonym reads in both languages") {
            val pseudonym = Pseudonym(0, 0, 42)

            pseudonym.of(Language.DE) shouldBe "Satter Seehund 42"
            pseudonym.of(Language.EN) shouldBe "Full Seal 42"
        }

        test("weeks are ISO weeks in Hamburg time") {
            val berlin = ZoneId.of("Europe/Berlin")
            // Sunday 23:30 in Berlin is still week 44; Monday 00:30 is week 45.
            Weeks.of(Instant.parse("2026-11-01T22:30:00Z"), berlin) shouldBe "2026-W44"
            Weeks.of(Instant.parse("2026-11-01T23:30:00Z"), berlin) shouldBe "2026-W45"
            Weeks.of(Instant.parse("2027-01-01T12:00:00Z"), berlin) shouldBe "2026-W53"
        }
    })
