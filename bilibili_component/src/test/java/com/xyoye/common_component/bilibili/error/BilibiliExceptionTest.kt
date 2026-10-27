package com.xyoye.common_component.bilibili.error

import org.junit.Assert.*
import org.junit.Test

class BilibiliExceptionTest {
    @Test fun structuredVerificationNeverIncludesVoucherInThrowableOrMessageFields() {
        val failure = BilibiliException.from(-352, "v_voucher=private-voucher", "private-voucher")
        assertEquals("private-voucher", failure.verificationVoucher)
        assertFalse(failure.message.orEmpty().contains("private-voucher"))
        assertFalse(failure.bilibiliMessage.orEmpty().contains("private-voucher"))
        assertFalse(failure.stackTraceToString().contains("private-voucher"))
        assertFalse(failure.toString().contains("private-voucher"))
    }
}
