package info.nightscout.androidaps.complications;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationManager;
import android.support.wearable.complications.ComplicationProviderService;
import android.support.wearable.complications.ComplicationText;
import android.support.wearable.complications.ProviderUpdateRequester;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import dagger.android.AndroidInjection;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.comm.DataLayerListenerServiceWear;
import info.nightscout.androidaps.data.RawDisplayData;
import info.nightscout.androidaps.events.EventWearToMobile;
import info.nightscout.androidaps.interaction.utils.Constants;
import info.nightscout.androidaps.interaction.utils.DisplayFormat;
import info.nightscout.androidaps.interaction.utils.Inevitable;
import info.nightscout.androidaps.interaction.utils.Persistence;
import info.nightscout.androidaps.interaction.utils.WearUtil;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.shared.logging.AAPSLogger;
import info.nightscout.shared.logging.LTag;
import info.nightscout.shared.weardata.EventData;

/**
 * Base class for all complications
 * <p>
 * Created by dlvoy on 2019-11-12
 */
@SuppressWarnings("deprecation")
public abstract class BaseComplicationProviderService extends ComplicationProviderService {

    @Inject Inevitable inevitable;
    @Inject WearUtil wearUtil;
    @Inject DisplayFormat displayFormat;
    @Inject Persistence persistence;
    @Inject AAPSLogger aapsLogger;
    @Inject RxBus rxBus;

    // Not derived from DaggerService, do injection here
    @Override
    public void onCreate() {
        AndroidInjection.inject(this);
        super.onCreate();
    }

    private static final String TASK_ID_REFRESH_COMPLICATION = "refresh-complication";


    private LocalBroadcastManager localBroadcastManager;
    private MessageReceiver messageReceiver;

    //==============================================================================================
    // ABSTRACT COMPLICATION INTERFACE
    //==============================================================================================

    public abstract ComplicationData buildComplicationData(int dataType, RawDisplayData raw, PendingIntent complicationPendingIntent);

    public abstract String getProviderCanonicalName();

    public ComplicationAction getComplicationAction() {
        return ComplicationAction.MENU;
    }

    //----------------------------------------------------------------------------------------------
    // DEFAULT BEHAVIOURS
    //----------------------------------------------------------------------------------------------

    public ComplicationData buildNoSyncComplicationData(int dataType,
                                                        RawDisplayData raw,
                                                        PendingIntent complicationPendingIntent,
                                                        PendingIntent exceptionalPendingIntent,
                                                        long since) {


        final ComplicationData.Builder builder = new ComplicationData.Builder(dataType);
        if (dataType != ComplicationData.TYPE_LARGE_IMAGE) {
            builder.setIcon(Icon.createWithResource(this, R.drawable.ic_sync_alert));
        }

        if (dataType == ComplicationData.TYPE_RANGED_VALUE) {
            builder.setMinValue(0);
            builder.setMaxValue(100);
            builder.setValue(0);
        }

        switch (dataType) {
            case ComplicationData.TYPE_ICON:
            case ComplicationData.TYPE_SHORT_TEXT:
            case ComplicationData.TYPE_RANGED_VALUE:
                if (since > 0) {
                    builder.setShortText(ComplicationText.plainText(displayFormat.shortTimeSince(since) + " old"));
                } else {
                    builder.setShortText(ComplicationText.plainText("!err!"));
                }
                break;
            case ComplicationData.TYPE_LONG_TEXT:
                builder.setLongTitle(ComplicationText.plainText(getString(R.string.label_warning_sync)));
                if (since > 0) {
                    builder.setLongText(ComplicationText.plainText(String.format(getString(R.string.label_warning_since), displayFormat.shortTimeSince(since))));
                } else {
                    builder.setLongText(ComplicationText.plainText(getString(R.string.label_warning_sync_aaps)));
                }
                break;
            case ComplicationData.TYPE_LARGE_IMAGE:
                return buildComplicationData(dataType, raw, complicationPendingIntent);
            default:
                aapsLogger.warn(LTag.WEAR, "Unexpected complication type " + dataType);
                break;
        }

        builder.setTapAction(exceptionalPendingIntent);
        return builder.build();
    }

