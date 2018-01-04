package com.mixpanel.android.util;

import android.content.Context;
import java.io.IOException;
import javax.net.ssl.SSLSocketFactory;

public interface RemoteService {
    boolean isOnline(Context context, OfflineMode offlineMode);

    void checkIsMixpanelBlocked();

    HttpResponse performRequest(String endpointUrl, String body, SSLSocketFactory socketFactory)
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
