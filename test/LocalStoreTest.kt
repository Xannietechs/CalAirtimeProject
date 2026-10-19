package org.jetbrains.amper.ktor

import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class LocalStoreTest {
    private fun withStore(test: (LocalStore, File) -> Unit) {
        val file = File.createTempFile("local-store-", ".db")
        val store = LocalStore(file)
        try { test(store, file) } finally { store.close(); file.delete() }
    }
    @Test fun setupIsOneTimeAndPasswordsAreVerified() = withStore { store, _ ->
        assertTrue(store.setupRequired())
        assertFailsWith<IllegalArgumentException> { store.setupRoot("short") }
        val root = store.setupRoot("root-test-password")
        assertFalse(store.setupRequired())
        assertEquals("root", root.role)
        assertFailsWith<IllegalStateException> { store.setupRoot("different-password") }
        assertNull(store.login("root", "incorrect-password"))
        val token = store.login("root", "root-test-password")!!.second
        assertEquals(root, store.authenticate(token))
        assertNull(store.authenticate("invented-token"))
        store.logout(token)
        assertNull(store.authenticate(token))
    }
    @Test fun userRecordsAreIsolatedAndSurviveReopen() {
        val file = File.createTempFile("persist-records-", ".db")
        try {
            lateinit var alice: Operator
            lateinit var bob: Operator
            lateinit var root: Operator
            var id = 0L
            LocalStore(file).use { store ->
                root = store.setupRoot("root-test-password")
                alice = store.createUser(root, "alice", "alice-test-password")
                bob = store.createUser(root, "bob", "bob-test-password")
                id = store.beginRecord(alice, "voucher-vend", buildJsonObject { put("amount", "10") })
                store.finishRecord(id, "success", """{"voucher":[{"pin":"test-pin"}]}""")
                assertNull(store.record(bob, id))
                assertEquals(1, store.records(alice).size)
                assertEquals(0, store.records(bob).size)
                assertTrue(store.records(root).size >= 3)
                assertFailsWith<IllegalStateException> { store.createUser(alice, "mallory", "another-password") }
            }
            LocalStore(file).use { store ->
                assertFalse(store.setupRequired())
                assertTrue(store.record(alice, id)!!["response"]!!.toString().contains("test-pin"))
                assertNull(store.record(bob, id))
                assertNotNull(store.login("alice", "alice-test-password"))
            }
        } finally { file.delete() }
    }
    @Test fun disablingAndPasswordResetRevokeSessions() = withStore { store, _ ->
        val root = store.setupRoot("root-test-password")
        val alice = store.createUser(root, "alice", "alice-test-password")
        val token = store.login("alice", "alice-test-password")!!.second
        store.setActive(root, alice.id, false)
        assertNull(store.authenticate(token))
        assertNull(store.login("alice", "alice-test-password"))
        store.setActive(root, alice.id, true)
        val second = store.login("alice", "alice-test-password")!!.second
        store.resetPassword(root, alice.id, "replacement-password")
        assertNull(store.authenticate(second))
        assertNull(store.login("alice", "alice-test-password"))
        val third = store.login("alice", "replacement-password")!!.second
        store.changePassword(alice, "replacement-password", "new-own-password")
        assertNull(store.authenticate(third))
        assertNotNull(store.login("alice", "new-own-password"))
        assertFailsWith<IllegalArgumentException> { store.setActive(root, root.id, false) }
    }
    @Test fun failedLoginAttemptsAreLimited() = withStore { store, _ ->
        store.setupRoot("root-test-password")
        repeat(5) { assertNull(store.login("root", "incorrect-password")) }
        assertFailsWith<IllegalStateException> { store.login("root", "root-test-password") }
    }
}
