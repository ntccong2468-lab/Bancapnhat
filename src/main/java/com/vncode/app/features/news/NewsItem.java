package com.vncode.app.features.news;
import java.time.Instant;
public record NewsItem(String id, Instant publishedAt, String title, String body) {}
