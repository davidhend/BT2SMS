package com.sloppingcomputing.bt2sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Manifest receiver for the system SMS_RECEIVED broadcast.  Hands the
 * extracted SMS off to {@link MyForegroundService} via
 * startForegroundService(), which is what lets the gateway keep working
 * with the activity backgrounded or after a fresh boot — Android exempts
 * SMS_RECEIVED from background-FGS-launch restrictions.
 *
 * Also fires the existing SMS_RECEIVED_ACTION intra-app broadcast so the
 * MainActivity can display the message body in its scroll view when visible.
 * Functional forwarding does not depend on that broadcast anymore.
 */
public class MessageReceiver extends BroadcastReceiver {
    private static final String TAG = "MessageReceiver";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SMS_RECEIVED.equals(intent.getAction())) {
            return;
        }
        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }

        // Concatenate every PDU so a message split into multiple SMS parts
        // reassembles in order.  The previous code only kept messages[0],
        // silently dropping any continuation.
        String format = bundle.getString("format");
        String phoneNumber = null;
        StringBuilder body = new StringBuilder();
        for (Object pduObj : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pduObj, format);
            if (msg == null) continue;
            if (phoneNumber == null) {
                phoneNumber = msg.getOriginatingAddress();
            }
            body.append(msg.getMessageBody());
        }
        String messageBody = body.toString();
        Log.i(TAG, "SMS from " + phoneNumber + " (" + messageBody.length() + " bytes)");

        // 1) Hand the data to the gateway service so it can forward to BT
        //    regardless of whether the activity is currently in the foreground.
        Intent svc = new Intent(context, MyForegroundService.class);
        svc.setAction(MyForegroundService.ACTION_SMS_RECEIVED);
        svc.putExtra(MyForegroundService.EXTRA_SMS_BODY, messageBody);
        svc.putExtra(MyForegroundService.EXTRA_SMS_FROM, phoneNumber);
        try {
            ContextCompat.startForegroundService(context, svc);
        } catch (Exception e) {
            Log.e(TAG, "Could not start gateway service: " + e.getMessage());
        }

        // 2) Also fire the intra-app broadcast purely for the UI to display
        //    the body in the message log when MainActivity is visible.
        Intent uiBroadcast = new Intent("SMS_RECEIVED_ACTION");
        uiBroadcast.setPackage(context.getPackageName());
        uiBroadcast.putExtra("message", messageBody);
        uiBroadcast.putExtra("phonenumber", phoneNumber);
        context.sendBroadcast(uiBroadcast);
    }
}
