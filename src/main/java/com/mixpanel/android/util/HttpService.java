package com.mixpanel.android.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import com.mixpanel.android.mpmetrics.MPConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * An HTTP utility class for internal use in the Mixpanel library. Not thread-safe.
 */
public class HttpService implements RemoteService {

    private static final String LOGTAG = "MixpanelAPI.Message";
    private static boolean sIsMixpanelBlocked;
    @NonNull private final OkHttpClient okHttpClient;

    public HttpService() {
        okHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(10L, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void checkIsMixpanelBlocked() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    InetAddress apiMixpanelInet = InetAddress.getByName("api.mixpanel.com");
                    InetAddress decideMixpanelInet = InetAddress.getByName("decide.mixpanel.com");
                    sIsMixpanelBlocked = apiMixpanelInet.isLoopbackAddress() ||
                            apiMixpanelInet.isAnyLocalAddress() ||
                            decideMixpanelInet.isLoopbackAddress() ||
                            decideMixpanelInet.isAnyLocalAddress();
                    if (sIsMixpanelBlocked) {
                        MPLog.v(LOGTAG, "AdBlocker is enabled. Won't be able to use Mixpanel services.");
                    }
                } catch (Exception ignored) { }
            }
        });

        t.start();
    }

    @SuppressLint("MissingPermission")
    @Override
    public boolean isOnline(Context context, OfflineMode offlineMode) {
        if (sIsMixpanelBlocked) return false;
        if (onOfflineMode(offlineMode)) return false;

        boolean isOnline;
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
            if (netInfo == null) {
                isOnline = true;
                MPLog.v(LOGTAG, "A default network has not been set so we cannot be certain whether we are offline");
            } else {
                isOnline = netInfo.isConnectedOrConnecting();
                MPLog.v(LOGTAG, "ConnectivityManager says we " + (isOnline ? "are" : "are not") + " online");
            }
        } catch (final SecurityException e) {
            isOnline = true;
            MPLog.v(LOGTAG, "Don't have permission to check connectivity, will assume we are online");
        }
        return isOnline;
    }

    private boolean onOfflineMode(OfflineMode offlineMode) {
        boolean onOfflineMode;

        try {
            onOfflineMode = offlineMode != null && offlineMode.isOffline();
        } catch (Exception e) {
            onOfflineMode = false;
            MPLog.v(LOGTAG, "Client State should not throw exception, will assume is not on offline mode", e);
        }

        return onOfflineMode;
    }

    @NonNull
    @Override
    public RemoteResponse performRequest(@NonNull final String endpointUrl, @NonNull final String postBody) throws IOException {
        MPLog.v(LOGTAG, "Attempting request to " + endpointUrl);
        final RequestBody requestBody = RequestBody.create(null, postBody);
        final Request.Builder builderRequest = new Request.Builder();
        final Request request = fillHeaders(builderRequest)
                .url(endpointUrl)
                .post(requestBody)
                .build();
        final Response response = okHttpClient.newCall(request).execute();
        final ResponseBody body = response.body();
        final RemoteResponse remoteResponse = new RemoteResponse(response.code(), response.message(), body != null ? body.string() : "");
        MPLog.d(LOGTAG, remoteResponse.toString());
        return remoteResponse;
    }

    @NonNull
    public Request.Builder fillHeaders(@NonNull final Request.Builder builderRequest) {
        return builderRequest
                .addHeader("X-AF-CLIENT-TS", String.valueOf(System.currentTimeMillis()))
                .addHeader("X_AF_DEBUG", MPConfig.DEBUG ? "1" : "0");
    }
}
