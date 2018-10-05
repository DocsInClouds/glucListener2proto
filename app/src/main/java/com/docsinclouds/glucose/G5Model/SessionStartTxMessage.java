package com.docsinclouds.glucose.G5Model;

import android.util.Log;

import com.docsinclouds.glucose.HelperClass;

import java.nio.ByteOrder;

import lombok.Getter;

import static com.docsinclouds.glucose.NotificationChannels.TAG;

// created by jamorham

public class SessionStartTxMessage extends TransmitterMessage {

    final byte opcode = 0x26;
    @Getter
    private final long startTime;
    @Getter
    private final int dexTime;

    public SessionStartTxMessage(int dexTime) {
        this((int) (HelperClass.tsl() / 1000), dexTime);
    }

    public SessionStartTxMessage(long startTime, int dexTime) {
        this(startTime, dexTime, null);
    }

    public SessionStartTxMessage(long startTime, int dexTime, String code) {
        this.startTime = startTime;
        this.dexTime = dexTime;
        //data = ByteBuffer.allocate(code == null || new G6CalibrationParameters(code).isNullCode() ? 11 : 15);
        data.order(ByteOrder.LITTLE_ENDIAN);
        data.put(opcode);
        data.putInt(dexTime);
        data.putInt((int) (startTime / 1000));

        if (code != null) {
            Log.d(TAG, "Breakpoint"); //Übersprungen
            /*
            final G6CalibrationParameters params = new G6CalibrationParameters(code);
            if (params.isValid() && !params.isNullCode()) {
                data.putShort((short) params.getParamA());
                data.putShort((short) params.getParamB());
            } else {
                if (!params.isValid()) {
                    throw new IllegalArgumentException("Invalid G6 code in SessionStartTxMessage");
                }
            }*/
        }
        appendCRC();
       Log.d(TAG, "SessionStartTxMessage dbg: " + HelperClass.bytesToHex(byteSequence));
    }

}
