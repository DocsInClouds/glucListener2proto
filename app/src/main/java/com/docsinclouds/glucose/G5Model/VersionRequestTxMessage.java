package com.docsinclouds.glucose.G5Model;

import android.util.Log;


import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;

import static com.docsinclouds.glucose.NotificationChannels.TAG;


/**
 * Created by jamorham on 25/11/2016.
 */

public class VersionRequestTxMessage extends TransmitterMessage {

    byte opcode = 0x4A;
    private byte[] crc = CRC.calculate(opcode);

    public VersionRequestTxMessage() {
        data = ByteBuffer.allocate(3);
        data.put(opcode);
        data.put(crc);
        byteSequence = data.array();
        Log.e(TAG, "VersionTx dbg: " + HelperClass.bytesToHex(byteSequence));
    }
}

