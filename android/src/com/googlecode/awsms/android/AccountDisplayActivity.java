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

import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.awsms.R;
import com.googlecode.awsms.account.AccountManagerAndroid;
import com.googlecode.esms.account.Account;
import com.googlecode.esms.account.AccountManager;

/**
 * Show a list of existing accounts.
 * @author Andrea De Pasquale
 */
public class AccountDisplayActivity extends Activity {

  AccountManager accountManager;

  LinearLayout accountsLinear;
  Button backButton, createButton;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.account_display_activity);

    accountManager = new AccountManagerAndroid(AccountDisplayActivity.this);
    accountsLinear = (LinearLayout) findViewById(R.id.accounts_linear);
    refreshAccountsList();
    
    backButton = (Button) findViewById(R.id.back_button);
    backButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        AccountDisplayActivity.this.finish();
      }
    });
    
    createButton = (Button) findViewById(R.id.create_button);
    createButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        startActivity(new Intent(
            AccountDisplayActivity.this,
            AccountCreateActivity.class));
      }
    });
  }

  private void refreshAccountsList() {
    accountsLinear.removeAllViews();
    List<Account> accounts = accountManager.getAccounts();
    
    for (final Account account : accounts) {
      View listItem = getLayoutInflater().inflate(
          R.layout.account_display_list_item, null);
      LinearLayout listItemLinear = (LinearLayout) listItem
          .findViewById(R.id.list_item_linear);
      TextView listItemLabel = (TextView) listItem
          .findViewById(R.id.list_item_label);
      TextView listItemSender = (TextView) listItem
          .findViewById(R.id.list_item_sender);
      ImageView listItemLogo = (ImageView) listItem
          .findViewById(R.id.list_item_logo);

      String label = account.getLabel();
      if (label == null || label.equals(""))
        label = getString(R.string.no_label_text);
      listItemLabel.setText(label);
      listItemSender.setText(account.getSender());
      
      listItemLogo.setImageBitmap(
          AccountBitmap.getLogo(account, getResources()));
      
      listItemLinear.setOnClickListener(new OnClickListener() {
        public void onClick(View v) {
          showModifyActivity(account);
        }
      });

      listItemLinear.setOnLongClickListener(new OnLongClickListener() {
        public boolean onLongClick(View v) {
          showAccountDialog(account);
          return true;
        }
      });

      accountsLinear.addView(listItem);
    }
    
    if (accounts.size() == 0) {
      View listEmpty = getLayoutInflater().inflate(
          R.layout.account_display_list_empty, null);
      accountsLinear.addView(listEmpty);
    }

    accountsLinear.invalidate();
  }

  private void showAccountDialog(final Account account) {
    AlertDialog.Builder builder = new AlertDialog.Builder(
        AccountDisplayActivity.this);

    Resources resources = getResources();
    CharSequence[] items = resources.getTextArray(R.array.account_dialog);

    String label = account.getLabel();
    if (label == null || label.equals(""))
      label = getString(R.string.no_label_text);

    builder.setTitle(label);
    builder.setItems(items, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int item) {
        switch (item) {
        case 0: // rename
          showRenameDialog(account);
          break;

        case 1: // modify
          showModifyActivity(account);
          break;

        case 2: // delete
          showDeleteDialog(account);
          break;
        }
      }
    });

    builder.show();
  }

  private void showRenameDialog(final Account account) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);

    LayoutInflater inflater = (LayoutInflater) this
        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    LinearLayout renameLinear = (LinearLayout) inflater.inflate(
        R.layout.account_rename_dialog, null);
    final EditText labelText = (EditText) renameLinear
        .findViewById(R.id.label_text);

    String label = account.getLabel();
    labelText.setText(label);

    if (label == null || label.equals(""))
      label = getString(R.string.no_label_text);

    builder.setTitle(label);
    builder.setView(renameLinear);

    builder.setPositiveButton(R.string.rename_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            String oldLabel = account.getLabel();
            String newLabel = labelText.getText().toString().trim();
            if (newLabel.equalsIgnoreCase(oldLabel)) return;
            
            for (Account a : accountManager.getAccounts())
              if (a.getLabel().equalsIgnoreCase(newLabel)) {
                Toast.makeText(AccountDisplayActivity.this, 
                    R.string.existing_label_toast, Toast.LENGTH_SHORT).show();
                showRenameDialog(account);
                return;
              }
            
            account.setLabel(newLabel);
            accountManager.update(oldLabel, account);
            refreshAccountsList();
          }
        });

    builder.setNegativeButton(R.string.cancel_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            // do nothing
          }
        });

    builder.show();
    labelText.setSelection(0, labelText.length());
    labelText.requestFocus();
  }

  private void showModifyActivity(Account account) {
    Intent intent = new Intent(AccountDisplayActivity.this,
        AccountModifyActivity.class);
    intent.setAction(AccountIntents.DO_AUTHENTICATION);
    intent.putExtra(AccountIntents.NEW_ACCOUNT, account);
    intent.putExtra(AccountIntents.OLD_ACCOUNT, account);
    startActivity(intent);
  }

  private void showDeleteDialog(final Account account) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);

    String label = account.getLabel();
    if (label == null || label.equals(""))
      label = getString(R.string.no_label_text);

    builder.setTitle(label);
    builder.setMessage(R.string.account_delete_message);

    builder.setPositiveButton(R.string.yes_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            accountManager.delete(account);
            refreshAccountsList();
          }
        });

    builder.setNegativeButton(R.string.no_button,
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            // do nothing
          }
        });

    builder.show();
  }
}