    public ComplicationData buildOutdatedComplicationData(int dataType,
                                                          RawDisplayData raw,
                                                          PendingIntent complicationPendingIntent,
                                                          PendingIntent exceptionalPendingIntent,
                                                          long since) {

        final ComplicationData.Builder builder = new ComplicationData.Builder(dataType);
        if (dataType != ComplicationData.TYPE_LARGE_IMAGE) {
            builder.setIcon(Icon.createWithResource(this, R.drawable.ic_alert));
            builder.setBurnInProtectionIcon(Icon.createWithResource(this, R.drawable.ic_alert_burnin));
        }

        if (dataType == ComplicationData.TYPE_RANGED_VALUE) {
            builder.setMinValue(0);
            builder.setMaxValue(100);
            builder.setValue(0);
        }

        switch (dataType) {
            case ComplicationData.TYPE_ICON:
            case ComplicationData.TYPE_SHORT_TEXT:
            case ComplicationData.TYPE_RANGED_VALUE:
                if (since > 0) {
                    builder.setShortText(ComplicationText.plainText(displayFormat.shortTimeSince(since) + " old"));
                } else {
                    builder.setShortText(ComplicationText.plainText("!old!"));
                }
                break;
            case ComplicationData.TYPE_LONG_TEXT:
                builder.setLongTitle(ComplicationText.plainText(getString(R.string.label_warning_old)));
                if (since > 0) {
                    builder.setLongText(ComplicationText.plainText(String.format(getString(R.string.label_warning_since), displayFormat.shortTimeSince(since))));
                } else {
                    builder.setLongText(ComplicationText.plainText(getString(R.string.label_warning_sync_aaps)));
                }
                break;
            case ComplicationData.TYPE_LARGE_IMAGE:
                return buildComplicationData(dataType, raw, complicationPendingIntent);
            default:
                aapsLogger.warn(LTag.WEAR, "Unexpected complication type " + dataType);
                break;
        }

        builder.setTapAction(exceptionalPendingIntent);
        return builder.build();
    }

    /**
     * If Complication depend on "since" field and need to be updated every minute or not
     * and need only update when new DisplayRawData arrive
     */
    protected boolean usesSinceField() {
        return false;
    }

    //==============================================================================================
    // COMPLICATION LIFECYCLE
    //==============================================================================================

    /*
     * Called when a complication has been activated. The method is for any one-time
     * (per complication) set-up.
     *
     * You can continue sending data for the active complicationId until onComplicationDeactivated()
     * is called.
     */
    @Override
    public void onComplicationActivated(
            int complicationId, int dataType, ComplicationManager complicationManager) {
        aapsLogger.warn(LTag.WEAR, "onComplicationActivated(): " + complicationId + " of kind: " + getProviderCanonicalName());

        persistence.putString("complication_" + complicationId, getProviderCanonicalName());
        persistence.putBoolean("complication_" + complicationId + "_since", usesSinceField());
        persistence.addToSet(Persistence.KEY_COMPLICATIONS, "complication_" + complicationId);

        IntentFilter messageFilter = new IntentFilter(DataLayerListenerServiceWear.Companion.getINTENT_NEW_DATA());

        messageReceiver = new MessageReceiver();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        localBroadcastManager.registerReceiver(messageReceiver, messageFilter);

        rxBus.send(new EventWearToMobile(new EventData.ActionResendData("BaseComplicationProviderService")));
        checkIfUpdateNeeded();
    }

