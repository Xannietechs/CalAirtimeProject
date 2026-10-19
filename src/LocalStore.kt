package org.jetbrains.amper.ktor

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Operator(val id: Long, val username: String, val role: String) {
    fun json() = buildJsonObject { put("id", id); put("username", username); put("role", role) }
}

/** Local SQLite store. All SQL values are bound; passwords and session tokens are never stored in plaintext. */
class LocalStore(file: File) : AutoCloseable {
    private val random = SecureRandom()
    private val db = run {
        file.absoluteFile.parentFile.mkdirs()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }
    private val attempts = mutableMapOf<String, Pair<Int, Long>>()

    init {
        db.createStatement().use { sql ->
            sql.execute("PRAGMA foreign_keys=ON")
            sql.execute("PRAGMA busy_timeout=5000")
            sql.execute("PRAGMA journal_mode=WAL")
            sql.execute("""CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
                role TEXT NOT NULL CHECK(role IN ('root','user')), active INTEGER NOT NULL DEFAULT 1)""")
            sql.execute("""CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL)""")
            sql.execute("""CREATE TABLE IF NOT EXISTS records (
                id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), created TEXT NOT NULL,
                operation TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, response TEXT NOT NULL DEFAULT '')""")
            sql.execute("CREATE INDEX IF NOT EXISTS records_user ON records(user_id, id)")
        }
    }

    private fun execute(sql: String, vararg values: Any): Int = db.prepareStatement(sql).use { s ->
        values.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
    }
    private fun <T> query(sql: String, vararg values: Any, read: (ResultSet) -> T): T = db.prepareStatement(sql).use { s ->
        values.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeQuery().use(read)
    }
    @Synchronized fun setupRequired(): Boolean = query("SELECT COUNT(*) FROM users") { it.next(); it.getInt(1) == 0 }

    @Synchronized fun setupRoot(password: String): Operator {
        check(setupRequired()) { "Root has already been created. Sign in instead." }
        val hash = passwordHash(password)
        execute("INSERT INTO users(username,password_hash,role) VALUES('root',?,'root')", hash)
        return query("SELECT id, username, role FROM users WHERE username='root'") { it.next(); operator(it) }
    }

    @Synchronized fun login(username: String, password: String): Pair<Operator, String>? {
        val name = username.trim().lowercase()
        require(name.length <= 40 && password.length <= 128) { "Invalid credentials." }
        val now = Instant.now().epochSecond
        attempts.entries.removeIf { now - it.value.second > 900 }
        check((attempts[name]?.first ?: 0) < 5) { "Too many failed attempts. Wait 15 minutes." }
        val account = query("SELECT id,username,role,password_hash,active FROM users WHERE username=?", name) {
            if (it.next()) Triple(operator(it), it.getString("password_hash"), it.getInt("active") == 1) else null
        }
        // Perform the same expensive derivation for unknown usernames.
        val valid = passwordMatches(password, account?.second ?: dummyHash)
        if (account == null || !valid || !account.third) {
            if (attempts.size < 1000 || name in attempts) attempts[name] = ((attempts[name]?.first ?: 0) + 1) to (attempts[name]?.second ?: now)
            return null
        }
        attempts.remove(name)
        execute("DELETE FROM sessions WHERE expires<=?", now)
        val token = randomBytes(32)
        execute("INSERT INTO sessions(token_hash,user_id,expires) VALUES(?,?,?)", tokenHash(token), account.first.id, now + 8 * 3600)
        return account.first to token
    }

    @Synchronized fun authenticate(token: String?): Operator? {
        if (token == null || token.length !in 40..100) return null
        return query("""SELECT u.id,u.username,u.role FROM sessions s JOIN users u ON s.user_id=u.id
            WHERE s.token_hash=? AND s.expires>? AND u.active=1""", tokenHash(token), Instant.now().epochSecond) {
            if (it.next()) operator(it) else null
        }
    }
    @Synchronized fun logout(token: String) { execute("DELETE FROM sessions WHERE token_hash=?", tokenHash(token)) }

    @Synchronized fun createUser(actor: Operator, username: String, password: String): Operator {
        requireRoot(actor)
        val name = username.trim().lowercase()
        require(name.matches(Regex("[a-z][a-z0-9._-]{2,39}"))) { "Username must be 3-40 characters: letters, numbers, dot, underscore or hyphen." }
        require(query("SELECT COUNT(*) FROM users WHERE username=?", name) { it.next(); it.getInt(1) } == 0) { "Username already exists." }
        execute("INSERT INTO users(username,password_hash,role) VALUES(?,?,'user')", name, passwordHash(password))
        val user = query("SELECT id,username,role FROM users WHERE username=?", name) { it.next(); operator(it) }
        event(actor, "user-created", "Created user $name")
        return user
    }

