# FOBO Tire Lite Diagnostic Logger v2

Android diagnostic tool for the FOBO Tire Lite TM1802 investigation on the Jetalon Android 14 head unit.

## What v2 adds
- Logs every scan callback for N0334, N0201, N02EZ and N02JB.
- Captures timestamp, Android BLE address, RSSI, complete raw ScanRecord, FEAF service data, all service data, and manufacturer data.
- Counts advertisements per sensor.
- Automatically highlights byte changes in the FEAF payload.
- Dedicated NORMAL / SENSOR REMOVED / SENSOR REINSTALLED markers.
- Read-only GATT inspection for FEAF characteristics 9D11 and 9D12.
- Export saves a text log in the app-specific external files directory and copies it to clipboard.

## Safety / protocol rule
The GATT diagnostic deliberately performs NO characteristic writes. It reads 9D11 and 9D12 only. It does NOT enable 9D12 indications because subscribing requires writing the CCCD descriptor (0x2902). Do not add arbitrary writes until the FOBO protocol is understood.

## Recommended test
1. Start scan and wait until all four FOBO names have appeared.
2. Press NORMAL.
3. Remove one known wheel sensor; press REMOVED immediately.
4. Wait 30-60 seconds.
5. Reinstall it; press REINSTALLED.
6. Wait 60 seconds.
7. Export the log.
8. Separately, with the official FOBO app closed, select a sensor and press READ GATT (NO WRITES). Export that log too.

The finished GX470 iPhone/CarPlay app is separate; this Android app is diagnostic only.
