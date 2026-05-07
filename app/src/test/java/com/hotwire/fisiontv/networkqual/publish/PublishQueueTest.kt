package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.data.PendingPublishDao
import com.hotwire.fisiontv.networkqual.data.PendingPublishEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublishQueueTest {

    private class FakeDao : PendingPublishDao {
        private val rows = MutableStateFlow<List<PendingPublishEntity>>(emptyList())
        override suspend fun upsert(entity: PendingPublishEntity) {
            rows.update { prev -> prev.filterNot { it.certificationId == entity.certificationId } + entity }
        }
        override suspend fun all(): List<PendingPublishEntity> = rows.value
        override fun observeCount(): Flow<Int> = throw UnsupportedOperationException()
        override suspend fun deleteById(id: String) {
            rows.update { prev -> prev.filterNot { it.certificationId == id } }
        }
        override suspend fun recordAttempt(id: String, now: Long, error: String?) {
            rows.update { prev ->
                prev.map {
                    if (it.certificationId == id)
                        it.copy(attemptCount = it.attemptCount + 1, lastAttemptAtMs = now, lastError = error)
                    else it
                }
            }
        }
        override suspend fun deleteExhausted(maxAttempts: Int) {
            rows.update { prev -> prev.filter { it.attemptCount < maxAttempts } }
        }

        fun snapshot() = rows.value
    }

    private fun row(id: String, attempt: Int = 0) = PendingPublishEntity(
        certificationId = id,
        payloadJson = """{"id":"$id"}""",
        deviceId = "dev",
        appVersion = "0.0.0",
        schemaVersion = 1,
        createdAtMs = 1L,
        attemptCount = attempt
    )

    @Test fun `successful drain deletes the row`() = runTest {
        val dao = FakeDao()
        dao.upsert(row("a"))
        val q = PublishQueue(
            dao = dao,
            send = { _, _, _ -> PublishOutcome.Success }
        )
        val drained = q.drain("https://example/v1/certifications")
        assertThat(drained).isEqualTo(1)
        assertThat(dao.snapshot()).isEmpty()
    }

    @Test fun `duplicate counts as success`() = runTest {
        val dao = FakeDao()
        dao.upsert(row("a"))
        val q = PublishQueue(dao = dao, send = { _, _, _ -> PublishOutcome.Duplicate })
        assertThat(q.drain("e")).isEqualTo(1)
        assertThat(dao.snapshot()).isEmpty()
    }

    @Test fun `permanent failure deletes the row but doesn't count as drained`() = runTest {
        val dao = FakeDao()
        dao.upsert(row("a"))
        val q = PublishQueue(
            dao = dao,
            send = { _, _, _ -> PublishOutcome.PermanentFailure(400, "bad request") }
        )
        assertThat(q.drain("e")).isEqualTo(0)
        assertThat(dao.snapshot()).isEmpty()
    }

    @Test fun `transient failure increments attempt and stops draining`() = runTest {
        val dao = FakeDao()
        dao.upsert(row("a"))
        dao.upsert(row("b"))
        val q = PublishQueue(
            dao = dao,
            send = { _, _, _ -> PublishOutcome.TransientFailure("net down") }
        )
        assertThat(q.drain("e")).isEqualTo(0)
        // Both rows still present; first row has attempt=1 (the one we tried),
        // second is untouched because we paused on transient.
        val rows = dao.snapshot().sortedBy { it.certificationId }
        assertThat(rows).hasSize(2)
        assertThat(rows[0].attemptCount).isEqualTo(1)
        assertThat(rows[0].lastError).isEqualTo("net down")
        assertThat(rows[1].attemptCount).isEqualTo(0)
    }

    @Test fun `exhausted rows pruned before drain attempts`() = runTest {
        val dao = FakeDao()
        dao.upsert(row("dead", attempt = 99))
        dao.upsert(row("alive"))
        val q = PublishQueue(
            dao = dao,
            send = { _, _, _ -> PublishOutcome.Success },
            maxAttempts = 8
        )
        assertThat(q.drain("e")).isEqualTo(1)
        // Both gone: dead pruned, alive published.
        assertThat(dao.snapshot()).isEmpty()
    }

    @Test fun `multiple successful rows drain in order until one fails`() = runTest {
        val dao = FakeDao()
        repeat(5) { dao.upsert(row("r$it")) }
        var attempts = 0
        val q = PublishQueue(
            dao = dao,
            send = { _, _, _ ->
                attempts++
                if (attempts < 3) PublishOutcome.Success else PublishOutcome.TransientFailure("dropped")
            }
        )
        assertThat(q.drain("e")).isEqualTo(2)
        // 2 drained, 3 still present (one with attempt=1).
        val remaining = dao.snapshot().sortedBy { it.certificationId }
        assertThat(remaining).hasSize(3)
        assertThat(remaining.first().attemptCount).isEqualTo(1)
    }
}
