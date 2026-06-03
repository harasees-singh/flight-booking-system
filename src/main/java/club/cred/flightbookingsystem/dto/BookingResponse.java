package club.cred.flightbookingsystem.dto;

import club.cred.flightbookingsystem.domain.Booking;
import club.cred.flightbookingsystem.domain.BookingState;
import club.cred.flightbookingsystem.domain.Flight;
import club.cred.flightbookingsystem.domain.Passenger;
import java.math.BigDecimal;
import java.util.List;

/** Booking details returned by create and lookup endpoints. */
public record BookingResponse(
        Long bookingId,
        BookingState state,
        List<Long> flightIds,
        List<PassengerView> passengers,
        BigDecimal perPassengerRate,
        BigDecimal totalAmount) {

    public record PassengerView(Long id, String name, int age, BigDecimal rate) {
    }

    public static BookingResponse from(Booking booking) {
        List<Long> flightIds = booking.getFlights().stream().map(Flight::getId).toList();
        List<PassengerView> passengers = booking.getPassengers().stream()
                .map(p -> new PassengerView(p.getId(), p.getName(), p.getAge(), p.getBookingRate()))
                .toList();
        BigDecimal perPassengerRate = booking.getPassengers().isEmpty()
                ? BigDecimal.ZERO
                : booking.getPassengers().get(0).getBookingRate();
        return new BookingResponse(booking.getId(), booking.getState(), flightIds,
                passengers, perPassengerRate, booking.getTotalAmount());
    }
}

