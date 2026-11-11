package com.nightlight.app.data.api.dto;

/** Backend envelope: { success, data, message, code }. */
public final class ApiResponse<T> {
    public boolean success;
    public T data;
    public String message;
    public String code;
}