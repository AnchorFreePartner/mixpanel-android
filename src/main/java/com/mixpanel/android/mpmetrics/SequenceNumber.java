package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;

class SequenceNumber {
    @NonNull private static final String TAG = "SequenceNumber";
    @NonNull private static final String KEY_SEQ_NO = "seq_no_";
    private long sequenceNumber;
    @NonNull private final SharedPreferences preferences;

    public SequenceNumber(@NonNull final Context context) {
        preferences = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        sequenceNumber = preferences.getLong(KEY_SEQ_NO + hashCode(), 0L);
    }

    long getSequenceNumberAndIncrement() {
        final long currentSeqNo = sequenceNumber;
        preferences.edit().putLong(KEY_SEQ_NO + hashCode(), ++sequenceNumber).apply();
        return currentSeqNo;
    }
}
