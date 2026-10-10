# FOBO Packet Logger v4

Build 4 adds a safe diagnostic subscription to indications on FOBO FEAF characteristic `...9D12`.

- Scans N0334, N0201, N02EZ, and N02JB advertisements.
- Connects to the selected sensor and discovers services.
- Calls `setCharacteristicNotification()` for 9D12.
- Writes only the standard Bluetooth CCCD descriptor `0x2902` with `0x0200` (Enable Indication).
- Logs every 9D12 indication including payload length and hex bytes.
- **Never writes to 9D11.**
- **Never writes a value to either FOBO characteristic.**

Recommended test: start scan, select sensor, press `SUBSCRIBE D12 (NO D11 WRITES)`, wait until `D12 INDICATIONS ARMED`, mark NORMAL, then remove/reinstall that same sensor while logging. Export the resulting `FOBO_v4_*.txt` file.
