package com.ktb.chatapp.dto;

public record CompleteUploadRequest(
        String filename,
        String originalname,
        String mimetype,
        long size) {}
