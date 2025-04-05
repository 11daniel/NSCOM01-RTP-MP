import sys
import time
from sip_utils import SIPClient
from rtp_utils import RTPSender
from audio_utils import AudioFileHandler

def main():
    if len(sys.argv) < 6:
        print("Usage: python client1.py <local_ip> <local_port> <remote_ip> <remote_port> <audio_file>")
        return
    
    local_ip = sys.argv[1]
    local_port = int(sys.argv[2])
    remote_ip = sys.argv[3]
    remote_port = int(sys.argv[4])
    audio_file = sys.argv[5]
    
    # Initialize SIP client
    sip_client = SIPClient(local_ip, local_port + 2, remote_ip, remote_port + 2)
    
    # Choose RTP port (even number)
    rtp_port = local_port
    
    # Send INVITE
    invite_msg = sip_client.generate_invite(rtp_port)
    print("Sending INVITE...")
    sip_client.send_message(invite_msg)
    
    # Wait for 200 OK
    print("Waiting for 200 OK...")
    response = sip_client.receive_message()
    if response is None:
        print("No response received")
        return
    
    status_code, headers, sdp = sip_client.parse_response(response)
    if status_code != 200:
        print(f"Received error response: {status_code}")
        return
    
    print("Received 200 OK")
    remote_rtp_port = sdp['media_port']
    
    # Send ACK
    ack_msg = sip_client.generate_ack()
    sip_client.send_message(ack_msg)
    print("Sent ACK")
    
    # Initialize RTP sender
    rtp_sender = RTPSender(local_ip, rtp_port, remote_ip, remote_rtp_port)
    
    # Load audio file
    audio_handler = AudioFileHandler(audio_file)
    print(f"Audio duration: {audio_handler.get_duration():.2f} seconds")
    
    # Send audio in RTP packets
    print("Starting RTP stream...")
    for i, chunk in enumerate(audio_handler.get_audio_chunks()):
        rtp_sender.send_packet(chunk, marker=0)
        time.sleep(0.020)  # Simulate real-time (20ms packets)
    
    # Send final packet with marker bit
    rtp_sender.send_packet(b'', marker=1)
    print("RTP stream complete")
    
    # Send BYE to terminate session
    bye_msg = sip_client.generate_bye()
    sip_client.send_message(bye_msg)
    print("Sent BYE")
    
    # Wait for 200 OK to BYE
    print("Waiting for 200 OK to BYE...")
    response = sip_client.receive_message()
    if response:
        status_code, _, _ = sip_client.parse_response(response)
        if status_code == 200:
            print("Call terminated successfully")
    
    print("Client 1 exiting")

if __name__ == "__main__":
    main()