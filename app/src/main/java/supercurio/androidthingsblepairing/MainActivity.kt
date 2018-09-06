package supercurio.androidthingsblepairing

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import com.google.android.things.bluetooth.*
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var connectionManager: BluetoothConnectionManager
    private lateinit var configManager: BluetoothConfigManager

    private var gattServer: BluetoothGattServer? = null

    companion object {
        private const val TAG = "BLEBonding"

        private val SERVICE_UUID: UUID =
                UUID.fromString("B340B65C-B8AE-49E7-8ED8-F79C61708475")
        private val CHARACTERISTIC_UUID: UUID =
                UUID.fromString("FEE891B9-032A-43AF-8923-5E3A4FF989A3")
        private val DESCRIPTOR_CONFIG_UUID: UUID =
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // change to false to test pairing at the first read of an encrypted characteristic
        private const val START_BOND_FIRST = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        connectionManager = BluetoothConnectionManager.getInstance()
        configManager = BluetoothConfigManager.getInstance()

        // start with the BT adapter shut off
        if (bluetoothManager.adapter.isEnabled) bluetoothManager.adapter.disable()

        // Register the adapter state change receiver
        registerReceiver(adapterStateChangeReceiver,
                         IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Register more BT pairing broadcast receivers
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(bluetoothDeviceReceiver, filter)
        connectionManager.registerConnectionCallback(connectionCallback)
        connectionManager.registerPairingCallback(pairingCallback)

        // Turn BT adapter back on
        Timer().schedule(1000) {
            bluetoothManager.adapter.enable()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        connectionManager.unregisterPairingCallback(pairingCallback)
        connectionManager.unregisterConnectionCallback(connectionCallback)
    }

    private fun listBondedDevices() {
        Log.i(TAG, "Bonded devices:")
        bluetoothManager.adapter.bondedDevices.forEach { device ->
            Log.i(TAG, "addr: ${device.address}, name: ${device.name}")
        }
    }

    private fun startAdvertising() {
        BluetoothAdapter.getDefaultAdapter().name = "BLE"

        val advertiser: BluetoothLeAdvertiser = bluetoothManager.adapter.bluetoothLeAdvertiser

        advertiser.let {
            val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .build()

            val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

            it.startAdvertising(settings, data, advertiseCallback)
        }
    }

    private fun stopAdvertising() {
        Log.i(TAG, "Stop BLE Advertising")
        bluetoothManager
                .adapter
                .bluetoothLeAdvertiser
                .stopAdvertising(advertiseCallback)
    }

    private fun restartAdvertising() {
        thread(start = true) {
            Thread.sleep(1000)
            stopAdvertising()
            Thread.sleep(500)
            startAdvertising()
        }
    }

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        gattServer?.addService(createService()) ?: Log.e(TAG, "Unable to create GATT server")
    }

    private fun createService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID,
                                           BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)

        val config = BluetoothGattDescriptor(
                DESCRIPTOR_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ)
        characteristic.addDescriptor(config)

        service.addCharacteristic(characteristic)

        return service
    }


    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertise Started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "LE Advertise Failed: " + when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown"
            })
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE device connected: $device")

                    if (START_BOND_FIRST)
                    // connectionManager.initiatePairing(device)
                        device.createBond()

                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "BLE device disconnected: $device")
                    restartAdvertising()
                }
            }
        }
    }


    private val adapterStateChangeReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive for action: " + intent.action)

            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                                   BluetoothAdapter.STATE_OFF)

                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.i(TAG, "BT Adapter is on")
                        listBondedDevices()

                        // IO_CAPABILITY_OUT logs the PIN
                        configManager.leIoCapability = BluetoothConfigManager.IO_CAPABILITY_NONE
                        configManager.ioCapability = BluetoothConfigManager.IO_CAPABILITY_NONE

                        startGattServer()
                        Timer().schedule(500) {
                            startAdvertising()
                        }
                    }
                }
            }
        }
    }

    private val bluetoothDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Intent: $intent")
            intent.extras?.keySet()?.forEach { key ->
                Log.i(TAG, "extra key: $key, value: ${intent.extras!![key]}")
            }

            // val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private val connectionCallback = object : BluetoothConnectionCallback {
        override fun onConnected(device: BluetoothDevice, profile: Int) {
            Log.i(TAG, "Connected: device addr: ${device.address}, name: ${device.name}")
        }

        override fun onDisconnected(device: BluetoothDevice, profile: Int) {
            Log.i(TAG, "Disconnected: device addr: ${device.address}, name: ${device.name}")
        }

        override fun onConnectionRequested(device: BluetoothDevice,
                                           connectionParams: ConnectionParams?) {
            Log.i(TAG, "Connection requested: device addr: ${device.address}, name: ${device.name}")
        }

        override fun onConnectionRequestCancelled(device: BluetoothDevice,
                                                  requestType: Int) {
            Log.i(TAG, "Connection request cancelled: device " +
                    "addr: ${device.address}, name: ${device.name}")
        }
    }

    private val pairingCallback = object : BluetoothPairingCallback {
        override fun onPairingInitiated(device: BluetoothDevice,
                                        pairingParams: PairingParams) {
            Log.i(TAG, "Pairing initiated: device addr: ${device.address}, name: ${device.name}")

            when (pairingParams.pairingType) {
                PairingParams.PAIRING_VARIANT_DISPLAY_PIN ->
                    Log.i(TAG, "Display PIN ${pairingParams.pairingPin}")
                PairingParams.PAIRING_VARIANT_DISPLAY_PASSKEY ->
                    Log.i(TAG, "Display passkey: ${pairingParams.pairingPin}")
                PairingParams.PAIRING_VARIANT_CONSENT -> {
                    Log.i(TAG, "Consent")
                    connectionManager.finishPairing(device)
                }
                PairingParams.PAIRING_VARIANT_PIN ->
                    Log.i(TAG, "PIN")
                PairingParams.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
                    Log.i(TAG, "Passkey confirm")
            }
        }

        override fun onPaired(device: BluetoothDevice) {
            Log.i(TAG, "Paired: device addr: ${device.address}, name: ${device.name}")
        }

        override fun onPairingError(device: BluetoothDevice,
                                    error: BluetoothPairingCallback.PairingError) {
            Log.i(TAG, "Pairing error: device addr: ${device.address}, name: ${device.name} " +
                    "Error: " + describePairingError(error.errorCode))
        }
    }

    fun describePairingError(error: Int): String =
            when (error) {
                BluetoothPairingCallback.PairingError.UNBOND_REASON_AUTH_CANCELED ->
                    "AUTH_CANCELED"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_AUTH_FAILED ->
                    "AUTH_FAILED"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_AUTH_REJECTED ->
                    "AUTH_REJECTED"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_AUTH_TIMEOUT ->
                    "AUTH_TIMEOUT"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_DISCOVERY_IN_PROGRESS ->
                    "DISCOVERY_IN_PROGRESS"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_REMOTE_AUTH_CANCELED ->
                    "REMOTE_AUTH_CANCELED"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_REMOTE_DEVICE_DOWN ->
                    "REMOTE_DEVICE_DOWN"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_REMOVED ->
                    "REMOVED"
                BluetoothPairingCallback.PairingError.UNBOND_REASON_REPEATED_ATTEMPTS ->
                    "REPEATED_ATTEMPTS"
                else -> "Unknown"
            }
}
