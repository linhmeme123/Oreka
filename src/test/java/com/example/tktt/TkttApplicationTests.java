package com.example.tktt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TkttApplicationTests {
    @Autowired MockMvc mvc;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String index = Files.createTempDirectory("hust-test-index-").toString();
        registry.add("app.index-directory", () -> index);
    }
    @Test
    void servesSearchAndAnalyzerAndValidatesInputs() throws Exception {
        mvc.perform(get("/api/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(0));
        mvc.perform(get("/api/search").param("q", "học bổng"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/analyze").param("text", "Đại học"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokens[0]").value("dai_hoc"));
        mvc.perform(get("/api/search").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("type", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", "a".repeat(301))).andExpect(status().isBadRequest());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
