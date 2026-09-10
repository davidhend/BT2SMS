package com.sloppingcomputing.bt2sms;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.RECEIVE_SMS;
import static android.Manifest.permission.SEND_SMS;
import static android.Manifest.permission_group.SMS;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity
        implements MyForegroundService.Callback {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSIONS = 200;

    private MyForegroundService myService;
    private boolean isBound = false;
    private IntentFilter intentFilter;
    private IntentFilter btFilter;

    /** One persistent ServiceConnection.  The old code created a fresh
     *  connection on every checkIfConnected() call and never unbound,
     *  leaking bindings on every foreground/background cycle. */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MyForegroundService.LocalBinder binder =
                    (MyForegroundService.LocalBinder) service;
            myService = binder.getService();
            myService.setCallback(MainActivity.this);
            isBound = true;
            refreshConnectionStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            myService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        SharedPreferences prefs = getSharedPreferences(
                MyForegroundService.PREFS, MODE_PRIVATE);

        // Restore MAC.
        String macAddress = prefs.getString(MyForegroundService.PREF_MAC,
                "20:14:04:16:30:52");
        EditText macBox = findViewById(R.id.macText);
        macBox.setText(macAddress);
        macBox.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ENTER) {
                saveMacAddress();
                return true;
            }
            return false;
        });

        // Restore phone number — previously this was only kept in memory and
        // re-prompted on every launch.
        EditText phoneNumberText = findViewById(R.id.telephoneNumber);
        String savedNumber = prefs.getString(MyForegroundService.PREF_NUMBER, "");
        phoneNumberText.setText(savedNumber);
        phoneNumberText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ENTER) {
                String phoneNumber = phoneNumberText.getText().toString();
                prefs.edit()
                        .putString(MyForegroundService.PREF_NUMBER, phoneNumber)
                        .apply();
                if (isBound && myService != null) {
                    myService.doSomethingInService(phoneNumber);
                }
                Toast.makeText(this, "Number saved", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        intentFilter = new IntentFilter("SMS_RECEIVED_ACTION");
        btFilter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);

        findViewById(R.id.button).setOnClickListener(v -> {
            if (isBound && myService != null) {
                myService.establishBluetoothConnection();
                refreshConnectionStatus();
            }
        });

        // Kick off the permission flow.  startGatewayService() will run once
        // perms are answered, or immediately if already granted.
        if (hasAllPermissions()) {
            startGatewayService();
        } else {
            requestPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // API 33 added the registerReceiver(receiver, filter, flag) overload
        // and the RECEIVER_NOT_EXPORTED constant.  minSdk is 28, so we have
        // to branch — the old code's @RequiresApi annotation suppressed lint
        // but the call would have NoSuchMethodError'd on Android 9-12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(intentReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(intentReceiver, intentFilter);
        }
        registerReceiver(btReceiver, btFilter);
        // The service is started in onCreate (once perms are granted), but
        // re-binding on resume is fine — bindService is a no-op if already bound.
        if (!isBound && hasAllPermissions()) {
            bindService(new Intent(this, MyForegroundService.class),
                    serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            refreshConnectionStatus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Symmetrical unregister — the old code only unregistered intentReceiver
        // on the FIRST pause (and unregistered btReceiver in onPause but never
        // re-registered it on resume, so it was gone after the first cycle).
        try { unregisterReceiver(intentReceiver); } catch (IllegalArgumentException ignore) { }
        try { unregisterReceiver(btReceiver); } catch (IllegalArgumentException ignore) { }
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            try { unbindService(serviceConnection); } catch (IllegalArgumentException ignore) { }
            isBound = false;
        }
        super.onDestroy();
    }

    private void startGatewayService() {
        Intent svc = new Intent(this, MyForegroundService.class);
        ContextCompat.startForegroundService(this, svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void refreshConnectionStatus() {
        EditText connectionBox = findViewById(R.id.connectionStatus);
        if (isBound && myService != null && myService.isConnected()) {
            connectionBox.setText("connected");
        } else {
            connectionBox.setText("disconnected");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        // Whatever the user picked, log it and try to bring up the service.
        // The service itself fails gracefully when a needed permission is
        // missing and surfaces the reason in its notification.
        if (hasAllPermissions()) {
            startGatewayService();
        } else {
            Log.w(TAG, "Some permissions were denied; gateway may be limited.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && shouldShowRequestPermissionRationale(SMS)) {
                showMessageOKCancel(
                        "You need to allow access to all the permissions",
                        (d, w) -> requestPermission());
            } else {
                // Still start the service — it'll show "permission missing"
                // in its notification, and the user can grant later.
                startGatewayService();
            }
        }
    }

    private boolean hasAllPermissions() {
        Context ctx = getApplicationContext();
        boolean ok = ContextCompat.checkSelfPermission(ctx, SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = ok && ContextCompat.checkSelfPermission(ctx, POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ok;
    }

    private void requestPermission() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{SEND_SMS, RECEIVE_SMS, BLUETOOTH_SCAN,
                    BLUETOOTH_CONNECT, POST_NOTIFICATIONS};
        } else {
            perms = new String[]{SEND_SMS, RECEIVE_SMS, BLUETOOTH_SCAN,
                    BLUETOOTH_CONNECT};
        }
        ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
    }

    private void showMessageOKCancel(String message,
                                     DialogInterface.OnClickListener okListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("OK", okListener)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    public void saveMacAddress() {
        String userData = ((EditText) findViewById(R.id.macText)).getText().toString();
        if (!userData.matches("^([0-9A-F]{2}[:]){5}([0-9A-F]{2})$")) {
            Toast.makeText(this, "Invalid MAC format (expect AA:BB:CC:DD:EE:FF)",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(MyForegroundService.PREFS, MODE_PRIVATE)
                .edit()
                .putString(MyForegroundService.PREF_MAC, userData)
                .apply();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        // Reconnect so the new MAC is picked up.
        if (isBound && myService != null) {
            myService.establishBluetoothConnection();
            refreshConnectionStatus();
        }
    }

    @Override
    public void onServiceResponse(String result) {
        Log.d("onServiceResponse:", result);
    }

    /**
     * Only updates the UI text log.  The actual SMS->BT forwarding now
     * happens in the service (kicked off by MessageReceiver), so it works
     * even when this activity isn't in the foreground.
     */
    private final BroadcastReceiver intentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"SMS_RECEIVED_ACTION".equals(intent.getAction())) return;
            String messageData = intent.getStringExtra("message");
            if (messageData == null) return;
            TextView inTxt = findViewById(R.id.textMsg);
            inTxt.append(messageData + '\n');
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                Log.d("btReceiver", "Disconnected");
                EditText connectionBox = findViewById(R.id.connectionStatus);
                connectionBox.setText("disconnected");
            }
        }
    };
}
