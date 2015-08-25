package no.g_v.googlecalendar

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
Code has been based on the examples found at the following URLs
https://developers.google.com/google-apps/calendar/quickstart/java
https://developers.google.com/google-apps/calendar/create-events
 */

Properties properties = new Properties()
resourceAsStream('/calendar.properties').withStream {
    properties.load(it)
}

def calendarEvents = []

def workweekOnly = properties.getProperty('workweek.only').split(',')
def weekendOnly = properties.getProperty('weekend.only').split(',')
def eventJobText = properties.getProperty('event.job.text')
def nightShifts = properties.getProperty('nightshifts').split(',')
def eventPickupText = properties.getProperty('event.pickup.text')

resourceAsStream('/turnus.csv').splitEachLine(',') { fields ->
    def date = LocalDate.parse(fields[0], DateTimeFormatter.ofPattern('dd.MM.yyyy'))
    def shiftCode = fields[1]

    if (shiftCodeIsValidForDay(workweekOnly, weekendOnly, date, shiftCode)) {
        def summary = String.format(eventJobText, shiftCode, properties.getProperty("shift.${shiftCode}.hours"))

        calendarEvents << [date: createZonedDateTime(date, 8), summary: summary]

        if (nightShifts.contains(shiftCode)) {
            calendarEvents << [date: createZonedDateTime(date, 15), summary: eventPickupText]
        }
    } else {
        throw new Exception("Shift code '${shiftCode}' is not valid for day ${date} [$date.dayOfWeek]")
    }
}

def calendarId = properties.getProperty('calendarid')
service = getCalendarService()

calendarEvents.forEach() {
    addEventToCalendar(calendarId, it.date, it.summary)
}

private boolean shiftCodeIsValidForDay(String[] workweekOnly, String[] weekendOnly, LocalDate date, String shiftCode) {
    def weekendDays = [DayOfWeek.SATURDAY, DayOfWeek.SUNDAY]

    boolean isWeekendDay = weekendDays.contains(date.dayOfWeek)
    boolean isWorkWeekDay = !isWeekendDay
    boolean shiftCodeIsWeekendOnly = weekendOnly.contains(shiftCode)
    boolean shiftCodeIsWorkWeekOnly = workweekOnly.contains(shiftCode)

    (isWorkWeekDay && !shiftCodeIsWeekendOnly) || (isWeekendDay && !shiftCodeIsWorkWeekOnly)
}

private ZonedDateTime createZonedDateTime(LocalDate date, Integer startHour) {
    def zonedDateTime = ZonedDateTime.now()
            .withFixedOffsetZone()
            .withYear(date.year)
            .withMonth(date.monthValue)
            .withDayOfMonth(date.dayOfMonth)
            .withHour(startHour)
            .withMinute(0)
    zonedDateTime
}

def addEventToCalendar(String calendarId, ZonedDateTime zonedDateTime, String summary) {
    println('Adding event to calendar')

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
    println("Event created: ${event.getHtmlLink()}")
}

private EventDateTime createEventDateTime(ZonedDateTime zonedDateTime) {
    DateTime dateTime = new DateTime(zonedDateTime.toString())

    EventDateTime eventDateTime = new EventDateTime()
            .setDateTime(dateTime)
            .setTimeZone("Europe/Oslo")

    eventDateTime
}

private InputStream resourceAsStream(String resource) {
    CalendarUpdater.class.getResourceAsStream(resource)
}

private Calendar getCalendarService() {
    new Calendar.Builder(httpTransport(), jacksonFactory(), authorize())
            .setApplicationName('Google Calendar Updater')
            .build()
}

private Credential authorize() {
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
    println("Credentials saved to ${dataStoreDir.getAbsolutePath()}")

    credential
}

private JacksonFactory jacksonFactory() {
    JacksonFactory.getDefaultInstance()
}

private HttpTransport httpTransport() {
    GoogleNetHttpTransport.newTrustedTransport()
}