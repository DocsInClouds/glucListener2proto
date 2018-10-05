package com.docsinclouds.glucose.G5Model;

import android.util.Log;

import com.docsinclouds.glucose.HelperClass;

import java.util.Arrays;

import static com.docsinclouds.glucose.G5CollectionService.TAG;

/**
 * Created by joeginley on 3/16/16.
 */
public class AuthChallengeRxMessage extends TransmitterMessage {
    public static final int opcode = 0x3;
    public byte[] tokenHash;
    public byte[] challenge;
    public AuthChallengeRxMessage(byte[] data) {
        Log.d(TAG,"AuthChallengeRX: "+ HelperClass.bytesToHex(data));
        if (data.length >= 17) {
            if (data[0] == opcode) {
                tokenHash = Arrays.copyOfRange(data, 1, 9);
                challenge = Arrays.copyOfRange(data, 9, 17);
            }
        }
    }
}
