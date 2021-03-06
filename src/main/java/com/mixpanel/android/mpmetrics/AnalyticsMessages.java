package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.util.RemoteResponse;
import com.mixpanel.android.util.RemoteService;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

import static java.net.HttpURLConnection.HTTP_OK;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical Mixpanel thread.
 */
/* package */
@SuppressWarnings("WeakerAccess")
class AnalyticsMessages {

    // Messages for our thread
    private static final int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static final int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static final int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static final int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static final int INSTALL_DECIDE_CHECK = 12; // Run this DecideCheck at intervals until it isDestroyed()
    @NonNull private static final String LOGTAG = "MixpanelAPI.Messages";
    @NonNull private static final Map<Context, AnalyticsMessages> sInstances = new HashMap<>();
    @NonNull protected final Context mContext;
    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.
    @NonNull protected final MPConfig mConfig;
    @NonNull protected final String mToken;
    @NonNull private final SequenceNumber sequenceNumber;
    // Used across thread boundaries
    @NonNull private final Worker mWorker;

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /*protected*/ AnalyticsMessages(@NonNull final Context context, @NonNull final String token) {
        mContext = context;
        mToken = token;
        mConfig = getConfig(context, token);
        mWorker = createWorker();
        sequenceNumber = new SequenceNumber(context, token);
    }

    /** Only for test purposes */
    /*protected*/ AnalyticsMessages(@NonNull final Context context) {
        this(context, "test_token");
    }

    ////////////////////////////////////////////////////

    /**
     * Use this to get an instance of AnalyticsMessages instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     * associated with these messages.
     */
    public static AnalyticsMessages getInstance(final Context messageContext, final String token) {
        return new AnalyticsMessages(messageContext, token);
    }

    protected Worker createWorker() {
        return new Worker();
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void peopleMessage(final PeopleDescription peopleDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleDescription;

        mWorker.runMessage(m);
    }

    public void postToServer(final FlushDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = flushDescription.getToken();
        m.arg1 = flushDescription.shouldCheckDecide() ? 1 : 0;

        mWorker.runMessage(m);
    }

    public void installDecideCheck(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = INSTALL_DECIDE_CHECK;
        m.obj = check;

        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }
    /////////////////////////////////////////////////////////

    protected MPDbAdapter makeDbAdapter(Context context, final String token) {
        return MPDbAdapter.getInstance(context, token);
    }

    protected MPConfig getConfig(Context context, final String token) {
        return MPConfig.getInstance(context, token);
    }

    // Sends a message if and only if we are running with Mixpanel Message log enabled.
    // Will be called from the Mixpanel thread.
    private void logAboutMessageToMixpanel(String message) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
    }

