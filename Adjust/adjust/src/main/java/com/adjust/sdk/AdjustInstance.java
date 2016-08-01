package com.adjust.sdk;

import android.net.Uri;

/**
 * Created by pfms on 04/12/14.
 */
public class AdjustInstance {

    private String referrer;
    private long referrerClickTime;
    private ActivityHandler activityHandler;

    private static ILogger getLogger() {
        return AdjustFactory.getLogger();
    }

    public void onCreate(AdjustConfig adjustConfig) {
        if (activityHandler != null) {
            getLogger().error("Adjust already initialized");
            return;
        }

        adjustConfig.referrer = this.referrer;
        adjustConfig.referrerClickTime = this.referrerClickTime;

        activityHandler = ActivityHandler.getInstance(adjustConfig);
    }

    public void trackEvent(AdjustEvent event) {
        if (!checkActivityHandler()) return;
        activityHandler.trackEvent(event);
    }

    public void onResume() {
        if (!checkActivityHandler()) return;
        activityHandler.onResume();
    }

    public void onPause() {
        if (!checkActivityHandler()) return;
        activityHandler.onPause();
    }

    public void setEnabled(boolean enabled) {
        if (!checkActivityHandler()) return;
        activityHandler.setEnabled(enabled);
    }

    public boolean isEnabled() {
        if (!checkActivityHandler()) return false;
        return activityHandler.isEnabled();
    }

    public void appWillOpenUrl(Uri url) {
        if (!checkActivityHandler()) return;
        long clickTime = System.currentTimeMillis();
        activityHandler.readOpenUrl(url, clickTime);
    }

    public void sendReferrer(String referrer) {
        long clickTime = System.currentTimeMillis();
        // sendReferrer might be triggered before Adjust
        if (activityHandler == null) {
            // save it to inject in the config before launch
            this.referrer = referrer;
            this.referrerClickTime = clickTime;
        } else {
            activityHandler.sendReferrer(referrer, clickTime);
        }
    }

    public void setOfflineMode(boolean enabled) {
        if (!checkActivityHandler()) return;
        activityHandler.setOfflineMode(enabled);
    }


    public void sendFirstPackages() {
        if (!checkActivityHandler()) return;
        activityHandler.sendFirstPackages();
    }

    public void addExternalDeviceId(String externalDeviceId) {
        if (activityHandler != null) {
            activityHandler.addExternalDeviceId(externalDeviceId);
        }

        // TODO action list
    }

    public void addSessionCallbackParameter(String key, String value) {
        if (activityHandler != null) {
            activityHandler.addSessionCallbackParameter(key, value);
        }

        // TODO action list
    }

    public void addSessionPartnerParameter(String key, String value) {
        if (activityHandler != null) {
            activityHandler.addSessionPartnerParameter(key, value);
        }

        // TODO action list
    }

    private boolean checkActivityHandler() {
        if (activityHandler == null) {
            getLogger().error("Adjust not initialized correctly");
            return false;
        } else {
            return true;
        }
    }
}
