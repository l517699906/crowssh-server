package com.llf.ai.trigger.http.db;

import com.llf.ai.api.dto.DbApprovalDecisionRequestDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.agent.service.armory.matter.tools.CommandApprovalService;
import com.llf.ai.types.enums.ResponseCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/db/approvals")
@ConditionalOnProperty(name = "crowssh.db.enabled", havingValue = "true")
public class DbApprovalController {
    private final CommandApprovalService approvals;
    public DbApprovalController(CommandApprovalService approvals) { this.approvals = approvals; }

    @PostMapping("/{approvalId}/decision")
    public Response<String> decide(@PathVariable String approvalId,
            @RequestBody DbApprovalDecisionRequestDTO request, Principal principal) {
        try {
            if (request == null || request.decision() == null || principal == null) {
                throw new IllegalArgumentException("审批请求不完整");
            }
            boolean approved = switch (request.decision().trim().toLowerCase(java.util.Locale.ROOT)) {
                case "approve", "approved" -> true;
                case "deny", "denied" -> false;
                default -> throw new IllegalArgumentException("审批决定只能是 approve 或 deny");
            };
            var state = approvals.databaseApprovals().decide(approvalId, principal.getName(),
                    request.sessionId(), request.turnId(), approved);
            return Response.<String>builder().code(ResponseCode.SUCCESS.getCode())
                    .info("审批当前状态").data(state.name()).build();
        } catch (IllegalArgumentException invalid) {
            return Response.<String>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(invalid.getMessage()).build();
        }
    }
}
