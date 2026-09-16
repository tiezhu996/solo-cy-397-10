package com.contractapi.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ticket_transfers")
public class TicketTransfer {
  private Long id;
  private Long ticketId;
  private Long fromAssigneeId;
  private Long toAssigneeId;
  private String status;
  private Instant createdAt;
  private Instant acceptedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getTicketId() { return ticketId; }
  public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
  public Long getFromAssigneeId() { return fromAssigneeId; }
  public void setFromAssigneeId(Long fromAssigneeId) { this.fromAssigneeId = fromAssigneeId; }
  public Long getToAssigneeId() { return toAssigneeId; }
  public void setToAssigneeId(Long toAssigneeId) { this.toAssigneeId = toAssigneeId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
}
