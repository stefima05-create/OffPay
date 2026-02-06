package com.example.offpay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothManager {

    private val TAG = "BluetoothManager"
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val SERVICE_NAME = "OffPayPayment"

    private var isListening = false
    private var listenerThread: Thread? = null

    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun sendPayment(
        device: BluetoothDevice?,
        message: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (device == null) {
            onResult(false, "No device selected")
            return
        }

        thread(name = "BT-Send") {
            var socket: BluetoothSocket? = null
            try {
                Log.d(TAG, "Connecting to ${device.name ?: device.address}")
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                Log.d(TAG, "Connected successfully")

                val out: OutputStream = socket.outputStream
                val bytes = message.toByteArray(Charsets.UTF_8)
                out.write(bytes)
                out.flush()
                Log.d(TAG, "Data flushed")

                Thread.sleep(800)  // Give receiver time to read

                onResult(true, null)
                Log.d(TAG, "Send completed")
            } catch (e: IOException) {
                Log.e(TAG, "Send IOException: ${e.message}", e)
                onResult(false, e.localizedMessage ?: "Connection failed")
            } catch (e: Exception) {
                Log.e(TAG, "Send unexpected error: ${e.message}", e)
                onResult(false, "Unknown send error")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun startListening(
        onPaymentReceived: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (isListening) return
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth disabled or not available")
            return
        }

        isListening = true
        listenerThread = thread(name = "BT-Listener") {
            while (isListening) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    Log.d(TAG, "Creating RFCOMM server socket...")
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    Log.d(TAG, "Waiting for incoming connection...")

                    val socket = serverSocket.accept()
                    val remoteName = socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "unknown"
                    Log.d(TAG, "Accepted connection from $remoteName")

                    thread(name = "BT-Read") {
                        try {
                            val input: InputStream = socket.inputStream
                            Log.d(TAG, "Starting to read data...")

                            val buffer = ByteArray(1024)
                            var totalBytes = 0
                            val startTime = System.currentTimeMillis()

                            while (totalBytes == 0 && (System.currentTimeMillis() - startTime < 3000)) {
                                val bytesRead = input.read(buffer, totalBytes, buffer.size - totalBytes)
                                if (bytesRead == -1) break
                                if (bytesRead > 0) totalBytes += bytesRead
                                Thread.sleep(100)
                            }

                            Log.d(TAG, "Bytes read: $totalBytes")
                            if (totalBytes > 0) {
                                val msg = String(buffer, 0, totalBytes, Charsets.UTF_8)
                                Log.i(TAG, "Received message: '$msg'")
                                onPaymentReceived(msg)
                            } else {
                                Log.w(TAG, "No data received (timeout)")
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Read failed: ${e.message}", e)
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Accept or listen failed: ${e.message}", e)
                    onError("Accept failed: ${e.localizedMessage}")
                    Thread.sleep(2000)
                } finally {
                    try { serverSocket?.close() } catch (_: Exception) {}
                }
            }
            Log.d(TAG, "Listener stopped")
        }
    }

    fun stopListening() {
        isListening = false
        listenerThread?.interrupt()
        listenerThread = null
    }
}