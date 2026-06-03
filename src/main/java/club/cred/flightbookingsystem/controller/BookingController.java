package club.cred.flightbookingsystem.controller;

import club.cred.flightbookingsystem.booking.BookingService;
import club.cred.flightbookingsystem.booking.CancellationService;
import club.cred.flightbookingsystem.dto.BookingResponse;
import club.cred.flightbookingsystem.dto.CancellationResponse;
import club.cred.flightbookingsystem.dto.CreateBookingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;

    public BookingController(BookingService bookingService, CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    /** Call 1: synchronously block seats and create the booking in PENDING_PAYMENT. */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.createBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id) {
        return bookingService.getBooking(id);
    }

    /** Cancel a confirmed booking: release seats and raise a (partial) refund. */
    @PostMapping("/{id}/cancel")
    public CancellationResponse cancel(@PathVariable Long id) {
        return cancellationService.cancel(id);
    }
}

