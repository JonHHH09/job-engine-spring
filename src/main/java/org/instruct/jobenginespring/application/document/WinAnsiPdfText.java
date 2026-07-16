package org.instruct.jobenginespring.application.document;

import java.nio.charset.Charset;

final class WinAnsiPdfText {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private WinAnsiPdfText() {
    }

    static String sanitize(String text) {
        var encoder = WINDOWS_1252.newEncoder();
        StringBuilder safe = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                safe.append(' ');
                return;
            }
            String symbol = new String(Character.toChars(codePoint));
            safe.append(encoder.canEncode(symbol) ? symbol : "?");
        });
        return safe.toString();
    }
}
