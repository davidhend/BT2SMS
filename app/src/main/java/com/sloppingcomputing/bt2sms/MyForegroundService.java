package com.sloppingcomputing.bt2sms;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.SEND_SMS;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Foreground service that owns the Bluetooth socket and bridges between SMS
 * and the Teensy.  Previously the SMS->BT path lived in MainActivity, so any
 * incoming SMS was dropped when the user wasn't looking at the app.  Now the
 * activity is purely a settings/UI surface; this service does the actual
 * forwarding in both directions.
 *
 * Triggered three ways:
 *   - MainActivity startForegroundService() on app launch (warm path).
 *   - MessageReceiver startForegroundService(ACTION_SMS_RECEIVED) when an
 *     SMS arrives — works even if the process was killed (cold start).
 *   - User taps the "Manually Connect" button, which calls
 *     establishBluetoothConnection() directly via the binder.
 */
public class MyForegroundService extends Service {
    private static final String TAG = "MyForegroundService";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "MyForegroundServiceChannel";
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final String PREFS = "myPreferences";
    public static final String PREF_MAC = "MAC";
    public static final String PREF_NUMBER = "NUMBER";

    /** Intent action MessageReceiver uses to hand us an inbound SMS. */
    public static final String ACTION_SMS_RECEIVED =
            "com.sloppingcomputing.bt2sms.SMS_RECEIVED";
    public static final String EXTRA_SMS_BODY = "sms_body";
    public static final String EXTRA_SMS_FROM = "sms_from";

    private final IBinder binder = new LocalBinder();
    private Callback activityCallback;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outStream;
    private InputStream mmInputStream;
    private Thread workerThread;
    private volatile boolean stopWorker;

    /** Cached destination number, mirrored to SharedPreferences. */
    private String telephoneNumber;

    public class LocalBinder extends Binder {
        MyForegroundService getService() {
            return MyForegroundService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public interface Callback {
        void onServiceResponse(String result);
    }

    public void setCallback(Callback callback) {
        this.activityCallback = callback;
    }

    /** Persist the destination number from the activity. */
    public void doSomethingInService(String message) {
        Log.d(TAG, "Phone number updated: " + message);
        telephoneNumber = message;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NUMBER, message)
                .apply();
        if (activityCallback != null) {
            activityCallback.onServiceResponse("Service processed: " + message);
        }
    }

