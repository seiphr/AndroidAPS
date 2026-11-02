package app.aaps.plugins.constraints.versionChecker

import android.os.Build
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.versionChecker.VersionDefinition
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.plugins.constraints.ConstraintsStrings
import app.aaps.plugins.constraints.ConstraintsStringsValues
import app.aaps.plugins.constraints.versionChecker.keys.VersionCheckerLongKey
import app.aaps.shared.tests.TestBase
import app.aaps.shared.tests.generatedTextResolver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class VersionCheckerNotificationsTest : TestBase() {

    @Mock lateinit var config: Config
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var preferences: Preferences

    private lateinit var versionCheckerUtils: VersionCheckerUtilsImpl
    private val currentVersion = "3.3.1"
    private val newerVersion = "3.3.2"
    private val now = T.days(100).msecs()

    @BeforeEach
    fun setup() {
        whenever(config.VERSION_NAME).thenReturn(currentVersion)
        whenever(dateUtil.now()).thenReturn(now)
        versionCheckerUtils = VersionCheckerUtilsImpl(
            aapsLogger,
            preferences,
            generatedTextResolver("constraints" to ConstraintsStringsValues::textOf),
            { config },
            dateUtil,
            notificationManager,
            VersionDefinition { buildJsonObject { put(Build.VERSION.SDK_INT.toString(), currentVersion) } }
        )
    }

    @Test
    fun `stored expiration warns even when current version matches latest`() {
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
        verify(preferences).put(VersionCheckerLongKey.LastExpiredWarning, now)
        verify(preferences, never()).put(VersionCheckerLongKey.LastVersionCheckWarning, now)
    }

    @Test
    fun `stored expiration warns when latest version is unavailable`() {
        versionCheckerUtils.definition = buildJsonObject {}
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
    }

    @Test
    fun `update and expiration notifications are both posted`() {
        versionCheckerUtils.definition = buildJsonObject { put(Build.VERSION.SDK_INT.toString(), newerVersion) }
        whenever(dateUtil.isAfterNoon()).thenReturn(true)
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.NEW_VERSION_DETECTED, ConstraintsStrings.versionavailable.withArgs(newerVersion), level = NotificationLevel.LOW)
        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
        verify(preferences).put(VersionCheckerLongKey.LastVersionCheckWarning, now)
        verify(preferences).put(VersionCheckerLongKey.LastExpiredWarning, now)
    }

    @Test
    fun `recent update notification does not delay expiration warning`() {
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1)
        whenever(preferences.get(VersionCheckerLongKey.LastVersionCheckWarning)).thenReturn(now)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
    }

    @Test
    fun `recent advance warning does not delay first expired warning`() {
        val endDate = now - 1
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(endDate)
        whenever(preferences.get(VersionCheckerLongKey.LastExpiredWarning)).thenReturn(endDate - 1)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
    }

    @Test
    fun `recent expired warning is not repeated or replaced by advance warning`() {
        whenever(dateUtil.isAfterNoon()).thenReturn(true)
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1000)
        whenever(preferences.get(VersionCheckerLongKey.LastExpiredWarning)).thenReturn(now - 1)

        versionCheckerUtils.triggerCheckVersion()

        verifyNoInteractions(notificationManager)
    }

    @Test
    fun `expired warning repeats after one day`() {
        val lastWarning = now - T.days(1).msecs() - 1
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(lastWarning - 1)
        whenever(preferences.get(VersionCheckerLongKey.LastExpiredWarning)).thenReturn(lastWarning)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
        verify(preferences).put(VersionCheckerLongKey.LastExpiredWarning, now)
    }

    @Test
    fun `first warning is not repeated on the next version check`() {
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(now - 1)

        versionCheckerUtils.triggerCheckVersion()
        verify(preferences).put(VersionCheckerLongKey.LastExpiredWarning, now)
        whenever(preferences.get(VersionCheckerLongKey.LastExpiredWarning)).thenReturn(now)
        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired)
    }

    @Test
    fun `update notification does not suppress advance expiration warning`() {
        val endDate = now + T.days(2).msecs()
        versionCheckerUtils.definition = buildJsonObject { put(Build.VERSION.SDK_INT.toString(), newerVersion) }
        whenever(dateUtil.isAfterNoon()).thenReturn(true)
        whenever(dateUtil.dateString(endDate)).thenReturn("expiration date")
        whenever(preferences.get(LongComposedKey.AppExpiration, currentVersion)).thenReturn(endDate)
        whenever(preferences.get(VersionCheckerLongKey.LastVersionCheckWarning)).thenReturn(now)

        versionCheckerUtils.triggerCheckVersion()

        verifyNotification(
            NotificationId.VERSION_EXPIRE,
            ConstraintsStrings.version_expire.withArgs(currentVersion, "expiration date"),
            level = NotificationLevel.LOW
        )
        verify(preferences).put(VersionCheckerLongKey.LastExpiredWarning, now)
        verifyNotification(NotificationId.VERSION_EXPIRE, ConstraintsStrings.application_expired, count = 0)
    }

    @Test
    fun `version without an expiration date does not warn`() {
        versionCheckerUtils.triggerCheckVersion()

        verifyNoInteractions(notificationManager)
    }

    private fun verifyNotification(id: NotificationId, text: TextRef, level: NotificationLevel = id.defaultLevel, count: Int = 1) {
        verify(notificationManager, times(count)).post(
            id = eq(id),
            textRef = eq(text),
            level = eq(level),
            validMinutes = eq(0),
            date = any(),
            validTo = eq(0L),
            sound = isNull(),
            actions = eq(emptyList()),
            validityCheck = isNull()
        )
    }
}
