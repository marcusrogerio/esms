/*
 *  This file is part of Ermete SMS.
 *  
 *  Ermete SMS is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  Ermete SMS is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with Ermete SMS.  If not, see <http://www.gnu.org/licenses/>.
 *  
 */

package com.googlecode.awsms.android;

import java.util.Date;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnKeyListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.awsms.R;
import com.googlecode.awsms.account.AccountConnectorAndroid;
import com.googlecode.awsms.account.AccountManagerAndroid;
import com.googlecode.awsms.message.ConversationManagerAndroid;
import com.googlecode.esms.account.Account;
import com.googlecode.esms.account.Account.Result;
import com.googlecode.esms.account.AccountManager;
import com.googlecode.esms.message.ConversationManager;
import com.googlecode.esms.message.Receiver;
import com.googlecode.esms.message.SMS;

/**
 * Performs sending in foreground or in background.
 * @author Andrea De Pasquale
 */
public class AccountService extends Service {
  // TODO change back to AsyncTask?
  
  static final String TAG = "AccountService";

  SharedPreferences preferences;
  ConversationManager conversationManager;
  NotificationManager notificationManager;
  AudioManager audioManager;
  ProgressDialog progressDialog;
  
  // prevent errors when preferences are changed during send()
  boolean background, notifications;
  
  // notification IDs
  int currentNID;
  int sendingNID;
  int successfulNID;
  int captchaNID;
  int networkNID;
  int providerNID;
  int accountNID;
  int limitNID;
  int receiverNID;
  int messageNID;

  private final IBinder binder = new AccountServiceBinder();

  public class AccountServiceBinder extends Binder {
    public AccountService getService() {
      return AccountService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onCreate() {
    preferences = PreferenceManager.getDefaultSharedPreferences(this);
    conversationManager = new ConversationManagerAndroid(this);
    notificationManager = (NotificationManager) getSystemService(
        Context.NOTIFICATION_SERVICE);
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    currentNID = (int) new Date().getTime();
  }

//  @Override
//  public void onDestroy() {
//  }

  public void login(final Account account) {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        Log.d(TAG, "call login()");
        account.login();
        Log.d(TAG, "done login()");
        return null;
      }
    }.execute();
  }

  public void send(final ComposeActivity activity, 
      final Account account, final SMS sms) {
    new AsyncTask<Void, Void, List<Result>>() {
      
      @Override
      protected void onPreExecute() {
        conversationManager.saveOutbox(sms);
        
        background = preferences.getBoolean("background_send", false);
        if (!background) progressDialog = showProgressDialog(activity);

        notifications = preferences.getBoolean("enable_notifications", true);
        if (notifications) showSendNotification(sms);
      }

      @Override
      protected List<Result> doInBackground(Void... params) {
        int attempts = 3;
        while (true) {
          Log.d(TAG, "call send(), " + attempts + " remaining");
          List<Result> results = account.send(sms);
          Log.d(TAG, "done send() with result " + results);
          AccountManager accountManager = new AccountManagerAndroid(activity);
          accountManager.update(account.getLabel(), account);
          switch (results.get(0)) {
          case PROVIDER_ERROR:
          case NETWORK_ERROR:
          case UNKNOWN_ERROR:
            if (--attempts == 0) return results;
            account.setAccountConnector(
                new AccountConnectorAndroid(activity));
            sleep(1000); // wait one second before retrying
            break;
            
          default:
            return results;
          }
        }
      }

      @Override
      protected void onPostExecute(List<Result> results) {
        if (!background && progressDialog != null) {
          progressDialog.dismiss();
          progressDialog = null;
        }

        if (notifications) {
          AccountService.this.stopForeground(true);
        }
        
        // must handle partial failures
        int firstNotSuccessful = -1;
        for (int r = 0; r < results.size(); ++r) {
          if (results.get(r) != Result.SUCCESSFUL) {
            firstNotSuccessful = r;
            break;
          }
        }
        
        if (firstNotSuccessful == -1) { // all successful
          
          conversationManager.saveSent(sms);
          if (notifications) {
            showSuccessfulNotification(sms);
          } else { // toast
            Toast.makeText(activity, 
                R.string.sending_successful_toast, 
                Toast.LENGTH_LONG).show();
          }
          
          String preference = preferences.getString("clear_message", "M");
          activity.clearFields(preference.contains("R"), preference.contains("M"));
          activity.updateCounter();
          
        } else { // some or all not successful
          
          SMS successful = successfulSMS(sms, firstNotSuccessful);
          if (successful.getReceivers().size() > 0)
            conversationManager.saveSent(successful);
          
          SMS unsuccessful = unsuccessfulSMS(sms, firstNotSuccessful);
          conversationManager.saveFailed(unsuccessful);
          
          switch (results.get(firstNotSuccessful)) {
          case CAPTCHA_NEEDED:
          case CAPTCHA_ERROR:
            showCaptchaDialog(activity, account, successful, unsuccessful);
            if (notifications) showCaptchaNotification();
            break;
            
          case NETWORK_ERROR:
          case UNKNOWN_ERROR:
            showNetworkDialog(activity, account, successful, unsuccessful);
            if (notifications) showNetworkNotification();
            break;
            
          case PROVIDER_ERROR:
            showProviderDialog(activity, account, successful, unsuccessful);
            if (notifications) showProviderNotification();
            break;
            
          case LOGIN_ERROR:
          case LOGOUT_ERROR:
          case SENDER_ERROR:
          case UNSUPPORTED_ERROR:
            showAccountDialog(activity, account, successful, unsuccessful);
            if (notifications) showAccountNotification();
            break;
            
          case LIMIT_ERROR:
            showLimitDialog(activity, account, successful, unsuccessful);
            if (notifications) showLimitNotification();
            AccountManager accountManager = new AccountManagerAndroid(activity);
            // no more messages available, limit reached
            account.setCount(account.getLimit(), new Date());
            accountManager.update(account.getLabel(), account);
            activity.updateCounter();
            break;
            
          case RECEIVER_ERROR:
            showReceiverDialog(activity, account, successful, unsuccessful);
            if (notifications) showReceiverNotification();
            break;
            
          case MESSAGE_ERROR:
            showMessageDialog(activity, account, successful, unsuccessful);
            if (notifications) showMessageNotification();
            break;
            
          default:
            break;
          }
          
        }
      }

    }.execute();
  }

