package com.contractapi.constants;

public final class ErrorCode {
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String PDF_EXPORT_FAILED = "PDF_EXPORT_FAILED";
  public static final String TICKET_CLOSED = "TICKET_CLOSED";
  public static final String TRANSFER_NOT_FOUND = "TRANSFER_NOT_FOUND";
  public static final String TRANSFER_ALREADY_PENDING = "TRANSFER_ALREADY_PENDING";
  public static final String NOT_CURRENT_ASSIGNEE = "NOT_CURRENT_ASSIGNEE";
  public static final String NOT_TRANSFER_TARGET = "NOT_TRANSFER_TARGET";
  public static final String ASSIGNEE_ALREADY_EXISTS = "ASSIGNEE_ALREADY_EXISTS";
  private ErrorCode() {}
}
