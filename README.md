# Real-Time Audio Streaming over IP

This project demonstrates a simple SIP (Session Initiation Protocol) client-server implementation with RTP (Real-time Transport Protocol) for audio streaming.

## Requirements

- Java Development Kit (JDK) installed
- Two separate terminal windows or tabs
- WAV audio file for testing

## How to Run

1. Open two terminal windows
2. **Compile the source files**:
   ```bash
   javac SIPServer.java SIPClient.java
3. In the first terminal:
   ```bash
   java SIPServer
4. In the 2nd terminal (Client 2 - receiver)
   ```bash
   java SIPClient
