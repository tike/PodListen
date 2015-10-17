package com.einmalfel.podlisten;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** This class is intended to encapsulate preferences names and default values */
public class Preferences implements SharedPreferences.OnSharedPreferenceChangeListener {
  enum Key {
    STORAGE_PATH,
    MAX_DOWNLOADS,
    REFRESH_INTERVAL,
    SORTING_MODE,
    PLAYER_FOREGROUND,
    AUTO_DOWNLOAD,
  }

  enum AutoDownloadMode {
    ALL_NEW(R.string.auto_download_all_new),
    PLAYLIST(R.string.auto_download_playlist),
    NEVER(R.string.auto_download_never);

    private final int stringId;

    AutoDownloadMode(@StringRes int stringId) {
      this.stringId = stringId;
    }

    @Override
    public String toString() {
      return PodListenApp.getContext().getString(stringId);
    }
  }

  enum MaxDownloadsOption {
    ONE, TWO, THREE, FOUR, FIVE, TEN, UNLIMITED;

    public int toInt() {
      switch (this) {
        case TEN:
          return 10;
        case UNLIMITED:
          return Integer.MAX_VALUE;
        default:
          return ordinal() + 1;
      }
    }

    @Override
    public String toString() {
      if (this == UNLIMITED) {
        return PodListenApp.getContext().getString(R.string.preferences_max_downloads_unlimited);
      } else {
        return Integer.toString(toInt());
      }
    }
  }

  enum SortingMode {
    OLDEST_FIRST, NEWEST_FIRST, BY_FEED, SHORTEST_FIRST, LONGEST_FIRST;

    @NonNull
    public String toSql() {
      switch (this) {
        case OLDEST_FIRST:
          return Provider.K_EDATE + " ASC";
        case NEWEST_FIRST:
          return Provider.K_EDATE + " DESC";
        case BY_FEED:
          return Provider.K_EPID + " ASC";
        case SHORTEST_FIRST:
          return Provider.K_ELENGTH + " ASC";
        case LONGEST_FIRST:
          return Provider.K_ELENGTH + " DESC";
        default:
          throw new AssertionError("Unknown sorting mode");
      }
    }

    @NonNull
    public SortingMode nextCyclic() {
      int newArrayId = ordinal() == values().length - 1 ? 0 : ordinal() + 1;
      return values()[newArrayId];
    }

    /** @return at string intended to be shown in snackbar when user switches modes */
    @Override
    public String toString() {
      switch (this) {
        case OLDEST_FIRST:
          return PodListenApp.getContext().getString(R.string.sorting_mode_oldest_first);
        case NEWEST_FIRST:
          return PodListenApp.getContext().getString(R.string.sorting_mode_newest_first);
        case BY_FEED:
          return PodListenApp.getContext().getString(R.string.sorting_mode_by_feed);
        case SHORTEST_FIRST:
          return PodListenApp.getContext().getString(R.string.sorting_mode_shortest_first);
        case LONGEST_FIRST:
          return PodListenApp.getContext().getString(R.string.sorting_mode_longest_first);
        default:
          throw new AssertionError("Unknown sorting mode");
      }
    }
  }

  enum RefreshIntervalOption {
    NEVER(R.string.refresh_period_never, 0),
    HOUR(R.string.refresh_period_hour, 1),
    HOUR2(R.string.refresh_period_2hours, 2),
    HOUR3(R.string.refresh_period_3hours, 3),
    HOUR6(R.string.refresh_period_6hours, 6),
    HOUR12(R.string.refresh_period_12hours, 12),
    DAY(R.string.refresh_period_day, 24),
    DAY2(R.string.refresh_period_2days, 24 * 2),
    WEEK(R.string.refresh_period_week, 24 * 7),
    WEEK2(R.string.refresh_period_2weeks, 24 * 14),
    MONTH(R.string.refresh_period_month, 30 * 24);

    public final int periodSeconds;
    private final int stringResource;

    RefreshIntervalOption(@StringRes int stringResource, int periodHours) {
      this.periodSeconds = periodHours * 60 * 60;
      this.stringResource = stringResource;
    }

    @Override
    public String toString() {
      return PodListenApp.getContext().getString(stringResource);
    }
  }

