import java.io.*;
import java.net.*;
import java.util.*;

import javax.sound.sampled.*;

public class SIPClient {

    private static final int SIP_PORT = 5060;

    public static void main(String[] args) throws Exception {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Enter your preferred RTP port (e.g. 5004): ");
            int rtpPort = Integer.parseInt(scanner.nextLine());

            System.out.print("Enter preferred codec (e.g. PCMU/8000): ");
            String codec = scanner.nextLine();

            DatagramSocket socket = new DatagramSocket();
            InetAddress serverAddress = InetAddress.getByName("localhost");

            String callId = UUID.randomUUID().toString();
            String branch = UUID.randomUUID().toString();
            String tag = UUID.randomUUID().toString();

            String sdp = "v=0\r\n" +
                    "o=client 123456 654321 IN IP4 localhost\r\n" +
                    "s=VoIP Call\r\n" +
                    "c=IN IP4 localhost\r\n" +
                    "t=0 0\r\n" +
                    "m=audio " + rtpPort + " RTP/AVP 96\r\n" +
                    "a=rtpmap:96 " + codec + "\r\n";

            String invite = "INVITE sip:user@localhost SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP client.local:" + rtpPort + ";branch=z9hG4bK-" + branch + "\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "From: <sip:client@localhost>;tag=" + tag + "\r\n" +
                    "To: <sip:user@localhost>\r\n" +
                    "Call-ID: " + callId + "\r\n" +
                    "CSeq: 1 INVITE\r\n" +
                    "Contact: <sip:client@localhost>\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: " + sdp.length() + "\r\n\r\n" +
                    sdp;

            socket.send(new DatagramPacket(invite.getBytes(), invite.length(), serverAddress, SIP_PORT));
            System.out.println("\nINVITE with SDP sent.");

            byte[] buffer = new byte[2048];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);
            String response = new String(buffer, 0, responsePacket.getLength());
            System.out.println("\nReceived 200 OK:\n" + response);

            String remoteIp = "localhost";
            int remotePort = 4000;

            String[] lines = response.split("\r\n");
            for (String line : lines) {
                if (line.startsWith("m=audio")) {
                    String[] parts = line.split(" ");
                    remotePort = Integer.parseInt(parts[1]);
                } else if (line.startsWith("c=IN IP4")) {
                    remoteIp = line.split(" ")[2];
                }
            }

            System.out.println("\nNegotiated Media:");
            System.out.println("  Remote IP   : " + remoteIp);
            System.out.println("  Remote Port : " + remotePort);

            // Send ACK
            String ack = "ACK sip:user@localhost SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP client.local:" + rtpPort + ";branch=z9hG4bK-" + branch + "\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "From: <sip:client@localhost>;tag=" + tag + "\r\n" +
                    "To: <sip:user@localhost>;tag=server123\r\n" +
                    "Call-ID: " + callId + "\r\n" +
                    "CSeq: 1 ACK\r\n" +
                    "Content-Length: 0\r\n\r\n";

            socket.send(new DatagramPacket(ack.getBytes(), ack.length(), serverAddress, SIP_PORT));
            System.out.println("\nACK sent.\n");

            while (true) {
                System.out.print("Enter WAV audio file to send: ");
                String audioFile = scanner.nextLine();

                RTPSender sender = new RTPSender();
                sender.startRtpStream(audioFile, remoteIp, remotePort);
                // Send multiple stop packets to ensure delivery
                for (int i = 0; i < 3; i++) {
                    sender.sendStopSignal(remoteIp, remotePort);
                    Thread.sleep(20);
                }
                // Wait for buffers to clear
                Thread.sleep(100);

                System.out.print("Send another audio file? (yes/no): ");
                String choice = scanner.nextLine().trim().toLowerCase();
                if (!choice.equals("yes")) break;
            }

            // Send BYE
            String bye = "BYE sip:user@localhost SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP client.local:" + rtpPort + ";branch=z9hG4bK-" + UUID.randomUUID() + "\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "From: <sip:client@localhost>;tag=" + tag + "\r\n" +
                    "To: <sip:user@localhost>;tag=server123\r\n" +
                    "Call-ID: " + callId + "\r\n" +
                    "CSeq: 2 BYE\r\n" +
                    "Content-Length: 0\r\n\r\n";

            socket.send(new DatagramPacket(bye.getBytes(), bye.length(), serverAddress, SIP_PORT));
            System.out.println("\nBYE sent. Call terminated.");

