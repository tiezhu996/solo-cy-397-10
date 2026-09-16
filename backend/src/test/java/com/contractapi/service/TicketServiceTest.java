package com.contractapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TicketStatus;
import com.contractapi.constants.TicketType;
import com.contractapi.constants.TransferStatus;
import com.contractapi.dto.TicketRequest;
import com.contractapi.dto.TransferAcceptRequest;
import com.contractapi.dto.TransferRequest;
import com.contractapi.entity.LegalTicket;
import com.contractapi.entity.TicketTransfer;
import com.contractapi.exception.ApiException;
import org.junit.jupiter.api.Test;

class TicketServiceTest {

  private TicketService service = new TicketService();

  private LegalTicket newTicket(Long assigneeId) {
    return service.create(new TicketRequest(1L, TicketType.OTHER, "问题描述", List.of(), assigneeId));
  }

  @Test
  void existingFlowUnchanged() {
    LegalTicket ticket = newTicket(null);
    assertEquals(TicketStatus.PENDING.name(), ticket.getStatus());
    service.updateStatus(ticket.getId(), TicketStatus.PROCESSING);
    assertEquals(TicketStatus.PROCESSING.name(), service.find(ticket.getId()).getStatus());
    service.reply(ticket.getId(), "回复内容", List.of());
    assertEquals(TicketStatus.REPLIED.name(), service.find(ticket.getId()).getStatus());
    service.updateStatus(ticket.getId(), TicketStatus.CLOSED);
    assertEquals(TicketStatus.CLOSED.name(), service.find(ticket.getId()).getStatus());
  }

