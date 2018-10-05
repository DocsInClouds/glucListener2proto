package com.docsinclouds.glucose;
/**
 * This class contains and returns the values that are sent in the demomode
 */
public class DemoValues {

  private int[] demoValues;
  private int position;

  DemoValues() {
    demoValues = new int[] {130, 135, 140, 141, 140, 134, 120, 90, 65, 65, 69, 69, 60, 55,
        55, 53, 52, 55, 56, 56, 58, 59, 60, 65, 70, 75, 75, 68, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67, 67};
    position = 0;
  }

  /**
   * gets a certain value from the demo value list and sets the internal position counter to that field
   * @param pos position of value to be returned
   * @return value at defined position
   */
  public int getDemoValue(int pos) {
    this.position = pos % demoValues.length; // keep internal counter inside array bounds
    return demoValues[pos];
  }

  /**
   * gets the next value for the demo mode and advances the internal position counter
   * @return next simulated glucose value
   */
  public int getNextDemoValueAndAdvance() {
    if(position == demoValues.length) {  // loop through array
      position = 0;
    }

    return demoValues[position++];
  }
}
