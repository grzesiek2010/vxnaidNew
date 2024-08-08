package com.jnj.vaccinetracker.common.data.managers

import com.jnj.vaccinetracker.common.data.database.typealiases.dateNow
import com.jnj.vaccinetracker.common.data.models.Constants
import com.jnj.vaccinetracker.common.data.repositories.UserRepository
import com.jnj.vaccinetracker.common.domain.entities.*
import com.jnj.vaccinetracker.common.domain.usecases.GetPersonImageUseCase
import com.jnj.vaccinetracker.common.domain.usecases.MatchParticipantsUseCase
import com.jnj.vaccinetracker.common.domain.usecases.RegisterParticipantUseCase
import com.jnj.vaccinetracker.common.domain.usecases.UpdateParticipantUseCase
import com.jnj.vaccinetracker.common.exceptions.NoSiteUuidAvailableException
import com.jnj.vaccinetracker.common.exceptions.OperatorUuidNotAvailableException
import com.jnj.vaccinetracker.sync.data.repositories.SyncSettingsRepository
import com.soywiz.klock.DateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author maartenvangiel
 * @author druelens
 * @version 1
 */
@Singleton
class ParticipantManager @Inject constructor(
    private val matchParticipantsUseCase: MatchParticipantsUseCase,
    private val getPersonImageUseCase: GetPersonImageUseCase,
    private val registerParticipantUseCase: RegisterParticipantUseCase,
    private val updateParticipantUseCase: UpdateParticipantUseCase,
    private val userRepository: UserRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    ) {

    /**
     * Match participant based on the authentication criteria.
     * Generates the parameters for the Multipart match API call and calls it.
     */
    suspend fun matchParticipants(
        participantId: String?,
        phone: String?,
        biometricsTemplateBytes: BiometricsTemplateBytes?,
        onProgressPercentChanged: OnProgressPercentChanged = {},
    ): List<ParticipantMatch> {
        return matchParticipantsUseCase.matchParticipants(
            ParticipantIdentificationCriteria(
                participantId = participantId,
                phone = phone,
                biometricsTemplate = biometricsTemplateBytes
            ), onProgressPercentChanged
        )
    }

    /**
     * Gets the person image for a person and decodes the base64 response to a byte array
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getPersonImage(personUuid: String): ImageBytes {
        return getPersonImageUseCase.getPersonImage(personUuid) ?: error("couldn't find person image for person $personUuid")
    }

    private fun createScheduleFirstVisit(): ScheduleFirstVisit {
        val locationUuid = syncSettingsRepository.getSiteUuid() ?: throw NoSiteUuidAvailableException("Trying to register scheduled visit without a selected site")
        val operatorUUid = userRepository.getUser()?.uuid ?: throw OperatorUuidNotAvailableException("trying to register scheduled visit without stored operator uuid")
        return ScheduleFirstVisit(
            visitType = Constants.VISIT_TYPE_DOSING,
            startDatetime = dateNow(),
            locationUuid = locationUuid,
            attributes = mapOf(
                Constants.ATTRIBUTE_VISIT_STATUS to Constants.VISIT_STATUS_SCHEDULED,
                Constants.ATTRIBUTE_OPERATOR to operatorUUid,
                Constants.ATTRIBUTE_VISIT_DOSE_NUMBER to "1"
            )
        )

    }

    @SuppressWarnings("LongParameterList")
    suspend fun registerParticipant(
        participantId: String,
        nin: String?,
        birthWeight: String?,
        gender: Gender,
        birthDate: DateTime,
        isBirthDateEstimated: Boolean,
        telephone: String?,
        siteUuid: String,
        language: String,
        address: Address,
        picture: ImageBytes?,
        biometricsTemplateBytes: BiometricsTemplateBytes?,
        fatherName: String?,
        motherName: String,
        participantName: String,
        childCategory: String?,
        participantUuid: String? = null
    ): DraftParticipant {
        val operatorUUid = userRepository.getUser()?.uuid ?: throw OperatorUuidNotAvailableException("trying to register participant without stored operator uuid")

        val personAttributes = mutableMapOf(
            Constants.ATTRIBUTE_LOCATION to siteUuid,
            Constants.ATTRIBUTE_LANGUAGE to language,
            Constants.ATTRIBUTE_OPERATOR to operatorUUid,
            Constants.ATTRIBUTE_MOTHER_NAME to motherName,
            Constants.ATTRIBUTE_PARTICIPANT_NAME to participantName,
        )
        if (telephone != null) {
            personAttributes[Constants.ATTRIBUTE_TELEPHONE] = telephone
        }
        if (birthWeight != null) {
            personAttributes[Constants.ATTRIBUTE_BIRTH_WEIGHT] = birthWeight
        }
        if (fatherName != null) {
            personAttributes[Constants.ATTRIBUTE_FATHER_NAME] = fatherName
        }
        if (childCategory != null) {
            personAttributes[Constants.ATTRIBUTE_CHILD_CATEGORY] = childCategory
        }

        if (participantUuid == null) {
            val request = RegisterParticipant(
                participantId = participantId,
                nin = nin,
                gender = gender,
                isBirthDateEstimated = isBirthDateEstimated,
                birthDate = BirthDate(birthDate.unixMillisLong),
                address = address,
                attributes = personAttributes,
                image = picture,
                biometricsTemplate = biometricsTemplateBytes,
                scheduleFirstVisit = createScheduleFirstVisit()
            )
            return registerParticipantUseCase.registerParticipant(request)
        } else {
            val request = UpdateParticipant(
                participantUuid = participantUuid,
                participantId = participantId,
                nin = nin,
                gender = gender,
                isBirthDateEstimated = isBirthDateEstimated,
                birthDate = BirthDate(birthDate.unixMillisLong),
                address = address,
                attributes = personAttributes,
                image = picture,
                scheduleFirstVisit = createScheduleFirstVisit()
            )
            return updateParticipantUseCase.updateParticipant(request)
        }
    }

}