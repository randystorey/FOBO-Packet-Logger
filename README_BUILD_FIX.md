# Codemagic build fix

This revision removes the dependency on a missing Gradle wrapper (`gradlew`).
Codemagic downloads Gradle 8.9 from the official Gradle distribution service and runs `assembleDebug` directly.