  private static final String TAG = "PRF";
  private static final MaxDownloadsOption DEFAULT_MAX_DOWNLOADS = MaxDownloadsOption.TWO;
  private static final RefreshIntervalOption DEFAULT_REFRESH_INTERVAL = RefreshIntervalOption.DAY;
  private static final SortingMode DEFAULT_SORTING_MODE = SortingMode.OLDEST_FIRST;
  private static final AutoDownloadMode DEFAULT_DOWNLOAD_MODE = AutoDownloadMode.PLAYLIST;
  private static Preferences instance = null;

  // fields below could be changed from readPreference() only
  private MaxDownloadsOption maxDownloads;
  private Storage storage;
  private RefreshIntervalOption refreshInterval;
  private SortingMode sortingMode;
  private AutoDownloadMode autoDownloadMode;
  private boolean playerForeground; // preserve last player service state across app kill/restarts

  private final SharedPreferences sPrefs;
  private final Context context = PodListenApp.getContext();

  public static Preferences getInstance() {
    if (instance == null) {
      synchronized (Preferences.class) {
        if (instance == null) {
          instance = new Preferences();
        }
      }
    }
    return instance;
  }

  public Preferences() {
    sPrefs = PreferenceManager.getDefaultSharedPreferences(PodListenApp.getContext());
    sPrefs.registerOnSharedPreferenceChangeListener(this);
    for (Key key : Key.values()) {
      readPreference(key);
    }
  }

  private void stopDownloads(@Nullable String selection) {
    String finalSelection = Provider.K_EDID + " != 0";
    if (selection != null && !selection.isEmpty()) {
      finalSelection += " AND " + selection;
    }
    Cursor cursor = context.getContentResolver().query(
        Provider.episodeUri, new String[]{Provider.K_EDID}, finalSelection, null, null);
    if (cursor != null) {
      if (cursor.getCount() != 0) {
        long[] ids = new long[cursor.getCount()];
        int columnId = cursor.getColumnIndexOrThrow(Provider.K_EDID);
        int i = 0;
        while (cursor.moveToNext()) {
          ids[i++] = cursor.getLong(columnId);
        }

        DownloadManager dM = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        int removeResult = dM.remove(ids);
        if (removeResult != ids.length) {
          Log.e(TAG, "Failed to delete " + (ids.length - removeResult) + " downloads");
        }
        ContentValues cv = new ContentValues(1);
        cv.put(Provider.K_EDID, 0);
        cv.put(Provider.K_EDFIN, 0);
        context.getContentResolver().update(Provider.episodeUri, cv, finalSelection, null);
      }
      cursor.close();
    } else {
      Log.e(TAG, "Query failed unexpectedly", new AssertionError());
    }
  }

  /**
   * When there is some downloaded episodes on current storage and user asks to switch storage
   * - stop all running downloads
   * - stop and disable sync
   * - stop playback if not streaming (TODO)
   * - reset download progress and download ID fields
   * - remove old files
   * - ask download manager to start downloads for all non-gone episodes
   * - re-enable sync and re-run it to re-download images
   */
  private void clearStorage() {
    stopDownloads(null);

    PodlistenAccount account = PodlistenAccount.getInstance();
    account.setupSync(0);
    account.cancelRefresh();

    ContentValues cv = new ContentValues(4);
    cv.put(Provider.K_EDID, 0);
    cv.put(Provider.K_EDFIN, 0);
    cv.put(Provider.K_EDTSTAMP, 0);
    cv.put(Provider.K_EERROR, (String)null);
    context.getContentResolver().update(Provider.episodeUri, cv, null, null);

    for (File file : storage.getPodcastDir().listFiles()) {
      if (!file.delete()) {
        Log.e(TAG, "Failed to delete " + file.getAbsolutePath());
      }
    }
    for (File file : storage.getImagesDir().listFiles()) {
      if (!file.delete()) {
        Log.e(TAG, "Failed to delete " + file.getAbsolutePath());
      }
    }

    context.sendBroadcast(new Intent(DownloadReceiver.UPDATE_QUEUE_ACTION));
    account.refresh(0);
    account.setupSync(getRefreshInterval().periodSeconds);
  }

  @NonNull
  private <T extends Enum<T>> T readEnum(@NonNull Key key, @NonNull T defaultValue) {
    try {
      String pref = sPrefs.getString(key.toString(), "-1");
      int id = Integer.valueOf(pref);
      return defaultValue.getDeclaringClass().getEnumConstants()[id];
    } catch (ClassCastException | ArrayIndexOutOfBoundsException | NumberFormatException e) {
      Log.e(TAG, "Illegal enum value, reverting to default: " + defaultValue.toString(), e);
      sPrefs.edit().putString(key.toString(), Integer.toString(defaultValue.ordinal())).commit();
      return defaultValue;
    }
  }

