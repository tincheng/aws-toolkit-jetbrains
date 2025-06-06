// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.apache.commons.codec.digest.DigestUtils
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CustomerSelection
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.jetbrains.services.codemodernizer.model.ValidationResult
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeTransformTelemetryState
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.calculateTotalLatency
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getAuthType
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getJavaVersionFromProjectSetting
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getMavenVersion
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.tryGetJdk
import software.aws.toolkits.telemetry.CodeTransformArtifactType
import software.aws.toolkits.telemetry.CodeTransformBuildCommand
import software.aws.toolkits.telemetry.CodeTransformCancelSrcComponents
import software.aws.toolkits.telemetry.CodeTransformJavaSourceVersionsAllowed
import software.aws.toolkits.telemetry.CodeTransformJavaTargetVersionsAllowed
import software.aws.toolkits.telemetry.CodeTransformPreValidationError
import software.aws.toolkits.telemetry.CodetransformTelemetry
import software.aws.toolkits.telemetry.MetricResult
import software.aws.toolkits.telemetry.Result
import java.time.Instant
import java.util.Base64

/**
 * CodeModernizerTelemetry contains g functions for common operations that require telemetry.
 */
@Service(Service.Level.PROJECT)
class CodeTransformTelemetryManager(private val project: Project) {
    private val sessionId get() = CodeTransformTelemetryState.instance.getSessionId()

    fun initiateTransform(telemetryErrorMessage: String? = null) {
        CodetransformTelemetry.initiateTransform(
            codeTransformSessionId = sessionId,
            credentialSourceId = getAuthType(project),
            result = if (telemetryErrorMessage.isNullOrEmpty()) Result.Succeeded else Result.Failed,
            reason = telemetryErrorMessage,
        )
    }

    fun validateProject(validationResult: ValidationResult) {
        val validationError = if (validationResult.valid) {
            null
        } else {
            validationResult.invalidTelemetryReason.category ?: CodeTransformPreValidationError.Unknown
        }

        CodetransformTelemetry.validateProject(
            buildSystemVersion = validationResult.buildSystemVersion,
            codeTransformLocalJavaVersion = project.tryGetJdk().toString(),
            codeTransformPreValidationError = validationError,
            codeTransformBuildSystem = validationResult.buildSystem,
            codeTransformSessionId = sessionId,
            codeTransformMetadata = validationResult.metadata,
            result = if (validationResult.valid) Result.Succeeded else Result.Failed,
            reason = if (validationResult.valid) null else validationResult.invalidTelemetryReason.additionalInfo,
        )
    }

    fun submitSelection(userChoice: String, jobId: String? = null, customerSelection: CustomerSelection? = null, telemetryErrorMessage: String? = null) {
        CodetransformTelemetry.submitSelection(
            // TODO: remove the below 2 lines (JavaSource / JavaTarget) once BI is updated to use source / target
            codeTransformJavaSourceVersionsAllowed = CodeTransformJavaSourceVersionsAllowed.from(customerSelection?.sourceJavaVersion?.name.orEmpty()),
            codeTransformJavaTargetVersionsAllowed = CodeTransformJavaTargetVersionsAllowed.from(customerSelection?.targetJavaVersion?.name.orEmpty()),
            codeTransformSessionId = sessionId,
            codeTransformProjectId = customerSelection?.let { getProjectHash(it) },
            codeTransformJobId = jobId,
            source = if (userChoice == "Confirm-Java") customerSelection?.sourceJavaVersion?.name.orEmpty() else customerSelection?.sourceVendor.orEmpty(),
            target = if (userChoice == "Confirm-Java") customerSelection?.targetJavaVersion?.name.orEmpty() else customerSelection?.targetVendor.orEmpty(),
            userChoice = userChoice,
            result = if (telemetryErrorMessage.isNullOrEmpty()) Result.Succeeded else Result.Failed,
            reason = telemetryErrorMessage,
        )
    }

    // Replace the input as needed to support Gradle and other transformation types.
    fun localBuildProject(buildCommand: CodeTransformBuildCommand, localBuildResult: Result, telemetryErrorMessage: String?) {
        CodetransformTelemetry.localBuildProject(
            codeTransformBuildCommand = buildCommand,
            codeTransformSessionId = sessionId,
            result = localBuildResult,
            reason = if (telemetryErrorMessage.isNullOrEmpty()) null else telemetryErrorMessage,
        )
    }

