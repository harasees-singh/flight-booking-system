package club.cred.flightbookingsystem.dto;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Booking details returned by create and lookup endpoints. */
public record BookingResponse(
        Long bookingId,
        BookingState state,
        List<Long> flightIds,
        List<PassengerView> passengers,
        Map<Long, BigDecimal> passengerRates,
        BigDecimal totalAmount) {

    public record PassengerView(Long id, String name, int age) {
    }

    public static BookingResponse from(Booking booking) {
        List<Long> flightIds = booking.getFlights().stream().map(Flight::getId).toList();
        List<PassengerView> passengers = booking.getPassengers().stream()
                .map(p -> new PassengerView(p.getId(), p.getName(), p.getAge()))
                .toList();
        return new BookingResponse(booking.getId(), booking.getState(), flightIds,
                passengers, booking.getPassengerRates(), booking.getTotalAmount());
    }
}