    /*
     * Called when the complication needs updated data from your provider. There are four scenarios
     * when this will happen:
     *
     *   1. An active watch face complication is changed to use this provider
     *   2. A complication using this provider becomes active
     *   3. The period of time you specified in the manifest has elapsed (UPDATE_PERIOD_SECONDS)
     *   4. You triggered an update from your own class via the
     *       ProviderUpdateRequester.requestUpdate() method.
     */
    @Override
    public void onComplicationUpdate(
            int complicationId, int dataType, ComplicationManager complicationManager) {
        aapsLogger.warn(LTag.WEAR, "onComplicationUpdate() id: " + complicationId + " of class: " + getProviderCanonicalName());

        // Create Tap Action so that the user can checkIfUpdateNeeded an update by tapping the complication.
        final ComponentName thisProvider = new ComponentName(this, getProviderCanonicalName());

        // We pass the complication id, so we can only update the specific complication tapped.
        final PendingIntent complicationPendingIntent =
                ComplicationTapBroadcastReceiver.getTapActionIntent(
                        getApplicationContext(), thisProvider, complicationId, getComplicationAction());

        final RawDisplayData raw = new RawDisplayData();
        raw.updateFromPersistence(persistence);
        aapsLogger.warn(LTag.WEAR, "Complication data: " + raw.toDebugString());

        // store what is currently rendered in 'SGV since' field, to detect if it was changed and need update
        persistence.putString(Persistence.KEY_LAST_SHOWN_SINCE_VALUE,
                displayFormat.shortTimeSince(raw.getSingleBg().getTimeStamp()));

        // by each render we clear stale flag to ensure it is re-rendered at next refresh detection round
        persistence.putBoolean(Persistence.KEY_STALE_REPORTED, false);

        ComplicationData complicationData;

        if (wearUtil.msSince(persistence.whenDataUpdated()) > Constants.STALE_MS) {
            // no new data arrived - probably configuration or connection error
            final PendingIntent infoToast = ComplicationTapBroadcastReceiver.getTapWarningSinceIntent(
                    getApplicationContext(), thisProvider, complicationId, ComplicationAction.WARNING_SYNC, persistence.whenDataUpdated());
            complicationData = buildNoSyncComplicationData(dataType, raw, complicationPendingIntent, infoToast, persistence.whenDataUpdated());
        } else if (wearUtil.msSince(raw.getSingleBg().getTimeStamp()) > Constants.STALE_MS) {
            // data arriving from phone AAPS, but it is outdated (uploader/NS/xDrip/Sensor error)
            final PendingIntent infoToast = ComplicationTapBroadcastReceiver.getTapWarningSinceIntent(
                    getApplicationContext(), thisProvider, complicationId, ComplicationAction.WARNING_OLD, raw.getSingleBg().getTimeStamp());
            complicationData = buildOutdatedComplicationData(dataType, raw, complicationPendingIntent, infoToast, raw.getSingleBg().getTimeStamp());
        } else {
            // data is up-to-date, we can render standard complication
            complicationData = buildComplicationData(dataType, raw, complicationPendingIntent);
        }

        if (complicationData != null) {
            complicationManager.updateComplicationData(complicationId, complicationData);
        } else {
            // If no data is sent, we still need to inform the ComplicationManager, so the update
            // job can finish and the wake lock isn't held any longer than necessary.
            complicationManager.noUpdateRequired(complicationId);
        }
    }

    /*
     * Called when the complication has been deactivated.
     */
    @Override
    public void onComplicationDeactivated(int complicationId) {
        aapsLogger.warn(LTag.WEAR, "onComplicationDeactivated(): " + complicationId);

        persistence.removeFromSet(Persistence.KEY_COMPLICATIONS, "complication_" + complicationId);

        if (localBroadcastManager != null && messageReceiver != null) {
            localBroadcastManager.unregisterReceiver(messageReceiver);
        }
        inevitable.kill(TASK_ID_REFRESH_COMPLICATION);
    }

    //==============================================================================================
    // UPDATE AND REFRESH LOGIC
    //==============================================================================================

    /*
     * Schedule check for field update
     */
    public void checkIfUpdateNeeded() {

        aapsLogger.warn(LTag.WEAR, "Pending check if update needed - " + persistence.getString(Persistence.KEY_COMPLICATIONS, ""));

        inevitable.task(TASK_ID_REFRESH_COMPLICATION, 15 * Constants.SECOND_IN_MS, () -> {
            if (wearUtil.isBelowRateLimit("complication-checkIfUpdateNeeded", 5)) {
                aapsLogger.warn(LTag.WEAR, "Checking if update needed");
                requestUpdateIfSinceChanged();
                // We reschedule need for check - to make sure next check will Inevitable go in next 15s
                checkIfUpdateNeeded();
            }
        });

    }

