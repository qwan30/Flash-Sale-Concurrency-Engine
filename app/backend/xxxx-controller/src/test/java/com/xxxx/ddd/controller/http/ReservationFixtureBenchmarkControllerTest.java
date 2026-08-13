package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.reservation.ReservationFixtureResult;
import com.xxxx.ddd.application.service.reservation.ReservationFixtureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReservationFixtureBenchmarkControllerTest {

    @Mock
    private ReservationFixtureService reservationFixtureService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ReservationFixtureBenchmarkController(
                        reservationFixtureService,
                        "test-fixture-token",
                        950015L))
                .build();
    }

    @Test
    void resetReturnsDurableAndRedisProof() throws Exception {
        when(reservationFixtureService.reset(any())).thenReturn(
                ReservationFixtureResult.success(950015L, 1000, 0, "OPEN"));

        mockMvc.perform(post("/admin/reservation-fixtures/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token")
                        .content("""
                                {
                                  "ticketItemId": 950015,
                                  "stock": 1000,
                                  "strategy": "REDIS_FIRST",
                                  "reservationFixture": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.success").value(true))
                .andExpect(jsonPath("$.result.reservationFixtureReset").value(true))
                .andExpect(jsonPath("$.result.reservationStockAfter").value(1000))
                .andExpect(jsonPath("$.result.reservationRedisStockAfter").value(1000))
                .andExpect(jsonPath("$.result.admissionState").value("OPEN"));
    }

    @Test
    void resetRejectsMissingSyntheticMarker() throws Exception {
        mockMvc.perform(post("/admin/reservation-fixtures/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token")
                        .content("""
                                {
                                  "ticketItemId": 950015,
                                  "stock": 1000,
                                  "strategy": "REDIS_FIRST",
                                  "reservationFixture": true
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetRejectsMissingOrIncorrectFixtureToken() throws Exception {
        for (String fixtureToken : new String[]{"", "wrong-fixture-token"}) {
            mockMvc.perform(post("/admin/reservation-fixtures/reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Flashsale-Synthetic", "true")
                            .header("X-Flashsale-Fixture-Token", fixtureToken)
                            .content("""
                                    {
                                      "ticketItemId": 950015,
                                      "stock": 1000,
                                      "strategy": "REDIS_FIRST",
                                      "reservationFixture": true
                                    }
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void resetRejectsAnyTicketOtherThanTheConfiguredDisposableFixture() throws Exception {
        mockMvc.perform(post("/admin/reservation-fixtures/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token")
                        .content("""
                                {
                                  "ticketItemId": 950016,
                                  "stock": 1000,
                                  "strategy": "REDIS_FIRST",
                                  "reservationFixture": true
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
