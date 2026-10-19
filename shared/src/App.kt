package com.calairtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun MerchantPortal(baseUrl: String, token: String, username: String, root: Boolean, onLogout: () -> Unit) {
    val client = remember { HttpClient { install(HttpTimeout) { requestTimeoutMillis = 70_000; connectTimeoutMillis = 15_000 } } }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf("Overview") }
    var provider by remember { mutableStateOf("VCL") }
    var product by remember { mutableStateOf("mocha-o-chele") }
    var amounts by remember { mutableStateOf("10") }
    var denomination by remember { mutableStateOf(10) }
    var quantity by remember { mutableStateOf(1) }
    var mixed by remember { mutableStateOf(false) }
    var meter by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("100") }
    var receipt by remember { mutableStateOf("") }
    var voucherId by remember { mutableStateOf("") }
    var reprintValue by remember { mutableStateOf("10") }
    var reprintType by remember { mutableStateOf("Airtime voucher") }
    var shiftDate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checkedPayment by remember { mutableStateOf<Pair<String, String>?>(null) }
    var checkedDetails by remember { mutableStateOf<JsonObject?>(null) }
    var vendSubmitted by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var resultPage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf("Not checked yet") }
    var confirmPurchase by remember { mutableStateOf(false) }
    val allowed = when (product) { "buddie" -> listOf(5,10,20); "libertie" -> listOf(50,100); else -> listOf(5,10,20,50,100) }
    val values = amounts.split(',').mapNotNull { it.trim().toIntOrNull() }
    val validVouchers = values.isNotEmpty() && values.size <= 100 && values.size == amounts.split(',').size && values.all { it in allowed }
    fun submit(path: String, params: Map<String, String> = emptyMap()) {
        if (busy) return
        busy = true; error = ""; result = null; resultPage = operation
        if (path == "token-vend") vendSubmitted = true
        scope.launch {
            try {
                val response = client.post("${baseUrl.trimEnd('/')}/$path") {
                    bearerAuth(token); params.forEach { (key, value) -> parameter(key, value) }
                }
                if (response.status.value == 401) { onLogout(); return@launch }
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                result = json
                val ok = json["status"]?.jsonObject?.get("success")?.jsonPrimitive?.intOrNull == 1
                if (path == "consumer-check") {
                    checkedPayment = if (ok) meter to amount else null
                    checkedDetails = if (ok) json else null
                }
                if (path == "token-vend" && ok) { checkedPayment = null; checkedDetails = null }
                json["balance"]?.jsonPrimitive?.contentOrNull?.let { balance = it }
                if (!ok) error = json["status"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull ?: "Request failed."
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { error = "No confirmed response: ${e.message}. Check saved records and ask the administrator before retrying." }
            finally { busy = false }
        }
    }
    fun checkConnection() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val response = client.get("${baseUrl.trimEnd('/')}/config-status") { bearerAuth(token) }
                if (response.status.value == 401) { onLogout(); return@launch }
                connection = Json.parseToJsonElement(response.bodyAsText()).jsonObject["description"]?.jsonPrimitive?.contentOrNull ?: "Sign in again."
            } catch (e: CancellationException) { throw e }
            catch (_: Throwable) { connection = "Local server unavailable. Check that the server is running." }
            finally { busy = false }
        }
    }
    val pages = listOf("Overview", "Airtime", "Electricity", "Records") + (if (root) listOf("History", "Reprint", "Shift", "Users") else emptyList()) + "Account"
    CalTheme {
        Workspace(operation, pages, username, root, busy, { operation = it }, {
            scope.launch {
                try { client.post("${baseUrl.trimEnd('/')}/auth/logout") { bearerAuth(token) } }
                catch (e: CancellationException) { throw e }
                catch (_: Throwable) { }
                finally { onLogout() }
            }
        }) {
            when (operation) {
                "Overview" -> {
                    PageHeading("Welcome back, $username", "Your daily workspace for airtime, electricity and customer receipts.")
                    SplitContent(main = {
                        Panel("Ready for your next customer?", "Choose a service to start a sale.") {
                            Action("Sell airtime vouchers", !busy) { operation = "Airtime" }
                            OutlinedButton(onClick = { operation = "Electricity" }, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = !busy) { Text("Sell prepaid electricity") }
                        }
                        Panel("Every transaction, in one place", "Review your saved sales and open a receipt without sending another purchase.") {
                            Text("Your local records include successful, rejected and unconfirmed attempts.", color = Muted)
                            TextButton(onClick = { operation = "Records" }) { Text("View saved records") }
                        }
                    }, aside = {
                        Panel("Last reported balance") {
                            Text(balance?.let { "LSL $it" } ?: "Not available yet", style = MaterialTheme.typography.headlineSmall)
                            Text("Updated only when the provider returns a balance. This is not a live wallet check.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        Panel("Connection & workspace") {
                            LabelValue("Signed in as", if (root) "Root administrator" else "User")
                            LabelValue("Record storage", "This computer")
                            Text(connection, color = Muted, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = ::checkConnection, enabled = !busy) { Text(if (busy) "Checking..." else "Check local connection") }
                            Text("This checks the local server configuration; it does not test the provider or make a sale.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    })
                }
                "Records" -> LocalRecordsScreen(baseUrl, token, root)
                "Users" -> if (root) UsersScreen(baseUrl, token)
                "Account" -> AccountScreen(baseUrl, token, onLogout)
                "Airtime" -> {
                    PageHeading("Sell airtime", "Choose a network and voucher value. Review the total before confirming.")
                    SplitContent(main = {
                        Panel("1. Choose a network", "Airtime is issued as a PIN voucher for your customer.") {
                            ProductPicker(provider, product, !busy) { network, plan ->
                                provider = network; product = plan
                                denomination = if (plan == "libertie") 50 else 10
                                quantity = 1; amounts = denomination.toString()
                            }
                        }
                        Panel("2. Select voucher value", "All values are in Lesotho loti (LSL).") {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                allowed.forEach { value -> FilterChip(selected = !mixed && denomination == value, enabled = !busy, onClick = {
                                    mixed = false; denomination = value; amounts = List(quantity) { value }.joinToString(",")
                                }, label = { Text("LSL $value") }) }
                            }
                            if (!mixed) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Number of vouchers", fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(enabled = !busy && quantity > 1, onClick = { quantity--; amounts = List(quantity) { denomination }.joinToString(",") }) { Text("−") }
                                    Text(quantity.toString(), style = MaterialTheme.typography.titleLarge)
                                    OutlinedButton(enabled = !busy && quantity < 100, onClick = { quantity++; amounts = List(quantity) { denomination }.joinToString(",") }) { Text("+") }
                                }
                            }
                            TextButton(enabled = !busy, onClick = { mixed = !mixed; if (!mixed) amounts = List(quantity) { denomination }.joinToString(",") }) { Text(if (mixed) "Use a single voucher value" else "Mix different voucher values") }
                            if (mixed) Field(amounts, { amounts = it }, "Voucher values", "Separate each voucher with a comma, for example 10,20,10.", !busy, error = !validVouchers)
                        }
                    }, aside = {
                        Panel("Sale summary") {
                            LabelValue("Network", provider)
                            LabelValue("Product", productLabel(product))
                            LabelValue("Vouchers", if (validVouchers) values.size.toString() else "Choose valid values")
                            HorizontalDivider(color = Line)
                            Text("Total to collect", color = Muted)
                            Text(if (validVouchers) "LSL ${values.sum()}.00" else "--", style = MaterialTheme.typography.headlineLarge)
                            Action(if (busy) "Processing..." else "Review purchase", !busy && validVouchers) { confirmPurchase = true }
                            Text("Confirming the purchase sends a request to the provider. Whether it debits a wallet depends on the provider's environment.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (resultPage == operation && error.isNotBlank()) ErrorNotice(error)
                        if (resultPage == operation && error.isBlank()) result?.let { ReceiptPanel(it, "Airtime receipt") }
                    })
                }
                "Electricity" -> {
                    PageHeading("Sell electricity", "Validate the meter, check the customer details, then confirm the purchase.")
                    SplitContent(main = {
                        Panel("1. Meter & payment", "Use the meter number supplied by your customer.") {
                            Field(meter, { meter = it }, "Meter number", "Enter all 11 digits, including leading zeros.", !busy && checkedPayment == null)
                            Field(amount, { amount = it }, "Amount (LSL)", "Enter the amount collected from your customer.", !busy && checkedPayment == null)
                            Action(if (busy) "Please wait..." else "Validate meter", !busy && checkedPayment == null && !vendSubmitted && meter.length == 11 && meter.all { it in '0'..'9' } && (amount.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true)) {
                                submit("consumer-check", mapOf("meter" to meter, "amount" to amount))
                            }
                        }
                        if (checkedPayment != null) Panel("2. Review customer details") {
                            checkedDetails?.get("consumer")?.let { DataFields(it) }
                            Notice("Meter validated", "The meter and amount are reserved for this sale. Confirm these details with the customer.")
                            Action("Confirm electricity purchase", !busy && checkedPayment == (meter to amount) && !vendSubmitted) { confirmPurchase = true }
                        }
                        if (vendSubmitted && checkedPayment == null) Action("Start another electricity sale", !busy) { vendSubmitted = false; meter = ""; result = null }
                    }, aside = {
                        Panel("Purchase summary") {
                            LabelValue("Meter", meter.ifBlank { "Not entered" }); LabelValue("Payment", "Cash"); LabelValue("Amount", "LSL ${amount.ifBlank { "0" }}")
                            Text("A purchase can only use the exact meter and amount from a successful validation.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (resultPage == operation && error.isNotBlank()) ErrorNotice(error)
                        if (resultPage == operation && error.isBlank()) result?.takeIf { vendSubmitted }?.let { ReceiptPanel(it, "Electricity receipt") }
                    })
                }
                "History" -> {
                    PageHeading("Provider history", "Retrieve terminal-wide voucher history from the provider.")
                    Panel("Provider voucher history", "Root access only") {
                        Notice("This contacts the provider", "Unlike saved records, loading this history uses a new message sequence.")
                        Action("Load provider history", !busy) { submit("voucher-list") }
                        TextButton(onClick = { operation = "Records" }) { Text("View local records instead") }
                    }
                }
                "Reprint" -> {
                    PageHeading("Reprint receipts", "Find a previously issued voucher or electricity token.")
                    Panel("Find a receipt", "Root access only. For a receipt already saved on this computer, use Saved records.") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Airtime voucher", "Electricity receipt", "Meter number").forEach { kind -> FilterChip(selected = reprintType == kind, enabled = !busy, onClick = { reprintType = kind }, label = { Text(kind) }) }
                        }
                        when (reprintType) {
                            "Airtime voucher" -> {
                                ProductPicker(provider, product, !busy) { network, plan -> provider = network; product = plan; reprintValue = if (plan == "libertie") "50" else "10" }
                                Field(voucherId, { voucherId = it }, "Voucher transaction ID", "Use the ID from provider history.", !busy)
                                Field(reprintValue, { reprintValue = it }, "Voucher value (LSL)", enabled = !busy)
                                Action("Retrieve voucher", !busy && voucherId.isNotBlank() && reprintValue.toIntOrNull() in allowed) { submit("voucher-reprint", mapOf("provider" to provider, "product" to product, "id" to voucherId, "denom" to reprintValue)) }
                            }
                            "Electricity receipt" -> {
                                Field(receipt, { receipt = it }, "Receipt number", "Enter the number printed on the electricity receipt.", !busy)
                                Action("Retrieve electricity receipt", !busy && receipt.isNotBlank()) { submit("token-reprint", mapOf("receipt" to receipt)) }
                            }
                            else -> {
                                Field(meter, { meter = it }, "Meter number", "Retrieve the latest token for this meter.", !busy)
                                Action("Retrieve latest token", !busy && meter.length == 11) { submit("token-reprint", mapOf("meter" to meter)) }
                                Notice("Provider test limitation", "Reprint by meter may be unavailable in the provider's test environment.")
                            }
                        }
                    }
                }
                "Shift" -> {
                    PageHeading("Shift reports", "Review provider sales totals for a selected date.")
                    Panel("End-of-shift report", "Root access only") {
                        Field(shiftDate, { shiftDate = it }, "Shift date", "Use YYYY-MM-DD, for example 2026-10-19.", !busy)
                        Action("Load shift report", !busy && shiftDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { submit("eos", mapOf("shiftDate" to shiftDate)) }
                        Text("This report comes from the provider and uses a new request sequence.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (operation in setOf("History", "Reprint", "Shift") && resultPage == operation) {
                if (error.isNotBlank()) ErrorNotice(error) else result?.let { ReceiptPanel(it, if (operation == "Shift") "Shift report" else "Provider response") }
            }
            Text("CalAirtime  /  Local merchant workspace  /  LSL", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (confirmPurchase) AlertDialog(onDismissRequest = { confirmPurchase = false },
            title = { Text("Confirm ${if (operation == "Airtime") "airtime" else "electricity"} purchase") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (operation == "Airtime") {
                    LabelValue("Network", "$provider / ${productLabel(product)}"); LabelValue("Vouchers", values.size.toString()); LabelValue("Total", "LSL ${values.sum()}.00")
                } else { LabelValue("Meter", meter); LabelValue("Amount", "LSL $amount") }
                Text("Confirm only after checking the customer's order and collecting payment.", color = Muted)
            } },
            confirmButton = { Button(onClick = {
                confirmPurchase = false
                if (operation == "Airtime") submit("voucher-vend", mapOf("provider" to provider, "product" to product, "amount" to amounts))
                else submit("token-vend", mapOf("meter" to meter, "amount" to amount))
            }) { Text("Confirm purchase") } },
            dismissButton = { TextButton(onClick = { confirmPurchase = false }) { Text("Go back") } })
    }
}

internal fun productLabel(product: String) = when (product) { "mocha-o-chele" -> "Mocha-o-chele"; "buddie" -> "Buddie"; else -> "Libertie" }

@Composable
private fun ProductPicker(provider: String, product: String, enabled: Boolean, onSelect: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("VCL" to "mocha-o-chele", "ETL" to "buddie", "ETL" to "libertie").forEach { (network, plan) ->
            val selected = provider == network && product == plan
            OutlinedButton(onClick = { onSelect(network, plan) }, Modifier.fillMaxWidth().heightIn(min = 58.dp), enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Teal.copy(alpha = .08f) else androidx.compose.ui.graphics.Color.White),
                border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Teal else Line)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(network, fontWeight = FontWeight.Bold); Text(productLabel(plan), color = Muted, style = MaterialTheme.typography.bodyMedium) }
                    Text(if (selected) "Selected" else "Select", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal fun receiptText(value: JsonElement, indent: String = ""): String = when (value) {
    is JsonObject -> value.entries.joinToString("\n") { (key, child) -> if (child is JsonPrimitive) "$indent${friendly(key)}: ${child.content}" else "$indent${friendly(key)}:\n${receiptText(child, "$indent  ")}" }
    is JsonArray -> value.joinToString("\n\n") { receiptText(it, indent) }
    else -> "$indent${value.jsonPrimitive.content}"
}

