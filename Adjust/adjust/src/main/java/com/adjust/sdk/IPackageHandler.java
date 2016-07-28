package com.adjust.sdk;

import android.content.Context;

public interface IPackageHandler {
    public void init(IActivityHandler activityHandler, Context context, boolean startsSending);

    public void addPackage(ActivityPackage activityPackage);

    public void sendFirstPackage();

    public void sendNextPackage(ResponseData responseData);

    public void closeFirstPackage(ResponseData responseData, ActivityPackage activityPackage);

    public void pauseSending();

    public void resumeSending();

    public void updatePackages();
}
