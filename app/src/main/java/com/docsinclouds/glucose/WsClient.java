package com.docsinclouds.glucose;

import static com.docsinclouds.glucose.WebsocketService.TAG;

import android.util.Log;
import android.widget.Toast;
import com.docsinclouds.glucose.GlucoseDataBase.GlucoEntity;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;

public class WsClient extends WebSocketClient {

  public WsClient(URI serverUri, Draft draft) {
    super(serverUri, draft);
  }

  public WsClient(URI serverURI) {
    super(serverURI);
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
    Log.i(TAG, "new connection opened - transmitting last database entries");
    // send last history (10 items ) to toolbox on connect
    List<GlucoEntity> list = MainActivity.glucoseDatabase.glucoDao().getLastEntries(10);
    byte[] mes = ProtoMessageBuilder.buildMultipleRawValueBytesFromDatabase(list);
    send(mes);
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    System.out.println("closed with exit code " + code + " additional info: " + reason);
  }

  @Override
  public void onMessage(String message) {
    System.out.println("received message: " + message);
  }

  @Override
  public void onMessage(ByteBuffer message) {
    System.out.println("received ByteBuffer");
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("an error occurred:" + ex);
  }
}

