# Real-Time Audio Streaming over IP

This project implements a simple VoIP system using SIP for signaling and RTP for audio transport between two clients.

## Requirements

- Python 3.x
- PyAudio (`pip install pyaudio`)
- WAV audio file for testing

## How to Run

1. Open two terminal windows
2. In the first terminal (Client 1 - sender):
   ```bash
   python client1.py <local_ip> <local_port> <remote_ip> <remote_port> <audio_file.wav>
