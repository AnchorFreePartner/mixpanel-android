package com.mixpanel.android.util;

import android.content.Context;
import android.support.annotation.NonNull;
import java.io.IOException;

public interface RemoteService {
    boolean isOnline(Context context, OfflineMode offlineMode);

    void checkIsMixpanelBlocked();

    @NonNull
    RemoteResponse performRequest(@NonNull final String endpointUrl, @NonNull final String postBody)
            throws ServiceUnavailableException, IOException;

    class ServiceUnavailableException extends Exception {
        private final int mRetryAfter;

        public ServiceUnavailableException(String message, String strRetryAfter) {
            super(message);
            int retry;
            try {
                retry = Integer.parseInt(strRetryAfter);
            } catch (NumberFormatException e) {
                retry = 0;
            }
            mRetryAfter = retry;
        }

        public int getRetryAfter() {
            return mRetryAfter;
        }
    }
}
