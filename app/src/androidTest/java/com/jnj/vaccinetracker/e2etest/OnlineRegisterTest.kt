package com.jnj.vaccinetracker.e2etest

import androidx.test.espresso.intent.Intents
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.jnj.vaccinetracker.common.data.database.ParticipantRoomDatabaseConfig
import com.jnj.vaccinetracker.common.data.helpers.AndroidFiles
import com.jnj.vaccinetracker.e2etest.helper.awaitAirplaneModeSettings
import com.jnj.vaccinetracker.e2etest.helper.context
import com.jnj.vaccinetracker.e2etest.helper.goThroughRegistrationFlow
import com.jnj.vaccinetracker.login.LoginActivity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
@LargeTest
class OnlineRegisterTest {

    @get:Rule
    //the screen to start from
    var activityRule: ActivityScenarioRule<LoginActivity> = ActivityScenarioRule(LoginActivity::class.java)

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            context.deleteDatabase(ParticipantRoomDatabaseConfig.FILE_NAME)
            AndroidFiles(context).externalFiles.deleteRecursively()
        }
    }

    @Before
    fun setUp() {
        Intents.init()
    }

    @Test
    fun testParticipantRegisterFlowSuccessOnline() = runBlocking {
        activityRule.awaitAirplaneModeSettings(false)
        goThroughRegistrationFlow(false)
    }

    @After
    fun teardown() {
        Intents.release()
    }
}
