package de.cimt.talendcomp.checksum;

/**
 *
 * @author daniel.koch@cimt-ag.de
 */
public enum CaseSensitive {
    CASE_SENSITIVE,
    UPPER_CASE {
        @Override
        public String process(String value) {
            return value == null ? null : value.toUpperCase();
        }
    },
    LOWER_CASE {
        @Override
        public String process(String value) {
            return value == null ? null : value.toLowerCase();
        }
    },
    /**
     * Default Option when not set
     */
    NOT_IN_USE;

    public String process(String value) {
        return value == null ? null : value;
    }

    public static CaseSensitive parse(String value) {
        if (value == null) {
            return NOT_IN_USE;
        }
        try {
            return CaseSensitive.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException iae) {
            return NOT_IN_USE;
        }
    }

}
