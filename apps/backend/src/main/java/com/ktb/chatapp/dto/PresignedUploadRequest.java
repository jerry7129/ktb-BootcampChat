package com.ktb.chatapp.dto;

public record PresignedUploadRequest(String originalname, String mimetype, long size) {}
