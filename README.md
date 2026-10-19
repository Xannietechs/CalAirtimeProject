# CalAirtime merchant portal

Kotlin/Compose web portal with a local Ktor backend. Credentials stay on the backend.

Implemented flows: airtime PIN voucher purchase (including multiple denominations), voucher history and reprint, electricity consumer-check and paired token vend, electricity reprint by receipt/meter, end-of-shift report. Amounts use LSL. Balance is the last value actually returned by the provider.

## First setup: local server, root, and users

The local server saves accounts and transaction records in **data/calairtime.db**. No separate database installation is needed. These are app accounts, separate from the dealer/terminal API credentials.

1. Open PowerShell in `E:\Apps\CalAirtimeProject\CalAirtimeProject` and run:

   ```powershell
   .\kotlin.bat run -m CalAirtimeProject
   ```

2. Open another PowerShell window in the same folder and run:

   ```powershell
   .\kotlin.bat run -m webApp --port 8081
   ```

3. Open **http://localhost:8081/**. On first use, choose a root password (at least 10 characters) and click **Create root account**. The username is **root**. There is no default password.
4. Sign in as root using that password.
5. Open **Team members**, enter a username and password, then click **Create user account**. Root can also disable/re-enable users and reset user passwords. These changes revoke existing sessions.
6. Sign out. The new operator signs in using the username and password you created.
7. **Saved records** shows saved transactions and receipts. Root sees everyone's records; users see only their own. Click **Refresh records**, then click a record to view its saved receipt. This does not call the merchant API or spend another sequence number.
8. **My account** lets either role change their own password and signs them out afterward.

Accounts and records work before merchant credentials are configured. Live sales require the separate setup below. First-run root setup is disabled once an account exists. Sessions expire after eight hours; a browser refresh asks you to sign in again.

Root alone can access provider-wide history, remote reprints, and shift reports because the provider returns terminal-wide data. Users retrieve their own previously saved receipts from Saved records. Electricity checks are tied to the signed-in operator, meter and amount, including after server restart.

## Using the workspace

The sidebar appears on larger screens; smaller screens use horizontally scrollable navigation. Overview provides shortcuts and the last balance returned by the provider. Select **Sell airtime**, choose a network, a value and quantity, then **Review purchase**. The review dialog shows the total before any sale is sent. **Mix different voucher values** supports separate denominations in one purchase. Electricity uses a meter-validation step followed by a customer review and purchase confirmation.

Saved records load locally, support search and status filters, and open a record/receipt dialog. **Needs review** includes pending and unknown outcomes; reconcile these with the administrator before retrying. **Provider history**, **Reprint receipts**, and **Shift reports** contact the merchant and use new message IDs. Password fields have Show/Hide controls; user password resets use a separate dialog.

## Configure live merchant transactions (Windows)

Use two PowerShell terminals in this project directory. In the backend terminal set:

```powershell
$env:CAL_AIRTIME_BASE_URL = 'https://105.242.192.25:8443/vend-client/merchant'
$env:CAL_AIRTIME_DEALER = '300020135'
$env:CAL_AIRTIME_TERMINAL = 'TL000135'
$env:CAL_AIRTIME_API_KEY = '<real API key>'
$env:CAL_AIRTIME_PASSWORD = '<real terminal password>'
# Set this to the host:port shown by the web development server:
$env:CAL_AIRTIME_WEB_ORIGINS = 'localhost:8081,127.0.0.1:8081'
# Only if the test gateway still requires its self-signed/expired certificate:
# $env:CAL_AIRTIME_INSECURE_TEST_TLS = 'true'
.\kotlin.bat run -m CalAirtimeProject
```

Before starting, ask the server administrator for the **last accepted** sequence for this dealer/terminal. With the backend stopped and no unresolved purchase, update `calairtime-sequence.txt`:

```text
seq=<last accepted sequence, not the next sequence>
dateTime=<last accepted timestamp as an ISO UTC instant, e.g. 2026-10-16T08:00:00Z>
```

The existing seq=11 file is historical and must be reconciled before live use. Do not copy the August request dates from the API examples. If this terminal has never been initialized, the administrator must perform the documented `issue-advice` bootstrap first; seed seq=0 only once bootstrap is confirmed. The application generates current UTC dates automatically.

In the second terminal:

```powershell
.\kotlin.bat run -m webApp --port 8081
```

Open the development server URL printed by the toolchain. If its origin differs from port 8081, update `CAL_AIRTIME_WEB_ORIGINS` and restart the backend. In the portal set Backend URL to `http://localhost:8080` and click **Check connection**.

Do not put the gateway URL or credentials in browser settings. The browser talks only to this backend. The provider controls whether test transactions use dummy inventory; the portal does not simulate a successful purchase.

## Transaction safety and recovery

Run exactly one backend instance per terminal. Use a persistent working directory. Request processing is serialized and sequence state is atomically replaced before sending. A successful consumer-check reserves the terminal until the matching electricity vend completes. Its meter and amount cannot change. A vend is durably marked submitted before the network call, preventing a second vend after a timeout or restart.

If a request times out, returns a sequence error, or a vend is rejected, reconcile the outcome and sequence with the administrator before making another purchase. Never guess a new sequence or automatically retry. For an unresolved electricity request, stop the backend, have the administrator confirm the outcome, back up the state file, then restore the confirmed last sequence/date without pending fields. Restart only after reconciliation. A definitive failed consumer-check releases the local reservation.

The provider documents error 1111 for reprint by meter in dummy mode. Reprint by receipt remains available.

## Validation

```powershell
.\kotlin.bat test -m CalAirtimeProject
.\kotlin.bat build -m webApp
```

Tests use a mocked merchant gateway; no wallet or inventory is touched. They cover shared message IDs, failed-check rejection, request escaping and configured URL, product validation, timestamp ordering, persisted vend claims, and corrupt state rejection.

## Local records and backups

The server records the operator, UTC date/time, operation, request summary, status, and provider receipt. Attempts are saved before contacting the provider. If the server loses the response, the record remains pending/unknown: this is not proof that the purchase failed, and it must be reconciled before retrying. Account-management changes are recorded without passwords. API credentials and login/session secrets are excluded from records. Passwords use salted PBKDF2 hashes and session tokens are stored only as hashes.

Keep `data/` and `calairtime-sequence.txt` on this computer. To back up: stop the backend, copy the complete `data` folder and sequence file to a protected backup location, then restart it. Backups contain accounts and voucher PINs; protect them with your Windows account permissions. The database is local and is not encrypted by the app. Deleting the database deletes the app accounts and saved records. Save the root password securely; changing the API password does not reset it.

The backend binds to 127.0.0.1 for use on this computer. Run one server instance per terminal. Serving other computers or publishing online requires a separate HTTPS/network deployment. To move the database, set `CAL_AIRTIME_DB` in the backend PowerShell window to an absolute file path before starting the server.



