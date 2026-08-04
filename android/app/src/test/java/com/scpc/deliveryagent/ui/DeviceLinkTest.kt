package com.scpc.deliveryagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device connection shown beside the synthetic state.
 *
 * Only the mapping is covered here — reading `ConnectivityManager` needs a
 * device. That split is the point: the part that decides what a judge reads is
 * ordinary Kotlin, so it is pinned without an emulator.
 */
class DeviceLinkTest {

    @Test
    fun `no active network reads as disconnected`() {
        assertEquals(
            "비행기 모드처럼 활성 network이 없으면 끊김으로 말한다",
            DeviceLink.DISCONNECTED,
            deviceLinkOf(hasActiveNetwork = false, hasInternetCapability = false),
        )
    }

    @Test
    fun `an active network without internet is not reported as connected`() {
        assertEquals(
            DeviceLink.NO_INTERNET,
            deviceLinkOf(hasActiveNetwork = true, hasInternetCapability = false),
        )
    }

    @Test
    fun `an active network carrying internet reads as connected`() {
        assertEquals(
            DeviceLink.CONNECTED,
            deviceLinkOf(hasActiveNetwork = true, hasInternetCapability = true),
        )
    }

    @Test
    fun `only a working connection counts as connected`() {
        assertTrue(DeviceLink.CONNECTED.isConnected)
        listOf(DeviceLink.NO_INTERNET, DeviceLink.DISCONNECTED, DeviceLink.UNREADABLE)
            .forEach { assertFalse("${it.name}은 연결로 취급하지 않는다", it.isConnected) }
    }

    @Test
    fun `every state has a label a person can read`() {
        DeviceLink.entries.forEach { link ->
            assertTrue("${link.name}에 사람이 읽을 라벨이 있다", link.label.isNotBlank())
            assertFalse(
                "${link.name}의 라벨이 내부 상수를 그대로 노출하지 않는다",
                link.label.contains(link.name),
            )
        }
    }
}
