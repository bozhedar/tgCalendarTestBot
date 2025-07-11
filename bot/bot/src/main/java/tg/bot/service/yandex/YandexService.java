package tg.bot.service.yandex;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class YandexService {


    @Value("${calendar.url}")
    private String calendarUrl;
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final int WORK_DAY_START_HOUR = 9;
    private static final int WORK_DAY_END_HOUR = 23;
    private static final int DEFAULT_DAYS_AHEAD = 7;
    private static final int SLOT_DURATION_HOURS = 1;



    public List<TimeSlot> getFreeSlots() throws CalendarException {
        try {
            List<TimeSlot> busySlots = getBusySlotsFromCalendar(calendarUrl);
            List<TimeSlot> allPossibleSlots = generateAllPossibleSlots();
            return findFreeSlots(allPossibleSlots, busySlots);
        } catch (Exception e) {
            throw new CalendarException("Failed to get free slots", e);
        }
    }

    public String printSlots() throws CalendarException {
        List<TimeSlot> freeSlots = getFreeSlots();
        if (freeSlots == null || freeSlots.isEmpty()) {
            return "Нет свободных слотов в ближайшие " + DEFAULT_DAYS_AHEAD + " дней";
        }

        // Группируем слоты по дням
        Map<LocalDate, List<TimeSlot>> slotsByDay = freeSlots.stream()
                .collect(Collectors.groupingBy(
                        slot -> slot.getStart().toLocalDate(),
                        TreeMap::new, // Сортируем дни по порядку
                        Collectors.toList()
                ));

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", new Locale("ru"));
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

        StringBuilder result = new StringBuilder();
        result.append("Свободные слоты на ближайшие ").append(DEFAULT_DAYS_AHEAD).append(" дней:\n\n");

        slotsByDay.forEach((date, slots) -> {
            // Форматируем дату (Понедельник, 15.07.2024)
            String dayHeader = date.atStartOfDay(TIME_ZONE)
                    .format(dateFormatter);
            dayHeader = dayHeader.substring(0, 1).toUpperCase() + dayHeader.substring(1);

            result.append(dayHeader).append(": ");

            // Объединяем смежные слоты
            List<String> timeRanges = mergeContinuousSlots(slots, timeFormatter);
            result.append(String.join(", ", timeRanges));

            result.append("\n\n");
        });

        return result.toString();
    }

    /**
     * Объединяет непрерывные слоты (8:00-9:00 и 9:00-10:00 -> 8:00-10:00)
     */
    private List<String> mergeContinuousSlots(List<TimeSlot> slots, DateTimeFormatter timeFormatter) {
        List<String> ranges = new ArrayList<>();
        if (slots.isEmpty()) return ranges;

        // Сортируем слоты по времени начала
        slots.sort(Comparator.comparing(TimeSlot::getStart));

        ZonedDateTime currentStart = slots.get(0).getStart();
        ZonedDateTime currentEnd = slots.get(0).getEnd();

        for (int i = 1; i < slots.size(); i++) {
            TimeSlot slot = slots.get(i);
            if (slot.getStart().equals(currentEnd)) {
                // Слоты непрерывны - расширяем текущий интервал
                currentEnd = slot.getEnd();
            } else {
                // Добавляем текущий интервал и начинаем новый
                ranges.add(formatTimeRange(currentStart, currentEnd, timeFormatter));
                currentStart = slot.getStart();
                currentEnd = slot.getEnd();
            }
        }
        // Добавляем последний интервал
        ranges.add(formatTimeRange(currentStart, currentEnd, timeFormatter));

        return ranges;
    }

    private String formatTimeRange(ZonedDateTime start, ZonedDateTime end, DateTimeFormatter timeFormatter) {
        return start.format(timeFormatter) + "-" + end.format(timeFormatter);
    }

    private List<TimeSlot> getBusySlotsFromCalendar(String calendarUrl) throws IOException, ParserException {
        List<TimeSlot> busySlots = new ArrayList<>();

        URL url = new URL(calendarUrl);
        URLConnection connection = url.openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream in = connection.getInputStream()) {
            CalendarBuilder builder = new CalendarBuilder();
            Calendar calendar = builder.build(in);

            ZonedDateTime now = ZonedDateTime.now(TIME_ZONE);
            ZonedDateTime endDate = now.plusDays(DEFAULT_DAYS_AHEAD - 1);

            for (Object component : calendar.getComponents()) {
                if (component instanceof VEvent) {
                    VEvent event = (VEvent) component;

                    Optional<Property> dtStartOpt = event.getProperty("DTSTART");
                    Optional<Property> dtEndOpt = event.getProperty("DTEND");

                    if (dtStartOpt.isPresent() && dtEndOpt.isPresent()) {
                        ZonedDateTime start = parseDate(dtStartOpt.get());
                        ZonedDateTime end = parseDate(dtEndOpt.get());

                        if (start != null && end != null && !start.isAfter(end)) {
                            // Обрезаем по рабочим часам
                            ZonedDateTime adjustedStart = adjustToWorkHours(start, true);
                            ZonedDateTime adjustedEnd = adjustToWorkHours(end, false);

                            if (adjustedStart.isBefore(adjustedEnd) ){
                                busySlots.add(new TimeSlot(adjustedStart, adjustedEnd));
                            }
                        }
                    }
                }
            }
        }
        return busySlots;
    }

    private ZonedDateTime parseDate(Property property) {
        try {
            if (property instanceof DtStart) {
                DtStart dtStart = (DtStart) property;
                return convertToZonedDateTime(dtStart);
            } else if (property instanceof DtEnd) {
                DtEnd dtEnd = (DtEnd) property;
                return convertToZonedDateTime(dtEnd);
            }
        } catch (Exception e) {
            System.err.println("Error parsing date: " + e.getMessage());
        }
        return null;
    }

    private ZonedDateTime convertToZonedDateTime(DateProperty dateProperty) {
        try {

            String dateStr = dateProperty.getValue();
            if (dateStr != null) {
                return parseIcalDateTimeString(dateStr);
            }
        } catch (Exception e) {
            System.err.println("Error converting date: " + e.getMessage());
        }
        return null;
    }

    private ZonedDateTime parseIcalDateTimeString(String dateStr) {
        try {
            // Формат: 20250101T120000 или 20250101T120000Z
            dateStr = dateStr.replace("Z", "");
            LocalDateTime ldt = LocalDateTime.parse(
                    dateStr,
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            );
            return ldt.atZone(TIME_ZONE);
        } catch (Exception e) {
            System.err.println("Error parsing date string: " + dateStr);
            return null;
        }
    }

    private List<TimeSlot> generateAllPossibleSlots() {
        List<TimeSlot> slots = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(TIME_ZONE);
        ZonedDateTime endDate = now.plusDays(DEFAULT_DAYS_AHEAD - 1);

        ZonedDateTime currentDay = now.toLocalDate().atStartOfDay(TIME_ZONE);

        while (currentDay.isBefore(endDate)) {
            ZonedDateTime dayStart = currentDay.withHour(WORK_DAY_START_HOUR).withMinute(0);
            ZonedDateTime dayEnd = currentDay.withHour(WORK_DAY_END_HOUR).withMinute(0);

            ZonedDateTime slotStart = now.isAfter(dayStart) ? now : dayStart;
            slotStart = slotStart.withMinute(0).plusHours(1); // Начинаем со следующего полного часа

            while (slotStart.isBefore(dayEnd)) {
                ZonedDateTime slotEnd = slotStart.plusHours(SLOT_DURATION_HOURS);
                if (slotEnd.isAfter(dayEnd)) break;

                slots.add(new TimeSlot(slotStart, slotEnd));
                slotStart = slotEnd;
            }

            currentDay = currentDay.plusDays(1);
        }

        return slots;
    }

    private List<TimeSlot> findFreeSlots(List<TimeSlot> allSlots, List<TimeSlot> busySlots) {
        return allSlots.stream()
                .filter(slot -> isWithinWorkHours(slot) &&
                        busySlots.stream().noneMatch(busy -> isOverlapping(slot, busy)))
                .collect(Collectors.toList());
    }

    private boolean isOverlapping(TimeSlot slot1, TimeSlot slot2) {
        return slot1.getStart().isBefore(slot2.getEnd()) &&
                slot1.getEnd().isAfter(slot2.getStart());
    }

    private boolean isWithinWorkHours(TimeSlot slot) {
        return slot.getStart().getHour() >= WORK_DAY_START_HOUR &&
                slot.getEnd().getHour() <= WORK_DAY_END_HOUR;
    }

    private ZonedDateTime adjustToWorkHours(ZonedDateTime time, boolean isStart) {
        LocalTime workStart = LocalTime.of(WORK_DAY_START_HOUR, 0);
        LocalTime workEnd = LocalTime.of(WORK_DAY_END_HOUR, 0);
        LocalTime current = time.toLocalTime();

        if (isStart) {
            if (current.isBefore(workStart)) return time.with(workStart);
            if (current.isAfter(workEnd)) return time.plusDays(1).with(workStart);
        } else {
            if (current.isAfter(workEnd)) return time.with(workEnd);
            if (current.isBefore(workStart)) return time.minusDays(1).with(workEnd);
        }
        return time;
    }

    public static class TimeSlot {
        private final ZonedDateTime start;
        private final ZonedDateTime end;

        public TimeSlot(ZonedDateTime start, ZonedDateTime end) {
            this.start = start;
            this.end = end;
        }

        public ZonedDateTime getStart() { return start; }
        public ZonedDateTime getEnd() { return end; }

        public String format() {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", new Locale("ru"));
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

            String day = start.format(dateFormatter);
            day = day.substring(0, 1).toUpperCase() + day.substring(1);

            return String.format("%s: %s - %s",
                    day,
                    start.format(timeFormatter),
                    end.format(timeFormatter));
        }
    }

    public static class CalendarException extends Exception {
        public CalendarException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}

