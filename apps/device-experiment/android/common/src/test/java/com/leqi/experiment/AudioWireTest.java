package com.leqi.experiment;

public final class AudioWireTest {
    public static void main(String[] args) {
        byte[] pcm = {1, 2, 3, 4};
        byte[] wav = AudioWire.pcm16MonoWav(pcm, 16000);
        check(wav.length == 48, "WAV includes 44-byte header");
        check(wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F', "RIFF header");
        check(wav[44] == 1 && wav[47] == 4, "PCM bytes preserved");
    }

    private static void check(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
    }
}
