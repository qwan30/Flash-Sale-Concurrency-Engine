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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketQueryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TicketDetailAppService ticketDetailAppService;

    @BeforeEach
    void setUp() {
        TicketQueryController controller = new TicketQueryController();
        ReflectionTestUtils.setField(controller, "ticketDetailAppService", ticketDetailAppService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getTicketReturnsTicketDetail() throws Exception {
        TicketDetailDTO dto = new TicketDetailDTO();
        dto.setId(4L);
        dto.setName("Vé VIP Sự Kiện 01/01");
        dto.setStockAvailable(1000);

        when(ticketDetailAppService.getTicketDetailById(4L, null)).thenReturn(dto);

        mockMvc.perform(get("/tickets/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.id").value(4L))
                .andExpect(jsonPath("$.result.name").value("Vé VIP Sự Kiện 01/01"));
    }
}
