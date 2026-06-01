package ch.fmartin.symphony.trello.time;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

@Singleton
public class ApplicationClock {
    @Produces
    @Singleton
    public Clock clock() {
        return systemUtc();
    }

    @SuppressWarnings("TimeZoneUsage")
    public static Clock systemUtc() {
        return Clock.systemUTC();
    }
}
