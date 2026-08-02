package com.uragestudio.companion;

public record MediaItem(String id, String kind, String fileName, String title, String createdAt, String downloadUrl, String thumbnailUrl, String source, long size) {
}
