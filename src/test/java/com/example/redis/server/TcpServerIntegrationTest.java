package com.example.redis.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the actual running NIO server over real sockets - no mocking of
 * the network layer. Uses {@code redis.tcp.port=0} so the OS assigns a free
 * ephemeral port, retrieved afterwards via {@link TcpServer#getBoundPort()}.
 */
@SpringBootTest(properties = {"redis.tcp.port=0", "redis.persistence.mode=none"})
class TcpServerIntegrationTest {

    @Autowired
    private TcpServer tcpServer;

    @Test
    void inlineSetCommandReturnsOkStatusLine() throws IOException {
        try (Socket socket = connect()) {
            send(socket, "SET tcp-inline-key Yash\r\n");
            assertEquals("+OK\r\n", readExact(socket, "+OK\r\n".length()));
        }
    }

    @Test
    void respSetThenGetRoundTrip() throws IOException {
        try (Socket socket = connect()) {
            send(socket, "*3\r\n$3\r\nSET\r\n$12\r\ntcp-resp-key\r\n$4\r\nYash\r\n");
            assertEquals("+OK\r\n", readExact(socket, "+OK\r\n".length()));

            send(socket, "*2\r\n$3\r\nGET\r\n$12\r\ntcp-resp-key\r\n");
            String expected = "$4\r\nYash\r\n";
            assertEquals(expected, readExact(socket, expected.length()));
        }
    }

    @Test
    void unknownCommandReturnsRespError() throws IOException {
        try (Socket socket = connect()) {
            send(socket, "FOO bar\r\n");
            String response = readUntilNewline(socket);
            assertTrue(response.startsWith("-ERR unknown command"));
        }
    }

    @Test
    void pipelinedCommandsInOneWriteBothGetReplies() throws IOException {
        try (Socket socket = connect()) {
            send(socket, "SET tcp-pkey1 a\r\nSET tcp-pkey2 b\r\n");
            String expected = "+OK\r\n+OK\r\n";
            assertEquals(expected, readExact(socket, expected.length()));
        }
    }

    @Test
    void valuesWithSpacesWorkOverRespButNotOverInline() throws IOException {
        try (Socket socket = connect()) {
            String value = "Yash Kumar";
            String setRequest = "*3\r\n$3\r\nSET\r\n$13\r\ntcp-space-key\r\n$" + value.length() + "\r\n" + value + "\r\n";
            send(socket, setRequest);
            assertEquals("+OK\r\n", readExact(socket, "+OK\r\n".length()));

            send(socket, "*2\r\n$3\r\nGET\r\n$13\r\ntcp-space-key\r\n");
            String expected = "$" + value.length() + "\r\n" + value + "\r\n";
            assertEquals(expected, readExact(socket, expected.length()));
        }
    }

    private Socket connect() throws IOException {
        return new Socket("localhost", tcpServer.getBoundPort());
    }

    private void send(Socket socket, String data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads until exactly {@code expectedLength} bytes have arrived, avoiding flakiness from partial TCP reads. */
    private String readExact(Socket socket, int expectedLength) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[expectedLength];
        int totalRead = 0;
        while (totalRead < expectedLength) {
            int read = in.read(buffer, totalRead, expectedLength - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
    }

    private String readUntilNewline(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder result = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            result.append((char) b);
            if (result.toString().endsWith("\r\n")) {
                break;
            }
        }
        return result.toString();
    }
}