    /*
     * Check if displayed since field (field that shows how old, in minutes, is reading)
     * is up-to-date or need to be changed (a minute or more elapsed)
     */
    private void requestUpdateIfSinceChanged() {
        final RawDisplayData raw = new RawDisplayData();
        raw.updateFromPersistence(persistence);

        final String lastSince = persistence.getString(Persistence.KEY_LAST_SHOWN_SINCE_VALUE, "-");
        final String calcSince = displayFormat.shortTimeSince(raw.getSingleBg().getTimeStamp());
        final boolean isStale = (wearUtil.msSince(persistence.whenDataUpdated()) > Constants.STALE_MS)
                || (wearUtil.msSince(raw.getSingleBg().getTimeStamp()) > Constants.STALE_MS);

        final boolean staleWasRefreshed = persistence.getBoolean(Persistence.KEY_STALE_REPORTED, false);
        final boolean sinceWasChanged = !lastSince.equals(calcSince);

        if (sinceWasChanged || (isStale && !staleWasRefreshed)) {
            persistence.putString(Persistence.KEY_LAST_SHOWN_SINCE_VALUE, calcSince);
            persistence.putBoolean(Persistence.KEY_STALE_REPORTED, isStale);

            aapsLogger.warn(LTag.WEAR, "Detected refresh of time needed! Reason: "
                    + (isStale ? "- stale detected" : "")
                    + (sinceWasChanged ? "- since changed from: " + lastSince + " to: " + calcSince : ""));

            if (isStale) {
                // all complications should update to show offline/old warning
                requestUpdate(getActiveProviderClasses());
            } else {
                // ... but only some require update due to 'since' field change
                requestUpdate(getSinceDependingProviderClasses());
            }
        }
    }

    /*
     * Request update for specified list of providers
     */
    private void requestUpdate(Set<String> providers) {
        for (String provider : providers) {
            aapsLogger.warn(LTag.WEAR, "Pending update of " + provider);
            // We wait with updating allowing all request, from various sources, to arrive
            inevitable.task("update-req-" + provider, 700, () -> {
                if (wearUtil.isBelowRateLimit("update-req-" + provider, 2)) {
                    aapsLogger.warn(LTag.WEAR, "Requesting update of " + provider);
                    final ComponentName componentName = new ComponentName(getApplicationContext(), provider);
                    final ProviderUpdateRequester providerUpdateRequester = new ProviderUpdateRequester(getApplicationContext(), componentName);
                    providerUpdateRequester.requestUpdateAll();
                }
            });
        }
    }

    /*
     * List all Complication providing classes that have active (registered) providers
     */
    private Set<String> getActiveProviderClasses() {
        Set<String> providers = new HashSet<>();
        Set<String> complications = persistence.getSetOf(Persistence.KEY_COMPLICATIONS);
        for (String complication : complications) {
            final String providerClass = persistence.getString(complication, "");
            if (providerClass.length() > 0) {
                providers.add(providerClass);
            }
        }
        return providers;
    }

    /*
     * List all Complication providing classes that have active (registered) providers
     * and additionally they depend on "since" field
     *    == they need to be updated not only on data broadcasts, but every minute or so
     */
    private Set<String> getSinceDependingProviderClasses() {
        Set<String> providers = new HashSet<>();
        Set<String> complications = persistence.getSetOf(Persistence.KEY_COMPLICATIONS);
        for (String complication : complications) {
            final String providerClass = persistence.getString(complication, "");
            final boolean dependOnSince = persistence.getBoolean(complication + "_since", false);
            if ((providerClass.length() > 0) && (dependOnSince)) {
                providers.add(providerClass);
            }
        }
        return providers;
    }

    /*
     * Listen to broadcast --> new data was stored by ListenerService to Persistence
     */
    public class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateAll();
        }
    }

    private void updateAll() {
        Set<String> complications = persistence.getSetOf(Persistence.KEY_COMPLICATIONS);
        if (complications.size() > 0) {
            checkIfUpdateNeeded();
            // We request all active providers
            requestUpdate(getActiveProviderClasses());
        }
    }
}
