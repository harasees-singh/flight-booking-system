package club.cred.flightbookingsystem.messaging;

import club.cred.flightbookingsystem.domain.Booking;

/** Publishes booking state-change events (to {@code booking.events}). */
public interface BookingEventPublisher {

    void bookingStateChanged(Booking booking);
}

