package com.contractapi.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractapi.exception.GlobalExceptionHandler;
import com.contractapi.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TicketControllerTest {

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new TicketController(new TicketService()))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  private long createTicket(Long assigneeId) throws Exception {
    String body = "{\"userId\":1,\"type\":\"CONTRACT\",\"description\":\"合同纠纷咨询\",\"attachments\":[]"
        + (assigneeId == null ? "" : ",\"assigneeId\":" + assigneeId) + "}";
    MvcResult result = mockMvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andReturn();
    String json = result.getResponse().getContentAsString();
    return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
  }

  @Test
  void transferLifecycleOverHttp() throws Exception {
    long ticketId = createTicket(10L);

    mockMvc.perform(post("/api/tickets/{id}/transfers", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAssigneeId\":10,\"toAssigneeId\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.fromAssigneeId").value(10))
        .andExpect(jsonPath("$.toAssigneeId").value(20))
        .andExpect(jsonPath("$.createdAt").exists());

    // 接手前待办仍归原处理人
    mockMvc.perform(get("/api/tickets/todo").param("assigneeId", "10"))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    mockMvc.perform(get("/api/tickets/todo").param("assigneeId", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

    // 重复发起：同目标幂等返回，不产生第二份记录
    mockMvc.perform(post("/api/tickets/{id}/transfers", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAssigneeId\":10,\"toAssigneeId\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
    mockMvc.perform(post("/api/tickets/{id}/transfers", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAssigneeId\":10,\"toAssigneeId\":30}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TRANSFER_ALREADY_PENDING"));

    mockMvc.perform(post("/api/tickets/{id}/transfers/accept", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assigneeId\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.acceptedAt").exists());

    // 接手后待办迁移一次：原处理人清空，新处理人持有
    mockMvc.perform(get("/api/tickets/todo").param("assigneeId", "10"))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    mockMvc.perform(get("/api/tickets/todo").param("assigneeId", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

    // 归属与转派记录可回读
    mockMvc.perform(get("/api/tickets/{id}", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").value(20));
    mockMvc.perform(get("/api/tickets/{id}/transfers", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].status").value("ACCEPTED"));

    // 重复接手：幂等返回，不产生第二份记录
    mockMvc.perform(post("/api/tickets/{id}/transfers/accept", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assigneeId\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
    mockMvc.perform(get("/api/tickets/{id}/transfers", ticketId))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void closeThenAcceptFailsCleanly() throws Exception {
    long ticketId = createTicket(10L);
    mockMvc.perform(post("/api/tickets/{id}/transfers", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAssigneeId\":10,\"toAssigneeId\":20}"))
        .andExpect(status().isOk());

    mockMvc.perform(patch("/api/tickets/{id}/status", ticketId).param("status", "CLOSED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));

    mockMvc.perform(post("/api/tickets/{id}/transfers/accept", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assigneeId\":20}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TICKET_CLOSED"));

    // 失败一方不留下待办，归属保持原处理人
    mockMvc.perform(get("/api/tickets/todo").param("assigneeId", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    mockMvc.perform(get("/api/tickets/{id}", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").value(10));
    mockMvc.perform(get("/api/tickets/{id}/transfers", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].status").value("CANCELLED"));
  }

  @Test
  void existingEndpointsStillWork() throws Exception {
    long ticketId = createTicket(null);
    mockMvc.perform(patch("/api/tickets/{id}/status", ticketId).param("status", "PROCESSING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSING"));
    mockMvc.perform(post("/api/tickets/{id}/replies", ticketId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"已受理\",\"attachments\":[]}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/tickets/{id}", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REPLIED"));
  }
}
