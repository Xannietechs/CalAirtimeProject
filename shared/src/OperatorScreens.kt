package com.calairtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal class PortalApi(val baseUrl: String, val token: String = "") : AutoCloseable {
    private val client = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 70_000 } }
    suspend fun request(path: String, body: JsonObject? = null): JsonElement {
        val response = client.request("${baseUrl.trimEnd('/')}/$path") {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            if (token.isNotBlank()) bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        val json = Json.parseToJsonElement(response.bodyAsText())
        if (!response.status.isSuccess()) {
            val message = (json as? JsonObject)?.get("status")?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(message ?: "Request failed (${response.status.value}).")
        }
        return json
    }
    override fun close() { client.close() }
}

@Composable
fun App() {
    var base by remember { mutableStateOf("http://localhost:8080") }
    var token by remember { mutableStateOf("") }
    var user by remember { mutableStateOf<JsonObject?>(null) }
    if (user == null) {
        CalTheme { LoginScreen(base, { base = it }) { account, session -> user = account; token = session } }
    } else {
        key(token) {
            MerchantPortal(base, token, user!!["username"]!!.jsonPrimitive.content,
                user!!["role"]!!.jsonPrimitive.content == "root") { user = null; token = "" }
        }
    }
}

@Composable
private fun LoginScreen(base: String, onBase: (String) -> Unit, onLogin: (JsonObject, String) -> Unit) {
    val api = remember(base) { PortalApi(base) }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var setup by remember(base) { mutableStateOf<Boolean?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    var serverSettings by remember { mutableStateOf(false) }
    var draftBase by remember(base) { mutableStateOf(base) }
    suspend fun checkServer() {
        connecting = true
        try { setup = api.request("auth/status").jsonObject["setupRequired"]!!.jsonPrimitive.boolean; message = "" }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { setup = null; success = false; message = "We couldn't reach the local server. Make sure it is running, then reconnect." }
        finally { connecting = false }
    }
    LaunchedEffect(api) { checkServer() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val wide = maxWidth >= 850.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Column(Modifier.weight(1f).fillMaxHeight().background(Ink).padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Brand(true)
                Spacer(Modifier.weight(1f))
                Text("A simpler way\nto serve every\ncustomer.", fontSize = 44.sp, lineHeight = 53.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Airtime. Electricity. Every receipt.\nTogether in one clear workspace.", fontSize = 18.sp, lineHeight = 28.sp, color = Color(0xFFADC7D6))
                HorizontalDivider(color = Color(0xFF345165))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column { Text("01", color = Color(0xFF70DFCD), fontWeight = FontWeight.Bold); Text("Make a sale", color = Color.White, fontSize = 13.sp) }
                    Column { Text("02", color = Color(0xFF70DFCD), fontWeight = FontWeight.Bold); Text("Keep a record", color = Color.White, fontSize = 13.sp) }
                    Column { Text("03", color = Color(0xFF70DFCD), fontWeight = FontWeight.Bold); Text("Stay in control", color = Color.White, fontSize = 13.sp) }
                }
                Spacer(Modifier.weight(1f))
                Text("CALAIRTIME  /  MERCHANT WORKSPACE", color = Color(0xFF8CA7B8), fontSize = 11.sp, letterSpacing = 1.sp)
            }
            Box(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (wide) 36.dp else 20.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 470.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (!wide) Brand()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(if (connecting) "Connecting" else if (setup != null) "Ready" else "Offline")
                        Text(if (setup == true) "Set up your workspace" else "Welcome back", style = MaterialTheme.typography.headlineLarge)
                        Text(if (setup == true) "Create the root account to manage your team and local records." else "Sign in to your merchant account to get started.", color = Muted)
                    }
                    Panel {
                        if (connecting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Connecting to your local server...", color = Muted)
                        } else if (setup != null) {
                            if (setup == true) Notice("Your administrator account", "The username is root. Choose a password with at least 10 characters.")
                            else Field(username, { username = it }, "Username", enabled = !busy)
                            Field(password, { password = it }, if (setup == true) "Choose root password" else "Password", if (setup == true) "Use at least 10 characters." else "", !busy, password = true)
                            if (setup == true) Field(confirm, { confirm = it }, "Confirm password", hint = if (confirm.isNotBlank() && confirm != password) "Passwords do not match." else "", enabled = !busy, password = true, error = confirm.isNotBlank() && confirm != password)
                            Action(if (busy) "Please wait..." else if (setup == true) "Create root account" else "Sign in", !busy && (if (setup == true) password.length in 10..128 && password == confirm else username.isNotBlank() && password.isNotBlank())) {
                                scope.launch {
                                    busy = true; message = ""; success = false
                                    try {
                                        if (setup == true) {
                                            api.request("auth/setup", buildJsonObject { put("password", password) })
                                            username = "root"; setup = false; password = ""; confirm = ""; success = true
                                            message = "Workspace ready. Sign in as root with the password you just chose."
                                        } else {
                                            val result = api.request("auth/login", buildJsonObject { put("username", username); put("password", password) }).jsonObject
                                            password = ""; confirm = ""
                                            onLogin(result["user"]!!.jsonObject, result["token"]!!.jsonPrimitive.content)
                                        }
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Throwable) { message = e.message ?: "Could not sign in. Please try again." }
                                    finally { busy = false }
                                }
                            }
                        } else {
                            Notice("Local server unavailable", "Start the backend on this computer, then select Reconnect.", true)
                            Action("Reconnect", !busy) { scope.launch { checkServer() } }
                        }
                        if (message.isNotBlank()) Notice(if (success) "You're all set" else "Please check", message, !success)
                    }
                    Text("Your account is separate from the merchant API credentials. Need access? Ask your root administrator.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !busy && !connecting, onClick = { serverSettings = !serverSettings }) { Text(if (serverSettings) "Hide server settings" else "Local server settings") }
                    if (serverSettings) Panel("Server settings", "Usually, the default address is all you need.") {
                        Field(draftBase, { draftBase = it }, "Local server URL", enabled = !busy)
                        OutlinedButton(enabled = !busy, onClick = { if (draftBase != base) onBase(draftBase) else scope.launch { checkServer() } }) { Text("Save & reconnect") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalRecordsScreen(base: String, token: String, root: Boolean) {
    val api = remember(base, token) { PortalApi(base, token) }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(JsonArray(emptyList())) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var end by remember { mutableStateOf(false) }
    suspend fun load(older: Boolean) {
        busy = true; message = ""
        try {
            val before = if (older) records.lastOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content else null
            val page = api.request("records" + (before?.let { "?before=$it" } ?: "")).jsonArray
            records = if (older) JsonArray(records + page) else page
            end = page.size < 100
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { message = e.message ?: "Could not load records." }
        finally { busy = false }
    }
    LaunchedEffect(api) { load(false) }
    PageHeading(if (root) "Saved records" else "My saved records", if (root) "A clear view of your team's local transactions and receipts." else "Your transactions and saved receipts, all in one place.")
    Panel {
        Field(search, { search = it }, "Search records", "Search loaded records by reference, operator, service or request details.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Success", "Needs review", "Failed", "Rejected").forEach { state -> FilterChip(selected = filter == state, onClick = { filter = state }, label = { Text(state) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${records.size} records loaded", color = Muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(enabled = !busy, onClick = { scope.launch { load(false) } }) { Text("Refresh records") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Notice("Records unavailable", message, true)
        val visible = records.filter { item ->
            val row = item.jsonObject
            val state = row["state"]!!.jsonPrimitive.content
            val match = when (filter) { "All" -> true; "Needs review" -> state in setOf("unknown", "pending"); else -> state == filter.lowercase() }
            match && item.toString().contains(search, ignoreCase = true)
        }
        if (visible.isEmpty() && !busy && message.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (records.isEmpty()) "No transactions yet" else "No matching records", style = MaterialTheme.typography.titleLarge)
                Text(if (records.isEmpty()) "Your sales and receipts will appear here." else "Try another search or status filter.", color = Muted)
            }
        }
        visible.forEach { element ->
            val row = element.jsonObject
            Surface(Modifier.fillMaxWidth(), color = Canvas, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Record #${row["id"]!!.jsonPrimitive.content}", fontWeight = FontWeight.SemiBold)
                        StatusPill(row["state"]!!.jsonPrimitive.content)
                    }
                    Text(serviceName(row["operation"]!!.jsonPrimitive.content), style = MaterialTheme.typography.titleMedium)
                    Text("${row["username"]!!.jsonPrimitive.content}  /  ${readableTime(row["created"]!!.jsonPrimitive.content)}", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        try { detail = api.request("records/${row["id"]!!.jsonPrimitive.content}").jsonObject }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { message = e.message ?: "Could not load receipt." }
                        finally { busy = false }
                    } }) { Text("View record & receipt") }
                }
            }
        }
        if (!end && records.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { scope.launch { load(true) } }) { Text("Load older records") }
        Text("Times shown in UTC. Viewing saved records does not contact the provider or use a new sequence.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
    detail?.let { row -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("Record #${row["id"]!!.jsonPrimitive.content}") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusPill(row["state"]!!.jsonPrimitive.content)
            LabelValue("Operator", row["username"]!!.jsonPrimitive.content)
            LabelValue("Time (UTC)", readableTime(row["created"]!!.jsonPrimitive.content).removeSuffix(" UTC"))
            LabelValue("Service", serviceName(row["operation"]!!.jsonPrimitive.content))
            if (row["state"]!!.jsonPrimitive.content in setOf("pending", "unknown")) Notice("Confirm the outcome first", "Ask the API administrator to reconcile this attempt before making another purchase.", true)
            Text("Request details", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { DataFields(row["request"] ?: JsonObject(emptyMap())) } }
            HorizontalDivider(color = Line)
            Text("Saved response / receipt", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { DataFields(row["response"] ?: JsonPrimitive("No response available.")) } }
        } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close record") } }) }
}

@Composable
internal fun UsersScreen(base: String, token: String) {
    val api = remember(base, token) { PortalApi(base, token) }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf(JsonArray(emptyList())) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var resetPassword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh() { users = api.request("admin/users").jsonArray }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true; message = ""; failed = false
        try { block(); refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { failed = true; message = e.message ?: "Action failed." }
        finally { busy = false }
    } }
    LaunchedEffect(api) {
        busy = true
        try { refresh() } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { failed = true; message = e.message ?: "Could not load team members." }
        finally { busy = false }
    }
    PageHeading("Team members", "Create operator accounts and manage access to your local workspace.")
    if (message.isNotBlank()) Notice(if (failed) "Please check" else "Team updated", message, failed)
    SplitContent(main = {
        Panel("Your team", "${users.size} accounts on this workspace") {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { action { } }) { Text("Refresh team") }
            users.forEach { element ->
                val row = element.jsonObject
                val name = row["username"]!!.jsonPrimitive.content
                val active = row["active"]!!.jsonPrimitive.boolean
                Surface(color = Canvas, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2); StatusPill(if (active) "Enabled" else "Disabled")
                        }
                        Text(if (row["role"]!!.jsonPrimitive.content == "root") "Root administrator" else "User / sales operator", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        if (row["role"]!!.jsonPrimitive.content != "root") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = { action {
                                api.request("admin/users/${row["id"]!!.jsonPrimitive.content}/active", buildJsonObject { put("active", !active) })
                                message = "$name is now ${if (active) "disabled" else "enabled"}. Existing sessions have been signed out."
                            } }) { Text(if (active) "Disable account" else "Enable account") }
                            TextButton(enabled = !busy, onClick = { selected = row; resetPassword = "" }) { Text("Reset password") }
                        }
                    }
                }
            }
            if (users.isEmpty() && !busy && !failed) Text("No team members loaded.", color = Muted)
        }
    }, aside = {
        Panel("Add a team member", "New accounts have the user role.") {
            Field(username, { username = it }, "Username", "3-40 characters: letters, numbers, dots, underscores or hyphens.", !busy)
            Field(password, { password = it }, "Password", "At least 10 characters. Share it directly with this operator.", !busy, password = true)
            Action("Create user account", !busy && username.trim().lowercase().matches(Regex("[a-z][a-z0-9._-]{2,39}")) && password.length in 10..128) { action {
                api.request("admin/users", buildJsonObject { put("username", username); put("password", password) })
                username = ""; password = ""; message = "User account created. The operator can now sign in."
            } }
        }
        Panel("Access at a glance") {
            Text("Root", fontWeight = FontWeight.SemiBold)
            Text("Manages the team, views all records and accesses terminal-wide reports and reprints.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("User", fontWeight = FontWeight.SemiBold)
            Text("Makes sales and views their own saved transactions and receipts.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    })
    selected?.let { row -> AlertDialog(onDismissRequest = { if (!busy) { selected = null; resetPassword = "" } },
        title = { Text("Reset ${row["username"]!!.jsonPrimitive.content}'s password") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("This signs the operator out of all existing sessions. Choose a new password and share it directly with them.", color = Muted)
            Field(resetPassword, { resetPassword = it }, "New password", "At least 10 characters.", !busy, password = true)
        } }, confirmButton = { Button(enabled = !busy && resetPassword.length in 10..128, onClick = { action {
            api.request("admin/users/${row["id"]!!.jsonPrimitive.content}/password", buildJsonObject { put("password", resetPassword) })
            resetPassword = ""; selected = null; message = "Password reset. Existing sessions have been signed out."
        } }) { Text("Reset password") } }, dismissButton = { TextButton(enabled = !busy, onClick = { selected = null; resetPassword = "" }) { Text("Cancel") } }) }
}

@Composable
internal fun AccountScreen(base: String, token: String, onLogout: () -> Unit) {
    val api = remember(base, token) { PortalApi(base, token) }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    PageHeading("My account", "Keep your account secure with a password only you know.")
    SplitContent(main = {
        Panel("Change your password", "You'll be signed out after updating it.") {
            Field(old, { old = it }, "Current password", enabled = !busy, password = true)
            Field(password, { password = it }, "New password", "Use 10-128 characters.", !busy, password = true)
            Field(confirm, { confirm = it }, "Confirm new password", hint = if (confirm.isNotBlank() && confirm != password) "Passwords do not match." else "", enabled = !busy, password = true, error = confirm.isNotBlank() && confirm != password)
            if (message.isNotBlank()) Notice("Password not changed", message, true)
            Action(if (busy) "Updating..." else "Update password & sign out", !busy && old.isNotBlank() && password.length in 10..128 && password == confirm) { scope.launch {
                busy = true
                try {
                    api.request("auth/password", buildJsonObject { put("oldPassword", old); put("password", password) })
                    onLogout()
                } catch (e: CancellationException) { throw e }
                catch (e: Throwable) { message = e.message ?: "Could not change password." }
                finally { busy = false }
            } }
        }
    }, aside = {
        Panel("A few helpful reminders") {
            Text("Choose a unique password", fontWeight = FontWeight.SemiBold)
            Text("Avoid sharing your account. Individual accounts keep sales and receipts linked to the right operator.", color = Muted)
            HorizontalDivider(color = Line)
            Text("App and provider passwords are separate", fontWeight = FontWeight.SemiBold)
            Text("Changing your password here does not change the dealer or terminal API credentials.", color = Muted)
            Notice("Need help signing in?", "Users can ask the root administrator to reset their password.")
        }
    })
}

internal fun readableTime(value: String) = value.take(19).replace('T', ' ') + " UTC"
internal fun serviceName(value: String) = mapOf("voucher-vend" to "Airtime sale", "token-vend" to "Electricity sale", "consumer-check" to "Meter validation", "token-reprint" to "Electricity reprint", "voucher-reprint" to "Voucher reprint", "voucher-list" to "Provider history", "eos" to "Shift report", "user-created" to "User created", "user-status" to "Account access updated", "password-reset" to "Password reset", "password-changed" to "Password changed")[value] ?: friendly(value)


