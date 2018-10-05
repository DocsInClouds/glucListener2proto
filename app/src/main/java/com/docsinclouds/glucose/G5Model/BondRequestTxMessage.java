package com.docsinclouds.glucose.G5Model;

import android.util.Log;

import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;

import static com.docsinclouds.glucose.G5CollectionService.TAG;

/**
 * Created by joeginley on 3/16/16.
 */
public class BondRequestTxMessage extends TransmitterMessage {
    byte opcode = 0x7;

    public BondRequestTxMessage() {
        data = ByteBuffer.allocate(1);
        data.put(opcode);
        byteSequence = data.array();
        Log.d(TAG,"New BONDRequestTxMessage: "+ HelperClass.bytesToHex(byteSequence));
    }
}

