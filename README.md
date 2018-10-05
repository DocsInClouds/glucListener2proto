# Overview
This application implements a sensorlistener for the Dexcom G5 and sends the data to a websocket server using protobuf messages. It is based on the Nightscout xDrip+ App (https://github.com/NightscoutFoundation/xDrip), which also holds the GPL3 copyright.

# Changes
* Reduced the xDrip app to the classes that run the automata for connecting to the BluetoothLE device and the Ob1G5model
* Added a new notificationbar
* Added a new database to save the value history
* Implemented a new UI
* Added an API to send data to a websocket server
* Added a demonstration mode and manual mode that sends simulated values to the websocket server

# Install Instruction
To install this application, download Android Studio here: https://developer.android.com/studio/. Connect an Android smartphone to your computer and install the app via Android Studio. Alternatively, install the provided .apk on the smartphone.