    fun uploadProject(payloadSize: Int, startTime: Instant, dependenciesCopied: Boolean = false, telemetryErrorMessage: String? = null) {
        CodetransformTelemetry.uploadProject(
            codeTransformRunTimeLatency = calculateTotalLatency(startTime, Instant.now()).toLong(),
            codeTransformSessionId = sessionId,
            codeTransformTotalByteSize = payloadSize.toLong(),
            codeTransformDependenciesCopied = dependenciesCopied,
            result = if (telemetryErrorMessage.isNullOrEmpty()) Result.Succeeded else Result.Failed,
            reason = telemetryErrorMessage,
        )
    }

    fun jobStart(transformStartTime: Instant, jobId: JobId?, telemetryErrorMessage: String? = null) = CodetransformTelemetry.jobStart(
        codeTransformSessionId = sessionId,
        codeTransformJobId = jobId?.id.orEmpty(),
        codeTransformRunTimeLatency = calculateTotalLatency(transformStartTime, Instant.now()).toLong(), // subtract current time by project start time
        result = if (telemetryErrorMessage.isNullOrEmpty()) Result.Succeeded else Result.Failed,
        reason = telemetryErrorMessage,
    )

    fun downloadArtifact(
        artifactType: CodeTransformArtifactType,
        downloadStartTime: Instant,
        jobId: JobId,
        totalDownloadBytes: Int,
        telemetryErrorMessage: String?,
    ) {
        CodetransformTelemetry.downloadArtifact(
            codeTransformArtifactType = artifactType,
            codeTransformJobId = jobId.id,
            codeTransformRuntimeError = telemetryErrorMessage,
            codeTransformRunTimeLatency = calculateTotalLatency(downloadStartTime, Instant.now()).toLong(),
            codeTransformSessionId = sessionId,
            codeTransformTotalByteSize = totalDownloadBytes.toLong(),
            result = if (telemetryErrorMessage.isNullOrEmpty()) Result.Succeeded else Result.Failed,
            reason = telemetryErrorMessage,
        )
    }

    fun getProjectHash(customerSelection: CustomerSelection) = Base64.getEncoder().encodeToString(
        DigestUtils.sha256(customerSelection.configurationFile?.toNioPath()?.toAbsolutePath().toString())
    )

    /**
     * Will be the first invokation per job submission.
     * Should contain relevant initialization for proper telemetry emission.
     */
    fun prepareForNewJobSubmission() {
        CodeTransformTelemetryState.instance.setSessionId()
    }

    fun jobIsCancelledByUser(srcComponent: CodeTransformCancelSrcComponents) = CodetransformTelemetry.jobIsCancelledByUser(
        codeTransformCancelSrcComponents = srcComponent,
        codeTransformSessionId = sessionId
    )

    fun jobIsResumedAfterIdeClose(lastJobId: JobId, status: TransformationStatus) = CodetransformTelemetry.jobIsResumedAfterIdeClose(
        codeTransformSessionId = sessionId,
        codeTransformJobId = lastJobId.id,
        codeTransformStatus = status.toString()
    )

    fun totalRunTime(codeTransformResultStatusMessage: String, jobId: JobId?) = CodetransformTelemetry.totalRunTime(
        buildSystemVersion = getMavenVersion(project),
        codeTransformJobId = jobId?.toString(),
        codeTransformSessionId = sessionId,
        codeTransformResultStatusMessage = codeTransformResultStatusMessage,
        codeTransformRunTimeLatency = calculateTotalLatency(
            CodeTransformTelemetryState.instance.getStartTime(),
            Instant.now()
        ).toLong(),
        codeTransformLocalJavaVersion = getJavaVersionFromProjectSetting(project),
    )

    fun error(errorMessage: String) = CodetransformTelemetry.logGeneralError(
        reason = errorMessage,
        codeTransformSessionId = sessionId,
    )

    fun jobStatusChanged(jobId: JobId, newStatus: String, previousStatus: String) = CodetransformTelemetry.jobStatusChanged(
        codeTransformPreviousStatus = previousStatus,
        codeTransformSessionId = sessionId,
        codeTransformJobId = jobId.id,
        codeTransformStatus = newStatus
    )

    fun logHil(jobId: String, metaData: HilTelemetryMetaData, success: Boolean, reason: String) {
        CodetransformTelemetry.humanInTheLoop(
            project = project,
            codeTransformJobId = jobId,
            codeTransformMetadata = metaData.toString(),
            codeTransformSessionId = sessionId,
            reason = reason,
            result = if (success) MetricResult.Succeeded else MetricResult.Failed,
        )
    }

    companion object {
        fun getInstance(project: Project): CodeTransformTelemetryManager = project.service()
    }
}

data class HilTelemetryMetaData(
    val dependencyVersionSelected: String? = null,
    val cancelledFromChat: Boolean = false,
)
