package com.llf.ai.domain.agent.service.prompt.dynamic;

import com.llf.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 里程碑追踪器，基于正则规则自动识别用户纠偏、任务完成、任务切换、工具报错等关键事件，并按会话维度缓存。
 *
 * @author llf
 * 2026/7/30 23:16
 */
@Slf4j
@Component
public class MilestoneTracker {

    private static final int MAX_MILESTONES = 50;
    private final Map<String, LinkedList<MilestoneVO>> milestones = new ConcurrentHashMap<>();

    /**
     * 检测并记录里程碑事件。
     * <p>
     * 按角色分别识别关键事件：
     * <ul>
     *   <li>user —— 匹配"不对/换个思路"等记为 TASK_CHANGE，"完成/搞定"记为 TASK_COMPLETE，"不要/停/别"记为 USER_CORRECTION</li>
     *   <li>tool —— 匹配 error/failed/exception 等关键词记为 ERROR</li>
     * </ul>
     * 同一个词在不同角色含义不同（用户说"错了"是纠偏，工具输出"error"是执行失败），因此必须按角色分开识别。
     *
     * @param sessionId 会话 ID，按会话隔离里程碑
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容，为 null 或空时直接返回
     */
    public void detectAndRecord(String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isEmpty()) return;

        MilestoneVO.Type type = null;

        if ("user".equals(role)) {
            if (matches(content, "不对|不是这样|改一下|换个思路|换种方式|错了")) {
                type = MilestoneVO.Type.TASK_CHANGE;
            } else if (matches(content, "完成了|搞定|结束|好了")) {
                type = MilestoneVO.Type.TASK_COMPLETE;
            } else if (matches(content, "不要|停|别")) {
                type = MilestoneVO.Type.USER_CORRECTION;
            }
        }

        // 如果实际测试中还发现有兜不住的，可以继续完善。
        if ("tool".equals(role)) {
            if (matches(content, "(?i)error|failed|exception|permission denied|not found|refused")) {
                type = MilestoneVO.Type.ERROR;
            }
        }

        if (type != null) {
            push(sessionId, MilestoneVO.builder()
                    .type(type)
                    .content(truncate(content, 200))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("里程碑记录: sessionId={}, type={}, content={}", sessionId, type, truncate(content, 100));
        }
    }

    /**
     * 将里程碑加入指定会话的缓存队列。
     * <p>
     * 会话级隔离（ConcurrentHashMap，key=sessionId），每会话最多 {@value #MAX_MILESTONES} 条，
     * 超出淘汰最老的，防止长对话把内存撑爆。
     *
     * @param sessionId    会话 ID
     * @param milestoneVO  里程碑事件
     */
    private void push(String sessionId, MilestoneVO milestoneVO) {
        LinkedList<MilestoneVO> list = milestones.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) {
                list.removeFirst();
            }
        }
    }

    /**
     * 获取指定会话最近的 N 条里程碑事件。
     *
     * @param sessionId 会话 ID
     * @param limit     返回条数上限
     * @return 里程碑列表（按时间正序），无数据时返回空列表
     */
    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        LinkedList<MilestoneVO> list = milestones.getOrDefault(sessionId, new LinkedList<>());
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    /**
     * 清除指定会话的全部里程碑记录。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        milestones.remove(sessionId);
    }

    /**
     * 判断 content 是否匹配给定正则模式。
     * <p>
     * 使用 {@code .*(regex).*} 全文匹配，毫秒级、零成本。
     * 确定性方案，不调用模型做事件识别。
     *
     * @param content 待匹配文本
     * @param regex   正则表达式（不含前后通配）
     * @return true 表示命中
     */
    private boolean matches(String content, String regex) {
        return Pattern.compile(".*(" + regex + ").*").matcher(content).matches();
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 表示。
     *
     * @param s   原始字符串，为 null 时返回空串
     * @param max 最大保留长度
     * @return 截断后的字符串
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

}
