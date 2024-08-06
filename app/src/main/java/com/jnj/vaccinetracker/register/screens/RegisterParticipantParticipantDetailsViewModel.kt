package com.jnj.vaccinetracker.register.screens

import androidx.collection.ArrayMap
import com.jnj.vaccinetracker.R
import com.jnj.vaccinetracker.common.data.database.typealiases.yearNow
import com.jnj.vaccinetracker.common.data.helpers.delaySafe
import com.jnj.vaccinetracker.common.data.managers.ConfigurationManager
import com.jnj.vaccinetracker.common.data.managers.ParticipantManager
import com.jnj.vaccinetracker.common.data.models.IrisPosition
import com.jnj.vaccinetracker.common.di.ResourcesWrapper
import com.jnj.vaccinetracker.common.domain.entities.*
import com.jnj.vaccinetracker.common.domain.usecases.GenerateUniqueParticipantIdUseCase
import com.jnj.vaccinetracker.common.domain.usecases.GetTempBiometricsTemplatesBytesUseCase
import com.jnj.vaccinetracker.common.exceptions.NoSiteUuidAvailableException
import com.jnj.vaccinetracker.common.exceptions.OperatorUuidNotAvailableException
import com.jnj.vaccinetracker.common.exceptions.ParticipantAlreadyExistsException
import com.jnj.vaccinetracker.common.helpers.*
import com.jnj.vaccinetracker.common.ui.model.DisplayValue
import com.jnj.vaccinetracker.common.validators.NinValidator
import com.jnj.vaccinetracker.common.validators.ParticipantIdValidator
import com.jnj.vaccinetracker.common.validators.PhoneValidator
import com.jnj.vaccinetracker.common.validators.TextInputValidator
import com.jnj.vaccinetracker.common.viewmodel.ViewModelBase
import com.jnj.vaccinetracker.participantflow.model.ParticipantImageUiModel
import com.jnj.vaccinetracker.participantflow.model.ParticipantImageUiModel.Companion.toDomain
import com.jnj.vaccinetracker.participantflow.model.ParticipantSummaryUiModel
import com.jnj.vaccinetracker.register.dialogs.HomeLocationPickerViewModel
import com.jnj.vaccinetracker.sync.data.repositories.SyncSettingsRepository
import com.soywiz.klock.DateFormat
import com.soywiz.klock.DateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

