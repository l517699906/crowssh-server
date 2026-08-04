package com.llf.ai.infrastructure.adapter.port;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 从终端输出的 OSC 7 序列中提取远端 Shell 当前工作目录。
 */
final class TerminalWorkingDirectoryTracker {

    private static final int ESC = 0x1B;
    private static final int BEL = 0x07;
    private static final int STRING_TERMINATOR = 0x9C;
    private static final int MAX_OSC_PAYLOAD_BYTES = 8192;
    private static final int MAX_WORKING_DIRECTORY_LENGTH = 4096;
    private static final byte[] OSC_7_FILE_PREFIX = "7;file://".getBytes(StandardCharsets.US_ASCII);
    private static final String CROWSSH_OSC_HOST = "crowssh";

    private final Consumer<String> workingDirectoryConsumer;
    private final ByteArrayOutputStream oscPayload = new ByteArrayOutputStream(256);

    private State state = State.TEXT;

    TerminalWorkingDirectoryTracker(Consumer<String> workingDirectoryConsumer) {
        this.workingDirectoryConsumer = workingDirectoryConsumer;
    }

    void accept(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) {
            return;
        }
        int end = Math.min(bytes.length, offset + length);
        for (int index = Math.max(0, offset); index < end; index++) {
            acceptByte(bytes[index] & 0xFF);
        }
    }

    private void acceptByte(int value) {
        switch (state) {
            case TEXT -> {
                if (value == ESC) {
                    state = State.ESCAPE;
                }
            }
            case ESCAPE -> {
                if (value == ']') {
                    oscPayload.reset();
                    state = State.OSC;
                } else if (value != ESC) {
                    state = State.TEXT;
                }
            }
            case OSC -> {
                if (value == BEL || value == STRING_TERMINATOR) {
                    finishOsc();
                } else if (value == ESC) {
                    state = State.OSC_ESCAPE;
                } else {
                    appendOscByte(value);
                }
            }
            case OSC_ESCAPE -> {
                if (value == '\\') {
                    finishOsc();
                } else {
                    appendOscByte(ESC);
                    appendOscByte(value);
                    if (state != State.DISCARD_OSC) {
                        state = State.OSC;
                    }
                }
            }
            case DISCARD_OSC -> {
                if (value == BEL || value == STRING_TERMINATOR) {
                    resetToText();
                } else if (value == ESC) {
                    state = State.DISCARD_OSC_ESCAPE;
                }
            }
            case DISCARD_OSC_ESCAPE -> {
                if (value == '\\') {
                    resetToText();
                } else if (value != ESC) {
                    state = State.DISCARD_OSC;
                }
            }
        }
    }

    private void appendOscByte(int value) {
        if (oscPayload.size() >= MAX_OSC_PAYLOAD_BYTES) {
            oscPayload.reset();
            state = State.DISCARD_OSC;
            return;
        }
        oscPayload.write(value);
    }

    private void finishOsc() {
        byte[] payload = oscPayload.toByteArray();
        resetToText();
        String workingDirectory = parseWorkingDirectory(payload);
        if (workingDirectory != null) {
            workingDirectoryConsumer.accept(workingDirectory);
        }
    }

    private void resetToText() {
        oscPayload.reset();
        state = State.TEXT;
    }

    private String parseWorkingDirectory(byte[] payload) {
        if (!startsWith(payload, OSC_7_FILE_PREFIX)) {
            return null;
        }

        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }

        int hostStart = OSC_7_FILE_PREFIX.length;
        int pathStart = value.indexOf('/', hostStart);
        if (pathStart < 0) {
            return null;
        }

        String host = value.substring(hostStart, pathStart);
        String path = value.substring(pathStart);
        if (!CROWSSH_OSC_HOST.equals(host)) {
            path = decodePercentEscapes(path);
        }
        return isValidWorkingDirectory(path) ? path : null;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String decodePercentEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); ) {
            if (value.charAt(index) != '%' || index + 2 >= value.length()) {
                decoded.append(value.charAt(index++));
                continue;
            }

            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            while (index + 2 < value.length() && value.charAt(index) == '%') {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    break;
                }
                encoded.write((high << 4) + low);
                index += 3;
            }
            if (encoded.size() == 0) {
                decoded.append(value.charAt(index++));
                continue;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded.toByteArray())));
            } catch (CharacterCodingException e) {
                return null;
            }
        }
        return decoded.toString();
    }

    private boolean isValidWorkingDirectory(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_WORKING_DIRECTORY_LENGTH
                || path.charAt(0) != '/') {
            return false;
        }
        for (int index = 0; index < path.length(); index++) {
            char current = path.charAt(index);
            if (current < 0x20 || current == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private enum State {
        TEXT,
        ESCAPE,
        OSC,
        OSC_ESCAPE,
        DISCARD_OSC,
        DISCARD_OSC_ESCAPE
    }
}
