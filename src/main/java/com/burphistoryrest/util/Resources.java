/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Resources {
    private Resources() {
    }

    public static byte[] read(String path) throws IOException {
        try (InputStream stream = Resources.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Resource not found: " + path);
            }
            return stream.readAllBytes();
        }
    }

    public static String readUtf8(String path) throws IOException {
        return new String(read(path), StandardCharsets.UTF_8);
    }
}
