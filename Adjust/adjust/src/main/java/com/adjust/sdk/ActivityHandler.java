//
//  ActivityHandler.java
//  Adjust
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adjust GmbH. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adjust.sdk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.adjust.sdk.Constants.ACTIVITY_STATE_FILENAME;
import static com.adjust.sdk.Constants.ATTRIBUTION_FILENAME;
import static com.adjust.sdk.Constants.LOGTAG;
import static com.adjust.sdk.Constants.SESSION_CALLBACK_PARAMETERS_FILENAME;
import static com.adjust.sdk.Constants.SESSION_PARAMETERS_FILENAME;
import static com.adjust.sdk.Constants.SESSION_PARTNER_PARAMETERS_FILENAME;

public class ActivityHandler extends HandlerThread implements IActivityHandler {

    private static long FOREGROUND_TIMER_INTERVAL;
    private static long FOREGROUND_TIMER_START;
    private static long BACKGROUND_TIMER_INTERVAL;
    private static long SESSION_INTERVAL;
    private static long SUBSESSION_INTERVAL;
    private static final String TIME_TRAVEL = "Time travel!";
    private static final String ADJUST_PREFIX = "adjust_";
    private static final String ACTIVITY_STATE_NAME = "Activity state";
    private static final String ATTRIBUTION_NAME = "Attribution";
    private static final String FOREGROUND_TIMER_NAME = "Foreground timer";
    private static final String BACKGROUND_TIMER_NAME = "Background timer";
    private static final String DELAY_TIMER_NAME = "Delay timer";
    private static final String SESSION_PARAMETERS_NAME = "Session parameters";
    private static final String SESSION_CALLBACK_PARAMETERS_NAME = "Session Callback parameters";
    private static final String SESSION_PARTNER_PARAMETERS_NAME = "Session Partner parameters";

    private Handler internalHandler;
    private IPackageHandler packageHandler;
    private ActivityState activityState;
    private ILogger logger;
    private TimerCycle foregroundTimer;
    private ScheduledExecutorService scheduler;
    private TimerOnce backgroundTimer;
    private TimerOnce delayStartTimer;
    private InternalState internalState;

    private DeviceInfo deviceInfo;
    private AdjustConfig adjustConfig; // always valid after construction
    private AdjustAttribution attribution;
    private IAttributionHandler attributionHandler;
    private ISdkClickHandler sdkClickHandler;
    private SessionParameters sessionParameters;

    public class InternalState {
        boolean enabled;
        boolean offline;
        boolean background;
        boolean delayStart;
        boolean updatePackages;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isDisabled() {
            return !enabled;
        }

        public boolean isOffline() {
            return offline;
        }

        public boolean isOnline() {
            return !offline;
        }

        public boolean isBackground() {
            return background;
        }

        public boolean isForeground() {
            return !background;
        }

        public boolean isDelayStart() {
            return delayStart;
        }

        public boolean isToStartNow() {
            return !delayStart;
        }

        public boolean isToUpdatePackages() {
            return updatePackages;
        }
    }

