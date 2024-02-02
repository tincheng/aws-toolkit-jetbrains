// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.controller

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQAppInitContext
import software.aws.toolkits.jetbrains.services.amazonq.messages.MessagePublisher
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeModernizerManager
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeTransformTelemetryState
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.v1.ChatSessionFactoryV1
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.StaticPrompt
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.telemetry.TelemetryHelper
import software.aws.toolkits.jetbrains.services.cwc.messages.ChatMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.ChatMessageType
import software.aws.toolkits.jetbrains.services.cwc.messages.ErrorMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.IncomingCwcMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.QuickActionMessage
import software.aws.toolkits.jetbrains.services.cwc.storage.ChatSessionStorage
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodetransformTelemetry
import software.aws.toolkits.telemetry.CwsprChatCommandType
import java.util.UUID

class CodeModernizerChatController private constructor(
    private val context: AmazonQAppInitContext,
    private val chatSessionStorage: ChatSessionStorage,  // not currently used, but might need to store session later
) {

    private val messagePublisher: MessagePublisher = context.messagesFromAppToUi

    constructor(
        context: AmazonQAppInitContext,
    ) : this(
        context = context,
        chatSessionStorage = ChatSessionStorage(ChatSessionFactoryV1()),
    )

    suspend fun processTransformQuickAction(message: IncomingCwcMessage.Transform) {
        LOG.info { "Routing transform action through Code Modernization App" }
        val triggerId = UUID.randomUUID().toString()
        sendQuickActionMessage(triggerId, StaticPrompt.Transform)
        val manager = CodeModernizerManager.getInstance(context.project)
        val isActive = manager.isModernizationJobActive()
        val replyContent = if (isActive) {
            message("codemodernizer.chat.reply_job_is_running")
        } else {
            message("codemodernizer.chat.reply")
        }
        val reply = ChatMessage(
            tabId = message.tabId,
            triggerId = UUID.randomUUID().toString(),
            messageId = "",
            messageType = ChatMessageType.Answer,
            message = replyContent,
        )
        context.messagesFromAppToUi.publish(reply)
        ApplicationManager.getApplication().invokeLater {
            runInEdt {
                if (!isActive) {
                    manager.validateAndStart()
                } else {
                    manager.getBottomToolWindow().show()
                }
                CodetransformTelemetry.jobIsStartedFromChatPrompt(
                    codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
                )
            }
        }
        TelemetryHelper.recordTelemetryChatRunCommand(CwsprChatCommandType.Transform)
    }

    /*
     Not currently used, but might need in the future.
     */
    private suspend fun sendErrorMessage(tabId: String, message: String, requestId: String?) {
        val errorMessage = ErrorMessage(
            tabId = tabId,
            title = "An error occurred while processing your request.",
            message = message,
            messageId = requestId,
        )
        messagePublisher.publish(errorMessage)
    }

    private suspend fun sendQuickActionMessage(triggerId: String, prompt: StaticPrompt) {
        val message = QuickActionMessage(
            triggerId = triggerId,
            message = prompt.message,
        )
        messagePublisher.publish(message)
    }

    companion object {
        private val LOG = getLogger<CodeModernizerChatController>()

        val objectMapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}
