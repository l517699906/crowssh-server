package com.llf.ai.domain.agent.service.armory.matter.tools;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令检测器。
 *
 * <p>为 AI 生成的 Shell 命令提供破坏性操作识别。命中任一规则的命令必须由用户显式审批，
 * 无交互审批通道时直接拒绝执行。
 *
 * <p>设计取舍：采用黑名单式检测而非白名单，以保持"AI 自由执行 + 危险命令审批"的产品定位。
 * 规则以覆盖高破坏性、不可逆操作为目标，同时避免对相对路径下的常规清理命令（如
 * {@code rm -rf ./build}、{@code rm -rf node_modules}）误报。
 */
public class DangerousCommandDetector {

    /**
     * 高危系统目录：破坏其任意深度的内容都可能不可逆地损坏系统。
     * 因此这些目录本身及其任意子路径均视为危险。
     */
    private static final String CRITICAL_DIR =
            "/(?:etc|bin|sbin|lib|lib64|boot|dev|proc|sys|root)(?:/\\S*)?";

    /**
     * 软系统目录：顶层或其直接子目录（单层）作为删除目标风险高，
     * 但更深层子路径（如 {@code /var/log/myapp/old}）通常是常规运维/应用数据，予以放行。
     */
    private static final String SOFT_DIR =
            "/(?:usr|var|opt|home)(?:/[^/\\s]+)?";

    /**
     * 危险删除路径：根、通配根、家目录顶层、以及高危 / 软系统目录（按分级策略）。
     *
     * <p>家目录仅匹配裸 {@code ~} / {@code ~/} / {@code $HOME}（顶层），
     * {@code ~/project/build} 这类子路径视为用户自有数据放行。
     */
    private static final String DANGEROUS_PATH =
            "(?:/\\*|/|~/?|\\$HOME/?|" + CRITICAL_DIR + "|" + SOFT_DIR + ")";

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // rm 递归强制删除根 / 家目录 / 系统目录：覆盖 -rf、-fr、-r -f、--recursive --force
            Pattern.compile(
                    "\\brm\\b(?:\\s+(?:-[a-z]*[rf][a-z]*|--recursive|--force|-r|-f))+"
                            + "\\s+" + DANGEROUS_PATH + "(?:\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 磁盘写入
            Pattern.compile("\\bdd\\s+if=", Pattern.CASE_INSENSITIVE),
            // 文件系统格式化：mkfs、mkfs.ext4、mkfs -t ext4
            Pattern.compile("\\bmkfs(?:\\.\\S+)?\\b", Pattern.CASE_INSENSITIVE),
            // fork 炸弹
            Pattern.compile(":\\(\\)\\s*\\{", Pattern.CASE_INSENSITIVE),
            // 重定向写入块设备：覆盖 SATA/SCSI(sd)、IDE(hd)、virtio(vd)、NVMe(nvme)、eMMC/SD(mmcblk)
            Pattern.compile(">\\s*/dev/(?:sd|hd|vd|nvme|mmcblk)", Pattern.CASE_INSENSITIVE),
            // 重定向覆盖系统关键文件：> /etc/passwd 等
            // 注：不含 dev，因字符设备（/dev/null、/dev/stdout）是标准 IO 手段；
            //     块设备写入（真正危险）由专门规则 >\s*/dev/sd 拦截
            Pattern.compile(
                    ">\\s*/(?:etc|bin|sbin|lib|lib64|boot|proc|sys|usr|var|root)(?:/\\S*)?",
                    Pattern.CASE_INSENSITIVE),
            // chmod 作用于根 / 家目录 / 系统目录（含或不含 -R）
            Pattern.compile(
                    "\\bchmod\\s+(?:-[a-zA-Z]*\\s+)*\\d{3,4}\\s+" + DANGEROUS_PATH + "(?:\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "\\bchmod\\s+-R\\s+\\d{3,4}\\s+" + DANGEROUS_PATH + "(?:\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // find 批量删除：搜索起点为根 / 高危目录（任意深度）/ 软目录顶层时视为危险；
            //   软目录深层（如 find /var/log ... -delete）属常规清理，放行。
            Pattern.compile(
                    "\\bfind\\s+(?:/|" + CRITICAL_DIR + "|/(?:usr|var|opt|home))(?=\\s|$).*-delete",
                    Pattern.CASE_INSENSITIVE),
            // 关机 / 重启
            Pattern.compile("\\b(?:shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 判定命令是否属于需要用户审批的破坏性操作。
     *
     * @param command 完整的 Shell 命令，可为 null
     * @return true 表示命中危险规则
     */
    public boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
