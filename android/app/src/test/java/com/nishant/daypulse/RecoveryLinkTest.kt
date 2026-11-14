package com.nishant.daypulse

import com.nishant.daypulse.domain.RecoveryInput
import com.nishant.daypulse.domain.parseRecoveryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLinkTest {
    @Test fun verifyLink() {
        val link = "https://abc.supabase.co/auth/v1/verify?token=9f86d081884c&type=recovery&redirect_to=https://x.github.io/app/reset-password"
        assertEquals(RecoveryInput.TokenHash("9f86d081884c"), parseRecoveryInput(link))
    }

    @Test fun redirectedPage() {
        val link = "http://localhost:3000/#access_token=aaa.bbb.ccc&expires_in=3600&refresh_token=r3fr&token_type=bearer&type=recovery"
        assertEquals(RecoveryInput.Tokens("aaa.bbb.ccc", "r3fr"), parseRecoveryInput("  $link\n"))
    }

    @Test fun tokenHashAndCode() {
        assertEquals(RecoveryInput.TokenHash("abc123"), parseRecoveryInput("https://x.dev/reset?token_hash=abc123&type=recovery"))
        assertEquals(RecoveryInput.Code("123456"), parseRecoveryInput("123456"))
    }

    @Test fun errors() {
        val expired = parseRecoveryInput("http://localhost:3000/#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired")
        assertTrue(expired is RecoveryInput.Error && expired.message.contains("expired"))
        assertTrue(parseRecoveryInput("https://abc.supabase.co/auth/v1/verify?token=x&type=signup") is RecoveryInput.Error)
    }

    @Test fun junk() {
        assertNull(parseRecoveryInput(""))
        assertNull(parseRecoveryInput("hello there"))
        assertNull(parseRecoveryInput("https://example.com/nothing"))
    }
}
