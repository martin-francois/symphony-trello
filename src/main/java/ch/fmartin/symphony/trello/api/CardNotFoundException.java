package ch.fmartin.symphony.trello.api;

import jakarta.ws.rs.NotFoundException;

/// Card-lookup 404 raised by the local status API, so the exception mapper can distinguish a
/// missing Trello card from an unknown local HTTP route.
public class CardNotFoundException extends NotFoundException {
    public CardNotFoundException(String message) {
        super(message);
    }
}
