package com.hivemem.hooks;

import com.hivemem.search.CellSearchRepository.RankedRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ContextFormatter {

    public String format(List<RankedRow> rows, int turn) {
        if (rows == null || rows.isEmpty()) return "";
        String body = rows.stream()
                .map(r -> "- " + safeSummary(r) + " (id: " + r.id() + ")")
                .collect(Collectors.joining("\n"));
        return "<hivemem_context turn=\"" + turn + "\">\n"
                + "Relevant (summaries only — use hivemem_get_cell for details):\n"
                + body + "\n"
                + "</hivemem_context>";
    }

    private String safeSummary(RankedRow r) {
        if (r.summary() != null && !r.summary().isBlank()) return r.summary().strip();
        if (r.content() != null) {
            String collapsed = r.content().strip().replaceAll("\\s+", " ");
            return collapsed.length() > 120 ? collapsed.substring(0, 120) : collapsed;
        }
        return "(no summary)";
    }
}
