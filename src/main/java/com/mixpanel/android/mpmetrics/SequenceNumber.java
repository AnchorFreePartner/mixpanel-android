package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;

class SequenceNumber {
    @NonNull private static final String TAG = "SequenceNumber";
    @NonNull private static final String KEY_SEQ_NO = "seq_no_";
    private long sequenceNumber;
    @NonNull private final SharedPreferences preferences;
    @NonNull private final String preferencesKey;

    public SequenceNumber(@NonNull final Context context, @NonNull final String token) {
        preferencesKey = KEY_SEQ_NO + token;
        preferences = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        sequenceNumber = preferences.getLong(preferencesKey, 0L);
    }

    long getSequenceNumberAndIncrement() {
        final long currentSeqNo = sequenceNumber;
        preferences.edit().putLong(preferencesKey, ++sequenceNumber).apply();
        return currentSeqNo;
    }
}
