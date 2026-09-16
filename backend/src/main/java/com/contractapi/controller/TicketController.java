package com.contractapi.controller;

import java.util.List;
import java.util.Map;
import com.contractapi.constants.TicketStatus;
import com.contractapi.dto.TicketRequest;
import com.contractapi.dto.TransferAcceptRequest;
import com.contractapi.dto.TransferRequest;
import com.contractapi.entity.LegalTicket;
import com.contractapi.entity.TicketTransfer;
import com.contractapi.service.TicketService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
  private final TicketService service;
  public TicketController(TicketService service) { this.service = service; }
  @PostMapping public LegalTicket create(@RequestBody TicketRequest request) { return service.create(request); }
  @GetMapping("/{id}") public LegalTicket get(@PathVariable Long id) { return service.find(id); }
  @GetMapping("/todo") public List<LegalTicket> todo(@RequestParam Long assigneeId) { return service.todo(assigneeId); }
  @PatchMapping("/{id}/status") public LegalTicket status(@PathVariable Long id, @RequestParam TicketStatus status) { return service.updateStatus(id, status); }
  @PostMapping("/{id}/replies") public Map<String, Object> reply(@PathVariable Long id, @RequestBody Map<String, Object> body) { return service.reply(id, String.valueOf(body.get("content")), (List<String>) body.getOrDefault("attachments", List.of())); }
  @PostMapping("/{id}/transfers") public TicketTransfer transfer(@PathVariable Long id, @RequestBody TransferRequest request) { return service.initiateTransfer(id, request); }
  @PostMapping("/{id}/transfers/accept") public TicketTransfer accept(@PathVariable Long id, @RequestBody TransferAcceptRequest request) { return service.acceptTransfer(id, request); }
  @GetMapping("/{id}/transfers") public List<TicketTransfer> transfers(@PathVariable Long id) { return service.listTransfers(id); }
}
