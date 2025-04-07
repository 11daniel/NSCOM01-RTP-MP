import sys
import time
import tkinter as tk
from tkinter import ttk
from sip_utils import SIPClient
from rtp_utils import RTPReceiver
from audio_utils import AudioPlayer
import threading
import socket

class VoIPReceiverGUI:
    def __init__(self, root, local_ip, local_port, remote_ip, remote_port):
        self.root = root
        self.local_ip = local_ip
        self.local_port = local_port
        self.remote_ip = remote_ip
        self.remote_port = remote_port
        self.running = False
        self.audio_player = None
        self.sip_client = None
        self.rtp_receiver = None
        
        self.setup_gui()
        
    def setup_gui(self):
        self.root.title("VoIP Receiver")
        self.root.geometry("300x150")
        
        # Status label
        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(self.root, textvariable=self.status_var).pack(pady=10)
        
        # Control buttons
        btn_frame = ttk.Frame(self.root)
        btn_frame.pack(pady=10)
        
        self.play_btn = ttk.Button(btn_frame, text="Listen", command=self.start_receiving)
        self.play_btn.pack(side=tk.LEFT, padx=5)
        
        self.stop_btn = ttk.Button(btn_frame, text="Stop", command=self.stop_receiving, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=5)
        
        # Close handler
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
    
    def start_receiving(self):
        self.status_var.set("Waiting for call...")
        self.play_btn.config(state=tk.DISABLED)
        self.stop_btn.config(state=tk.NORMAL)
        self.running = True
        
        voip_thread = threading.Thread(target=self.voip_session, daemon=True)
        voip_thread.start()
    
    def voip_session(self):
        try:
            print(f"\nStarting Client 2 (Receiver) on {self.local_ip}:{self.local_port}")
            print(f"Expecting Client 1 at {self.remote_ip}:{self.remote_port}\n")

            # Initialize SIP client with timeout
            self.sip_client = SIPClient(self.local_ip, self.local_port + 100, 
                                      self.remote_ip, self.remote_port + 100)
            self.sip_client.sip_socket.settimeout(0.5)
            
            # Wait for INVITE
            print("[SIP] Waiting for INVITE...")
            while self.running:
                try:
                    invite = self.sip_client.receive_message()
                    if invite and b"INVITE" in invite:
                        print(f"[SIP] Received INVITE")
                        break
                except socket.timeout:
                    continue
            
            if not self.running:
                return

            # Process INVITE and send 200 OK
            rtp_port = self.local_port
            ok_response = f"""SIP/2.0 200 OK
Content-Type: application/sdp
Content-Length: 122

v=0
o=- 123456 0 IN IP4 {self.local_ip}
s=VoIP Call
c=IN IP4 {self.local_ip}
t=0 0
m=audio {rtp_port} RTP/AVP 0
a=rtpmap:0 PCMU/8000"""
            self.sip_client.send_message(ok_response.encode())

            # Wait for ACK
            print("[SIP] Waiting for ACK...")
            while self.running:
                try:
                    ack = self.sip_client.receive_message()
                    if ack and b"ACK" in ack:
                        print(f"[SIP] Received ACK")
                        break
                except socket.timeout:
                    continue

            # Initialize audio player
            self.audio_player = AudioPlayer()
            print(f"[RTP] Initializing RTP receiver on port {rtp_port}")
            self.status_var.set("Receiving audio...")

            # Initialize RTP receiver
            self.rtp_receiver = RTPReceiver(self.local_ip, rtp_port)
            self.rtp_receiver.rtp_socket.settimeout(0.5)

            def callback(payload, *_):
                if payload and self.running:
                    try:
                        self.audio_player.play_audio(payload)
                    except Exception as e:
                        print(f"[AUDIO] Playback error: {e}")
                        self.cleanup()

            # Main receive loop
            while self.running:
                try:
                    self.rtp_receiver.receive_packets(callback)
                except socket.timeout:
                    continue
                except OSError:
                    break  # Socket closed

            # Wait for BYE
            print("[SIP] Waiting for BYE...")
            while self.running:
                try:
                    bye = self.sip_client.receive_message()
                    if bye and b"BYE" in bye:
                        print(f"[SIP] Received BYE")
                        # Send 200 OK to BYE
                        ok_response = "SIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n"
                        self.sip_client.send_message(ok_response.encode())
                        self.status_var.set("Call ended normally")
                        break
                except socket.timeout:
                    continue

        except Exception as e:
            print(f"\n[ERROR] Fatal error: {e}")
            self.status_var.set(f"Error: {str(e)}")
        finally:
            self.cleanup()
    
    def cleanup(self):
        """Clean up resources safely"""
        self.running = False
        
        # Close RTP receiver
        if hasattr(self, 'rtp_receiver') and self.rtp_receiver:
            try:
                self.rtp_receiver.stop()
            except Exception as e:
                print(f"Error stopping RTP receiver: {e}")

        # Close SIP client
        if hasattr(self, 'sip_client') and self.sip_client:
            try:
                self.sip_client.sip_socket.close()
            except Exception as e:
                print(f"Error closing SIP socket: {e}")

        # Close audio player
        if hasattr(self, 'audio_player') and self.audio_player:
            try:
                self.audio_player.close()
            except Exception as e:
                print(f"Error closing audio player: {e}")

        # Reset UI
        self.root.after(100, lambda: [
            self.stop_btn.config(state=tk.DISABLED),
            self.play_btn.config(state=tk.NORMAL),
            self.status_var.set("Ready")
        ])
    
    def stop_receiving(self):
        """Stop the receiver immediately"""
        self.status_var.set("Stopping...")
        self.cleanup()
    
    def on_close(self):
        """Handle window close event"""
        self.stop_receiving()
        self.root.destroy()

def main():
    if len(sys.argv) < 5:
        print("Usage: python client2.py <local_ip> <local_port> <remote_ip> <remote_port>")
        print("Example for local testing:")
        print("python client2.py 127.0.0.1 6002 127.0.0.1 6000")
        return
    
    root = tk.Tk()
    app = VoIPReceiverGUI(root, 
                         sys.argv[1], int(sys.argv[2]),
                         sys.argv[3], int(sys.argv[4]))
    root.mainloop()

if __name__ == "__main__":
    main()