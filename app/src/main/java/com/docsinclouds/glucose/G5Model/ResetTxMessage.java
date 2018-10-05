package com.docsinclouds.glucose.G5Model;

import android.util.Log;


import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;

import static com.docsinclouds.glucose.NotificationChannels.TAG;


/**
 * Created by jcostik1 on 3/26/16.
 */
public class ResetTxMessage extends TransmitterMessage {
    byte opcode = 0x42;
    private byte[] crc = CRC.calculate(opcode);


    public ResetTxMessage() {
        data = ByteBuffer.allocate(3);
        data.put(opcode);
        data.put(crc);
        byteSequence = data.array();
        Log.d(TAG, "ResetTx dbg: " + HelperClass.bytesToHex(byteSequence));
    }
}
