package com.microsoft.mimicker.ringing;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.microsoft.mimicker.R;
import com.microsoft.mimicker.mimics.MimicFactory;
import com.microsoft.mimicker.model.Alarm;
import com.microsoft.mimicker.model.AlarmList;
import com.microsoft.mimicker.scheduling.AlarmScheduler;
import com.microsoft.mimicker.settings.AlarmSettingsFragment;
import com.microsoft.mimicker.settings.MimicsPreference;
import com.microsoft.mimicker.settings.MimicsSettingsFragment;
import com.microsoft.mimicker.utilities.GeneralUtilities;

import java.util.ArrayList;
import java.util.UUID;

public class AlarmRingingActivity extends AppCompatActivity
        implements MimicFactory.MimicResultListener,
        ShareFragment.ShareResultListener,
        AlarmRingingFragment.RingingResultListener,
        AlarmSnoozeFragment.SnoozeResultListener,
        AlarmNoMimicsFragment.NoMimicResultListener,
        AlarmSettingsFragment.AlarmSettingsListener,
        MimicsSettingsFragment.MimicsSettingsListener {


    private static final int ALARM_DURATION_INTEGER = (2 * 60 * 60) * 1000;
    public final String TAG = this.getClass().getSimpleName();
    private Alarm mAlarm;
    private Fragment mAlarmRingingFragment;
    private Handler mHandler;
    private Runnable mAlarmCancelTask;
    private boolean mAlarmTimedOut;
    private AlarmRingingService mRingingService;
    private boolean mIsServiceBound;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to an explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mRingingService = ((AlarmRingingService.LocalBinder)service).getService();
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            mRingingService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UUID alarmId = (UUID) getIntent().getSerializableExtra(AlarmScheduler.ARGS_ALARM_ID);
        mAlarm = AlarmList.get(this).getAlarm(alarmId);

        Log.d(TAG, "Creating activity!");

        // This call must be made before setContentView to avoid the view being refreshed
        GeneralUtilities.setLockScreenFlags(getWindow());

        setContentView(R.layout.activity_fragment);

        mAlarmRingingFragment = AlarmRingingFragment.newInstance(mAlarm.getId().toString());

        // We do not want any animations when the ringing fragment is launched for the first time
        GeneralUtilities.showFragment(getSupportFragmentManager(),
                mAlarmRingingFragment,
                AlarmRingingFragment.RINGING_FRAGMENT_TAG);

        mAlarmCancelTask = new Runnable() {
            @Override
            public void run() {
                mAlarmTimedOut = true;
                if (!isGameRunning()) {
                    finishActivity();
                }
            }
        };
        mHandler = new Handler();
        int ringingDuration = getAlarmRingingDuration();
        if (ringingDuration > 0) {
            mHandler.postDelayed(mAlarmCancelTask, ringingDuration);
        }

        bindRingingService();
    }

    @Override
    public void onMimicSuccess(String shareable) {
        mAlarm.onDismiss();
        cancelAlarmTimeout();
        if (shareable != null && shareable.length() > 0) {
            GeneralUtilities.showFragmentFromRight(getSupportFragmentManager(),
                    ShareFragment.newInstance(shareable),
                    ShareFragment.SHARE_FRAGMENT_TAG);
        } else {
            finishActivity();
        }
    }

    @Override
    public void onMimicFailure() {
        if (mAlarmTimedOut) {
            finishActivity();
        } else {
            transitionBackToRingingScreen();
        }
    }

    @Override
    public void onShareCompleted() {
        finishActivity();
    }

    @Override
    public void onRequestLaunchShareAction() {
        notifyControllerAllowDismiss();
    }

    @Override
    public void onRingingDismiss() {
        notifyControllerSilenceAlarmRinging();
        Fragment mimicFragment = MimicFactory.getMimicFragment(this, mAlarm.getId());
        if (mimicFragment != null) {
            GeneralUtilities.showFragmentFromRight(getSupportFragmentManager(),
                    mimicFragment, MimicFactory.MIMIC_FRAGMENT_TAG);
        } else {
            mAlarm.onDismiss();
            cancelAlarmTimeout();
            GeneralUtilities.showFragmentFromRight(getSupportFragmentManager(),
                    AlarmNoMimicsFragment.newInstance(mAlarm.getId().toString()),
                    AlarmNoMimicsFragment.NO_MIMICS_FRAGMENT_TAG);
        }
    }

    @Override
    public void onRingingSnooze() {
        notifyControllerSilenceAlarmRinging();
        cancelAlarmTimeout();
        mAlarm.snooze();
        // Show the snooze user interface
        GeneralUtilities.showFragmentFromLeft(getSupportFragmentManager(),
                new AlarmSnoozeFragment(),
                AlarmSnoozeFragment.SNOOZE_FRAGMENT_TAG);
    }

    @Override
    public void onSnoozeDismiss() {
        finishActivity();
    }

    @Override
    public void onNoMimicDismiss(boolean launchSettings) {
        if (launchSettings) {
            GeneralUtilities.showFragmentFromRight(getSupportFragmentManager(),
                    MimicsSettingsFragment.newInstance(
                            MimicsPreference.getEnabledMimics(this, mAlarm)),
                    MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG);
        } else {
            finishActivity();
        }
    }

    @Override
    public void onSettingsSaveOrIgnoreChanges() {
        finishActivity();
    }

    @Override
    public void onSettingsDeleteOrNewCancel() {
        finishActivity();
    }

    @Override
    public void onMimicsSettingsDismiss(ArrayList<String> enabledMimics) {
        // If Mimics settings was launched from Alarm settings just update Alarm settings,
        // otherwise we need to launch Alarm settings
        AlarmSettingsFragment settingsFragment = (AlarmSettingsFragment)getSupportFragmentManager()
                .findFragmentByTag(AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG);
        if (settingsFragment != null){
            settingsFragment.updateMimicsPreference(enabledMimics);
        } else {
            GeneralUtilities.showFragmentFromLeft(getSupportFragmentManager(),
                    AlarmSettingsFragment.newInstance(mAlarm.getId().toString(), enabledMimics),
                    AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG);
        }
    }

    @Override
    public void onShowMimicsSettings(ArrayList<String> enabledMimics) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_left, R.anim.slide_out_right);
        transaction.replace(R.id.fragment_container, MimicsSettingsFragment.newInstance(enabledMimics),
                MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "Entered onResume!");

        GeneralUtilities.registerCrashReport(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Entered onPause!");
        notifyControllerRingingDismissed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Entered onDestroy!");
        unbindRingingService();
    }

    @Override
    public void onBackPressed() {
        if (isGameRunning()) {
            transitionBackToRingingScreen();
        } else if (areEditingSettings()) {
            if (areEditingAlarmSettings()) {
                ((AlarmSettingsFragment) getSupportFragmentManager()
                        .findFragmentByTag(AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG))
                        .onCancel();
            } else if (areEditingMimicSettings()) {
                ((MimicsSettingsFragment) getSupportFragmentManager()
                        .findFragmentByTag(MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG))
                        .onBack();
            } else {
                // This implies we are in the Mimics settings and we were launched from Alarm
                // settings.  In this case we just pop the stack.
                super.onBackPressed();
            }
        } else if (!isAlarmRinging()) {
            finishActivity();
        }
    }

    private void finishActivity() {
        // We only want to report that ringing completed as a result of correct user action
        notifyControllerRingingCompleted();
        finish();
    }

    private void transitionBackToRingingScreen() {
        GeneralUtilities.showFragmentFromLeft(getSupportFragmentManager(),
                mAlarmRingingFragment,
                AlarmRingingFragment.RINGING_FRAGMENT_TAG);
        notifyControllerStartAlarmRinging();
    }

    private boolean isAlarmRinging() {
        return (getSupportFragmentManager()
                .findFragmentByTag(AlarmRingingFragment.RINGING_FRAGMENT_TAG) != null);
    }

    private boolean isGameRunning() {
        return (getSupportFragmentManager()
                .findFragmentByTag(MimicFactory.MIMIC_FRAGMENT_TAG) != null);
    }

    private boolean areEditingSettings() {
        return (getSupportFragmentManager()
                .findFragmentByTag(AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG) != null) ||
                (getSupportFragmentManager()
                        .findFragmentByTag(MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG) != null);
    }

    private boolean areEditingAlarmSettings() {
        return (getSupportFragmentManager()
                .findFragmentByTag(AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG) != null) &&
                (getSupportFragmentManager()
                        .findFragmentByTag(MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG) == null);
    }

    private boolean areEditingMimicSettings() {
        return (getSupportFragmentManager()
                .findFragmentByTag(MimicsSettingsFragment.MIMICS_SETTINGS_FRAGMENT_TAG) != null) &&
                (getSupportFragmentManager()
                        .findFragmentByTag(AlarmSettingsFragment.SETTINGS_FRAGMENT_TAG) == null);
    }

    private int getAlarmRingingDuration() {
        return GeneralUtilities.getDurationSetting(R.string.pref_ring_duration_key,
                R.string.pref_default_ring_duration_value,
                ALARM_DURATION_INTEGER);
    }

    private void bindRingingService() {
        // Establish a connection with the service.  We use an explicit
        // class name because we want a specific service implementation that
        // we know will be running in our own process (and thus won't be
        // supporting component replacement by other applications).
        bindService(new Intent(AlarmRingingActivity.this,
                AlarmRingingService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
        mIsServiceBound = true;
    }

    private void unbindRingingService() {
        if (mIsServiceBound) {
            // Detach our existing connection.
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    private void notifyControllerRingingCompleted() {
        if (mRingingService != null) {
            mRingingService.reportAlarmUXCompleted();
        }
    }

    private void notifyControllerSilenceAlarmRinging() {
        if (mRingingService != null) {
            mRingingService.silenceAlarmRinging();
        }
    }

    private void notifyControllerStartAlarmRinging() {
        if (mRingingService != null) {
            mRingingService.startAlarmRinging();
        }
    }

    private void notifyControllerRingingDismissed() {
        if (mRingingService != null) {
            mRingingService.reportAlarmUXDismissed();
        }
    }

    private void notifyControllerAllowDismiss() {
        if (mRingingService != null) {
            mRingingService.requestAllowUXDismiss();
        }
    }

    private void cancelAlarmTimeout () {
        mHandler.removeCallbacks(mAlarmCancelTask);
    }
}
