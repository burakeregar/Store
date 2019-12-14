/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.flow.multicast

import com.dropbox.flow.multicast.ChannelManager.Message.Dispatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.lang.Exception

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ChannelManagerTest {
    private val scope = TestCoroutineScope()
    private val upstream: Channel<String> = Channel(Channel.UNLIMITED)
    private val manager = ChannelManager(
        scope,
        0,
        onEach = {},
        upstream = upstream.consumeAsFlow()
    )

    @Test
    fun `Given one downstream WHEN two values come in on the upstream THEN two values are consumed`() =
        scope.runBlockingTest {
            val collection = async {
                val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
                try {
                    manager.addDownstream(downstream)
                    downstream.consumeAsFlow()
                        .onEach { it.markDelivered() }
                        .take(2)
                        .toList()
                        .map { it.value }
                } finally {
                    manager.removeDownstream(downstream)
                }
            }
            try {
                upstream.send("a")
                upstream.send("b")
                upstream.close()
            } catch (e: Throwable) {
            }
            assertThat(collection.await()).isEqualTo(listOf("a", "b"))
        }

    @Test(expected = TestException::class)
    fun `Given one downstream WHEN upstream errors THEN error is propagated`() =
        scope.runBlockingTest {
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)

            val collection = async {
                try {
                    downstream.consumeAsFlow()
                        .onEach { it.markDelivered() }
                        .take(2)
                        .toList()
                        .map { it.value }
                } finally {
                    manager.removeDownstream(downstream)
                }
            }
            try {
                upstream.close(TestException())
            } catch (e: Throwable) {
            }
            collection.await()
        }

    @Test
    fun `Given one downstream WHEN upstream closes THEN downstream is closed`() =
        scope.runBlockingTest {
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)

            val collection = async {
                try {
                    downstream.consumeAsFlow()
                        .onEach { it.markDelivered() }
                        .toList()
                        .map { it.value }
                } finally {
                    manager.removeDownstream(downstream)
                }
            }
            try {
                upstream.close()
            } catch (e: Throwable) {
            }
            delay(100) // give the upstream a chance to finish.
            assertThat(collection.isCompleted).isTrue()
            assertThat(collection.getCompleted()).isEmpty()
        }

    @Test
    fun `Given two downstreams WHEN two values come in on the upstream THEN two values are consumed`() =
        scope.runBlockingTest {
            val downstream1 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            val downstream2 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)

            // ack on channel 1
            val collection1 = async {
                manager.addDownstream(downstream1)
                manager.addDownstream(downstream2)
                try {
                    downstream1.consumeAsFlow()
                        .onEach { it.markDelivered() }
                        .take(2)
                        .toList()
                        .map { it.value }
                } finally {
                    manager.removeDownstream(downstream1)
                }
            }

            // also consume (without ack) on channel 2 to make sure we got everything.
            val collection2 = async {
                try {
                    downstream2.consumeAsFlow()
                        .take(2)
                        .toList()
                        .map { it.value }
                } finally {
                    manager.removeDownstream(downstream2)
                }
            }

            try {
                upstream.send("a")
                upstream.send("b")
                upstream.close()
            } catch (e: Throwable) {
            }
            assertThat(collection1.await()).isEqualTo(listOf("a", "b"))
            assertThat(collection2.await()).isEqualTo(listOf("a", "b"))
        }

    @Test
    fun `GIVEN no keepUpstreamAlive WHEN a add two non overlapping downstreams THEN registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams and count upstream creations`(
                    keepUpstreamAlive = false
                )
            assertThat(upstreamCreateCount).isEqualTo(2)
        }

    @Test
    fun `GIVEN keepUpstreamAlive WHEN a add two non overlapping downstreams THEN registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams and count upstream creations`(
                    keepUpstreamAlive = true
                )
            assertThat(upstreamCreateCount).isEqualTo(1)
        }

    private suspend fun `consume two non-overlapping downstreams and count upstream creations`(
        keepUpstreamAlive: Boolean
    ) = coroutineScope {
        // upstream that tracks creates and can be emitted to on demand
        var upstreamCreateCount = 0
        val upstreamChannel = Channel<String>(Channel.UNLIMITED)
        val upstream = flow {
            upstreamCreateCount++
            for (message in upstreamChannel) {
                emit(message)
            }
        }

        // create a manager with this specific upstream
        val manager = ChannelManager(
            scope,
            0,
            onEach = {},
            keepUpstreamAlive = keepUpstreamAlive,
            upstream = upstream
        )

        // subscribe with fist downstream
        val downstream1 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream1)
        val s1 = async {
            try {
                downstream1.consumeAsFlow()
                    .first().let {
                        it.markDelivered()
                        it.value
                    }
            } finally {
                manager.removeDownstream(downstream1)
            }
        }

        // get value and make sure first downstream is closed
        upstreamChannel.send("a")
        assertThat(s1.await()).isEqualTo("a")
        assertThat(downstream1.isClosedForReceive)

        // add second downstream
        val downstream2 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream2)
        val s2 = async {
            try {
                downstream2.consumeAsFlow()
                    .first().let {
                        it.markDelivered()
                        it.value
                    }
            } finally {
                manager.removeDownstream(downstream2)
            }
        }

        // get second value
        upstreamChannel.send("b")
        assertThat(s2.await()).isEqualTo("b")

        return@coroutineScope upstreamCreateCount
    }
}

private class TestException : Exception()
