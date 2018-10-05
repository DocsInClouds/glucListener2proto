package com.docsinclouds.glucose.G5Model;

import android.util.Log;


import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.docsinclouds.glucose.G5CollectionService.TAG;


/**
 * Created by joeginley on 3/16/16.
 */
public class KeepAliveTxMessage extends TransmitterMessage {
    public static final int opcode = 0x6;
    private int time;


    public KeepAliveTxMessage(int time) {
        this.time = time;

        data = ByteBuffer.allocate(2);
        data.put(new byte[]{(byte) opcode, (byte) this.time});
        byteSequence = data.order(ByteOrder.LITTLE_ENDIAN).array();

        Log.d(TAG, "New KeepAliveRequestTxMessage: " + HelperClass.bytesToHex(byteSequence));

    }
}