  private ProgressDialog showProgressDialog(Activity activity) {
    return ProgressDialog.show(activity, "",
        getString(R.string.sending_progress_dialog), true, false);
  }

  private void showSendNotification(SMS sms) {
    sendingNID = currentNID++;
    String ticker = String.format(
        getString(R.string.sending_progress_notification),
        shortReceiverString(sms));
    Notification notification = createNotification(ticker, true);
    AccountService.this.startForeground(sendingNID, notification);
  }
  
  private void showSuccessfulNotification(SMS sms) {
    successfulNID = currentNID++;
    String ticker = String.format(
        getString(R.string.sending_successful_notification), 
        shortReceiverString(sms));
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(successfulNID, notification);
  }
  
  private void showCaptchaDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    LayoutInflater inflater = (LayoutInflater) this
        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    LinearLayout decodeLinear = (LinearLayout) inflater.inflate(
        R.layout.captcha_decode_dialog, null);
    final TextView errorText = (TextView) 
        decodeLinear.findViewById(R.id.partial_error);
    final CaptchaView captchaView = (CaptchaView) 
        decodeLinear.findViewById(R.id.captcha_image);
    final EditText captchaText = (EditText) 
        decodeLinear.findViewById(R.id.captcha_text);
    
    if (successful.getReceivers().size() > 0) {
      errorText.setText(String.format(
          getString(R.string.captcha_partial_message),
          fullReceiverString(unsuccessful)));
      errorText.setVisibility(View.VISIBLE);
    }
    
    builder.setTitle(R.string.captcha_decode_dialog);
    builder.setView(decodeLinear);
    byte[] captchaArray = unsuccessful.getCaptchaArray();
    Bitmap captchaBitmap = BitmapFactory.decodeByteArray(
        captchaArray, 0, captchaArray.length);
    BitmapDrawable captchaDrawable = new BitmapDrawable(captchaBitmap);
    captchaView.setBackgroundDrawable(captchaDrawable);
    
