package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class LocalContractTest {
    @Test
    fun `both named LAN tiers produce fitting grouped contracts and validate a partial edit`() {
        for (tier in listOf(CapabilityLevel.Simple, CapabilityLevel.Intermediate)) {
            val c =
                TurnContext(
                    MotionMode.Layered,
                    tier,
                    AppLanguage.English,
                    false,
                    true,
                    false,
                    LayeredScore(),
                    10,
                    80,
                    local = true,
                )
            val req =
                requireNotNull(
                    ChatContract.compose(
                        c,
                        listOf(TextMessage(TextRole.User, "Move to a different range.")),
                        if (tier == CapabilityLevel.Simple) 16384 else 32768,
                    )
                )
            val proposal =
                validateProposal(
                    """{"action":"update","edits":[{"range":{"min_percent":17,"max_percent":69}},{"geometry":"full_band"},{"controls":{"speed_percent":32}}],"reply":"Changed."}""",
                    c,
                    req,
                )
            val score = assertIs<LayeredScore>(proposal.score)
            assertEquals(17, score.low)
            assertEquals(69, score.high)
            assertEquals(32, score.speed)
            assertEquals(52, score.floor)
        }
    }
}
