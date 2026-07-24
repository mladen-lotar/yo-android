# Yo Android

Yo Android is a minimal local-first prototype for sending a “Yo” to a selected friend and viewing sent history. The current implementation stores sends in Room; backend delivery is stubbed pending T3.

## Build

Use JDK 17 and an Android SDK with API 34 installed:

```sh
./gradlew assembleDebug
```

## Run

With an emulator or Android device connected:

```sh
./gradlew installDebug
```
