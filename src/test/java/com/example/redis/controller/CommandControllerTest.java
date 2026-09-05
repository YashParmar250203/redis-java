package com.example.redis.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void setCommandReturnsOk() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("SET name Yash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value("OK"));
    }

    @Test
    void setThenGetRoundTrip() throws Exception {
        mockMvc.perform(post("/api/command")
                .contentType(MediaType.TEXT_PLAIN)
                .content("SET city Delhi"));

        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("GET city"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value("Delhi"));
    }

    @Test
    void unknownCommandReturnsBadRequestWithErrorBody() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("FOO bar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("ERR unknown command 'FOO'"));
    }

    @Test
    void wrongArgCountReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("SET onlykey"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR wrong number of arguments for 'set' command"));
    }
}
