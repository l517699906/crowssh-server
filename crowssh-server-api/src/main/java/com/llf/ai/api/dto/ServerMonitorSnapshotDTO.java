package com.llf.ai.api.dto;

/**
 * Linux 服务器监控快照响应。
 */
public record ServerMonitorSnapshotDTO(
        long capturedAtEpochMs,
        Host host,
        long uptimeSeconds,
        Cpu cpu,
        Load load,
        Memory memory,
        Disk disk,
        Network network
) {

    public record Host(
            String hostname,
            String osName,
            String osVersion,
            String kernelVersion,
            String architecture
    ) {
    }

    public record Cpu(
            int logicalProcessors,
            long totalTicks,
            long idleTicks
    ) {
    }

    public record Load(
            double oneMinute,
            double fiveMinutes,
            double fifteenMinutes
    ) {
    }

    public record Memory(
            long totalBytes,
            long availableBytes
    ) {
    }

    public record Disk(
            String mountPoint,
            long totalBytes,
            long usedBytes,
            long availableBytes
    ) {
    }

    public record Network(
            String interfaceName,
            long receivedBytes,
            long transmittedBytes
    ) {
    }
}
