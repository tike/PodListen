package com.einmalfel.podlisten;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;

public class PodlistenAccount {
  private static PodlistenAccount instance;

  private final String appId;
  // cannot derive from Account - instance will be send to sync framework in form of parcel
  private final Account account;

  private PodlistenAccount(@NonNull Context context) {
    AccountManager accManager = (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
    appId = context.getResources().getString(R.string.app_id);
    account = new Account(appId, appId);
    accManager.addAccountExplicitly(account, null, null);
  }

  public static PodlistenAccount getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (PodlistenAccount.class) {
        if (instance == null) {
          instance = new PodlistenAccount(context);
        }
      }
    }
    return instance;
  }

  /**
   * @param feedId ID of feed to sync. If zero is given, will request sync for all feeds
   */
  void refresh(long feedId) {
    Bundle settingsBundle = new Bundle();
    settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
    settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
    settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
    settingsBundle.putLong(EpisodesSyncAdapter.FEED_ID_EXTRA_OPTION, feedId);
    ContentResolver.requestSync(account, appId, settingsBundle);
  }

  void cancelRefresh() {
    ContentResolver.cancelSync(account, appId);
  }

  /**
   * @param pollPeriod sync interval in seconds. Pass zero to disable periodic sync
   */
  void setupSync(int pollPeriod) {
    if (pollPeriod == 0) {
      ContentResolver.removePeriodicSync(account, appId, Bundle.EMPTY);
      ContentResolver.setSyncAutomatically(account, appId, false);
    } else {
      ContentResolver.addPeriodicSync(account, appId, Bundle.EMPTY, pollPeriod);
      ContentResolver.setSyncAutomatically(account, appId, true);
    }
  }
}
