package com.llf.ai.infrastructure.adapter.port;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 在终端内部命令执行期间抑制输出，收到完成序列后恢复透传。
 */
final class OneShotTerminalOutputFilter {

    private final byte[] completionSequence;
    private final int[] longestPrefixSuffix;

    private int matchedLength;
    private volatile boolean filtering = true;
    private volatile boolean complete;

    OneShotTerminalOutputFilter(String completionSequence) {
        this.completionSequence = completionSequence.getBytes(StandardCharsets.UTF_8);
        this.longestPrefixSuffix = buildLongestPrefixSuffix(this.completionSequence);
    }

    byte[] filter(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || completionSequence.length == 0) {
            return new byte[0];
        }

        int start = Math.max(0, offset);
        int end = Math.min(bytes.length, start + length);
        if (!filtering) {
            ByteArrayOutputStream passthrough = new ByteArrayOutputStream(end - start);
            passthrough.write(bytes, start, end - start);
            return passthrough.toByteArray();
        }

        ByteArrayOutputStream visible = new ByteArrayOutputStream(end - start);
        for (int index = start; index < end; index++) {
            if (!filtering) {
                visible.write(bytes, index, end - index);
                break;
            }
            byte current = bytes[index];
            while (matchedLength > 0 && current != completionSequence[matchedLength]) {
                matchedLength = longestPrefixSuffix[matchedLength - 1];
            }

            if (current == completionSequence[matchedLength]) {
                matchedLength++;
                if (matchedLength == completionSequence.length) {
                    matchedLength = 0;
                    complete = true;
                }
            }
        }
        return visible.toByteArray();
    }

    boolean isComplete() {
        return complete;
    }

    void release() {
        filtering = false;
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
