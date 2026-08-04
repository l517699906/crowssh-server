package com.llf.ai.infrastructure.adapter.port;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从终端字节流中剥离一次指定序列，支持目标序列跨读取分片。
 */
final class OneShotTerminalOutputFilter {

    private final byte[] hiddenSequence;
    private final int[] longestPrefixSuffix;

    private int matchedLength;
    private boolean removed;

    OneShotTerminalOutputFilter(String hiddenSequence) {
        this.hiddenSequence = hiddenSequence.getBytes(StandardCharsets.UTF_8);
        this.longestPrefixSuffix = buildLongestPrefixSuffix(this.hiddenSequence);
    }

    byte[] filter(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || hiddenSequence.length == 0) {
            return new byte[0];
        }

        int start = Math.max(0, offset);
        int end = Math.min(bytes.length, start + length);
        if (removed) {
            ByteArrayOutputStream passthrough = new ByteArrayOutputStream(end - start);
            passthrough.write(bytes, start, end - start);
            return passthrough.toByteArray();
        }

        ByteArrayOutputStream visible = new ByteArrayOutputStream(end - start);
        for (int index = start; index < end; index++) {
            if (removed) {
                visible.write(bytes, index, end - index);
                break;
            }
            byte current = bytes[index];
            while (matchedLength > 0 && current != hiddenSequence[matchedLength]) {
                int fallbackLength = longestPrefixSuffix[matchedLength - 1];
                visible.write(hiddenSequence, 0, matchedLength - fallbackLength);
                matchedLength = fallbackLength;
            }

            if (current == hiddenSequence[matchedLength]) {
                matchedLength++;
                if (matchedLength == hiddenSequence.length) {
                    matchedLength = 0;
                    removed = true;
                }
            } else {
                visible.write(current);
            }
        }
        return visible.toByteArray();
    }

    private int[] buildLongestPrefixSuffix(byte[] pattern) {
        int[] result = new int[pattern.length];
        for (int index = 1, prefixLength = 0; index < pattern.length; ) {
            if (pattern[index] == pattern[prefixLength]) {
                result[index++] = ++prefixLength;
            } else if (prefixLength > 0) {
                prefixLength = result[prefixLength - 1];
            } else {
                result[index++] = 0;
            }
        }
        return result;
    }
}
