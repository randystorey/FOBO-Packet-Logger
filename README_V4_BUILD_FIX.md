# FOBO Packet Logger v4 build correction

This package corrects the v4 Kotlin compile errors from the first v4 archive:

- Removed two stale calls to `readD12()` left from the earlier read-only GATT implementation.
- Removed the invalid four-argument `onDescriptorWrite` override. Android's `BluetoothGattCallback` descriptor-write callback uses the three-argument form.
- Updated the Codemagic workflow display name to `FOBO Packet Logger v4 APK`.
- Retains Java/Kotlin JVM 17 configuration and Android app `versionCode = 4`, `versionName = "4.0"`.
- D12 subscription still writes only the standard CCCD value `0200`; it does not write to FOBO characteristic D11.
