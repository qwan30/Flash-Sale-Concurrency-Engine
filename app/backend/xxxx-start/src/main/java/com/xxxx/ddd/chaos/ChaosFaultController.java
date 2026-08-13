package com.xxxx.ddd.chaos;

import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("chaos")
@RequestMapping("/__chaos/faults")
public final class ChaosFaultController {

    private final ConfigurableFaultInjection faults;

    public ChaosFaultController(ConfigurableFaultInjection faults) {
        this.faults = faults;
    }

    @GetMapping
    public FaultCatalog catalog() {
        return new FaultCatalog(faults.catalog(), faults.active());
    }

    @PutMapping("/{point}")
    public ResponseEntity<FaultCatalog> activate(
            @PathVariable("point") FaultInjectionPort.FaultPoint point) {
        faults.activate(point);
        return ResponseEntity.ok(catalog());
    }

    @DeleteMapping
    public ResponseEntity<FaultCatalog> clear() {
        faults.clear();
        return ResponseEntity.ok(catalog());
    }

    public record FaultCatalog(List<FaultInjectionPort.FaultPoint> catalog, FaultInjectionPort.FaultPoint active) {
        public FaultCatalog {
            catalog = List.copyOf(catalog);
        }
    }
}
