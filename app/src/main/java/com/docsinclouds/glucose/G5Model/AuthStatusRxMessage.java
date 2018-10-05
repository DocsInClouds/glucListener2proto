package com.docsinclouds.glucose.G5Model;


import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.docsinclouds.glucose.G5CollectionService.TAG;

/**
 * Created by joeginley on 3/16/16.
 */
public class AuthStatusRxMessage extends TransmitterMessage {
    public static final int opcode = 0x5;
    public int authenticated;
    public int bonded;

    public AuthStatusRxMessage(byte[] packet) {
        if (packet.length >= 3) {
            if (packet[0] == opcode) {
                data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);

                authenticated = data.get(1);
                bonded = data.get(2);
                Log.d(TAG,"AuthRequestRxMessage:  authenticated:"+authenticated+"  bonded:"+bonded);
            }
        }
    }

    public boolean isAuthenticated() {
        return authenticated == 1;
    }
    public boolean isBonded() {
        return bonded == 1;
    }
}
