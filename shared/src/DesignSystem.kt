package com.calairtime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*

internal val Ink = Color(0xFF142D42)
internal val Muted = Color(0xFF607585)
internal val Teal = Color(0xFF007F76)
internal val Canvas = Color(0xFFF3F6F8)
internal val Line = Color(0xFFDCE5EA)

@Composable
internal fun CalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White, secondary = Ink,
            background = Canvas, surface = Color.White, onSurface = Ink, onBackground = Ink,
            secondaryContainer = Color(0xFFE0F2ED), onSecondaryContainer = Teal,
            surfaceContainerHigh = Color.White, surfaceContainer = Color.White,
            surfaceVariant = Color(0xFFEAF0F3), onSurfaceVariant = Muted, outline = Color(0xFF899CA9)),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 32.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)),
        content = content)
}

@Composable
internal fun Brand(light: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = Teal) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text("ca", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        Column {
            Text("CalAirtime", color = if (light) Color.White else Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("MERCHANT WORKSPACE", color = if (light) Color(0xFFABC2D0) else Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
internal fun PageHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, color = Muted)
    }
}

@Composable
internal fun Panel(title: String = "", subtitle: String = "", modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (title.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String = "", enabled: Boolean = true, password: Boolean = false, error: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled, isError = error, shape = RoundedCornerShape(12.dp),
        supportingText = if (hint.isNotBlank()) ({ Text(hint) }) else null,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) ({ TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } }) else null)
}

@Composable
internal fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) { Text(text) }
}

@Composable
internal fun Notice(title: String, body: String = "", danger: Boolean = false) {
    val tone = if (danger) Color(0xFF993829) else Teal
    Surface(color = if (danger) Color(0xFFFFF1EC) else Color(0xFFE8F5F1), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = tone, fontWeight = FontWeight.SemiBold)
            if (body.isNotBlank()) Text(body, color = tone, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ErrorNotice(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    val title = when {
        message.contains("certificate", true) || message.contains("TLS", true) -> "Secure connection needs attention"
        message.contains("sequence", true) || message.contains("Message ID", true) -> "The terminal sequence needs checking"
        message.contains("configured", true) -> "The merchant connection is not set up"
        message.contains("confirmed response", true) || message.contains("timed out", true) -> "Purchase outcome is not confirmed"
        else -> "We couldn't complete that request"
    }
    Panel {
        Notice(title, if (title.contains("outcome")) "Check your records and contact the administrator before trying again." else message, true)
        if (title.contains("outcome")) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide technical details" else "Show technical details") }
        if (expanded) SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun StatusPill(status: String) {
    val tone = when (status.lowercase()) {
        "success", "enabled", "ready" -> Teal
        "pending", "unknown", "needs review" -> Color(0xFF986200)
        "failed", "rejected", "disabled" -> Color(0xFFAE4234)
        else -> Muted
    }
    Surface(color = tone.copy(alpha = .10f), shape = RoundedCornerShape(30.dp)) {
        Text(status.replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 11.dp, vertical = 5.dp), color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SplitContent(main: @Composable () -> Unit, aside: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(20.dp)) { main() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { aside() }
        } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { main(); aside() }
    }
}

internal val PageNames = mapOf("Overview" to "Overview", "Airtime" to "Sell airtime", "Electricity" to "Sell electricity", "Records" to "Saved records", "History" to "Provider history", "Reprint" to "Reprint receipts", "Shift" to "Shift reports", "Users" to "Team members", "Account" to "My account")
private val PageMarks = mapOf("Overview" to "01", "Airtime" to "02", "Electricity" to "03", "Records" to "04", "History" to "05", "Reprint" to "06", "Shift" to "07", "Users" to "08", "Account" to "09")

@Composable
internal fun Workspace(page: String, pages: List<String>, username: String, root: Boolean, busy: Boolean, navigate: (String) -> Unit, logout: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val wide = maxWidth >= 1000.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Column(Modifier.width(240.dp).fillMaxHeight().background(Ink).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Brand(true)
                Spacer(Modifier.height(20.dp))
                Text("WORKSPACE", color = Color(0xFF8CA7B8), fontSize = 10.sp, letterSpacing = 1.5.sp)
                pages.forEach { item ->
                    val selected = page == item
                    Surface(Modifier.fillMaxWidth().clickable(enabled = !busy) { navigate(item) }, color = if (selected) Color(0xFF24475C) else Color.Transparent, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(PageMarks[item] ?: "", color = if (selected) Color(0xFF70DFCD) else Color(0xFF8CA7B8), fontSize = 11.sp)
                            Text(PageNames[item] ?: item, color = if (selected) Color.White else Color(0xFFC0D0DB), fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = Color(0xFF345165))
                Text("Local workspace", color = Color.White, fontSize = 13.sp)
                Text("Records saved on this computer", color = Color(0xFFABC2D0), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Surface(color = Color.White, shadowElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 30.dp else 16.dp, vertical = 14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (wide) Column { Text("Merchant portal", fontWeight = FontWeight.SemiBold); Text("Airtime & electricity", color = Muted, fontSize = 12.sp) } else Brand()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (wide) StatusPill(if (root) "Root" else "User")
                                if (wide) Text(username, Modifier.widthIn(max = 140.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                TextButton(onClick = logout, enabled = !busy) { Text("Sign out") }
                            }
                        }
                        if (!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pages.forEach { item -> FilterChip(selected = page == item, enabled = !busy, onClick = { navigate(item) }, label = { Text(PageNames[item] ?: item) }) }
                        }
                    }
                }
                key(page) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(if (wide) 30.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
                    }
                }
            }
        }
    }
}

internal fun friendly(key: String): String = mapOf("tk" to "Electricity tokens", "instr" to "Recharge instructions", "receiptNo" to "Receipt number", "seqNumber" to "Request sequence", "dateTime" to "Request time (UTC)", "denom" to "Value (LSL)", "pin" to "Voucher PIN", "accNo" to "Account number", "voucherLog" to "Voucher history")[key]
    ?: key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
internal fun DataFields(value: JsonElement, depth: Int = 0) {
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            if (child is JsonPrimitive) {
                if (key.lowercase() in setOf("pin", "token") && child.content.length >= 12) {
                    Text(friendly(key), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Text(child.content.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                } else LabelValue(friendly(key), if (child is JsonNull) "--" else child.content)
            } else {
                Text(friendly(key), fontWeight = FontWeight.SemiBold)
                Column(Modifier.padding(start = if (depth < 2) 10.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { DataFields(child, depth + 1) }
            }
        }
        is JsonArray -> if (value.isEmpty()) Text("No items to display.", color = Muted) else value.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = Line)
            DataFields(item, depth + 1)
        }
        else -> Text((value as? JsonPrimitive)?.content ?: value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ReceiptPanel(response: JsonObject, title: String = "Transaction receipt") {
    var details by remember(response) { mutableStateOf(false) }
    val status = response["status"] as? JsonObject
    val ok = status?.get("success")?.jsonPrimitive?.intOrNull == 1
    Panel(title, "Keep this receipt for your records.") {
        if (status != null) StatusPill(if (ok) "success" else "needs review")
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val fields = JsonObject(response.filterKeys { it !in setOf("message", "status", "waterMark") })
                if (fields.isNotEmpty()) DataFields(fields)
                else Text(status?.get("description")?.jsonPrimitive?.contentOrNull ?: "No receipt details available.", color = Muted)
            }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide technical details" else "Technical details") }
        if (details) SelectionContainer { Text(receiptText(response), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
    }
}