    /**
     * Null-safe.  The previous version dereferenced {@code btSocket} directly,
     * crashing on first launch whenever BT couldn't be set up (permissions
     * missing, MAC invalid, peer absent).
     */
    public boolean isConnected() {
        return btSocket != null && btSocket.isConnected();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Must come up as a foreground service first, BEFORE any potentially
        // slow work, or the OS will kill us with ForegroundServiceDidNotStart
        // -InTime.  Previously establishBluetoothConnection() ran here on the
        // main thread, which both blocked the UI and ate into the 5-second
        // start-foreground window.
        startForegroundCompat("Starting…");

        // Restore the phone number persisted from a prior session.
        telephoneNumber = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_NUMBER, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SMS_RECEIVED.equals(intent.getAction())) {
            // Cold-start path: we may have been spun up purely to deliver
            // this SMS.  Make sure BT is up, then forward.
            if (!isConnected()) {
                establishBluetoothConnection();
            }
            handleIncomingSms(intent.getStringExtra(EXTRA_SMS_BODY),
                    intent.getStringExtra(EXTRA_SMS_FROM));
        } else if (!isConnected()) {
            establishBluetoothConnection();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWorker = true;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        closeBluetooth();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void handleIncomingSms(String body, String from) {
        if (body == null || from == null) return;
        String entered = telephoneNumber;
        if (entered == null || entered.isEmpty()) {
            Log.i(TAG, "Dropping SMS from " + from + ": no destination configured.");
            return;
        }
        // PhoneNumberUtils.compare is format-tolerant: "5551234567",
        // "+15551234567", "1-555-123-4567" all match each other.  The old
        // String.equals path was fragile and silently dropped messages
        // whenever the carrier-supplied originating address didn't byte-match
        // what the user typed.
        if (!PhoneNumberUtils.compare(entered, from)) {
            Log.i(TAG, "Dropping SMS from " + from
                    + ": doesn't match configured " + entered);
            return;
        }
        Log.i(TAG, "Forwarding SMS to BT (" + body.length() + " bytes)");
        sendData(body);
    }

    public void establishBluetoothConnection() {
        Log.d(TAG, "establishBluetoothConnection called");

        if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("BLUETOOTH_CONNECT not granted");
            return;
        }

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        btAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (btAdapter == null) {
            errorExit("Bluetooth not supported.");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String macAddress = prefs.getString(PREF_MAC, null);
        if (macAddress == null) {
            macAddress = "00:14:03:06:8C:C7";
        }

        BluetoothDevice device;
        try {
            device = btAdapter.getRemoteDevice(macAddress);
        } catch (IllegalArgumentException e) {
            errorExit("Invalid MAC address: " + macAddress);
            return;
        }

        // Clean up any prior socket before reconnecting.
        closeBluetooth();

        btAdapter.cancelDiscovery();
        try {
            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            btSocket.connect();
            outStream = btSocket.getOutputStream();
            mmInputStream = btSocket.getInputStream();
            updateNotification("Connected to " + macAddress);
            beginListenForData();
        } catch (IOException e) {
            Log.e(TAG, "BT connect failed: " + e.getMessage());
            updateNotification("Disconnected");
            closeBluetooth();
        }
    }

    public void errorExit(String message) {
        Log.d(TAG, "errorExit: " + message);
        // Toasts from a service silently no-op on Android 11+, so logging is
        // the reliable channel.  Keep the toast as a hint for older devices.
        try {
            Toast.makeText(getBaseContext(), "Fatal Error - " + message,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) { }
    }

    public void sendData(String message) {
        if (message == null) return;
        if (outStream == null) {
            Log.w(TAG, "Drop BT write, outStream is null: " + message);
            return;
        }
        try {
            outStream.write(message.getBytes(StandardCharsets.US_ASCII));
            outStream.write('\n');
        } catch (IOException e) {
            Log.e(TAG, "BT write failed: " + e.getMessage());
            updateNotification("Disconnected");
            closeBluetooth();
        }
    }

    private void beginListenForData() {
        stopWorker = false;
        workerThread = new Thread(() -> {
            byte[] readBuffer = new byte[1024];
            int readBufferPosition = 0;
            try {
                while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                    int bytesAvailable = mmInputStream.available();
                    if (bytesAvailable <= 0) {
                        Thread.sleep(25);
                        continue;
                    }
                    byte[] packetBytes = new byte[bytesAvailable];
                    int read = mmInputStream.read(packetBytes);
                    for (int i = 0; i < read; i++) {
                        byte b = packetBytes[i];
                        if (b == '\n') {
                            String data = new String(readBuffer, 0,
                                    readBufferPosition, StandardCharsets.US_ASCII);
                            readBufferPosition = 0;
                            Log.i(TAG, "BT->SMS (" + data.length() + "): " + data);
                            sendMsg(telephoneNumber, data);
                        } else if (b != '\r' && readBufferPosition < readBuffer.length) {
                            // Strip the CR that Teensy's println() emits.
                            // Otherwise it rides along in the SMS body and
                            // throws off the receiver's hash extraction.
                            readBuffer[readBufferPosition++] = b;
                        }
                    }
                }
            } catch (InterruptedException ignore) {
                // expected on shutdown
            } catch (IOException ex) {
                if (!stopWorker) {
                    Log.e(TAG, "Reader thread I/O error: " + ex.getMessage());
                    updateNotification("Disconnected");
                }
            }
        }, "BtReader");
        workerThread.start();
    }

    private void sendMsg(String theNumber, String myMsg) {
        if (ActivityCompat.checkSelfPermission(this, SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS not granted; cannot forward to phone.");
            return;
        }
        if (theNumber == null || theNumber.isEmpty()) {
            Log.w(TAG, "No destination number set, sending _nonum to Teensy.");
            sendData("_nonum");
            return;
        }
        String destination = theNumber.startsWith("+") ? theNumber : "+1" + theNumber;
        try {
            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0,
                    new Intent("Message Sent"), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(this, 0,
                    new Intent("Message Delivered"), PendingIntent.FLAG_IMMUTABLE);
            SmsManager.getDefault().sendTextMessage(destination, null, myMsg,
                    sentPI, deliveredPI);
            Log.i(TAG, "SMS sent to " + destination);
        } catch (Exception e) {
            errorExit("sendTextMessage failed: " + e.getMessage());
        }
    }

    private void closeBluetooth() {
        try { if (mmInputStream != null) mmInputStream.close(); } catch (IOException ignore) { }
        try { if (outStream != null) outStream.close(); } catch (IOException ignore) { }
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignore) { }
        mmInputStream = null;
        outStream = null;
        btSocket = null;
    }

    private void startForegroundCompat(String text) {
        ensureChannel();
        Notification notif = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notif);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "BT2SMS Gateway", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BT2SMS Gateway")
                .setContentText(text)
                .setSmallIcon(R.drawable.icon)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }
}
