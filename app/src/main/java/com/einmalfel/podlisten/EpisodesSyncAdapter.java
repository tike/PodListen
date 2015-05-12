package com.einmalfel.podlisten;


import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.code.rome.android.repackaged.com.sun.syndication.feed.synd.SyndEnclosure;
import com.google.code.rome.android.repackaged.com.sun.syndication.feed.synd.SyndEntry;
import com.google.code.rome.android.repackaged.com.sun.syndication.feed.synd.SyndFeed;
import com.google.code.rome.android.repackaged.com.sun.syndication.io.FeedException;
import com.google.code.rome.android.repackaged.com.sun.syndication.io.SyndFeedInput;
import com.google.code.rome.android.repackaged.com.sun.syndication.io.XmlReader;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;


public class EpisodesSyncAdapter extends AbstractThreadedSyncAdapter {
  private static final String TAG = "ESA";
  private static final String[] P_PROJECTION = {Provider.K_PFURL, Provider.K_ID, Provider.K_PSTATE};
  private static final Pattern AUDIO_PATTERN = Pattern.compile("^audio/\\w*");
  private static final int NEW_SUBSCRIPTION_LIMIT = 2; // limit episodes to add for feeds TODO option

  public EpisodesSyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);
  }

  @Override
  public void onPerformSync(Account account, Bundle extras, String authority,
                            ContentProviderClient provider, SyncResult syncResult) {
    Log.i(TAG, "Starting sync..");

    // Get podcasts cursor
    Cursor c = null;
    try {
      c = provider.query(Provider.podcastUri, P_PROJECTION, null, null, Provider.K_PSTATE);
    } catch (RemoteException e) {
      Log.e(TAG, "Podcast provider query failed with remote exception " + e);
      syncResult.databaseError = true;
    }
    if (c == null) {
      return;
    }
    if (c.getCount() < 1) {
      c.close();
      return;
    }

    // ROME-for-android doesn't work with system class loader which is used by default
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

    //setup notification
    Intent intent = new Intent(getContext(), MainActivity.class);
    Bundle opts = new Bundle();
    opts.putInt(MainActivity.PAGE_LAUNCH_OPTION, MainActivity.Pages.NEW_EPISODES.ordinal());
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pendingIntent =
        PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, opts);
    NotificationManager nm = (NotificationManager) getContext().getSystemService(
        Context.NOTIFICATION_SERVICE);
    Notification.Builder nb = new Notification.Builder(getContext())
        .setSmallIcon(R.mipmap.ic_sync_green_24dp)
        .setContentTitle("Podlisten refreshing")
        .setOngoing(true)
        .setProgress(c.getCount(), 0, false);
        .setContentIntent(pendingIntent);
    nm.notify(0, nb.build());

    // For each podcast launch rss parsing on its feed URL
    try {
      int furlIndex = c.getColumnIndexOrThrow(Provider.K_PFURL);
      int idIndex = c.getColumnIndexOrThrow(Provider.K_ID);
      int stateIndex = c.getColumnIndexOrThrow(Provider.K_PSTATE);
      int count = 0;
      if (c.moveToFirst()) {
        do {
          long id = c.getLong(idIndex);
          String feedUrl = c.getString(furlIndex);
          int state = c.getInt(stateIndex);
          try {
            count += loadFeed(feedUrl, id, provider, state == Provider.PSTATE_NEW);
          } catch (IOException e) {
            Log.e(TAG, "IO error while loading feed, skipping. " + feedUrl + " Exception: " + e);
            syncResult.stats.numIoExceptions++;
          } catch (FeedException e) {
            Log.e(TAG, "Feed error while loading feed, skipping. " + feedUrl + " Exception: " + e);
            syncResult.stats.numIoExceptions++;
          }
          nb.setProgress(c.getCount(), c.getPosition() + 1, false);
          if (count > 0) {
            nb.setContentText(Integer.toString(count) + " new episode(s)");
          }
          nm.notify(0, nb.build());
        } while (c.moveToNext());
      }
      if (count == 0) {
        if (syncResult.stats.numIoExceptions == c.getCount()) {
          nb.setContentText("Refresh failed");
        } else {
          nb.setContentText("No new episodes");
        }
      }
    } catch (RemoteException re) {
      Log.e(TAG, "Content provider error " + re);
      syncResult.databaseError = true;
      nb.setContentText("DB error while loading episodes");
    } finally {
      c.close();
      nb.setOngoing(false)
          .setProgress(0,0,false)
          .setContentTitle("Podlisten refreshed");
      nm.notify(0, nb.build());
    }
  }

  private static int countNewInDB(ContentProviderClient cpc) throws RemoteException {
    Cursor cursorBefore = cpc.query(Provider.episodeUri, null, Provider.K_ESTATE + " = ?",
        new String[]{Integer.toString(Provider.ESTATE_NEW)}, null);
    int count = cursorBefore.getCount();
    cursorBefore.close();
    return count;
  }

  private int loadFeed(String url, long pid, ContentProviderClient cpc, boolean newSubscription)
      throws IOException, RemoteException, FeedException {
    Log.i(TAG, "Refreshing " + url);
    SyndFeedInput input = new SyndFeedInput();
    SyndFeed feed = input.build(new XmlReader(new URL(url)));

    updatePodcastInfo(pid, cpc, feed);

    int countBefore = countNewInDB(cpc);

    // insert every episode
    @SuppressWarnings("unchecked")
    List<SyndEntry> entries = feed.getEntries();
    StringBuilder b = new StringBuilder("");
    int count = 0;
    for (SyndEntry entry : entries) {
      Long id = tryInsertEntry(entry, pid, cpc, newSubscription && count >= NEW_SUBSCRIPTION_LIMIT);
      if (id != null) {
        b.append(id);
        b.append(',');
        count++;
      }
    }
    b.deleteCharAt(b.lastIndexOf(","));

    int countAfter = countNewInDB(cpc);

//    //cleanup episodes which are both not interesting for user (ESTATE_GONE) and absent in feed
//    String presentInFeed = b.toString();
//    try {
//      //TODO check if this actually works
//      cpc.delete(Provider.episodeUri, Provider.K_ESTATE + "=? AND " + Provider.K_ID + " NOT IN (?)",
//          new String[]{Integer.toString(Provider.ESTATE_GONE), presentInFeed});
//    } catch (RemoteException ignore) {
//    }

    return countAfter - countBefore;
  }

  private static void updatePodcastInfo(long id, ContentProviderClient cpc, SyndFeed feed)
      throws RemoteException {
    ContentValues values = new ContentValues();
    putStringIfNotNull(values, Provider.K_PURL, feed.getLink());
    putStringIfNotNull(values, Provider.K_PNAME, feed.getTitle());
    putStringIfNotNull(values, Provider.K_PDESCR, feed.getDescription());
    //TODO check if image file exists and download it
    values.put(Provider.K_PSTATE, Provider.PSTATE_SEEN_ONCE);
    int updated = cpc.update(Provider.getUri(Provider.T_PODCAST, id), values, null, null);
    if (updated != 1) {
      Log.e(TAG, "Unexpected number of items updated " + updated + " id " + id);
    }
  }

  private Long tryInsertEntry(SyndEntry entry, long pid, ContentProviderClient cpc, boolean gone)
      throws RemoteException {
    String audioLink = extractAudio(entry.getEnclosures());
    if (audioLink == null) {
      Log.i(TAG, entry.getTitle() + " has no audio attachment, skipping");
      return null;
    }

    long id = (long) audioLink.hashCode() - Integer.MIN_VALUE;
    //TODO this is suboptimal. Will later check existence of episodes in a bunch query
    Cursor c = cpc.query(Provider.getUri(Provider.T_EPISODE, id), null, null, null, null);
    boolean newEpisode = c == null || c.isAfterLast();
    if (c != null) {
      c.close();
    }

    if (newEpisode) {//episode is not yet in db
      String title = entry.getTitle();
      if (title == null) {
        title = "NO TITLE";
      }

      ContentValues values = new ContentValues();
      putStringIfNotNull(values, Provider.K_ENAME, title);
      putStringIfNotNull(values, Provider.K_EAURL, audioLink);
      putStringIfNotNull(values, Provider.K_EDESCR, entry.getDescription().getValue());
      putStringIfNotNull(values, Provider.K_EURL, entry.getLink());
      values.put(Provider.K_EDATT, 0);
      values.put(Provider.K_EDFIN, 0);
      Date date = entry.getPublishedDate();
      if (date != null) {
        values.put(Provider.K_EDATE, date.getTime());
      }
      values.put(Provider.K_EPID, pid);
      values.put(Provider.K_ID, id);
      values.put(Provider.K_ESTATE, gone ? Provider.ESTATE_GONE : Provider.ESTATE_NEW);
      cpc.insert(Provider.episodeUri, values);

      if (!gone) {
        Log.d(TAG, "New episode! " + title);
        Intent bi = new Intent(DownloadStartReceiver.NEW_EPISODE_INTENT);
        bi.putExtra(DownloadStartReceiver.URL_EXTRA_NAME, audioLink);
        bi.putExtra(DownloadStartReceiver.TITLE_EXTRA_NAME, title);
        bi.putExtra(DownloadStartReceiver.ID_EXTRA_NAME, id);
        getContext().sendBroadcast(bi);
      }
    }

    return id;
  }

  private static String extractAudio(List enclosures) {
    for (Object o : enclosures) {
      SyndEnclosure enclosure = (SyndEnclosure) o;
      String audioLink = enclosure.getUrl();
      if (AUDIO_PATTERN.matcher(enclosure.getType()).matches() && audioLink != null) {
        return audioLink;
      }
    }
    return null;
  }

  private static void putStringIfNotNull(ContentValues values, String key, String s) {
    if (s != null) {
      values.put(key, s);
    }
  }
}
