package com.docsinclouds.glucose.G5Model;

import android.util.Log;

import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteBuffer;

import static com.docsinclouds.glucose.G5CollectionService.TAG;

/**
 * Created by jamorham on 25/11/2016.
 */

public class BatteryInfoTxMessage extends TransmitterMessage {

    byte opcode = 0x22;
    private byte[] crc = CRC.calculate(opcode);

    public BatteryInfoTxMessage() {
        data = ByteBuffer.allocate(3);
        data.put(opcode);
        data.put(crc);
        byteSequence = data.array();
        Log.e(TAG, "BatteryInfoTx dbg: " + HelperClass.bytesToHex(byteSequence));
    }
}