    private void logAboutMessageToMixpanel(String message, Throwable e) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
    }

    public long getTrackEngageRetryAfter() {
        return ((Worker.AnalyticsMessageHandler) mWorker.mHandler).getTrackEngageRetryAfter();
    }

    static class EventDescription extends MixpanelDescription {
        private final String mEventName;
        private final JSONObject mProperties;
        private final boolean mIsAutomatic;

        public EventDescription(String eventName, JSONObject properties, String token, boolean isAutomatic) {
            super(token);
            mEventName = eventName;
            mProperties = properties;
            mIsAutomatic = isAutomatic;
        }

        public String getEventName() {
            return mEventName;
        }

        public JSONObject getProperties() {
            return mProperties;
        }

        public boolean isAutomatic() {
            return mIsAutomatic;
        }
    }

    static class PeopleDescription extends MixpanelDescription {
        private final JSONObject message;

        public PeopleDescription(JSONObject message, String token) {
            super(token);
            this.message = message;
        }

        @Override
        public String toString() {
            return message.toString();
        }

        public JSONObject getMessage() {
            return message;
        }
    }

    static class FlushDescription extends MixpanelDescription {
        private final boolean checkDecide;

        public FlushDescription(String token) {
            this(token, true);
        }

        protected FlushDescription(String token, boolean checkDecide) {
            super(token);
            this.checkDecide = checkDecide;
        }

        public boolean shouldCheckDecide() {
            return checkDecide;
        }
    }

    static class MixpanelDescription {
        private final String mToken;

        public MixpanelDescription(String token) {
            this.mToken = token;
        }

        public String getToken() {
            return mToken;
        }
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    class Worker {
        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;

        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized (mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized (mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToMixpanel("Dead mixpanel worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        protected Handler restartWorkerThread() {
            final HandlerThread thread = new HandlerThread("com.mixpanel.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            return new AnalyticsMessageHandler(thread.getLooper());
        }

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToMixpanel("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        class AnalyticsMessageHandler extends Handler {
            private final DecideChecker mDecideChecker;
            private final long mFlushInterval;
            private MPDbAdapter mDbAdapter;
            private long mDecideRetryAfter;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;

            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
                mSystemInformation = SystemInformation.getInstance(mContext);
                mDecideChecker = createDecideChecker();
                mFlushInterval = mConfig.getFlushInterval();
            }

            protected DecideChecker createDecideChecker() {
                return new DecideChecker(mContext, mConfig, mToken);
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext, mToken);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.PEOPLE);
                }

                try {
                    int returnCode = MPDbAdapter.DB_UNDEFINED_CODE;
                    String token = null;

                    if (msg.what == ENQUEUE_PEOPLE) {
                        final PeopleDescription message = (PeopleDescription) msg.obj;

                        logAboutMessageToMixpanel("Queuing people record for sending later");
                        logAboutMessageToMixpanel("    " + message.toString());
                        token = message.getToken();
                        returnCode = mDbAdapter.addJSON(message.getMessage(), token, MPDbAdapter.Table.PEOPLE, false);
                    } else if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToMixpanel("Queuing event for sending later");
                            logAboutMessageToMixpanel("    " + message.toString());
                            token = eventDescription.getToken();

                            DecideMessages decide = mDecideChecker.getDecideMessages(token);
                            if (decide != null && eventDescription.isAutomatic() && !decide.shouldTrackAutomaticEvent()) {
                                return;
                            }
                            returnCode = mDbAdapter.addJSON(message, token, MPDbAdapter.Table.EVENTS, eventDescription.isAutomatic());
                        } catch (final JSONException e) {
                            MPLog.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    } else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToMixpanel("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        token = (String) msg.obj;
                        final boolean shouldCheckDecide = msg.arg1 == 1;
                        sendAllData(mDbAdapter, token);
                        if (shouldCheckDecide && SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, mConfig.getRemoteService());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (msg.what == INSTALL_DECIDE_CHECK) {
                        logAboutMessageToMixpanel("Installing a check for in-app notifications");
                        final DecideMessages check = (DecideMessages) msg.obj;
                        mDecideChecker.addDecideCheck(check);
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(check.getToken(), mConfig.getRemoteService());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (msg.what == KILL_WORKER) {
                        MPLog.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized (mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            final Looper looper = Looper.myLooper();
                            if (looper != null) {
                                looper.quit();
                            }
                        }
                    } else {
                        MPLog.e(LOGTAG, "Unexpected message received by Mixpanel worker: " + msg);
                    }

                    ///////////////////////////
                    if ((returnCode >= mConfig.getBulkUploadLimit() || returnCode == MPDbAdapter.DB_OUT_OF_MEMORY_ERROR) && mFailedRetries <= 0 && token != null) {
                        logAboutMessageToMixpanel("Flushing queue due to bulk upload limit (" + returnCode + ") for project " + token);
                        updateFlushFrequency();
                        sendAllData(mDbAdapter, token);
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, mConfig.getRemoteService());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (returnCode > 0 && !hasMessages(FLUSH_QUEUE, token)) {
                        // The !hasMessages(FLUSH_QUEUE, token) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToMixpanel("Queue depth " + returnCode + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = token;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    MPLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            final Looper looper = Looper.myLooper();
                            if (looper != null) {
                                looper.quit();
                            }
                            MPLog.e(LOGTAG, "Mixpanel will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            MPLog.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }// handleMessage

            protected long getTrackEngageRetryAfter() {
                return mTrackEngageRetryAfter;
            }

            private void sendAllData(MPDbAdapter dbAdapter, String token) {
                final RemoteService poster = mConfig.getRemoteService();
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessageToMixpanel("Not flushing data to Mixpanel because the device is not connected to the internet.");
                    return;
                }

                final List<String> endpoints = new ArrayList<>();
                final String eventsEndpoint = mConfig.getEventsEndpoint();
                if (eventsEndpoint != null) {
                    endpoints.add(eventsEndpoint);
                }
                final List<String> eventsFallbackEndpoints = mConfig.getEventsFallbackEndpoints();
                if (eventsFallbackEndpoints != null) {
                    endpoints.addAll(eventsFallbackEndpoints);
                }
                sendData(dbAdapter, token, MPDbAdapter.Table.EVENTS, endpoints);
            }

            private void sendData(final MPDbAdapter dbAdapter, final String token,
                    final MPDbAdapter.Table table, final List<String> urls) {
                DecideMessages decideMessages = mDecideChecker.getDecideMessages(token);
                boolean includeAutomaticEvents = true;
                if (decideMessages == null || decideMessages.isAutomaticEventsEnabled() == null) {
                    includeAutomaticEvents = false;
                }
                String[] eventsData = dbAdapter.generateDataString(table, token, includeAutomaticEvents);
                Integer queueCount = 0;
                if (eventsData != null) {
                    queueCount = Integer.valueOf(eventsData[2]);
                }

                while (eventsData != null && queueCount > 0) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];
                    boolean deleteEvents = false;
                    for (final String url : urls) {
                        try {
                            final RemoteService poster = mConfig.getRemoteService();
                            final RemoteResponse response = poster.performRequest(url, rawMessage);
                            deleteEvents = response.getResponseCode() == HTTP_OK; // Delete events on any successful post, regardless of 1 or 0 response
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
                                removeMessages(FLUSH_QUEUE, token);
                            }

                            logAboutMessageToMixpanel("Posted to " + url + ": \n" + rawMessage);
                            logAboutMessageToMixpanel("Response code = " + response.getResponseCode());
                            logAboutMessageToMixpanel("Response message = " + response.getResponseMessage());
                            break;
                        } catch (final OutOfMemoryError e) {
                            MPLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                            deleteEvents = false;
                        } catch (final MalformedURLException e) {
                            MPLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                            deleteEvents = false;
                        } catch (final RemoteService.ServiceUnavailableException e) {
                            logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                            deleteEvents = false;
                            mTrackEngageRetryAfter = e.getRetryAfter() * 1000;
                        } catch (final IOException e) {
                            logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                            deleteEvents = false;
                        }
                    }

                    if (deleteEvents) {
                        logAboutMessageToMixpanel("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table, token, includeAutomaticEvents);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter = Math.max((long) Math.pow(2, mFailedRetries) * 60000, mTrackEngageRetryAfter);
                        mTrackEngageRetryAfter = Math.min(mTrackEngageRetryAfter, 10 * 60 * 1000); // limit 10 min
                        final Message flushMessage = Message.obtain();
                        flushMessage.what = FLUSH_QUEUE;
                        flushMessage.obj = token;
                        sendMessageDelayed(flushMessage, mTrackEngageRetryAfter);
                        mFailedRetries++;
                        logAboutMessageToMixpanel("Retrying this batch of events in " + mTrackEngageRetryAfter + " ms");
                        break;
                    }

                    eventsData = dbAdapter.generateDataString(table, token, includeAutomaticEvents);
                    if (eventsData != null) {
                        queueCount = Integer.valueOf(eventsData[2]);
                    }
                }
            }

            private String getLocalTime() {
                try {
                    final Calendar c = Calendar.getInstance();
                    final int offset = (c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / (60 * 1000);
                    final int hourOffset = Math.abs(offset / 60);
                    final int minuteOffset = Math.abs(offset % 60);
                    final String tz;
                    if (offset == 0) {
                        tz = "Z";
                    } else {
                        tz = ((offset > 0) ? "-" : "+") + String.format(Locale.ENGLISH, "%02d", hourOffset)
                                + ":" + String.format(Locale.ENGLISH, "%02d", minuteOffset);
                    }
                    final DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss" + tz, Locale.ENGLISH);
                    return format.format(c.getTime());
                } catch (Throwable t) {
                    return "ERROR";
                }
            }

            private JSONObject prepareEventObject(final EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = new JSONObject();
                sendProperties.put("seq_no", sequenceNumber.getSequenceNumberAndIncrement());
                long ts = System.currentTimeMillis();
                if (eventProperties != null) {
                    final Iterator<String> iterator = eventProperties.keys();
                    while (iterator.hasNext()) {
                        final String key = iterator.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                    try {
                        ts = eventProperties.getLong("time");
                    } catch (final Throwable ignored) { }
                }
                eventObj.put("event", eventDescription.getEventName().toLowerCase(Locale.ENGLISH)
                        .replace(" ", "_").replace("-", "_"));
                eventObj.put("ts", ts);
                eventObj.put("payload", sendProperties);
                return eventObj;
            }
        }// AnalyticsMessageHandler
    }
}
