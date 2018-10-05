package com.docsinclouds.glucose;


import com.docsinclouds.glucose.GlucoseDataBase.GlucoEntity;
import java.util.List;

/**
 * used to serialize the data before transmitting it
 */
public class ProtoMessageBuilder {

  /**
   * build a RawValues message containing a single SensorValue message
   * @param value glucose value to be stored as integer
   * @param timestamp corresponding timestamp for the glucose mesaurement
   * @return RawValues message serialized to a byte array
   */
  public static byte[] buildSingleRawValueBytes (int value, String timestamp) {
    // Create message that is sent via protobuf
    GlucoseProtos.SensorValue singleValueProto = buildSensorValue(value, timestamp);

    // Put single value in rawValues

    GlucoseProtos.RawValues multipleValuesProto =
        GlucoseProtos.RawValues.newBuilder()
            .addSensorValues(singleValueProto)
            .build();

    return GlucoseProtos.MetaMessage.newBuilder()
        .setRaw( multipleValuesProto).build().toByteArray();
  }

  /**
   * build a RawValues message that can contain more than one SensorValue message.
   * It can be used with the return value of database queries (see parameters)
   * @param entityList list with GlucoEntities that can result from a room database query.
   * @return serialized RawValues protobuf message as byte array
   */
  public static byte[] buildMultipleRawValueBytesFromDatabase (List<GlucoEntity> entityList) {
    // Create message that is sent via protobuf
    GlucoseProtos.RawValues.Builder multipleValuesProtoBuilder =
        GlucoseProtos.RawValues.newBuilder();

    for (GlucoEntity entity:entityList) {

      GlucoseProtos.SensorValue singleValueProto = buildSensorValue(entity.getGlucoValue(),
          entity.getTimestamp());
      multipleValuesProtoBuilder.addSensorValues(singleValueProto);
    }

    return GlucoseProtos.MetaMessage.newBuilder()
        .setRaw( multipleValuesProtoBuilder.build()).build().toByteArray();
  }

  private static GlucoseProtos.SensorValue buildSensorValue (int value, String timestamp) {
    return GlucoseProtos.SensorValue.newBuilder()
        .setValue(value)
        .setTimestamp(timestamp)
        .setSensorType("G5")
        .build();
  }
}
