/**
 AirCasting - Share your Air!
 Copyright (C) 2011-2012 HabitatMap, Inc.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.

 You can contact the authors by email at <info@habitatmap.org>
 */
package pl.llp.aircasting.activity;

import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatCallback;
import android.support.v7.view.ActionMode;
import pl.llp.aircasting.Intents;
import pl.llp.aircasting.R;
import pl.llp.aircasting.activity.adapter.SessionAdapter;
import pl.llp.aircasting.activity.adapter.SessionAdapterFactory;
import pl.llp.aircasting.activity.events.SessionLoadedEvent;
import pl.llp.aircasting.activity.task.CalibrateSessionsTask;
import pl.llp.aircasting.activity.task.OpenSessionTask;
import pl.llp.aircasting.helper.SelectSensorHelper;
import pl.llp.aircasting.helper.SettingsHelper;
import pl.llp.aircasting.helper.DashboardChartManager;
import pl.llp.aircasting.model.Session;
import pl.llp.aircasting.model.CurrentSessionManager;
import pl.llp.aircasting.model.ViewingSessionsManager;
import pl.llp.aircasting.receiver.SyncBroadcastReceiver;
import pl.llp.aircasting.storage.db.UncalibratedMeasurementCalibrator;
import pl.llp.aircasting.storage.repository.SessionRepository;

import android.app.Application;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;
import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import roboguice.inject.InjectView;

import java.util.List;

public class SessionsActivity extends RoboListActivityWithProgress implements AppCompatCallback
{
  @Inject SessionAdapterFactory sessionAdapterFactory;
  @Inject SelectSensorHelper selectSensorHelper;
  @Inject SessionRepository sessionRepository;
  @Inject DashboardChartManager chartManager;
  @Inject CurrentSessionManager currentSessionManager;
  @Inject ViewingSessionsManager viewingSessionsManager;
  @Inject SettingsHelper settingsHelper;
  @Inject Application context;
  @Inject ApplicationState state;
  @Inject EventBus eventBus;

  @Inject UncalibratedMeasurementCalibrator calibrator;

  @InjectView(R.id.sessions_swipe_refresh_layout) SwipeRefreshLayout swipeRefreshLayout;

  @Inject SyncBroadcastReceiver syncBroadcastReceiver;

  BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      refreshList();
    }
  };

  private SessionAdapter sessionAdapter;
  private long sessionId;
  private boolean calibrationAttempted;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    calibrateOldRecords();
    setContentView(R.layout.sessions);

    swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
      @Override
      public void onRefresh() {
        Intents.triggerSync(SessionsActivity.this);
        refreshList();
      }
    });

    getDelegate().onCreate(savedInstanceState);
    initToolbar("Sessions");
    initNavigationDrawer();
  }

  @Override
  protected void onResume() {
    super.onResume();

    refreshList();

    IntentFilter filter = new IntentFilter();
    filter.addAction(Intents.ACTION_SYNC_UPDATE);
    registerReceiver(broadcastReceiver, filter);

    registerReceiver(syncBroadcastReceiver, SyncBroadcastReceiver.INTENT_FILTER);
    eventBus.register(this);
  }

  private void refreshItems()
  {
     List<Session> sessions = sessionRepository.notDeletedSessions();

    if (sessionAdapter == null) {
      sessionAdapter = sessionAdapterFactory.getSessionAdapter(this);
      setListAdapter(sessionAdapter);
    }

    sessionAdapter.setSessions(sessions);
    swipeRefreshLayout.setRefreshing(false);
  }

  @Override
  protected void onPause() {
    super.onPause();

    unregisterReceiver(broadcastReceiver);
    unregisterReceiver(syncBroadcastReceiver);
    eventBus.unregister(this);
  }

  private void refreshList()
  {
    runOnUiThread(new Runnable()
    {
      @Override
      public void run()
      {
        refreshItems();
      }
    });
  }

  @Override
  protected void onListItemClick(ListView listView, View view, int position, long id) {
    Session s = sessionAdapter.getSession(position);
    sessionId = s.getId();
    Intent intent;

    if (s.isFixed())
      intent = new Intent(this, OpenFixedSessionActivity.class);
    else
      intent = new Intent(this, OpenMobileSessionActivity.class);

    startActivityForResult(intent, 0);
  }

  @Override
  public void onProfileClick(View view) {
    super.onProfileClick(view);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (resultCode) {
      case R.id.view:
        viewSession(sessionId);
        break;
      case R.id.delete_session:
        deleteSession(sessionId);
        break;
      case R.id.edit:
        editSession(sessionId);
        break;
      case R.id.save_button:
        updateSession(data);
        break;
      case R.id.share:
        Intents.shareSession(this, sessionId);
        break;
      case R.id.continue_streaming:
        continueAircastingSession(sessionId);
        finish();
        break;
    }
  }

  private void updateSession(Intent data) {
    Session session = Intents.editSessionResult(data);

    sessionRepository.update(session);
    Intents.triggerSync(context);

    refreshList();
  }

  private void continueAircastingSession(long id) {
    Session session = sessionRepository.loadShallow(id);
    currentSessionManager.continueStreamingSession(session, true);
  }

  private void editSession(long id) {
    Session session = sessionRepository.loadShallow(id);
    Intents.editSession(this, session);
  }

  private void deleteSession(long id) {
    sessionRepository.markSessionForRemoval(id);

    refreshList();
  }

  private void viewSession(long id) {
    new OpenSessionTask(this) {
      @Override
      protected Session doInBackground(Long... longs) {
        viewingSessionsManager.loadSessionForViewing(longs[0], this);

        return null;
      }

      @Override
      protected void onPostExecute(Session session) {
        super.onPostExecute(session);

        chartManager.resetAllStaticCharts();
        startSessionView();
      }
    }.execute(id);
  }

  private void startSessionView() {
    eventBus.post(new SessionLoadedEvent(currentSessionManager.getCurrentSession()));
    Intents.startDashboardActivity(this, true);
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    switch (id) {
      case SelectSensorHelper.DIALOG_ID:
        return selectSensorHelper.chooseSensor(this);
      default:
        return super.onCreateDialog(id);
    }
  }

  @Override
  public void onSupportActionModeStarted(ActionMode mode) { }

  @Override
  public void onSupportActionModeFinished(ActionMode mode) { }

  @android.support.annotation.Nullable
  @Override
  public ActionMode onWindowStartingSupportActionMode(ActionMode.Callback callback) {
    return null;
  }

  private void calibrateOldRecords()
  {
    if(calibrationAttempted)
      return;

    calibrationAttempted = true;
    if(calibrator.sessionsToCalibrate() > 0)
    {
      CalibrateSessionsTask task = new CalibrateSessionsTask(this, calibrator);
      task.execute();
    }
  }
}
