package com.contractapi.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TicketStatus;
import com.contractapi.constants.TransferStatus;
import com.contractapi.dto.AssigneeRequest;
import com.contractapi.dto.TicketRequest;
import com.contractapi.dto.TransferAcceptRequest;
import com.contractapi.dto.TransferRequest;
import com.contractapi.entity.LegalTicket;
import com.contractapi.entity.TicketTransfer;
import com.contractapi.exception.ApiException;
import org.springframework.stereotype.Service;

@Service
public class TicketService {
  private final List<LegalTicket> tickets = new ArrayList<>();
  private final List<Map<String, Object>> replies = new ArrayList<>();
  private final List<TicketTransfer> transfers = new ArrayList<>();
  private final AtomicLong ticketIdGen = new AtomicLong(System.currentTimeMillis());
  private final AtomicLong transferIdGen = new AtomicLong(System.currentTimeMillis());

  public synchronized LegalTicket create(TicketRequest request) {
    LegalTicket ticket = new LegalTicket();
    ticket.setId(ticketIdGen.getAndIncrement());
    ticket.setUserId(request.userId());
    ticket.setAssigneeId(request.assigneeId());
    ticket.setType(request.type().name());
    ticket.setDescription(request.description());
    ticket.setStatus(TicketStatus.PENDING.name());
    ticket.setAttachments(String.valueOf(request.attachments()));
    tickets.add(ticket);
    return ticket;
  }

  public synchronized LegalTicket updateStatus(Long id, TicketStatus status) {
    LegalTicket ticket = requireTicket(id);
    ticket.setStatus(status.name());
    if (TicketStatus.CLOSED == status) {
      cancelPendingTransfer(id);
    }
    return ticket;
  }

  public synchronized Map<String, Object> reply(Long id, String content, List<String> attachments) {
    Map<String, Object> reply = Map.of("ticketId", id, "content", content, "attachments", attachments);
    replies.add(reply);
    updateStatus(id, TicketStatus.REPLIED);
    return reply;
  }

  public synchronized LegalTicket find(Long id) {
    return requireTicket(id);
  }

  public synchronized List<LegalTicket> todo(Long assigneeId) {
    return tickets.stream()
        .filter(item -> Objects.equals(item.getAssigneeId(), assigneeId) && !TicketStatus.CLOSED.name().equals(item.getStatus()))
        .toList();
  }

  public synchronized List<TicketTransfer> listTransfers(Long ticketId) {
    requireTicket(ticketId);
    return transfers.stream().filter(item -> item.getTicketId().equals(ticketId)).toList();
  }

  public synchronized LegalTicket designateAssignee(Long ticketId, AssigneeRequest request) {
    LegalTicket ticket = requireTicket(ticketId);
    if (request.assigneeId() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少处理人身份");
    }
    if (TicketStatus.CLOSED.name().equals(ticket.getStatus())) {
      throw new ApiException(ErrorCode.TICKET_CLOSED, "工单已关闭，无法指定处理人");
    }
    if (ticket.getAssigneeId() != null) {
      throw new ApiException(ErrorCode.ASSIGNEE_ALREADY_EXISTS, "工单已有处理人，不能重复指定");
    }
    ticket.setAssigneeId(request.assigneeId());
    return ticket;
  }

  public synchronized TicketTransfer initiateTransfer(Long ticketId, TransferRequest request) {
    LegalTicket ticket = requireTicket(ticketId);
    if (TicketStatus.CLOSED.name().equals(ticket.getStatus())) {
      throw new ApiException(ErrorCode.TICKET_CLOSED, "工单已关闭，无法发起转派");
    }
    if (request.toAssigneeId() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少接手处理人");
    }
    if (request.fromAssigneeId() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少当前处理人身份");
    }
    if (!Objects.equals(request.fromAssigneeId(), ticket.getAssigneeId())) {
      throw new ApiException(ErrorCode.NOT_CURRENT_ASSIGNEE, "只有当前处理人可以发起转派");
    }
    if (Objects.equals(request.toAssigneeId(), ticket.getAssigneeId())) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "接手处理人不能与当前处理人相同");
    }
    TicketTransfer pending = pendingTransferOf(ticketId);
    if (pending != null) {
      if (Objects.equals(pending.getToAssigneeId(), request.toAssigneeId())) {
        return pending;
      }
      throw new ApiException(ErrorCode.TRANSFER_ALREADY_PENDING, "同一工单只允许一个待接手转派");
    }
    TicketTransfer transfer = new TicketTransfer();
    transfer.setId(transferIdGen.getAndIncrement());
    transfer.setTicketId(ticketId);
    transfer.setFromAssigneeId(ticket.getAssigneeId());
    transfer.setToAssigneeId(request.toAssigneeId());
    transfer.setStatus(TransferStatus.PENDING.name());
    transfer.setCreatedAt(Instant.now());
    transfers.add(transfer);
    return transfer;
  }

  public synchronized TicketTransfer acceptTransfer(Long ticketId, TransferAcceptRequest request) {
    LegalTicket ticket = requireTicket(ticketId);
    if (request.assigneeId() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少接手人身份");
    }
    TicketTransfer latest = latestTransferOf(ticketId);
    if (latest != null && TransferStatus.ACCEPTED.name().equals(latest.getStatus())) {
      if (!Objects.equals(request.assigneeId(), latest.getToAssigneeId())) {
        throw new ApiException(ErrorCode.NOT_TRANSFER_TARGET, "转派已由其他处理人接手，归属不可改动");
      }
      return latest;
    }
    if (TicketStatus.CLOSED.name().equals(ticket.getStatus())) {
      throw new ApiException(ErrorCode.TICKET_CLOSED, "工单已关闭，无法接手转派");
    }
    if (latest == null || !TransferStatus.PENDING.name().equals(latest.getStatus())) {
      throw new ApiException(ErrorCode.TRANSFER_NOT_FOUND, "工单没有待接手的转派");
    }
    if (!Objects.equals(request.assigneeId(), latest.getToAssigneeId())) {
      throw new ApiException(ErrorCode.NOT_TRANSFER_TARGET, "只有指定处理人可以接手该转派");
    }
    latest.setStatus(TransferStatus.ACCEPTED.name());
    latest.setAcceptedAt(Instant.now());
    ticket.setAssigneeId(latest.getToAssigneeId());
    return latest;
  }

  private LegalTicket requireTicket(Long id) {
    return tickets.stream().filter(item -> item.getId().equals(id)).findFirst()
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "工单不存在: " + id));
  }

  private TicketTransfer pendingTransferOf(Long ticketId) {
    return transfers.stream()
        .filter(item -> item.getTicketId().equals(ticketId) && TransferStatus.PENDING.name().equals(item.getStatus()))
        .findFirst().orElse(null);
  }

  private TicketTransfer latestTransferOf(Long ticketId) {
    TicketTransfer latest = null;
    for (TicketTransfer item : transfers) {
      if (item.getTicketId().equals(ticketId)) {
        latest = item;
      }
    }
    return latest;
  }

  private void cancelPendingTransfer(Long ticketId) {
    TicketTransfer pending = pendingTransferOf(ticketId);
    if (pending != null) {
      pending.setStatus(TransferStatus.CANCELLED.name());
    }
  }
}
