package no.g_v.googlecalendar

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import static java.time.DayOfWeek.*

/*
Code has been based on the examples found at the following URLs
https://developers.google.com/google-apps/calendar/quickstart/java
https://developers.google.com/google-apps/calendar/create-events
https://developers.google.com/drive/v2/web/handle-errors#implementing_exponential_backoff
 */

Properties properties = new Properties()
resourceAsStream('/calendar.properties').withStream {
    properties.load(it)
}

def startDate = LocalDate.parse(properties.getProperty('start.date'))
assert MONDAY == startDate.dayOfWeek

def startOnShiftWeek = properties.getProperty('start.on.shift.week') as int
assert (1..12).contains(startOnShiftWeek)

def shifts = []

(startOnShiftWeek..12).each {
    def shiftCodes = properties.getProperty("shift.week.${it}").split(',')
    assert 7 == shiftCodes.size()
    shifts.addAll(shiftCodes)
}
println "number of shifts: ${shifts.size()}, shifts:  ${shifts}"

def eventJobText = properties.getProperty('event.job.text')
def nightShiftsWeekDays = properties.getProperty('nightshifts.weekdays').split(',')
def eventPickupText = properties.getProperty('event.pickup.text')
def calendarEvents = []

shifts.eachWithIndex { shiftCode, index ->
    def summary = String.format(eventJobText, shiftCode, properties.getProperty("shift.hours.${shiftCode}"))

    def date = startDate.plusDays(index as long)
    calendarEvents << [date: zonedDateTime(date, 8), summary: summary]

    if (nightShiftsWeekDays.contains(shiftCode) && dayIsWeekDay(date)) {
        calendarEvents << [date: zonedDateTime(date, 15), summary: eventPickupText]
    }
}

println "number of events ${calendarEvents.size()}, calendarEvents:"
calendarEvents.each {
    println it
}

def calendarId = properties.getProperty('calendarid')
service = getCalendarService()

calendarEvents.forEach() {
    try {
        addEventToCalendar(calendarId, it.date as ZonedDateTime, it.summary as String)
    } catch (GoogleJsonResponseException e) {
        println e

        if (retryAddingEvent(e)) {
            sleep(3000)
            addEventToCalendar(calendarId, it.date as ZonedDateTime, it.summary as String)
        } else {
            throw e
        }
    }
}

boolean retryAddingEvent(GoogleJsonResponseException e) {
    def reason = e.getDetails().getErrors().get(0).getReason()
    e.getStatusCode() == 403 && (reason == 'rateLimitExceeded' || reason == 'userRateLimitExceeded')
}

boolean dayIsWeekDay(LocalDate date) {
    [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY].contains(date.dayOfWeek)
}

ZonedDateTime zonedDateTime(LocalDate date, Integer startHour) {
    ZonedDateTime.now()
            .withYear(date.year)
            .withMonth(date.monthValue)
            .withDayOfMonth(date.dayOfMonth)
            .withHour(startHour)
            .truncatedTo(ChronoUnit.HOURS)
}

void addEventToCalendar(String calendarId, ZonedDateTime zonedDateTime, String summary) {
    println 'Adding event to calendar'

    Event event = new Event()
            .setSummary(summary)

    def start = createEventDateTime(zonedDateTime)
    event.setStart(start)

    def end = createEventDateTime(zonedDateTime.plusHours(1))
    event.setEnd(end)

    Event.Reminders reminders = new Event.Reminders()
            .setUseDefault(true)
    event.setReminders(reminders)

    event = service.events().insert(calendarId, event).execute()
    println "Event created: ${event.getHtmlLink()}"
}

EventDateTime createEventDateTime(ZonedDateTime zonedDateTime) {
    DateTime dateTime = new DateTime(zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

    EventDateTime eventDateTime = new EventDateTime()
            .setDateTime(dateTime)
            .setTimeZone('Europe/Oslo')

    eventDateTime
}

InputStream resourceAsStream(String resource) {
    CalendarUpdater.class.getResourceAsStream(resource)
}

Calendar getCalendarService() {
    new Calendar.Builder(httpTransport(), jacksonFactory(), authorize())
            .setApplicationName('Google Calendar Updater')
            .build()
}

Credential authorize() {
    // Load client secrets.
    InputStream inputStream = resourceAsStream('/client_secret.json')
    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jacksonFactory(), new InputStreamReader(inputStream))

    // Build flow and trigger user authorization request.
    File dataStoreDir = new File(System.getProperty('user.home'), '.credentials/calendar-api-updater')

    FileDataStoreFactory fileDataStoreFactory = new FileDataStoreFactory(dataStoreDir)
    GoogleAuthorizationCodeFlow flow =
            new GoogleAuthorizationCodeFlow.Builder(httpTransport(), jacksonFactory(), clientSecrets, [CalendarScopes.CALENDAR])
                    .setDataStoreFactory(fileDataStoreFactory)
                    .setAccessType('offline')
                    .build()
    Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize('user')
    println "Credentials saved to ${dataStoreDir.getAbsolutePath()}"

    credential
}

JacksonFactory jacksonFactory() {
    JacksonFactory.getDefaultInstance()
}

HttpTransport httpTransport() {
    GoogleNetHttpTransport.newTrustedTransport()
}