  private synchronized void readPreference(Key key) {
    switch (key) {
      case PLAYER_FOREGROUND:
        playerForeground = sPrefs.getBoolean(Key.PLAYER_FOREGROUND.toString(), false);
        break;
      case AUTO_DOWNLOAD:
        AutoDownloadMode newM = readEnum(Key.AUTO_DOWNLOAD, DEFAULT_DOWNLOAD_MODE);
        if (newM != autoDownloadMode) {
          if (newM == AutoDownloadMode.PLAYLIST && autoDownloadMode == AutoDownloadMode.ALL_NEW) {
            stopDownloads(
                Provider.K_ESTATE + " != " + Integer.toString(Provider.ESTATE_IN_PLAYLIST));
          } else if (newM == AutoDownloadMode.NEVER) {
            stopDownloads(null);
          }
          autoDownloadMode = newM;
          context.sendBroadcast(new Intent(DownloadReceiver.UPDATE_QUEUE_ACTION));
        }
        break;
      case SORTING_MODE:
        sortingMode = readEnum(Key.SORTING_MODE, DEFAULT_SORTING_MODE);
        break;
      case MAX_DOWNLOADS:
        MaxDownloadsOption newMaxDL = readEnum(Key.MAX_DOWNLOADS, DEFAULT_MAX_DOWNLOADS);
        if (newMaxDL != maxDownloads) {
          maxDownloads = newMaxDL;
          context.sendBroadcast(new Intent(DownloadReceiver.UPDATE_QUEUE_ACTION));
        }
        break;
      case REFRESH_INTERVAL:
        RefreshIntervalOption newRI = readEnum(Key.REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL);
        if (newRI != refreshInterval) {
          refreshInterval = newRI;
          PodlistenAccount.getInstance().setupSync(refreshInterval.periodSeconds);
        }
        break;
      case STORAGE_PATH:
        String storagePreferenceString = sPrefs.getString(Key.STORAGE_PATH.toString(), "");
        if (storagePreferenceString.isEmpty()) {
          // by default, if there are removable storages use first removable, otherwise use last one
          for (Storage storageOption : Storage.getAvailableStorages()) {
            storage = storageOption;
            if (storage.isRemovable()) {
              break;
            }
          }
          if (storage != null) {
            sPrefs.edit().putString(Key.STORAGE_PATH.toString(), storage.toString()).commit();
          }
        } else {
          try {
            Storage newStorage = new Storage(new File(storagePreferenceString));
            newStorage.createSubdirs();
            if (storage != null && !storage.equals(newStorage)) {
              clearStorage();
            }
            storage = newStorage;
          } catch (IOException e) {
            Log.wtf(
                TAG, "Failed to set storage " + storagePreferenceString + ". Reverting to prev", e);
            sPrefs.edit().putString(
                Key.STORAGE_PATH.toString(), storage == null ? "" : storage.toString()).commit();
          }
        }
        break;
    }
  }

  @NonNull
  public RefreshIntervalOption getRefreshInterval() {
    return refreshInterval;
  }

  @NonNull
  public MaxDownloadsOption getMaxDownloads() {
    return maxDownloads;
  }

  @Nullable
  public Storage getStorage() {
    return storage;
  }

  @NonNull
  public SortingMode getSortingMode() {
    return sortingMode;
  }

  public void setSortingMode(SortingMode sortingMode) {
    sPrefs.edit()
          .putString(Key.SORTING_MODE.toString(), Integer.toString(sortingMode.ordinal()))
          .commit();
  }

  public void setPlayerForeground(boolean playerServicePlaying) {
    sPrefs.edit().putBoolean(Key.PLAYER_FOREGROUND.toString(), playerServicePlaying).commit();
  }

  public boolean getPlayerForeground() {
    return playerForeground;
  }

  @NonNull
  public AutoDownloadMode getAutoDownloadMode() {
    return autoDownloadMode;
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    Log.i(TAG, "Preference changed " + key + ", values: " + sharedPreferences.getAll().toString());
    readPreference(Key.valueOf(key));
  }
}
