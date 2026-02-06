package com.example.offpay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.offpay.ui.theme.OffPayTheme

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var transactionManager: TransactionManager

    private var incomingPaymentMessage by mutableStateOf<String?>(null)
    private var showPinSetup by mutableStateOf(false)
    private var showPinEntry by mutableStateOf(false)
    private var pendingSendAmount by mutableStateOf(0.0)
    private var showAmountInput by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth & location permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = BluetoothManager()
        transactionManager = TransactionManager(this)

        if (!transactionManager.isPinSet()) {
            showPinSetup = true
        }

        setContent {
            OffPayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val available = transactionManager.getAvailableBalance()
                    val pendingCount = transactionManager.getPendingCount()

                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Available Balance", style = MaterialTheme.typography.titleLarge)
                        Text("₹${String.format("%.0f", available)}", style = MaterialTheme.typography.displayMedium)

                        if (pendingCount > 0) {
                            Text("$pendingCount pending tx", color = MaterialTheme.colorScheme.error)
                            Button(onClick = {
                                val count = transactionManager.settlePendingTransactions()
                                Toast.makeText(this@MainActivity, "$count tx settled", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Sync & Settle")
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { showAmountInput = true },
                            enabled = available >= 10.0
                        ) {
                            Text("Send Money")
                        }

                        incomingPaymentMessage?.let { msg ->
                            IncomingPaymentDialog(
                                message = msg,
                                onAccept = { amount ->
                                    transactionManager.addPendingTransaction(amount, "credit")
                                    incomingPaymentMessage = null
                                    Toast.makeText(this@MainActivity, "Received ₹${amount.toInt()} (pending)", Toast.LENGTH_SHORT).show()
                                },
                                onReject = {
                                    incomingPaymentMessage = null
                                }
                            )
                        }

                        if (showPinSetup) {
                            PinSetupDialog { pin ->
                                transactionManager.setPin(pin)
                                showPinSetup = false
                                Toast.makeText(this@MainActivity, "PIN set successfully", Toast.LENGTH_SHORT).show()
                            }
                        }

                        if (showAmountInput) {
                            AmountInputDialog { amount ->
                                showAmountInput = false
                                if (amount > 0 && transactionManager.getAvailableBalance() >= amount) {
                                    pendingSendAmount = amount
                                    showPinEntry = true
                                } else {
                                    Toast.makeText(this@MainActivity, "Invalid amount or low balance", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        if (showPinEntry) {
                            PinEntryDialog(
                                onPinEntered = { pin ->
                                    showPinEntry = false
                                    if (transactionManager.verifyPin(pin)) {
                                        showDeviceSelectionDialog(pendingSendAmount)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onCancel = { showPinEntry = false }
                            )
                        }
                    }
                }
            }
        }

        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed)
        } else {
            initializeBluetooth()
        }
    }

    private fun initializeBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.isEnabled == true) {
            bluetoothManager.startListening(
                onPaymentReceived = { msg ->
                    incomingPaymentMessage = msg
                },
                onError = { err ->
                    Toast.makeText(this, "Bluetooth error: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun showDeviceSelectionDialog(amount: Double) {
        val devices = bluetoothManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No paired devices", Toast.LENGTH_LONG).show()
            return
        }

        val names = devices.map { it.name ?: it.address }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Select Receiver")
            .setItems(names) { _, which ->
                val device = devices[which]
                val message = "${Build.MODEL} wants to send ₹${amount.toInt()}"

                bluetoothManager.sendPayment(device, message) { success, error ->
                    runOnUiThread {
                        if (success) {
                            transactionManager.addPendingTransaction(amount, "debit")
                            Toast.makeText(this, "Sent ₹${amount.toInt()} (pending)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, error ?: "Send failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        bluetoothManager.stopListening()
        super.onDestroy()
    }
}

@Composable
fun PinSetupDialog(onPinSet: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Set 4-Digit PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirm = it },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length == 4 && pin == confirm,
                onClick = { onPinSet(pin) }
            ) { Text("Set PIN") }
        }
    )
}

@Composable
fun AmountInputDialog(onConfirmed: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Enter Amount to Send") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text("Amount (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.toDoubleOrNull()?.let { it > 0 } == true,
                onClick = { onConfirmed(text.toDoubleOrNull() ?: 0.0) }
            ) { Text("Next") }
        }
    )
}

@Composable
fun PinEntryDialog(onPinEntered: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter PIN to Send") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
        },
        confirmButton = {
            TextButton(
                enabled = pin.length == 4,
                onClick = { onPinEntered(pin) }
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
fun IncomingPaymentDialog(
    message: String,
    onAccept: (Double) -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Incoming Payment Request") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                // Extract amount from message like "wants to send ₹500"
                val amountStr = message.substringAfterLast("₹").trim()
                val amount = amountStr.toDoubleOrNull() ?: 100.0
                onAccept(amount)
            }) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Reject") }
        }
    )
}

@Composable
fun WalletScreen(
    balance: Double,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Available Balance", style = MaterialTheme.typography.titleMedium)
                Text("₹${String.format("%.0f", balance)}", style = MaterialTheme.typography.displayMedium)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(onClick = onSendClick, modifier = Modifier.fillMaxWidth(0.8f)) {
            Text("Send Money")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Offline payments are provisional credit – settle when network returns",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}