package de.cimt.talendcomp.checksum;

import java.util.Base64;

/**
 *
 * @author daniel.koch@cimt-ag.de
 */
public enum HashOutputEncoding {
    BASE64 {
        @Override
        public String encode(byte[] value) {
            return Base64.getEncoder().encodeToString(value);
        }
    },
    HEX {
        @Override
        public String encode(byte[] value) {

            final StringBuilder sb = new StringBuilder();

            for (int i = 0; i < value.length; i++) {
                sb.append(Integer.toString((value[i] & 0xff) + 0x100, 16).substring(1));
            }

            return sb.toString();
        }
    },
    PLAIN;

    public static HashOutputEncoding parse(String type) {
        try {
            return HashOutputEncoding.valueOf(type.trim().toUpperCase());
        } catch (Throwable t) {
        }

        return HEX;
    }

    public String encode(byte[] value) {
        if (value == null) {
            return null;
        }

        return new String(value);
    }
}
