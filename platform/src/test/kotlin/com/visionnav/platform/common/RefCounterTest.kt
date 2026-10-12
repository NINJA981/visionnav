package com.visionnav.platform.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefCounterTest {
    private var opened = 0
    private var closed = 0
    private val counter = RefCounter(onFirstAcquire = { opened++ }, onLastRelease = { closed++ })

    @Test
    fun `multiple holders share one open and one close`() {
        counter.acquire()
        counter.acquire()
        counter.acquire()
        assertThat(opened).isEqualTo(1)
        counter.release()
        counter.release()
        assertThat(closed).isEqualTo(0)
        counter.release()
        assertThat(closed).isEqualTo(1)
        assertThat(counter.isActive).isFalse()
    }

    @Test
    fun `unbalanced release is ignored and later acquire reopens`() {
        assertThat(counter.release()).isFalse()
        assertThat(closed).isEqualTo(0)
        counter.acquire()
        counter.release()
        assertThat(counter.release()).isFalse()
        counter.acquire()
        assertThat(opened).isEqualTo(2)
        assertThat(closed).isEqualTo(1)
        assertThat(counter.count).isEqualTo(1)
    }
}