  @Test
  void acceptMigratesOwnershipAndTodoExactlyOnce() {
    LegalTicket ticket = newTicket(10L);
    TicketTransfer transfer = service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    assertEquals(TransferStatus.PENDING.name(), transfer.getStatus());
    assertEquals(10L, transfer.getFromAssigneeId());
    assertEquals(20L, transfer.getToAssigneeId());

    // 接手前责任与待办仍归原处理人
    assertEquals(10L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(1, service.todo(10L).size());
    assertEquals(0, service.todo(20L).size());

    TicketTransfer accepted = service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L));
    assertEquals(TransferStatus.ACCEPTED.name(), accepted.getStatus());
    assertNotNull(accepted.getAcceptedAt());
    assertEquals(20L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(0, service.todo(10L).size());
    assertEquals(1, service.todo(20L).size());

    // 重复接手：返回同一份记录，归属与待办不再变化
    TicketTransfer again = service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L));
    assertEquals(accepted.getId(), again.getId());
    assertEquals(accepted.getAcceptedAt(), again.getAcceptedAt());
    assertEquals(1, service.listTransfers(ticket.getId()).size());
    assertEquals(20L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(1, service.todo(20L).size());
  }

  @Test
  void duplicateInitiateDoesNotCreateSecondRecord() {
    LegalTicket ticket = newTicket(10L);
    TicketTransfer first = service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    TicketTransfer second = service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    assertEquals(first.getId(), second.getId());
    assertEquals(1, service.listTransfers(ticket.getId()).size());

    ApiException conflict = assertThrows(ApiException.class,
        () -> service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 30L)));
    assertEquals(ErrorCode.TRANSFER_ALREADY_PENDING, conflict.getCode());
    assertEquals(1, service.listTransfers(ticket.getId()).size());
  }

  @Test
  void initiateWithoutCurrentAssigneeFails() {
    LegalTicket ticket = newTicket(10L);
    ApiException ex = assertThrows(ApiException.class,
        () -> service.initiateTransfer(ticket.getId(), new TransferRequest(null, 20L)));
    assertEquals(ErrorCode.VALIDATION_FAILED, ex.getCode());
    // 不新增转派记录，责任人与待办不变
    assertEquals(0, service.listTransfers(ticket.getId()).size());
    assertEquals(10L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(1, service.todo(10L).size());
    assertEquals(0, service.todo(20L).size());
  }

  @Test
  void acceptWithoutAssigneeIdentityFails() {
    LegalTicket ticket = newTicket(10L);
    service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    ApiException ex = assertThrows(ApiException.class,
        () -> service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(null)));
    assertEquals(ErrorCode.VALIDATION_FAILED, ex.getCode());
    // 待接手记录保持原样，责任人与待办不变
    List<TicketTransfer> records = service.listTransfers(ticket.getId());
    assertEquals(1, records.size());
    assertEquals(TransferStatus.PENDING.name(), records.get(0).getStatus());
    assertNull(records.get(0).getAcceptedAt());
    assertEquals(10L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(1, service.todo(10L).size());
    assertEquals(0, service.todo(20L).size());
  }

  @Test
  void duplicateAcceptWithoutIdentityFails() {
    LegalTicket ticket = newTicket(10L);
    service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    TicketTransfer accepted = service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(null)));
    assertEquals(ErrorCode.VALIDATION_FAILED, ex.getCode());
    // 已完成归属不被改动，正确身份的重复接手仍幂等
    assertEquals(20L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(accepted.getId(), service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L)).getId());
    assertEquals(1, service.listTransfers(ticket.getId()).size());
  }

  @Test
  void onlyCurrentAssigneeCanInitiate() {
    LegalTicket ticket = newTicket(10L);
    ApiException ex = assertThrows(ApiException.class,
        () -> service.initiateTransfer(ticket.getId(), new TransferRequest(99L, 20L)));
    assertEquals(ErrorCode.NOT_CURRENT_ASSIGNEE, ex.getCode());
    assertEquals(0, service.listTransfers(ticket.getId()).size());
  }

  @Test
  void onlyTargetAssigneeCanAccept() {
    LegalTicket ticket = newTicket(10L);
    service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    ApiException ex = assertThrows(ApiException.class,
        () -> service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(30L)));
    assertEquals(ErrorCode.NOT_TRANSFER_TARGET, ex.getCode());
    assertEquals(10L, service.find(ticket.getId()).getAssigneeId());
  }

  @Test
  void closedTicketRejectsTransferAndAccept() {
    LegalTicket ticket = newTicket(10L);
    service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    service.updateStatus(ticket.getId(), TicketStatus.CLOSED);

    // 关闭后待接手转派被作废，不产生悬挂待办
    TicketTransfer record = service.listTransfers(ticket.getId()).get(0);
    assertEquals(TransferStatus.CANCELLED.name(), record.getStatus());
    assertEquals(0, service.todo(10L).size());
    assertEquals(0, service.todo(20L).size());

    ApiException acceptEx = assertThrows(ApiException.class,
        () -> service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L)));
    assertEquals(ErrorCode.TICKET_CLOSED, acceptEx.getCode());

    ApiException initiateEx = assertThrows(ApiException.class,
        () -> service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 30L)));
    assertEquals(ErrorCode.TICKET_CLOSED, initiateEx.getCode());

    // 归属保持原处理人，未被失败操作改动
    assertEquals(10L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(1, service.listTransfers(ticket.getId()).size());
  }

  @Test
  void completedOwnershipIsImmutable() {
    LegalTicket ticket = newTicket(10L);
    service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));
    TicketTransfer accepted = service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(30L)));
    assertEquals(ErrorCode.NOT_TRANSFER_TARGET, ex.getCode());
    assertEquals(20L, service.find(ticket.getId()).getAssigneeId());
    assertEquals(TransferStatus.ACCEPTED.name(), service.listTransfers(ticket.getId()).get(0).getStatus());
    assertEquals(accepted.getAcceptedAt(), service.listTransfers(ticket.getId()).get(0).getAcceptedAt());
  }

  @Test
  void concurrentDuplicateInitiateCreatesSingleRecord() throws Exception {
    LegalTicket ticket = newTicket(10L);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    List<TicketTransfer> results = new java.util.concurrent.CopyOnWriteArrayList<>();
    for (int i = 0; i < threads; i++) {
      pool.submit(() -> {
        try {
          start.await();
          results.add(service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L)));
        } catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        return null;
      });
    }
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertNull(error.get());
    assertEquals(threads, results.size());
    assertEquals(1, results.stream().map(TicketTransfer::getId).distinct().count());
    assertEquals(1, service.listTransfers(ticket.getId()).size());
  }

  @Test
  void concurrentCloseAndAcceptYieldSingleConsistentOutcome() throws Exception {
    for (int round = 0; round < 50; round++) {
      LegalTicket ticket = newTicket(10L);
      service.initiateTransfer(ticket.getId(), new TransferRequest(10L, 20L));

      CountDownLatch start = new CountDownLatch(1);
      AtomicReference<Object> acceptOutcome = new AtomicReference<>();
      AtomicReference<Object> closeOutcome = new AtomicReference<>();
      ExecutorService pool = Executors.newFixedThreadPool(2);
      pool.submit(() -> {
        try {
          start.await();
          acceptOutcome.set(service.acceptTransfer(ticket.getId(), new TransferAcceptRequest(20L)));
        } catch (ApiException e) {
          acceptOutcome.set(e);
        }
        return null;
      });
      pool.submit(() -> {
        try {
          start.await();
          closeOutcome.set(service.updateStatus(ticket.getId(), TicketStatus.CLOSED));
        } catch (ApiException e) {
          closeOutcome.set(e);
        }
        return null;
      });
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

      // 同一工单始终只有一份转派记录
      List<TicketTransfer> records = service.listTransfers(ticket.getId());
      assertEquals(1, records.size());
      LegalTicket current = service.find(ticket.getId());
      long todoHolders = (service.todo(10L).contains(current) ? 1 : 0) + (service.todo(20L).contains(current) ? 1 : 0);

      if (acceptOutcome.get() instanceof TicketTransfer accepted) {
        // 接手成功：归属迁移到 20，且只迁移一次
        assertEquals(TransferStatus.ACCEPTED.name(), accepted.getStatus());
        assertEquals(20L, current.getAssigneeId());
      } else {
        // 接手失败：只能是工单已先关闭，且不得留下迁移痕迹
        ApiException ex = assertInstanceOf(ApiException.class, acceptOutcome.get());
        assertEquals(ErrorCode.TICKET_CLOSED, ex.getCode());
        assertEquals(10L, current.getAssigneeId());
        assertEquals(TransferStatus.CANCELLED.name(), records.get(0).getStatus());
        assertNull(records.get(0).getAcceptedAt());
      }
      // 关闭要么成功要么因已关闭而保持 CLOSED；最终待办不重复、不丢失
      assertEquals(TicketStatus.CLOSED.name(), current.getStatus());
      assertEquals(0, todoHolders);
    }
  }
}
