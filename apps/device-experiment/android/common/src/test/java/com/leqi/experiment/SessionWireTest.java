package com.leqi.experiment;

public final class SessionWireTest {
    public static void main(String[] args) {
        String encoded = SessionWire.encode("nav-session", 7, "button.pressed", "眼镜|短按");
        SessionWire.Event decoded = SessionWire.decode(encoded);
        check("nav-session".equals(decoded.sessionId), "session ID");
        check(decoded.sequence == 7, "sequence");
        check("button.pressed".equals(decoded.type), "event type");
        check("眼镜|短按".equals(decoded.payload), "Unicode and separator payload");
        expectInvalid(() -> SessionWire.encode("", 1, "button.pressed", ""));
        expectInvalid(() -> SessionWire.encode("nav-session", 0, "button.pressed", ""));
        expectInvalid(() -> SessionWire.decode("v1|broken"));
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void expectInvalid(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid wire message");
    }
}
