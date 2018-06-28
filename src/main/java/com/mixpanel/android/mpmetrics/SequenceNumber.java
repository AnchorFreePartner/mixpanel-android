package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

class SequenceNumber {
    @NonNull private static final String TAG = "SequenceNumber";
    @NonNull private static final String KEY_SEQ_NO = "seq_no";
    @Nullable private static SequenceNumber sequenceNumberInstance;
    private static long sequenceNumber;
    @NonNull private final SharedPreferences preferences;

    private SequenceNumber(@NonNull final Context context) {
        preferences = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        sequenceNumber = preferences.getLong(KEY_SEQ_NO, 0L);
    }

    @NonNull
    static SequenceNumber getInstance(@NonNull final Context context) {
        SequenceNumber local = sequenceNumberInstance;
        if (local == null) {
            synchronized (TAG) {
                local = sequenceNumberInstance;
                if (local == null) {
                    sequenceNumberInstance = local = new SequenceNumber(context);
                }
            }
        }
        return local;
    }

    synchronized long getSequenceNumberAndIncrement() {
        final long currentSeqNo = sequenceNumber;
        preferences.edit().putLong(KEY_SEQ_NO, ++sequenceNumber).apply();
        return currentSeqNo;
    }
}
