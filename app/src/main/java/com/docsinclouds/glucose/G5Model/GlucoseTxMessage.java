package com.docsinclouds.glucose.G5Model;

import android.util.Log;


import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;

import static com.docsinclouds.glucose.G5CollectionService.TAG;


/**
 * Created by jamorham on 25/11/2016.
 */

public class GlucoseTxMessage extends TransmitterMessage {

    byte opcode = 0x30;
    byte[] crc = CRC.calculate(opcode);

    public GlucoseTxMessage() {
        data = ByteBuffer.allocate(3);
        data.put(opcode);
        data.put(crc);
        byteSequence = data.array();
        Log.d(TAG, "GlucoseTx dbg: " + HelperClass.bytesToHex(byteSequence));
    }
}

