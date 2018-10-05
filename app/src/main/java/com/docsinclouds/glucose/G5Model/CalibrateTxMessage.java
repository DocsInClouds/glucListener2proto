package com.docsinclouds.glucose.G5Model;


// created by jamorham

import android.util.Log;

import com.docsinclouds.glucose.HelperClass;

import static com.docsinclouds.glucose.G5CollectionService.TAG;

public class CalibrateTxMessage extends TransmitterMessage {

    final byte opcode = 0x34;
    final int length = 9;

    public CalibrateTxMessage(int glucose, int dexTime) {
        init(opcode, length);
        data.putShort((short) glucose);
        data.putInt(dexTime);
        appendCRC();
        Log.d(TAG, "CalibrateGlucoseTxMessage dbg: " + HelperClass.bytesToHex(byteSequence));
    }

}