    builder.setOnKeyListener(new OnKeyListener() {
      @Override
      public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
          if (notifications) notificationManager.cancel(captchaNID);
          unsuccessful.setCaptchaText(captchaText.getText().toString());
          send(activity, account, unsuccessful);
          dialog.dismiss();
          return true;
        }
        return false;
      }
    });
    
    builder.setPositiveButton(R.string.ok_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(captchaNID);
            unsuccessful.setCaptchaText(captchaText.getText().toString());
            send(activity, account, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.cancel_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(captchaNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });
    
    builder.setOnCancelListener(new OnCancelListener() {
          public void onCancel(DialogInterface dialog) {
            if (notifications) notificationManager.cancel(captchaNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
    captchaText.requestFocus();
  }
  
  private void showCaptchaNotification() {
    captchaNID = currentNID++;
    String ticker = getString(R.string.captcha_decode_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(captchaNID, notification);
  }
  
  private void showNetworkDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.network_error_dialog);
    String dialogMessage = getString(R.string.network_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(networkNID);
            send(activity, account, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(networkNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showNetworkNotification() {
    networkNID = currentNID++;
    String ticker = getString(R.string.network_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(networkNID, notification);
  }
  
  private void showProviderDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.provider_error_dialog);
    String dialogMessage = getString(R.string.provider_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.retry_now,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(providerNID);
            send(activity, account, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.retry_later,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(providerNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showProviderNotification() {
    providerNID = currentNID++;
    String ticker = getString(R.string.provider_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(providerNID, notification);
  }
  
  private void showAccountDialog(final Activity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.account_error_dialog);
    String dialogMessage = getString(R.string.account_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(accountNID);
            Intent intent = new Intent(
                AccountService.this, AccountDisplayActivity.class);
            intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_SINGLE_TOP | 
                Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(accountNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showAccountNotification() {
    accountNID = currentNID++;
    String ticker = getString(R.string.account_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(accountNID, notification);
  }
  
  private void showLimitDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.limit_error_dialog);
    String dialogMessage = getString(R.string.limit_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(limitNID);
            showResendDialog(activity, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(limitNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showLimitNotification() {
    limitNID = currentNID++;
    String ticker = getString(R.string.limit_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(limitNID, notification);
  }
  
  private void showReceiverDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.receiver_error_dialog);
    String dialogMessage = getString(R.string.receiver_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(receiverNID);
            showResendDialog(activity, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(receiverNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showReceiverNotification() {
    receiverNID = currentNID++;
    String ticker = getString(R.string.receiver_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(receiverNID, notification);
  }
  
  private void showMessageDialog(final ComposeActivity activity, 
      final Account account, final SMS successful, final SMS unsuccessful) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    String dialogTitle = getString(R.string.message_error_dialog);
    String dialogMessage = getString(R.string.message_error_message);
    
    if (successful.getReceivers().size() > 0)
      dialogMessage = String.format(getString(R.string.partial_error_message),
          fullReceiverString(successful), fullReceiverString(unsuccessful)) + 
          " " + dialogMessage;
    
    builder.setTitle(dialogTitle);
    builder.setMessage(dialogMessage);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(messageNID);
            showResendDialog(activity, unsuccessful);
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (notifications) notificationManager.cancel(messageNID);
            Toast.makeText(activity, R.string.sending_canceled_toast, 
                Toast.LENGTH_LONG).show();
          }
        });

    builder.show();
  }
  
  private void showMessageNotification() {
    messageNID = currentNID++;
    String ticker = getString(R.string.message_error_notification);
    Notification notification = createNotification(ticker, false);
    notificationManager.notify(messageNID, notification);
  }
  
  private void showResendDialog(final ComposeActivity activity, final SMS sms) {
    AccountManager accountManager = new AccountManagerAndroid(this);
    final List<Account> accounts = accountManager.getAccounts();
    final CharSequence[] accountLabels = new CharSequence[accounts.size()];
    for (int a = 0; a < accounts.size(); ++a) {
      String label = accounts.get(a).getLabel();
      if (label == null || label.equals(""))
        label = getString(R.string.no_label_text);
      accountLabels[a] = label;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setTitle(R.string.account_prompt_dialog);
    builder.setSingleChoiceItems(accountLabels, -1, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int item) {
        dialog.dismiss();
        send(activity, accounts.get(item), sms);
      }
    });
    
    builder.setCancelable(true);
    builder.setOnCancelListener(new OnCancelListener() {
      public void onCancel(DialogInterface dialog) {
        Toast.makeText(activity, R.string.sending_canceled_toast, 
            Toast.LENGTH_LONG).show();
      }
    });

    builder.show();
  }
  
  /**
   * Create a simple notification
   * @param ticker The ticket text to display.
   * @return Notification to be shown.
   */
  private Notification createNotification(String ticker, boolean ongoing) {
    Notification notification = new Notification(
        R.drawable.ic_stat_notify, ticker, System.currentTimeMillis());
    Intent intent = new Intent(AccountService.this, ComposeActivity.class);
    intent.setAction(Intent.ACTION_MAIN);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
        Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    notification.setLatestEventInfo(AccountService.this,
        AccountService.this.getString(R.string.app_name), ticker,
        PendingIntent.getActivity(AccountService.this, 0, intent, 0));
    notification.sound = Uri.parse(preferences.getString(
        "notification_ringtone", "DEFAULT_RINGTONE_URI"));

    String vibrate = preferences.getString("notification_vibration", "S");
    if (vibrate.equals("A") || (vibrate.equals("S") && 
        audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE)) {
      long[] pattern = { 0, 200, 100, 100 };
      notification.vibrate = pattern;
    }

    if (ongoing) {
      notification.flags |= Notification.FLAG_ONGOING_EVENT;
      notification.flags |= Notification.FLAG_NO_CLEAR;
    } else {
      notification.flags |= Notification.FLAG_AUTO_CANCEL;
    }
    
    return notification;
  }
  
  /**
   * Convert SMS receivers to a compact string.
   * @param sms A message with some receivers.
   * @return A string with at most two receivers.
   */
  private String shortReceiverString(SMS sms) {
    String receiverString = "";
    List<Receiver> receivers = sms.getReceivers();
    switch (receivers.size()) {
    case 2:
      Receiver r1 = receivers.get(1);
      receiverString = " "+getString(R.string.and_connector)+" ";
      if (r1.getName() != null && !r1.getName().equals("")) 
        receiverString += r1.getName();
      else receiverString += r1.getNumber();
    case 1:
      Receiver r0 = receivers.get(0);
      if (r0.getName() != null && !r0.getName().equals(""))
        receiverString = r0.getName() + receiverString;
      else receiverString = r0.getNumber() + receiverString;
      break;
      
    default:
      Receiver r = receivers.get(0);
      if (r.getName() != null && !r.getName().equals("")) 
        receiverString += r.getName();
      else receiverString += r.getNumber();
      receiverString += " "+getString(R.string.and_connector)+" "+
          getString(R.string.other_connector)+" "+(receivers.size()-1);      
    }
    
    return receiverString;
  }
  
  /**
   * Convert SMS receivers to a string. 
   * @param sms A message with some receivers.
   * @return A string with all the receivers.
   */
  private String fullReceiverString(SMS sms) {
    String receiverString = "";
    List<Receiver> receivers = sms.getReceivers();
    
    Receiver fr = receivers.get(0);
    if (fr.getName() != null && !fr.getName().equals("")) 
      receiverString += fr.getName();
    else receiverString += fr.getNumber();
    
    if (receivers.size() > 1) {
      for (int i = 1; i < receivers.size()-1; ++i) {
        receiverString += ", ";
        Receiver mr = receivers.get(i);
        if (mr.getName() != null && !mr.getName().equals("")) 
          receiverString += mr.getName();
        else receiverString += mr.getNumber();
      }

      receiverString += " "+getString(R.string.and_connector)+" ";
      Receiver lr = receivers.get(receivers.size()-1);
      if (lr.getName() != null && !lr.getName().equals("")) 
        receiverString += lr.getName();
      else receiverString += lr.getNumber();
    }
    
    return receiverString;
  }

  /**
   * Split an SMS in two parts, and keep the successful. 
   * @param sms The original SMS.
   * @param where Where things went wrong.
   * @return A new SMS representing the successful part.
   */
  private SMS successfulSMS(SMS sms, int where) {
    SMS successful = sms.clone();
    List<Receiver> receivers = sms.getReceivers();
    for (int r = where; r < receivers.size(); ++r)
      successful.removeReceiver(receivers.get(r));
    return successful;
  }
  
  /**
   * Split an SMS in two parts, and keep the unsuccessful. 
   * @param sms The original SMS.
   * @param where Where things went wrong.
   * @return A new SMS representing the unsuccessful part.
   */  
  private SMS unsuccessfulSMS(SMS sms, int where) {
    SMS unsuccessful = sms.clone();
    List<Receiver> receivers = sms.getReceivers();
    for (int r = 0; r < where && r < receivers.size(); ++r)
      unsuccessful.removeReceiver(receivers.get(r));
    return unsuccessful;
  }
  
  private void sleep(int milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException e) { 
      e.printStackTrace();
    }
  }
}
