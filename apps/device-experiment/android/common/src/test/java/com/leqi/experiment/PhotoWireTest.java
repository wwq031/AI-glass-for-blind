package com.leqi.experiment;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PhotoWireTest {
    public static void main(String[] args) {
        byte[] source = ("入口照片测试|".repeat(600)).getBytes(StandardCharsets.UTF_8);
        List<String> chunks = PhotoWire.chunks(source, 1200);
        check(chunks.size() > 1, "photo is split into multiple messages");
        PhotoWire.Collector collector = new PhotoWire.Collector(source.length, PhotoWire.sha256(source));
        for (String chunk : chunks) collector.add(chunk);
        check(java.util.Arrays.equals(source, collector.finish()), "all bytes arrive intact");
        PhotoWire.Collector missing = new PhotoWire.Collector(source.length, PhotoWire.sha256(source));
        for (int i = 0; i < chunks.size() - 1; i++) missing.add(chunks.get(i));
        expectInvalid(missing::finish);
    }

    private static void check(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected an incomplete photo to be rejected");
    }
}
