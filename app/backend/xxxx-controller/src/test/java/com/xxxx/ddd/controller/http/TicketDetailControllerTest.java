package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.TicketDetailDTO;
import com.xxxx.ddd.application.service.ticket.TicketDetailAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketDetailControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TicketDetailAppService ticketDetailAppService;

    @BeforeEach
    void setUp() {
        TicketDetailController controller = new TicketDetailController();
        ReflectionTestUtils.setField(controller, "ticketDetailAppService", ticketDetailAppService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void pingReturnsOkStatus() throws Exception {
        mockMvc.perform(get("/ticket/ping/java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void getTicketDetailReturnsDetailDto() throws Exception {
        TicketDetailDTO dto = new TicketDetailDTO();
        dto.setId(42L);
        dto.setName("Vé Tết SE1");

        when(ticketDetailAppService.getTicketDetailById(eq(42L), eq(10L))).thenReturn(dto);

        mockMvc.perform(get("/ticket/4/detail/42")
                        .param("version", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.id").value(42L))
                .andExpect(jsonPath("$.result.name").value("Vé Tết SE1"));
    }

    @Test
    void orderTicketByUserReturnsBoolean() throws Exception {
        when(ticketDetailAppService.orderTicketByUser(42L)).thenReturn(true);

        mockMvc.perform(get("/ticket/4/detail/42/order"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }
}
