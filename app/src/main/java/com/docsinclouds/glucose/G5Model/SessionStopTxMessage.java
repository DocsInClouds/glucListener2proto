package com.docsinclouds.glucose.G5Model;



// created by jamorham

import com.docsinclouds.glucose.HelperClass;

public class SessionStopTxMessage extends TransmitterMessage {

    final byte opcode = 0x28;
    final int length = 7;
    {
        postExecuteGuardTime = 1000;
    }

    SessionStopTxMessage(int stopTime) {

        init(opcode, length);
        data.putInt(stopTime);
        appendCRC();
    }

    SessionStopTxMessage(String transmitterId) {
        final int stopTime = DexTimeKeeper.getDexTime(transmitterId, HelperClass.tsl());
        init(opcode, 7);
        data.putInt(stopTime);
        appendCRC();
    }


}
