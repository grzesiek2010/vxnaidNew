package com.jnj.vaccinetracker.common.data.repositories

import com.jnj.vaccinetracker.common.helpers.logInfo
import com.tfcporciuncula.flow.FlowSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class AutoGeneratedParticipantIdSequenceRepository @Inject constructor(private val flowSharedPreferences: FlowSharedPreferences) {

    private fun createKey(deviceName: String) = "sequence_$deviceName"

    private fun createPref(deviceName: String) = flowSharedPreferences.getInt(createKey(deviceName))

    fun storeSequence(deviceName: String, counter: Int) {
        logInfo("storeSequence: $deviceName $counter")
        createPref(deviceName).set(counter)
    }

    fun getSequence(deviceName: String): Int = createPref(deviceName).get()
}