    @Synchronized fun users(actor: Operator): JsonArray {
        requireRoot(actor)
        return query("SELECT id,username,role,active FROM users ORDER BY id") { rs -> buildJsonArray {
            while (rs.next()) add(buildJsonObject {
                operator(rs).json().forEach { (k,v) -> put(k,v) }; put("active", rs.getInt("active") == 1)
            })
        } }
    }
    @Synchronized fun setActive(actor: Operator, id: Long, active: Boolean) {
        requireRoot(actor)
        require(execute("UPDATE users SET active=? WHERE id=? AND role='user'", if (active) 1 else 0, id) == 1) { "Select a user account. Root cannot be disabled." }
        execute("DELETE FROM sessions WHERE user_id=?", id)
        event(actor, "user-status", "User $id active=$active")
    }
    @Synchronized fun resetPassword(actor: Operator, id: Long, password: String) {
        requireRoot(actor)
        require(execute("UPDATE users SET password_hash=? WHERE id=? AND role='user'", passwordHash(password), id) == 1) { "Select a user account." }
        execute("DELETE FROM sessions WHERE user_id=?", id)
        event(actor, "password-reset", "Reset password for user $id")
    }
    @Synchronized fun changePassword(actor: Operator, old: String, password: String) {
        val hash = query("SELECT password_hash FROM users WHERE id=?", actor.id) { it.next(); it.getString(1) }
        require(passwordMatches(old, hash)) { "Current password is incorrect." }
        execute("UPDATE users SET password_hash=? WHERE id=?", passwordHash(password), actor.id)
        execute("DELETE FROM sessions WHERE user_id=?", actor.id)
        event(actor, "password-changed", "Changed own password")
    }
    @Synchronized fun beginRecord(user: Operator, operation: String, request: JsonObject): Long {
        execute("INSERT INTO records(user_id,created,operation,request,state) VALUES(?,?,?,?,'pending')",
            user.id, Instant.now().toString(), operation, request.toString())
        return query("SELECT last_insert_rowid()") { it.next(); it.getLong(1) }
    }
    @Synchronized fun finishRecord(id: Long, state: String, response: String) {
        execute("UPDATE records SET state=?,response=? WHERE id=?", state, response, id)
    }
    private fun event(user: Operator, operation: String, description: String) {
        val id = beginRecord(user, operation, buildJsonObject { put("description", description) })
        finishRecord(id, "success", "{}")
    }
    @Synchronized fun records(user: Operator, before: Long = Long.MAX_VALUE): JsonArray = query(
        """SELECT r.*,u.username FROM records r JOIN users u ON r.user_id=u.id
            WHERE r.id<? AND (?='root' OR r.user_id=?) ORDER BY r.id DESC LIMIT 100""", before, user.role, user.id
    ) { rs -> buildJsonArray { while (rs.next()) add(recordJson(rs, false)) } }

    @Synchronized fun record(user: Operator, id: Long): JsonObject? = query(
        """SELECT r.*,u.username FROM records r JOIN users u ON r.user_id=u.id
            WHERE r.id=? AND (?='root' OR r.user_id=?)""", id, user.role, user.id
    ) { if (it.next()) recordJson(it, true) else null }

    private fun recordJson(rs: ResultSet, detail: Boolean) = buildJsonObject {
        put("id", rs.getLong("id")); put("username", rs.getString("username")); put("created", rs.getString("created"))
        put("operation", rs.getString("operation")); put("state", rs.getString("state"))
        put("request", Json.parseToJsonElement(rs.getString("request")))
        if (detail) put("response", runCatching { Json.parseToJsonElement(rs.getString("response")) }.getOrElse { JsonPrimitive(rs.getString("response")) })
    }
    private fun operator(rs: ResultSet) = Operator(rs.getLong("id"), rs.getString("username"), rs.getString("role"))
    private fun requireRoot(user: Operator) = check(user.role == "root") { "Root access required." }
    private fun randomBytes(count: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(count).also(random::nextBytes))
    private fun tokenHash(token: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    private fun passwordHash(password: String): String {
        require(password.length in 10..128) { "Password must contain 10-128 characters." }
        val salt = randomBytes(16)
        return "$salt:${derive(password, salt)}"
    }
    private fun derive(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 210_000, 256)
        return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    private fun passwordMatches(password: String, encoded: String): Boolean {
        val parts = encoded.split(':')
        return parts.size == 2 && MessageDigest.isEqual(derive(password, parts[0]).toByteArray(), parts[1].toByteArray())
    }
    private val dummyHash by lazy { passwordHash(randomBytes(24)) }
    @Synchronized override fun close() { db.close() }
}

