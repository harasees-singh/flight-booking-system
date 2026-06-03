package club.cred.flightbookingsystem.controller;

import club.cred.flightbookingsystem.dto.SearchResponse;
import club.cred.flightbookingsystem.search.SearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flights")
@Validated
public class FlightSearchController {

    private final SearchService searchService;

    public FlightSearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Search direct + connecting journeys between two cities for a date and passenger count.
     * Example: {@code GET /api/v1/flights/search?src=DEL&dst=BLR&date=2026-06-10&pax=2}
     */
    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam @NotBlank String src,
            @RequestParam @NotBlank String dst,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10) int pax) {
        return searchService.search(src, dst, date, pax);
    }
}

