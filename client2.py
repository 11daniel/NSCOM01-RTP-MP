import sys
import time
from sip_utils import SIPClient
from rtp_utils import RTPReceiver
from audio_utils import AudioPlayer

def main():
    if len(sys.argv) < 5:
        print("Usage: python client2.py <local_ip> <local_port> <remote_ip> <remote_port>")
        return
    
    local_ip = sys.argv[1]
    local_port = int(sys.argv[2])
    remote_ip = sys.argv[3]
    remote_port = int(sys.argv[4])
    
    # Initialize SIP client
    sip_client = SIPClient(local_ip, local_port + 2, remote_ip, remote_port + 2)
    
    # Wait for INVITE
    print("Waiting for INVITE...")
    invite = sip_client.receive_message()
    if invite is None:
        print("No INVITE received")
        return
    
    status_code, headers, sdp = sip_client.parse_response(invite)
    if status_code != 1:  # Not a response, but a request
        # Send 200 OK with SDP
        rtp_port = local_port
        sdp_response = (
            "v=0\r\n"
            f"o=- {sip_client.call_id} 0 IN IP4 {local_ip}\r\n"
            "s=VoIP Call\r\n"
            f"c=IN IP4 {local_ip}\r\n"
            "t=0 0\r\n"
            f"m=audio {rtp_port} RTP/AVP 0\r\n"
            "a=rtpmap:0 PCMU/8000\r\n"
        )
        
        ok_response = (
            "SIP/2.0 200 OK\r\n"
            f"Via: {headers['via']}\r\n"
            f"From: {headers['from']}\r\n"
            f"To: {headers['to']};tag={sip_client.tag}\r\n"
            f"Call-ID: {headers['call-id']}\r\n"
            f"CSeq: {headers['cseq']}\r\n"
            "Contact: <sip:user2@{}:{}>\r\n".format(local_ip, local_port + 2) +
            "Content-Type: application/sdp\r\n"
            "Content-Length: {}\r\n\r\n".format(len(sdp_response)) +
            sdp_response
        )
        
        sip_client.send_message(ok_response)
        print("Sent 200 OK")
        
        # Wait for ACK
        ack = sip_client.receive_message()
        if ack:
            print("Received ACK")
        
        # Initialize RTP receiver
        audio_player = AudioPlayer()
        received_packets = []
        
        def rtp_callback(payload, timestamp, sequence_number, ssrc):
            if payload:
                received_packets.append(payload)
                audio_player.play_audio(payload)
        
        rtp_receiver = RTPReceiver(local_ip, rtp_port)
        print("Starting RTP receiver...")
        rtp_receiver.receive_packets(rtp_callback)
        
        # Wait for BYE
        print("Waiting for BYE...")
        bye = sip_client.receive_message()
        if bye:
            status_code, headers, _ = sip_client.parse_response(bye)
            if status_code == 1:  # It's a BYE request
                # Send 200 OK to BYE
                ok_response = (
                    "SIP/2.0 200 OK\r\n"
                    f"Via: {headers['via']}\r\n"
                    f"From: {headers['from']}\r\n"
                    f"To: {headers['to']}\r\n"
                    f"Call-ID: {headers['call-id']}\r\n"
                    f"CSeq: {headers['cseq']}\r\n"
                    "Content-Length: 0\r\n\r\n"
                )
                sip_client.send_message(ok_response)
                print("Sent 200 OK to BYE")
        
        rtp_receiver.stop()
        audio_player.close()
        print("Client 2 exiting")

if __name__ == "__main__":
    main()