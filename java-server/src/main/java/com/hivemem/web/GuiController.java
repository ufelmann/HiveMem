package com.hivemem.web;

import com.hivemem.tools.read.ReadToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/gui")
public class GuiController {

    private static final int DEFAULT_CELL_LIMIT = 500;
    private static final int DEFAULT_TUNNEL_LIMIT = 1000;

    private final ReadToolService readToolService;

    public GuiController(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @GetMapping("/stream")
    public Map<String, Object> stream() {
        return readToolService.streamSnapshot(DEFAULT_CELL_LIMIT, DEFAULT_TUNNEL_LIMIT);
    }
}