@SuppressWarnings("TooManyFunctions")
class RegisterParticipantParticipantDetailsViewModel @Inject constructor(
        private val phoneValidator: PhoneValidator,
        private val syncSettingsRepository: SyncSettingsRepository,
        private val configurationManager: ConfigurationManager,
        private val resourcesWrapper: ResourcesWrapper,
        private val participantManager: ParticipantManager,
        override val dispatchers: AppCoroutineDispatchers,
        private val participantIdValidator: ParticipantIdValidator,
        private val sessionExpiryObserver: SessionExpiryObserver,
        private val getTempBiometricsTemplatesBytesUseCase: GetTempBiometricsTemplatesBytesUseCase,
        private val fullPhoneFormatter: FullPhoneFormatter,
        private val generateUniqueParticipantIdUseCase: GenerateUniqueParticipantIdUseCase,
        private val textInputValidator: TextInputValidator,
        private val ninValidator: NinValidator
) : ViewModelBase() {

    companion object {
        private const val YEAR_OF_BIRTH_MIN_VALUE = 1900
        private val YEAR_OF_BIRTH_MAX_VALUE = yearNow()
        private const val YEAR_OF_BIRTH_LENGTH = 4

        /**
         * wait this long before we validate a field while typing
         */
        private val INLINE_VALIDATION_DELAY = 2.seconds

        fun calculateAgeFromDate(birthDate: DateTime): String {
            val now = DateTime.now()
            val years = now.yearInt - birthDate.yearInt
            val months = now.month1 - birthDate.month1
            val days = now.dayOfMonth - birthDate.dayOfMonth

            val adjustedMonths = if (days < 0) months - 1 else months
            val adjustedYears = if (adjustedMonths < 0) years - 1 else years

            val totalMonths = (adjustedYears * 12) + adjustedMonths

            val daysUntilNow = now.unixMillisLong / (1000 * 60 * 60 * 24)
            val daysUntilBirthDate = birthDate.unixMillisLong / (1000 * 60 * 60 * 24)
            val totalDays = (daysUntilNow - daysUntilBirthDate).toInt()

            return when {
                totalDays < 30 -> "$totalDays days"
                totalMonths < 24 -> "$totalMonths months"
                else -> "$adjustedYears years"
            }
        }
    }

    data class Args(
            val participantId: String?,
            val isManualSetParticipantID: Boolean,
            val leftEyeScanned: Boolean,
            val rightEyeScanned: Boolean,
            val phoneNumber: String?,
    )

    private val args = stateFlow<Args?>(null)

    val registerSuccessEvents = eventFlow<ParticipantSummaryUiModel>()
    val registerFailedEvents = eventFlow<String>()
    val registerNoPhoneEvents = eventFlow<Unit>()
    val registerNoMatchingIdEvents = eventFlow<Unit>()
    val registerChildNewbornEvents = eventFlow<Unit>()
    val registerParticipantSuccessDialogEvents = eventFlow<ParticipantSummaryUiModel>()

    val loading = mutableLiveBoolean()
    val participantId = mutableLiveData<String?>()
    val scannedParticipantId = mutableLiveData<String?>()

    val isManualSetParticipantID = mutableLiveBoolean()
    val isAutoGeneratedParticipantId = mutableLiveBoolean()

    val nin = mutableLiveData<String?>()
    val ninValidationMessage = mutableLiveData<String>()

    val name = mutableLiveData<String>()
    val nameValidationMessage = mutableLiveData<String>()

    val mothersName = mutableLiveData<String>()
    val mothersNameValidationMessage = mutableLiveData<String>()

    val birthWeight = mutableLiveData<String>()
    val birthWeightValidationMessage = mutableLiveData<String>()

    val fathersName = mutableLiveData<String>()
    val fathersNameValidationMessage = mutableLiveData<String>()

    val childCategory = mutableLiveData<DisplayValue>()
    val childCategoryValidationMessage = mutableLiveData<String>()
    val childCategoryNames = mutableLiveData<List<DisplayValue>>()

    val birthDate = mutableLiveData<DateTime>()
    val birthDateText = mutableLiveData<String>()
    val birthDateValidationMessage = mutableLiveData<String>()

    val isBirthDateEstimated = mutableLiveData<Boolean>()

    val leftIrisScanned = mutableLiveBoolean()
    val rightIrisScanned = mutableLiveBoolean()
    val gender = mutableLiveData<Gender>()
    val defaultPhoneCountryCode = mutableLiveData<String>()
    private val phoneCountryCode = mutableLiveData<String>()
    val phone = mutableLiveData<String>()
    val homeLocationLabel = mutableLiveData<String>()
    private val homeLocation = mutableLiveData<Address>()
    val selectedAddressType = mutableLiveData<HomeLocationPickerViewModel.SelectedAddressModel>()
    val vaccine = mutableLiveData<DisplayValue>()
    val language = mutableLiveData<DisplayValue>()

    val participantIdValidationMessage = mutableLiveData<String>()

    val genderValidationMessage = mutableLiveData<String>()
    val phoneValidationMessage = mutableLiveData<String>()
    val homeLocationValidationMessage = mutableLiveData<String>()
    val languageValidationMessage = mutableLiveData<String>()

    val vaccineNames = mutableLiveData<List<DisplayValue>>()
    val languages = mutableLiveData<List<DisplayValue>>()
    val ninIdentifiers = mutableLiveData<NinIdentifiersList>()

    var canSkipPhone = false
    private val irisScans = ArrayMap<IrisPosition, Boolean>()

    var isChildNewbornQuestionAlreadyAsked = false
    var shouldOpenRegisterParticipantSuccessDialog = false

    private var validatePhoneJob: Job? = null
    private var validateParticipantIdJob: Job? = null

    init {
        initState()
    }

    private suspend fun load(args: Args) {
        loading.set(true)
        try {
            val config = configurationManager.getConfiguration()
            isAutoGeneratedParticipantId.value = config.isAutoGenerateParticipantId
            if (config.isAutoGenerateParticipantId) {
                isManualSetParticipantID.value = false
                participantId.value = generateUniqueParticipantIdUseCase.generateUniqueParticipantId()
            } else {
                isManualSetParticipantID.value = args.isManualSetParticipantID
                participantId.value = args.participantId
                if (!isManualSetParticipantID.value) {
                    scannedParticipantId.value = participantId.value
                }
            }

            leftIrisScanned.set(args.leftEyeScanned)
            rightIrisScanned.set(args.rightEyeScanned)
            irisScans[IrisPosition.LEFT] = args.leftEyeScanned
            irisScans[IrisPosition.RIGHT] = args.rightEyeScanned

            args.phoneNumber?.let {
                phone.set(it)
            }

            val site = syncSettingsRepository.getSiteUuid()?.let { configurationManager.getSiteByUuid(it) }
                    ?: throw NoSiteUuidAvailableException()
            val configuration = configurationManager.getConfiguration()
            val loc = configurationManager.getLocalization()
            onSiteAndConfigurationLoaded(site, configuration, loc)
            loading.set(false)
            ninIdentifiers.set(configurationManager.getNinIdentifiers())
        } catch (ex: Throwable) {
            yield()
            ex.rethrowIfFatal()
            loading.set(false)
            logError("Failed to get site by uuid: ", ex)
        }
    }

    private fun initState() {
        args.filterNotNull().distinctUntilChanged()
                .onEach { args ->
                    load(args)
                }.launchIn(scope)
    }

    fun setArguments(args: Args) {
        this.args.tryEmit(args)
    }

    private fun onSiteAndConfigurationLoaded(site: Site, configuration: Configuration, loc: TranslationMap) {

        defaultPhoneCountryCode.set(site.countryCode)
        if (phoneCountryCode.get() == null) phoneCountryCode.set(site.countryCode)

        vaccineNames.set(configuration.vaccines.map { vaccine ->
            DisplayValue(vaccine.name, loc[vaccine.name])
        })
        val categories = listOf("National", "Foreigner", "Refugee")
        val childCategoryDisplayValue = categories.map { categories ->
            DisplayValue(categories, loc[categories])
        }
        childCategoryNames.set(childCategoryDisplayValue)

        languages.set(configuration.personLanguages.map { language ->
            DisplayValue(language.name, loc[language.name]) })
    }

    private suspend fun ImageBytes.compress() = ImageHelper.compressRawImage(this, dispatchers.io)

    @SuppressWarnings("LongParameterList", "LongMethod")
    fun submitRegistration(
            picture: ParticipantImageUiModel?,
    ) {
        scope.launch {
            doRegistration(picture)
        }
    }

    private suspend fun doRegistration(
            picture: ParticipantImageUiModel?,
    ) {
        val siteUuid = syncSettingsRepository.getSiteUuid()
                ?: return logWarn("Cannot submit registration: no site UUID known")
        val homeLocation = homeLocation.get()
        val participantId = participantId.get()
        val nin = nin.get()
        logInfo("setting up birthweight")
        val birthWeight = birthWeight.get()
        val gender = gender.get()
        val birthDate = birthDate.get()
        val isBirthDateEstimated = isBirthDateEstimated.get()
        val fullPhoneNumber = createFullPhone()
        val motherName = mothersName.get()
        val fatherName = fathersName.get()
        val childName = name.get()

        val areInputsValid = validateInput(participantId, gender, birthDate, homeLocation, motherName, fatherName, childName)
        val isNinValid = isNinValueValid(nin)

        var phoneNumberToSubmit: String? = null

        //if manual entered participantId check if it is matching incoming one
        if (isManualSetParticipantID.get()) {
            logInfo("participantId: $participantId")
            registerNoMatchingIdEvents.tryEmit(Unit)
            return
        }

        // Validate the phone number input. If empty, it shows the dialog that it can be skipped.
        if (areInputsValid && phone.get().isNullOrEmpty() && !canSkipPhone) {
            registerNoPhoneEvents.tryEmit(Unit)
            return
        } else if (!phone.get().isNullOrEmpty() && !phoneValidator.validate(fullPhoneNumber)) {
            phoneValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_phone))
            return
        } else if (!phone.get().isNullOrEmpty()) {
            phoneNumberToSubmit = fullPhoneNumber
        }
        if (!areInputsValid || !isNinValid)
            return

        if (!isChildNewbornQuestionAlreadyAsked) {
            registerChildNewbornEvents.tryEmit(Unit)
            return
        }

        loading.set(true)

        try {
            val compressedImage = picture?.toDomain()?.compress()
            val biometricsTemplateBytes = getTempBiometricsTemplatesBytesUseCase.getBiometricsTemplate(irisScans)
            val result = participantManager.registerParticipant(
                participantId = participantId!!,
                nin = nin,
                birthWeight = birthWeight,
                gender = gender!!,
                birthDate = birthDate!!,
                isBirthDateEstimated = isBirthDateEstimated!!,
                telephone = phoneNumberToSubmit,
                siteUuid = siteUuid,
                language = "English",
                address = homeLocation!!,
                picture = compressedImage,
                biometricsTemplateBytes = biometricsTemplateBytes,
            )
            loading.set(false)

            val participant = ParticipantSummaryUiModel(
                                result.participantUuid,
                                participantId,
                                gender,
                                birthDate.format(DateFormat.FORMAT_DATE),
                                isBirthDateEstimated,
                                null,
                                compressedImage?.let { ParticipantImageUiModel(it.bytes) }
            )

            if (shouldOpenRegisterParticipantSuccessDialog) {
                registerParticipantSuccessDialogEvents.tryEmit(participant)
            } else {
                registerSuccessEvents.tryEmit(participant)
            }

        } catch (ex: Throwable) {
            yield()
            ex.rethrowIfFatal()
            loading.set(false)
            logError("Failed to register participant: ", ex)
            when (ex) {
                is ParticipantAlreadyExistsException -> {
                    val errorMessage = resourcesWrapper.getString(R.string.participant_registration_details_error_participant_already_exists)
                    participantIdValidationMessage.set(errorMessage)
                    registerFailedEvents.tryEmit(errorMessage)
                }

                is OperatorUuidNotAvailableException -> {
                    sessionExpiryObserver.notifySessionExpired()
                }

                else -> {
                    registerFailedEvents.tryEmit(resourcesWrapper.getString(R.string.general_label_error))
                }
            }
        }
    }

    @SuppressWarnings("LongParameterList")
    private suspend fun validateInput(
            participantId: String?,
            gender: Gender?,
            birthDate: DateTime?,
            homeLocation: Address?,
            motherName: String?,
            fatherName: String?,
            childName: String?
    ): Boolean {
        var isValid = true
        resetValidationMessages()

        if (participantId.isNullOrEmpty()) {
            isValid = false
            participantIdValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_participant_id))
        } else if (!participantIdValidator.validate(participantId)) {
            isValid = false
            participantIdValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_invalid_participant_id))
        }

        if (gender == null) {
            isValid = false
            genderValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_gender))
        }

        if (birthWeight == null ) {
            isValid = false
            birthWeightValidationMessage.set("Please enter birth weight as integer")
        }

        if (homeLocation?.isEmpty() != false) {
            isValid = false
            homeLocationValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_home_location))
        }

        if (birthDate == null) {
            isValid = false
            birthDateValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_birthday))
        }

        if (motherName.isNullOrEmpty()) {
            isValid = false
            mothersNameValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_mother_name))
        } else if (!textInputValidator.validate(motherName)) {
            isValid = false
            mothersNameValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_letters_used))
        }

        if (!fatherName.isNullOrEmpty()) {
            if (!textInputValidator.validate(fatherName)) {
                isValid = false
                fathersNameValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_letters_used))
            }
        }

        if (!childName.isNullOrEmpty()) {
            if (!textInputValidator.validate(childName)) {
                isValid = false
                nameValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_letters_used))
            }
        }

        return isValid
    }

    private fun isNinValueValid(ninValue: String?): Boolean {
        var isValid = true

        if (!ninValue.isNullOrEmpty()) {
            if (!ninValidator.validate(ninValue)) {
                isValid = false
                ninValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_nin_wrong_format))
            } else if (isNinAlreadyExist(ninValue)) {
                isValid = false
                ninValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_nin_already_exist))
            }
        }

        return isValid
    }

    private fun isNinAlreadyExist(ninValue: String?): Boolean {
        val ninIds = ninIdentifiers.get()?.map { it.identifierValue }

        if (!ninIds.isNullOrEmpty()) {
            return ninIds.any { it.equals(ninValue, ignoreCase = true) }
        }

        return false
    }

    private fun resetValidationMessages() {
        participantIdValidationMessage.set(null)
        ninValidationMessage.set(null)
        genderValidationMessage.set(null)
        birthWeightValidationMessage.set(null)
        birthDateValidationMessage.set(null)
        phoneValidationMessage.set(null)
        homeLocationValidationMessage.set(null)
        languageValidationMessage.set(null)
        mothersNameValidationMessage.set(null)
        fathersNameValidationMessage.set(null)
        nameValidationMessage.set(null)
    }

    fun setGender(gender: Gender) {
        if (this.gender.get() == gender) return
        this.gender.set(gender)
        genderValidationMessage.set(null)
    }

    fun onParticipantIdScanned(participantIdBarcode: String) {
        logInfo("onParticipantIdScanned: $participantIdBarcode")
        isManualSetParticipantID.set(false)
        scannedParticipantId.set(participantIdBarcode)
        setParticipantId(participantIdBarcode)
    }

    fun setParticipantId(participantId: String) {
        if (this.participantId.get() == participantId) return
        this.participantId.set(participantId)

        //in case we have a scanned id, and we change our id back to original scanned value, the confirm field disappears
        if (participantId == scannedParticipantId.value) {
            isManualSetParticipantID.set(false)
        }
        validateParticipantId()
    }

    fun setNin(nin: String) {
        if (this.nin.get() == nin) return
        this.nin.set(nin)
    }

    fun setMotherName(motherName: String) {
        if (this.mothersName.get() == motherName) return
        this.mothersName.set(motherName)
    }

    fun setFatherName(fatherName: String) {
        if (this.fathersName.get() == fatherName) return
        this.fathersName.set(fatherName)
    }

    fun setChildName(childName: String) {
        if (this.name.get() == childName) return
        this.name.set(childName)
    }

    fun setBirthWeight(birthWeight: String) {
        if(this.birthWeight.get() == birthWeight) return
        this.birthWeight.set(birthWeight)
    }

    private fun validateParticipantId() {
        logInfo("validateParticipantId")
        participantIdValidationMessage.set(null)
        validateParticipantIdJob?.cancel()
        validateParticipantIdJob = scope.launch {
            delaySafe(INLINE_VALIDATION_DELAY)
            val validateParticipantId = participantId.value
            if (!validateParticipantId.isNullOrEmpty() && !participantIdValidator.validate(validateParticipantId)) {
                participantIdValidationMessage.value = resourcesWrapper.getString(R.string.participant_registration_details_error_invalid_participant_id)
            }
        }
    }

    fun setBirthDate(birthDate: DateTime?, isChecked: Boolean) {
        val currentBirthDate = this.birthDate.get()
        val currentIsChecked = this.isBirthDateEstimated.get()

        if (currentBirthDate == birthDate && currentIsChecked == isChecked) return

        this.birthDate.set(birthDate)
        val formattedDate =  if (isChecked) {
            calculateAgeFromDate(birthDate!!)
        } else {
            birthDate?.format(DateFormat.FORMAT_DATE)
        }
        this.birthDateText.set(formattedDate)
        birthDateValidationMessage.set(null)
        isBirthDateEstimated.set(isChecked)
    }

    private fun createFullPhone(): String {
        val phone = phone.value ?: return ""
        val phoneCountryCode = phoneCountryCode.get() ?: return ""
        return fullPhoneFormatter.toFullPhoneNumberOrNull(phone, phoneCountryCode) ?: ""
    }

    private fun validatePhone() {
        logInfo("validatePhone")
        phoneValidationMessage.set(null)
        validatePhoneJob?.cancel()
        validatePhoneJob = scope.launch {
            delaySafe(INLINE_VALIDATION_DELAY)
            val fullPhone = createFullPhone()
            if (fullPhone.isNotEmpty() && !phoneValidator.validate(fullPhone)) {
                phoneValidationMessage.set(resourcesWrapper.getString(R.string.participant_registration_details_error_no_phone))
            }
        }
    }

    fun setPhone(phone: String) {
        if (this.phone.get() == phone) return
        this.phone.set(phone)
        validatePhone()
    }

    fun setPhoneCountryCode(selectedCountryCode: String) {
        if (phoneCountryCode.get() == selectedCountryCode) return // Break feedback loop
        phoneCountryCode.set(selectedCountryCode)
        validatePhone()
    }

    fun setSelectedChildCategory(childCategoryName: DisplayValue) {
        if (this.childCategory.get() == childCategoryName) return
        childCategory.set(childCategoryName)
        childCategoryValidationMessage.set(null)
    }

    fun setHomeLocation(homeLocation: Address, stringRepresentation: String, selectedAddressType: HomeLocationPickerViewModel.SelectedAddressModel) {
        this.homeLocation.set(homeLocation)
        this.homeLocationLabel.set(stringRepresentation)
        this.selectedAddressType.set(selectedAddressType)
        homeLocationValidationMessage.set(null)
    }
}
