import java.io.*;
import java.net.*;
import javax.sound.sampled.*;

public class SIPServer {
    private static final int SIP_PORT = 5060;
    private static final int RTP_PORT = 4000;
    private static final int RTP_HEADER_SIZE = 12;
    private static final int FORMAT_PACKET_TYPE = 127;

    public static void main(String[] args) throws Exception {
        DatagramSocket sipSocket = new DatagramSocket(SIP_PORT);
        System.out.println("SIP Server listening on port " + SIP_PORT + "...");

        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        // wait for INVITE
        sipSocket.receive(packet);
        String request = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received INVITE:\n" + request);

        String[] lines = request.split("\r\n");
        String via = "", from = "", to = "", callId = "", cseq = "", contact = "";
        for (String line : lines) {
            if (line.startsWith("Via:")) via = line;
            if (line.startsWith("From:")) from = line;
            if (line.startsWith("To:")) to = line;
            if (line.startsWith("Call-ID:")) callId = line;
            if (line.startsWith("CSeq:")) cseq = line;
            if (line.startsWith("Contact:")) contact = line;
        }

        // 200 OK
        String sdpAnswer = "v=0\r\n" +
                "o=server 789012 210987 IN IP4 localhost\r\n" +
                "s=VoIP Call\r\n" +
                "c=IN IP4 localhost\r\n" +
                "t=0 0\r\n" +
                "m=audio " + RTP_PORT + " RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n";

        String response = "SIP/2.0 200 OK\r\n" +
                via + "\r\n" +
                from + "\r\n" +
                to + ";tag=server123\r\n" +
                callId + "\r\n" +
                cseq + "\r\n" +
                contact + "\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: " + sdpAnswer.length() + "\r\n\r\n" +
                sdpAnswer;

        sipSocket.send(new DatagramPacket(response.getBytes(), response.length(), packet.getAddress(), packet.getPort()));
        System.out.println("Sent 200 OK with SDP.");

        // wait for ACK
        sipSocket.receive(packet);
        String ack = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received ACK:\n" + ack);


        Thread rtpThread = new Thread(() -> startRtpReceiver());
        rtpThread.start();

        //wait for BYE
        sipSocket.receive(packet);
        String byeMsg = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received BYE:\n" + byeMsg);

        System.out.println("Call ended by client. Server shutting down.");
        sipSocket.close();
        System.exit(0);
    }

    private static void startRtpReceiver() {
        try (DatagramSocket socket = new DatagramSocket(RTP_PORT)) {
            byte[] buffer = new byte[2048];
            AudioFormat format = null;
            SourceDataLine speaker = null;

            System.out.println("RTP Receiver listening on port " + RTP_PORT);

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                int payloadType = buffer[1] & 0x7F;

                if (payloadType == 200) {
                    System.out.println("[DEBUG] Received stop packet from " + packet.getAddress());
                    if (speaker != null) {
                        System.out.println("[DEBUG] Stopping audio playback...");
                        speaker.stop();
                        System.out.println("[DEBUG] Flushing buffers...");
                        speaker.flush();
                        System.out.println("[DEBUG] Closing audio line...");
                        speaker.close();
                        speaker = null;
                        System.out.println("[DEBUG] Audio resources released");
                    }
                    // Clear any remaining packets in socket buffer
                    socket.setSoTimeout(100);
                    try {
                        while (true) {
                            socket.receive(packet);
                        }
                    } catch (SocketTimeoutException e) {
                        // Expected - buffer cleared
                    }
                    socket.setSoTimeout(0);
                    continue;
                }

                if (payloadType == FORMAT_PACKET_TYPE) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(buffer, RTP_HEADER_SIZE, packet.getLength() - RTP_HEADER_SIZE);
                    DataInputStream dis = new DataInputStream(bais);

                    float sampleRate = dis.readFloat();
                    int sampleSize = dis.readInt();
                    int channels = dis.readInt();
                    boolean signed = dis.readBoolean();
                    boolean bigEndian = dis.readBoolean();

                    format = new AudioFormat(sampleRate, sampleSize, channels, signed, bigEndian);
                    System.out.println("[INFO] Received format: " + sampleRate + " Hz, " + sampleSize + " bit, " +
                            channels + " ch, signed=" + signed + ", bigEndian=" + bigEndian);

                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                    speaker = (SourceDataLine) AudioSystem.getLine(info);
                    speaker.open(format);
                    speaker.start();
                } else if (format != null && speaker != null) {
                    try {
                        byte[] audioData = new byte[packet.getLength() - RTP_HEADER_SIZE];
                        System.arraycopy(buffer, RTP_HEADER_SIZE, audioData, 0, audioData.length);
                        
                        // Calculate expected buffer size based on format
                        // Calculate optimal buffer size (50ms of audio)
                        int optimalBufferSize = (int)(format.getSampleRate() * format.getFrameSize() * 0.05);
                        
                        // Wait for buffer space if needed
                        while (speaker.available() < optimalBufferSize) {
                            Thread.sleep(1);
                        }
                        
                        // Write data in chunks to prevent buffer overflow
                        int offset = 0;
                        int remaining = audioData.length;
                        while (remaining > 0) {
                            int chunkSize = Math.min(speaker.available(), remaining);
                            if (chunkSize > 0) {
                                speaker.write(audioData, offset, chunkSize);
                                offset += chunkSize;
                                remaining -= chunkSize;
                            }
                            Thread.sleep(1);
                        }
                    } catch (Exception e) {
                        System.err.println("[ERROR] Audio playback error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException | LineUnavailableException e) {
            System.out.println("[INFO] RTP Receiver stopped: " + e.getMessage());
        }
    }
}
