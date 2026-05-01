package com.hivemem.extraction;

import java.util.regex.Pattern;

/** Static utility — picks an initial document_type hint from cheap signals. */
public final class PreClassifier {

    private static final Pattern INVOICE_FILENAME =
            Pattern.compile("(?i)(rechnung|invoice|receipt|beleg|quittung)");
    private static final Pattern CONTRACT_FILENAME =
            Pattern.compile("(?i)(vertrag|contract|agb|mietvertrag)");

    private static final Pattern INVOICE_HEAD =
            Pattern.compile("(?i)(rechnungsnummer|invoice\\s*no|invoice\\s*number|"
                    + "rechnungsbetrag|gesamtbetrag|amount\\s+due)");
    private static final Pattern CONTRACT_HEAD =
            Pattern.compile("(?i)(kündigungsfrist|notice\\s+period|vertragsbeginn|vertragslaufzeit)");

    private PreClassifier() {}

    public static String guessType(String mime, String filename, String head200) {
        if (mime != null && mime.startsWith("image/")) {
            return "other";
        }
        if (filename != null) {
            if (INVOICE_FILENAME.matcher(filename).find()) return "invoice";
            if (CONTRACT_FILENAME.matcher(filename).find()) return "contract";
        }
        if (head200 != null) {
            if (INVOICE_HEAD.matcher(head200).find()) return "invoice";
            if (CONTRACT_HEAD.matcher(head200).find()) return "contract";
        }
        return "other";
    }
}