    private ActivityHandler(AdjustConfig adjustConfig) {
        super(LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();

        init(adjustConfig);

        // init logger to be available everywhere
        logger = AdjustFactory.getLogger();
        if (AdjustConfig.ENVIRONMENT_PRODUCTION.equals(adjustConfig.environment)) {
            logger.setLogLevel(LogLevel.ASSERT);
        } else {
            logger.setLogLevel(adjustConfig.logLevel);
        }

        this.internalHandler = new Handler(getLooper());
        internalState = new InternalState();

        // enabled by default
        internalState.enabled = true;
        // online by default
        internalState.offline = false;
        // in the background by default
        internalState.background = true;
        // delay start not configured by default
        internalState.delayStart = false;

        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                initInternal();
            }
        });
    }

    @Override
    public void init(AdjustConfig adjustConfig) {
        this.adjustConfig = adjustConfig;
    }

    public static ActivityHandler getInstance(AdjustConfig adjustConfig) {
        if (adjustConfig == null) {
            AdjustFactory.getLogger().error("AdjustConfig missing");
            return null;
        }

        if (!adjustConfig.isValid()) {
            AdjustFactory.getLogger().error("AdjustConfig not initialized correctly");
            return null;
        }

        if (adjustConfig.processName != null) {
            int currentPid = android.os.Process.myPid();
            ActivityManager manager = (ActivityManager) adjustConfig.context.getSystemService(Context.ACTIVITY_SERVICE);

            if (manager == null) {
                return null;
            }

            for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
                if (processInfo.pid == currentPid) {
                    if (!processInfo.processName.equalsIgnoreCase(adjustConfig.processName)) {
                        AdjustFactory.getLogger().info("Skipping initialization in background process (%s)", processInfo.processName);
                        return null;
                    }
                    break;
                }
            }
        }

        ActivityHandler activityHandler = new ActivityHandler(adjustConfig);
        return activityHandler;
    }

    @Override
    public void onResume() {
        internalState.background = false;

        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                delayStart();

                stopBackgroundTimer();

                startForegroundTimer();

                logger.verbose("Subsession start");

                startInternal();
            }
        });
    }

    @Override
    public void onPause() {
        internalState.background = true;

        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                stopForegroundTimer();

                startBackgroundTimer();

                logger.verbose("Subsession end");

                endInternal();
            }
        });
    }

    @Override
    public void trackEvent(final AdjustEvent event) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activityState == null) {
                    logger.warn("Event triggered before first application launch.\n" +
                            "This will trigger the SDK start and an install without user interaction.\n" +
                            "Please check https://github.com/adjust/android_sdk#can-i-trigger-an-event-at-application-launch for more information.");
                    startInternal();
                }
                trackEventInternal(event);
            }
        });
    }

    @Override
    public void finishedTrackingActivity(ResponseData responseData) {
        // redirect session responses to attribution handler to check for attribution information
        if (responseData instanceof SessionResponseData) {
            attributionHandler.checkSessionResponse((SessionResponseData)responseData);
            return;
        }
        // check if it's an event response
        if (responseData instanceof EventResponseData) {
            launchEventResponseTasks((EventResponseData)responseData);
            return;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        // compare with the saved or internal state
        if (!hasChangedState(this.isEnabled(), enabled,
                "Adjust already enabled", "Adjust already disabled")) {
            return;
        }

        // save new enabled state in internal state
        internalState.enabled = enabled;

        if (activityState == null) {
            updateStatus(!enabled,
                    "Handlers will start as paused due to the SDK being disabled",
                    "Handlers will still start as paused",
                    "Handlers will start as active due to the SDK being enabled");
            return;
        }

        // save new enabled state in activity state
        activityState.enabled = enabled;
        writeActivityState();

        updateStatus(!enabled,
                "Pausing handlers due to SDK being disabled",
                "Handlers remain paused",
                "Resuming handlers due to SDK being enabled");
    }

    private void updateStatus(boolean pausingState, String pausingMessage,
                              String remainsPausedMessage, String unPausingMessage)
    {
        // it is changing from an active state to a pause state
        if (pausingState) {
            logger.info(pausingMessage);
        }
        // check if it's remaining in a pause state
        else if (paused(false)) {
            // including the sdk click handler
            if (paused(true)) {
                logger.info(remainsPausedMessage);
            } else {
                logger.info(remainsPausedMessage + ", except the Sdk Click Handler");
            }
        } else {
            // it is changing from a pause state to an active state
            logger.info(unPausingMessage);
        }

        updateHandlersStatusAndSend();
    }

    private boolean hasChangedState(boolean previousState, boolean newState,
                                    String trueMessage, String falseMessage)
    {
        if (previousState != newState) {
            return true;
        }

        if (previousState) {
            logger.debug(trueMessage);
        } else {
            logger.debug(falseMessage);
        }

        return false;
    }

    @Override
    public void setOfflineMode(boolean offline) {
        // compare with the internal state
        if (!hasChangedState(internalState.isOffline(), offline,
                "Adjust already in offline mode",
                "Adjust already in online mode")) {
            return;
        }

        internalState.offline = offline;

        if (activityState == null) {
            updateStatus(offline,
                    "Handlers will start paused due to SDK being offline",
                    "Handlers will still start as paused",
                    "Handlers will start as active due to SDK being online");
            return;
        }

        updateStatus(offline,
                "Pausing handlers to put SDK offline mode",
                "Handlers remain paused",
                "Resuming handlers to put SDK in online mode");
    }

    @Override
    public boolean isEnabled() {
        if (activityState != null) {
            return activityState.enabled;
        } else {
            return internalState.isEnabled();
        }
    }

    @Override
    public void readOpenUrl(final Uri url, final long clickTime) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                readOpenUrlInternal(url, clickTime);
            }
        });
    }

    @Override
    public boolean updateAttribution(AdjustAttribution attribution) {
        if (attribution == null) {
            return false;
        }

        if (attribution.equals(this.attribution)) {
            return false;
        }

        saveAttribution(attribution);
        return true;
    }

    private void saveAttribution(AdjustAttribution attribution) {
        this.attribution = attribution;
        writeAttribution();
    }

    @Override
    public void setAskingAttribution(boolean askingAttribution) {
        activityState.askingAttribution = askingAttribution;
        writeActivityState();
    }

    @Override
    public void sendReferrer(final String referrer, final long clickTime) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                sendReferrerInternal(referrer, clickTime);
            }
        });
    }

    @Override
    public void launchEventResponseTasks(final EventResponseData eventResponseData) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                launchEventResponseTasksInternal(eventResponseData);
            }
        });
    }

    @Override
    public void launchSessionResponseTasks(final SessionResponseData sessionResponseData) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                launchSessionResponseTasksInternal(sessionResponseData);
            }
        });
    }

    @Override
    public void launchAttributionResponseTasks(final AttributionResponseData attributionResponseData) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                launchAttributionResponseTasksInternal(attributionResponseData);
            }
        });
    }

    @Override
    public void sendFirstPackages () {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                sendFirstPackagesInternal();
            }
        });
    }

    @Override
    public void addExternalDeviceId(final String externalDeviceId) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                addExternalDeviceIdInternal(externalDeviceId);
            }
        });
    }

    @Override
    public void addSessionCallbackParameter(final String key, final String value) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                addSessionCallbackParameterInternal(key, value);
            }
        });
    }

    @Override
    public void addSessionPartnerParameter(final String key, final String value) {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                addSessionPartnerParameterInternal(key, value);
            }
        });
    }

    public ActivityPackage getAttributionPackage() {
        long now = System.currentTimeMillis();
        PackageBuilder attributionBuilder = new PackageBuilder(adjustConfig,
                deviceInfo,
                activityState,
                now);
        return attributionBuilder.buildAttributionPackage();
    }

    public InternalState getInternalState() {
        return internalState;
    }

    private void updateHandlersStatusAndSend() {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                updateHandlersStatusAndSendInternal();
            }
        });
    }

    private void foregroundTimerFired() {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                foregroundTimerFiredInternal();
            }
        });
    }

    private void backgroundTimerFired() {
        internalHandler.post(new Runnable() {
            @Override
            public void run() {
                backgroundTimerFiredInternal();
            }
        });
    }

    private void initInternal() {
        SESSION_INTERVAL = AdjustFactory.getSessionInterval();
        SUBSESSION_INTERVAL = AdjustFactory.getSubsessionInterval();
        // get timer values
        FOREGROUND_TIMER_INTERVAL = AdjustFactory.getTimerInterval();
        FOREGROUND_TIMER_START = AdjustFactory.getTimerStart();
        BACKGROUND_TIMER_INTERVAL = AdjustFactory.getTimerInterval();

        // has to be read in the background
        readAttribution(adjustConfig.context);
        readActivityState(adjustConfig.context);
        // read first the Session parameter class
        readSessionParameters(adjustConfig.context);
        if (sessionParameters == null) {
            sessionParameters = new SessionParameters();
        }
        readSessionCallbackParameters(adjustConfig.context);
        readSessionPartnerParameters(adjustConfig.context);

        if (activityState != null) {
            internalState.enabled = activityState.enabled;
        }

        deviceInfo = new DeviceInfo(adjustConfig.context, adjustConfig.sdkPrefix);

        if (adjustConfig.eventBufferingEnabled) {
            logger.info("Event buffering is enabled");
        }

        String playAdId = Util.getPlayAdId(adjustConfig.context);
        if (playAdId == null) {
            logger.warn("Unable to get Google Play Services Advertising ID at start time");
            if (deviceInfo.macSha1 == null &&
                    deviceInfo.macShortMd5 == null &&
                    deviceInfo.androidId == null)
            {
                logger.error("Unable to get any device id's. Please check if Proguard is correctly set with Adjust SDK");
            }
        } else {
            logger.info("Google Play Services Advertising ID read correctly at start time");
        }

        if (adjustConfig.defaultTracker != null) {
            logger.info("Default tracker: '%s'", adjustConfig.defaultTracker);
        }

        if (adjustConfig.referrer != null) {
            sendReferrer(adjustConfig.referrer, adjustConfig.referrerClickTime); // send to background queue to make sure that activityState is valid
        }

        foregroundTimer = new TimerCycle(new Runnable() {
            @Override
            public void run() {
                foregroundTimerFired();
            }
        }, FOREGROUND_TIMER_START, FOREGROUND_TIMER_INTERVAL, FOREGROUND_TIMER_NAME);

        // create background timer
        scheduler = Executors.newSingleThreadScheduledExecutor();
        if (adjustConfig.sendInBackground) {
            logger.info("Send in background configured");

            backgroundTimer = new TimerOnce(scheduler, new Runnable() {
                @Override
                public void run() {
                    backgroundTimerFired();
                }
            }, BACKGROUND_TIMER_NAME);
        }

        // configure delay start timer
        if (activityState == null &&
                adjustConfig.delayStart > 0)
        {
            logger.info("Delay start configured");
            delayStartTimer = new TimerOnce(scheduler, new Runnable() {
                @Override
                public void run() {
                    sendFirstPackages();
                }
            }, DELAY_TIMER_NAME);
        }

        packageHandler = AdjustFactory.getPackageHandler(this, adjustConfig.context, toSend(false));

        ActivityPackage attributionPackage = getAttributionPackage();
        attributionHandler = AdjustFactory.getAttributionHandler(this,
                attributionPackage,
                toSend(false),
                adjustConfig.hasAttributionChangedListener());

        sdkClickHandler = AdjustFactory.getSdkClickHandler(toSend(true));
    }

    private void startInternal() {
        // it shouldn't start if it was disabled after a first session
        if (activityState != null
                && !activityState.enabled) {
            return;
        }

        updateHandlersStatusAndSendInternal();

        processSession();

        checkAttributionState();
    }

    private void processSession() {
        long now = System.currentTimeMillis();

        // very first session
        if (activityState == null) {
            activityState = new ActivityState();
            activityState.sessionCount = 1; // this is the first session

            transferSessionPackage(now);
            activityState.resetSessionAttributes(now);
            activityState.enabled = internalState.isEnabled();
            writeActivityState();
            return;
        }

        long lastInterval = now - activityState.lastActivity;

        if (lastInterval < 0) {
            logger.error(TIME_TRAVEL);
            activityState.lastActivity = now;
            writeActivityState();
            return;
        }

        // new session
        if (lastInterval > SESSION_INTERVAL) {
            activityState.sessionCount++;
            activityState.lastInterval = lastInterval;

            transferSessionPackage(now);
            activityState.resetSessionAttributes(now);
            writeActivityState();
            return;
        }

        // new subsession
        if (lastInterval > SUBSESSION_INTERVAL) {
            activityState.subsessionCount++;
            activityState.sessionLength += lastInterval;
            activityState.lastActivity = now;
            logger.verbose("Started subsession %d of session %d",
                    activityState.subsessionCount,
                    activityState.sessionCount);
            writeActivityState();
            return;
        }

        logger.verbose("Time span since last activity too short for a new subsession");
    }

    private void checkAttributionState() {
        if (!checkActivityState(activityState)) { return; }

        // if it's a new session
        if (activityState.subsessionCount <= 1) {
            return;
        }

        // if there is already an attribution saved and there was no attribution being asked
        if (attribution != null && !activityState.askingAttribution) {
            return;
        }

        attributionHandler.getAttribution();
    }

    private void endInternal() {
        // pause sending if it's not allowed to send
        if (!toSend()) {
            pauseSending();
        }

        if (updateActivityState(System.currentTimeMillis())) {
            writeActivityState();
        }
    }

    private void trackEventInternal(AdjustEvent event) {
        if (!checkActivityState(activityState)) return;
        if (!this.isEnabled()) return;
        if (!checkEvent(event)) return;

        long now = System.currentTimeMillis();

        activityState.eventCount++;
        updateActivityState(now);

        PackageBuilder eventBuilder = new PackageBuilder(adjustConfig, deviceInfo, activityState, now);
        ActivityPackage eventPackage = eventBuilder.buildEventPackage(event, sessionParameters);
        packageHandler.addPackage(eventPackage);

        if (adjustConfig.eventBufferingEnabled) {
            logger.info("Buffered event %s", eventPackage.getSuffix());
        } else {
            packageHandler.sendFirstPackage();
        }

        // if it is in the background and it can send, start the background timer
        if (adjustConfig.sendInBackground && internalState.isBackground()) {
            startBackgroundTimer();
        }

        writeActivityState();
    }

    private void launchEventResponseTasksInternal(final EventResponseData eventResponseData) {
        Handler handler = new Handler(adjustConfig.context.getMainLooper());

        // success callback
        if (eventResponseData.success && adjustConfig.onEventTrackingSucceededListener != null) {
            logger.debug("Launching success event tracking listener");
            // add it to the handler queue
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    adjustConfig.onEventTrackingSucceededListener.onFinishedEventTrackingSucceeded(eventResponseData.getSuccessResponseData());
                }
            };
            handler.post(runnable);

            return;
        }
        // failure callback
        if (!eventResponseData.success && adjustConfig.onEventTrackingFailedListener != null) {
            logger.debug("Launching failed event tracking listener");
            // add it to the handler queue
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    adjustConfig.onEventTrackingFailedListener.onFinishedEventTrackingFailed(eventResponseData.getFailureResponseData());
                }
            };
            handler.post(runnable);

            return;
        }
    }

    private void launchSessionResponseTasksInternal(SessionResponseData sessionResponseData) {
        // use the same handler to ensure that all tasks are executed sequentially
        Handler handler = new Handler(adjustConfig.context.getMainLooper());

        // try to update the attribution
        boolean attributionUpdated = updateAttribution(sessionResponseData.attribution);

        // if attribution changed, launch attribution changed delegate
        if (attributionUpdated) {
            launchAttributionListener(handler);
        }

        // launch Session tracking listener if available
        launchSessionResponseListener(sessionResponseData, handler);

        // if there is any, try to launch the deeplink
        prepareDeeplink(sessionResponseData, handler);
    }

    private void launchSessionResponseListener(final SessionResponseData sessionResponseData, Handler handler) {
        // success callback
        if (sessionResponseData.success && adjustConfig.onSessionTrackingSucceededListener != null) {
            logger.debug("Launching success session tracking listener");
            // add it to the handler queue
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    adjustConfig.onSessionTrackingSucceededListener.onFinishedSessionTrackingSucceeded(sessionResponseData.getSuccessResponseData());
                }
            };
            handler.post(runnable);

            return;
        }
        // failure callback
        if (!sessionResponseData.success && adjustConfig.onSessionTrackingFailedListener != null) {
            logger.debug("Launching failed session tracking listener");
            // add it to the handler queue
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    adjustConfig.onSessionTrackingFailedListener.onFinishedSessionTrackingFailed(sessionResponseData.getFailureResponseData());
                }
            };
            handler.post(runnable);

            return;
        }
    }

    private void launchAttributionResponseTasksInternal(AttributionResponseData responseData) {
        Handler handler = new Handler(adjustConfig.context.getMainLooper());

        // try to update the attribution
        boolean attributionUpdated = updateAttribution(responseData.attribution);

        // if attribution changed, launch attribution changed delegate
        if (attributionUpdated) {
            launchAttributionListener(handler);
        }
    }

    private void launchAttributionListener(Handler handler) {
        if (adjustConfig.onAttributionChangedListener == null) {
            return;
        }
        // add it to the handler queue
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                adjustConfig.onAttributionChangedListener.onAttributionChanged(attribution);
            }
        };
        handler.post(runnable);
    }

    private void prepareDeeplink(ResponseData responseData, final Handler handler) {
        if (responseData.jsonResponse == null) {
            return;
        }

        final String deeplink = responseData.jsonResponse.optString("deeplink", null);

        if (deeplink == null) {
            return;
        }

        final Uri location = Uri.parse(deeplink);
        final Intent deeplinkIntent = createDeeplinkIntent(location);

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                boolean toLaunchDeeplink = true;
                if (adjustConfig.onDeeplinkResponseListener != null) {
                    toLaunchDeeplink = adjustConfig.onDeeplinkResponseListener.launchReceivedDeeplink(location);
                }
                if (toLaunchDeeplink) {
                    launchDeeplinkMain(deeplinkIntent, deeplink);
                }
            }
        };
        handler.post(runnable);
    }

    private Intent createDeeplinkIntent(Uri location) {
        Intent mapIntent;
        if (adjustConfig.deepLinkComponent == null) {
            mapIntent = new Intent(Intent.ACTION_VIEW, location);
        } else {
            mapIntent = new Intent(Intent.ACTION_VIEW, location, adjustConfig.context, adjustConfig.deepLinkComponent);
        }
        mapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mapIntent.setPackage(adjustConfig.context.getPackageName());

        return mapIntent;
    }

    private void launchDeeplinkMain(final Intent deeplinkIntent, final String deeplink) {
        // Verify it resolves
        PackageManager packageManager = adjustConfig.context.getPackageManager();
        List<ResolveInfo> activities = packageManager.queryIntentActivities(deeplinkIntent, 0);
        boolean isIntentSafe = activities.size() > 0;

        // Start an activity if it's safe
        if (!isIntentSafe) {
            logger.error("Unable to open deep link (%s)", deeplink);
            return;
        }

        // add it to the handler queue
        logger.info("Open deep link (%s)", deeplink);
        adjustConfig.context.startActivity(deeplinkIntent);
    }

    private void sendReferrerInternal(String referrer, long clickTime) {
        if (referrer == null || referrer.length() == 0 ) {
            return;
        }
        PackageBuilder clickPackageBuilder = queryStringClickPackageBuilder(referrer);

        if (clickPackageBuilder == null) {
            return;
        }

        clickPackageBuilder.referrer = referrer;
        ActivityPackage clickPackage = clickPackageBuilder.buildClickPackage(Constants.REFTAG, clickTime);

        sdkClickHandler.sendSdkClick(clickPackage);
    }

    private void readOpenUrlInternal(Uri url, long clickTime) {
        if (url == null) {
            return;
        }

        String queryString = url.getQuery();

        if (queryString == null && url.toString().length() > 0) {
            queryString = "";
        }

        PackageBuilder clickPackageBuilder = queryStringClickPackageBuilder(queryString);
        if (clickPackageBuilder == null) {
            return;
        }

        clickPackageBuilder.deeplink = url.toString();
        ActivityPackage clickPackage = clickPackageBuilder.buildClickPackage(Constants.DEEPLINK, clickTime);

        sdkClickHandler.sendSdkClick(clickPackage);
    }

    private PackageBuilder queryStringClickPackageBuilder(String queryString) {
        if (queryString == null) {
            return null;
        }

        Map<String, String> queryStringParameters = new LinkedHashMap<String, String>();
        AdjustAttribution queryStringAttribution = new AdjustAttribution();

        logger.verbose("Reading query string (%s)", queryString);

        String[] queryPairs = queryString.split("&");

        for (String pair : queryPairs) {
            readQueryString(pair, queryStringParameters, queryStringAttribution);
        }

        String reftag = queryStringParameters.remove(Constants.REFTAG);

        long now = System.currentTimeMillis();
        PackageBuilder builder = new PackageBuilder(adjustConfig, deviceInfo, activityState, now);
        builder.extraParameters = queryStringParameters;
        builder.attribution = queryStringAttribution;
        builder.reftag = reftag;

        return builder;
    }

    private boolean readQueryString(String queryString,
                                    Map<String, String> extraParameters,
                                    AdjustAttribution queryStringAttribution) {
        String[] pairComponents = queryString.split("=");
        if (pairComponents.length != 2) return false;

        String key = pairComponents[0];
        if (!key.startsWith(ADJUST_PREFIX)) return false;

        String value = pairComponents[1];
        if (value.length() == 0) return false;

        String keyWOutPrefix = key.substring(ADJUST_PREFIX.length());
        if (keyWOutPrefix.length() == 0) return false;

        if (!trySetAttribution(queryStringAttribution, keyWOutPrefix, value)) {
            extraParameters.put(keyWOutPrefix, value);
        }

        return true;
    }

    private boolean trySetAttribution(AdjustAttribution queryStringAttribution,
                                      String key,
                                      String value) {
        if (key.equals("tracker")) {
            queryStringAttribution.trackerName = value;
            return true;
        }

        if (key.equals("campaign")) {
            queryStringAttribution.campaign = value;
            return true;
        }

        if (key.equals("adgroup")) {
            queryStringAttribution.adgroup = value;
            return true;
        }

        if (key.equals("creative")) {
            queryStringAttribution.creative = value;
            return true;
        }

        return false;
    }

    private void updateHandlersStatusAndSendInternal() {
        // check if it should stop sending
        if (!toSend()) {
            pauseSending();
            return;
        }

        resumeSending();

        // try to send
        if (!adjustConfig.eventBufferingEnabled) {
            packageHandler.sendFirstPackage();
        }
    }

    private void pauseSending() {
        attributionHandler.pauseSending();
        packageHandler.pauseSending();
        // the conditions to pause the sdk click handler are less restrictive
        // it's possible for the sdk click handler to be active while others are paused
        if (!toSend(true)) {
            sdkClickHandler.pauseSending();
        }
    }

    private void resumeSending() {
        attributionHandler.resumeSending();
        packageHandler.resumeSending();
        sdkClickHandler.resumeSending();
    }

    private boolean updateActivityState(long now) {
        if (!checkActivityState(activityState)) { return false; }

        long lastInterval = now - activityState.lastActivity;
        // ignore late updates
        if (lastInterval > SESSION_INTERVAL) {
            return false;
        }
        activityState.lastActivity = now;

        if (lastInterval < 0) {
            logger.error(TIME_TRAVEL);
        } else {
            activityState.sessionLength += lastInterval;
            activityState.timeSpent += lastInterval;
        }
        return true;
    }

    public static boolean deleteActivityState(Context context) {
        return context.deleteFile(ACTIVITY_STATE_FILENAME);
    }

    public static boolean deleteAttribution(Context context) {
        return context.deleteFile(ATTRIBUTION_FILENAME);
    }

    private void transferSessionPackage(long now) {
        PackageBuilder builder = new PackageBuilder(adjustConfig, deviceInfo, activityState, now);
        ActivityPackage sessionPackage = builder.buildSessionPackage(sessionParameters);
        packageHandler.addPackage(sessionPackage);
        packageHandler.sendFirstPackage();
    }

    private void startForegroundTimer() {
        // don't start the timer if it's disabled or offline
        if (paused()) {
            return;
        }

        foregroundTimer.start();
    }

    private void stopForegroundTimer() {
        foregroundTimer.suspend();
    }

    private void foregroundTimerFiredInternal() {
        if (paused()) {
            // stop the timer cycle if it's disabled/offline
            stopForegroundTimer();
            return;
        }

        packageHandler.sendFirstPackage();

        if (updateActivityState(System.currentTimeMillis())) {
            writeActivityState();
        }
    }

    private void startBackgroundTimer() {
        if (backgroundTimer == null) {
            return;
        }

        // check if it can send in the background
        if (!toSend()) {
            return;
        }

        // background timer already started
        if (backgroundTimer.getFireIn() > 0) {
            return;
        }

        backgroundTimer.startIn(BACKGROUND_TIMER_INTERVAL);
    }

    private void stopBackgroundTimer() {
        if (backgroundTimer == null) {
            return;
        }

        backgroundTimer.cancel();
    }

    private void backgroundTimerFiredInternal() {
        packageHandler.sendFirstPackage();
    }

    private void delayStart() {
        // it's not configured to start delayed or already finished
        if (internalState.isToStartNow()) {
            return;
        }

        // the delay has already started
        if (internalState.isToUpdatePackages()) {
            return;
        }

        // check against max start delay
        long delayStart = adjustConfig.delayStart;
        long maxDelayStart = AdjustFactory.getMaxDelayStart();

        if (delayStart > maxDelayStart) {
            String delayStartFormatted = Util.SecondsDisplayFormat.format(delayStart);
            String maxDelayStartFormatted = Util.SecondsDisplayFormat.format(maxDelayStart);

            logger.warn("Delay start of %s seconds bigger than max allowed value of %s seconds", delayStartFormatted, maxDelayStartFormatted);
            delayStart = maxDelayStart;
        }

        String delayStartFormatted = Util.SecondsDisplayFormat.format(delayStart);
        logger.info("Waiting %s seconds before starting first session", delayStartFormatted);

        delayStartTimer.startIn(delayStart);

        internalState.updatePackages = true;
    }

    private void sendFirstPackagesInternal() {
        if (internalState.isToStartNow()) {
            logger.info("Start delay expired or never configured");
            return;
        }

        // update packages in queue
        updatePackagesInternal();
        // no longer is in delay start
        internalState.delayStart = false;
        // cancel possible still running timer if it was called by user
        delayStartTimer.cancel();
        // and release timer
        delayStartTimer = null;
        // update the status and try to send first package
        updateHandlersStatusAndSendInternal();
    }

    private void updatePackagesInternal() {
        // update activity packages
        packageHandler.updatePackages();
        // no longer needs to update packages
        internalState.updatePackages = false;
    }

    private void addExternalDeviceIdInternal(String externalDeviceId) {
        if (!Util.isValidParameter(externalDeviceId, "value", "External Device Id")) return;

        if (sessionParameters.externalDeviceId != null) {
            logger.warn("External Device Id %s will be overwritten", externalDeviceId);
        }

        sessionParameters.externalDeviceId = externalDeviceId;

        writeSessionParameters();
    }

    private void addSessionCallbackParameterInternal(String key, String value) {
        if (!Util.isValidParameter(key, "key", "Session Callback")) return;
        if (!Util.isValidParameter(value, "value", "Session Callback")) return;

        if (sessionParameters.callbackParameters == null) {
            sessionParameters.callbackParameters = new LinkedHashMap<String, String>();
        }

        String oldValue = sessionParameters.callbackParameters.get(key);

        if (value.equals(oldValue)) {
            logger.verbose("Key %s already present with the same value", key);
            return;
        }

        if (oldValue != null) {
            logger.warn("Key %s will be overwritten", key);
        }

        sessionParameters.callbackParameters.put(key, value);

        writeSessionCallbackParameters();
    }

    private void addSessionPartnerParameterInternal(String key, String value) {
        if (!Util.isValidParameter(key, "key", "Session Partner")) return;
        if (!Util.isValidParameter(value, "value", "Session Partner")) return;

        if (sessionParameters.partnerParameters == null) {
            sessionParameters.partnerParameters = new LinkedHashMap<String, String>();
        }

        String oldValue = sessionParameters.partnerParameters.get(key);

        if (value.equals(oldValue)) {
            logger.verbose("Key %s already present with the same value", key);
            return;
        }

        if (oldValue != null) {
            logger.warn("Key %s will be overwritten", key);
        }

        sessionParameters.partnerParameters.put(key, value);

        writeSessionPartnerParameters();
    }

    private void readActivityState(Context context) {
        try {
            activityState = Util.readObject(context, ACTIVITY_STATE_FILENAME, ACTIVITY_STATE_NAME, ActivityState.class);
        } catch (Exception e) {
            logger.error("Failed to read %s file (%s)", ACTIVITY_STATE_NAME, e.getMessage());
            activityState = null;
        }
    }

    private void readAttribution(Context context) {
        try {
            attribution = Util.readObject(context, ATTRIBUTION_FILENAME, ATTRIBUTION_NAME, AdjustAttribution.class);
        } catch (Exception e) {
            logger.error("Failed to read %s file (%s)", ATTRIBUTION_NAME, e.getMessage());
            attribution = null;
        }
    }

    private void readSessionParameters(Context context) {
        try {
            sessionParameters = Util.readObject(context,
                    SESSION_PARAMETERS_FILENAME,
                    SESSION_PARAMETERS_NAME,
                    SessionParameters.class);
        } catch (Exception e) {
            logger.error("Failed to read %s file (%s)", SESSION_PARAMETERS_NAME, e.getMessage());
            sessionParameters = null;
        }
    }

    private void readSessionCallbackParameters(Context context) {
        try {
            sessionParameters.callbackParameters = Util.readObject(context,
                    SESSION_CALLBACK_PARAMETERS_FILENAME,
                    SESSION_CALLBACK_PARAMETERS_NAME,
                    (Class<Map<String,String>>)(Class)Map.class);
        } catch (Exception e) {
            logger.error("Failed to read %s file (%s)", SESSION_CALLBACK_PARAMETERS_NAME, e.getMessage());
            sessionParameters.callbackParameters = null;
        }
    }

    private void readSessionPartnerParameters(Context context) {
        try {
            sessionParameters.partnerParameters = Util.readObject(context,
                    SESSION_PARTNER_PARAMETERS_FILENAME,
                    SESSION_PARTNER_PARAMETERS_NAME,
                    (Class<Map<String,String>>)(Class)Map.class);
        } catch (Exception e) {
            logger.error("Failed to read %s file (%s)", SESSION_PARTNER_PARAMETERS_NAME, e.getMessage());
            sessionParameters.partnerParameters = null;
        }
    }

    private synchronized void writeActivityState() {
        Util.writeObject(activityState, adjustConfig.context, ACTIVITY_STATE_FILENAME, ACTIVITY_STATE_NAME);
    }

    private void writeAttribution() {
        Util.writeObject(attribution, adjustConfig.context, ATTRIBUTION_FILENAME, ATTRIBUTION_NAME);
    }

    private void writeSessionParameters() {
        Util.writeObject(sessionParameters, adjustConfig.context, SESSION_PARAMETERS_FILENAME, SESSION_PARAMETERS_NAME);
    }

    private void writeSessionCallbackParameters() {
        Util.writeObject(sessionParameters.callbackParameters, adjustConfig.context, SESSION_CALLBACK_PARAMETERS_FILENAME, SESSION_CALLBACK_PARAMETERS_NAME);
    }

    private void writeSessionPartnerParameters() {
        Util.writeObject(sessionParameters.partnerParameters, adjustConfig.context, SESSION_PARTNER_PARAMETERS_FILENAME, SESSION_PARTNER_PARAMETERS_NAME);
    }

    private boolean checkEvent(AdjustEvent event) {
        if (event == null) {
            logger.error("Event missing");
            return false;
        }

        if (!event.isValid()) {
            logger.error("Event not initialized correctly");
            return false;
        }

        return true;
    }

    private boolean checkActivityState(ActivityState activityState) {
        if (activityState == null) {
            logger.error("Missing activity state");
            return false;
        }
        return true;
    }

    private boolean paused() {
        return paused(false);
    }

    private boolean paused(boolean sdkClickHandlerOnly) {
        if (sdkClickHandlerOnly) {
            // sdk click handler is paused if either:
            return internalState.isOffline() ||     // it's offline
                    !this.isEnabled();              // is disabled
        }
        // other handlers are paused if either:
        return internalState.isOffline()    ||      // it's offline
                !this.isEnabled()           ||      // is disabled
                internalState.isDelayStart();       // is in delayed start
    }

    private boolean toSend() {
        return toSend(false);
    }

    private boolean toSend(boolean sdkClickHandlerOnly) {
        // don't send when it's paused
        if (paused(sdkClickHandlerOnly)) {
            return false;
        }

        // has the option to send in the background -> is to send
        if (adjustConfig.sendInBackground) {
            return true;
        }

        // doesn't have the option -> depends on being on the background/foreground
        return internalState.isForeground();
    }
}