            socket.close();
        }
    }

    static class RTPSender {
        private static final int RTP_HEADER_SIZE = 12;
        private static final int FRAME_DURATION_MS = 20;
        private static final int STOP_PACKET_TYPE = 200;

        public void sendStopSignal(String ip, int port) throws Exception {
            DatagramSocket socket = new DatagramSocket();
            InetAddress destAddress = InetAddress.getByName(ip);
            
            byte[] stopPacket = new byte[RTP_HEADER_SIZE];
            stopPacket[1] = (byte) STOP_PACKET_TYPE;
            
            socket.send(new DatagramPacket(stopPacket, stopPacket.length, destAddress, port));
            socket.close();
            Thread.sleep(50); // Brief pause to ensure delivery
        }

        public void startRtpStream(String filePath, String ip, int port) throws Exception {
            File audioFile = new File(filePath);
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat format = audioStream.getFormat();

            if (!format.getEncoding().toString().startsWith("PCM")) {
                throw new UnsupportedAudioFileException("Only PCM WAV files are supported.");
            }

            System.out.println("\nAudio Format:");
            System.out.println("  Sample Rate: " + format.getSampleRate());
            System.out.println("  Channels   : " + format.getChannels());
            System.out.println("  Sample Size: " + format.getSampleSizeInBits() + " bit");

            int frameSize = format.getFrameSize();
            int bytesPerMs = (int) (format.getSampleRate() * frameSize / 1000);
            int payloadSize = bytesPerMs * FRAME_DURATION_MS;

            DatagramSocket socket = new DatagramSocket();
            InetAddress destAddress = InetAddress.getByName(ip);

            Random random = new Random();
            int sequenceNumber = 0;
            int timestamp = random.nextInt();
            int ssrc = random.nextInt();

            byte[] formatPacket = buildFormatPacket(format, sequenceNumber, timestamp, ssrc);
            socket.send(new DatagramPacket(formatPacket, formatPacket.length, destAddress, port));
            sequenceNumber++;
            timestamp++;

            // Send audio data
            byte[] audioBuffer = new byte[payloadSize];
            int bytesRead;

            System.out.println("\nStreaming audio via RTP to " + ip + ":" + port + "...");

            while ((bytesRead = audioStream.read(audioBuffer)) != -1) {
                byte[] rtpPacket = new byte[RTP_HEADER_SIZE + bytesRead];

                rtpPacket[0] = (byte) 0x80;
                rtpPacket[1] = 0x00;
                rtpPacket[2] = (byte) (sequenceNumber >> 8);
                rtpPacket[3] = (byte) (sequenceNumber & 0xFF);
                rtpPacket[4] = (byte) (timestamp >> 24);
                rtpPacket[5] = (byte) (timestamp >> 16);
                rtpPacket[6] = (byte) (timestamp >> 8);
                rtpPacket[7] = (byte) (timestamp);
                rtpPacket[8] = (byte) (ssrc >> 24);
                rtpPacket[9] = (byte) (ssrc >> 16);
                rtpPacket[10] = (byte) (ssrc >> 8);
                rtpPacket[11] = (byte) (ssrc);

                System.arraycopy(audioBuffer, 0, rtpPacket, RTP_HEADER_SIZE, bytesRead);
                socket.send(new DatagramPacket(rtpPacket, rtpPacket.length, destAddress, port));

                sequenceNumber++;
                timestamp += bytesRead / frameSize;

                // Calculate precise sleep time based on audio format
                long targetTime = System.currentTimeMillis() + FRAME_DURATION_MS;
                while (System.currentTimeMillis() < targetTime) {
                    Thread.sleep(1);
                }
            }

            socket.close();
            System.out.println("Finished sending WAV audio over RTP.\n");
        }

        private byte[] buildFormatPacket(AudioFormat format, int sequenceNumber, int timestamp, int ssrc) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeFloat(format.getSampleRate());
            dos.writeInt(format.getSampleSizeInBits());
            dos.writeInt(format.getChannels());
            dos.writeBoolean(format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED);
            dos.writeBoolean(format.isBigEndian());

            byte[] payload = baos.toByteArray();
            byte[] packet = new byte[RTP_HEADER_SIZE + payload.length];

            packet[0] = (byte) 0x80;
            packet[1] = (byte) 127;
            packet[2] = (byte) (sequenceNumber >> 8);
            packet[3] = (byte) (sequenceNumber & 0xFF);
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) (timestamp);
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) (ssrc);

            System.arraycopy(payload, 0, packet, RTP_HEADER_SIZE, payload.length);
            return packet;
        }
    }

    
}
